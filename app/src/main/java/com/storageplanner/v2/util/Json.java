package com.storageplanner.v2.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal strict JSON reader. Objects become LinkedHashMap, arrays ArrayList, integers Long (Double if they do
 * not fit), other numbers Double, plus String, Boolean and null. Nesting deeper than 64 is rejected.
 */
public final class Json {
    public static final int MAX_DEPTH = 64;
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) throws JsonException {
        Json p = new Json(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != text.length()) throw new JsonException("trailing characters", p.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) throws JsonException {
        Object o = parse(text);
        if (!(o instanceof Map)) throw new JsonException("not an object", 0);
        return (Map<String, Object>) o;
    }

    private void ws() { while (i < s.length()) { char c = s.charAt(i); if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break; } }

    private Object value(int depth) throws JsonException {
        if (depth > MAX_DEPTH) throw new JsonException("nesting deeper than " + MAX_DEPTH, i);
        if (i >= s.length()) throw new JsonException("unexpected end", i);
        char c = s.charAt(i);
        switch (c) {
            case '{': return object(depth + 1);
            case '[': return array(depth + 1);
            case '"': return string();
            case 't': word("true"); return Boolean.TRUE;
            case 'f': word("false"); return Boolean.FALSE;
            case 'n': word("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw new JsonException("unexpected character '" + c + "'", i);
        }
    }

    private void word(String w) throws JsonException {
        if (!s.startsWith(w, i)) throw new JsonException("bad literal", i);
        i += w.length();
    }

    private Map<String, Object> object(int depth) throws JsonException {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++; ws();
        if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw new JsonException("expected key", i);
            String k = string();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') throw new JsonException("expected ':'", i);
            i++; ws();
            m.put(k, value(depth));
            ws();
            if (i >= s.length()) throw new JsonException("unterminated object", i);
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw new JsonException("expected ',' or '}'", i - 1);
        }
    }

    private List<Object> array(int depth) throws JsonException {
        List<Object> l = new ArrayList<Object>();
        i++; ws();
        if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value(depth));
            ws();
            if (i >= s.length()) throw new JsonException("unterminated array", i);
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw new JsonException("expected ',' or ']'", i - 1);
        }
    }

    private String string() throws JsonException {
        int start = i;
        i++;
        StringBuilder b = null;
        int runStart = i;
        while (true) {
            if (i >= s.length()) throw new JsonException("unterminated string", start);
            char c = s.charAt(i);
            if (c == '"') {
                String r = b == null ? s.substring(runStart, i) : b.append(s, runStart, i).toString();
                i++;
                return r;
            }
            if (c < 0x20) throw new JsonException("control character in string", i);
            if (c == '\\') {
                if (b == null) b = new StringBuilder();
                b.append(s, runStart, i);
                if (i + 1 >= s.length()) throw new JsonException("unterminated escape", i);
                char e = s.charAt(i + 1);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        if (i + 6 > s.length()) throw new JsonException("bad unicode escape", i);
                        try { b.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16)); }
                        catch (NumberFormatException x) { throw new JsonException("bad unicode escape", i); }
                        i += 4;
                        break;
                    default: throw new JsonException("bad escape", i);
                }
                i += 2;
                runStart = i;
                continue;
            }
            i++;
        }
    }

    private Object number() throws JsonException {
        int start = i;
        if (s.charAt(i) == '-') i++;
        int digits = i;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i == digits) throw new JsonException("bad number", start);
        if (s.charAt(digits) == '0' && i - digits > 1) throw new JsonException("leading zero", start);
        boolean real = false;
        if (i < s.length() && s.charAt(i) == '.') {
            real = true; i++;
            int f = i;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i == f) throw new JsonException("bad fraction", start);
        }
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            real = true; i++;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            int f = i;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i == f) throw new JsonException("bad exponent", start);
        }
        String t = s.substring(start, i);
        if (!real) {
            try { return Long.valueOf(Long.parseLong(t)); } catch (NumberFormatException x) { /* too large: fall through */ }
        }
        return Double.valueOf(Double.parseDouble(t));
    }

    // ---- typed accessors used by codecs ----

    public static String str(Map<String, Object> m, String k) { Object o = m.get(k); return o instanceof String ? (String) o : null; }
    public static long lng(Map<String, Object> m, String k, long unknown) {
        Object o = m.get(k);
        if (o instanceof Long) return (Long) o;
        if (o instanceof Double) return (long) (double) (Double) o;
        return unknown;
    }
    public static int integer(Map<String, Object> m, String k, int unknown) { Object o = m.get(k); return o instanceof Number ? ((Number) o).intValue() : unknown; }
    public static double dbl(Map<String, Object> m, String k) { Object o = m.get(k); return o instanceof Number ? ((Number) o).doubleValue() : Double.NaN; }
    public static boolean bool(Map<String, Object> m, String k, boolean dflt) { Object o = m.get(k); return o instanceof Boolean ? (Boolean) o : dflt; }
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String k) { Object o = m.get(k); return o instanceof Map ? (Map<String, Object>) o : null; }
    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String k) { Object o = m.get(k); return o instanceof List ? (List<Object>) o : null; }
    public static List<String> strings(Map<String, Object> m, String k) {
        List<Object> l = list(m, k);
        if (l == null) return null;
        List<String> r = new ArrayList<String>();
        for (Object o : l) if (o instanceof String) r.add((String) o);
        return r;
    }
    public static <E extends Enum<E>> E enumOf(Map<String, Object> m, String k, Class<E> type, E unknown) {
        String v = str(m, k);
        if (v == null) return unknown;
        try { return Enum.valueOf(type, v); } catch (IllegalArgumentException e) { return unknown; }
    }
}
