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
 * Sends the gazed line + function context (+ most-focused word) to
 * OpenAI gpt-4o-mini and returns a short, targeted explanation.
 *
 * Key lookup order: JVM system property -DOPENAI_API_KEY → env var OPENAI_API_KEY.
 */
public class LlmExplainer {

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL   = "gpt-4o-mini";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Fires async; {@code onResult} is always called on the EDT. */
    public static void explain(String targetLine, String functionContext,
                               @Nullable String focusedWord, Consumer<String> onResult) {
        String apiKey = System.getProperty("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            deliver(onResult, "Set OPENAI_API_KEY in Help → Edit VM Options");
            return;
        }

        final String key = apiKey;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                deliver(onResult, request(key, targetLine, functionContext, focusedWord));
            } catch (Exception e) {
                deliver(onResult, "Error: " + e.getMessage());
            }
        });
    }

    private static String request(String apiKey, String line, String context,
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

        String body = "{\"model\":\"" + MODEL + "\",\"max_tokens\":80,"
                    + "\"messages\":["
                    + "{\"role\":\"system\",\"content\":\"" + sys + "\"},"
                    + "{\"role\":\"user\",\"content\":\"" + user + "\"}"
                    + "]}";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

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
