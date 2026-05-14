package io.github.gazehighlighter;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;

/**
 * Extracts the function/method body surrounding a given line.
 * Uses an indentation-based heuristic that works well for Python
 * and reasonably well for Java, JS, Go, etc.
 */
public class FunctionExtractor {

    public static String extract(Document doc, int targetLine) {
        int total = doc.getLineCount();

        // Walk upward to find a function/method definition
        int funcStart = -1;
        for (int i = targetLine; i >= 0; i--) {
            String stripped = getLine(doc, i).stripLeading();
            if (isFunctionHeader(stripped)) {
                funcStart = i;
                break;
            }
        }

        if (funcStart < 0) {
            // Fallback: ±25-line window
            int from = Math.max(0, targetLine - 25);
            int to   = Math.min(total - 1, targetLine + 25);
            return extractRange(doc, from, to);
        }

        // Walk downward until indentation returns to the same level as the def line
        int headerIndent = leadingSpaces(getLine(doc, funcStart));
        int funcEnd = funcStart + 1;
        while (funcEnd < total) {
            String line = getLine(doc, funcEnd);
            if (!line.isBlank() && leadingSpaces(line) <= headerIndent && funcEnd > funcStart + 1) {
                break;
            }
            funcEnd++;
        }
        funcEnd = Math.min(funcEnd - 1, total - 1);

        // Cap at 80 lines to keep prompt size reasonable
        if (funcEnd - funcStart > 80) funcEnd = funcStart + 80;

        return extractRange(doc, funcStart, funcEnd);
    }

    private static boolean isFunctionHeader(String stripped) {
        return stripped.startsWith("def ")
            || stripped.startsWith("async def ")
            || stripped.startsWith("function ")
            || stripped.startsWith("func ")
            || stripped.startsWith("fn ")
            || stripped.matches("(public|private|protected|static|override|suspend)\\s.*\\(.*\\).*\\{?\\s*")
            || stripped.matches("(void|int|long|double|float|bool|string|String|List|Map)\\s+\\w+\\s*\\(.*\\).*\\{?\\s*");
    }

    private static String extractRange(Document doc, int from, int to) {
        int start = doc.getLineStartOffset(from);
        int end   = doc.getLineEndOffset(to);
        return doc.getText(new TextRange(start, end));
    }

    private static String getLine(Document doc, int line) {
        return doc.getText(new TextRange(doc.getLineStartOffset(line), doc.getLineEndOffset(line)));
    }

    private static int leadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return i;
    }
}
