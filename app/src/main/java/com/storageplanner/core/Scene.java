package com.storageplanner.core;

/**
 * What a photo shows: food, nature, document, pet, vehicle, sky, selfie or other.
 *
 * Food, nature, pet and vehicle come from SceneNet's 1000 ImageNet classes, added up per group (all dog and cat
 * breeds are "pet", the dishes, fruit and vegetables are "food", and so on: see the lists below). A photo is put in a
 * group when the group gets at least MIN_SHARE of the network's belief and more than any other group.
 * Document comes from the picture's category (receipts, bills, papers, tickets, ID cards); selfie from the faces (a
 * face close to the camera); sky from the pixels (a smooth blue, sunset or cloudy area that reaches the top edge and
 * fills much of the picture), because ImageNet has no sky class.
 */
public final class Scene {
    private Scene() {}

    public static final String FOOD = "food", NATURE = "nature", DOCUMENT = "document", PET = "pet", VEHICLE = "vehicle",
            SKY = "sky", SELFIE = "selfie", OTHER = "other";
    public static final String[] ALL = {FOOD, NATURE, DOCUMENT, PET, VEHICLE, SKY, SELFIE, OTHER};

    /** The groups SceneNet decides, in the order of shares(). */
    public static final String[] GROUPS = {FOOD, NATURE, PET, VEHICLE};
    /** A group needs at least this share of the network's belief. */
    public static final double MIN_SHARE = 0.4;
    /** Sky: at least this share of the picture. */
    public static final double MIN_SKY = 0.45;
    /** Selfie: the biggest face's eyes are at least this share of the picture's short side apart. */
    public static final double SELFIE_FACE = 0.09;

    /** ImageNet class ranges per group: {from, to} inclusive. */
    static final int[][] FOOD_CLASSES = {{923, 945}, {947, 957}, {959, 969}, {809, 809}, {567, 567}, {909, 909}, {415, 415}, {509, 509},
            {762, 762}, {532, 532}, {659, 659}, {504, 504}, {987, 987}, {868, 868}, {544, 544}, {469, 469}, {521, 521}};
    static final int[][] PET_CLASSES = {{151, 268}, {281, 285}, {330, 333}, {338, 338}, {1, 1}};
    static final int[][] VEHICLE_CLASSES = {{403, 405}, {407, 408}, {436, 436}, {444, 444}, {466, 466}, {468, 468}, {472, 472},
            {475, 475}, {479, 479}, {484, 484}, {510, 511}, {535, 535}, {547, 547}, {554, 555}, {561, 561}, {565, 565},
            {569, 569}, {573, 573}, {575, 576}, {581, 581}, {586, 586}, {595, 595}, {603, 603}, {609, 609}, {612, 612},
            {625, 625}, {627, 628}, {654, 654}, {656, 656}, {661, 661}, {665, 665}, {670, 671}, {675, 675}, {690, 690},
            {694, 694}, {705, 705}, {717, 717}, {724, 724}, {734, 734}, {751, 751}, {757, 757}, {779, 780}, {802, 803},
            {812, 812}, {814, 814}, {817, 817}, {820, 820}, {829, 829}, {833, 833}, {847, 847}, {864, 864}, {866, 867},
            {870, 871}, {874, 874}, {880, 880}, {895, 895}, {908, 908}, {914, 914}};
    /**
     * Wild animals, birds, insects and sea life (every animal class that is not a pet), landscapes, plants, flowers, fungi.
     * ImageNet has no rose or tulip: flower photos land on vase, greenhouse, picket fence (gardens) and cardoon, so
     * those count as nature too (on 3,670 flower photos: 80% nature instead of 75%, and no other picture changed).
     */
    static final int[][] NATURE_CLASSES = {{0, 0}, {2, 150}, {269, 280}, {286, 329}, {334, 337}, {339, 397}, {970, 970},
            {972, 980}, {984, 986}, {988, 997}, {738, 738}, {449, 449}, {460, 460},
            {580, 580}, {716, 716}, {883, 883}, {946, 946}};

    private static final int[][][] CLASSES = {FOOD_CLASSES, NATURE_CLASSES, PET_CLASSES, VEHICLE_CLASSES};

    /** Share of the network's belief per group (GROUPS order), from its class probabilities. */
    public static float[] shares(float[] probabilities) {
        float[] s = new float[GROUPS.length];
        for (int g = 0; g < GROUPS.length; g++)
            for (int[] r : CLASSES[g]) for (int c = r[0]; c <= r[1]; c++) s[g] += probabilities[c];
        return s;
    }

    /** The group SceneNet's shares point to, or null when none is clear. */
    public static String group(float[] shares) {
        if (shares == null) return null;
        int b = -1;
        for (int g = 0; g < shares.length && g < GROUPS.length; g++) if (b < 0 || shares[g] > shares[b]) b = g;
        return b >= 0 && shares[b] >= MIN_SHARE ? GROUPS[b] : null;
    }

    /** Whether a picture category is a paper or card (shown as the "document" scene). */
    public static boolean document(String category) {
        return PhotoCategory.DOCUMENT.equals(category) || PhotoCategory.RECEIPT.equals(category) || PhotoCategory.BILL.equals(category)
                || PhotoCategory.TICKET.equals(category) || PhotoCategory.ID.equals(category);
    }

    /**
     * The scene of a picture, or null when it is not known yet (not looked at by the scene network). Documents and
     * selfies are known without the network.
     */
    public static String of(FileItem f) {
        if (document(f.category)) return DOCUMENT;
        if (f.faceSize >= SELFIE_FACE) return SELFIE;
        if (f.scene == null) return null;
        String g = group(f.scene);
        if (g != null) return g;
        if (f.scene.length > GROUPS.length && f.scene[GROUPS.length] >= MIN_SKY) return SKY;
        return OTHER;
    }

    /**
     * Share of the picture that is sky: smooth pixels coloured like sky (blue, sunset, or bright cloud) joined to the
     * top edge. ARGB pixels, any size (a small copy is enough). At least half of the area must be blue or sunset
     * coloured, so a white wall or ceiling is not sky.
     */
    public static double sky(int[] px, int w, int h) {
        if (w < 3 || h < 3) return 0;
        int n = w * h;
        int[] l = new int[n];
        for (int i = 0; i < n; i++) { int p = px[i]; l[i] = (299 * ((p >> 16) & 255) + 587 * ((p >> 8) & 255) + 114 * (p & 255)) / 1000; }
        byte[] kind = new byte[n];                       // 0 not sky, 1 cloud (bright grey), 2 blue or sunset
        for (int y = 1; y < h - 1; y++)
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                int g = Math.abs(l[i + 1] - l[i - 1]) + Math.abs(l[i + w] - l[i - w]);
                if (g > 14) continue;                                     // texture: not sky
                int p = px[i], r = (p >> 16) & 255, gr = (p >> 8) & 255, b = p & 255;
                int mx = Math.max(r, Math.max(gr, b)), mn = Math.min(r, Math.min(gr, b));
                if (b > r + 15 && b >= gr - 5 && l[i] > 70) kind[i] = 2;                       // blue sky
                else if (r > b + 25 && r >= gr && l[i] > 80 && gr > b) kind[i] = 2;           // sunset / dawn
                else if (l[i] > 150 && mx - mn < 30) kind[i] = 1;                              // white or grey cloud
            }
        for (int x = 0; x < w; x++) { kind[x] = kind[w + x]; }                                 // the top row counts as its neighbour
        boolean[] seen = new boolean[n];
        int[] stack = new int[n];
        int top = 0, area = 0, coloured = 0;
        for (int x = 0; x < w; x++) if (kind[x] != 0) { seen[x] = true; stack[top++] = x; }
        while (top > 0) {
            int i = stack[--top];
            area++;
            if (kind[i] == 2) coloured++;
            int x = i % w, y = i / w;
            if (x > 0) top = push(kind, seen, stack, top, i - 1);
            if (x < w - 1) top = push(kind, seen, stack, top, i + 1);
            if (y > 0) top = push(kind, seen, stack, top, i - w);
            if (y < h - 1) top = push(kind, seen, stack, top, i + w);
        }
        if (area == 0 || coloured * 2 < area) return 0;
        return area / (double) n;
    }

    private static int push(byte[] kind, boolean[] seen, int[] stack, int top, int j) {
        if (!seen[j] && kind[j] != 0) { seen[j] = true; stack[top++] = j; }
        return top;
    }
}
