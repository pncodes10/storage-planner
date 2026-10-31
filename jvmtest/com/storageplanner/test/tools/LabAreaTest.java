package com.storageplanner.test.tools;

import com.storageplanner.core.Lab;

/** The Algorithm Lab treats a picked picture as WhatsApp image or screenshot when its name says so. */
public final class LabAreaTest {
    static int checks, fails;

    static void expect(String name, String chosen, String want) {
        checks++;
        String got = Lab.guessArea(name, chosen);
        if (!want.equals(got)) { fails++; System.out.println("FAIL " + name + " (" + chosen + "): got " + got + ", want " + want); }
    }

    public static void main(String[] a) {
        expect("IMG-20261031-WA0007.jpg", "camera", "wa_image");
        expect("null-20261031-WA0007.jpg", "camera", "wa_image");       // as one phone's picker named it
        expect("Screenshot_20261031-101500.png", "camera", "screenshot");
        expect("IMG_20261031_101500.jpg", "camera", "camera");
        expect("IMG-20261031-WA0007.jpg", "download", "download");         // the user's own choice wins
        System.out.println((checks - fails) + "/" + checks + " Lab name checks pass");
        if (fails > 0) System.exit(1);
    }
}
