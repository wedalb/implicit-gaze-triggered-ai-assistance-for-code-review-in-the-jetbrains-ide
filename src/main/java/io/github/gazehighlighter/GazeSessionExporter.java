package io.github.gazehighlighter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports a finished {@link GazeSession} to the three data types Implicit supports:
 *
 * <ul>
 *   <li><b>CSV</b> — one row per recorded line visit; for spreadsheets / pandas / R.</li>
 *   <li><b>JSON</b> — the same events plus session metadata; for programmatic analysis.</li>
 *   <li><b>HTML</b> — a self-contained, dependency-free heatmap + time-scrubbable replay page,
 *       so "where did I look" is answerable by opening a file in a browser, no IDE or tooling
 *       required.</li>
 * </ul>
 */
public final class GazeSessionExporter {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

    private GazeSessionExporter() {}

    /** Writes all three formats next to each other as {@code <baseName>.csv/.json/.html}. */
    public static List<Path> exportAll(GazeSession session, Path directory, String baseName) throws IOException {
        Files.createDirectories(directory);
        List<Path> out = new ArrayList<>();
        out.add(exportCsv(session, directory.resolve(baseName + ".csv")));
        out.add(exportJson(session, directory.resolve(baseName + ".json")));
        out.add(exportHeatmapHtml(session, directory.resolve(baseName + ".html")));
        return out;
    }

    // ── CSV ──────────────────────────────────────────────────────────────────────

    public static Path exportCsv(GazeSession session, Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("timestamp_iso,elapsed_ms,file,line,column,dwell_ms,explained,source,word,line_text\n");
        for (GazeEvent e : session.events) {
            sb.append(ISO.format(Instant.ofEpochMilli(e.timestampMs))).append(',')
              .append(e.elapsedMs).append(',')
              .append(csv(e.filePath)).append(',')
              .append(e.line).append(',')
              .append(e.column).append(',')
              .append(e.dwellMs).append(',')
              .append(e.explained).append(',')
              .append(e.source.name()).append(',')
              .append(csv(e.word == null ? "" : e.word)).append(',')
              .append(csv(e.lineText)).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static String csv(String s) {
        boolean needsQuoting = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!needsQuoting) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    // ── JSON ─────────────────────────────────────────────────────────────────────

    public static Path exportJson(GazeSession session, Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"project\": "); jsonString(sb, session.projectName); sb.append(",\n");
        sb.append("  \"startMs\": ").append(session.startMs).append(",\n");
        sb.append("  \"endMs\": ").append(session.endMs).append(",\n");
        sb.append("  \"durationMs\": ").append(session.durationMs()).append(",\n");
        sb.append("  \"startIso\": "); jsonString(sb, ISO.format(Instant.ofEpochMilli(session.startMs))); sb.append(",\n");
        sb.append("  \"endIso\": "); jsonString(sb, ISO.format(Instant.ofEpochMilli(session.endMs))); sb.append(",\n");
        sb.append("  \"events\": [\n");
        for (int i = 0; i < session.events.size(); i++) {
            GazeEvent e = session.events.get(i);
            sb.append("    { \"timestampMs\": ").append(e.timestampMs)
              .append(", \"elapsedMs\": ").append(e.elapsedMs)
              .append(", \"file\": "); jsonString(sb, e.filePath);
            sb.append(", \"line\": ").append(e.line)
              .append(", \"column\": ").append(e.column)
              .append(", \"dwellMs\": ").append(e.dwellMs)
              .append(", \"explained\": ").append(e.explained)
              .append(", \"source\": \"").append(e.source.name()).append('"')
              .append(", \"word\": "); jsonString(sb, e.word == null ? "" : e.word);
            sb.append(", \"lineText\": "); jsonString(sb, e.lineText);
            sb.append(" }").append(i < session.events.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }

    /** Appends a JSON string literal for {@code s} (including surrounding quotes) to {@code sb}. */
    private static void jsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                // Escaped so this is always safe to also embed inside a <script> block in HTML.
                case '<'  -> sb.append("\\u003C");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ── HTML heatmap + replay ────────────────────────────────────────────────────

    public static Path exportHeatmapHtml(GazeSession session, Path file) throws IOException {
        // Group events by file, preserving first-seen order.
        Map<String, List<GazeEvent>> byFile = new LinkedHashMap<>();
        for (GazeEvent e : session.events)
            byFile.computeIfAbsent(e.filePath, k -> new ArrayList<>()).add(e);

        long totalDwell = session.events.stream().mapToLong(e -> e.dwellMs).sum();
        long explainedCount = session.events.stream().filter(e -> e.explained).count();

        StringBuilder html = new StringBuilder();
        html.append("<!doctype html>\n<html><head><meta charset=\"utf-8\">\n");
        html.append("<title>Implicit — Gaze Session Replay (").append(esc(session.projectName)).append(")</title>\n");
        html.append("<style>").append(CSS).append("</style>\n</head><body>\n");

        html.append("<header>\n");
        html.append("  <h1>Implicit — Gaze Session Replay</h1>\n");
        html.append("  <div class=\"meta\">")
            .append("<span><b>Project:</b> ").append(esc(session.projectName)).append("</span>")
            .append("<span><b>Started:</b> ").append(esc(ISO.format(Instant.ofEpochMilli(session.startMs)))).append("</span>")
            .append("<span><b>Duration:</b> ").append(formatDuration(session.durationMs())).append("</span>")
            .append("<span><b>Line visits:</b> ").append(session.events.size()).append("</span>")
            .append("<span><b>Files:</b> ").append(byFile.size()).append("</span>")
            .append("<span><b>Total dwell:</b> ").append(formatDuration(totalDwell)).append("</span>")
            .append("<span><b>AI explanations triggered:</b> ").append(explainedCount).append("</span>")
            .append("</div>\n");
        html.append("</header>\n");

        html.append("<div id=\"transport\">\n")
            .append("  <button id=\"playBtn\">▶ Play</button>\n")
            .append("  <input id=\"scrubber\" type=\"range\" min=\"0\" max=\"").append(Math.max(1, session.durationMs())).append("\" value=\"").append(Math.max(1, session.durationMs())).append("\">\n")
            .append("  <span id=\"clock\">00:00 / ").append(formatDuration(session.durationMs())).append("</span>\n")
            .append("  <select id=\"speed\"><option value=\"1\">1x</option><option value=\"4\">4x</option><option value=\"16\" selected>16x</option><option value=\"64\">64x</option></select>\n")
            .append("  <span id=\"nowLoc\" class=\"nowloc\">—</span>\n")
            .append("</div>\n");
        html.append("<p class=\"hint\">Drag the scrubber (or hit Play) to replay the session — line shading shows cumulative time spent, up to the current point in time.</p>\n");

        html.append("<main id=\"files\">\n");
        int fileIdx = 0;
        for (Map.Entry<String, List<GazeEvent>> entry : byFile.entrySet()) {
            String path = entry.getKey();
            List<GazeEvent> events = entry.getValue();
            long fileMax = events.stream().collect(
                    java.util.stream.Collectors.groupingBy(e -> e.line,
                            java.util.stream.Collectors.summingLong(e -> e.dwellMs)))
                    .values().stream().mapToLong(Long::longValue).max().orElse(1);

            // Latest snippet text per line, in ascending line order.
            Map<Integer, String> lineText = new LinkedHashMap<>();
            for (GazeEvent e : events) lineText.put(e.line, e.lineText);
            List<Integer> lines = new ArrayList<>(lineText.keySet());
            lines.sort(Integer::compareTo);

            html.append("<section class=\"file\" data-file=\"").append(fileIdx).append("\">\n");
            html.append("  <h2>").append(esc(path)).append("</h2>\n");
            html.append("  <div class=\"code\">\n");
            for (int line : lines) {
                html.append("    <div class=\"line\" data-line=\"").append(line)
                    .append("\" data-max=\"").append(fileMax).append("\">")
                    .append("<span class=\"ln\">").append(line).append("</span>")
                    .append("<span class=\"txt\">").append(esc(lineText.get(line))).append("</span>")
                    .append("</div>\n");
            }
            html.append("  </div>\n</section>\n");
            fileIdx++;
        }
        html.append("</main>\n");

        html.append("<script>\nconst SESSION = {\n  durationMs: ").append(Math.max(1, session.durationMs())).append(",\n  files: [\n");
        fileIdx = 0;
        for (Map.Entry<String, List<GazeEvent>> entry : byFile.entrySet()) {
            html.append("    { path: "); jsonString(html, entry.getKey()); html.append(", events: [\n");
            List<GazeEvent> events = entry.getValue();
            for (int i = 0; i < events.size(); i++) {
                GazeEvent e = events.get(i);
                html.append("      [").append(e.line).append(',').append(e.elapsedMs).append(',').append(e.dwellMs)
                    .append(',').append(e.explained ? 1 : 0).append(']')
                    .append(i < events.size() - 1 ? ",\n" : "\n");
            }
            html.append("    ] }").append(fileIdx < byFile.size() - 1 ? ",\n" : "\n");
            fileIdx++;
        }
        html.append("  ]\n};\n").append(JS).append("\n</script>\n");

        html.append("</body></html>\n");
        Files.writeString(file, html.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String formatDuration(long ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600, m = (totalSec % 3600) / 60, s = totalSec % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    // ── Static assets (inline — the exported page must be fully self-contained) ──

    private static final String CSS = """
        :root { color-scheme: light dark; }
        body { font-family: -apple-system, Segoe UI, sans-serif; margin: 0; padding: 0 24px 48px;
               background: #1e1f22; color: #dfe1e5; }
        header { padding: 20px 0 8px; }
        h1 { font-size: 20px; margin: 0 0 8px; }
        .meta { display: flex; flex-wrap: wrap; gap: 18px; font-size: 13px; color: #9aa0a6; }
        .meta b { color: #dfe1e5; }
        #transport { position: sticky; top: 0; background: #1e1f22; padding: 12px 0; display: flex;
                     align-items: center; gap: 12px; border-bottom: 1px solid #35363a; z-index: 10; }
        #transport button { background: #3b82f6; color: white; border: none; border-radius: 4px;
                             padding: 6px 14px; cursor: pointer; font-size: 13px; }
        #transport button:hover { background: #2f6fd6; }
        #scrubber { flex: 1; }
        #clock { font-variant-numeric: tabular-nums; font-size: 12px; color: #9aa0a6; min-width: 110px; }
        .nowloc { font-size: 12px; color: #7bd88f; min-width: 220px; text-align: right; }
        .hint { font-size: 12px; color: #9aa0a6; margin: 10px 0 20px; }
        .file h2 { font-size: 13px; font-weight: 600; color: #9aa0a6; margin: 26px 0 6px;
                   font-family: monospace; }
        .code { border: 1px solid #35363a; border-radius: 6px; overflow: hidden; }
        .line { display: flex; font-family: "SF Mono", Consolas, monospace; font-size: 12.5px;
                padding: 1px 0; white-space: pre; }
        .line .ln { width: 48px; text-align: right; padding-right: 12px; color: #6b6f76;
                    flex-shrink: 0; user-select: none; }
        .line .txt { flex: 1; padding-right: 12px; overflow: hidden; text-overflow: ellipsis; }
        .line.active { outline: 1px solid #7bd88f; }
        """;

    private static final String JS = """
        const scrubber = document.getElementById('scrubber');
        const playBtn  = document.getElementById('playBtn');
        const clock    = document.getElementById('clock');
        const speedSel = document.getElementById('speed');
        const nowLoc   = document.getElementById('nowLoc');
        const fileSections = document.querySelectorAll('.file');

        function fmt(ms) {
          const totalSec = Math.floor(ms / 1000);
          const h = Math.floor(totalSec / 3600), m = Math.floor((totalSec % 3600) / 60), s = totalSec % 60;
          const pad = n => String(n).padStart(2, '0');
          return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`;
        }

        function render(t) {
          let active = null;
          SESSION.files.forEach((f, idx) => {
            const section = fileSections[idx];
            const dwellByLine = new Map();
            for (const [line, elapsed, dwell, explained] of f.events) {
              const start = elapsed - dwell;
              if (start > t) continue;
              const covered = Math.min(dwell, t - start);
              dwellByLine.set(line, (dwellByLine.get(line) || 0) + covered);
              if (t >= start && t <= elapsed) active = { path: f.path, line, explained };
            }
            let max = 1;
            for (const v of dwellByLine.values()) max = Math.max(max, v);
            section.querySelectorAll('.line').forEach(row => {
              const line = Number(row.dataset.line);
              const dwell = dwellByLine.get(line) || 0;
              const alpha = dwell === 0 ? 0 : 0.15 + 0.65 * Math.min(1, dwell / max);
              row.style.background = dwell === 0 ? '' : `rgba(96, 165, 250, ${alpha})`;
              row.classList.remove('active');
            });
          });
          if (active) {
            const row = fileSections[[...SESSION.files].findIndex(f => f.path === active.path)]
              .querySelector(`.line[data-line="${active.line}"]`);
            if (row) row.classList.add('active');
            nowLoc.textContent = `${active.path}:${active.line}${active.explained ? '  (explained)' : ''}`;
          } else {
            nowLoc.textContent = '—';
          }
          clock.textContent = `${fmt(t)} / ${fmt(SESSION.durationMs)}`;
        }

        let playing = false, rafId = null, lastFrame = 0;
        function tick(now) {
          if (!playing) return;
          const dt = (now - lastFrame) * Number(speedSel.value);
          lastFrame = now;
          let t = Number(scrubber.value) + dt;
          if (t >= SESSION.durationMs) { t = SESSION.durationMs; playing = false; playBtn.textContent = '▶ Play'; }
          scrubber.value = t;
          render(t);
          if (playing) rafId = requestAnimationFrame(tick);
        }

        playBtn.addEventListener('click', () => {
          playing = !playing;
          playBtn.textContent = playing ? '⏸ Pause' : '▶ Play';
          if (playing) {
            if (Number(scrubber.value) >= SESSION.durationMs) scrubber.value = 0;
            lastFrame = performance.now();
            rafId = requestAnimationFrame(tick);
          } else if (rafId) {
            cancelAnimationFrame(rafId);
          }
        });
        scrubber.addEventListener('input', () => render(Number(scrubber.value)));
        render(Number(scrubber.value));
        """;
}
