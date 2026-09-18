package ru.itmo.hasd;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SchemaParser {
    private SchemaParser() { }

    static Schema parse(Path path) throws IOException {
        Object root = new Json(Files.readString(path, StandardCharsets.UTF_8)).parse();
        Map<String, Object> object = asObject(root, "schema");
        String delimiter = string(object, "delimiter", true);
        if (delimiter.length() != 1 || delimiter.charAt(0) == '\r' || delimiter.charAt(0) == '\n'
                || delimiter.charAt(0) == '"') {
            throw new IllegalArgumentException("delimiter must be one non-quote character");
        }
        boolean hasHeader = bool(object, "hasHeader", true, false);
        Object rawColumns = object.get("columns");
        if (!(rawColumns instanceof List<?> list)) throw new IllegalArgumentException("columns must be an array");
        List<Schema.Column> columns = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> column = asObject(list.get(i), "columns[" + i + "]");
            String name = string(column, "name", true);
            Schema.Type type;
            try {
                type = Schema.Type.valueOf(string(column, "type", true));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unsupported type in column " + name, e);
            }
            columns.add(new Schema.Column(name, type,
                    bool(column, "signed", false, false), bool(column, "nullable", false, false)));
        }
        return new Schema(delimiter.charAt(0), hasHeader, columns);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value, String at) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException(at + " must be an object");
        return (Map<String, Object>) value;
    }

    private static String string(Map<String, Object> object, String key, boolean required) {
        Object value = object.get(key);
        if (value instanceof String text) return text;
        if (!required && value == null) return null;
        throw new IllegalArgumentException(key + " must be a string");
    }

    private static boolean bool(Map<String, Object> object, String key, boolean required, boolean fallback) {
        Object value = object.get(key);
        if (value instanceof Boolean result) return result;
        if (!required && value == null) return fallback;
        throw new IllegalArgumentException(key + " must be a boolean");
    }

    // Небольшого рекурсивного парсера достаточно для JSON-структуры schema.json.
    private static final class Json {
        private final String text;
        private int position;

        Json(String text) { this.text = text; }

        Object parse() {
            skipWhitespace();
            Object value = value();
            skipWhitespace();
            if (position != text.length()) fail("Unexpected trailing content");
            return value;
        }

        private Object value() {
            skipWhitespace();
            if (position >= text.length()) fail("Expected value");
            return switch (text.charAt(position)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            position++;
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (take('}')) return result;
            while (true) {
                skipWhitespace();
                if (position >= text.length() || text.charAt(position) != '"') fail("Expected object key");
                String key = string();
                skipWhitespace();
                expect(':');
                if (result.containsKey(key)) fail("Duplicate key: " + key);
                result.put(key, value());
                skipWhitespace();
                if (take('}')) return result;
                expect(',');
            }
        }

        private List<Object> array() {
            position++;
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (take(']')) return result;
            while (true) {
                result.add(value());
                skipWhitespace();
                if (take(']')) return result;
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (position < text.length()) {
                char c = text.charAt(position++);
                if (c == '"') return result.toString();
                if (c == '\\') {
                    if (position >= text.length()) fail("Incomplete escape");
                    char escaped = text.charAt(position++);
                    switch (escaped) {
                        case '"', '\\', '/' -> result.append(escaped);
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'u' -> {
                            if (position + 4 > text.length()) fail("Incomplete unicode escape");
                            try {
                                result.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                            } catch (NumberFormatException e) {
                                fail("Invalid unicode escape");
                            }
                            position += 4;
                        }
                        default -> fail("Invalid escape");
                    }
                } else {
                    if (c < 0x20) fail("Control character in string");
                    result.append(c);
                }
            }
            fail("Unterminated string");
            return null;
        }

        private Object number() {
            int start = position;
            take('-');
            digits();
            if (take('.')) digits();
            if (position < text.length() && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
                position++;
                if (position < text.length() && (text.charAt(position) == '+' || text.charAt(position) == '-')) position++;
                digits();
            }
            try {
                return Double.valueOf(text.substring(start, position));
            } catch (NumberFormatException e) {
                fail("Invalid number");
                return null;
            }
        }

        private void digits() {
            int start = position;
            while (position < text.length() && Character.isDigit(text.charAt(position))) position++;
            if (start == position) fail("Expected digit");
        }

        private Object literal(String expected, Object value) {
            if (!text.startsWith(expected, position)) fail("Expected " + expected);
            position += expected.length();
            return value;
        }

        private void skipWhitespace() {
            while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) position++;
        }

        private boolean take(char expected) {
            if (position < text.length() && text.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) fail("Expected '" + expected + "'");
        }

        private void fail(String message) {
            throw new IllegalArgumentException(message + " at JSON offset " + position);
        }
    }
}
