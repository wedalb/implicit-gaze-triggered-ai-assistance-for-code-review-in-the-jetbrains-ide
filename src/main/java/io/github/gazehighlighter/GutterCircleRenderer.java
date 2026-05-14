package io.github.gazehighlighter;

import com.intellij.openapi.editor.markup.GutterIconRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/** Blue-filled circle drawn in the gutter next to the currently gazed-at line. */
public class GutterCircleRenderer extends GutterIconRenderer {

    private static final Icon ICON = new Icon() {
        private static final int D = 10;

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int cx = x + 1;
            int cy = y + (getIconHeight() - D) / 2;
            // Outer glow
            g2.setColor(new Color(100, 170, 255, 60));
            g2.fillOval(cx - 2, cy - 2, D + 4, D + 4);
            // Fill
            g2.setColor(new Color(60, 150, 255));
            g2.fillOval(cx, cy, D, D);
            // Highlight
            g2.setColor(new Color(180, 220, 255, 160));
            g2.fillOval(cx + 2, cy + 1, D / 2, D / 3);
            g2.dispose();
        }

        @Override public int getIconWidth()  { return D + 4; }
        @Override public int getIconHeight() { return 16; }
    };

    @Override public @NotNull Icon getIcon() { return ICON; }
    @Override public @NotNull Alignment getAlignment() { return Alignment.LEFT; }
    @Override public @Nullable String getTooltipText() { return "GazeAIDE: current gaze line"; }
    @Override public boolean equals(Object o) { return o instanceof GutterCircleRenderer; }
    @Override public int hashCode() { return GutterCircleRenderer.class.hashCode(); }
}
