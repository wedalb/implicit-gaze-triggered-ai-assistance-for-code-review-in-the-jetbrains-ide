package io.github.gazehighlighter;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Exports the current (or most recently stopped) gaze recording as CSV + JSON + a self-contained
 * HTML heatmap/replay page, then opens the HTML page so the result is immediately visible.
 *
 * <p>If a recording is still running, this stops it first — export is meant to be "I'm done,
 * save what I looked at", not a mid-session action.
 */
public class ExportGazeSessionAction extends AnAction {

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        GazeSession session = GazeRecordingService.getInstance(project).finishedSession();

        if (session == null || session.events.isEmpty()) {
            GazeNotify.warn(project, "Nothing to export yet — start a gaze recording, read some "
                    + "code, then stop it before exporting.");
            return;
        }

        VirtualFile guessed = ProjectUtil.guessProjectDir(project);
        VirtualFile defaultDir = guessed != null ? guessed
                : LocalFileSystem.getInstance().findFileByPath(System.getProperty("user.home"));
        VirtualFile chosen = FileChooser.chooseFile(
                FileChooserDescriptorFactory.createSingleFolderDescriptor()
                        .withTitle("Export Gaze Session")
                        .withDescription("Choose a folder — implicit-gaze-<timestamp>.csv/.json/.html will be created there."),
                project, defaultDir);
        if (chosen == null) return;   // user cancelled

        Path dir = Path.of(chosen.getPath());
        String baseName = "implicit-gaze-" + FILE_STAMP.format(LocalDateTime.now());

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                List<Path> written = GazeSessionExporter.exportAll(session, dir, baseName);
                Path html = written.get(2);
                GazeNotify.info(project, "Gaze session exported: " + written.size()
                        + " files written to " + dir + " (opening the heatmap replay).");
                ApplicationManager.getApplication().invokeLater(() -> BrowserUtil.browse(html.toFile()));
            } catch (IOException ex) {
                GazeNotify.warn(project, "Export failed: " + ex.getMessage());
            }
        });
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
