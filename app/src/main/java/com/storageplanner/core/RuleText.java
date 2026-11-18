package com.storageplanner.core;

import java.util.Locale;

import com.storageplanner.core.Rule.Action;
import com.storageplanner.core.Rule.Condition;
import com.storageplanner.core.Rule.Op;

/** A rule in plain words, for screens: "Category is Greetings & forwards, age more than 7 days and is chat or Telegram media". */
public final class RuleText {
    private RuleText() {}

    /** "7.0" -> "7", "0.50" -> "0.5"; anything that is not a number as written. */
    public static String number(String v) {
        try {
            double d = Double.parseDouble(v.trim());
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
            return String.valueOf(d);
        } catch (NumberFormatException e) { return v; }
    }

    /** One condition as words, starting lower case ("age more than 7 days", "is a screenshot", "picture category is one of Receipts, Bills"). */
    public static String condition(Condition c) {
        Tags.Info i = Tags.info(c.tag);
        if (i == null || c.op == null) return "unknown condition \"" + c.toText() + "\"";
        Op op = c.op;
        boolean not = c.not;
        switch (i.type) {
            case NUMBER: {
                if (op == Op.KNOWN) return i.phrase + (not ? " is not known (never matches)" : " is known");
                if (not) op = flip(op);             // NOT is the same as the opposite comparison: an unknown value matches neither
                String words;
                switch (op) {
                    case EQ: words = "is"; break;
                    case NE: words = "is not"; break;
                    case GT: words = "more than"; break;
                    case GE: words = "at least"; break;
                    case LT: words = "less than"; break;
                    case LE: words = "at most"; break;
                    default: return i.phrase + " " + op.text + " " + c.value;
                }
                return i.phrase + " " + words + " " + number(c.value) + (i.unit.length() > 0 ? " " + i.unit : "");
            }
            case FLAG: {
                if (op == Op.KNOWN) return i.phrase + (not ? " is not known (never matches)" : " is known");
                boolean yes;
                if (op == Op.IS_YES) yes = true;
                else if (op == Op.IS_NO) yes = false;
                else {
                    boolean v = c.value.equalsIgnoreCase("yes") || c.value.equalsIgnoreCase("true");
                    yes = op == Op.EQ ? v : !v;
                }
                if (not) yes = !yes;
                return yes ? i.phrase : negate(i.phrase);
            }
            default: {
                if (op == Op.KNOWN) return i.phrase + (not ? " is not known (never matches)" : " is known");
                if (not) op = op == Op.EQ ? Op.NE : op == Op.NE ? Op.EQ : op;
                String words = op == Op.EQ ? "is" : op == Op.NE ? "is not" : op == Op.IN ? (not ? "is none of" : "is one of") : op.text;
                StringBuilder v = new StringBuilder();
                for (String part : c.value.split(",")) {
                    part = part.trim();
                    if (part.length() == 0) continue;
                    if (v.length() > 0) v.append(", ");
                    v.append(Tags.valueLabel(c.tag, part));
                }
                return i.phrase + " " + words + " " + v;
            }
        }
    }

    private static Op flip(Op o) {
        switch (o) {
            case EQ: return Op.NE;
            case NE: return Op.EQ;
            case GT: return Op.LE;
            case GE: return Op.LT;
            case LT: return Op.GE;
            case LE: return Op.GT;
            default: return o;
        }
    }

    private static String negate(String phrase) {
        if (phrase.startsWith("is ")) return "is not " + phrase.substring(3);
        if (phrase.startsWith("has ")) return "does not have " + phrase.substring(4);
        return "not: " + phrase;
    }

    /** All conditions of a rule as one sentence. */
    public static String conditions(Rule r) {
        if (r.when.isEmpty()) return "No conditions yet";
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < r.when.size(); k++) {
            if (k > 0) b.append(k == r.when.size() - 1 ? " and " : ", ");
            b.append(condition(r.when.get(k)));
        }
        String s = b.toString();
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /** A rule's file type in words: "Any file", "Pictures (any format)", "JPEG pictures", ... */
    public static String fileType(String ft) {
        if (ft == null || Rule.ANY_FILE.equals(ft)) return "Any file";
        switch (ft) {
            case Tags.IMAGE: return "Pictures (any format)";
            case Tags.VIDEO: return "Videos (any format)";
            case Tags.AUDIO: return "Music and audio (any format)";
            case Tags.DOCUMENT: return "Documents (any format)";
            case Tags.APK: return "App installers (APK)";
            case Tags.OTHER: return "Other files";
            case "zip": return "ZIP archives";
            default: {
                String k = Rule.kindOf(ft), up = ft.toUpperCase(Locale.ROOT);
                if (Tags.IMAGE.equals(k)) return up + " pictures";
                if (Tags.VIDEO.equals(k)) return up + " videos";
                if (Tags.AUDIO.equals(k)) return up + " audio";
                if (Tags.DOCUMENT.equals(k)) return up + " documents";
                return up + " files";
            }
        }
    }

    /** pictures, videos, music and audio files, ... */
    public static String kindWords(String kind) {
        if (Tags.IMAGE.equals(kind)) return "pictures";
        if (Tags.VIDEO.equals(kind)) return "videos";
        if (Tags.AUDIO.equals(kind)) return "music and audio files";
        if (Tags.DOCUMENT.equals(kind)) return "documents";
        if (Tags.APK.equals(kind)) return "app installers";
        return "other files";
    }

    /** "For JPEG pictures only", or "For any file"; with the kind a re-encode is limited to when the rule says any file. */
    public static String appliesTo(Rule r) {
        String ft = r.fileType == null ? Rule.ANY_FILE : r.fileType, need = Rule.kindFor(r.action);
        if (Rule.ANY_FILE.equals(ft)) return need == null ? "For any file" : "For " + kindWords(need) + " (" + action(r.action).toLowerCase(Locale.ROOT) + " works on nothing else)";
        String w = fileType(ft);
        if (Rule.isKind(ft)) w = w.substring(0, 1).toLowerCase(Locale.ROOT) + w.substring(1);     // "pictures (any format)"; keep "JPEG pictures"
        return "For " + w + " only";
    }

    /** Short name of the action: "Delete", "Compress photo", "Keep (protect)". */
    public static String action(Rule r) { return r.action == null ? "Unknown action" : action(r.action); }

    public static String action(Action a) {
        switch (a) {
            case DELETE: return "Delete";
            case REENCODE_PHOTO: return "Compress photo";
            case LOSSLESS_IMAGE: return "Compress screenshot (lossless)";
            case REENCODE_VIDEO: return "Compress video";
            case REENCODE_AUDIO: return "Compress music";
            default: return "Keep (protect)";
        }
    }

    /** What the action does, in words, with its risk. */
    public static String actionSentence(Rule r) {
        if (r.action == null) return "Unknown action: this rule is switched off.";
        switch (r.action) {
            case KEEP: return "Keeps these files out of every suggestion. No risk: nothing is touched.";
            case DELETE: return r.effectiveRisk() == Option.R4
                    ? "Suggests deleting. Needs your confirmation for each file." : "Suggests deleting. " + Option.TIER_NAME[r.effectiveRisk()] + ".";
            default: return "Suggests compressing. " + Option.TIER_NAME[r.effectiveRisk()] + "; the saving is an estimate.";
        }
    }
}
