package com.storageplanner.v2.store.disk;

import java.util.BitSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A sorted key → int map with a compact base (PHASE_S_STORE 3.5): immutable arrays {@code keyArena/keyOff} of keys in
 * {@link KeyCmp#pack} form (32-hex runs packed to 17 bytes) in String order, a value per base entry, a live bit per base entry (a removed base key stays in
 * the arrays until the next merge), and a delta {@code TreeMap} for keys not in the base. The delta is merged into a new
 * base when it (or the number of dead base entries) exceeds max(mergeMin, size/8).
 * <p>
 * {@link #range} takes a key snapshot that later writes and merges do not change: the base arrays are never modified in
 * place (a merge allocates new ones), the live bits of the range and the delta keys are copied. Not thread-safe; the
 * store serializes access.
 */
public final class KeyIndex {
    private static final byte[] EMPTY = new byte[0];
    private byte[] arena = EMPTY;
    private int[] off = new int[1];
    private int[] val = new int[0];
    private BitSet live = new BitSet();
    private int n, dead;
    private final TreeMap<String, Integer> delta = new TreeMap<String, Integer>();
    private int size;
    private final int mergeMin;
    private int merges;

    public KeyIndex(int mergeMin) { this.mergeMin = Math.max(1, mergeMin); }

    public int size() { return size; }
    public int merges() { return merges; }

    private byte[] key(int i) { byte[] k = new byte[off[i + 1] - off[i]]; System.arraycopy(arena, off[i], k, 0, k.length); return k; }
    private int cmp(int i, byte[] k) { return KeyCmp.comparePacked(arena, off[i], off[i + 1] - off[i], k, 0, k.length); }

    /** Index of the base entry equal to k, or -(insertion point) - 1. */
    private int find(byte[] k) {
        int lo = 0, hi = n - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = cmp(mid, k);
            if (c < 0) lo = mid + 1; else if (c > 0) hi = mid - 1; else return mid;
        }
        return -(lo + 1);
    }

    /** First base index whose key is ≥ k. */
    private int lowerBound(byte[] k) { int i = find(k); return i >= 0 ? i : -(i + 1); }

    /** The value of key, or -1. */
    public int get(String key) {
        if (n > 0) {
            int i = find(KeyCmp.pack(key));
            if (i >= 0) return live.get(i) ? val[i] : -1;
        }
        Integer v = delta.get(key);
        return v == null ? -1 : v;
    }

    /** Sets key → v (v ≥ 0); returns the previous value or -1. */
    public int put(String key, int v) {
        if (v < 0) throw new IllegalArgumentException("value " + v);
        if (n > 0) {
            int i = find(KeyCmp.pack(key));
            if (i >= 0) {
                int prev = live.get(i) ? val[i] : -1;
                if (prev < 0) { live.set(i); dead--; size++; }
                val[i] = v;
                return prev;
            }
        }
        Integer prev = delta.put(key, v);
        if (prev == null) { size++; maybeMerge(); return -1; }
        return prev;
    }

    /** Removes key; returns its value or -1. */
    public int remove(String key) {
        if (n > 0) {
            int i = find(KeyCmp.pack(key));
            if (i >= 0) {
                if (!live.get(i)) return -1;
                live.clear(i); dead++; size--;
                int prev = val[i];
                maybeMerge();
                return prev;
            }
        }
        Integer prev = delta.remove(key);
        if (prev == null) return -1;
        size--;
        return prev;
    }

    private void maybeMerge() {
        int limit = Math.max(mergeMin, size / 8);
        if (delta.size() > limit || dead > limit) merge();
    }

    /** Rebuilds the base from the live base entries and the delta (new arrays; snapshots keep the old ones). */
    public void merge() {
        if (delta.isEmpty() && dead == 0) return;
        int total = 0;
        for (int i = 0; i < n; i++) if (live.get(i)) total += off[i + 1] - off[i];
        byte[][] dk = new byte[delta.size()][];
        int[] dv = new int[dk.length];
        int j = 0;
        for (Map.Entry<String, Integer> e : delta.entrySet()) { dk[j] = KeyCmp.pack(e.getKey()); dv[j] = e.getValue(); total += dk[j].length; j++; }
        int m = size;
        byte[] na = new byte[total];
        int[] no = new int[m + 1];
        int[] nv = new int[m];
        int p = 0, q = 0, k = 0, pos = 0;
        while (true) {
            while (p < n && !live.get(p)) p++;
            boolean hasB = p < n, hasD = q < dk.length;
            if (!hasB && !hasD) break;
            boolean takeB = hasB && (!hasD || cmp(p, dk[q]) < 0);
            if (takeB) {
                int len = off[p + 1] - off[p];
                System.arraycopy(arena, off[p], na, pos, len);
                nv[k] = val[p]; pos += len; p++;
            } else {
                System.arraycopy(dk[q], 0, na, pos, dk[q].length);
                nv[k] = dv[q]; pos += dk[q].length; q++;
            }
            k++;
            no[k] = pos;
        }
        if (k != m) throw new IllegalStateException("key index merge: " + k + " entries, size " + m);
        arena = na; off = no; val = nv; n = m; dead = 0;
        live = new BitSet(m);
        live.set(0, m);
        delta.clear();
        merges++;
    }

    // ------------------------------------------------------------------ bulk load (index file)

    /** Appends a key greater than every key so far (bulk load into an empty index); call {@link #endLoad} after. */
    public void load(byte[] key, int v) {
        if (!delta.isEmpty() || dead != 0) throw new IllegalStateException("load into a used index");
        if (n > 0 && KeyCmp.comparePacked(arena, off[n - 1], off[n] - off[n - 1], key, 0, key.length) >= 0)
            throw new IllegalArgumentException("keys not ascending");
        if (n == val.length) { val = java.util.Arrays.copyOf(val, Math.max(16, n * 2)); off = java.util.Arrays.copyOf(off, val.length + 1); }
        if (off[n] + key.length > arena.length) arena = java.util.Arrays.copyOf(arena, Math.max(off[n] + key.length, Math.max(64, arena.length * 2)));
        System.arraycopy(key, 0, arena, off[n], key.length);
        off[n + 1] = off[n] + key.length;
        val[n] = v;
        live.set(n);
        n++; size++;
    }

    public void endLoad() {
        arena = java.util.Arrays.copyOf(arena, off[n]);
        off = java.util.Arrays.copyOf(off, n + 1);
        val = java.util.Arrays.copyOf(val, n);
    }

    /** Visits every entry in key order (merges first). */
    public void visit(Visitor v) throws java.io.IOException {
        merge();
        for (int i = 0; i < n; i++) v.entry(arena, off[i], off[i + 1] - off[i], val[i]);
    }
    public interface Visitor { void entry(byte[] arena, int off, int len, int value) throws java.io.IOException; }

    /** Approximate heap bytes (arrays, delta entries with their String keys). */
    public long bytesEstimate() {
        long b = arena.length + 16 + 4L * off.length + 16 + 4L * val.length + 16 + live.size() / 8 + 32;
        for (String k : delta.keySet()) b += 40 + 40 + 2L * k.length() + 16;   // TreeMap entry, String + char[]/byte[], Integer
        return b + 48;
    }

    // ------------------------------------------------------------------ snapshots

    /** Keys in [lo, hi) (hi null = no upper bound), ascending, as of now. */
    public Iterable<String> range(String lo, String hi) {
        final byte[] fa = arena;
        final int[] fo = off;
        byte[] lb = lo == null ? null : KeyCmp.pack(lo);
        byte[] hb = hi == null ? null : KeyCmp.pack(hi);
        final int from = lb == null ? 0 : lowerBound(lb);
        final int to = hb == null ? n : Math.max(from, lowerBound(hb));
        final BitSet lv = live.get(from, to);
        SortedMap<String, Integer> d = lo == null ? (hi == null ? delta : delta.headMap(hi)) : (hi == null ? delta.tailMap(lo) : delta.subMap(lo, hi));
        final String[] dk = d.keySet().toArray(new String[0]);
        return new Iterable<String>() {
            public Iterator<String> iterator() {
                return new Iterator<String>() {
                    int i = from, j = 0;
                    String b = nextBase();
                    String nextBase() {
                        while (i < to && !lv.get(i - from)) i++;
                        if (i >= to) return null;
                        String s = KeyCmp.unpack(fa, fo[i], fo[i + 1] - fo[i]);
                        i++;
                        return s;
                    }
                    public boolean hasNext() { return b != null || j < dk.length; }
                    public String next() {
                        if (b == null && j >= dk.length) throw new NoSuchElementException();
                        if (b != null && (j >= dk.length || b.compareTo(dk[j]) < 0)) { String r = b; b = nextBase(); return r; }
                        return dk[j++];
                    }
                    public void remove() { throw new UnsupportedOperationException(); }
                };
            }
        };
    }

    public Iterable<String> keys() { return range(null, null); }
}
