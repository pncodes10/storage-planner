package com.storageplanner.app;

import android.content.Context;
import android.util.AtomicFile;

import com.storageplanner.core.RuleSet;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The user's rules, kept in app-private storage as the plain text RuleSet.toText() writes (getFilesDir()/rules.txt).
 * A file, not SharedPreferences: it is one text block that can grow with the user's rules, and AtomicFile (like the
 * saved scan results) leaves the old file intact if the app dies in the middle of a write. No file means the built-in rules.
 * The set handed out is never changed in place: the rules screens edit a copy() and call save() with it, so a plan
 * being built on another thread always sees one consistent set.
 */
final class RulesStore {
    private RulesStore() {}

    private static final String NAME = "rules.txt";
    private static RuleSet current;
    private static int version;

    private static AtomicFile file(Context c) { return new AtomicFile(new File(c.getApplicationContext().getFilesDir(), NAME)); }

    /** The rules in use; read from disk on first use (RuleSet.fromText never throws; a damaged file gives the built-in rules and a warning). */
    static synchronized RuleSet get(Context c) {
        if (current == null) current = read(c);
        return current;
    }

    /** Goes up by one every time the rules are saved: lets a plan that was being built notice the rules changed meanwhile. */
    static synchronized int version() { return version; }

    /** Stores the rules and makes them the ones in use (what is in use is what was written, read back, so warnings show up at once). Returns false if the write failed. */
    static synchronized boolean save(Context c, RuleSet rules) {
        String text = rules.toText();
        current = RuleSet.fromText(text);
        version++;
        AtomicFile af = file(c);
        FileOutputStream fos = null;
        try {
            fos = af.startWrite();
            fos.write(text.getBytes(StandardCharsets.UTF_8));
            af.finishWrite(fos);
            return true;
        } catch (Throwable t) {
            if (fos != null) af.failWrite(fos);
            return false;
        }
    }

    private static RuleSet read(Context c) {
        FileInputStream in = null;
        try {
            File f = new File(c.getApplicationContext().getFilesDir(), NAME);
            if (!f.exists() && !new File(f.getPath() + ".bak").exists()) return RuleSet.defaults();
            in = file(c).openRead();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return RuleSet.fromText(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            RuleSet d = RuleSet.defaults();
            d.warnings.add("your saved rules could not be read; using the built-in rules");
            return d;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }
}
