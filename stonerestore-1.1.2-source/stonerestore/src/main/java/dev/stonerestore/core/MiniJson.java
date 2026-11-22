package dev.stonerestore.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Small strict JSON parser (objects -> LinkedHashMap, arrays -> ArrayList, numbers -> Double, strings,
 * booleans, null). Only used to compare resource-pack blockstates/models with the vanilla ones, so
 * that "same content, different whitespace" is not mistaken for a modification.
 */
public final class MiniJson {

    private static final int MAX_DEPTH = 64;

    private final String s;
    private int pos;

    private MiniJson(String s) {
        this.s = s;
    }

    /** @throws IllegalArgumentException if the data is not valid JSON */
    public static Object parse(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        MiniJson p = new MiniJson(text);
        p.skipWs();
        Object value = p.value(0);
        p.skipWs();
        if (p.pos != text.length()) throw p.error("trailing data");
        return value;
    }

    /** True if any string anywhere in the tree equals one of the needles. */
    public static boolean mentions(Object tree, Set<String> needles) {
        if (tree instanceof String str) return needles.contains(str);
        if (tree instanceof Map<?, ?> map) {
            for (Object v : map.values()) if (mentions(v, needles)) return true;
        } else if (tree instanceof List<?> list) {
            for (Object v : list) if (mentions(v, needles)) return true;
        }
        return false;
    }

    /** How many times an object key with this name appears anywhere in the tree (e.g. "tintindex"). */
    public static int countKey(Object tree, String key) {
        int n = 0;
        if (tree instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (key.equals(e.getKey())) n++;
                n += countKey(e.getValue(), key);
            }
        } else if (tree instanceof List<?> list) {
            for (Object v : list) n += countKey(v, key);
        }
        return n;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw error("nesting too deep");
        if (pos >= s.length()) throw error("unexpected end");
        char c = s.charAt(pos);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        skipWs();
        if (peek() == '}') { pos++; return map; }
        while (true) {
            skipWs();
            if (peek() != '"') throw error("object key expected");
            String key = string();
            skipWs();
            expect(':');
            skipWs();
            map.put(key, value(depth + 1));
            skipWs();
            char c = next();
            if (c == '}') return map;
            if (c != ',') throw error("',' or '}' expected");
        }
    }

    private List<Object> array(int depth) {
        List<Object> list = new ArrayList<>();
        pos++; // [
        skipWs();
        if (peek() == ']') { pos++; return list; }
        while (true) {
            skipWs();
            list.add(value(depth + 1));
            skipWs();
            char c = next();
            if (c == ']') return list;
            if (c != ',') throw error("',' or ']' expected");
        }
    }

    private String string() {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= s.length()) throw error("unterminated string");
            char c = s.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c < 0x20) throw error("control character in string");
            if (c != '\\') { sb.append(c); continue; }
            if (pos >= s.length()) throw error("bad escape");
            char e = s.charAt(pos++);
            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > s.length()) throw error("bad \\u escape");
                    try {
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad \\u escape");
                    }
                    pos += 4;
                }
                default -> throw error("bad escape");
            }
        }
    }

    private Object number() {
        int start = pos;
        while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
        if (start == pos) throw error("value expected");
        try {
            return Double.parseDouble(s.substring(start, pos));
        } catch (NumberFormatException e) {
            throw error("bad number");
        }
    }

    private Object literal(String word, Object value) {
        if (!s.startsWith(word, pos)) throw error("value expected");
        pos += word.length();
        return value;
    }

    private void skipWs() {
        while (pos < s.length() && " \t\r\n".indexOf(s.charAt(pos)) >= 0) pos++;
    }

    private char peek() {
        if (pos >= s.length()) throw error("unexpected end");
        return s.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) throw error("'" + c + "' expected");
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("invalid JSON at " + pos + ": " + message);
    }
}
