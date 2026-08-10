package io.github.gazehighlighter;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

public class GazeHighlighterStartupActivity implements StartupActivity.DumbAware {

    @Override
    public void runActivity(@NotNull Project project) {
        EditorFactory factory = EditorFactory.getInstance();

        // Attach to already-open editors
        for (Editor editor : factory.getAllEditors()) {
            if (project.equals(editor.getProject())) {
                attach(editor, project);
            }
        }

        // Attach to / detach from editors created or released from now on
        factory.addEditorFactoryListener(new EditorFactoryListener() {
            @Override
            public void editorCreated(@NotNull EditorFactoryEvent event) {
                Editor editor = event.getEditor();
                if (project.equals(editor.getProject())) {
                    attach(editor, project);
                }
            }

            @Override
            public void editorReleased(@NotNull EditorFactoryEvent event) {
                Editor editor = event.getEditor();
                if (project.equals(editor.getProject())) {
                    GazeDispatcher.getInstance(project).unregister(editor);
                }
            }
        }, project);

        // If the user left the plugin in webcam mode, bring the tracker back up.
        if (GazeInputSettings.getInstance().getInputMode() == GazeInputMode.WEBCAM) {
            UniteyeGazeService.getInstance().applyMode(GazeInputMode.WEBCAM);
        }
    }

    private void attach(Editor editor, Project project) {
        GazeDispatcher dispatcher = GazeDispatcher.getInstance(project);
        dispatcher.register(editor, project);
        // Mouse source: a thin adapter that forwards to the engine only in mouse mode.
        editor.addEditorMouseMotionListener(new GazeMouseListener(editor, dispatcher), project);
    }
}
