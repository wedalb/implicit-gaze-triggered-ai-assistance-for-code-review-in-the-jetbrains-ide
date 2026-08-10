package io.github.gazehighlighter;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * "Read Report" — the in-IDE counterpart to {@link ExportGazeSessionAction}: stops an
 * in-progress recording if needed, then shows a {@link GazeSessionReportDialog} summarizing it,
 * with no file export required.
 */
public class ReadGazeSessionReportAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        GazeSession session = GazeRecordingService.getInstance(project).finishedSession();

        if (session == null || session.events.isEmpty()) {
            GazeNotify.warn(project, "Nothing to report yet — start a gaze recording, read some "
                    + "code, then stop it before reading the report.");
            return;
        }

        ApplicationManager.getApplication().invokeLater(
                () -> new GazeSessionReportDialog(project, session).show());
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null && GazeRecordingService.getInstance(project).hasData());
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
