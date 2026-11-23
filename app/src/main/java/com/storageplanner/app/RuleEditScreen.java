package com.storageplanner.app;

import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.storageplanner.core.FileItem;
import com.storageplanner.core.Report;
import com.storageplanner.core.Rule;
import com.storageplanner.core.Rule.Action;
import com.storageplanner.core.Rule.Condition;
import com.storageplanner.core.Rule.Op;
import com.storageplanner.core.RuleSet;
import com.storageplanner.core.RuleText;
import com.storageplanner.core.Scanner;
import com.storageplanner.core.Tags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Edit one rule, or write a new one: name, what it does, and a list of conditions that must all be true. Below the form a
 * live preview shows how many files of the current scan the rule matches, with a grid of the largest ones. A built-in rule
 * keeps its name, action and conditions; only its numbers and its on/off switch can change. Nothing is deleted anywhere:
 * saving stores the rule and rebuilds the plan from the scan already in memory.
 */
public final class RuleEditScreen extends Screen {
    private static final int PREVIEW_FILES = 30;
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    /** One condition being edited (Rule.Condition is immutable). */
    private static final class Cond {
        String tag; Op op; String value; boolean not;
    }

    private interface Picked { void on(int index); }

    private final String ruleId;             // null = a new rule
    private final RuleSet set;               // a copy of the rules in use; the edited rule goes back into it on Save
    private final Rule base;                 // the rule as it was; null for a new one
    private final boolean builtIn;
    private String name = "";
    private Action action = Action.DELETE;
    private String fileType = Rule.ANY_FILE;
    private boolean enabled = true;
    private final List<Cond> conds = new ArrayList<Cond>();

    private LinearLayout content, previewBox, problemBox;
    private TextView previewSummary;
    private Button saveBtn;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int previewGen;
    private boolean previewFresh;
    private RuleSet.Matches lastMatches;
    private String previewNote = "";
    private List<String> problems = new ArrayList<String>();
    private boolean broadAck, confirmDelete;
    private String broadMsg = "";
    private volatile Map<String, List<String>> occurring = new HashMap<String, List<String>>();
    private boolean occurringStarted;

    private final Runnable previewTask = new Runnable() { public void run() { computePreview(); } };

    public RuleEditScreen(MainActivity a, String ruleId) {
        super(a);
        this.ruleId = ruleId;
        set = RulesStore.get(a).copy();
        Rule r = ruleId == null ? null : set.find(ruleId);
        base = r == null ? null : r.copy();
        builtIn = base != null && base.builtIn;
        if (base != null) {
            name = base.name; action = base.action; enabled = base.enabled;
            fileType = base.fileType == null ? Rule.ANY_FILE : base.fileType;
            for (Condition c : base.when) conds.add(fromCondition(c));
        } else if (ruleId == null) {
            conds.add(defaultCond(Tags.AGE_DAYS));
        }
    }

    public String title() { return base == null ? "New rule" : builtIn ? "Built-in rule" : "Edit rule"; }

    protected View build() {
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(false);
        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(32));
        sv.addView(content);
        startOccurring();
        render();
        schedulePreview();
        return sv;
    }

    @Override public void refresh() { if (content != null) schedulePreview(); }

    @Override public void onHide() { ui.removeCallbacks(previewTask); }

    // ------------------------------------------------------------------ conditions as data
    private static Cond fromCondition(Condition c) {
        Cond x = new Cond();
        x.tag = c.tag; x.op = c.op; x.value = c.value; x.not = c.not;
        Tags.Type t = Tags.typeOf(c.tag);
        if (t == Tags.Type.FLAG && (c.op == Op.EQ || c.op == Op.NE)) {          // "= yes" is the same as "is yes"
            boolean yes = c.value.equalsIgnoreCase("yes") || c.value.equalsIgnoreCase("true");
            x.op = (c.op == Op.EQ) == yes ? Op.IS_YES : Op.IS_NO;
            x.value = "";
        }
        if (x.op == null) x.op = defaultOp(t);
        return x;
    }

    private static Op defaultOp(Tags.Type t) { return t == Tags.Type.FLAG ? Op.IS_YES : t == Tags.Type.TEXT ? Op.EQ : Op.GT; }

    private static Cond defaultCond(String tag) {
        Cond x = new Cond();
        Tags.Type t = Tags.typeOf(tag);
        x.tag = tag; x.op = defaultOp(t); x.not = false;
        x.value = t == Tags.Type.NUMBER ? defaultNumber(tag) : "";
        if (t == Tags.Type.TEXT) { List<Tags.Choice> ch = Tags.choices(tag); if (!ch.isEmpty()) x.value = ch.get(0).value; }
        return x;
    }

    private static String defaultNumber(String tag) {
        switch (tag) {
            case Tags.AGE_DAYS: return "30";
            case Tags.SIZE_MB: return "10";
            case Tags.SIZE_BYTES: return "1000000";
            case Tags.BLUR: return "70";
            case Tags.BRIGHTNESS: return "20";
            case Tags.BITS_PER_PIXEL: return "1";
            case Tags.BITRATE_KBPS: return "128";
            case Tags.DAYS_SINCE_DATE: return "7";
            case Tags.MEGAPIXELS: return "1";
            default: return "0";
        }
    }

    private static Op[] opsFor(Tags.Type t) {
        if (t == Tags.Type.FLAG) return new Op[] {Op.IS_YES, Op.IS_NO, Op.KNOWN};
        if (t == Tags.Type.TEXT) return new Op[] {Op.EQ, Op.NE, Op.IN, Op.CONTAINS, Op.KNOWN};
        return new Op[] {Op.GT, Op.GE, Op.LT, Op.LE, Op.EQ, Op.NE, Op.KNOWN};
    }

    private static String opLabel(Tags.Type t, Op op) {
        if (op == Op.KNOWN) return "is known";
        if (t == Tags.Type.FLAG) return op == Op.IS_YES ? "yes" : "no";
        switch (op) {
            case GT: return "more than";
            case GE: return "at least";
            case LT: return "less than";
            case LE: return "at most";
            case EQ: return "is";
            case NE: return "is not";
            case IN: return "is one of";
            case CONTAINS: return "contains";
            default: return op.text;
        }
    }

    private Condition toCondition(Cond x) { return new Condition(x.tag, x.op, x.value, x.not); }

    /** The rule as the form describes it now. */
    private Rule toRule() {
        Rule r = base != null ? base.copy() : new Rule();
        if (base == null) { r.builtIn = false; r.why = ""; }
        r.enabled = enabled;
        if (!builtIn) {
            r.name = name.trim();
            r.technique = Rule.USER_PREFIX + r.name;
            r.action = action;
            r.fileType = fileType;
            if (action != null) { r.unknownAction = null; r.saving = Rule.Saving.defaultFor(action); }
        }
        r.when.clear();
        for (Cond x : conds) r.when.add(toCondition(x));
        return r;
    }

    // ------------------------------------------------------------------ form
    private void render() {
        content.removeAllViews();
        if (builtIn) {
            Ui.add(content, Ui.heading(a, base.name, 22), 0);
            Ui.add(content, Ui.text(a, "A built-in rule. Its name, what it does and its conditions are fixed. You can change its numbers and switch it on or off; "
                    + "“Reset to default” puts it back.", 14, Ui.text2, false), 6);
        } else {
            Ui.add(content, Ui.heading(a, "Name", 18), 0);
            final EditText nameBox = new EditText(a);
            styleField(nameBox);
            nameBox.setHint("For example: Old chat graphics");
            nameBox.setText(name);
            nameBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            nameBox.setContentDescription("Rule name");
            nameBox.addTextChangedListener(new Watcher() { public void changed(String s) { name = s; edited(); } });
            content.addView(nameBox, new LinearLayout.LayoutParams(-1, -2));
            Ui.add(content, Ui.text(a, "Suggestions from this rule appear as “" + Rule.USER_PREFIX + "” followed by this name. Two of your rules cannot share a name.", 13, Ui.text2, false), 4);
        }

        Ui.add(content, Ui.heading(a, "What it does", 18), 22);
        if (builtIn) {
            Ui.add(content, Ui.text(a, RuleText.action(base), 16, Ui.text, true), 6);
        } else {
            final Action[] actions = {Action.DELETE, Action.REENCODE_PHOTO, Action.LOSSLESS_IMAGE, Action.REENCODE_VIDEO, Action.REENCODE_AUDIO, Action.KEEP, Action.PROTECT};
            final String[] labels = new String[actions.length];
            int cur = -1;
            for (int i = 0; i < actions.length; i++) { labels[i] = actionLabel(actions[i]); if (actions[i] == action) cur = i; }
            final int curIdx = cur;
            Button pick = picker("What it does", cur < 0 ? "Choose what it does" : labels[cur]);
            pick.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    choose("What should this rule do?", labels, curIdx, new Picked() {
                        public void on(int i) { action = actions[i]; edited(); render(); }
                    });
                }
            });
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2); pl.topMargin = dp(6);
            content.addView(pick, pl);
        }
        Rule shown = toRule();
        int risk = shown.effectiveRisk();
        if (shown.action != null) {
            LinearLayout rr = Ui.row(a);
            if (risk >= 0) rr.addView(Ui.badge(a, risk));
            TextView sentence = Ui.text(a, (risk >= 0 ? "  " : "") + RuleText.actionSentence(shown), 14, Ui.text, false);
            rr.addView(sentence, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = dp(8);
            content.addView(rr, rl);
            if (shown.action == Action.KEEP)
                Ui.add(content, Ui.text(a, "Files this rule matches get no suggestion at all, from any rule. ID documents are protected like this anyway.", 13, Ui.text2, false), 4);
            if (shown.action == Action.PROTECT)
                Ui.add(content, Ui.text(a, "Files this rule matches are never suggested for deletion by any rule, except an identical copy, a sent copy or a worse "
                        + "shot of a burst; they can still be compressed.", 13, Ui.text2, false), 4);
        }

        Ui.add(content, Ui.heading(a, "File type", 18), 22);
        if (builtIn) {
            Ui.add(content, Ui.text(a, RuleText.appliesTo(base) + ".", 16, Ui.text, true), 6);
        } else {
            final String[] types = Rule.FILE_TYPES;
            final String[] tl = new String[types.length];
            int cur = 0;
            for (int i = 0; i < types.length; i++) {
                tl[i] = (Rule.isKind(types[i]) || Rule.ANY_FILE.equals(types[i]) ? "" : "      ") + RuleText.fileType(types[i]);   // formats indented under their kind
                if (types[i].equals(fileType)) cur = i;
            }
            final int curIdx = cur;
            Button pickType = picker("File type", RuleText.fileType(fileType));
            pickType.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    choose("Which files is this rule for?", tl, curIdx, new Picked() {
                        public void on(int i) { fileType = types[i]; edited(); render(); }
                    });
                }
            });
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2); tp.topMargin = dp(6);
            content.addView(pickType, tp);
            Ui.add(content, Ui.text(a, RuleText.appliesTo(shown) + ". The rule never matches a file of another type. "
                    + "Compressing only ever touches its own kind of file (photos, videos or music), whatever is chosen here.", 13, Ui.text2, false), 4);
        }

        Ui.add(content, Ui.heading(a, "When", 18), 22);
        Ui.add(content, Ui.text(a, "All of these must be true for a file. A file where the app could not measure something never matches that condition, "
                + "even with NOT.", 14, Ui.text2, false), 4);
        for (int i = 0; i < conds.size(); i++) condCard(i);
        if (!builtIn) {
            Button add = Ui.button(a, "Add a condition", false);
            add.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { conds.add(defaultCond(Tags.AGE_DAYS)); edited(); render(); }
            });
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2); al.topMargin = dp(10);
            content.addView(add, al);
        }

        Switch on = new Switch(a);
        on.setText("Rule is on");
        on.setTextColor(Ui.text);
        on.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        on.setChecked(enabled);
        on.setMinHeight(dp(48));
        on.setContentDescription("Rule is on");
        on.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean c) { enabled = c; }
        });
        LinearLayout.LayoutParams onl = new LinearLayout.LayoutParams(-1, -2); onl.topMargin = dp(18);
        content.addView(on, onl);

        Ui.add(content, Ui.heading(a, "What it matches in your scan", 18), 22);
        previewBox = new LinearLayout(a);
        previewBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(previewBox, new LinearLayout.LayoutParams(-1, -2));
        previewSummary = null;
        fillPreview();

        problemBox = new LinearLayout(a);
        problemBox.setOrientation(LinearLayout.VERTICAL);
        problemBox.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams pbl = new LinearLayout.LayoutParams(-1, -2); pbl.topMargin = dp(16);
        content.addView(problemBox, pbl);
        showProblems();

        LinearLayout buttons = Ui.row(a);
        saveBtn = Ui.button(a, saveLabel(), true);
        saveBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { save(); } });
        Button cancel = Ui.button(a, "Cancel", false);
        cancel.setContentDescription("Cancel: leave without saving");
        cancel.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.onBackPressed(); } });
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, -2, 1); b1.rightMargin = dp(8);
        buttons.addView(saveBtn, b1); buttons.addView(cancel, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2); bl.topMargin = dp(12);
        content.addView(buttons, bl);

        if (builtIn && RuleSet.changed(toRule())) {
            Button reset = Ui.button(a, "Reset to default", false);
            reset.setContentDescription("Reset " + base.name + " to the built-in default");
            reset.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    for (Rule d : com.storageplanner.core.BuiltInRules.all())
                        if (d.id.equals(base.id)) {
                            conds.clear();
                            for (Condition c : d.when) conds.add(fromCondition(c));
                            enabled = d.enabled;
                        }
                    edited(); render();
                    v.announceForAccessibility("Back to the default numbers. Tap Save to keep this.");
                }
            });
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2); rl.topMargin = dp(10);
            content.addView(reset, rl);
        }
        if (!builtIn && base != null) {
            Button del = Ui.button(a, confirmDelete ? "Tap again to delete this rule" : "Delete rule", false);
            del.setContentDescription(confirmDelete ? "Confirm: delete the rule " + base.name : "Delete the rule " + base.name);
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (!confirmDelete) { confirmDelete = true; render(); v.announceForAccessibility("Tap again to delete the rule"); return; }
                    set.remove(base.id);
                    if (!RulesStore.save(a, set)) a.toast("Could not save your rules. They stay in use until the app is closed.");
                    a.onBackPressed();
                    RulesScreen.afterEdit(a, "Rule “" + base.name + "” deleted.");
                }
            });
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2); dl.topMargin = dp(10);
            content.addView(del, dl);
        }
    }

    private static String actionLabel(Action x) {
        switch (x) {
            case DELETE: return "Delete: suggest deleting (you confirm each file)";
            case REENCODE_PHOTO: return "Compress photo";
            case LOSSLESS_IMAGE: return "Compress screenshot (lossless)";
            case REENCODE_VIDEO: return "Compress video";
            case REENCODE_AUDIO: return "Compress music";
            case PROTECT: return "Never delete: no delete suggestions (compressing still allowed)";
            default: return "Keep: protect these files from every suggestion";
        }
    }

    private void condCard(final int index) {
        final Cond x = conds.get(index);
        final Tags.Info info = Tags.info(x.tag);
        final Tags.Type type = Tags.typeOf(x.tag);
        LinearLayout card = Ui.card(a);
        LinearLayout head = Ui.row(a);
        head.addView(Ui.heading(a, "Condition " + (index + 1), 16), new LinearLayout.LayoutParams(0, -2, 1));
        if (!builtIn) {
            Button rm = Ui.button(a, "Remove", false);
            rm.setContentDescription("Remove condition " + (index + 1));
            rm.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { conds.remove(index); edited(); render(); a.toast("Condition removed."); }
            });
            head.addView(rm);
        }
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));
        final TextView reads = Ui.text(a, "", 14, Ui.text2, false);

        if (builtIn) {
            Ui.add(card, Ui.text(a, RuleText.condition(toCondition(x)).substring(0, 1).toUpperCase(Locale.ROOT) + RuleText.condition(toCondition(x)).substring(1), 15, Ui.text, true), 6);
            if (type == Tags.Type.NUMBER && x.op != Op.KNOWN) {
                Ui.add(card, Ui.text(a, "Change the number:", 14, Ui.text2, false), 8);
                card.addView(numberRow(x, info, reads), new LinearLayout.LayoutParams(-1, -2));
                Ui.add(card, reads, 4);
                updateReads(reads, x);
            } else {
                Ui.add(card, Ui.text(a, "This part is fixed for built-in rules.", 13, Ui.text2, false), 4);
            }
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.topMargin = dp(10);
            content.addView(card, cl);
            return;
        }

        CheckBox not = new CheckBox(a);
        not.setText("NOT: reverse this condition");
        not.setTextColor(Ui.text);
        not.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        not.setMinHeight(dp(48));
        not.setChecked(x.not);
        not.setContentDescription("Reverse this condition. It then matches files where it is not true; files where it was not measured still never match.");
        not.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean c) { x.not = c; updateReads(reads, x); edited(); }
        });
        card.addView(not, new LinearLayout.LayoutParams(-1, -2));

        // tag
        final List<Tags.Info> all = Tags.list();
        final String[] tagLabels = new String[all.size()];
        int tagIdx = -1;
        for (int i = 0; i < tagLabels.length; i++) { tagLabels[i] = all.get(i).label; if (all.get(i).id.equals(x.tag)) tagIdx = i; }
        final int curTag = tagIdx;
        Button tagBtn = picker("About", info != null ? info.label : "Unknown: " + x.tag);
        tagBtn.setContentDescription("Condition " + (index + 1) + " is about: " + (info != null ? info.label : x.tag) + ". Double tap to change.");
        tagBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                choose("What is the condition about?", tagLabels, curTag, new Picked() {
                    public void on(int i) {
                        Cond n = defaultCond(all.get(i).id);
                        x.tag = n.tag; x.op = n.op; x.value = n.value;
                        edited(); render();
                    }
                });
            }
        });
        Ui.add(card, Ui.text(a, "About", 13, Ui.text2, false), 6);
        card.addView(tagBtn, new LinearLayout.LayoutParams(-1, -2));

        if (type != null) {
            // operator
            final Op[] ops = opsFor(type);
            final String[] opLabels = new String[ops.length];
            int opIdx = -1;
            for (int i = 0; i < ops.length; i++) { opLabels[i] = opLabel(type, ops[i]); if (ops[i] == x.op) opIdx = i; }
            final int curOp = opIdx;
            Ui.add(card, Ui.text(a, type == Tags.Type.FLAG ? "Answer" : "Comparison", 13, Ui.text2, false), 8);
            Button opBtn = picker(type == Tags.Type.FLAG ? "Answer" : "Comparison", opIdx < 0 ? x.op.text : opLabels[opIdx]);
            opBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    choose(type == Tags.Type.FLAG ? "Is it true for the file?" : "How is it compared?", opLabels, curOp, new Picked() {
                        public void on(int i) {
                            Op old = x.op;
                            x.op = ops[i];
                            if (x.op == Op.KNOWN || type == Tags.Type.FLAG) x.value = "";
                            else if (type == Tags.Type.TEXT && old == Op.IN && x.op != Op.IN) x.value = x.value.split(",")[0].trim();   // a list becomes one value
                            if (x.op != Op.KNOWN && x.value.length() == 0) x.value = defaultCond(x.tag).value;
                            edited(); render();
                        }
                    });
                }
            });
            card.addView(opBtn, new LinearLayout.LayoutParams(-1, -2));

            // value
            if (x.op != Op.KNOWN && type != Tags.Type.FLAG) {
                Ui.add(card, Ui.text(a, "Value", 13, Ui.text2, false), 8);
                if (type == Tags.Type.NUMBER) card.addView(numberRow(x, info, reads), new LinearLayout.LayoutParams(-1, -2));
                else card.addView(textValue(x, reads), new LinearLayout.LayoutParams(-1, -2));
            }
        }
        Ui.add(card, reads, 8);
        updateReads(reads, x);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.topMargin = dp(10);
        content.addView(card, cl);
    }

    private void updateReads(TextView t, Cond x) {
        String s = RuleText.condition(toCondition(x));
        t.setText("Reads as: " + s);
        Condition c = toCondition(x);
        String p = c.problem();
        if (p != null) { t.setText("Not complete yet: " + p); t.setTextColor(Ui.tier[3]); }
        else t.setTextColor(Ui.text2);
    }

    private View numberRow(final Cond x, Tags.Info info, final TextView reads) {
        LinearLayout row = Ui.row(a);
        final EditText e = new EditText(a);
        styleField(e);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(x.value);
        String unit = info == null ? "" : info.unit;
        e.setHint(unit.length() > 0 ? unit : "number");
        e.setContentDescription("Number" + (unit.length() > 0 ? ", in " + unit : ""));
        e.addTextChangedListener(new Watcher() { public void changed(String s) { x.value = s.trim(); updateReads(reads, x); edited(); } });
        row.addView(e, new LinearLayout.LayoutParams(0, -2, 1));
        if (unit.length() > 0) {
            TextView u = Ui.text(a, "  " + unit, 15, Ui.text2, false);
            u.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(u);
        }
        return row;
    }

    /** A value of a text tag: pick from what the app knows and what your scan has, or type it when the tag has no list. */
    private View textValue(final Cond x, final TextView reads) {
        final List<String[]> choices = choicesFor(x.tag);          // {value, label}
        if (choices.isEmpty()) {
            final EditText e = new EditText(a);
            styleField(e);
            e.setInputType(InputType.TYPE_CLASS_TEXT);
            e.setText(x.value);
            e.setHint(x.op == Op.IN ? "values separated by commas" : x.op == Op.CONTAINS ? "words separated by commas (any of them)" : "value");
            e.setContentDescription(x.op == Op.IN ? "Values, separated by commas" : "Value");
            e.addTextChangedListener(new Watcher() { public void changed(String s) { x.value = s.trim(); updateReads(reads, x); edited(); } });
            return e;
        }
        final String[] labels = new String[choices.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = choices.get(i)[1];
        if (x.op == Op.IN) {
            final boolean[] on = new boolean[labels.length];
            StringBuilder shown = new StringBuilder();
            java.util.Set<String> have = new java.util.HashSet<String>();
            for (String p : x.value.split(",")) have.add(p.trim().toLowerCase(Locale.ROOT));
            for (int i = 0; i < on.length; i++) {
                on[i] = have.contains(choices.get(i)[0].toLowerCase(Locale.ROOT));
                if (on[i]) { if (shown.length() > 0) shown.append(", "); shown.append(labels[i]); }
            }
            Button b = picker("Values", shown.length() == 0 ? "Choose values" : shown.toString());
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    final boolean[] now = on.clone();
                    new android.app.AlertDialog.Builder(a).setTitle("Choose one or more")
                        .setMultiChoiceItems(labels, now, new DialogInterface.OnMultiChoiceClickListener() {
                            public void onClick(DialogInterface d, int which, boolean checked) { now[which] = checked; }
                        })
                        .setPositiveButton("Done", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                StringBuilder val = new StringBuilder();
                                for (int i = 0; i < now.length; i++) if (now[i]) { if (val.length() > 0) val.append(","); val.append(choices.get(i)[0]); }
                                x.value = val.toString();
                                edited(); render();
                            }
                        }).setNegativeButton("Cancel", null).show();
                }
            });
            return b;
        }
        int cur = -1;
        for (int i = 0; i < labels.length; i++) if (choices.get(i)[0].equalsIgnoreCase(x.value.trim()) || labels[i].equalsIgnoreCase(x.value.trim())) cur = i;
        final int curIdx = cur;
        Button b = picker("Value", cur >= 0 ? labels[cur] : x.value.length() > 0 ? Tags.valueLabel(x.tag, x.value) : "Choose a value");
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                choose("Choose a value", labels, curIdx, new Picked() { public void on(int i) { x.value = choices.get(i)[0]; edited(); render(); } });
            }
        });
        return b;
    }

    /** Values the app knows for a tag plus the ones this scan has (each once): {value, label}. */
    private List<String[]> choicesFor(String tag) {
        List<String[]> out = new ArrayList<String[]>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (Tags.Choice c : Tags.choices(tag)) if (seen.add(c.value.toLowerCase(Locale.ROOT))) out.add(new String[] {c.value, c.label});
        List<String> inScan = occurring.get(tag);
        if (inScan != null) for (String v : inScan) if (seen.add(v.toLowerCase(Locale.ROOT))) out.add(new String[] {v, Tags.valueLabel(tag, v) + " (in your scan)"});
        return out;
    }

    /** Looks once, off the main thread, for the values the text tags have in this scan. */
    private void startOccurring() {
        if (occurringStarted || !Session.hasResults()) return;
        occurringStarted = true;
        final Scanner.Result res = Session.result;
        final Tags.Context ctx = Session.tags();
        POOL.execute(new Runnable() {
            public void run() {
                String[] ids = {Tags.SOURCE, Tags.AREA, Tags.KIND, Tags.CATEGORY, Tags.TEXT_READ, Tags.TEXT_AMOUNT, Tags.FILE_TYPE, Tags.VIDEO_CODEC};
                Map<String, TreeSet<String>> sets = new HashMap<String, TreeSet<String>>();
                for (String id : ids) sets.put(id, new TreeSet<String>());
                try {
                    for (FileItem f : res.files) {
                        Tags.T t = Tags.of(f, ctx, res.now);
                        for (String id : ids) { String v = t.textTag(id); TreeSet<String> s = sets.get(id); if (v != null && v.length() > 0 && s.size() < 300) s.add(v); }
                    }
                } catch (Throwable ignored) {}
                Map<String, List<String>> out = new HashMap<String, List<String>>();
                for (String id : ids) out.put(id, new ArrayList<String>(sets.get(id)));
                occurring = out;
            }
        });
    }

    // ------------------------------------------------------------------ small helpers
    private Button picker(String what, String current) {
        Button b = Ui.button(a, current + "  ▾", false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setContentDescription(what + ": " + current + ". Double tap to change.");
        return b;
    }

    private void styleField(EditText e) {
        e.setTextColor(Ui.text);
        e.setHintTextColor(Ui.text2);
        e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        e.setMinHeight(dp(48));
        e.setSingleLine(true);
    }

    private void choose(String title, String[] labels, int current, final Picked p) {
        new android.app.AlertDialog.Builder(a).setTitle(title)
            .setSingleChoiceItems(labels, current, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) { d.dismiss(); p.on(which); }
            }).setNegativeButton("Cancel", null).show();
    }

    private abstract static class Watcher implements TextWatcher {
        abstract void changed(String s);
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        public void onTextChanged(CharSequence s, int a, int b, int c) {}
        public void afterTextChanged(Editable e) { changed(e.toString()); }
    }

    /** Anything in the form changed: the old preview and the "save anyway" step no longer apply. */
    private void edited() {
        broadAck = false; broadMsg = ""; confirmDelete = false;
        if (problems.size() > 0) { problems = new ArrayList<String>(); showProblems(); }
        if (saveBtn != null) saveBtn.setText(saveLabel());
        schedulePreview();
    }

    private String saveLabel() { return broadAck ? "Save anyway" : "Save rule"; }

    // ------------------------------------------------------------------ live preview
    private void schedulePreview() {
        previewFresh = false;
        ui.removeCallbacks(previewTask);
        if (previewSummary != null && Session.hasResults()) previewSummary.setText("Counting…");
        ui.postDelayed(previewTask, 300);
    }

    private void computePreview() {
        final Rule r = toRule();
        if (!Session.hasResults()) { lastMatches = null; previewNote = "Analyse your storage to see what this rule matches."; fillPreview(); return; }
        if (r.action == null || r.problem() != null) {
            lastMatches = null; previewNote = "Fill in every condition to see what the rule matches."; fillPreview(); return;
        }
        final int gen = ++previewGen;
        final Scanner.Result res = Session.result;
        final Tags.Context ctx = Session.tags();
        final com.storageplanner.core.Estimates est = Session.est;
        POOL.execute(new Runnable() {
            public void run() {
                if (gen != previewGen) return;                              // a newer edit is already waiting
                RuleSet.Matches m = null;
                try { m = set.matches(r, res, ctx, est, res.now, PREVIEW_FILES); } catch (Throwable ignored) {}
                final RuleSet.Matches fm = m;
                AnalysisState.UI.post(new Runnable() {
                    public void run() {
                        if (gen != previewGen || content == null) return;
                        lastMatches = fm; previewFresh = fm != null;
                        previewNote = fm == null ? "Could not count the matches." : "";
                        fillPreview();
                    }
                });
            }
        });
    }

    private void fillPreview() {
        if (previewBox == null) return;
        previewBox.removeAllViews();
        final Rule r = toRule();
        previewSummary = Ui.text(a, "", 16, Ui.text, true);
        previewSummary.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        previewBox.addView(previewSummary, new LinearLayout.LayoutParams(-1, -2));
        RuleSet.Matches m = lastMatches;
        if (m == null) {
            previewSummary.setText(previewNote.length() > 0 ? previewNote : "Counting…");
            previewSummary.setTypeface(null, android.graphics.Typeface.NORMAL);
            return;
        }
        previewSummary.setText(RulesScreen.matchLine(r, m) + ".");
        if (m.broad(r)) {
            int pct = (int) Math.round(100.0 * m.count / Math.max(1, m.total));
            TextView w = Ui.text(a, "Warning: this rule matches " + pct + "% of all your files (" + m.count + " of " + m.total + "). That is a lot. "
                    + "Nothing is deleted without your confirmation, but you would have to look at every one of them. Check the pictures below before you save.", 15, Ui.tier[4], true);
            w.setContentDescription(w.getText().toString());
            Ui.add(previewBox, w, 8);
        }
        if (m.alsoSuggested > 0)
            Ui.add(previewBox, Ui.text(a, r.action == Action.KEEP || r.action == Action.PROTECT
                    ? m.alsoSuggested + " of them are suggested by other rules now; this rule would remove those suggestions."
                    : m.alsoSuggested + " of them are already suggested by another rule.", 14, Ui.text2, false), 6);
        if (m.overridden > 0)
            Ui.add(previewBox, Ui.text(a, m.overridden + " of them are already compressed by a built-in rule that comes first, so that rule is used for them instead. "
                    + "Only the first compressing rule that matches a file counts.", 14, Ui.text2, false), 6);
        if (m.idProtected > 0)
            Ui.add(previewBox, Ui.text(a, m.idProtected + " look like ID documents. They are never suggested for deletion, so they are left out.", 14, Ui.text2, false), 6);
        if (m.count > m.files.size() && !m.files.isEmpty())
            Ui.add(previewBox, Ui.text(a, "Showing the largest " + m.files.size() + " of " + m.count + ". Tap one to see its details.", 13, Ui.text2, false), 8);
        previewBox.addView(grid(m.files), new LinearLayout.LayoutParams(-1, -2));
    }

    private View grid(List<FileItem> files) {
        LinearLayout g = new LinearLayout(a);
        g.setOrientation(LinearLayout.VERTICAL);
        int gap = dp(4);
        int size = (a.getResources().getDisplayMetrics().widthPixels - dp(32) - 2 * gap) / 3;
        LinearLayout row = null;
        for (int i = 0; i < files.size(); i++) {
            if (i % 3 == 0) {
                row = Ui.row(a);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = gap;
                g.addView(row, rl);
            }
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(size, size);
            if (i % 3 != 2) cl.rightMargin = gap;
            row.addView(cell(files.get(i)), cl);
        }
        return g;
    }

    private View cell(final FileItem f) {
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(0xFF101014);
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        if (Thumbs.canPreview(f)) Thumbs.load(iv, f, dp(140));
        else {
            TextView ty = Ui.text(a, f.type != null ? f.type : "file", 14, 0xFFFFFFFF, true);
            ty.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(ty, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        }
        TextView cap = Ui.text(a, f.name() + " · " + Report.size(f.size), 11, 0xFFFFFFFF, false);
        cap.setBackgroundColor(0x99000000);
        cap.setPadding(dp(4), dp(2), dp(4), dp(2));
        Ui.ellipsizeMiddle(cap);
        cap.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(cap, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        root.setContentDescription(f.name() + ", " + Report.size(f.size) + ", " + Ui.ago(f.mtime, Session.result.now) + ". Double tap to see its details.");
        root.setClickable(true); root.setFocusable(true);
        root.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new DetailScreen(a, Row.forFile(f))); } });
        return root;
    }

    // ------------------------------------------------------------------ saving
    private void showProblems() {
        if (problemBox == null) return;
        problemBox.removeAllViews();
        if (!problems.isEmpty()) {
            Ui.add(problemBox, Ui.text(a, "This rule cannot be saved yet:", 15, Ui.tier[4], true), 0);
            for (String p : problems) Ui.add(problemBox, Ui.text(a, "• " + p, 14, Ui.tier[4], false), 4);
        }
        if (broadAck && broadMsg.length() > 0) Ui.add(problemBox, Ui.text(a, broadMsg, 15, Ui.tier[4], true), 4);
    }

    private void save() {
        Rule r = toRule();
        problems = set.problems(r);
        if (!problems.isEmpty()) {
            broadAck = false; broadMsg = "";
            showProblems(); saveBtn.setText(saveLabel());
            problemBox.announceForAccessibility("This rule cannot be saved yet. " + problems.get(0));
            return;
        }
        if (!builtIn && r.action == Action.DELETE && Session.hasResults() && !broadAck) {
            RuleSet.Matches m = previewFresh && lastMatches != null ? lastMatches
                    : set.matches(r, Session.result, Session.tags(), Session.est, Session.result.now, 0);
            if (m.broad(r)) {
                int pct = (int) Math.round(100.0 * m.count / Math.max(1, m.total));
                broadAck = true;
                broadMsg = "This delete rule matches " + pct + "% of all your files (" + m.count + " of " + m.total + "). "
                        + "Tap “Save anyway” if that is what you mean, or change the conditions.";
                showProblems();
                saveBtn.setText(saveLabel());
                saveBtn.setContentDescription("Save anyway: this rule matches " + pct + " percent of all your files");
                problemBox.announceForAccessibility(broadMsg);
                return;
            }
        }
        set.put(r);
        if (!RulesStore.save(a, set)) a.toast("Could not save your rules. They stay in use until the app is closed.");
        a.onBackPressed();
        RulesScreen.afterEdit(a, "Saved “" + r.name + "”.");
    }
}
