package com.storageplanner.test.tools;

import com.storageplanner.core.Lab;
import com.storageplanner.test.JvmPlatform;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Algorithm Lab on the desktop: every tool runs on real pictures of the category corpus without failing,
 * and says what the scan would decide (faces from the ".faces" stand-in, words from ".ocr.json").
 * usage: LabTest <corpus dir>
 */
public final class LabTest {
    static int fails;
    static final StringBuilder all = new StringBuilder();

    static void expect(String what, String out, String... needles) {
        for (String n : needles) if (!out.contains(n)) { fails++; System.out.println("FAIL " + what + ": missing \"" + n + "\""); }
    }

    public static void main(String[] a) throws Exception {
        File c = new File(a[0]);
        JvmPlatform pf = new JvmPlatform(c.getPath());
        Lab.Pic people = pic(c, "DCIM/Camera/people_00.jpg"), photo = pic(c, "DCIM/Camera/photo_01.jpg"),
                qr = pic(c, "DCIM/Camera/qr_01_photo.jpg"), receipt = pic(c, "DCIM/Camera/receipt_005.jpg"),
                otp = pic(c, "Pictures/Screenshots/Screenshot_otp_03.png");
        otp.area = "screenshot";
        long now = 1792000000000L;
        expect("faces", run(o -> Lab.faces(pf, people, o)), "counted");
        expect("faces, sizes and tilts", run(o -> Lab.facesDetailed(pf, people, o)), "<- what the app uses", "turned -20");
        expect("eyes", run(o -> { Lab.Shown s = Lab.eyes(pf, people, o); if (s.patches.isEmpty()) o.line("NO PATCHES"); }), "eyes-open probability", "EYES OPEN");
        expect("no faces", run(o -> Lab.faces(pf, photo, o)), "no face counted");
        expect("sharpness", run(o -> Lab.sharpness(pf, people, o)), "focus (used to compare", "blur score");
        expect("lighting", run(o -> Lab.lighting(pf, photo, o)), "lighting score");
        expect("best shot", run(o -> Lab.best(pf, Arrays.asList(people, photo), o)), "=> keep", "score parts");
        expect("near duplicates", run(o -> Lab.nearDuplicates(pf, Arrays.asList(people, photo), o)), "bits apart", "not grouped directly");
        expect("exact duplicates", run(o -> Lab.exactDuplicates(Arrays.asList(people, people), o)), "IDENTICAL");
        expect("QR", run(o -> Lab.codes(pf, qr, o)), "QR_CODE");
        expect("category: QR", run(o -> Lab.category(pf, qr, now, o)), "=> QR codes");
        expect("category: receipt", run(o -> Lab.category(pf, receipt, now, o)), "=> Receipts");
        expect("category: OTP", run(o -> Lab.category(pf, otp, now, o)), "=> One-time codes");
        expect("read text", run(o -> Lab.text(pf, receipt, now, o)), "text reader:", "text says: Receipts");
        System.out.println(all.length() + " characters of Lab output");
        System.out.println(fails == 0 ? "lab checks pass" : fails + " lab checks FAILED");
        if (fails > 0 || System.getProperty("show") != null) System.out.println(all);
        if (fails > 0) System.exit(1);
    }

    interface Job { void run(Lab.Out o) throws Exception; }

    static String run(Job j) throws Exception {
        StringBuilder b = new StringBuilder();
        j.run(s -> b.append(s).append('\n'));
        all.append(b);
        return b.toString();
    }

    static Lab.Pic pic(File root, String rel) {
        File f = new File(root, rel);
        return new Lab.Pic(f.getPath(), f.getName(), f.length(), f.lastModified());
    }
}
