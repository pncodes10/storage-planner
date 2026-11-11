package com.storageplanner.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.storageplanner.core.Policy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Algorithm settings: how readily a file is called "not needed", and which ways of judging are used
 * (core/Need.java). Changes apply to the current results at once; no new scan is needed, except for
 * "Look inside videos", which is done during a scan.
 */
public final class AlgorithmScreen extends Screen {
    private static final String PREFS = "algorithm";
    private LinearLayout levels;
    private TextView levelText, status;

    public AlgorithmScreen(MainActivity a) { super(a); }

    public String title() { return "Algorithm settings"; }
    @Override public String descriptor() { return "algorithm"; }

    // ------------------------------------------------------------------ saved settings

    /** The saved settings as a new Policy, with the user's earlier decisions for "learn from my choices". */
    public static Policy load(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Policy p = new Policy();
        int s = sp.getInt("sensitivity", Policy.BALANCED);
        p.sensitivity = s == Policy.CAREFUL || s == Policy.THOROUGH ? s : Policy.BALANCED;
        p.useSource = sp.getBoolean("source", true);
        p.useContent = sp.getBoolean("content", true);
        p.usePeople = sp.getBoolean("people", true);
        p.useQuality = sp.getBoolean("quality", true);
        p.useAge = sp.getBoolean("age", true);
        p.useEvent = sp.getBoolean("event", true);
        p.useMotion = sp.getBoolean("motion", true);
        p.useHistory = sp.getBoolean("history", true);
        p.lookInsideVideos = sp.getBoolean("videos", true);
        p.readIndic = sp.getBoolean("indic", true);
        // copies: these are read on background threads while the user keeps deciding
        SharedPreferences rv = c.getSharedPreferences("review", Context.MODE_PRIVATE);
        Set<String> kept = new HashSet<String>(rv.getStringSet("kept", new HashSet<String>()));
        Map<String, String> accepted = new HashMap<String, String>();
        for (String e : rv.getStringSet("accepted", new HashSet<String>())) {
            int i = e.indexOf('\t');
            if (i > 0) accepted.put(e.substring(0, i), e.substring(i + 1));
        }
        p.kept = kept; p.accepted = accepted;
        return p;
    }

    /** Loads the saved settings into Policy.active (used by every plan built from now on). */
    public static void activate(Context c) { Policy.active = load(c); }

    private void put(String key, boolean on) {
        a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, on).apply();
        changed();
    }

    /** Re-judges the current results with the new settings. */
    private void changed() {
        activate(a);
        if (!Session.hasResults()) { if (status != null) status.setText("Used from the next analysis on."); return; }
        if (status != null) status.setText("Updating your plan…");
        Session.rebuildAsync(new Runnable() {
            public void run() {
                if (status == null) return;
                status.setText("Your plan was updated: it now frees " + com.storageplanner.core.Report.size(Session.plan.saved()) + ".");
                status.announceForAccessibility(status.getText());
            }
        });
    }

    // ------------------------------------------------------------------ page

    protected View build() {
        ScrollView sv = new ScrollView(a);
        LinearLayout L = new LinearLayout(a);
        L.setOrientation(LinearLayout.VERTICAL);
        L.setPadding(dp(16), dp(16), dp(16), dp(32));
        sv.addView(L);

        Ui.add(L, Ui.text(a, "For photos, videos and downloads that are not copies of anything, the app estimates whether you will "
                + "need the file. Files you probably need are left alone. Files you probably do not need are suggested. "
                + "The ones in between are shown for you to decide.", 15, Ui.text2, false), 0);

        LinearLayout card = Ui.card(a);
        Ui.add(card, Ui.heading(a, "How readily a file is called \"not needed\"", 17), 0);
        levels = Ui.row(a);
        card.addView(levels, new LinearLayout.LayoutParams(-1, -2));
        ((LinearLayout.LayoutParams) levels.getLayoutParams()).topMargin = dp(10);
        levelText = Ui.add(card, Ui.text(a, "", 14, Ui.text2, false), 10);
        addCard(L, card);
        levelButtons();

        LinearLayout ways = Ui.card(a);
        Ui.add(ways, Ui.heading(a, "What the app looks at", 17), 0);
        Ui.add(ways, Ui.text(a, "Each of these adds to or takes away from the estimate. One reason is never enough to suggest a file: "
                + "at least two different ones must agree. Switching one off can only make the app suggest less.", 14, Ui.text2, false), 6);
        Policy p = Policy.active;
        way(ways, "source", p.useSource, "Where it came from", "Your camera, a screenshot, a chat, a download.");
        way(ways, "content", p.useContent, "What it shows", "One-time codes, greetings, tickets, receipts, documents, or nothing at all.");
        way(ways, "people", p.usePeople, "People in it", "Faces are the strongest reason to keep a photo or video.");
        way(ways, "quality", p.useQuality, "Blur and length", "Very soft or very small pictures, clips of a second or two.");
        way(ways, "age", p.useAge, "Age, by kind", "A code expires in a day and a ticket after its date. Your own photos and videos never count as old.");
        way(ways, "event", p.useEvent, "Taken with others", "Photos and videos taken within the same hour belong to a trip or a party.");
        way(ways, "motion", p.useMotion, "Videos: does anything happen", "A video in which every frame looks the same.");
        way(ways, "history", p.useHistory, "Learn from my choices", "After five decisions on one kind of suggestion, what you kept or approved counts for the next ones.");
        addCard(L, ways);

        LinearLayout scan = Ui.card(a);
        Ui.add(scan, Ui.heading(a, "During the analysis", 17), 0);
        way(scan, "videos", p.lookInsideVideos, "Look inside videos",
                "Reads five frames of each of your videos to see whether it is black, empty or still, whether people are in it, "
                + "and whether a WhatsApp Sent copy matches it. Takes about a second per video the first time. Used from the next analysis on.");
        way(scan, "indic", p.readIndic, "Read Marathi and Hindi text",
                "Reads Devanagari lettering on colourful pictures, so that greetings and festival wishes in Marathi and Hindi are recognised. "
                + "About half a second for each such picture. Used from the next analysis on.");
        addCard(L, scan);

        status = Ui.add(L, Ui.text(a, "", 14, Ui.text2, false), 12);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Ui.add(L, Ui.text(a, "These settings never loosen the fixed rules: personal records, the copy that is kept of a group, and files "
                + "with companions are never suggested, at any setting.", 14, Ui.text2, false), 12);
        return sv;
    }

    private void levelButtons() {
        levels.removeAllViews();
        final String[] what = {
                "Careful: suggests only files that are clearly not needed, and leaves alone anything that might be. Nothing unique is added to your plan automatically.",
                "Balanced: suggests files that are probably not needed, and shows the unclear ones for you to decide. Nothing unique is added to your plan automatically.",
                "Thorough: suggests more, and adds files that are almost certainly not needed (such as expired one-time codes) to your plan. You still confirm before anything is deleted."};
        int cur = Policy.active.sensitivity;
        for (int i = 0; i < 3; i++) {
            final int level = i;
            boolean sel = cur == i;
            Button b = Ui.button(a, Policy.sensitivityName(i), sel);
            b.setContentDescription(what[i] + (sel ? " Selected." : ""));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (Policy.active.sensitivity == level) return;
                    a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("sensitivity", level).apply();
                    changed();
                    levelButtons();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) lp.leftMargin = dp(8);
            levels.addView(b, lp);
        }
        levelText.setText(what[cur]);
    }

    private void way(LinearLayout parent, final String key, boolean on, String name, String what) {
        CheckBox box = new CheckBox(a);
        box.setText(name + "\n" + what);
        box.setTextColor(Ui.text);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        box.setChecked(on);
        box.setMinHeight(dp(48));
        box.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) { put(key, checked); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(8);
        parent.addView(box, lp);
    }

    private void addCard(LinearLayout p, View c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(16);
        p.addView(c, lp);
    }
}
