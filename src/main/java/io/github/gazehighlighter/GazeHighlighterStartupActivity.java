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

        // Attach to every editor opened from now on
        factory.addEditorFactoryListener(new EditorFactoryListener() {
            @Override
            public void editorCreated(@NotNull EditorFactoryEvent event) {
                Editor editor = event.getEditor();
                if (project.equals(editor.getProject())) {
                    attach(editor, project);
                }
            }
        }, project);
    }

    private void attach(Editor editor, Project project) {
        GazeMouseListener listener = new GazeMouseListener(editor, project);
        editor.addEditorMouseMotionListener(listener, project);
    }
}
