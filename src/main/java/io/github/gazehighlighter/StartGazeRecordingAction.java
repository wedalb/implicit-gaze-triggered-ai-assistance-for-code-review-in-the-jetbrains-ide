package io.github.gazehighlighter;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/** Starts a new gaze recording session for the current project (Tools menu / status bar). */
public class StartGazeRecordingAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        GazeRecordingService.getInstance(project).start();
        GazeNotify.info(project, "Gaze recording started. Read some code, then use "
                + "\"Stop Gaze Recording\" and \"Export Gaze Session\" when you're done.");
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        boolean recording = project != null && GazeRecordingService.getInstance(project).isRecording();
        e.getPresentation().setEnabledAndVisible(project != null && !recording);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
