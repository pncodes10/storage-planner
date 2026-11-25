package com.storageplanner.app;

import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

/** Settings: what the analysis aims for, your rules, developer mode (shows the Algorithm Lab), about. */
public final class SettingsScreen extends Screen {
    private LinearLayout content;

    public SettingsScreen(MainActivity a) { super(a); }

    public String title() { return "Settings"; }

    @Override public String descriptor() { return "settings"; }

    protected View build() {
        ScrollView sv = new ScrollView(a);
        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(32));
        sv.addView(content);
        refresh();
        return sv;
    }

    private interface Num { void set(int v); }

    /** A labelled whole-number field that saves as you type (empty = 0). */
    private void number(LinearLayout parent, String label, String help, String none, int value, final Num save) {
        TextView l = Ui.add(parent, Ui.text(a, label, 15, Ui.text, false), 16);
        Ui.add(parent, Ui.text(a, help, 13, Ui.text2, false), 2);
        final EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        e.setTextColor(Ui.text);
        e.setHint(none);
        e.setText(value > 0 ? String.valueOf(value) : "");
        e.setId(View.generateViewId());
        l.setLabelFor(e.getId());
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {}
            public void afterTextChanged(Editable s) {
                String t = s.toString().trim();
                if (t.isEmpty()) { save.set(0); return; }
                try { save.set(Integer.parseInt(t)); } catch (NumberFormatException ignored) {}
            }
        });
        parent.addView(e, new LinearLayout.LayoutParams(-1, -2));
    }

    @Override public void refresh() {
        if (content == null) return;
        content.removeAllViews();

        // ---- analysis
        LinearLayout an = section("Analysis");
        TextView tl = Ui.add(an, Ui.text(a, "Target: used space to reach, in GB", 15, Ui.text, false), 10);
        Ui.add(an, Ui.text(a, "The plan aims to bring your used storage down to this. The default fits the 70-of-128 GB ratio to your phone.", 13, Ui.text2, false), 2);
        final EditText target = new EditText(a);
        target.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        target.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        target.setTextColor(Ui.text);
        target.setText(String.format(Locale.US, "%.0f", PlanStore.targetGb(a)));
        target.setId(View.generateViewId());
        tl.setLabelFor(target.getId());
        target.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {}
            public void afterTextChanged(Editable s) {
                try { double v = Double.parseDouble(s.toString().trim()); if (v > 0) PlanStore.setTargetGb(a, v); } catch (NumberFormatException ignored) {}
            }
        });
        an.addView(target, new LinearLayout.LayoutParams(-1, -2));
        number(an, "Pictures to analyse", "Only the newest this many pictures are analysed, so a run is quick when you want to try the "
                + "screens. Leave empty or 0 for all of them. Other files and apps are still checked.", "All", PlanStore.maxPictures(a), new Num() {
            public void set(int v) { PlanStore.setMaxPictures(a, v); }
        });
        number(an, "Skip pictures smaller than (KB)", "Pictures under this size are left out of the analysis: they free little space and "
                + "take time to check. For example 100 skips everything under 100 KB. Leave empty or 0 to check all.", "None skipped", PlanStore.minPictureKb(a), new Num() {
            public void set(int v) { PlanStore.setMinPictureKb(a, v); }
        });
        Ui.add(an, Ui.text(a, "These apply the next time you tap Analyse my storage.", 13, Ui.text2, false), 6);
        an.addView(check("Measure compression on a few of your photos (about a minute more, more accurate)", PlanStore.measure(a),
                new CompoundButton.OnCheckedChangeListener() { public void onCheckedChanged(CompoundButton b, boolean on) { PlanStore.setMeasure(a, on); } }));
        an.addView(check("Sort pictures into categories and read the text in them (the first time takes longer)", AnalysisRunner.sortPictures(a),
                new CompoundButton.OnCheckedChangeListener() { public void onCheckedChanged(CompoundButton b, boolean on) { AnalysisRunner.setSortPictures(a, on); } }));

        // ---- rules
        LinearLayout ru = section("Rules");
        Ui.add(ru, Ui.text(a, "Why files are suggested. Switch a rule off, change its numbers, or write your own.", 13, Ui.text2, false), 6);
        Button rules = Ui.button(a, "Your rules", false);
        rules.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new RulesScreen(a)); } });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2); rl.topMargin = dp(8);
        ru.addView(rules, rl);

        // ---- developer
        LinearLayout dev = section("Developer");
        LinearLayout row = Ui.row(a);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(a, "Developer mode", 16, Ui.text, true));
        col.addView(Ui.text(a, "Shows the Algorithm Lab: debug tools, model tests, v2 vs v3, the classic plan view.", 13, Ui.text2, false));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        Switch sw = new Switch(a);
        sw.setChecked(PlanStore.developer(a));
        sw.setContentDescription("Developer mode");
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) { PlanStore.setDeveloper(a, on); refresh(); }
        });
        row.addView(sw);
        LinearLayout.LayoutParams rwl = new LinearLayout.LayoutParams(-1, -2); rwl.topMargin = dp(8);
        dev.addView(row, rwl);
        if (PlanStore.developer(a)) {
            Button lab = Ui.button(a, "Open Algorithm Lab", false);
            lab.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new LabScreen(a)); } });
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-2, -2); ll.topMargin = dp(10);
            dev.addView(lab, ll);
        }

        // ---- about
        LinearLayout ab = section("About");
        String version = "";
        try { version = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName; } catch (Throwable ignored) {}
        Ui.add(ab, Ui.text(a, "Storage Planner " + version, 15, Ui.text, true), 8);
        Ui.add(ab, Ui.text(a, "Everything runs on your phone: the app has no internet access. It never deletes a file by itself: files you choose to "
                + "delete go to your phone's Recycle bin, after you and Android confirm, and stay there for 30 days.", 13, Ui.text2, false), 4);
    }

    private LinearLayout section(String title) {
        Ui.add(content, Ui.heading(a, title, 18), content.getChildCount() == 0 ? 0 : 22);
        LinearLayout c = Ui.card(a);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(8);
        content.addView(c, lp);
        return c;
    }

    private CheckBox check(String text, boolean on, CompoundButton.OnCheckedChangeListener l) {
        CheckBox b = new CheckBox(a);
        b.setText(text);
        b.setTextColor(Ui.text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setChecked(on);
        b.setMinHeight(dp(48));
        b.setOnCheckedChangeListener(l);
        return b;
    }
}
