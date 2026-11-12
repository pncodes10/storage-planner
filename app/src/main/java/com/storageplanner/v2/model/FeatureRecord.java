package com.storageplanner.v2.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Table file_features (ARCHITECTURE 2.5, PHASE_2 2). Key = fileId. Sub-records are replaced, never mutated after a put. */
public final class FeatureRecord implements Record {
    /** Number of extractor ids (features.Extractors.COUNT). */
    public static final int EXTRACTORS = 20;
    public String fileId; public Fingerprint fp;
    public int[] versions = new int[EXTRACTORS];
    public long extractedScanId = -1;
    public double confidence = Double.NaN;
    public List<String> problems = new ArrayList<String>();
    public ImageInfo image; public ExifInfo exif; public NameDate nameDate;
    public VideoInfo video; public AudioInfo audio; public ApkInfo apk; public PdfInfo pdf; public ArchiveInfo archive;
    public Map<String, String> extra = new TreeMap<String, String>();

    public String key() { return fileId; }
    public void problem(String p) { if (problems.size() < 20 && !problems.contains(p)) problems.add(p); }

    public FeatureRecord copy() {
        FeatureRecord r = new FeatureRecord();
        r.fileId = fileId; r.fp = fp; r.versions = Arrays.copyOf(versions, EXTRACTORS); r.extractedScanId = extractedScanId;
        r.confidence = confidence; r.problems = new ArrayList<String>(problems);
        r.image = image == null ? null : image.<ImageInfo>copy(); r.exif = exif == null ? null : exif.<ExifInfo>copy();
        r.nameDate = nameDate == null ? null : nameDate.<NameDate>copy(); r.video = video == null ? null : video.<VideoInfo>copy();
        r.audio = audio == null ? null : audio.<AudioInfo>copy(); r.apk = apk == null ? null : apk.<ApkInfo>copy();
        r.pdf = pdf == null ? null : pdf.<PdfInfo>copy(); r.archive = archive == null ? null : archive.<ArchiveInfo>copy();
        r.extra = new TreeMap<String, String>(extra);
        return r;
    }
}
