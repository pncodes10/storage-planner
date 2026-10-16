package com.storageplanner.app;

import com.storageplanner.core.AppItem;
import com.storageplanner.core.FileItem;
import com.storageplanner.core.Option;
import com.storageplanner.core.Unit;

import java.util.ArrayList;
import java.util.List;

/** One reviewable item: a file or an app, with the decision units that concern it. */
public final class Row {
    public final FileItem file;
    public final AppItem app;
    public final List<Unit> units = new ArrayList<Unit>();
    /** How to find this row again after process death: "file:<rel>", "app:<pkg>" or "unit:<id>". */
    public final String ref;

    private Row(FileItem f, AppItem a, String ref) { file = f; app = a; this.ref = ref; }

    public static Row forFile(FileItem f) {
        Row r = new Row(f, null, "file:" + f.rel);
        Unit u = Session.unitById.get(f.rel);
        if (u != null) r.units.add(u);
        return r;
    }

    public static Row forApp(AppItem a) {
        Row r = new Row(null, a, "app:" + a.pkg);
        Unit u = Session.appUnit(a), c = Session.cacheUnit(a);
        if (u != null) r.units.add(u);
        if (c != null) r.units.add(c);
        return r;
    }

    /** A row for exactly one unit (used inside a recommendation list). */
    public static Row forUnit(Unit u) {
        Row r = new Row(u.file, u.app, "unit:" + u.id);
        r.units.add(u);
        return r;
    }

    /** Rebuilds a row from its ref against the current Session, or null if it no longer exists. */
    public static Row fromRef(String ref) {
        if (ref.startsWith("file:")) {
            FileItem f = Session.fileByRel.get(ref.substring(5));
            return f != null ? forFile(f) : null;
        }
        if (ref.startsWith("app:")) {
            String pkg = ref.substring(4);
            for (AppItem a : Session.result.apps) if (a.pkg.equals(pkg)) return forApp(a);
            return null;
        }
        if (ref.startsWith("unit:")) {
            Unit u = Session.unitById.get(ref.substring(5));
            return u != null ? forUnit(u) : null;
        }
        return null;
    }

    public String title() { return file != null ? file.name() : app.label + (units.size() == 1 && "cache".equals(units.get(0).kind) ? " (cache)" : ""); }

    public boolean anyKept() {
        for (Unit u : units) if (Session.kept.contains(u.id)) return true;
        return false;
    }

    public boolean anyAccepted() {
        for (Unit u : units) if (Session.isAccepted(u)) return true;
        return false;
    }

    public boolean flagged() {
        for (Unit u : units) if (!u.options.isEmpty()) return true;
        return false;
    }

    /** The option currently in the plan for this row, if any. */
    public Option chosen() {
        for (Unit u : units) { Option o = Session.plan.chosen.get(u); if (o != null) return o; }
        return null;
    }

    /** Chosen option, else the largest available one (for "why is this flagged"). */
    public Option headline() {
        Option c = chosen();
        if (c != null) return c;
        Option best = null;
        for (Unit u : units) for (Option o : u.options) if (best == null || o.saved > best.saved) best = o;
        return best;
    }

    public int optionCount() {
        int n = 0;
        for (Unit u : units) n += u.options.size();
        return n;
    }

    public long size() {
        if (file != null) return file.size;
        return app.codeBytes + app.dataBytes + app.cacheBytes;
    }

    public String folder() {
        if (file == null) return "";
        int i = file.rel.lastIndexOf('/');
        return i < 0 ? "Internal storage" : file.rel.substring(0, i);
    }
}
