package io.github.gazehighlighter;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;

import javax.swing.JWindow;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * A 9-point on-screen calibration flow for the webcam eye tracker.
 *
 * <p>UnitEye's bundled model has no calibration of its own, so its raw output is typically
 * biased (off-center, wrong scale for the monitor) rather than merely noisy. This walks the
 * user through fixating on nine known screen points, taps {@link UniteyeGazeService}'s raw
 * (pre-correction) gaze stream while they do, and fits a {@link GazeCalibration} affine
 * correction from the results via {@link GazeCalibration#fit}.
 *
 * <p>Entirely event-driven (a repeating {@link Timer} tick on the EDT) so it doesn't block the
 * Settings dialog that launches it.
 */
final class GazeCalibrationDialog {

    private static final int WAIT_MS    = 700;   // let the user saccade to the new target and settle
    private static final int COLLECT_MS = 900;   // then gather samples while they hold the fixation
    private static final int MIN_VALID_POINTS = 5;

    // Fractions of the screen — corners, edge midpoints, and center.
    private static final double[][] TARGETS = {
        {0.08, 0.08}, {0.5, 0.08}, {0.92, 0.08},
        {0.08, 0.5},  {0.5, 0.5},  {0.92, 0.5},
        {0.08, 0.92}, {0.5, 0.92}, {0.92, 0.92},
    };

    private enum Phase { WAIT, COLLECT }

    private final Rectangle bounds;
    private final JWindow   window;
    private final TargetPanel panel;
    private final Timer     timer;

    private int    pointIndex   = 0;
    private Phase  phase        = Phase.WAIT;
    private long   phaseStartMs = 0;
    private final List<double[]> currentSamples = new ArrayList<>();   // raw [x, y] this point
    private final List<GazeCalibration.Sample> collected = new ArrayList<>();

    static void open() {
        new GazeCalibrationDialog().start();
    }

    private GazeCalibrationDialog() {
        bounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().getBounds();

        panel  = new TargetPanel();
        window = new JWindow();
        window.setBounds(bounds);
        window.setAlwaysOnTop(true);
        window.getContentPane().add(panel, BorderLayout.CENTER);

        timer = new Timer(30, e -> tick());
    }

    private void start() {
        window.setVisible(true);
        phase = Phase.WAIT;
        phaseStartMs = System.currentTimeMillis();
        UniteyeGazeService.getInstance().setRawSampleListener(p -> {
            if (phase == Phase.COLLECT) {
                synchronized (currentSamples) {
                    currentSamples.add(new double[]{ p.x, p.y });
                }
            }
        });
        panel.repaint();
        timer.start();
    }

    private void tick() {
        long elapsed = System.currentTimeMillis() - phaseStartMs;

        if (phase == Phase.WAIT && elapsed >= WAIT_MS) {
            phase = Phase.COLLECT;
            phaseStartMs = System.currentTimeMillis();
            synchronized (currentSamples) { currentSamples.clear(); }
            panel.repaint();
            return;
        }

        if (phase == Phase.COLLECT && elapsed >= COLLECT_MS) {
            finishPoint();
        } else {
            panel.repaint();   // animate the countdown ring
        }
    }

    private void finishPoint() {
        double[] target = TARGETS[pointIndex];
        double tx = bounds.x + target[0] * bounds.width;
        double ty = bounds.y + target[1] * bounds.height;

        synchronized (currentSamples) {
            if (!currentSamples.isEmpty()) {
                double sx = 0, sy = 0;
                for (double[] s : currentSamples) { sx += s[0]; sy += s[1]; }
                int n = currentSamples.size();
                collected.add(new GazeCalibration.Sample(sx / n, sy / n, tx, ty));
            }
            currentSamples.clear();
        }

        pointIndex++;
        if (pointIndex >= TARGETS.length) {
            finish();
        } else {
            phase = Phase.WAIT;
            phaseStartMs = System.currentTimeMillis();
            panel.repaint();
        }
    }

    private void finish() {
        timer.stop();
        UniteyeGazeService.getInstance().setRawSampleListener(null);
        window.setVisible(false);
        window.dispose();

        int valid = collected.size();
        if (valid < MIN_VALID_POINTS) {
            notify("Calibration failed — only " + valid + "/" + TARGETS.length + " points saw a face. "
                    + "Check lighting and camera framing, then try again.", NotificationType.WARNING);
            return;
        }

        GazeCalibration fit = GazeCalibration.fit(collected);
        if (fit == null) {
            notify("Calibration failed — the collected points were too close together to fit. Try again.",
                    NotificationType.WARNING);
            return;
        }

        GazeInputSettings.getInstance().setCalibration(fit);
        notify("Calibration saved (" + valid + "/" + TARGETS.length + " points). Webcam gaze should "
                + "now track more accurately.", NotificationType.INFORMATION);
    }

    private void notify(String message, NotificationType type) {
        if (type == NotificationType.WARNING) GazeNotify.warn(null, message);
        else GazeNotify.info(null, message);
    }

    // ── Painting ──────────────────────────────────────────────────────────────

    private final class TargetPanel extends JComponent {
        TargetPanel() {
            setOpaque(true);
            setPreferredSize(bounds.getSize());

            JLabel hint = new JLabel(
                    "Look at the dot and hold your gaze steady   •   Esc to cancel",
                    SwingConstants.CENTER);
            hint.setForeground(Color.LIGHT_GRAY);
            hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 14f));
            setLayout(new BorderLayout());
            add(hint, BorderLayout.SOUTH);

            setFocusable(true);
            addKeyListener(new java.awt.event.KeyAdapter() {
                @Override public void keyPressed(java.awt.event.KeyEvent e) {
                    if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE) {
                        timer.stop();
                        UniteyeGazeService.getInstance().setRawSampleListener(null);
                        window.setVisible(false);
                        window.dispose();
                    }
                }
            });
            ApplicationManager.getApplication().invokeLater(this::requestFocusInWindow);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, getWidth(), getHeight());

            if (pointIndex >= TARGETS.length) return;

            double[] target = TARGETS[pointIndex];
            int cx = (int) (target[0] * getWidth());
            int cy = (int) (target[1] * getHeight());

            long elapsed = System.currentTimeMillis() - phaseStartMs;
            int phaseLen = phase == Phase.WAIT ? WAIT_MS : COLLECT_MS;
            double progress = Math.min(1.0, elapsed / (double) phaseLen);

            Color dotColor = phase == Phase.WAIT ? new Color(90, 175, 255) : new Color(255, 90, 90);
            int r = 10;
            g.setColor(dotColor);
            g.fillOval(cx - r, cy - r, r * 2, r * 2);

            // Countdown ring — shrinks during WAIT, grows during COLLECT.
            int ringR = 22;
            g.setStroke(new java.awt.BasicStroke(3));
            g.setColor(new Color(255, 255, 255, 140));
            int startAngle = 90;
            int arc = (int) Math.round(360 * (phase == Phase.WAIT ? (1 - progress) : progress));
            g.drawArc(cx - ringR, cy - ringR, ringR * 2, ringR * 2, startAngle, -arc);

            g.setColor(Color.WHITE);
            g.setFont(g.getFont().deriveFont(Font.BOLD, 13f));
            String label = "Point " + (pointIndex + 1) + " / " + TARGETS.length;
            g.drawString(label, cx - g.getFontMetrics().stringWidth(label) / 2, cy - 34);
        }
    }
}
