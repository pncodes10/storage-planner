package com.storageplanner.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Capacity bar: used after the plan | freed by the plan | free now, plus a target marker. */
public final class MeterView extends View {
    private long total = 1, used, saved, target;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    public MeterView(Context c) {
        super(c);
        setMinimumHeight(Ui.dp(c, 28));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void set(long total, long used, long saved, long target) {
        this.total = Math.max(1, total); this.used = used; this.saved = Math.min(saved, used); this.target = target;
        setContentDescription(String.format(java.util.Locale.US,
                "Storage bar. %.1f GB used now, %.1f GB after the plan, target %.1f GB, capacity %.1f GB.",
                used / 1e9, (used - this.saved) / 1e9, target / 1e9, total / 1e9));
        invalidate();
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), w), Ui.dp(getContext(), 28));
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), bar = Ui.dp(getContext(), 18), top = (h - bar) / 2, rad = bar / 2;
        r.set(0, top, w, top + bar);
        p.setColor(Ui.surface2); c.drawRoundRect(r, rad, rad, p);                       // free space
        float xAfter = w * (used - saved) / total, xUsed = w * used / total;
        c.save(); c.clipRect(0, top, xUsed, top + bar);
        p.setColor(Ui.accent); c.drawRoundRect(r, rad, rad, p);                          // freed by plan
        c.restore();
        c.save(); c.clipRect(0, top, xAfter, top + bar);
        p.setColor(Ui.meterUsed); c.drawRoundRect(r, rad, rad, p);                       // still used after plan
        c.restore();
        float xt = w * target / total;                                                    // target marker
        p.setColor(Ui.text);
        c.drawRect(xt - Ui.dp(getContext(), 1.5f), 0, xt + Ui.dp(getContext(), 1.5f), h, p);
    }
}
