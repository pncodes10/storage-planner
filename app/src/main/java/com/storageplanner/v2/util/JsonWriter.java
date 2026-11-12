package com.storageplanner.v2.util;

import com.storageplanner.v2.model.Tri;
import com.storageplanner.v2.model.Unknown;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Streaming JSON writer with deterministic number formatting. IOExceptions of the underlying writer are rethrown
 * unchecked as UncheckedIo so call sites stay small; callers that persist data catch it.
 */
public final class JsonWriter {
    /** Wraps an IOException of the underlying Writer. */
    public static final class UncheckedIo extends RuntimeException {
        public final IOException io;
        public UncheckedIo(IOException e) { super(e); this.io = e; }
    }

    private final Writer out;
    private final boolean pretty;
    private final List<Boolean> first = new ArrayList<Boolean>();   // per open container: no element yet
    private boolean afterName;

    public JsonWriter(Writer out) { this(out, false); }
    public JsonWriter(Writer out, boolean pretty) { this.out = out; this.pretty = pretty; }

    private void w(String s) { try { out.write(s); } catch (IOException e) { throw new UncheckedIo(e); } }
    private void w(char c) { try { out.write(c); } catch (IOException e) { throw new UncheckedIo(e); } }

    private void indent() { w('\n'); for (int k = 0; k < first.size(); k++) w("  "); }

    private void beforeValue() {
        if (afterName) { afterName = false; return; }
        if (!first.isEmpty()) {
            int top = first.size() - 1;
            if (!first.get(top)) w(',');
            first.set(top, false);
            if (pretty) indent();
        }
    }

    public JsonWriter beginObject() { beforeValue(); w('{'); first.add(true); return this; }
    public JsonWriter endObject() { return close('}'); }
    public JsonWriter beginArray() { beforeValue(); w('['); first.add(true); return this; }
    public JsonWriter endArray() { return close(']'); }
    private JsonWriter close(char c) {
        boolean empty = first.remove(first.size() - 1);
        if (pretty && !empty) indent();
        w(c);
        return this;
    }

    public JsonWriter name(String n) {
        int top = first.size() - 1;
        if (!first.get(top)) w(',');
        first.set(top, false);
        if (pretty) indent();
        quote(n);
        w(pretty ? ": " : ":");
        afterName = true;
        return this;
    }

    public JsonWriter value(String v) { beforeValue(); if (v == null) w("null"); else quote(v); return this; }
    public JsonWriter value(long v) { beforeValue(); w(Long.toString(v)); return this; }
    public JsonWriter value(boolean v) { beforeValue(); w(v ? "true" : "false"); return this; }
    public JsonWriter value(double v) { beforeValue(); w(formatDouble(v)); return this; }
    public JsonWriter nullValue() { beforeValue(); w("null"); return this; }
    public JsonWriter value(Enum<?> e) { return value(e == null ? null : e.name()); }
    /** Unknown.TIME → null. */
    public JsonWriter valueTime(long t) { return Unknown.knownTime(t) ? value(t) : nullValue(); }
    /** Negative (unknown) count → null. */
    public JsonWriter valueCount(long v) { return v >= 0 ? value(v) : nullValue(); }
    /** Tri.UNKNOWN or null → null. */
    public JsonWriter value(Tri t) { return t == null || t == Tri.UNKNOWN ? nullValue() : value(t == Tri.TRUE); }
    public JsonWriter strings(List<String> l) {
        if (l == null) return nullValue();
        beginArray(); for (String s : l) value(s); return endArray();
    }

    /** Writes Map / List / String / Number / Boolean / null recursively (maps in their iteration order). */
    @SuppressWarnings("unchecked")
    public JsonWriter any(Object o) {
        if (o == null) return nullValue();
        if (o instanceof String) return value((String) o);
        if (o instanceof Boolean) return value((boolean) (Boolean) o);
        if (o instanceof Double || o instanceof Float) return value(((Number) o).doubleValue());
        if (o instanceof Number) return value(((Number) o).longValue());
        if (o instanceof Enum) return value((Enum<?>) o);
        if (o instanceof Map) {
            beginObject();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) { name(e.getKey()); any(e.getValue()); }
            return endObject();
        }
        if (o instanceof Iterable) { beginArray(); for (Object x : (Iterable<Object>) o) any(x); return endArray(); }
        throw new IllegalArgumentException("not JSON-writable: " + o.getClass().getName());
    }

    public void flush() { try { out.flush(); } catch (IOException e) { throw new UncheckedIo(e); } }

    /** At most 6 decimals, no exponent, "-0" → "0", always a '.' so it reads back as a double; NaN/Infinity → null. */
    public static String formatDouble(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "null";
        String s = new BigDecimal(v).setScale(6, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString();
        if (s.equals("-0") || s.startsWith("-0.000000") || s.equals("-0.0")) s = "0";
        if (s.indexOf('.') < 0) s = s + ".0";
        return s;
    }

    private void quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029 || c == 0x7f) appendU(b, c);
                    else if (Character.isHighSurrogate(c)) {
                        if (k + 1 < s.length() && Character.isLowSurrogate(s.charAt(k + 1))) { b.append(c).append(s.charAt(++k)); }
                        else appendU(b, c);
                    } else if (Character.isLowSurrogate(c)) appendU(b, c);
                    else b.append(c);
            }
        }
        b.append('"');
        w(b.toString());
    }

    private static void appendU(StringBuilder b, char c) {
        b.append("\\u");
        String h = Integer.toHexString(c);
        for (int k = h.length(); k < 4; k++) b.append('0');
        b.append(h);
    }
}
