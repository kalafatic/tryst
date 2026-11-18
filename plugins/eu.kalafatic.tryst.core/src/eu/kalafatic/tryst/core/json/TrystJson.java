package eu.kalafatic.tryst.core.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON support for flat string maps and string lists. Used for
 * execution-configuration persistence and agent profile fields; intentionally limited to
 * flat structures (no nesting) so the parser stays small and safe. Lives in the core
 * bundle so domain, infrastructure and UI share one implementation.
 */
public final class TrystJson {

    private TrystJson() {
    }

    public static String writeStringMap(Map<String, String> data) {
        if (data == null || data.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(entry.getKey())).append(':')
                    .append(quote(entry.getValue() == null ? "" : entry.getValue()));
        }
        return sb.append('}').toString();
    }

    public static Map<String, String> readStringMap(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        String text = json == null || json.isBlank() ? "{}" : json.trim();
        if (!text.startsWith("{") || !text.endsWith("}")) {
            return result;
        }
        String body = text.substring(1, text.length() - 1);
        for (String pair : splitTopLevel(body)) {
            int colon = findKeyValueSeparator(pair);
            if (colon < 0) {
                continue;
            }
            String key = unquote(pair.substring(0, colon).trim());
            String value = unquote(pair.substring(colon + 1).trim());
            result.put(key, value);
        }
        return result;
    }

    public static String writeStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(quote(values.get(i) == null ? "" : values.get(i)));
        }
        return sb.append(']').toString();
    }

    public static List<String> readStringList(String json) {
        List<String> result = new ArrayList<>();
        String text = json == null || json.isBlank() ? "[]" : json.trim();
        if (!text.startsWith("[") || !text.endsWith("]")) {
            return result;
        }
        String body = text.substring(1, text.length() - 1);
        for (String part : splitTopLevel(body)) {
            String value = unquote(part.trim());
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    private static int findKeyValueSeparator(String pair) {
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < pair.length(); i++) {
            char c = pair.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (c == ':' && !inString) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> splitTopLevel(String body) {
        List<String> parts = new ArrayList<>();
        boolean inString = false;
        boolean escaped = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
            } else if (c == '\\') {
                current.append(c);
                escaped = true;
            } else if (c == '"') {
                current.append(c);
                inString = !inString;
            } else if (c == ',' && !inString) {
                if (!current.toString().isBlank()) {
                    parts.add(current.toString().trim());
                }
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) {
            parts.add(current.toString().trim());
        }
        return parts;
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String unquote(String value) {
        if (value == null || value.length() < 2 || !value.startsWith("\"")
                || !value.endsWith("\"")) {
            return value == null ? "" : value;
        }
        StringBuilder sb = new StringBuilder();
        String body = value.substring(1, value.length() - 1);
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char next = body.charAt(++i);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 < body.length()) {
                            try {
                                sb.append((char) Integer.parseInt(
                                        body.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException ignored) {
                                sb.append(next);
                            }
                        }
                    }
                    default -> sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
