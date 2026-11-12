package com.storageplanner.v2test;

import com.storageplanner.v2.device.Availability;
import com.storageplanner.v2.device.InstalledApp;
import com.storageplanner.v2.device.InstalledApps;
import com.storageplanner.v2.model.Tri;
import com.storageplanner.v2.util.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Installed apps from installed_apps.json: a list, {"apps": [...]} or the dataset's {"installed": [...]}. Keys
 * package|packageName, versionCode, versionName, signingCertSha256|signing_cert_sha256 (string or list), label, installer,
 * and the optional size/usage keys codeBytes, dataBytes, cacheBytes, lastUsed. FULL when a file is given, else UNAVAILABLE.
 */
public final class JvmInstalledApps implements InstalledApps {
    private final Availability availability;
    private final Map<String, InstalledApp> apps = new TreeMap<String, InstalledApp>();

    public JvmInstalledApps(String jsonPathOrNull) {
        if (jsonPathOrNull == null) { availability = Availability.UNAVAILABLE; return; }
        availability = Availability.FULL;
        try {
            Object root = Json.parse(new String(Files.readAllBytes(Paths.get(jsonPathOrNull)), StandardCharsets.UTF_8));
            load(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("installed apps file " + jsonPathOrNull + ": " + e, e);
        }
    }

    /** Test stub: given apps and availability. */
    public JvmInstalledApps(Availability a, InstalledApp... list) { availability = a; for (InstalledApp x : list) apps.put(x.packageName, x); }

    @SuppressWarnings("unchecked")
    private void load(Object root) {
        List<Object> l = null;
        if (root instanceof List) l = (List<Object>) root;
        else if (root instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) root;
            l = Json.list(m, "apps"); if (l == null) l = Json.list(m, "installed");
        }
        if (l == null) return;
        for (Object o : l) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) o;
            InstalledApp a = new InstalledApp();
            a.packageName = Json.str(m, "package"); if (a.packageName == null) a.packageName = Json.str(m, "packageName");
            if (a.packageName == null) continue;
            a.versionCode = Json.lng(m, "versionCode", -1); a.versionName = Json.str(m, "versionName"); a.label = Json.str(m, "label");
            a.installerPackage = Json.str(m, "installer");
            Object c = m.containsKey("signingCertSha256") ? m.get("signingCertSha256") : m.get("signing_cert_sha256");
            if (c instanceof String) a.signingCertSha256 = Collections.singletonList(((String) c).toLowerCase(Locale.ROOT));
            else if (c instanceof List) { a.signingCertSha256 = new ArrayList<String>(); for (Object x : (List<Object>) c) if (x instanceof String) a.signingCertSha256.add(((String) x).toLowerCase(Locale.ROOT)); }
            a.codeBytes = Json.lng(m, "codeBytes", -1); a.dataBytes = Json.lng(m, "dataBytes", -1); a.cacheBytes = Json.lng(m, "cacheBytes", -1);
            a.lastUsed = Json.lng(m, "lastUsed", Long.MIN_VALUE);
            Object sys = m.get("system"); if (sys instanceof Boolean) a.system = Tri.of((Boolean) sys);
            apps.put(a.packageName, a);
        }
    }

    public Availability availability() { return availability; }
    public List<InstalledApp> list() { return availability == Availability.UNAVAILABLE ? Collections.<InstalledApp>emptyList() : new ArrayList<InstalledApp>(apps.values()); }
    public InstalledApp get(String pkg) { return pkg == null ? null : apps.get(pkg); }
    public InstalledApp inspectArchive(String localPath) { return null; }
}
