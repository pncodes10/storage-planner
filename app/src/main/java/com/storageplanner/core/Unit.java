package com.storageplanner.core;

import java.util.ArrayList;
import java.util.List;

/** A decision unit: one file, one app, or one app's cache, with mutually exclusive options (keep is implicit). */
public final class Unit {
    public final String id;          // relative path, "app:<package>" or "cache:<package>"
    public final String title;       // what the user sees
    public final String kind;        // "file", "app" or "cache"
    public final FileItem file;      // null for apps and caches
    public final AppItem app;        // null for files
    public final List<Option> options = new ArrayList<Option>();

    private Unit(String id, String title, String kind, FileItem f, AppItem a) {
        this.id = id; this.title = title; this.kind = kind; this.file = f; this.app = a;
    }

    public static Unit forFile(FileItem f) { return new Unit(f.rel, f.rel, "file", f, null); }
    public static Unit forApp(AppItem a) { return new Unit("app:" + a.pkg, a.label, "app", null, a); }
    public static Unit forCache(AppItem a) { return new Unit("cache:" + a.pkg, a.label + " (cache)", "cache", null, a); }

    public String area() { return file != null ? file.area : kind; }

    private Option userDelete;

    /**
     * Deletion the user picked themselves (e.g. the shot the algorithm would keep, because they prefer
     * another one of the group). Not offered to the planner; only used when the user chose it.
     */
    public Option userDelete(String technique) {
        if (file == null) return null;
        if (userDelete == null || !userDelete.technique.equals(technique))
            userDelete = new Option("delete", technique, file.size, Techniques.DUPLICATES.equals(technique) ? Option.R1 : Option.R4, true,
                    "You chose to delete this one and keep another shot of the same moment");
        return userDelete;
    }

    public void add(Option o) {
        if (o.saved <= 0) return;
        // a file has at most one delete option: keep the lowest-risk reason
        if (o.action.equals("delete")) {
            for (int i = 0; i < options.size(); i++) {
                Option e = options.get(i);
                if (e.action.equals("delete")) {
                    if (o.risk < e.risk) options.set(i, o);
                    return;
                }
            }
        }
        options.add(o);
    }
}
