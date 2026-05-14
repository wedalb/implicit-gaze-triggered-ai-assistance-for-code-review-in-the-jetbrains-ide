package io.github.gazehighlighter;

public enum GazeMode {
    READING("Reading"),
    EXPLAINING("Explaining");

    public final String label;

    GazeMode(String label) {
        this.label = label;
    }
}
