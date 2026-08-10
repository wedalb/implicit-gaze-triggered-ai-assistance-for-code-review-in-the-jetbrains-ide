package io.github.gazehighlighter;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Settings UI under <b>Settings → Tools → Implicit Gaze</b>.
 *
 * <p>Chooses the gaze input source (mouse proxy vs. the UnitEye webcam eye tracker) and
 * configures it. Edits are committed to {@link GazeInputSettings} only on {@link #apply()},
 * which also starts/stops {@link UniteyeGazeService} to match.
 */
public final class GazeInputConfigurable implements Configurable {

    private JPanel root;

    private JBRadioButton mouseRadio;
    private JBRadioButton webcamRadio;
    private JBCheckBox     mockCheck;
    private JButton        restartButton;
    private JButton        calibrateButton;
    private JBLabel        statusLabel;
    private JBLabel        calibrationLabel;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Implicit Gaze";
    }

    @Override
    public @Nullable JComponent createComponent() {
        mouseRadio  = new JBRadioButton("Mouse (dwell as a gaze proxy)");
        webcamRadio = new JBRadioButton("Webcam eye tracker (UnitEye)");
        ButtonGroup group = new ButtonGroup();
        group.add(mouseRadio);
        group.add(webcamRadio);

        mockCheck = new JBCheckBox("Verify without a camera (drive the tracker with the mouse instead)");

        restartButton = new JButton("Restart tracker");
        restartButton.addActionListener(e -> {
            // Persist current edits first so the tracker launches with the chosen options.
            apply();
            UniteyeGazeService.getInstance().restart();
        });

        calibrateButton = new JButton("Calibrate…");
        calibrateButton.addActionListener(e -> {
            apply();   // make sure the tracker is running with the current settings first
            if (!UniteyeGazeService.getInstance().isRunning()) {
                JOptionPane.showMessageDialog(root,
                        "The webcam tracker isn't running — check camera permission and try Apply first.",
                        "Implicit Gaze", JOptionPane.WARNING_MESSAGE);
                return;
            }
            GazeCalibrationDialog.open();
        });

        statusLabel      = new JBLabel();
        calibrationLabel = new JBLabel();
        calibrationLabel.setForeground(JBColor.GRAY);

        // Webcam-only fields toggle with the selected source.
        Runnable syncEnabled = () -> {
            boolean webcam = webcamRadio.isSelected();
            mockCheck.setEnabled(webcam);
            restartButton.setEnabled(webcam);
            calibrateButton.setEnabled(webcam);
            refreshStatus();
        };
        mouseRadio.addActionListener(e -> syncEnabled.run());
        webcamRadio.addActionListener(e -> syncEnabled.run());

        JPanel restartRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        restartRow.add(restartButton, BorderLayout.WEST);
        restartRow.add(statusLabel, BorderLayout.CENTER);

        JPanel calibrateRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        calibrateRow.add(calibrateButton, BorderLayout.WEST);
        calibrateRow.add(calibrationLabel, BorderLayout.CENTER);

        root = FormBuilder.createFormBuilder()
                .addComponent(new JBLabel("Gaze input source:"))
                .addComponent(mouseRadio)
                .addComponent(webcamRadio)
                .addComponentToRightColumn(new JBLabel(
                        "Runs UnitEye's browser gaze pipeline (webcam → MediaPipe → EyeMU) in a "
                        + "hidden embedded browser, smoothed and bias-corrected with the calibration "
                        + "below. Needs camera permission and internet access on first use (downloads "
                        + "the ONNX/MediaPipe runtime)."))
                .addSeparator()
                .addComponent(mockCheck)
                .addComponent(restartRow)
                .addComponent(calibrateRow)
                .addComponentToRightColumn(new JBLabel(
                        "Tracking starts in the background and maps your gaze to the code line "
                        + "you are reading. Restart if accuracy drifts or after changing options. "
                        + "Calibrate walks through 9 on-screen points to correct the raw model's bias — "
                        + "do this once per session/lighting setup for best accuracy."))
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();

        reset();
        return root;
    }

    private void refreshStatus() {
        if (statusLabel == null) return;
        if (!webcamRadio.isSelected()) {
            statusLabel.setText("");
            calibrationLabel.setText("");
            return;
        }
        statusLabel.setText(UniteyeGazeService.getInstance().isRunning()
                ? "Tracker running." : "Tracker not running — Apply to start.");
        calibrationLabel.setText(GazeInputSettings.getInstance().isCalibrated()
                ? "Calibrated." : "Not calibrated — using raw (uncorrected) gaze.");
    }

    // ── Configurable contract ────────────────────────────────────────────────────

    @Override
    public boolean isModified() {
        GazeInputSettings s = GazeInputSettings.getInstance();
        return selectedMode() != s.getInputMode()
                || mockCheck.isSelected() != s.isMock();
    }

    @Override
    public void apply() {
        GazeInputSettings s = GazeInputSettings.getInstance();
        GazeInputMode mode = selectedMode();
        s.setInputMode(mode);
        s.setMock(mockCheck.isSelected());

        // Start or stop the tracker to match the (now persisted) settings.
        UniteyeGazeService.getInstance().applyMode(mode);
        refreshStatus();
    }

    @Override
    public void reset() {
        GazeInputSettings s = GazeInputSettings.getInstance();
        boolean webcam = s.getInputMode() == GazeInputMode.WEBCAM;
        mouseRadio.setSelected(!webcam);
        webcamRadio.setSelected(webcam);
        mockCheck.setSelected(s.isMock());

        mockCheck.setEnabled(webcam);
        restartButton.setEnabled(webcam);
        calibrateButton.setEnabled(webcam);
        refreshStatus();
    }

    private GazeInputMode selectedMode() {
        return webcamRadio.isSelected() ? GazeInputMode.WEBCAM : GazeInputMode.MOUSE;
    }
}
