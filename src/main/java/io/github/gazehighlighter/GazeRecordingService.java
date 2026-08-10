package io.github.gazehighlighter;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Project-scoped recording of {@link GazeEvent}s — the "record a session, then export it" half
 * of Implicit, independent of (and running alongside) the reading/dwell/AI-explanation pipeline.
 *
 * <p>{@link GazeEngine} calls {@link #record} once per line visit whenever a session is active;
 * this service just buffers those events until {@link #stop()} freezes them into a
 * {@link GazeSession} for {@link GazeSessionExporter}.
 */
@Service(Service.Level.PROJECT)
public final class GazeRecordingService {

    private final Project project;

    private volatile boolean recording = false;
    private volatile long    startMs   = 0;
    private final List<GazeEvent> events = new CopyOnWriteArrayList<>();

    /** The most recently stopped session, kept around so Export can be a separate, later step. */
    private volatile @Nullable GazeSession lastSession;

    /** Set by the status bar widget so it can repaint on start/stop; cleared on dispose. */
    private @Nullable Runnable onChange;

    public GazeRecordingService(Project project) {
        this.project = project;
    }

    public static GazeRecordingService getInstance(Project project) {
        return project.getService(GazeRecordingService.class);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────

    public synchronized void start() {
        if (recording) return;
        events.clear();
        startMs   = System.currentTimeMillis();
        recording = true;
        fireChange();
    }

    /** Stops recording and returns an immutable snapshot of what was captured. */
    public synchronized GazeSession stop() {
        recording = false;
        GazeSession session = new GazeSession(project.getName(), startMs,
                System.currentTimeMillis(), new ArrayList<>(events));
        lastSession = session;
        fireChange();
        return session;
    }

    public boolean isRecording() {
        return recording;
    }

    /** The most recently stopped session (survives after {@link #stop()} returns), or
     *  {@code null} if nothing has been recorded yet this IDE session. */
    public @Nullable GazeSession getLastSession() {
        return lastSession;
    }

    /** Whether there is a session worth exporting/reporting on — recording now, or already stopped. */
    public boolean hasData() {
        return recording || lastSession != null;
    }

    /** Stops an in-progress recording and returns it, or the last stopped session otherwise.
     *  Shared by Export/Read-report, which both treat "act on this" the same way. */
    public synchronized @Nullable GazeSession finishedSession() {
        return recording ? stop() : lastSession;
    }

    long getStartMs() {
        return startMs;
    }

    /** A live snapshot without stopping the recording (e.g. to show "N events so far"). */
    public int eventCount() {
        return events.size();
    }

    // ── Recording ────────────────────────────────────────────────────────────────

    void record(GazeEvent event) {
        if (recording) events.add(event);
    }

    // ── Widget hookup ────────────────────────────────────────────────────────────

    void setOnChangeListener(@Nullable Runnable listener) {
        this.onChange = listener;
    }

    private void fireChange() {
        Runnable r = onChange;
        if (r != null) ApplicationManager.getApplication().invokeLater(r);
    }
}
