package com.storageplanner.app;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Colours (light + dark, all text >= 4.5:1 contrast), spacing and accessible view helpers.
 * Risk is always shown as words as well as colour.
 */
public final class Ui {
    private Ui() {}

    public static boolean night;
    public static int bg, surface, surface2, text, text2, divider, accent, onAccent, meterUsed;
    public static final int[] tier = new int[5], tierBg = new int[5];
    public static final String[] TIER_WORD = {"Safe", "No loss", "Looks identical", "Small quality loss", "Your call"};
    public static final String[] TIER_SPOKEN = {
        "Safe: rebuildable or junk", "No loss: nothing unique is removed", "Looks identical: quality-checked re-encode",
        "Small quality loss", "Your call: deletes something unique, review each item"};

    public static void init(Context c) {
        night = Build.VERSION.SDK_INT >= 29
                && (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (!night) {
            bg = 0xFFFAFAFC; surface = 0xFFF0EEF4; surface2 = 0xFFE4E1EA; text = 0xFF1C1B1F; text2 = 0xFF4A4850;
            divider = 0xFFD5D1DC; accent = 0xFF1A56C4; onAccent = 0xFFFFFFFF; meterUsed = 0xFF6E6A78;
            int[] t = {0xFF1E6B2E, 0xFF1A56C4, 0xFF6B2FA8, 0xFF9A4A00, 0xFFB3261E};
            for (int i = 0; i < 5; i++) { tier[i] = t[i]; tierBg[i] = (t[i] & 0x00FFFFFF) | 0x1F000000; }
        } else {
            bg = 0xFF121316; surface = 0xFF1E1F24; surface2 = 0xFF2C2D33; text = 0xFFE6E1E5; text2 = 0xFFC9C5D0;
            divider = 0xFF3A3B42; accent = 0xFF8AB4F8; onAccent = 0xFF0B1A33; meterUsed = 0xFF8E8A98;
            int[] t = {0xFF7DD88F, 0xFF8AB4F8, 0xFFCDA5F5, 0xFFFFB86B, 0xFFF2B8B5};
            for (int i = 0; i < 5; i++) { tier[i] = t[i]; tierBg[i] = (t[i] & 0x00FFFFFF) | 0x33000000; }
        }
    }

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    public static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);       // scales with the user's font size
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    public static TextView heading(Context c, String s, float sp) {
        TextView t = text(c, s, sp, text, true);
        if (Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true);
        return t;
    }

    public static TextView add(LinearLayout parent, TextView t, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(parent.getContext(), topDp);
        parent.addView(t, lp);
        return t;
    }

    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(radiusPx);
        return g;
    }

    /** Risk badge: coloured pill with the tier word (never colour alone). */
    public static TextView badge(Context c, int r) {
        TextView t = text(c, TIER_WORD[r], 12, tier[r], true);
        t.setBackground(round(tierBg[r], dp(c, 12)));
        t.setPadding(dp(c, 10), dp(c, 3), dp(c, 10), dp(c, 3));
        t.setContentDescription("Risk: " + TIER_SPOKEN[r]);
        return t;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(surface, dp(c, 12)));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    public static Button button(Context c, String label, boolean primary) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setMinHeight(dp(c, 48)); b.setMinimumHeight(dp(c, 48));
        b.setTextColor(primary ? onAccent : accent);
        b.setBackground(primary ? round(accent, dp(c, 24)) : outline(c));
        b.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        if (Build.VERSION.SDK_INT >= 26) b.setStateListAnimator(null);
        return b;
    }

    /** Transparent rounded rectangle with a coloured border (selection frame). */
    public static GradientDrawable outlineDrawable(Context c, int color, float widthDp) {
        GradientDrawable g = round(0x00000000, dp(c, 8));
        g.setStroke(dp(c, widthDp), color);
        return g;
    }

    private static GradientDrawable outline(Context c) {
        GradientDrawable g = round(0x00000000, dp(c, 24));
        g.setStroke(dp(c, 1), divider);
        return g;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(divider);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(c, 1) / 2)));
        return v;
    }

    public static void ellipsizeMiddle(TextView t) { t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.MIDDLE); }

    public static String date(long ms) {
        return ms <= 0 ? "unknown" : new SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(new Date(ms));
    }

    public static String ago(long ms, long now) {
        if (ms <= 0) return "no record";
        long d = (now - ms) / 86400000L;
        if (d < 1) return "today";
        if (d < 60) return d + " days ago";
        if (d < 730) return (d / 30) + " months ago";
        return (d / 365) + " years ago";
    }
}
