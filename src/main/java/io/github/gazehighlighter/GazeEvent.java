package io.github.gazehighlighter;

/**
 * One recorded "visit" to a line of code — the unit of data a {@link GazeRecordingService}
 * session is made of.
 *
 * <p>Rather than logging every raw gaze/mouse sample (which for mouse mode would fire on every
 * pixel of motion), one event is recorded per line visit, at the point the gaze leaves that
 * line — mirroring {@link GazeEngine}'s own dwell accounting ({@link CoverageTracker#flush}).
 * That keeps recordings small while still capturing exactly what a heatmap/replay needs: which
 * line, for how long, whether it triggered an AI explanation, and what word was most focused on.
 */
public final class GazeEvent {

    /** Epoch millis when this visit ended (gaze moved to another line / left the editor). */
    public final long timestampMs;
    /** Millis since the recording started. */
    public final long elapsedMs;

    public final String filePath;   // project-relative when possible, else absolute
    public final int    line;       // 1-based, for human-readable export
    public final int    column;     // 0-based caret column within the line at the time
    public final long   dwellMs;    // total time spent on this line during this visit
    public final String lineText;   // snippet of the line's source, truncated
    public final String word;       // most gaze-focused word on the line during this visit (nullable)
    public final boolean explained; // did the 3s dwell fire (AI explanation triggered) this visit?
    public final GazeInputMode source; // MOUSE or WEBCAM, as configured when this event was recorded

    public GazeEvent(long timestampMs, long elapsedMs, String filePath, int line, int column,
                      long dwellMs, String lineText, String word, boolean explained,
                      GazeInputMode source) {
        this.timestampMs = timestampMs;
        this.elapsedMs   = elapsedMs;
        this.filePath    = filePath;
        this.line        = line;
        this.column      = column;
        this.dwellMs     = dwellMs;
        this.lineText    = lineText;
        this.word        = word;
        this.explained   = explained;
        this.source      = source;
    }
}
