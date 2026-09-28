package fi.io;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class Json {

    private Json() {
    }

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("trailing content at " + parser.pos);
        }
        return value;
    }

    public static String writeCanonical(Object node) {
        StringBuilder builder = new StringBuilder();
        writeNode(node, builder);
        return builder.toString();
    }

    public static byte[] writeCanonicalBytes(Object node) {
        return writeCanonical(node).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static void writeNode(Object node, StringBuilder builder) {
        if (node == null) {
            builder.append("null");
        } else if (node instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), (Object) entry.getValue());
            }
            builder.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                writeString(entry.getKey(), builder);
                builder.append(':');
                writeNode(entry.getValue(), builder);
            }
            builder.append('}');
        } else if (node instanceof List<?> list) {
            builder.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                writeNode(item, builder);
            }
            builder.append(']');
        } else if (node instanceof String value) {
            writeString(value, builder);
        } else if (node instanceof Boolean value) {
            builder.append(value.booleanValue());
        } else if (node instanceof BigDecimal value) {
            builder.append(value.stripTrailingZeros().toPlainString());
        } else if (node instanceof Number value) {
            builder.append(value.toString());
        } else {
            throw new IllegalArgumentException("unsupported json node: " + node.getClass());
        }
    }

    private static void writeString(String value, StringBuilder builder) {
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                default -> {
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        builder.append('"');
    }

    private static final class Parser {
        private final String text;
        private int pos;

        private Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        Object readValue() {
            skipWhitespace();
            if (atEnd()) {
                throw new IllegalArgumentException("unexpected end of json");
            }
            char ch = text.charAt(pos);
            return switch (ch) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                Object value = readValue();
                if (map.containsKey(key)) {
                    throw new IllegalArgumentException("duplicate key: " + key);
                }
                map.put(key, value);
                skipWhitespace();
                char delimiter = next();
                if (delimiter == '}') {
                    return map;
                }
                if (delimiter != ',') {
                    throw new IllegalArgumentException("expected , or } at " + pos);
                }
            }
        }

        List<Object> readArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                char delimiter = next();
                if (delimiter == ']') {
                    return list;
                }
                if (delimiter != ',') {
                    throw new IllegalArgumentException("expected , or ] at " + pos);
                }
            }
        }

        String readString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (true) {
                char ch = next();
                if (ch == '"') {
                    return builder.toString();
                }
                if (ch == '\\') {
                    char escaped = next();
                    switch (escaped) {
                        case '"' -> builder.append('"');
                        case '\\' -> builder.append('\\');
                        case '/' -> builder.append('/');
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        case 'b' -> builder.append('\b');
                        case 'f' -> builder.append('\f');
                        case 'u' -> {
                            String hex = text.substring(pos, pos + 4);
                            pos += 4;
                            builder.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw new IllegalArgumentException(
                                "bad escape: " + escaped);
                    }
                } else {
                    builder.append(ch);
                }
            }
        }

        Boolean readBoolean() {
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("invalid literal at " + pos);
        }

        Object readNull() {
            if (text.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("invalid literal at " + pos);
        }

        BigDecimal readNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            boolean dot = false;
            boolean exponent = false;
            while (pos < text.length()) {
                char ch = text.charAt(pos);
                if (Character.isDigit(ch)) {
                    pos++;
                } else if (ch == '.' && !dot && !exponent) {
                    dot = true;
                    pos++;
                } else if ((ch == 'e' || ch == 'E') && !exponent) {
                    exponent = true;
                    pos++;
                    if (pos < text.length()
                            && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                        pos++;
                    }
                } else {
                    break;
                }
            }
            if (start == pos) {
                throw new IllegalArgumentException("invalid number at " + pos);
            }
            return new BigDecimal(text.substring(start, pos));
        }

        char peek() {
            if (atEnd()) {
                throw new IllegalArgumentException("unexpected end of json");
            }
            return text.charAt(pos);
        }

        char next() {
            char ch = peek();
            pos++;
            return ch;
        }

        void expect(char expected) {
            char actual = next();
            if (actual != expected) {
                throw new IllegalArgumentException(
                        "expected " + expected + " but got " + actual + " at " + pos);
            }
        }
    }
}
