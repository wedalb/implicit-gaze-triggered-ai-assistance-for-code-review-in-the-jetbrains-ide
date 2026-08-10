package io.github.gazehighlighter;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseMotionListener;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Mouse gaze source — a thin adapter that turns mouse motion into gaze positions for the
 * editor's {@link GazeEngine}.
 *
 * <p>Active only when the input mode is {@link GazeInputMode#MOUSE} (the default). When the
 * user switches to {@link GazeInputMode#WEBCAM}, mouse events are ignored and the
 * {@link UniteyeGazeService} drives the same engine instead — so the listener can stay
 * attached regardless of mode.
 */
public class GazeMouseListener implements EditorMouseMotionListener {

    private final Editor          editor;
    private final GazeDispatcher  dispatcher;

    public GazeMouseListener(Editor editor, GazeDispatcher dispatcher) {
        this.editor     = editor;
        this.dispatcher = dispatcher;

        editor.getContentComponent().addMouseListener(new MouseAdapter() {
            @Override public void mouseExited(MouseEvent e) {
                if (!isMouseMode()) return;
                GazeEngine engine = dispatcher.engineFor(editor);
                if (engine != null) engine.onExit();
            }
        });
    }

    @Override
    public void mouseMoved(@NotNull EditorMouseEvent event) {
        if (!isMouseMode()) return;
        GazeEngine engine = dispatcher.engineFor(editor);
        if (engine == null) return;

        Point pos2d = event.getMouseEvent().getPoint();
        LogicalPosition pos = editor.xyToLogicalPosition(pos2d);
        engine.onGaze(pos.line, pos.column);
    }

    private static boolean isMouseMode() {
        return GazeInputSettings.getInstance().getInputMode() == GazeInputMode.MOUSE;
    }
}
