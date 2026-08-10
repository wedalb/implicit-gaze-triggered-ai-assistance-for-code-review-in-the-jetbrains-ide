package io.github.gazehighlighter;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

/** Tiny shared helper around the "Implicit Gaze" notification group (balloon toasts). */
final class GazeNotify {

    private static final String GROUP = "Implicit Gaze";

    private GazeNotify() {}

    static void info(@Nullable Project project, String message) {
        show(project, message, NotificationType.INFORMATION);
    }

    static void warn(@Nullable Project project, String message) {
        show(project, message, NotificationType.WARNING);
    }

    private static void show(@Nullable Project project, String message, NotificationType type) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                NotificationGroupManager.getInstance()
                        .getNotificationGroup(GROUP)
                        .createNotification(message, type)
                        .notify(project);
            } catch (Exception ignored) { }
        });
    }
}
