package io.github.gazehighlighter;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Project-scoped registry that owns one {@link GazeEngine} per open {@link Editor} and routes
 * gaze positions to the right engine.
 *
 * <p>The mouse source already knows its editor, so it talks to {@link #engineFor(Editor)}
 * directly. The webcam source ({@link UniteyeGazeService}) only knows a <i>screen</i> pixel
 * coordinate, so it calls {@link #routeScreenPoint(int, int)}, which finds the editor under
 * that point, converts it to a logical line/column, and feeds the engine.
 */
@Service(Service.Level.PROJECT)
public final class GazeDispatcher {

    private final Map<Editor, GazeEngine> engines = new ConcurrentHashMap<>();

    /** The editor that most recently received a routed gaze point (for onExit on leave). */
    private volatile @Nullable Editor lastRoutedEditor;

    public static GazeDispatcher getInstance(Project project) {
        return project.getService(GazeDispatcher.class);
    }

    // ── Registry (called by the startup activity per editor) ──────────────────

    public GazeEngine register(Editor editor, Project project) {
        return engines.computeIfAbsent(editor, e -> new GazeEngine(e, project));
    }

    public void unregister(Editor editor) {
        GazeEngine engine = engines.remove(editor);
        if (editor.equals(lastRoutedEditor)) lastRoutedEditor = null;
        if (engine != null) engine.dispose();
    }

    public @Nullable GazeEngine engineFor(Editor editor) {
        return engines.get(editor);
    }

    // ── Screen → editor → line routing (webcam source) ────────────────────────

    /**
     * Route a screen-pixel gaze point to whichever registered editor is showing under it.
     * Must be called on the EDT (screen geometry + markup model touch Swing/editor state).
     *
     * @return {@code true} if an editor consumed the point, so the caller can stop looking
     *         in other projects.
     */
    public boolean routeScreenPoint(int screenX, int screenY) {
        Editor hit = null;
        Point local = null;

        for (Editor editor : engines.keySet()) {
            JComponent comp = editor.getContentComponent();
            if (comp == null || !comp.isShowing()) continue;
            Point origin;
            try {
                origin = comp.getLocationOnScreen();
            } catch (IllegalComponentStateException ex) {
                continue;   // not on screen right now
            }
            int lx = screenX - origin.x;
            int ly = screenY - origin.y;
            if (lx >= 0 && ly >= 0 && lx < comp.getWidth() && ly < comp.getHeight()) {
                hit   = editor;
                local = new Point(lx, ly);
                break;
            }
        }

        if (hit == null) {
            // Gaze is no longer over any editor in this project — close out the last one.
            Editor prev = lastRoutedEditor;
            if (prev != null) {
                GazeEngine engine = engines.get(prev);
                if (engine != null) engine.onExit();
                lastRoutedEditor = null;
            }
            return false;
        }

        // Gaze moved off the previously-tracked editor (e.g. another split) — close it out.
        Editor prev = lastRoutedEditor;
        if (prev != null && !prev.equals(hit)) {
            GazeEngine prevEngine = engines.get(prev);
            if (prevEngine != null) prevEngine.onExit();
        }
        lastRoutedEditor = hit;

        GazeEngine engine = engines.get(hit);
        if (engine == null) return false;

        LogicalPosition pos = hit.xyToLogicalPosition(local);
        engine.onGaze(pos.line, pos.column);
        return true;
    }
}
