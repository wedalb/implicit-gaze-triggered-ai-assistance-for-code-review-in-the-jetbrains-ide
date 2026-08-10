package io.github.gazehighlighter;

import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Sends the gazed line + function context (+ most-focused word) to the user's configured
 * AI provider and returns a short, targeted explanation.
 *
 * <p>The provider, model and base URL come from {@link AiSettings} (Settings → Tools →
 * Implicit AI). Any OpenAI-compatible Chat Completions endpoint works — OpenAI, Groq,
 * Cerebras, a local Ollama/Mellum server, or a custom provider.
 *
 * <p>Key lookup: PasswordSafe entry for the active provider → (OpenAI only) legacy
 * {@code -DOPENAI_API_KEY} system property / {@code OPENAI_API_KEY} env var. Local
 * providers (e.g. Ollama) may run without any key.
 */
public class LlmExplainer {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Fires async; {@code onResult} is always called on the EDT. */
    public static void explain(String targetLine, String functionContext,
                               @Nullable String focusedWord, Consumer<String> onResult) {
        AiSettings settings = AiSettings.getInstance();
        AiSettings.ProviderConfig provider = settings.getActive();
        if (provider == null || provider.baseUrl == null || provider.baseUrl.isBlank()) {
            deliver(onResult, "No AI provider configured — see Settings → Tools → Implicit AI");
            return;
        }

        String apiKey = settings.getApiKey(provider.id);
        if ((apiKey == null || apiKey.isBlank()) && "openai".equals(provider.id)) {
            apiKey = System.getProperty("OPENAI_API_KEY");
            if (apiKey == null || apiKey.isBlank()) apiKey = System.getenv("OPENAI_API_KEY");
        }
        boolean local = isLocal(provider.baseUrl);
        if ((apiKey == null || apiKey.isBlank()) && !local) {
            deliver(onResult, "Set the " + provider.displayName
                    + " API key in Settings → Tools → Implicit AI");
            return;
        }

        final String key = apiKey;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                deliver(onResult, request(provider, key, targetLine, functionContext, focusedWord));
            } catch (Exception e) {
                deliver(onResult, "Error: " + e.getMessage());
            }
        });
    }

    /** Treat loopback hosts as local — they typically need no Authorization header. */
    private static boolean isLocal(String url) {
        return url.contains("localhost") || url.contains("127.0.0.1") || url.contains("0.0.0.0");
    }

    private static String request(AiSettings.ProviderConfig provider, @Nullable String apiKey,
                                  String line, String context,
                                  @Nullable String focusedWord) throws Exception {
        String sys  = escapeJson(
            "You explain a single code line in one short sentence (max 15 words). " +
            "Focus on WHY the code is written this way, not just what it does. " +
            "If a focus token is given, explain specifically why that token is " +
            "used/defined the way it is within this function. No markdown, no preamble."
        );

        StringBuilder userSb = new StringBuilder();
        userSb.append("Function:\n```\n").append(context).append("\n```\n\n");
        userSb.append("Line: `").append(line.strip()).append("`\n");
        if (focusedWord != null && !focusedWord.isBlank()) {
            userSb.append("The developer's gaze was focused on: `").append(focusedWord)
                  .append("` — explain why this is used this way in the function.");
        } else {
            userSb.append("Explain why this line is written this way.");
        }

        String user = escapeJson(userSb.toString());

        int maxTokens = provider.maxTokens > 0 ? provider.maxTokens : 80;
        String body = "{\"model\":\"" + escapeJson(provider.model) + "\",\"max_tokens\":" + maxTokens + ","
                    + "\"messages\":["
                    + "{\"role\":\"system\",\"content\":\"" + sys + "\"},"
                    + "{\"role\":\"user\",\"content\":\"" + user + "\"}"
                    + "]}";

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(provider.baseUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        HttpRequest req = builder.build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new RuntimeException("API " + resp.statusCode());

        return extractContent(resp.body());
    }

    /** Extracts the last {@code "content":"..."} string from a JSON response. */
    private static String extractContent(String json) {
        int idx = json.lastIndexOf("\"content\":");
        if (idx < 0) throw new RuntimeException("Unexpected response format");
        int q1 = json.indexOf('"', idx + 10) + 1;
        if (q1 <= 0) throw new RuntimeException("Unexpected response format");
        StringBuilder sb = new StringBuilder();
        for (int i = q1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                switch (n) {
                    case '"':  sb.append('"');  break;
                    case 'n':  sb.append(' ');  break;   // flatten newlines in explanation
                    case 't':  sb.append(' ');  break;
                    case '\\': sb.append('\\'); break;
                    default:   sb.append(n);
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString().strip();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static void deliver(Consumer<String> cb, String value) {
        ApplicationManager.getApplication().invokeLater(() -> cb.accept(value));
    }
}
