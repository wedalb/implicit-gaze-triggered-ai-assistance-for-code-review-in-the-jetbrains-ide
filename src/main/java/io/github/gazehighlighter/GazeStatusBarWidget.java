package io.github.gazehighlighter;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.CustomStatusBarWidget;
import com.intellij.openapi.wm.StatusBar;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Status-bar widget showing:
 *   ● Implicit: Reading      (green dot)
 *   ● Implicit: Explaining   (blue bold)
 *   ● REC                    (red, appended while a gaze recording session is active)
 *
 * Clicking it opens a popup: start/stop recording, export the session, or view the reading
 * report — the actions are the same ones registered under Tools → Implicit Gaze Recording.
 */
public class GazeStatusBarWidget implements CustomStatusBarWidget {

    public static final String ID = "Implicit";

    private static final String GREEN_HEX = "#32C864";
    private static final String ACCENT_HEX = "#5AAFFF";
    private static final String REC_HEX = "#E5484D";

    private final Project project;
    private final JLabel  label;

    private GazeMode currentMode = GazeMode.READING;

    public GazeStatusBarWidget(Project project) {
        this.project = project;

        label = new JLabel();
        label.setOpaque(false);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        label.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        label.setToolTipText("Click for Implicit actions (recording, export, reading report)");

        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                showPopup();
            }
            @Override
            public void mouseEntered(MouseEvent e) {
                label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }
            @Override
            public void mouseExited(MouseEvent e) {
                label.setCursor(Cursor.getDefaultCursor());
            }
        });

        refresh(GazeMode.READING);
    }

    void refresh(GazeMode mode) {
        this.currentMode = mode;
        SwingUtilities.invokeLater(this::repaint);
    }

    /** Called by {@link GazeRecordingService} (via its on-change listener) when recording starts/stops. */
    void refreshRecording() {
        SwingUtilities.invokeLater(this::repaint);
    }

    private void repaint() {
        String modeHtml = currentMode == GazeMode.EXPLAINING
                ? "<b><font color='" + ACCENT_HEX + "'>" + currentMode.label + "</font></b>"
                : currentMode.label;
        boolean recording = GazeRecordingService.getInstance(project).isRecording();
        String recHtml = recording
                ? "&nbsp;&nbsp;<font color='" + REC_HEX + "'>&#9679;&nbsp;<b>REC</b></font>" : "";
        label.setText("<html><font color='" + GREEN_HEX + "'>&#9679;</font>"
                + "&nbsp;<b>Implicit:</b>&nbsp;" + modeHtml + recHtml + "</html>");
    }

    private void showPopup() {
        ActionManager am = ActionManager.getInstance();
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(am.getAction("io.github.gazehighlighter.StartRecording"));
        group.add(am.getAction("io.github.gazehighlighter.StopRecording"));
        group.addSeparator();
        group.add(am.getAction("io.github.gazehighlighter.ExportSession"));
        group.add(am.getAction("io.github.gazehighlighter.ReadReport"));
        group.addSeparator();
        group.add(new AnAction("Current File Coverage…") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                GazeModeService svc = GazeModeService.getInstance(project);
                if (svc != null) svc.showSummary();
            }
        });

        am.createActionPopupMenu(ActionPlaces.STATUS_BAR_PLACE, group)
                .getComponent().show(label, 0, -label.getHeight());
    }

    // ── StatusBarWidget ───────────────────────────────────────────────────────

    @Override public @NotNull String ID() { return ID; }
    @Override public @Nullable WidgetPresentation getPresentation() { return null; }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        GazeModeService svc = GazeModeService.getInstance(project);
        if (svc != null) svc.registerWidget(this);
        GazeRecordingService.getInstance(project).setOnChangeListener(this::refreshRecording);
    }

    @Override
    public void dispose() {
        GazeModeService svc = GazeModeService.getInstance(project);
        if (svc != null) svc.registerWidget(null);
        GazeRecordingService.getInstance(project).setOnChangeListener(null);
    }

    @Override public @NotNull JComponent getComponent() { return label; }
}
