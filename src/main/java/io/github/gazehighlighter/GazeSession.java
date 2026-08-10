package io.github.gazehighlighter;

import java.util.List;

/** An immutable snapshot of a finished (or in-progress) recording, ready to export. */
public final class GazeSession {
    public final String projectName;
    public final long   startMs;
    public final long   endMs;
    public final List<GazeEvent> events;

    public GazeSession(String projectName, long startMs, long endMs, List<GazeEvent> events) {
        this.projectName = projectName;
        this.startMs     = startMs;
        this.endMs       = endMs;
        this.events      = events;
    }

    public long durationMs() {
        return Math.max(0, endMs - startMs);
    }
}
