package com.storageplanner.v2.model;

import java.util.List;

public final class ApkInfo extends Sub {
    public String packageName, versionName, signatureScheme, parseSource, problem;
    public long versionCode = -1, installedVersionCode = -1;
    public int minSdk = -1, targetSdk = -1;
    /** null = unknown, empty = unsigned. */
    public List<String> signerCertSha256;
    public Tri split = Tri.UNKNOWN, bundle = Tri.UNKNOWN, sameSigner = Tri.UNKNOWN;
    public InstallState installState = InstallState.UNKNOWN;
}
