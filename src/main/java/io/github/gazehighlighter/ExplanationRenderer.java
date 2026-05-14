package io.github.gazehighlighter;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;

import java.awt.*;

/**
 * Block inlay that renders the GazeAIDE explanation with a small robot badge
 * so the user can clearly distinguish it from actual code comments.
 *
 * Layout:  [ 🤖 badge ] | explanation text in italic
 */
@SuppressWarnings("rawtypes")
class ExplanationRenderer implements EditorCustomElementRenderer {

    // Colours for light / dark themes
    private static final JBColor BG      = new JBColor(new Color(232, 243, 255),     new Color(22, 42, 72));
    private static final JBColor TEXT_FG = new JBColor(new Color(30,  70, 140),      new Color(150, 200, 245));
    private static final JBColor SEP     = new JBColor(new Color(160, 200, 240),     new Color(50,  90, 140));

    private final Editor editor;
    private String text;

    ExplanationRenderer(Editor editor, String initialText) {
        this.editor = editor;
        this.text   = initialText;
    }

    void setText(String text) { this.text = text; }

    // ── EditorCustomElementRenderer ───────────────────────────────────────────

    @Override
    public int calcWidthInPixels(@NotNull Inlay inlay) {
        int badge = badgeSize();
        FontMetrics fm = editor.getComponent().getFontMetrics(italicFont());
        return 8 + badge + 10 + fm.stringWidth(text) + 12;
    }

    @Override
    public int calcHeightInPixels(@NotNull Inlay inlay) {
        return editor.getLineHeight() + 2;
    }

    @Override
    public void paint(@NotNull Inlay inlay, @NotNull Graphics g,
                      @NotNull Rectangle r, @NotNull TextAttributes ignored) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int pad  = 3;
            int bsz  = badgeSize();
            int mid  = r.y + r.height / 2;   // vertical centre

            // ── Background pill ──────────────────────────────────────────────
            g2.setColor(BG);
            g2.fillRoundRect(r.x + 1, r.y + pad, r.width - 2, r.height - pad * 2, 10, 10);

            // ── Robot badge ──────────────────────────────────────────────────
            int bx = r.x + 7;
            int by = mid - bsz / 2;
            paintRobotBadge(g2, bx, by, bsz);

            // ── Separator ────────────────────────────────────────────────────
            int sepX = bx + bsz + 5;
            g2.setColor(SEP);
            g2.drawLine(sepX, r.y + pad + 4, sepX, r.y + r.height - pad - 4);

            // ── Explanation text ─────────────────────────────────────────────
            g2.setFont(italicFont());
            g2.setColor(TEXT_FG);
            FontMetrics fm = g2.getFontMetrics();
            int textY = r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(text, sepX + 7, textY);

        } finally {
            g2.dispose();
        }
    }

    // ── Robot badge painter ───────────────────────────────────────────────────

    private static void paintRobotBadge(Graphics2D g, int x, int y, int s) {
        // Head — blue rounded square
        g.setColor(new Color(35, 100, 220));
        g.fillRoundRect(x, y, s, s, 4, 4);

        // Antenna dot
        g.setColor(new Color(100, 200, 255));
        int ax = x + s / 2 - 1;
        g.fillRect(ax, y - Math.max(2, s / 5), 2, Math.max(2, s / 5));
        g.fillOval(ax - 1, y - Math.max(2, s / 5) - 2, 4, 4);

        // Eyes — bright cyan ovals
        g.setColor(new Color(160, 235, 255));
        int ew = Math.max(2, s / 5);
        int eh = Math.max(1, s / 5);
        int ey = y + s * 3 / 10;
        g.fillOval(x + s / 5,           ey, ew, eh);
        g.fillOval(x + s - s / 5 - ew,  ey, ew, eh);

        // Mouth — green accent bar
        g.setColor(new Color(40, 215, 100));
        int mw = s * 2 / 5;
        int mx = x + (s - mw) / 2;
        int mh = Math.max(1, s / 6);
        int my = y + s * 13 / 20;
        g.fillRoundRect(mx, my, mw, mh, mh, mh);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int badgeSize() {
        return Math.max(10, editor.getLineHeight() - 8);
    }

    private Font italicFont() {
        return editor.getColorsScheme().getFont(EditorFontType.PLAIN).deriveFont(Font.ITALIC);
    }
}
