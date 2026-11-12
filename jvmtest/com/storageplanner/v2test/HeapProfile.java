package com.storageplanner.v2test;

import com.storageplanner.v2.model.Record;
import com.storageplanner.v2.store.StoreException;
import com.storageplanner.v2.store.Table;
import com.storageplanner.v2.store.V2Store;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.management.ObjectName;

/**
 * Heap profiling for the scale harness (PHASE_S_STORE 1.1, slice S0): used heap after 4 full GCs at named checkpoints,
 * optional class histograms (DiagnosticCommand MBean, the data of {@code jcmd GC.class_histogram}) and the exact retained
 * size of each store table (remove all keys, commit, GC). No reflection.
 */
final class HeapProfile {
    private final String outDir;          // null: no histograms
    private final PrintStream log;
    long base;
    long peakMarked;

    HeapProfile(String outDir, PrintStream log) { this.outDir = outDir; this.log = log; }

    static long used() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 4; i++) {
            System.gc();
            try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    void start() { base = used(); log.println("base used=" + base + " Xmx=" + Runtime.getRuntime().maxMemory()); histo("0_base"); }

    void histo(String name) {
        if (outDir == null) return;
        try {
            String h = (String) ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"),
                    "gcClassHistogram", new Object[]{new String[0]}, new String[]{String[].class.getName()});
            try (Writer w = new OutputStreamWriter(new FileOutputStream(outDir + "/h_" + name + ".txt"), "UTF-8")) { w.write(h); }
        } catch (Exception e) {
            log.println("histogram " + name + " unavailable: " + e);
        }
    }

    /** Logs used heap after GC, the delta to the base and the delta per file; returns used. */
    long mark(String name, int files) {
        long u = used();
        histo(name);
        peakMarked = Math.max(peakMarked, u);
        log.println(String.format(Locale.ROOT, "%-30s used=%11d  delta=%11d  perFile=%7.0f", name, u, u - base, files == 0 ? 0.0 : (u - base) / (double) files));
        log.flush();
        return u;
    }

    /** Empties the tables one at a time and logs each one's retained size. Destroys the store's content. */
    void perTable(V2Store s, int files, long before) throws StoreException {
        String[] names = {"files", "features", "hashes", "embeddings", "relationships", "duplicateGroups", "backups", "pairChecks",
                "recommendations", "actionPlans", "operations", "feedback", "preferences", "models", "scanSessions", "volumes", "directories"};
        Table<?>[] ts = {s.files(), s.features(), s.hashes(), s.embeddings(), s.relationships(), s.duplicateGroups(), s.backups(),
                s.pairChecks(), s.recommendations(), s.actionPlans(), s.operations(), s.feedback(), s.preferences(), s.models(),
                s.scanSessions(), s.volumes(), s.directories()};
        long prev = before;
        for (int i = 0; i < ts.length; i++) {
            Table<?> t = ts[i];
            int size = t.size();
            List<String> keys = new ArrayList<String>();
            for (Object o : t.all()) keys.add(((Record) o).key());
            for (String k : keys) t.remove(k);
            s.commit();
            keys = null;
            long u = used();
            if (size > 0) log.println(String.format(Locale.ROOT, "table %-16s n=%7d  bytes=%10d  perRecord=%6.0f  perFile=%6.0f",
                    names[i], size, prev - u, (prev - u) / (double) size, files == 0 ? 0.0 : (prev - u) / (double) files));
            prev = u;
        }
        log.println(String.format(Locale.ROOT, "residual after all tables removed: %d (base %d)", prev, base));
    }
}
