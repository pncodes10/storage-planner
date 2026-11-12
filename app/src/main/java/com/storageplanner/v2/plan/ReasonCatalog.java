package com.storageplanner.v2.plan;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reason codes emitted by Phase 4 (ARCHITECTURE 6, PHASE_4 11). Templates use {param}. */
public final class ReasonCatalog {
    private ReasonCatalog() {}
    static final Map<String, String[]> T = new LinkedHashMap<String, String[]>();
    static void c(String code, String tier, String sentence) { T.put(code, new String[]{tier, sentence}); }
    static {
        c("S_TEMP_FILE", "SAFE", "Unfinished download or temporary file ({ext}), last changed {age} ago; apps do not need it afterwards.");
        c("S_TRASHED", "SAFE", "Already in the trash; Android would remove it on its own {when}.");
        c("S_THUMBNAIL", "SAFE", "Thumbnail the gallery rebuilds automatically from the original.");
        c("S_APP_CACHE_FILE", "SAFE", "Cache file of {app}; the app recreates it when needed.");
        c("S_APP_LOG", "SAFE", "Log file written by {app}; apps do not need old logs.");
        c("S_EXPIRED_STATUS", "SAFE", "Viewed WhatsApp status older than a day; WhatsApp no longer shows it.");
        c("S_EMPTY_FILE", "SAFE", "Empty file (0 bytes); it holds no content.");
        // Phase 5 (PHASE_5 6.1, 12 C-4)
        c("S_APP_CACHE", "SAFE", "Cache of {app} ({bytes}); the app rebuilds it when needed.");
        c("N_APP_ARCHIVE", "NO_LOSS", "{app} has not been used for {idle}; Android can archive it and keep your data, restoring it from the store when you tap it.");
        c("Y_IDLE_APP", "YOUR_CALL", "{app} has not been used for {idle}; uninstalling it frees {bytes} including its data.");
        c("PL_NEEDS_YOUR_CALL", "PLAN", "To reach {target}, {n} items ({bytes}) need your decision; {safe} can be freed without any.");
        c("PL_TARGET_UNREACHABLE", "PLAN", "{target} cannot be reached within the allowed risk; {achievable} can be freed safely ({withYourCall} with items that need your decision).");
        c("PL_HASH_PENDING", "PLAN", "{pending} items are still being checked and will be offered later.");
        c("PL_VALIDATOR_DROPPED", "PLAN", "{dropped} items were removed from the plan by the final safety check.");
        c("S_UNINSTALLED_LEFTOVER", "SAFE", "Left behind by {pkg}, which is no longer installed.");
        c("N_EXACT_DUPLICATE", "NO_LOSS", "Identical copy: {keeper} has exactly the same content (checked byte for byte) and is kept.");
        c("N_SAME_CONTENT_DIFFERENT_NAME", "NO_LOSS", "Same content as {keeperName}, which is kept; only the name differs ({name}).");
        c("N_INSTALLER_INSTALLED", "NO_LOSS", "Installer for {app} {version}: the same version, signed by the same developer, is installed.");
        c("N_LOSSLESS_SMALLER", "NO_LOSS", "Can be stored {saving} smaller without changing any pixel or byte of content.");
        c("Y_CHAT_COPY", "YOUR_CALL", "Smaller chat copy of {original}, which is kept. The copy itself may matter to you (who sent it, the chat).");
        c("Y_SMALLER_COPY", "YOUR_CALL", "Smaller copy of {original}, which is kept at full quality.");
        c("Y_CONVERTED_COPY", "YOUR_CALL", "{format} copy of {original}, which is kept; the pictures look the same.");
        c("Y_SIMILAR_SHOT", "YOUR_CALL", "Nearly the same picture as {keeper}, which is kept; this one {why}.");
        c("Y_OLDER_INSTALLER", "YOUR_CALL", "Installer of an older {app} ({version}); {installed} is installed. Keep it if you may want to go back.");
        c("Y_INSTALLER_UNVERIFIED", "YOUR_CALL", "Installer for {app} {version}, which is installed, but we could not confirm it comes from the same developer.");
        c("Y_OLD_CHAT_BACKUP", "YOUR_CALL", "Older chat backup from {date}; WhatsApp restores from the newest one ({newer}). Backups are encrypted, so we could not check it.");
        c("Y_LEFTOVER_MEDIA", "YOUR_CALL", "Media left by {pkg}, which is no longer installed.");
        c("Y_LOW_QUALITY", "YOUR_CALL", "Very {problem} picture. Quality says nothing about how much it means to you, so it is your call.");
        c("R_SIMILAR_SHOT", "REVIEW", "One of {n} nearly identical shots taken within seconds; {keeper} looks best. Pick the ones you want to keep.");
        c("R_INSTALLER_NOT_INSTALLED", "REVIEW", "Installer for {app}, which is not installed. We cannot tell whether it can be downloaded again.");
        c("R_INSTALLER_SIGNER_MISMATCH", "REVIEW", "Installer for {app}, but signed by a different developer than the installed app.");
        c("R_INSTALLER_NEWER", "REVIEW", "Installer for a newer {app} ({version}) than the one installed ({installed}).");
        c("R_OLD_DOWNLOAD", "REVIEW", "Large {kind} downloaded {age} ago; Android does not tell us whether you opened it since.");
        c("R_UNREADABLE", "REVIEW", "Could not be read ({error}), so we cannot tell what it contains.");
        c("R_CONTRADICTORY", "REVIEW", "Signals disagree: {a}, but {b}.");
        c("R_UNCERTAIN_COPY", "REVIEW", "May be a copy of {other}, but this could not be confirmed.");
        c("R_UNKNOWN_DEPENDENCY", "REVIEW", "Other files may need it ({hint}).");
        c("R_NAME_CONTENT_MISMATCH", "REVIEW", "Its name says .{ext} but it contains {type}.");
        c("R_ENCRYPTED", "REVIEW", "Encrypted or password-protected; its content cannot be checked.");
        c("R_ARCHIVE_UNSAFE", "REVIEW", "Archive with entries that could not be checked safely ({issue}).");
        c("P_USER_PROTECTED", "PROTECTED", "You marked this as protected.");
        c("P_UNIQUE_RECORD", "PROTECTED", "Only copy of what looks like a {category}; losing it would be hard to undo.");
        c("P_KEEPER", "-", "Kept as the best copy of {n} identical or similar files.");
        c("P_CRITICAL_DEPENDENCY", "PROTECTED", "Needed by {other}.");
        c("P_ARCHIVE_UNIQUE_CONTENT", "PROTECTED", "Archive holding {n} documents that exist nowhere else on this phone.");
        c("H_LAST_VERIFIED_COPY", "block", "Not offered for deletion: no other verified copy exists.");
        c("H_SIMILAR_NOT_IDENTICAL", "block", "Similar is not identical, so it is only offered as your call.");
        c("H_CLASSIFICATION_ONLY", "block", "A guess about the content alone never decides deletion.");
        c("H_UNKNOWN_FEATURES", "block", "Missing information is treated as unknown, not as unimportant.");
        c("H_DEPENDENCY", "block", "Another kept file needs it.");
    }
    static final Pattern P = Pattern.compile("\\{([a-zA-Z]+)\\}");
    public static boolean exists(String code) { return T.containsKey(code); }
    public static String tier(String code) { String[] v = T.get(code); return v == null ? null : v[0]; }
    public static Set<String> codes() { return Collections.unmodifiableSet(T.keySet()); }
    public static List<String> placeholders(String code) {
        List<String> out = new ArrayList<String>(); String[] v = T.get(code);
        if (v == null) return out;
        Matcher m = P.matcher(v[1]); while (m.find()) out.add(m.group(1));
        return out;
    }
    /** Fills the template; a missing parameter renders as "unknown". */
    public static String render(String code, Map<String, String> params) {
        String[] v = T.get(code);
        if (v == null) throw new IllegalArgumentException("unknown reason code " + code);
        Matcher m = P.matcher(v[1]); StringBuffer sb = new StringBuffer();
        while (m.find()) { String x = params == null ? null : params.get(m.group(1)); m.appendReplacement(sb, Matcher.quoteReplacement(x == null ? "unknown" : x)); }
        m.appendTail(sb);
        return sb.toString();
    }
}
