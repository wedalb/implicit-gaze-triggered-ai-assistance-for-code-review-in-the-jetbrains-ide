package io.github.gazehighlighter;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.util.TextRange;
import com.intellij.ui.JBColor;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-editor reading tracker.
 *
 * Line states (subtle background tints):
 *   unread  →  green  (≥ 500 ms)
 *   green   →  red    (LLM dwell triggered at 3 s)
 *   red     →  yellow (cursor left after explanation)
 *
 * Hotspots: any line accumulating ≥ 5 s total is flagged.
 * The enclosing function name is extracted and surfaced in the report.
 */
public class CoverageTracker {

    public static final long READ_THRESHOLD_MS    = 500;
    public static final long HOTSPOT_THRESHOLD_MS = 5_000;

    private static final JBColor BG_GREEN  = new JBColor(new Color(236, 255, 236), new Color(24, 46, 24));
    private static final JBColor BG_RED    = new JBColor(new Color(255, 241, 238), new Color(56, 24, 22));
    private static final JBColor BG_YELLOW = new JBColor(new Color(255, 252, 226), new Color(48, 45, 16));
    private static final int     LAYER     = HighlighterLayer.SYNTAX - 10;

    // ── Hotspot model ─────────────────────────────────────────────────────────

    public static final class Hotspot {
        public final String functionName;
        public final int    lineNumber;    // 0-indexed
        public final String lineText;
        public final long   totalMs;

        Hotspot(String functionName, int lineNumber, String lineText, long totalMs) {
            this.functionName = functionName != null && !functionName.isBlank()
                    ? functionName : "(unnamed block)";
            this.lineNumber = lineNumber;
            this.lineText   = lineText;
            this.totalMs    = totalMs;
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    enum Status { READ, HEAVY, EXPLAINED }

    private final Editor editor;

    private final Map<Integer, Long>             lineTimes  = new HashMap<>();
    private final Map<Integer, Status>           statuses   = new HashMap<>();
    private final Map<Integer, RangeHighlighter> highlights = new HashMap<>();

    private int heavyCount     = 0;
    private int explainedCount = 0;

    // keyed by function name so each function appears only once (worst line kept)
    private final Map<String, Hotspot> hotspotMap  = new LinkedHashMap<>();
    private final Set<Integer>         hotspotSeen = new HashSet<>();

    CoverageTracker(Editor editor) {
        this.editor = editor;
    }

    // ── Time recording ────────────────────────────────────────────────────────

    void flush(int line, long ms) {
        if (line < 0 || editor.isDisposed()) return;
        long total = lineTimes.merge(line, ms, Long::sum);

        if (statuses.get(line) == null && total >= READ_THRESHOLD_MS)
            applyStatus(line, Status.READ);

        if (total >= HOTSPOT_THRESHOLD_MS && !hotspotSeen.contains(line))
            recordHotspot(line, total);
    }

    void markHeavy(int line) {
        if (line < 0 || editor.isDisposed()) return;
        Status cur = statuses.get(line);
        if (cur != Status.HEAVY && cur != Status.EXPLAINED) heavyCount++;
        applyStatus(line, Status.HEAVY);
    }

    void onLeave(int line) {
        if (line < 0 || editor.isDisposed()) return;
        if (statuses.get(line) == Status.HEAVY) {
            explainedCount++;
            applyStatus(line, Status.EXPLAINED);
        }
    }

    // ── Hotspot recording ─────────────────────────────────────────────────────

    private void recordHotspot(int line, long totalMs) {
        hotspotSeen.add(line);
        if (editor.isDisposed()) return;

        String funcName = extractFunctionName(line);
        String key      = funcName != null ? funcName : "__line_" + line;
        String text     = lineText(line);

        Hotspot existing = hotspotMap.get(key);
        // Keep the most-dwelled line as the representative for this function
        if (existing == null || totalMs > existing.totalMs)
            hotspotMap.put(key, new Hotspot(funcName, line, text, totalMs));
    }

    private String extractFunctionName(int targetLine) {
        Document doc = editor.getDocument();
        for (int i = targetLine; i >= Math.max(0, targetLine - 80); i--) {
            String stripped = lineText(i).stripLeading();
            String name = parseFunctionName(stripped);
            if (name != null) return name;
        }
        return null;
    }

    private static final Pattern JAVA_LIKE = Pattern.compile(
            "(?:public|private|protected|static|override|suspend|async)\\s+" +
            "(?:fun|function|void|int|long|String|bool|boolean|List|Map|def|fn)\\s+(\\w+)\\s*\\(");

    private static String parseFunctionName(String s) {
        // Python / Ruby
        if (s.startsWith("def ") || s.startsWith("async def ")) {
            int start = s.indexOf("def ") + 4;
            int end   = s.indexOf("(", start);
            if (end > start) return s.substring(start, end).trim();
        }
        // JS / TS
        if (s.startsWith("function ")) {
            int end = s.indexOf("(", 9);
            if (end > 9) return s.substring(9, end).trim();
        }
        // Go
        if (s.startsWith("func ")) {
            int start = 5, end = s.indexOf("(", start);
            if (end > start) return s.substring(start, end).trim();
        }
        // Rust
        if (s.startsWith("fn ")) {
            int start = 3, end = s.indexOf("(", start);
            if (end > start) return s.substring(start, end).trim();
        }
        // Java / Kotlin / C#
        Matcher m = JAVA_LIKE.matcher(s);
        if (m.find()) return m.group(1);

        return null;
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    int getReadCount()      { return statuses.size(); }
    int getHeavyCount()     { return heavyCount; }
    int getExplainedCount() { return explainedCount; }

    int getTotalLines() {
        Document doc = editor.getDocument();
        int n = 0;
        for (int i = 0; i < doc.getLineCount(); i++)
            if (doc.getLineEndOffset(i) > doc.getLineStartOffset(i)) n++;
        return n;
    }

    List<Hotspot> getHotspots() { return new ArrayList<>(hotspotMap.values()); }

    // ── Highlights ────────────────────────────────────────────────────────────

    private void applyStatus(int line, Status status) {
        try {
            Document doc = editor.getDocument();
            if (line >= doc.getLineCount()) return;

            RangeHighlighter old = highlights.remove(line);
            if (old != null && old.isValid()) editor.getMarkupModel().removeHighlighter(old);

            TextAttributes attrs = new TextAttributes();
            attrs.setBackgroundColor(status == Status.READ     ? BG_GREEN  :
                                     status == Status.HEAVY    ? BG_RED    : BG_YELLOW);

            int s = doc.getLineStartOffset(line), e = doc.getLineEndOffset(line);
            highlights.put(line, editor.getMarkupModel().addRangeHighlighter(
                    s, Math.max(s, e), LAYER, attrs, HighlighterTargetArea.LINES_IN_RANGE));
            statuses.put(line, status);
        } catch (Exception ignored) {}
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String lineText(int line) {
        try {
            Document doc = editor.getDocument();
            if (line >= doc.getLineCount()) return "";
            return doc.getText(new TextRange(doc.getLineStartOffset(line), doc.getLineEndOffset(line)));
        } catch (Exception e) { return ""; }
    }

    void dispose() {
        if (editor.isDisposed()) return;
        highlights.values().stream().filter(RangeHighlighter::isValid)
                .forEach(h -> editor.getMarkupModel().removeHighlighter(h));
        highlights.clear();
    }
}
