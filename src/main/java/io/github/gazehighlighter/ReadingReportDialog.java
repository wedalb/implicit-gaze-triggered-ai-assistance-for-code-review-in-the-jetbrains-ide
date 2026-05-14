package io.github.gazehighlighter;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.util.List;

/**
 * JetBrains-style reading coverage report.
 * Open by clicking the Implicit status-bar widget.
 */
public class ReadingReportDialog extends DialogWrapper {

    // Only three semantic colours — used sparingly
    private static final Color C_GREEN = new JBColor(new Color(75, 175, 70),  new Color(75, 175, 70));
    private static final Color C_AMBER = new JBColor(new Color(200, 140, 0),  new Color(200, 140, 0));
    private static final Color C_RED   = new JBColor(new Color(190, 55, 45),  new Color(190, 55, 45));

    private final int                           read, total, heavy, explained;
    private final List<CoverageTracker.Hotspot> hotspots;

    public ReadingReportDialog(Project project, int read, int total,
                               int heavy, int explained,
                               List<CoverageTracker.Hotspot> hotspots) {
        super(project, false);
        this.read      = read;
        this.total     = total;
        this.heavy     = heavy;
        this.explained = explained;
        this.hotspots  = hotspots;
        setTitle("Implicit — Reading Report");
        setResizable(false);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(JBUI.Borders.empty(4, 2, 8, 2));

        // ── Coverage ──────────────────────────────────────────────────────────
        root.add(sectionTitle("Coverage"));
        root.add(vgap(6));
        root.add(progressRow());
        root.add(vgap(16));

        // ── Three-column stats ────────────────────────────────────────────────
        root.add(statsGrid());

        // ── Hotspots ──────────────────────────────────────────────────────────
        if (!hotspots.isEmpty()) {
            root.add(vgap(18));
            root.add(separator());
            root.add(vgap(14));
            root.add(sectionTitle("Functions with Prolonged Focus"));
            root.add(vgap(3));

            JLabel note = new JLabel(
                "The following functions had lines with >5 s of focus. " +
                "Consider adding inline documentation.");
            note.setForeground(UIUtil.getContextHelpForeground());
            note.setFont(JBUI.Fonts.smallFont());
            note.setAlignmentX(Component.LEFT_ALIGNMENT);
            root.add(note);
            root.add(vgap(10));

            for (CoverageTracker.Hotspot h : hotspots) {
                root.add(hotspotRow(h));
                root.add(vgap(6));
            }
        }

        // Fixed width; height is natural
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setPreferredSize(new Dimension(JBUI.scale(460), 1));
        wrapper.add(root, BorderLayout.NORTH);

        JBScrollPane scroll = new JBScrollPane(wrapper,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(JBUI.Borders.empty());

        int baseH = hotspots.isEmpty() ? 220 : Math.min(520, 260 + hotspots.size() * 90);
        scroll.setPreferredSize(new Dimension(JBUI.scale(460), JBUI.scale(baseH)));
        return scroll;
    }

    @Override
    protected Action[] createActions() {
        getOKAction().putValue(Action.NAME, "Close");
        return new Action[]{ getOKAction() };
    }

    // ── Coverage row ──────────────────────────────────────────────────────────

    private JComponent progressRow() {
        int pct = total > 0 ? read * 100 / total : 0;
        Color barColor = pct >= 80 ? C_GREEN : pct >= 50 ? C_AMBER : C_RED;

        JProgressBar bar = new JProgressBar(0, 100);
        bar.setValue(pct);
        bar.setForeground(barColor);
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(14)));

        JLabel label = new JLabel(read + " of " + total + " non-blank lines  (" + pct + "%)");
        label.setForeground(UIUtil.getContextHelpForeground());
        label.setFont(JBUI.Fonts.smallFont());
        label.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        col.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(bar);
        col.add(vgap(4));
        col.add(label);
        return col;
    }

    // ── Stats grid ────────────────────────────────────────────────────────────

    private JComponent statsGrid() {
        JPanel row = new JPanel(new GridLayout(1, 3, JBUI.scale(12), 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(52)));

        row.add(statCell("Lines read",      read + " / " + total));
        row.add(statCell("Studied (≥3 s)",  String.valueOf(heavy)));
        row.add(statCell("Explanations",    String.valueOf(explained)));
        return row;
    }

    private JComponent statCell(String label, String value) {
        JPanel cell = new JPanel();
        cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
        cell.setOpaque(false);
        cell.setBorder(JBUI.Borders.customLineLeft(JBColor.border()));

        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setOpaque(false);
        inner.setBorder(JBUI.Borders.empty(0, 10));

        JLabel valLabel = new JLabel(value);
        valLabel.setFont(UIUtil.getLabelFont().deriveFont(Font.BOLD, UIUtil.getLabelFont().getSize() + 3f));

        JLabel keyLabel = new JLabel(label);
        keyLabel.setFont(JBUI.Fonts.smallFont());
        keyLabel.setForeground(UIUtil.getContextHelpForeground());

        inner.add(valLabel);
        inner.add(vgap(1));
        inner.add(keyLabel);
        cell.add(inner);
        return cell;
    }

    // ── Hotspot row ───────────────────────────────────────────────────────────

    private JComponent hotspotRow(CoverageTracker.Hotspot h) {
        JPanel panel = new JPanel(new BorderLayout(JBUI.scale(10), 0));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(8, 10)));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(68)));

        // Left — function name + code snippet
        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setOpaque(false);

        JLabel nameLabel = new JLabel(h.functionName + "()");
        nameLabel.setFont(UIUtil.getLabelFont().deriveFont(Font.BOLD));

        String snippet = h.lineText.strip();
        if (snippet.length() > 65) snippet = snippet.substring(0, 62) + "…";
        JLabel lineLabel = new JLabel("Line " + (h.lineNumber + 1) + ":  " + snippet);
        lineLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.Fonts.smallFont().getSize()));
        lineLabel.setForeground(UIUtil.getContextHelpForeground());

        JLabel recLabel = new JLabel("Add documentation or a comment to clarify intent.");
        recLabel.setFont(JBUI.Fonts.smallFont().asItalic());
        recLabel.setForeground(UIUtil.getContextHelpForeground());

        left.add(nameLabel);
        left.add(vgap(2));
        left.add(lineLabel);
        left.add(vgap(2));
        left.add(recLabel);

        // Right — time badge (only color here — communicates severity)
        long secs = Math.round(h.totalMs / 1000.0);
        JLabel timeLabel = new JLabel("~" + secs + "s");
        timeLabel.setFont(JBUI.Fonts.smallFont().asBold());
        timeLabel.setForeground(secs >= 10 ? C_RED : C_AMBER);
        timeLabel.setVerticalAlignment(SwingConstants.TOP);

        panel.add(left, BorderLayout.CENTER);
        panel.add(timeLabel, BorderLayout.EAST);
        return panel;
    }

    // ── Layout helpers ────────────────────────────────────────────────────────

    private static JLabel sectionTitle(String text) {
        JLabel l = new JLabel(text);
        l.setFont(UIUtil.getLabelFont().deriveFont(Font.BOLD));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static JSeparator separator() {
        JSeparator s = new JSeparator();
        s.setAlignmentX(Component.LEFT_ALIGNMENT);
        s.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        return s;
    }

    private static Component vgap(int px) {
        return Box.createVerticalStrut(JBUI.scale(px));
    }
}
