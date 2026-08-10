package io.github.gazehighlighter;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Application-level settings for the model-agnostic AI layer.
 *
 * <p>Stores a list of OpenAI-compatible providers (base URL + model + max tokens) and which
 * one is active. API keys are NOT serialized here — they live in the IDE's
 * {@link PasswordSafe} keyed by provider id, so they never end up in config XML or VCS.
 *
 * <p>Groq, Cerebras, OpenAI and Ollama all speak the same Chat Completions API, so switching
 * providers only changes the base URL, model and credential.
 */
@State(name = "ImplicitAiSettings", storages = @Storage("implicit-ai.xml"))
public final class AiSettings implements PersistentStateComponent<AiSettings.State> {

    private static final String CREDENTIAL_PREFIX = "io.github.gazehighlighter.ai:";

    /** A single configured provider. Public mutable fields so xmlb can (de)serialize it. */
    public static final class ProviderConfig {
        public String id = "";
        public String displayName = "";
        public String baseUrl = "";
        public String model = "";
        public int maxTokens = 80;
        /** Built-in presets cannot be deleted (but remain fully editable). */
        public boolean builtin = false;

        public ProviderConfig() { }

        public ProviderConfig(String id, String displayName, String baseUrl,
                              String model, int maxTokens, boolean builtin) {
            this.id = id;
            this.displayName = displayName;
            this.baseUrl = baseUrl;
            this.model = model;
            this.maxTokens = maxTokens;
            this.builtin = builtin;
        }

        public ProviderConfig copy() {
            return new ProviderConfig(id, displayName, baseUrl, model, maxTokens, builtin);
        }
    }

    public static final class State {
        public List<ProviderConfig> providers = new ArrayList<>();
        public String activeId = "";
    }

    private State state = new State();

    public static AiSettings getInstance() {
        return ApplicationManager.getApplication().getService(AiSettings.class);
    }

    // ── PersistentStateComponent ────────────────────────────────────────────────

    @Override
    public @NotNull State getState() {
        if (state.providers.isEmpty()) seedDefaults();
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        XmlSerializerUtil.copyBean(loaded, state);
        if (state.providers.isEmpty()) seedDefaults();
    }

    @Override
    public void initializeComponent() {
        if (state.providers.isEmpty()) seedDefaults();
    }

    /** Built-in presets, seeded the first time the service is used. */
    private void seedDefaults() {
        state.providers = defaultProviders();
        if (state.activeId == null || state.activeId.isBlank()) {
            state.activeId = "cerebras";
        }
    }

    /** Fresh copies of the shipped presets — also used by the UI's "restore" needs. */
    public static List<ProviderConfig> defaultProviders() {
        List<ProviderConfig> list = new ArrayList<>();
        list.add(new ProviderConfig("openai", "OpenAI",
                "https://api.openai.com/v1/chat/completions", "gpt-4o-mini", 80, true));
        list.add(new ProviderConfig("groq", "Groq",
                "https://api.groq.com/openai/v1/chat/completions", "llama-3.3-70b-versatile", 80, true));
        list.add(new ProviderConfig("cerebras", "Cerebras",
                "https://api.cerebras.ai/v1/chat/completions", "llama-3.3-70b", 80, true));
        list.add(new ProviderConfig("mellum", "Mellum (Ollama)",
                "http://localhost:11434/v1/chat/completions", "mellum", 80, true));
        return list;
    }

    // ── Provider accessors ──────────────────────────────────────────────────────

    public List<ProviderConfig> getProviders() {
        if (state.providers.isEmpty()) seedDefaults();
        return state.providers;
    }

    public void setProviders(List<ProviderConfig> providers) {
        state.providers = new ArrayList<>(providers);
    }

    public String getActiveId() {
        return state.activeId;
    }

    public void setActiveId(String id) {
        state.activeId = id;
    }

    /** The provider used for explanations; falls back to the first configured one. */
    public @Nullable ProviderConfig getActive() {
        List<ProviderConfig> providers = getProviders();
        for (ProviderConfig p : providers) {
            if (p.id.equals(state.activeId)) return p;
        }
        return providers.isEmpty() ? null : providers.get(0);
    }

    // ── API keys (PasswordSafe — never serialized to disk in plaintext) ──────────

    private static CredentialAttributes attributesFor(String providerId) {
        return new CredentialAttributes(CREDENTIAL_PREFIX + providerId);
    }

    public @Nullable String getApiKey(String providerId) {
        Credentials c = PasswordSafe.getInstance().get(attributesFor(providerId));
        return c == null ? null : c.getPasswordAsString();
    }

    public void setApiKey(String providerId, @Nullable String key) {
        CredentialAttributes attrs = attributesFor(providerId);
        if (key == null || key.isBlank()) {
            PasswordSafe.getInstance().set(attrs, null);
        } else {
            PasswordSafe.getInstance().set(attrs, new Credentials(providerId, key));
        }
    }
}
