package io.github.gazehighlighter;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Read Report" — an in-IDE summary of a recorded {@link GazeSession}, shown without needing to
 * export anything to disk first. The companion to {@link ExportGazeSessionAction}: Export writes
 * CSV/JSON/HTML files; this just displays the same aggregate numbers directly.
 */
public class GazeSessionReportDialog extends DialogWrapper {

    private static final Color C_AMBER = new JBColor(new Color(200, 140, 0), new Color(200, 140, 0));
    private static final Color C_RED   = new JBColor(new Color(190, 55, 45), new Color(190, 55, 45));
    private static final int   MAX_HOTSPOTS = 10;

    private static final class Hotspot {
        final String file; final int line; final String snippet;
        long totalMs; int visits;
        Hotspot(String file, int line, String snippet) { this.file = file; this.line = line; this.snippet = snippet; }
    }

    private final GazeSession session;
    private final List<Hotspot> hotspots;

    public GazeSessionReportDialog(Project project, GazeSession session) {
        super(project, false);
        this.session = session;
        this.hotspots = aggregate(session);
        setTitle("Implicit — Gaze Session Report");
        setResizable(false);
        init();
    }

    private static List<Hotspot> aggregate(GazeSession session) {
        Map<String, Hotspot> byKey = new LinkedHashMap<>();
        for (GazeEvent e : session.events) {
            String key = e.filePath + ":" + e.line;
            Hotspot h = byKey.computeIfAbsent(key, k -> new Hotspot(e.filePath, e.line, e.lineText));
            h.totalMs += e.dwellMs;
            h.visits++;
        }
        List<Hotspot> list = new ArrayList<>(byKey.values());
        list.sort((a, b) -> Long.compare(b.totalMs, a.totalMs));
        return list.size() > MAX_HOTSPOTS ? list.subList(0, MAX_HOTSPOTS) : list;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(JBUI.Borders.empty(4, 2, 8, 2));

        root.add(sectionTitle("Session"));
        root.add(vgap(6));
        root.add(new JLabel(session.projectName + "  •  started "
                + java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                        .format(java.time.Instant.ofEpochMilli(session.startMs)
                                .atZone(java.time.ZoneId.systemDefault()))));
        root.add(vgap(14));
        root.add(statsGrid());

        long distinctFiles = session.events.stream().map(e -> e.filePath).distinct().count();
        root.add(vgap(10));
        JLabel filesLabel = new JLabel(distinctFiles + " file(s) visited");
        filesLabel.setForeground(UIUtil.getContextHelpForeground());
        filesLabel.setFont(JBUI.Fonts.smallFont());
        filesLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(filesLabel);

        if (!hotspots.isEmpty()) {
            root.add(vgap(18));
            root.add(separator());
            root.add(vgap(14));
            root.add(sectionTitle("Where you looked most"));
            root.add(vgap(3));

            JLabel note = new JLabel("Top " + hotspots.size() + " lines by total time spent this session.");
            note.setForeground(UIUtil.getContextHelpForeground());
            note.setFont(JBUI.Fonts.smallFont());
            note.setAlignmentX(Component.LEFT_ALIGNMENT);
            root.add(note);
            root.add(vgap(10));

            for (Hotspot h : hotspots) {
                root.add(hotspotRow(h));
                root.add(vgap(6));
            }
        } else {
            root.add(vgap(14));
            JLabel empty = new JLabel("No line visits were recorded in this session.");
            empty.setForeground(UIUtil.getContextHelpForeground());
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            root.add(empty);
        }

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setPreferredSize(new Dimension(JBUI.scale(480), 1));
        wrapper.add(root, BorderLayout.NORTH);

        JBScrollPane scroll = new JBScrollPane(wrapper,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(JBUI.Borders.empty());

        int baseH = hotspots.isEmpty() ? 220 : Math.min(560, 280 + hotspots.size() * 66);
        scroll.setPreferredSize(new Dimension(JBUI.scale(480), JBUI.scale(baseH)));
        return scroll;
    }

    @Override
    protected Action[] createActions() {
        getOKAction().putValue(Action.NAME, "Close");
        return new Action[]{ getOKAction() };
    }

    private JComponent statsGrid() {
        JPanel row = new JPanel(new GridLayout(1, 3, JBUI.scale(12), 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(52)));

        long explained = session.events.stream().filter(e -> e.explained).count();
        row.add(statCell("Duration", formatDuration(session.durationMs())));
        row.add(statCell("Line visits", String.valueOf(session.events.size())));
        row.add(statCell("Explanations", String.valueOf(explained)));
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

    private JComponent hotspotRow(Hotspot h) {
        JPanel panel = new JPanel(new BorderLayout(JBUI.scale(10), 0));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(8, 10)));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(60)));

        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setOpaque(false);

        JLabel nameLabel = new JLabel(h.file + ":" + h.line);
        nameLabel.setFont(UIUtil.getLabelFont().deriveFont(Font.BOLD));

        String snippet = h.snippet.strip();
        if (snippet.length() > 60) snippet = snippet.substring(0, 57) + "…";
        JLabel lineLabel = new JLabel(snippet);
        lineLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.Fonts.smallFont().getSize()));
        lineLabel.setForeground(UIUtil.getContextHelpForeground());

        left.add(nameLabel);
        left.add(vgap(2));
        left.add(lineLabel);

        long secs = Math.round(h.totalMs / 1000.0);
        JLabel timeLabel = new JLabel("~" + secs + "s  (" + h.visits + "x)");
        timeLabel.setFont(JBUI.Fonts.smallFont().asBold());
        timeLabel.setForeground(secs >= 10 ? C_RED : C_AMBER);
        timeLabel.setVerticalAlignment(SwingConstants.TOP);

        panel.add(left, BorderLayout.CENTER);
        panel.add(timeLabel, BorderLayout.EAST);
        return panel;
    }

    private static String formatDuration(long ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600, m = (totalSec % 3600) / 60, s = totalSec % 60;
        return h > 0 ? String.format("%dh %02dm %02ds", h, m, s) : String.format("%dm %02ds", m, s);
    }

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
