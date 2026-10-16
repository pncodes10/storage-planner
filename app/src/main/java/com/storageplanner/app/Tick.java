package com.storageplanner.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * "Delete this one" checkbox drawn for the dark photo background (a framework CheckBox takes its colours from
 * the theme and can disappear on a photo). Ticked = red box with a white tick. The touch area is 48 dp.
 */
final class Tick extends View {
    private boolean checked;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF box = new RectF();
    private final float dp;

    Tick(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(0xFFFFFFFF);
        setMinimumWidth((int) (48 * dp)); setMinimumHeight((int) (48 * dp));
    }

    boolean isChecked() { return checked; }

    void setChecked(boolean c) {
        if (c == checked) return;
        checked = c;
        invalidate();
        if (android.os.Build.VERSION.SDK_INT >= 30) setStateDescription(c ? "ticked, will be deleted" : "not ticked, will be kept");
    }

    @Override protected void onMeasure(int ws, int hs) {
        int s = (int) (48 * dp);
        setMeasuredDimension(resolveSize(s, ws), resolveSize(s, hs));
    }

    @Override protected void onDraw(Canvas c) {
        float s = 26 * dp, cx = getWidth() / 2f, cy = getHeight() / 2f;
        box.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
        fill.setColor(checked ? 0xFFB3261E : 0x99000000);
        c.drawRoundRect(box, 6 * dp, 6 * dp, fill);
        line.setStrokeWidth(2.5f * dp);
        c.drawRoundRect(box, 6 * dp, 6 * dp, line);
        if (checked) {
            line.setStrokeWidth(3 * dp);
            path.reset();
            path.moveTo(cx - 7 * dp, cy + 0.5f * dp);
            path.lineTo(cx - 2 * dp, cy + 5.5f * dp);
            path.lineTo(cx + 7.5f * dp, cy - 5 * dp);
            c.drawPath(path, line);
        }
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.CheckBox");
        info.setCheckable(true);
        info.setChecked(checked);
    }
}
