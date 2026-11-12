package com.storageplanner.v2.risk;

import java.text.Normalizer;
import java.util.*;

/** PHASE_4 4.2: closed keyword lists, matched on NFC lower-case path tokens (plus whole-path substrings for multi-part entries). */
public final class Keywords {
    private Keywords() {}
    public static final List<String> FINANCIAL = Arrays.asList("statement", "bank", "passbook", "tax", "itr", "form16", "gst", "invoice",
            "receipt", "bill", "salary", "payslip", "paystub", "policy", "insurance", "premium", "loan", "emi", "mortgage", "deposit", "w-2",
            "w2", "1099", "demat", "mutual", "pension", "epf", "uan", "fd", "sanction");
    public static final List<String> LEGAL = Arrays.asList("passport", "aadhaar", "aadhar", "pan", "licence", "license", "voter",
            "certificate", "marksheet", "degree", "agreement", "contract", "deed", "lease", "affidavit", "will", "court", "registration",
            "property", "birth", "marriage", "visa", "identity", "discharge", "vaccination", "lab");
    public static final List<String> MEDICAL = Arrays.asList("prescription", "report", "discharge", "vaccination", "lab", "medical", "hospital");
    public static final List<String> SENTIMENTAL = Arrays.asList("wedding", "marriage", "baby", "family", "birthday", "anniversary", "funeral", "memories");

    /** Tokens: split on / _ - . space ( ), letter/digit and lower/Upper boundaries; lower-case. */
    public static Set<String> tokens(String path) {
        Set<String> out = new TreeSet<String>();
        if (path == null) return out;
        String p = Normalizer.normalize(path, Normalizer.Form.NFC);
        for (String part : p.split("[/_\\-. ()]+")) {
            if (part.isEmpty()) continue;
            out.add(part.toLowerCase(Locale.ROOT));
            for (String sub : part.split("(?<=[a-z])(?=[A-Z])|(?<=[A-Za-z])(?=[0-9])|(?<=[0-9])(?=[A-Za-z])")) if (!sub.isEmpty()) out.add(sub.toLowerCase(Locale.ROOT));
        }
        return out;
    }
    static String lower(String path) { return path == null ? "" : Normalizer.normalize(path, Normalizer.Form.NFC).toLowerCase(Locale.ROOT); }
    static String first(String path, List<String> words) {
        Set<String> t = tokens(path); String low = lower(path);
        for (String w : words) {
            if (w.equals("report") && !(low.startsWith("documents/") || low.contains("/documents/"))) continue;
            if (t.contains(w) || (w.indexOf('-') > 0 && low.contains(w))) return w;
        }
        return null;
    }
    public static boolean financial(String path) { return first(path, FINANCIAL) != null; }
    public static boolean legal(String path) { return first(path, LEGAL) != null; }
    public static boolean medical(String path) { return first(path, MEDICAL) != null; }
    public static boolean sentimental(String path) { return first(path, SENTIMENTAL) != null; }
    public static boolean record(String path) { return financial(path) || legal(path) || medical(path); }
    /** financial / medical / legal (identity included), or null. */
    public static String recordCategory(String path) {
        return financial(path) ? "financial record" : medical(path) ? "medical record" : legal(path) ? "legal or identity record" : null;
    }
    public static String hit(String path) {
        String w = first(path, FINANCIAL); if (w == null) w = first(path, MEDICAL); if (w == null) w = first(path, LEGAL);
        if (w == null) w = first(path, SENTIMENTAL); return w;
    }
}
