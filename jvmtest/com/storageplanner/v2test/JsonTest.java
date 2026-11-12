package com.storageplanner.v2test;

import com.storageplanner.v2.model.Unknown;
import com.storageplanner.v2.util.Json;
import com.storageplanner.v2.util.JsonException;
import com.storageplanner.v2.util.JsonWriter;

import java.io.StringWriter;
import java.util.*;

public final class JsonTest {
    static String write(Object o) { StringWriter s = new StringWriter(); new JsonWriter(s).any(o); return s.toString(); }

    static void malformed(String name, String text) {
        try { Json.parse(text); T.check(name + " rejected", false); }
        catch (JsonException e) { T.check(name + " rejected with offset", e.offset >= 0); }
    }

    public static int run(String[] args) throws Exception {
        T.section("Json");
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("s", "q\"b\\s\n\t\u0001 \u2028 \uD83D\uDE00 \u00e9");
        m.put("n", Long.MAX_VALUE);
        m.put("neg", -42L);
        m.put("d", 1.5);
        m.put("t", Boolean.TRUE);
        m.put("z", null);
        List<Object> l = new ArrayList<Object>();
        l.add(1L); l.add("x"); Map<String, Object> in = new LinkedHashMap<String, Object>(); in.put("k", new ArrayList<Object>()); l.add(in);
        m.put("l", l);
        String text = write(m);
        Object back = Json.parse(text);
        T.eq("round trip", m, back);
        T.check("Long.MAX_VALUE stays Long", ((Map<?, ?>) back).get("n") instanceof Long);
        T.check("quote escaped", text.contains("q\\\"b"));
        T.check("backslash escaped", text.contains("b\\\\s"));
        T.check("control char escaped", text.contains("\\u0001"));
        T.check("U+2028 escaped", text.contains("\\u2028"));
        T.check("emoji raw surrogate pair", text.contains("\uD83D\uDE00"));
        T.eq("lone surrogate escaped", "\"\\ud83d\"", write("\uD83D"));
        T.eq("double 6 decimals", "0.333333", JsonWriter.formatDouble(1.0 / 3));
        T.eq("double no exponent", "12345678901.0", JsonWriter.formatDouble(1.2345678901e10));
        T.eq("tiny double", "0.0", JsonWriter.formatDouble(1e-9));
        T.eq("-0.0 → 0", JsonWriter.formatDouble(0.0), JsonWriter.formatDouble(-0.0));
        T.check("-0.0 no sign", !JsonWriter.formatDouble(-0.0).startsWith("-"));
        T.eq("NaN → null", "null", JsonWriter.formatDouble(Double.NaN));
        T.eq("Infinity → null", "null", JsonWriter.formatDouble(Double.POSITIVE_INFINITY));
        StringWriter sw = new StringWriter();
        new JsonWriter(sw).beginArray().valueTime(Unknown.TIME).valueTime(5).valueCount(-1).endArray();
        T.eq("valueTime(Unknown.TIME) → null", "[null,5,null]", sw.toString());
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 65; i++) deep.append('[');
        for (int i = 0; i < 65; i++) deep.append(']');
        malformed("depth 65", deep.toString());
        StringBuilder ok = new StringBuilder();
        for (int i = 0; i < 64; i++) ok.append('[');
        for (int i = 0; i < 64; i++) ok.append(']');
        T.check("depth 64 accepted", Json.parse(ok.toString()) instanceof List);
        malformed("trailing comma", "[1,2,]");
        malformed("trailing comma object", "{\"a\":1,}");
        malformed("unterminated string", "[\"abc");
        malformed("bare word", "[hello]");
        malformed("trailing garbage", "{} x");
        sw = new StringWriter();
        new JsonWriter(sw, true).beginObject().name("a").beginArray().value(1).endArray().name("b").beginObject().endObject().endObject();
        T.eq("pretty parses", "{\"a\":[1],\"b\":{}}", write(Json.parse(sw.toString())));
        return T.finish("JsonTest");
    }
}
