package io.github.gazehighlighter;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Input-agnostic gaze core — the CodeGRITS-style reading/explaining state machine for a
 * single {@link Editor}.
 *
 * <p>It is driven by <i>logical positions</i> ({@link #onGaze(int, int)} with a line and
 * column), not by mouse events, so the same engine serves both input sources:
 * <ul>
 *   <li>{@link GazeMouseListener} — mouse motion as a gaze proxy, and</li>
 *   <li>{@link UniteyeGazeService} — real gaze from UnitEye's browser pipeline, routed
 *       through {@link GazeDispatcher#routeScreenPoint(int, int)}.</li>
 * </ul>
 *
 * Behaviour:
 * <ul>
 *   <li>Gaze moves to a new line → READING mode, gutter circle on that line.</li>
 *   <li>Gaze dwells 3 s        → EXPLAINING mode, inline LLM explanation appears.</li>
 *   <li>Gaze leaves the editor → markers cleared; coverage tracker updates tints.</li>
 * </ul>
 *
 * The {@link CoverageTracker} is created lazily on the first gaze so the editor is
 * guaranteed to be fully initialised.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class GazeEngine {

    private static final int DWELL_MS = 3000;

    private static final TextAttributes DWELL_LINE_ATTR = new TextAttributes(
            null, new JBColor(new Color(210, 235, 255), new Color(35, 55, 85)),
            null, null, Font.PLAIN);

    private final Editor  editor;
    private final Project project;
    private final Timer   dwellTimer;

    // Lazy — created on first gaze to ensure editor is ready
    private @Nullable CoverageTracker coverage;

    // ── Current gaze markers ──────────────────────────────────────────────────
    private @Nullable RangeHighlighter    gazeMarker;
    private @Nullable RangeHighlighter    dwellHighlight;
    private @Nullable Inlay               explanationInlay;
    private @Nullable ExplanationRenderer explanationRenderer;

    // ── Line tracking ─────────────────────────────────────────────────────────
    private int     lastLine          = -1;
    private long    lineEnterTime     = 0;
    private String  lineText          = "";
    private int     lastColumn        = 0;
    private boolean explainedThisVisit = false;

    // ── Word-focus tracking (intra-line) ──────────────────────────────────────
    private final Map<String, Long> wordFocusMs   = new LinkedHashMap<>();
    private @Nullable String         currentWord   = null;
    private           long           wordEnterTime = 0;

    // ─────────────────────────────────────────────────────────────────────────

    public GazeEngine(Editor editor, Project project) {
        this.editor  = editor;
        this.project = project;

        dwellTimer = new Timer(DWELL_MS, e -> onDwell());
        dwellTimer.setRepeats(false);
    }

    // ── Gaze input ──────────────────────────────────────────────────────────────

    /**
     * Report that the gaze is currently at the given logical position in this editor.
     * Safe to call on the EDT at any rate; only line changes do meaningful work.
     */
    public void onGaze(int line, int col) {
        try {
            // Lazily create the tracker the first time the editor is actually used
            if (coverage == null) {
                coverage = new CoverageTracker(editor);
                GazeModeService svc = GazeModeService.getInstance(project);
                if (svc != null) svc.setActiveTracker(coverage);
            }

            if (line != lastLine) {
                // ── Leave old line ────────────────────────────────────────────
                flushLine();
                coverage.onLeave(lastLine);

                // ── Enter new line ────────────────────────────────────────────
                lastLine      = line;
                lineEnterTime = System.currentTimeMillis();

                setMode(GazeMode.READING);
                clearDwell();
                clearGazeMarker();
                dwellTimer.restart();

                wordFocusMs.clear();
                currentWord        = null;
                wordEnterTime      = System.currentTimeMillis();
                explainedThisVisit = false;

                Document doc = editor.getDocument();
                if (line < 0 || line >= doc.getLineCount()) { lineText = ""; return; }

                int ls = doc.getLineStartOffset(line);
                int le = doc.getLineEndOffset(line);
                lineText = doc.getText(new TextRange(ls, le));

                // Gutter circle
                gazeMarker = editor.getMarkupModel().addRangeHighlighter(
                        ls, Math.max(ls, le),
                        HighlighterLayer.SELECTION,
                        new TextAttributes(),
                        HighlighterTargetArea.LINES_IN_RANGE);
                gazeMarker.setGutterIconRenderer(new GutterCircleRenderer());
            }

            accumulateWord(col);

        } catch (Exception ignored) {}   // never let a bug kill the input source
    }

    /** Report that the gaze has left this editor entirely. */
    public void onExit() {
        try {
            flushLine();
            if (coverage != null) coverage.onLeave(lastLine);
            clearAll();
            lastLine = -1;
            setMode(GazeMode.READING);
        } catch (Exception ignored) {}
    }

    // ── Word-focus helpers ────────────────────────────────────────────────────

    private void accumulateWord(int col) {
        lastColumn = col;
        String word = wordAt(lineText, col);
        long now = System.currentTimeMillis();
        if (!same(word, currentWord)) {
            if (currentWord != null)
                wordFocusMs.merge(currentWord, now - wordEnterTime, Long::sum);
            currentWord   = word;
            wordEnterTime = now;
        }
    }

    private @Nullable String mostFocusedWord() {
        Map<String, Long> totals = new LinkedHashMap<>(wordFocusMs);
        if (currentWord != null)
            totals.merge(currentWord, System.currentTimeMillis() - wordEnterTime, Long::sum);
        return totals.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private static @Nullable String wordAt(String text, int col) {
        if (col < 0 || col >= text.length() || !isWordChar(text.charAt(col))) return null;
        int s = col; while (s > 0 && isWordChar(text.charAt(s - 1))) s--;
        int e = col + 1; while (e < text.length() && isWordChar(text.charAt(e))) e++;
        return text.substring(s, e);
    }

    private static boolean isWordChar(char c) { return Character.isLetterOrDigit(c) || c == '_'; }
    private static boolean same(@Nullable String a, @Nullable String b) {
        return a == null ? b == null : a.equals(b);
    }

    // ── Dwell ─────────────────────────────────────────────────────────────────

    private void onDwell() {
        try {
            if (editor.isDisposed() || lastLine < 0) return;
            Document doc = editor.getDocument();
            if (lastLine >= doc.getLineCount()) return;

            setMode(GazeMode.EXPLAINING);
            explainedThisVisit = true;
            if (coverage != null) coverage.markHeavy(lastLine);

            int ls = doc.getLineStartOffset(lastLine);
            int le = doc.getLineEndOffset(lastLine);

            dwellHighlight = editor.getMarkupModel().addRangeHighlighter(
                    ls, Math.max(ls, le),
                    HighlighterLayer.SELECTION - 2,
                    DWELL_LINE_ATTR,
                    HighlighterTargetArea.LINES_IN_RANGE);

            explanationRenderer = new ExplanationRenderer(editor, "Thinking…");
            explanationInlay = editor.getInlayModel().addBlockElement(
                    le, false, false, 0, explanationRenderer);

            String context     = FunctionExtractor.extract(doc, lastLine);
            String focusedWord = mostFocusedWord();
            final int captured = lastLine;

            LlmExplainer.explain(lineText, context, focusedWord, explanation -> {
                if (lastLine == captured
                        && explanationInlay  != null && explanationInlay.isValid()
                        && explanationRenderer != null) {
                    explanationRenderer.setText(explanation);
                    explanationInlay.update();
                }
            });
        } catch (Exception ignored) {}
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void flushLine() {
        if (lastLine >= 0 && lineEnterTime > 0 && coverage != null) {
            long dwellMs = System.currentTimeMillis() - lineEnterTime;
            coverage.flush(lastLine, dwellMs);
            recordEvent(lastLine, dwellMs);
        }
    }

    /** Appends one {@link GazeEvent} to the project's {@link GazeRecordingService}, if a
     *  recording session is currently active. No-op (and cheap) otherwise. */
    private void recordEvent(int line, long dwellMs) {
        try {
            GazeRecordingService rec = GazeRecordingService.getInstance(project);
            if (rec == null || !rec.isRecording()) return;

            Document doc = editor.getDocument();
            VirtualFile vf = FileDocumentManager.getInstance().getFile(doc);
            String path = vf != null ? relativePath(vf) : "(unsaved document)";

            String snippet = lineText.strip();
            if (snippet.length() > 200) snippet = snippet.substring(0, 200) + "…";

            long now = System.currentTimeMillis();
            GazeInputMode source = GazeInputSettings.getInstance().getInputMode();

            rec.record(new GazeEvent(now, now - rec.getStartMs(), path, line + 1, lastColumn,
                    dwellMs, snippet, mostFocusedWord(), explainedThisVisit, source));
        } catch (Exception ignored) {}
    }

    private String relativePath(VirtualFile vf) {
        String base = project.getBasePath();
        String full = vf.getPath();
        if (base != null && full.startsWith(base)) {
            String rel = full.substring(base.length());
            return rel.startsWith("/") ? rel.substring(1) : rel;
        }
        return full;
    }

    private void setMode(GazeMode mode) {
        try {
            GazeModeService svc = GazeModeService.getInstance(project);
            if (svc != null) svc.setMode(mode);
        } catch (Exception ignored) {}
    }

    private void clearGazeMarker() {
        if (gazeMarker != null && gazeMarker.isValid())
            editor.getMarkupModel().removeHighlighter(gazeMarker);
        gazeMarker = null;
    }

    private void clearDwell() {
        dwellTimer.stop();
        if (dwellHighlight != null && dwellHighlight.isValid())
            editor.getMarkupModel().removeHighlighter(dwellHighlight);
        dwellHighlight = null;
        if (explanationInlay != null && explanationInlay.isValid())
            explanationInlay.dispose();
        explanationInlay    = null;
        explanationRenderer = null;
    }

    void clearAll() {
        clearGazeMarker();
        clearDwell();
    }

    public void dispose() {
        try {
            dwellTimer.stop();
            clearAll();
            if (coverage != null) coverage.dispose();
        } catch (Exception ignored) {}
    }
}
