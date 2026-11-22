package com.storageplanner.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, strict JSON reader: objects become LinkedHashMap (key order kept), arrays ArrayList, numbers Double,
 * plus String, Boolean and null. Throws IllegalArgumentException on anything that is not JSON.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.space();
        Object v = j.value(0);
        j.space();
        if (j.i != text.length()) throw j.bad("text after the value");
        return v;
    }

    private IllegalArgumentException bad(String why) { return new IllegalArgumentException("JSON: " + why + " at " + i); }

    private void space() { while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++; }

    private Object value(int depth) {
        if (depth > 64) throw bad("nested too deep");
        if (i >= s.length()) throw bad("unexpected end");
        char c = s.charAt(i);
        if (c == '{') {
            i++;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            space();
            if (peek('}')) return m;
            while (true) {
                space();
                if (i >= s.length() || s.charAt(i) != '"') throw bad("expected a key");
                String k = string();
                space();
                expect(':');
                space();
                m.put(k, value(depth + 1));
                space();
                if (peek('}')) return m;
                expect(',');
            }
        }
        if (c == '[') {
            i++;
            List<Object> l = new ArrayList<Object>();
            space();
            if (peek(']')) return l;
            while (true) {
                space();
                l.add(value(depth + 1));
                space();
                if (peek(']')) return l;
                expect(',');
            }
        }
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        int a = i;
        if (c == '-') i++;
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
        if (a == i || (c == '-' && a + 1 == i)) throw bad("unexpected '" + c + "'");
        try { return Double.valueOf(s.substring(a, i)); } catch (NumberFormatException e) { throw bad("bad number"); }
    }

    private boolean peek(char c) { if (i < s.length() && s.charAt(i) == c) { i++; return true; } return false; }

    private void expect(char c) { if (!peek(c)) throw bad("expected '" + c + "'"); }

    private String string() {
        i++;                                                    // the opening quote
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw bad("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) throw bad("unterminated string");
            char d = s.charAt(i++);
            switch (d) {
                case '"': case '\\': case '/': b.append(d); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw bad("short \\u escape");
                    try { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); } catch (NumberFormatException e) { throw bad("bad \\u escape"); }
                    i += 4;
                    break;
                default: throw bad("bad escape");
            }
        }
    }

    // ---- reading helpers (null-safe)
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o, String key) {
        Object v = o instanceof Map ? ((Map<String, Object>) o).get(key) : null;
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o, String key) {
        Object v = o instanceof Map ? ((Map<String, Object>) o).get(key) : null;
        return v instanceof List ? (List<Object>) v : new ArrayList<Object>();
    }

    @SuppressWarnings("unchecked")
    public static String str(Object o, String key) {
        Object v = o instanceof Map ? ((Map<String, Object>) o).get(key) : null;
        return v instanceof String ? (String) v : null;
    }

    @SuppressWarnings("unchecked")
    public static double num(Object o, String key, double dflt) {
        Object v = o instanceof Map ? ((Map<String, Object>) o).get(key) : null;
        return v instanceof Double ? (Double) v : dflt;
    }
}
