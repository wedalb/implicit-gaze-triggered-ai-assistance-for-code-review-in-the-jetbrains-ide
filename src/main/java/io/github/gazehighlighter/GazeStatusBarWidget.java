package io.github.gazehighlighter;

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
 *   ● GAZEAIDE: Reading      (green dot)
 *   ● GAZEAIDE: Explaining   (blue bold)
 *
 * Clicking it opens the reading-coverage summary dialog.
 */
public class GazeStatusBarWidget implements CustomStatusBarWidget {

    public static final String ID = "GazeAIDE";

    private static final String GREEN_HEX  = "#32C864";
    private static final String ACCENT_HEX = "#5AAFFF";

    private final Project project;
    private final JLabel  label;

    public GazeStatusBarWidget(Project project) {
        this.project = project;

        label = new JLabel();
        label.setOpaque(false);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        label.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        label.setToolTipText("Click to view GazeAIDE reading report");

        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                GazeModeService svc = GazeModeService.getInstance(project);
                if (svc != null) svc.showSummary();
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
        SwingUtilities.invokeLater(() -> {
            String modeHtml = mode == GazeMode.EXPLAINING
                    ? "<b><font color='" + ACCENT_HEX + "'>" + mode.label + "</font></b>"
                    : mode.label;
            label.setText("<html><font color='" + GREEN_HEX + "'>&#9679;</font>"
                    + "&nbsp;<b>GAZEAIDE:</b>&nbsp;" + modeHtml + "</html>");
        });
    }

    // ── StatusBarWidget ───────────────────────────────────────────────────────

    @Override public @NotNull String ID() { return ID; }
    @Override public @Nullable WidgetPresentation getPresentation() { return null; }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        GazeModeService svc = GazeModeService.getInstance(project);
        if (svc != null) svc.registerWidget(this);
    }

    @Override
    public void dispose() {
        GazeModeService svc = GazeModeService.getInstance(project);
        if (svc != null) svc.registerWidget(null);
    }

    @Override public @NotNull JComponent getComponent() { return label; }
}
