package io.github.gazehighlighter;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Application-level settings for the gaze <i>input source</i>.
 *
 * <p>Chooses between mouse-as-proxy ({@link GazeInputMode#MOUSE}, the default) and the UnitEye
 * webcam eye tracker ({@link GazeInputMode#WEBCAM}, run via an embedded browser).
 *
 * <p>This is intentionally separate from {@link AiSettings} (which provider explains the
 * code) — this service is only about <i>where the gaze comes from</i>.
 */
@State(name = "ImplicitGazeInput", storages = @Storage("gaze-input.xml"))
public final class GazeInputSettings implements PersistentStateComponent<GazeInputSettings.State> {

    /** Public mutable fields so xmlb can (de)serialize them. */
    public static final class State {
        /** Stored as the enum name; read back leniently via {@link GazeInputMode#from}. */
        public String inputMode = GazeInputMode.MOUSE.name();
        /** Verify the webcam-mode plumbing with the mouse instead of a real camera (debug aid). */
        public boolean mock = false;

        // ── Webcam calibration (affine correction: raw screen px -> corrected screen px) ──
        // corrected.x = calA * raw.x + calB * raw.y + calC
        // corrected.y = calD * raw.x + calE * raw.y + calF
        // Defaults to the identity transform (no correction) until the user calibrates.
        public boolean calibrated = false;
        public double calA = 1, calB = 0, calC = 0;
        public double calD = 0, calE = 1, calF = 0;
    }

    private State state = new State();

    public static GazeInputSettings getInstance() {
        return ApplicationManager.getApplication().getService(GazeInputSettings.class);
    }

    // ── PersistentStateComponent ────────────────────────────────────────────────

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        XmlSerializerUtil.copyBean(loaded, state);
    }

    // ── Accessors ────────────────────────────────────────────────────────────────

    public GazeInputMode getInputMode() {
        return GazeInputMode.from(state.inputMode);
    }

    public void setInputMode(GazeInputMode mode) {
        state.inputMode = mode.name();
    }

    public boolean isMock() {
        return state.mock;
    }

    public void setMock(boolean mock) {
        state.mock = mock;
    }

    // ── Webcam calibration ──────────────────────────────────────────────────────

    public boolean isCalibrated() {
        return state.calibrated;
    }

    public GazeCalibration getCalibration() {
        if (!state.calibrated) return GazeCalibration.IDENTITY;
        return new GazeCalibration(state.calA, state.calB, state.calC,
                                    state.calD, state.calE, state.calF);
    }

    public void setCalibration(GazeCalibration cal) {
        state.calibrated = true;
        state.calA = cal.a; state.calB = cal.b; state.calC = cal.c;
        state.calD = cal.d; state.calE = cal.e; state.calF = cal.f;
    }

    public void clearCalibration() {
        state.calibrated = false;
        state.calA = 1; state.calB = 0; state.calC = 0;
        state.calD = 0; state.calE = 1; state.calF = 0;
    }
}
