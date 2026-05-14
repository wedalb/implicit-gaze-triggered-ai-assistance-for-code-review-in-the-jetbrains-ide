package io.github.gazehighlighter;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;

/** Project-scoped service: current gaze mode + active coverage tracker. */
@Service(Service.Level.PROJECT)
public final class GazeModeService {

    private final Project project;

    private GazeMode                   currentMode   = GazeMode.READING;
    private @Nullable GazeStatusBarWidget widget;
    private @Nullable CoverageTracker    activeTracker;

    public GazeModeService(Project project) {
        this.project = project;
    }

    public static GazeModeService getInstance(Project project) {
        return project.getService(GazeModeService.class);
    }

    // ── Mode ──────────────────────────────────────────────────────────────────

    public GazeMode getMode() { return currentMode; }

    public void setMode(GazeMode mode) {
        if (currentMode == mode) return;
        currentMode = mode;
        if (widget != null) widget.refresh(mode);
    }

    // ── Widget ────────────────────────────────────────────────────────────────

    void registerWidget(GazeStatusBarWidget w) {
        this.widget = w;
        if (w != null) w.refresh(currentMode);
    }

    // ── Coverage tracker (set by active editor's listener) ────────────────────

    public void setActiveTracker(CoverageTracker tracker) {
        this.activeTracker = tracker;
    }

    // ── Summary — called from status-bar click (EDT) ──────────────────────────

    public void showSummary() {
        // Must run on EDT; the click listener already ensures this, but be safe
        ApplicationManager.getApplication().invokeLater(() -> {
            int read      = activeTracker != null ? activeTracker.getReadCount()      : 0;
            int total     = activeTracker != null ? activeTracker.getTotalLines()     : 0;
            int heavy     = activeTracker != null ? activeTracker.getHeavyCount()     : 0;
            int explained = activeTracker != null ? activeTracker.getExplainedCount() : 0;
            var hotspots  = activeTracker != null ? activeTracker.getHotspots() : Collections.<CoverageTracker.Hotspot>emptyList();

            new ReadingReportDialog(project, read, total, heavy, explained, hotspots).show();
        });
    }
}
