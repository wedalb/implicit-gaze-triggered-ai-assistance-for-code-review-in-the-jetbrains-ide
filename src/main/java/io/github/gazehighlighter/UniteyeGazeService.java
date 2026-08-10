package io.github.gazehighlighter;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefMediaAccessCallback;
import org.cef.handler.CefLoadHandler.ErrorCode;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefPermissionHandler;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.Point;
import java.awt.Window;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

/**
 * Owns the embedded-browser (JCEF) instance that runs UnitEye's browser gaze pipeline
 * (webcam -> MediaPipe FaceLandmarker -> EyeMU, https://github.com/wgnrto/uniteye, GPL-3.0) and
 * feeds its gaze stream into the per-project {@link GazeDispatcher}s.
 *
 * <p>Lifecycle is driven by {@link GazeInputSettings#getInputMode()}: the browser + local HTTP
 * server run while the mode is {@link GazeInputMode#WEBCAM} and are torn down otherwise. If the
 * tracker cannot start (JCEF unsupported, camera unavailable, model/CDN load failure) or dies
 * unexpectedly, the service notifies the user and falls back to {@link GazeInputMode#MOUSE}.
 *
 * <p>{@code getUserMedia} needs a real HTTP origin (loopback counts as a secure context; {@code
 * file://}/{@code data:} do not), so the vendored web assets under {@code gaze/web/} are served
 * from a tiny loopback-only HTTP server rather than loaded directly. The browser itself is kept
 * off-screen (positioned outside the visible desktop) rather than not shown at all, so Chromium
 * still treats the page as visible and doesn't throttle the tracker's per-frame processing.
 *
 * @see #WEB_ROOT_RESOURCE the bundled web pipeline in resources/gaze/web
 */
@Service
public final class UniteyeGazeService implements Disposable {

    private static final Logger LOG = Logger.getInstance(UniteyeGazeService.class);
    private static final String WEB_ROOT_RESOURCE = "/gaze/web";
    private static final String[] WEB_ASSETS = {
            "bridge.html", "uniteye-core.js", "uniteye-cv.js", "models/eyemu.onnx"
    };

    private volatile boolean intentionalStop;
    private volatile boolean running;

    private @Nullable Path webRoot;
    private @Nullable HttpServer httpServer;
    private @Nullable JBCefBrowser browser;
    private @Nullable JWindow hostWindow;
    private @Nullable JBCefJSQuery jsQuery;

    // ── Coalescing of gaze samples onto the EDT (cap at one pending routing) ──
    private volatile int     latestX;
    private volatile int     latestY;
    private volatile boolean routePending;

    // ── Exponential smoothing of the (calibrated) gaze point, to damp per-frame jitter ──
    // Raw EyeMU output is noisy frame-to-frame even while the user holds a steady fixation;
    // smoothing trades a small amount of latency for much more stable dwell detection.
    private static final double SMOOTHING_ALPHA = 0.35;
    private volatile double smoothedX = Double.NaN;
    private volatile double smoothedY = Double.NaN;

    // ── Raw (pre-calibration, pre-smoothing) sample tap, used by GazeCalibrationDialog ──
    private volatile @Nullable Consumer<Point> rawSampleListener;

    /** Register/clear a listener that sees every raw "OK x y" sample before calibration or
     *  smoothing is applied. Used by {@link GazeCalibrationDialog} to collect fixation points. */
    public void setRawSampleListener(@Nullable Consumer<Point> listener) {
        this.rawSampleListener = listener;
    }

    public static UniteyeGazeService getInstance() {
        return ApplicationManager.getApplication().getService(UniteyeGazeService.class);
    }

    // ── Public lifecycle ──────────────────────────────────────────────────────
    //
    // Start/stop create/tear down a JCEF browser + local HTTP server and touch Swing, so the
    // EDT work inside is dispatched via invokeAndWait; the outer call is pushed to a pooled
    // thread so callers (e.g. the Settings dialog) never block, and serialized via the service
    // monitor so a restart can't overlap two camera-grabbing sessions.

    /** Start or stop the tracker to match the requested input mode. */
    public void applyMode(GazeInputMode mode) {
        runLifecycle(() -> {
            if (mode == GazeInputMode.WEBCAM) {
                start();
            } else {
                stop();
            }
        });
    }

    /** Restart the tracker (e.g. after options changed or accuracy drifted). */
    public void restart() {
        runLifecycle(() -> {
            stop();          // fully terminates the old session before the new one grabs the camera
            start();
        });
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void dispose() {
        synchronized (this) {
            stop();
        }
    }

    private void runLifecycle(Runnable body) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            synchronized (this) {
                body.run();
            }
        });
    }

    // ── Start / stop ────────────────────────────────────────────────────────────

    private synchronized void start() {
        if (running) return;
        intentionalStop = false;
        smoothedX = Double.NaN;
        smoothedY = Double.NaN;

        final Path root;
        try {
            root = ensureWebRoot();
        } catch (IOException e) {
            fallbackToMouse("Could not unpack the gaze web pipeline: " + e.getMessage());
            return;
        }
        webRoot = root;

        final HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            fallbackToMouse("Could not start the local gaze server: " + e.getMessage());
            return;
        }
        server.createContext("/", new StaticFileHandler(root));
        server.setExecutor(null);
        server.start();
        httpServer = server;

        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port + "/";
        String url = base + "bridge.html" + (GazeInputSettings.getInstance().isMock() ? "?mock=1" : "");

        // startBrowser runs synchronously on the EDT via invokeAndWait while this pooled thread
        // holds the `this` monitor (start() is synchronized). Its own synchronous failure paths
        // therefore must NOT call fallbackToMouse()/stop() (also synchronized) directly — the EDT
        // thread would block acquiring a monitor this thread holds while waiting on that very EDT
        // call, deadlocking. So it reports failure via this holder instead; only handlers that
        // fire later, on a subsequent EDT turn (after this method has returned and the monitor is
        // released), are allowed to call fallbackToMouse directly.
        String[] error = new String[1];
        try {
            ApplicationManager.getApplication().invokeAndWait(() -> error[0] = startBrowser(base, url));
        } catch (Exception e) {
            fallbackToMouse("Could not start the embedded browser: " + e.getMessage());
            return;
        }
        if (error[0] != null) {
            fallbackToMouse(error[0]);
            return;
        }

        running = true;
    }

    /** Must run on the EDT: creates the JCEF browser, its off-screen host, and wires handlers.
     *  Returns an error message on (synchronous) failure, or null on success. */
    private @Nullable String startBrowser(String originPrefix, String url) {
        if (!JBCefApp.isSupported()) {
            return "The embedded browser (JCEF) isn't available in this IDE/JBR.";
        }

        JBCefBrowser b;
        try {
            b = new JBCefBrowser(url);
        } catch (Exception e) {
            return "Could not create the embedded browser: " + e.getMessage();
        }

        // Auto-grant camera access, but only to the page we ourselves are serving.
        b.getJBCefClient().addPermissionHandler(new CefPermissionHandler() {
            @Override
            public boolean onRequestMediaAccessPermission(CefBrowser cefBrowser, CefFrame frame,
                    String requestingUrl, int requestedPermissions, CefMediaAccessCallback callback) {
                if (requestingUrl != null && requestingUrl.startsWith(originPrefix)) {
                    callback.Continue(requestedPermissions);
                } else {
                    callback.Cancel();
                }
                return true;
            }
        }, b.getCefBrowser());

        JBCefJSQuery query = JBCefJSQuery.create(b);
        query.addHandler(line -> {
            handleLine(line);
            return new JBCefJSQuery.Response(null);
        });
        jsQuery = query;

        b.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                if (!frame.isMain()) return;
                String js = "window.implicitBridge = function(msg) {" + query.inject("msg") + "};"
                        + "if (window.__implicitQueue) {"
                        + "  window.__implicitQueue.forEach(window.implicitBridge);"
                        + "  window.__implicitQueue = null;"
                        + "}";
                frame.executeJavaScript(js, frame.getURL(), 0);
            }

            @Override
            public void onLoadError(CefBrowser cefBrowser, CefFrame frame, ErrorCode errorCode,
                    String errorText, String failedUrl) {
                if (frame.isMain()) {
                    fallbackToMouse("The gaze bridge page failed to load: " + errorText);
                }
            }
        }, b.getCefBrowser());

        // Keep the browser's native peer realized and "visible" (off the desktop, not off-screen
        // rendering) so Chromium doesn't treat the page as backgrounded and throttle/pause the
        // tracker's requestAnimationFrame-driven loop.
        JWindow host = new JWindow();
        host.setType(Window.Type.UTILITY);
        host.setAlwaysOnTop(false);
        host.setBounds(-10_000, -10_000, 640, 480);
        host.getContentPane().add(b.getComponent());
        host.setVisible(true);

        browser = b;
        hostWindow = host;
        return null;
    }

    private synchronized void stop() {
        intentionalStop = true;
        running = false;
        rawSampleListener = null;

        JBCefBrowser b = browser;
        JWindow host = hostWindow;
        browser = null;
        hostWindow = null;
        jsQuery = null;
        if (b != null || host != null) {
            ApplicationManager.getApplication().invokeAndWait(() -> {
                if (b != null) b.dispose();
                if (host != null) {
                    host.setVisible(false);
                    host.dispose();
                }
            });
        }

        HttpServer server = httpServer;
        httpServer = null;
        if (server != null) server.stop(0);

        Path root = webRoot;
        webRoot = null;
        if (root != null) deleteRecursively(root);
    }

    private static void deleteRecursively(Path root) {
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
            // best-effort cleanup; the JVM-exit deleteOnExit() on the root is the fallback
        }
    }

    // ── JS -> Java gaze protocol (mirrors the plain-text contract the old sidecar used) ──

    private void handleLine(String line) {
        if (line == null) return;
        line = line.trim();
        if (line.isEmpty()) return;

        if (line.startsWith("OK ")) {
            String[] parts = line.split("\\s+");
            if (parts.length >= 3) {
                try {
                    int rawX = Integer.parseInt(parts[1]);
                    int rawY = Integer.parseInt(parts[2]);

                    Consumer<Point> tap = rawSampleListener;
                    if (tap != null) tap.accept(new Point(rawX, rawY));

                    Point corrected = GazeInputSettings.getInstance().getCalibration().apply(rawX, rawY);
                    Point smoothed  = smooth(corrected.x, corrected.y);
                    queueRoute(smoothed.x, smoothed.y);
                } catch (NumberFormatException ignored) { /* skip malformed sample */ }
            }
        } else if (line.equals("READY")) {
            notifyUser("Webcam gaze tracking is active.", NotificationType.INFORMATION);
        } else if (line.startsWith("ERR ")) {
            fallbackToMouse(line.substring(4));
        }
        // "BAD" (no face this frame) is intentionally ignored.
    }

    /** Exponential moving average in screen-pixel space; resets (snaps instead of drifting) on start/stop. */
    private Point smooth(int x, int y) {
        if (Double.isNaN(smoothedX)) {
            smoothedX = x;
            smoothedY = y;
        } else {
            smoothedX += SMOOTHING_ALPHA * (x - smoothedX);
            smoothedY += SMOOTHING_ALPHA * (y - smoothedY);
        }
        return new Point((int) Math.round(smoothedX), (int) Math.round(smoothedY));
    }

    private void queueRoute(int x, int y) {
        latestX = x;
        latestY = y;
        if (routePending) return;
        routePending = true;
        ApplicationManager.getApplication().invokeLater(() -> {
            routePending = false;
            routeToProjects(latestX, latestY);
        });
    }

    private void routeToProjects(int x, int y) {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed()) continue;
            GazeDispatcher dispatcher = GazeDispatcher.getInstance(project);
            if (dispatcher != null && dispatcher.routeScreenPoint(x, y)) {
                return;   // first editor to consume the point wins
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Extract the bundled web pipeline to a temp directory once per session. */
    private Path ensureWebRoot() throws IOException {
        Path dir = Files.createTempDirectory("implicit-gaze-web");
        for (String asset : WEB_ASSETS) {
            Path dest = dir.resolve(asset);
            Files.createDirectories(dest.getParent());
            try (InputStream in = UniteyeGazeService.class.getResourceAsStream(WEB_ROOT_RESOURCE + "/" + asset)) {
                if (in == null) throw new IOException("bundled resource " + asset + " not found");
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        dir.toFile().deleteOnExit();
        return dir;
    }

    /** Show the message, flip the persisted mode back to MOUSE, and tear the tracker down. */
    private void fallbackToMouse(String message) {
        LOG.warn("Webcam gaze unavailable: " + message);
        GazeInputSettings.getInstance().setInputMode(GazeInputMode.MOUSE);
        notifyUser("Webcam gaze unavailable — falling back to mouse. " + message,
                NotificationType.WARNING);
        stop();
    }

    private void notifyUser(String content, NotificationType type) {
        if (type == NotificationType.WARNING) GazeNotify.warn(null, content);
        else GazeNotify.info(null, content);
    }

    // ── Static file server for the vendored web pipeline (loopback only) ──────────

    private static final class StaticFileHandler implements HttpHandler {
        private final Path root;

        StaticFileHandler(Path root) {
            this.root = root;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/") || path.isEmpty()) path = "/bridge.html";

                Path resolved = root.resolve(path.substring(1)).normalize();
                if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
                    byte[] body = "not found".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(404, body.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
                    return;
                }

                byte[] bytes = Files.readAllBytes(resolved);
                exchange.getResponseHeaders().set("Content-Type", contentType(resolved.toString()));
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            } finally {
                exchange.close();
            }
        }

        private static String contentType(String path) {
            if (path.endsWith(".html")) return "text/html; charset=utf-8";
            if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
            if (path.endsWith(".onnx")) return "application/octet-stream";
            return "application/octet-stream";
        }
    }
}
