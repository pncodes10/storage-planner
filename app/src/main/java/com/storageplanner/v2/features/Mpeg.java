package com.storageplanner.v2.features;

/** MPEG-1/2 audio and ADTS frame headers (strict two-frame rule, PHASE_2 1.2 rows 15/16). */
final class Mpeg {
    private Mpeg() {}
    private static final int[][] BITRATE = {   // [versionIndex 0 = MPEG1, 1 = MPEG2/2.5][layer 1..3 index] kbps by index
        {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320},   // MPEG1 layer III
        {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160},       // MPEG2/2.5 layer III / II
        {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384},  // MPEG1 layer II
        {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448}, // MPEG1 layer I
        {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256},  // MPEG2 layer I
    };
    private static final int[] RATE = {44100, 48000, 32000};

    /** Frame length in bytes of a valid MPEG audio header at p, else -1. Also returns via out[0..2] bitrate, sampleRate, channels. */
    static int frame(byte[] b, int n, int p, int[] out) {
        if (p < 0 || p + 4 > n) return -1;
        int h = (b[p] & 0xff) << 24 | (b[p + 1] & 0xff) << 16 | (b[p + 2] & 0xff) << 8 | (b[p + 3] & 0xff);
        if ((h & 0xFFE00000) != 0xFFE00000) return -1;
        int ver = (h >> 19) & 3, layer = (h >> 17) & 3, bri = (h >> 12) & 15, sri = (h >> 10) & 3, pad = (h >> 9) & 1;
        if (ver == 1 || layer == 0 || bri == 0 || bri == 15 || sri == 3) return -1;
        int rate = RATE[sri]; if (ver == 2) rate /= 2; else if (ver == 0) rate /= 4;
        boolean v1 = ver == 3;
        int kbps;
        if (layer == 1) kbps = v1 ? BITRATE[0][bri] : BITRATE[1][bri];
        else if (layer == 2) kbps = v1 ? BITRATE[2][bri] : BITRATE[1][bri];
        else kbps = v1 ? BITRATE[3][bri] : BITRATE[4][bri];
        int len;
        if (layer == 3) len = (12 * kbps * 1000 / rate + pad) * 4;
        else if (layer == 1 && !v1) len = 72 * kbps * 1000 / rate + pad;
        else len = 144 * kbps * 1000 / rate + pad;
        if (len < 21) return -1;
        if (out != null) { out[0] = kbps; out[1] = rate; out[2] = ((h >> 6) & 3) == 3 ? 1 : 2; out[3] = v1 ? 1 : 0; out[4] = layer; }
        return len;
    }

    static boolean framePair(byte[] b, int n, int p) {
        int l = frame(b, n, p, null);
        return l > 0 && frame(b, n, p + l, null) > 0;
    }

    static int adts(byte[] b, int n, int p) {
        if (p < 0 || p + 7 > n) return -1;
        if ((b[p] & 0xff) != 0xFF || ((b[p + 1] & 0xF6) != 0xF0)) return -1;
        int sfi = (b[p + 2] >> 2) & 15;
        if (sfi > 12) return -1;
        int len = ((b[p + 3] & 3) << 11) | ((b[p + 4] & 0xff) << 3) | ((b[p + 5] & 0xff) >> 5);
        return len >= 7 ? len : -1;
    }

    static boolean adtsPair(byte[] b, int n, int p) {
        int l = adts(b, n, p);
        return l > 0 && adts(b, n, p + l) > 0;
    }
}
