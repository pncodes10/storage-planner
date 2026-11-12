package com.storageplanner.v2.features;

import com.storageplanner.v2.device.Availability;
import com.storageplanner.v2.device.InstalledApp;
import com.storageplanner.v2.device.InstalledApps;
import com.storageplanner.v2.device.ReadableFile;
import com.storageplanner.v2.model.*;

import java.io.IOException;
import java.util.List;

/** APK identity, signer and install state (PHASE_2 5). */
public final class ApkProbe {
    private ApkProbe() {}

    public static ApkInfo probe(ReadableFile rf, long size, ZipProbe.Listing l, String type, InstalledApps apps, String localPath, FeatureRecord fr, Limits lim) throws IOException {
        ApkInfo a = new ApkInfo();
        a.bundle = Tri.of(!type.equals("apk"));
        ArchiveEntry man = null;
        if (type.equals("apk")) {
            for (ArchiveEntry e : l.entries) if (e.depth == 0 && e.path.equals("AndroidManifest.xml")) { man = e; break; }
            if (man != null) {
                byte[] d = ZipProbe.read(rf, size, man, lim.inflateManifest, 200);
                if (d == null) a.problem = "MANIFEST_UNREADABLE";
                else {
                    AxmlParser.ManifestInfo m = AxmlParser.parse(d);
                    a.packageName = m.packageName; a.versionCode = m.versionCode; a.versionName = m.versionName;
                    a.minSdk = m.minSdk; a.targetSdk = m.targetSdk; a.split = Tri.of(m.split != null);
                    a.parseSource = m.source; if (m.problem != null) a.problem = m.problem;
                    if ("ZIP_TEXT".equals(m.source)) fr.problem("apk:TEXT_MANIFEST");
                }
            }
            ApkSignatures.Result s = ApkSignatures.read(rf, size, l.cdOffset, l.entries, lim);
            a.signerCertSha256 = s.certs; a.signatureScheme = s.scheme;
            if (s.problem != null) fr.problem("apk:" + s.problem);
        } else {
            for (ArchiveEntry e : l.entries) if (e.depth == 0 && e.path.equals("base.apk")) { man = e; break; }
            if (man == null) a.problem = "BUNDLE_NO_BASE";
            else if (man.method != 0 && man.compressedSize > lim.bundleBaseMaxDeflated) a.problem = "BUNDLE_BASE_TOO_LARGE";
            else a.problem = "BUNDLE_BASE_NOT_PARSED";   // nested inflate of the base APK: Phase 6 (A11 scope)
        }
        if (a.packageName == null && localPath != null && apps != null) {
            InstalledApp p = apps.inspectArchive(localPath);
            if (p != null) { a.packageName = p.packageName; a.versionCode = p.versionCode; a.versionName = p.versionName; a.parseSource = "PLATFORM"; }
        }
        fr.problem("apk:SIGNATURE_UNVERIFIED");
        installState(a, apps);
        return a;
    }

    public static void installState(ApkInfo a, InstalledApps apps) {
        a.installState = InstallState.UNKNOWN; a.sameSigner = Tri.UNKNOWN; a.installedVersionCode = -1;
        if (apps == null || apps.availability() == Availability.UNAVAILABLE || a.packageName == null) return;
        InstalledApp i = apps.get(a.packageName);
        if (i == null) { if (apps.availability() == Availability.FULL) a.installState = InstallState.NOT_INSTALLED; return; }
        a.installedVersionCode = i.versionCode;
        if (i.versionCode >= 0 && a.versionCode >= 0)
            a.installState = i.versionCode == a.versionCode ? InstallState.SAME_VERSION : i.versionCode > a.versionCode ? InstallState.INSTALLED_NEWER : InstallState.INSTALLED_OLDER;
        List<String> x = a.signerCertSha256, y = i.signingCertSha256;
        if (x != null && y != null && !x.isEmpty() && !y.isEmpty()) {
            boolean any = false; for (String c : x) if (y.contains(c)) any = true;
            a.sameSigner = Tri.of(any);
        }
    }
}
