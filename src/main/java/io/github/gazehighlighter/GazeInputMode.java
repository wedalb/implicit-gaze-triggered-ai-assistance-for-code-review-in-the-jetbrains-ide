package io.github.gazehighlighter;

/** Where gaze positions come from. */
public enum GazeInputMode {
    /** Mouse motion is used as a proxy for gaze (the original CodeGRITS-style behaviour). */
    MOUSE("Mouse"),
    /** Real gaze from UnitEye's browser gaze pipeline (webcam -> MediaPipe -> EyeMU), via JCEF. */
    WEBCAM("Webcam eye tracker");

    public final String label;

    GazeInputMode(String label) {
        this.label = label;
    }

    /** Lenient parse used by the persisted setting; unknown values fall back to MOUSE. */
    public static GazeInputMode from(String name) {
        if (name != null) {
            for (GazeInputMode m : values()) {
                if (m.name().equalsIgnoreCase(name)) return m;
            }
        }
        return MOUSE;
    }
}
