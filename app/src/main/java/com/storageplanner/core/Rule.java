package com.storageplanner.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One rule: a list of conditions that must ALL be true (AND; for OR write two rules), and one action.
 * Each condition tests one tag of a file (see Tags) and can be negated with NOT.
 *
 * UNKNOWN RULE (safety): if the tag's value is unknown for a file (never measured: faces in a screenshot, blur
 * without enough camera photos, a date that was not read, ...), the condition is FALSE, also when it has NOT.
 * So "NOT faces > 0" does not match a picture whose faces were never checked. A rule can therefore never
 * act on something the app does not know.
 *
 * Text values compare without regard to case; numbers compare as numbers. For category the id ("greeting")
 * and the name shown on screen ("Greetings & forwards") both work.
 *
 * FILE TYPE: every rule says which files it is for (fileType): any file, a kind (pictures, videos, music, ...)
 * or one format (jpeg, mp3, ...). A rule never matches a file of another type. On top of that a re-encode only
 * ever touches files of its own kind (compress photo: pictures; compress video: videos; compress music: audio),
 * whatever the rule says, so a music rule can never act on a JPEG.
 */
public final class Rule {
    public enum Op {
        EQ("="), NE("!="), GT(">"), GE(">="), LT("<"), LE("<="), IS_YES("is yes"), IS_NO("is no"), IN("in"), KNOWN("is known");
        public final String text;
        Op(String t) { text = t; }
        static Op of(String t) { for (Op o : values()) if (o.text.equals(t)) return o; return null; }
    }

    public enum Action {
        DELETE("delete", "delete"), REENCODE_PHOTO("reencode_photo", "reencode_photo"), LOSSLESS_IMAGE("lossless_image", "lossless_image"),
        REENCODE_VIDEO("reencode_video", "reencode_video"), REENCODE_AUDIO("reencode_audio", "reencode_audio"), KEEP("keep", null);
        public final String text;
        /** The Option.action string this produces (null for KEEP, which makes no option). */
        public final String option;
        Action(String t, String o) { text = t; option = o; }
        public static Action of(String t) { for (Action a : values()) if (a.text.equals(t)) return a; return null; }
        /** A re-encode (not delete, not keep). */
        public boolean compress() { return this != DELETE && this != KEEP; }
    }

    /** How many bytes the option saves. FILE_SIZE is exact; the others are fractions from Estimates, so estimates. */
    public enum Saving {
        FILE_SIZE, CAMERA_PHOTO, CHAT_IMAGE, SCREENSHOT_LOSSLESS, VIDEO, MUSIC;
        public boolean exact() { return this == FILE_SIZE; }
        public static Saving of(String t) { for (Saving s : values()) if (s.name().equalsIgnoreCase(t)) return s; return null; }
        /** The usual saving of an action, for rules written by the user. */
        public static Saving defaultFor(Action a) {
            switch (a) {
                case REENCODE_PHOTO: return CAMERA_PHOTO;
                case LOSSLESS_IMAGE: return SCREENSHOT_LOSSLESS;
                case REENCODE_VIDEO: return VIDEO;
                case REENCODE_AUDIO: return MUSIC;
                default: return FILE_SIZE;
            }
        }
        long of(FileItem f, Estimates est) {
            switch (this) {
                case CAMERA_PHOTO: return (long) (f.size * est.cameraPhoto);
                case CHAT_IMAGE: return (long) (f.size * est.chatImage);
                case SCREENSHOT_LOSSLESS: return (long) (f.size * est.screenshotLossless);
                case VIDEO: return (long) (f.size * est.video(f));
                case MUSIC: return (long) (f.size * est.music(f));
                default: return f.size;
            }
        }
    }

    /** One test: [NOT] tag op value. Immutable; to change a threshold, replace it with withValue(). */
    public static final class Condition {
        public final String tag;
        public final Op op;
        public final String value;       // "" for the ops that need none
        public final boolean not;
        private final double num;        // value as a number, NaN if it is not one
        private final Set<String> list;  // lower-case items for IN
        private final String lc;         // lower-case value
        private final Tags.Type type;    // of the tag; null = no such tag

        public Condition(String tag, Op op, String value, boolean not) {
            this.tag = tag == null ? "" : tag.trim(); this.op = op; this.not = not;
            this.value = value == null ? "" : value.trim();
            this.type = Tags.typeOf(this.tag);
            this.lc = this.value.toLowerCase(Locale.ROOT);
            double n;
            try { n = Double.parseDouble(this.value); } catch (NumberFormatException e) { n = Double.NaN; }
            this.num = n;
            Set<String> l = new HashSet<String>();
            for (String s : this.lc.split(",")) { s = s.trim(); if (s.length() > 0) l.add(s); }
            this.list = l;
        }

        public Condition withValue(String v) { return new Condition(tag, op, v, not); }

        /** Null if the condition makes sense, else why not (unknown tag, operator that does not fit the tag, bad number). */
        public String problem() {
            if (type == null) return "unknown tag \"" + tag + "\"";
            if (op == null) return "unknown operator";
            switch (op) {
                case KNOWN: return null;
                case IS_YES: case IS_NO: return type == Tags.Type.FLAG ? null : tag + " is not a yes/no tag";
                case IN: return type == Tags.Type.TEXT && !list.isEmpty() ? null : "\"in\" needs a text tag and a list";
                case EQ: case NE:
                    if (type == Tags.Type.FLAG) return yesNo() != null ? null : "use yes or no with " + tag;
                    if (type == Tags.Type.NUMBER) return Double.isNaN(num) ? "\"" + value + "\" is not a number" : null;
                    return value.length() > 0 ? null : "no value";
                default:
                    if (type != Tags.Type.NUMBER) return op.text + " needs a number tag";
                    return Double.isNaN(num) ? "\"" + value + "\" is not a number" : null;
            }
        }

        private Boolean yesNo() {
            if (lc.equals("yes") || lc.equals("true")) return Boolean.TRUE;
            if (lc.equals("no") || lc.equals("false")) return Boolean.FALSE;
            return null;
        }

        /** True if this file has the tag value the condition asks for; false for an unknown value, with or without NOT. */
        public boolean matches(Tags.T t) {
            if (type == null || op == null) return false;
            Boolean r = test(t);
            if (r == null) return false;                // unknown, or the operator does not fit the tag
            return not ? !r.booleanValue() : r.booleanValue();
        }

        private Boolean test(Tags.T t) {
            switch (type) {
                case NUMBER: {
                    double v = t.numTag(tag);
                    if (v < 0) return null;
                    switch (op) {
                        case KNOWN: return Boolean.TRUE;
                        case EQ: return Double.isNaN(num) ? null : v == num;
                        case NE: return Double.isNaN(num) ? null : v != num;
                        case GT: return Double.isNaN(num) ? null : v > num;
                        case GE: return Double.isNaN(num) ? null : v >= num;
                        case LT: return Double.isNaN(num) ? null : v < num;
                        case LE: return Double.isNaN(num) ? null : v <= num;
                        default: return null;
                    }
                }
                case FLAG: {
                    Boolean v = t.flagTag(tag);
                    if (v == null) return null;
                    switch (op) {
                        case KNOWN: return Boolean.TRUE;
                        case IS_YES: return v;
                        case IS_NO: return !v.booleanValue();
                        case EQ: { Boolean w = yesNo(); return w == null ? null : (Boolean) (v.booleanValue() == w.booleanValue()); }
                        case NE: { Boolean w = yesNo(); return w == null ? null : (Boolean) (v.booleanValue() != w.booleanValue()); }
                        default: return null;
                    }
                }
                default: {
                    String v = t.textTag(tag);
                    if (v == null) return null;
                    switch (op) {
                        case KNOWN: return Boolean.TRUE;
                        case EQ: return same(v);
                        case NE: return !same(v);
                        case IN: return inList(v);
                        default: return null;
                    }
                }
            }
        }

        private boolean same(String v) {
            if (v.equalsIgnoreCase(value)) return true;
            return tag.equals(Tags.CATEGORY) && PhotoCategory.label(v).equalsIgnoreCase(value);
        }

        private boolean inList(String v) {
            if (list.contains(v.toLowerCase(Locale.ROOT))) return true;
            return tag.equals(Tags.CATEGORY) && list.contains(PhotoCategory.label(v).toLowerCase(Locale.ROOT));
        }

        /** "NOT faces > 0", "area in camera,pictures", "screenshot is yes". */
        public String toText() {
            StringBuilder b = new StringBuilder();
            if (not) b.append("NOT ");
            b.append(tag);
            if (op != null) b.append(' ').append(op.text);
            if (value.length() > 0) b.append(' ').append(value);
            return b.toString();
        }

        /** Reads what toText() writes; null if the line has no tag or no known operator. */
        public static Condition parse(String line) {
            String s = line.trim();
            boolean not = false;
            if (s.startsWith("NOT ")) { not = true; s = s.substring(4).trim(); }
            int sp = s.indexOf(' ');
            if (sp <= 0) return null;
            String tag = s.substring(0, sp), rest = s.substring(sp + 1).trim();
            // longest operator first: ">=" before ">", "is yes" before "is known" does not matter (different words)
            String[] ops = {"is known", "is yes", "is no", ">=", "<=", "!=", "=", ">", "<", "in"};
            for (String o : ops) {
                if (!rest.startsWith(o)) continue;
                String after = rest.substring(o.length());
                boolean word = Character.isLetter(o.charAt(0));
                if (word && after.length() > 0 && after.charAt(0) != ' ') continue;     // "in" must not eat "installer..."
                return new Condition(tag, Op.of(o), after, not);
            }
            return null;
        }
    }

    // ------------------------------------------------------------------ file type
    /** fileType of a rule for every file. */
    public static final String ANY_FILE = "any";
    /** File types a rule can be for: any file, then each kind followed by its formats. */
    public static final String[] FILE_TYPES = {ANY_FILE,
            Tags.IMAGE, "jpeg", "png", "webp", "heic", "avif",
            Tags.VIDEO, "mp4", "mkv",
            Tags.AUDIO, "mp3", "flac", "m4a", "ogg",
            Tags.DOCUMENT, "pdf",
            Tags.APK, Tags.OTHER, "zip"};

    /** A kind (image, video, ...) rather than one format. */
    public static boolean isKind(String ft) {
        return Tags.IMAGE.equals(ft) || Tags.VIDEO.equals(ft) || Tags.AUDIO.equals(ft) || Tags.DOCUMENT.equals(ft) || Tags.APK.equals(ft) || Tags.OTHER.equals(ft);
    }

    /** The kind of files of this file type: the kind itself, the kind of a format, null for any file or an unknown type. */
    public static String kindOf(String ft) {
        if (ft == null || ANY_FILE.equals(ft)) return null;
        if (isKind(ft)) return ft;
        FileItem f = new FileItem("x", "x", 0, 0);
        f.type = ft;
        String k = Tags.kind(f);
        return Tags.OTHER.equals(k) && !"zip".equals(ft) ? null : k;
    }

    public static boolean knownFileType(String ft) {
        for (String s : FILE_TYPES) if (s.equals(ft)) return true;
        return false;
    }

    /** The only kind of file this action may touch (re-encodes), or null when it may touch any (delete, keep). */
    public static String kindFor(Action a) {
        if (a == null) return null;
        switch (a) {
            case REENCODE_PHOTO: case LOSSLESS_IMAGE: return Tags.IMAGE;
            case REENCODE_VIDEO: return Tags.VIDEO;
            case REENCODE_AUDIO: return Tags.AUDIO;
            default: return null;
        }
    }

    /** True if a file with these tags is of this rule's file type and of the kind its action may touch. */
    public boolean fileTypeMatches(Tags.T t) {
        String ft = fileType == null ? ANY_FILE : fileType;
        if (!ANY_FILE.equals(ft)) {
            if (isKind(ft)) { if (!ft.equals(t.kind)) return false; }
            else if (t.fileType == null || !ft.equalsIgnoreCase(t.fileType)) return false;
        }
        String need = kindFor(action);
        return need == null || need.equals(t.kind);
    }

    // ------------------------------------------------------------------ the rule
    public String id;                    // stable; built-ins have fixed ids, user rules get one from the rule set
    public String name;                  // what the user sees
    public boolean builtIn;
    public boolean enabled = true;
    public final List<Condition> when = new ArrayList<Condition>();
    public Action action = Action.DELETE;
    public int risk = Option.R4;         // built-ins only; the engine fixes user rules to R4 (delete) or R2 (re-encode)
    public String technique;             // built-ins: a Techniques constant; user rules: "My rule: " + name
    public Saving saving = Saving.FILE_SIZE;
    public String why = "";              // text with {placeholders}, see RuleSet.fill()
    public String whyHook;               // built-ins that need code to word the reason: "burst"
    public String unknownAction;         // the action text of a rule saved by a newer version (action is null then); kept so saving loses nothing
    public boolean onlyIfNoOtherOption;  // fires only when no other rule gave this file an option yet
    public String fileType = ANY_FILE;   // which files the rule is for: ANY_FILE, a kind or a format (FILE_TYPES)

    public static final String USER_PREFIX = "My rule: ";

    public Rule() {}

    public Rule when(Condition... c) { for (Condition x : c) when.add(x); return this; }
    public Rule when(String tag, Op op, String value) { when.add(new Condition(tag, op, value, false)); return this; }
    public Rule whenNot(String tag, Op op, String value) { when.add(new Condition(tag, op, value, true)); return this; }

    /** A new rule written by the user. */
    public static Rule user(String name, Action action) {
        Rule r = new Rule();
        r.name = name; r.action = action; r.builtIn = false;
        r.technique = USER_PREFIX + name;
        r.saving = Saving.defaultFor(action);
        return r;
    }

    /** Null if the rule can run, else why it cannot (it is then loaded but left off). */
    public String problem() {
        if (action == null) return "unknown action";
        if (when.isEmpty()) return "no conditions";
        if (fileType != null && !knownFileType(fileType)) return "unknown file type \"" + fileType + "\"";
        String need = kindFor(action), have = kindOf(fileType);
        if (need != null && fileType != null && !ANY_FILE.equals(fileType) && !need.equals(have))
            return action.text + " only works on " + RuleText.kindWords(need) + ", not on " + fileType;
        for (Condition c : when) { String p = c.problem(); if (p != null) return p; }
        return null;
    }

    public boolean matches(Tags.T t) {
        if (!fileTypeMatches(t)) return false;
        for (int i = 0; i < when.size(); i++) if (!when.get(i).matches(t)) return false;
        return true;
    }

    /** The risk tier of the options this rule makes in the plan (user rules: R4 for a delete, R2 for a re-encode), or -1 for a keep rule. */
    public int effectiveRisk() {
        if (action == null || action == Action.KEEP) return -1;
        if (builtIn) return risk;
        return action == Action.DELETE ? Option.R4 : Option.R2;
    }

    /** A copy that can be edited without touching the original. */
    public Rule copy() {
        Rule r = new Rule();
        r.id = id; r.name = name; r.builtIn = builtIn; r.enabled = enabled; r.when.addAll(when); r.action = action; r.risk = risk;
        r.technique = technique; r.saving = saving; r.why = why; r.whyHook = whyHook; r.unknownAction = unknownAction; r.onlyIfNoOtherOption = onlyIfNoOtherOption;
        r.fileType = fileType;
        return r;
    }
}
