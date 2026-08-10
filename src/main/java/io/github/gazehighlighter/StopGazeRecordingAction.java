package io.github.gazehighlighter;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/** Stops the current gaze recording session; the captured events stay available for export. */
public class StopGazeRecordingAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        GazeSession session = GazeRecordingService.getInstance(project).stop();
        GazeNotify.info(project, "Gaze recording stopped — " + session.events.size()
                + " line visits captured. Use \"Export Gaze Session\" to save it.");
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        boolean recording = project != null && GazeRecordingService.getInstance(project).isRecording();
        e.getPresentation().setEnabledAndVisible(recording);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
