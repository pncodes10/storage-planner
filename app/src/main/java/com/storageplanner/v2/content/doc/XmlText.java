package com.storageplanner.v2.content.doc;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Minimal XML text scanner for OOXML and ODF parts (PHASE_6 7.3): a character scanner, no DOM, no JAXP parser, no entity
 * expansion beyond the five predefined ones and numeric references (so a DOCTYPE "billion laughs" cannot grow). Bounded: the input is
 * at most the caller's byte cap, the output at most {@code maxChars}, the element depth at most {@link #MAX_DEPTH}.
 */
public final class XmlText {
    private XmlText() {}

    public static final int MAX_DEPTH = 256;

    public static final class Out {
        public final StringBuilder text = new StringBuilder();
        /** The scan stopped early: depth above 256, a tag without its closing '&gt;', or an end tag that does not match. */
        public boolean malformed;
        /** The output reached maxChars. */
        public boolean full;
    }

    /**
     * Text of the elements named in {@code textElements} (character data, CDATA included), a '\n' after the end of every element in
     * {@code breakElements}. Element names are matched with their prefix exactly as written ({@code w:t}, {@code a:p}, {@code t}).
     */
    public static Out text(byte[] xml, Set<String> textElements, Set<String> breakElements, int maxChars) {
        Out out = new Out();
        String s = new String(xml, StandardCharsets.UTF_8);
        int n = s.length(), i = 0, depth = 0, textDepth = 0;
        String[] stack = new String[MAX_DEPTH + 1];
        boolean[] isText = new boolean[MAX_DEPTH + 1];
        StringBuilder sb = out.text;
        if (n > 0 && s.charAt(0) == '﻿') i = 1;
        while (i < n) {
            if (sb.length() >= maxChars) { out.full = true; break; }
            char c = s.charAt(i);
            if (c != '<') {
                int e = s.indexOf('<', i);
                if (e < 0) e = n;
                if (textDepth > 0) append(s, i, e, sb, maxChars);
                i = e;
                continue;
            }
            if (s.startsWith("<?", i)) { int e = s.indexOf("?>", i + 2); if (e < 0) { out.malformed = true; break; } i = e + 2; continue; }
            if (s.startsWith("<!--", i)) { int e = s.indexOf("-->", i + 4); if (e < 0) { out.malformed = true; break; } i = e + 3; continue; }
            if (s.startsWith("<![CDATA[", i)) {
                int e = s.indexOf("]]>", i + 9);
                if (e < 0) { out.malformed = true; break; }
                if (textDepth > 0) { int room = maxChars - sb.length(); sb.append(s, i + 9, Math.min(e, i + 9 + Math.max(0, room))); }
                i = e + 3;
                continue;
            }
            if (s.startsWith("<!", i)) { int e = s.indexOf('>', i + 2); if (e < 0) { out.malformed = true; break; } i = e + 1; continue; }
            int e = tagEnd(s, i + 1);
            if (e < 0) { out.malformed = true; break; }
            if (i + 1 < n && s.charAt(i + 1) == '/') {                       // end tag
                String name = name(s, i + 2, e);
                if (depth == 0 || !name.equals(stack[depth - 1])) { out.malformed = true; break; }
                depth--;
                if (isText[depth]) textDepth--;
                if (breakElements.contains(name)) sb.append('\n');
            } else {                                                          // start tag, maybe self-closing
                String name = name(s, i + 1, e);
                boolean selfClose = e > i + 1 && s.charAt(e - 1) == '/';
                if (selfClose) {
                    if (breakElements.contains(name)) sb.append('\n');
                } else {
                    if (depth >= MAX_DEPTH) { out.malformed = true; break; }
                    stack[depth] = name;
                    isText[depth] = textElements.contains(name);
                    if (isText[depth]) textDepth++;
                    depth++;
                }
            }
            i = e + 1;
        }
        if (sb.length() > maxChars) sb.setLength(maxChars);
        return out;
    }

    /** Index of the '&gt;' that closes the tag starting after '&lt;' (quotes in attribute values respected), or -1. */
    private static int tagEnd(String s, int from) {
        char q = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q != 0) { if (c == q) q = 0; }
            else if (c == '"' || c == '\'') q = c;
            else if (c == '>') return i;
        }
        return -1;
    }

    private static String name(String s, int from, int to) {
        int e = from;
        while (e < to && !Character.isWhitespace(s.charAt(e)) && s.charAt(e) != '/') e++;
        return s.substring(from, e);
    }

    /** Appends s[from, to) with entities decoded; unknown entities are kept literally. */
    private static void append(String s, int from, int to, StringBuilder sb, int maxChars) {
        int i = from;
        while (i < to && sb.length() < maxChars) {
            char c = s.charAt(i);
            if (c != '&') { sb.append(c); i++; continue; }
            int e = s.indexOf(';', i);
            if (e < 0 || e > to || e - i > 12) { sb.append(c); i++; continue; }
            String ent = s.substring(i + 1, e);
            String r = null;
            if (ent.equals("amp")) r = "&";
            else if (ent.equals("lt")) r = "<";
            else if (ent.equals("gt")) r = ">";
            else if (ent.equals("quot")) r = "\"";
            else if (ent.equals("apos")) r = "'";
            else if (ent.length() > 1 && ent.charAt(0) == '#') {
                try {
                    int cp = ent.charAt(1) == 'x' || ent.charAt(1) == 'X' ? Integer.parseInt(ent.substring(2), 16) : Integer.parseInt(ent.substring(1));
                    if (cp > 0 && cp <= 0x10FFFF && !(cp >= 0xD800 && cp <= 0xDFFF)) r = new String(Character.toChars(cp));
                } catch (NumberFormatException ex) { r = null; }
            }
            if (r == null) { sb.append(c); i++; }
            else { sb.append(r); i = e + 1; }
        }
    }

    /** Decoded character data of the first element called {@code name} (nested markup inside it is skipped), at most 200 chars; null when absent. */
    public static String firstText(byte[] xml, String name) {
        String s = new String(xml, StandardCharsets.UTF_8);
        int i = 0;
        String open = "<" + name;
        while ((i = s.indexOf(open, i)) >= 0) {
            int p = i + open.length();
            if (p < s.length() && s.charAt(p) != '>' && s.charAt(p) != '/' && !Character.isWhitespace(s.charAt(p))) { i = p; continue; }
            int e = tagEnd(s, p);
            if (e < 0) return null;
            if (s.charAt(e - 1) == '/') return null;
            int close = s.indexOf("</" + name + ">", e);
            if (close < 0) return null;
            StringBuilder sb = new StringBuilder();
            boolean inTag = false;
            for (int k = e + 1; k < close && sb.length() < 200; k++) {
                char c = s.charAt(k);
                if (c == '<') inTag = true;
                else if (c == '>') inTag = false;
                else if (!inTag) sb.append(c);
            }
            StringBuilder dec = new StringBuilder();
            append(sb.toString(), 0, sb.length(), dec, 200);
            String v = dec.toString().trim();
            return v.isEmpty() ? null : v;
        }
        return null;
    }

    /** Value of attribute {@code attr} on the first start tag called {@code element}; null when absent. */
    public static String attribute(byte[] xml, String element, String attr) {
        String s = new String(xml, StandardCharsets.UTF_8);
        String open = "<" + element;
        int i = 0;
        while ((i = s.indexOf(open, i)) >= 0) {
            int p = i + open.length();
            if (p < s.length() && !Character.isWhitespace(s.charAt(p)) && s.charAt(p) != '/' && s.charAt(p) != '>') { i = p; continue; }
            int e = tagEnd(s, p);
            if (e < 0) return null;
            String tag = s.substring(p, e);
            int a = tag.indexOf(" " + attr + "=");
            if (a < 0) { a = tag.indexOf("\n" + attr + "="); }
            if (a < 0) return null;
            int q = a + attr.length() + 2;
            if (q >= tag.length()) return null;
            char quote = tag.charAt(q);
            if (quote != '"' && quote != '\'') return null;
            int end = tag.indexOf(quote, q + 1);
            return end < 0 ? null : tag.substring(q + 1, end);
        }
        return null;
    }
}
