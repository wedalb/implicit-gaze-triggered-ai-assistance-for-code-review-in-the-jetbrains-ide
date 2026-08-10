package io.github.gazehighlighter;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Settings UI under <b>Settings → Tools → Implicit AI</b>.
 *
 * <p>Lets the user manage any number of OpenAI-compatible providers (edit the built-in
 * OpenAI / Groq / Cerebras / Mellum presets, add custom ones) and pick which is active.
 * Edits are held in a working copy and only written to {@link AiSettings} / PasswordSafe on
 * Apply.
 */
public final class AiSettingsConfigurable implements Configurable {

    private JPanel root;

    private DefaultListModel<AiSettings.ProviderConfig> listModel;
    private JBList<AiSettings.ProviderConfig> providerList;

    private JBTextField nameField;
    private JBTextField urlField;
    private JBTextField modelField;
    private JBTextField maxTokensField;
    private JBPasswordField keyField;
    private JButton setActiveButton;
    private JBLabel activeLabel;

    /** Working state — committed to AiSettings/PasswordSafe only on apply(). */
    private final Map<String, String> keys = new HashMap<>();
    private String activeId = "";
    /** The config whose fields are currently shown (so we can flush edits on switch). */
    private AiSettings.ProviderConfig current;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Implicit AI";
    }

    @Override
    public @Nullable JComponent createComponent() {
        listModel = new DefaultListModel<>();
        providerList = new JBList<>(listModel);
        providerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        providerList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                AiSettings.ProviderConfig p = (AiSettings.ProviderConfig) value;
                String label = p.displayName == null || p.displayName.isBlank()
                        ? "(unnamed)" : p.displayName;
                setText(p.id.equals(activeId) ? label + "  — active" : label);
                return this;
            }
        });
        providerList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) onSelectionChanged();
        });

        JPanel listPanel = ToolbarDecorator.createDecorator(providerList)
                .setAddAction(b -> addProvider())
                .setRemoveAction(b -> removeProvider())
                .setRemoveActionUpdater(e -> {
                    AiSettings.ProviderConfig sel = providerList.getSelectedValue();
                    return sel != null && !sel.builtin;   // built-in presets cannot be removed
                })
                .createPanel();
        listPanel.setPreferredSize(new Dimension(220, 320));

        nameField      = new JBTextField();
        urlField       = new JBTextField();
        modelField     = new JBTextField();
        maxTokensField = new JBTextField();
        keyField       = new JBPasswordField();

        setActiveButton = new JButton("Set as active");
        setActiveButton.addActionListener(e -> {
            if (current != null) {
                activeId = current.id;
                refreshActiveLabel();
                providerList.repaint();
            }
        });
        activeLabel = new JBLabel();

        JPanel detail = FormBuilder.createFormBuilder()
                .addLabeledComponent("Display name:", nameField)
                .addLabeledComponent("Base URL:", urlField)
                .addLabeledComponent("Model:", modelField)
                .addLabeledComponent("Max tokens:", maxTokensField)
                .addLabeledComponent("API key:", keyField)
                .addComponentToRightColumn(new JBLabel(
                        "Stored securely in the IDE password safe — never written to project files."))
                .addComponent(new JBLabel(
                        "Any OpenAI-compatible Chat Completions endpoint works. "
                        + "Local providers (Ollama) need no key."))
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();

        JPanel rightTop = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        rightTop.add(setActiveButton, BorderLayout.WEST);
        rightTop.add(activeLabel, BorderLayout.CENTER);

        JPanel right = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        right.setBorder(JBUI.Borders.emptyLeft(12));
        right.add(rightTop, BorderLayout.NORTH);
        right.add(detail, BorderLayout.CENTER);

        root = new JPanel(new BorderLayout());
        root.add(listPanel, BorderLayout.WEST);
        root.add(right, BorderLayout.CENTER);

        reset();
        return root;
    }

    // ── List actions ────────────────────────────────────────────────────────────

    private void addProvider() {
        flushCurrent();
        String id = "custom-" + System.currentTimeMillis();
        AiSettings.ProviderConfig p = new AiSettings.ProviderConfig(
                id, "Custom", "https://", "", 80, false);
        listModel.addElement(p);
        providerList.setSelectedValue(p, true);
    }

    private void removeProvider() {
        AiSettings.ProviderConfig sel = providerList.getSelectedValue();
        if (sel == null || sel.builtin) return;
        int idx = providerList.getSelectedIndex();
        keys.remove(sel.id);
        if (sel.id.equals(activeId)) activeId = "";
        current = null;                 // its fields are gone; don't flush back into it
        listModel.removeElement(sel);
        if (!listModel.isEmpty()) {
            providerList.setSelectedIndex(Math.min(idx, listModel.size() - 1));
        }
        refreshActiveLabel();
    }

    private void onSelectionChanged() {
        flushCurrent();
        AiSettings.ProviderConfig sel = providerList.getSelectedValue();
        current = sel;
        boolean has = sel != null;
        nameField.setEnabled(has);
        urlField.setEnabled(has);
        modelField.setEnabled(has);
        maxTokensField.setEnabled(has);
        keyField.setEnabled(has);
        setActiveButton.setEnabled(has);
        if (!has) {
            nameField.setText("");
            urlField.setText("");
            modelField.setText("");
            maxTokensField.setText("");
            keyField.setText("");
            return;
        }
        nameField.setText(sel.displayName);
        urlField.setText(sel.baseUrl);
        modelField.setText(sel.model);
        maxTokensField.setText(String.valueOf(sel.maxTokens));
        keyField.setText(keys.getOrDefault(sel.id, ""));
        refreshActiveLabel();
    }

    /** Copy the visible field values back into {@link #current}. */
    private void flushCurrent() {
        if (current == null) return;
        current.displayName = nameField.getText().trim();
        current.baseUrl = urlField.getText().trim();
        current.model = modelField.getText().trim();
        current.maxTokens = parseTokens(maxTokensField.getText());
        keys.put(current.id, new String(keyField.getPassword()));
    }

    private static int parseTokens(String s) {
        try {
            int v = Integer.parseInt(s.trim());
            return v > 0 ? v : 80;
        } catch (NumberFormatException e) {
            return 80;
        }
    }

    private void refreshActiveLabel() {
        String name = "(none)";
        for (int i = 0; i < listModel.size(); i++) {
            AiSettings.ProviderConfig p = listModel.get(i);
            if (p.id.equals(activeId)) {
                name = p.displayName;
                break;
            }
        }
        activeLabel.setText("Active provider: " + name);
    }

    // ── Configurable contract ────────────────────────────────────────────────────

    @Override
    public boolean isModified() {
        flushCurrent();
        AiSettings settings = AiSettings.getInstance();
        if (!Objects.equals(activeId, settings.getActiveId())) return true;

        List<AiSettings.ProviderConfig> saved = settings.getProviders();
        if (saved.size() != listModel.size()) return true;
        for (int i = 0; i < listModel.size(); i++) {
            AiSettings.ProviderConfig a = listModel.get(i);
            AiSettings.ProviderConfig b = saved.get(i);
            if (!a.id.equals(b.id) || !a.displayName.equals(b.displayName)
                    || !a.baseUrl.equals(b.baseUrl) || !a.model.equals(b.model)
                    || a.maxTokens != b.maxTokens || a.builtin != b.builtin) {
                return true;
            }
            String savedKey = nullToEmpty(settings.getApiKey(a.id));
            if (!nullToEmpty(keys.get(a.id)).equals(savedKey)) return true;
        }
        return false;
    }

    @Override
    public void apply() {
        flushCurrent();
        AiSettings settings = AiSettings.getInstance();

        for (int i = 0; i < listModel.size(); i++) {
            AiSettings.ProviderConfig p = listModel.get(i);
            if (p.baseUrl.isBlank() || p.model.isBlank()) {
                Messages.showErrorDialog(root,
                        "Provider \"" + p.displayName + "\" needs both a base URL and a model.",
                        "Incomplete Provider");
                return;
            }
        }

        List<AiSettings.ProviderConfig> copy = new ArrayList<>();
        for (int i = 0; i < listModel.size(); i++) copy.add(listModel.get(i).copy());
        settings.setProviders(copy);

        if (activeId.isBlank() && !copy.isEmpty()) activeId = copy.get(0).id;
        settings.setActiveId(activeId);

        for (AiSettings.ProviderConfig p : copy) {
            settings.setApiKey(p.id, keys.get(p.id));
        }
    }

    @Override
    public void reset() {
        AiSettings settings = AiSettings.getInstance();
        listModel.clear();
        keys.clear();
        current = null;
        for (AiSettings.ProviderConfig p : settings.getProviders()) {
            AiSettings.ProviderConfig c = p.copy();
            listModel.addElement(c);
            keys.put(c.id, nullToEmpty(settings.getApiKey(c.id)));
        }
        activeId = settings.getActiveId();
        if (!listModel.isEmpty()) providerList.setSelectedIndex(0);
        refreshActiveLabel();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
