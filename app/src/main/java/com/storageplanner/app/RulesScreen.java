package com.storageplanner.app;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.storageplanner.core.Report;
import com.storageplanner.core.Rule;
import com.storageplanner.core.RuleSet;
import com.storageplanner.core.RuleText;

import java.util.Map;

/**
 * The rules: the user's own, then the built-in ones. Each row says in words what the rule looks for and does, what risk it
 * carries, how many files of the current scan it matches, and has a switch. Tap a row to change it. Every change is saved
 * and the plan is rebuilt from the scan already in memory (no new scan).
 */
public final class RulesScreen extends Screen {
    private LinearLayout content;
    private TextView status;
    private String confirmDelete;            // id of the rule whose "Delete rule" was tapped once
    /** What just happened, shown at the top (static: the editor sets it before this screen is shown again). */
    private static String note = "";

    // match counts for the current scan, worked out on a thread; valid for exactly this scan and this rule set
    private Map<String, RuleSet.Matches> tally;
    private Object tallyResult, tallyRules;
    private boolean counting;

    public RulesScreen(MainActivity a) { super(a); }

    public String title() { return "Your rules"; }

    @Override public String descriptor() { return "rules"; }

    protected View build() {
        ScrollView sv = new ScrollView(a);
        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(32));
        sv.addView(content);
        refresh();
        return sv;
    }

    @Override public void refresh() {
        if (content == null) return;
        content.removeAllViews();
        RuleSet rs = RulesStore.get(a);
        startCounting(rs);

        LinearLayout intro = Ui.card(a);
        Ui.add(intro, Ui.text(a, "Rules decide what this app suggests for each file. Nothing is ever deleted by the app: it only suggests, "
                + "and every file your own delete rules suggest needs your confirmation. ID documents are never suggested for deletion. "
                + "A file is matched when all the conditions of a rule are true; a file the app could not measure never matches.", 15, Ui.text, false), 0);
        content.addView(intro, new LinearLayout.LayoutParams(-1, -2));

        status = Ui.text(a, "", 15, Ui.text, true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Ui.add(content, status, 12);
        showStatus();

        if (!rs.warnings.isEmpty()) {
            Ui.add(content, Ui.heading(a, "Notes about your saved rules", 16), 16);
            for (String w : rs.warnings) Ui.add(content, Ui.text(a, "• " + w, 14, Ui.tier[3], false), 4);
        }
        if (!Session.hasResults())
            Ui.add(content, Ui.text(a, "Analyse your storage to see how many files each rule matches.", 14, Ui.text2, false), 8);

        Button add = Ui.button(a, "Add a rule", true);
        add.setContentDescription("Add a rule of your own");
        add.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { confirmDelete = null; a.push(new RuleEditScreen(a, null)); } });
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2); al.topMargin = dp(16);
        content.addView(add, al);

        Ui.add(content, Ui.heading(a, "Your rules", 20), 24);
        int mine = 0;
        for (Rule r : rs.rules) if (!r.builtIn) { row(rs, r); mine++; }
        if (mine == 0)
            Ui.add(content, Ui.text(a, "You have no rules of your own yet. A rule can suggest deleting, suggest compressing, or protect files from every suggestion.", 14, Ui.text2, false), 6);

        Ui.add(content, Ui.heading(a, "Built-in rules", 20), 28);
        Ui.add(content, Ui.text(a, "These make today's suggestions. Switch one off, or tap it to change its numbers. Reset puts it back.", 14, Ui.text2, false), 4);
        for (Rule r : rs.rules) if (r.builtIn) row(rs, r);
    }

    @Override public void onHide() { note = ""; confirmDelete = null; }

    /** After the editor saved: the note shows on the list, with what the new plan frees once it is built. */
    static void afterEdit(MainActivity a, String what) {
        note = what;
        Session.rebuild(a, planNote);
    }

    private static final Runnable planNote = new Runnable() {
        public void run() { if (note.length() > 0 && Session.hasResults()) note += " Your plan now frees " + Report.gb(Session.plan.saved()) + "."; }
    };

    private void showStatus() {
        if (status == null) return;
        status.setText(Session.rebuilding() ? "Updating your plan…" : note);
        status.setVisibility(status.getText().length() == 0 ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------ match counts
    private void startCounting(final RuleSet rs) {
        if (!Session.hasResults()) { tally = null; return; }
        final com.storageplanner.core.Scanner.Result res = Session.result;
        if (tallyResult == res && tallyRules == rs && tally != null) return;
        if (counting) return;
        counting = true; tally = null;
        final com.storageplanner.core.Tags.Context ctx = Session.tags();
        final com.storageplanner.core.Estimates est = Session.est;
        new Thread(new Runnable() {
            public void run() {
                Map<String, RuleSet.Matches> t = null;
                try { t = rs.tally(res, ctx, est, res.now); } catch (Throwable ignored) { t = new java.util.HashMap<String, RuleSet.Matches>(); }
                final Map<String, RuleSet.Matches> ft = t;
                AnalysisState.UI.post(new Runnable() {
                    public void run() {
                        counting = false;
                        tally = ft; tallyResult = res; tallyRules = rs;
                        if (RulesStore.get(a) != rs || Session.result != res) { tally = null; }
                        refresh();
                    }
                });
            }
        }, "count-rule-matches").start();
    }

    /** "Matches 143 files, 1.2 GB" (a re-encode adds what it would free, as an estimate). */
    static String matchLine(Rule r, RuleSet.Matches m) {
        if (m.count == 0) return "Matches no files in your scan";
        String s = "Matches " + m.count + (m.count == 1 ? " file, " : " files, ") + Report.size(m.bytes);
        if (r.action != null && r.action.compress()) s += "; would free about " + Report.size(m.saved) + " (estimate)";
        return s;
    }

    // ------------------------------------------------------------------ one rule
    private void row(final RuleSet rs, final Rule r) {
        LinearLayout card = Ui.card(a);
        LinearLayout head = Ui.row(a);
        LinearLayout text = new LinearLayout(a);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setMinimumHeight(dp(48));
        final String problem = r.problem();
        final boolean on = r.enabled && problem == null;
        TextView name = Ui.text(a, r.name + (on ? "" : "  ·  Off"), 16, Ui.text, true);
        text.addView(name);
        Ui.add(text, Ui.text(a, RuleText.conditions(r), 14, Ui.text2, false), 2);
        LinearLayout act = Ui.row(a);
        int risk = r.effectiveRisk();
        if (risk >= 0) { act.addView(Ui.badge(a, risk)); }
        TextView actText = Ui.text(a, (risk >= 0 ? "  " : "") + RuleText.action(r) + (risk == com.storageplanner.core.Option.R4 ? ": needs your confirmation" : risk < 0 ? ": no risk, nothing is touched" : ""), 14, Ui.text, true);
        act.addView(actText);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2); alp.topMargin = dp(6);
        text.addView(act, alp);
        String matchText = null;
        if (Session.hasResults()) {
            RuleSet.Matches m = tally == null ? null : tally.get(r.id);
            matchText = m == null ? "Counting matches…" : matchLine(r, m);
            Ui.add(text, Ui.text(a, matchText, 14, Ui.text, false), 4);
        }
        if (problem != null) Ui.add(text, Ui.text(a, "Cannot run: " + problem + ". Tap to fix it.", 14, Ui.tier[4], true), 4);
        text.setClickable(true); text.setFocusable(true);
        text.setContentDescription(r.name + (on ? ", on" : ", off") + ". " + RuleText.conditions(r) + ". " + RuleText.action(r) + ". "
                + (risk >= 0 ? Ui.TIER_SPOKEN[risk] + ". " : "No risk. ") + (matchText != null ? matchText + ". " : "")
                + (problem != null ? "Cannot run: " + problem + ". " : "") + "Double tap to change.");
        text.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { confirmDelete = null; a.push(new RuleEditScreen(a, r.id)); } });
        head.addView(text, new LinearLayout.LayoutParams(0, -2, 1));

        Switch sw = new Switch(a);
        sw.setChecked(on);
        sw.setEnabled(problem == null);
        sw.setMinimumHeight(dp(48)); sw.setMinimumWidth(dp(56));
        sw.setContentDescription(r.name + (problem != null ? ", cannot run" : ""));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                if (checked == on) return;
                RuleSet n = RulesStore.get(a).copy();
                Rule x = n.find(r.id);
                if (x == null) return;
                x.enabled = checked;
                note = "“" + r.name + "” is now " + (checked ? "on." : "off.");
                commit(n);
            }
        });
        LinearLayout.LayoutParams swl = new LinearLayout.LayoutParams(-2, -2); swl.leftMargin = dp(8);
        head.addView(sw, swl);
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        if (r.builtIn && RuleSet.changed(r)) {
            Button reset = Ui.button(a, "Reset to default", false);
            reset.setContentDescription("Reset " + r.name + " to the built-in default");
            reset.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    RuleSet n = RulesStore.get(a).copy();
                    n.reset(r.id);
                    note = "“" + r.name + "” is back to its default.";
                    commit(n);
                }
            });
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2); rl.topMargin = dp(8);
            Ui.add(card, Ui.text(a, "Changed from the default", 13, Ui.tier[3], true), 6);
            card.addView(reset, rl);
        }
        if (!r.builtIn) {
            boolean sure = r.id.equals(confirmDelete);
            Button del = Ui.button(a, sure ? "Tap again to delete this rule" : "Delete rule", false);
            del.setContentDescription(sure ? "Confirm: delete the rule " + r.name : "Delete the rule " + r.name);
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (!r.id.equals(confirmDelete)) { confirmDelete = r.id; refresh(); v.announceForAccessibility("Tap again to delete the rule " + r.name); return; }
                    confirmDelete = null;
                    RuleSet n = RulesStore.get(a).copy();
                    n.remove(r.id);
                    note = "Rule “" + r.name + "” deleted.";
                    commit(n);
                }
            });
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2); dl.topMargin = dp(8);
            card.addView(del, dl);
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.topMargin = dp(10);
        content.addView(card, cl);
    }

    /** Saves the rules, rebuilds the plan from the scan in memory, and shows the result. */
    private void commit(RuleSet n) {
        if (!RulesStore.save(a, n)) a.toast("Could not save your rules. They stay in use until the app is closed.");
        Session.rebuild(a, planNote);
        refresh();
    }
}
