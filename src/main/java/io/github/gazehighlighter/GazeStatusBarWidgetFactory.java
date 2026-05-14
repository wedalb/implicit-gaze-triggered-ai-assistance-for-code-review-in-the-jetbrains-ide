package io.github.gazehighlighter;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import org.jetbrains.annotations.NotNull;

public class GazeStatusBarWidgetFactory implements StatusBarWidgetFactory {

    @Override public @NotNull String getId() { return GazeStatusBarWidget.ID; }
    @Override public @NotNull String getDisplayName() { return "Implicit Mode"; }
    @Override public boolean isAvailable(@NotNull Project project) { return true; }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new GazeStatusBarWidget(project);
    }

    @Override
    public void disposeWidget(@NotNull StatusBarWidget widget) {
        Disposer.dispose(widget);
    }

    @Override
    public boolean canBeEnabledOn(@NotNull com.intellij.openapi.wm.StatusBar statusBar) {
        return true;
    }
}
