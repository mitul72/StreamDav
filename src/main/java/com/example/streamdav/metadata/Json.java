package com.example.streamdav.metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Just enough JSON for the metadata APIs: parses into maps, lists, strings, numbers, booleans and null, with typed
 * accessors that treat missing and mistyped fields alike.
 */
public final class Json {
    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    /** Parses a JSON document; objects become {@link Obj}, arrays {@code List<Object>}, numbers {@code Double}. */
    public static Object parse(String text) {
        Json parser = new Json(text);
        parser.skipWhitespace();
        Object value = parser.value();
        parser.skipWhitespace();
        if (parser.pos != text.length()) {
            throw parser.error("Unexpected trailing content");
        }
        return value;
    }

    public static Obj parseObject(String text) {
        if (parse(text) instanceof Obj object) {
            return object;
        }
        throw new IllegalArgumentException("Expected a JSON object");
    }

    /** A JSON string literal, quotes included. */
    public static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** A JSON object. Accessors return empty or null rather than throwing when a field is missing or mistyped. */
    public record Obj(Map<String, Object> fields) {

        public Optional<String> string(String key) {
            return fields.get(key) instanceof String value ? Optional.of(value) : Optional.empty();
        }

        /** The string, or null when it's missing or blank. */
        public String text(String key) {
            return string(key).filter(value -> !value.isBlank()).orElse(null);
        }

        public Integer integer(String key) {
            return fields.get(key) instanceof Double value ? Integer.valueOf(value.intValue()) : null;
        }

        public Double number(String key) {
            return fields.get(key) instanceof Double value ? value : null;
        }

        public boolean bool(String key) {
            return fields.get(key) instanceof Boolean value && value;
        }

        public Obj object(String key) {
            return fields.get(key) instanceof Obj value ? value : new Obj(Map.of());
        }

        public List<Obj> objects(String key) {
            if (!(fields.get(key) instanceof List<?> list)) {
                return List.of();
            }
            return list.stream().filter(Obj.class::isInstance).map(Obj.class::cast).toList();
        }

        public List<String> strings(String key) {
            if (!(fields.get(key) instanceof List<?> list)) {
                return List.of();
            }
            return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }

        public List<Integer> integers(String key) {
            if (!(fields.get(key) instanceof List<?> list)) {
                return List.of();
            }
            return list.stream().filter(Double.class::isInstance).map(value -> ((Double) value).intValue()).toList();
        }
    }

    private Object value() {
        if (pos >= text.length()) {
            throw error("Unexpected end of input");
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> {
                if (c == '-' || Character.isDigit(c)) {
                    yield number();
                }
                throw error("Unexpected character '" + c + "'");
            }
        };
    }

    private Obj object() {
        pos++;
        Map<String, Object> fields = new LinkedHashMap<>();
        skipWhitespace();
        if (peek('}')) {
            pos++;
            return new Obj(fields);
        }
        while (true) {
            skipWhitespace();
            if (!peek('"')) {
                throw error("Expected a field name");
            }
            String key = string();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            fields.put(key, value());
            skipWhitespace();
            if (peek(',')) {
                pos++;
            } else {
                expect('}');
                return new Obj(fields);
            }
        }
    }

    private List<Object> array() {
        pos++;
        List<Object> values = new ArrayList<>();
        skipWhitespace();
        if (peek(']')) {
            pos++;
            return values;
        }
        while (true) {
            skipWhitespace();
            values.add(value());
            skipWhitespace();
            if (peek(',')) {
                pos++;
            } else {
                expect(']');
                return values;
            }
        }
    }

    private String string() {
        pos++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw error("Unterminated string");
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (pos >= text.length()) {
                throw error("Unterminated escape");
            }
            char escape = text.charAt(pos++);
            switch (escape) {
                case '"', '\\', '/' -> out.append(escape);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length()) {
                        throw error("Truncated unicode escape");
                    }
                    out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw error("Unknown escape '\\" + escape + "'");
            }
        }
    }

    private Double number() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        try {
            return Double.valueOf(text.substring(start, pos));
        } catch (NumberFormatException e) {
            throw error("Malformed number");
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) {
            throw error("Unexpected token");
        }
        pos += word.length();
        return value;
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private boolean peek(char c) {
        return pos < text.length() && text.charAt(pos) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw error("Expected '" + c + "'");
        }
        pos++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at offset " + pos);
    }
}
