package com.storageplanner.app;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;

/** Image with pinch-to-zoom, pan and double-tap zoom; zoom actions for TalkBack users too. */
public final class ZoomImageView extends ImageView {
    private static final int ACT_ZOOM_IN = 0x7f0f0101, ACT_ZOOM_OUT = 0x7f0f0102, ACT_RESET = 0x7f0f0103;
    private static final float MAX = 8f;
    private final Matrix m = new Matrix();
    private final float[] v = new float[9];
    private float base = 1f;
    private final ScaleGestureDetector sgd;
    private final GestureDetector gd;
    private Listener listener;
    private boolean quiet;                       // applying someone else's state: don't echo it back

    /** Told whenever the user changes zoom or position (for zooming two photos together). */
    public interface Listener { void onViewChanged(ZoomImageView v); }
    public void setListener(Listener l) { listener = l; }

    private void changed() { if (!quiet && listener != null) listener.onViewChanged(this); }

    /**
     * {zoom, x, y}: zoom relative to fit-to-screen and the image point at the view centre, as fractions of
     * the image size, so two photos of different resolutions can show the same spot. Null before layout.
     */
    public float[] state() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || d.getIntrinsicWidth() <= 0) return null;
        Matrix inv = new Matrix();
        if (!m.invert(inv)) return null;
        float[] c = {getWidth() / 2f, getHeight() / 2f};
        inv.mapPoints(c);
        return new float[]{zoom(), c[0] / d.getIntrinsicWidth(), c[1] / d.getIntrinsicHeight()};
    }

    public void applyState(float[] st) {
        Drawable d = getDrawable();
        if (st == null || d == null || getWidth() == 0 || d.getIntrinsicWidth() <= 0) return;
        quiet = true;
        try {
            float dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
            base = Math.min(getWidth() / dw, getHeight() / dh);
            float s = base * Math.max(1f, Math.min(MAX, st[0]));
            m.reset();
            m.postScale(s, s);
            m.postTranslate(getWidth() / 2f - st[1] * dw * s, getHeight() / 2f - st[2] * dh * s);
            clamp();
            announceZoom();
        } finally { quiet = false; }
    }

    public ZoomImageView(Context c) {
        super(c);
        setScaleType(ScaleType.MATRIX);
        sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) { zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY()); return true; }
        });
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                m.postTranslate(-dx, -dy); clamp(); return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (zoom() > 1.05f) reset(); else zoomBy(2.5f, e.getX(), e.getY());
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { return performClick(); }
        });
    }

    @Override public void setImageDrawable(Drawable d) { super.setImageDrawable(d); post(new Runnable() { public void run() { fit(); } }); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { super.onSizeChanged(w, h, ow, oh); fit(); }

    /** Current zoom relative to "fit to screen". */
    public float zoom() { m.getValues(v); return v[Matrix.MSCALE_X] / base; }

    /** Fit to screen because of a layout or image change (not the user): partners are not told. */
    private void fit() { quiet = true; try { reset(); } finally { quiet = false; } }

    public void reset() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || d.getIntrinsicWidth() <= 0) return;
        float dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight(), w = getWidth(), h = getHeight();
        base = Math.min(w / dw, h / dh);
        m.reset();
        m.postScale(base, base);
        m.postTranslate((w - dw * base) / 2, (h - dh * base) / 2);
        setImageMatrix(m);
        announceZoom();
        changed();
    }

    private void zoomBy(float f, float fx, float fy) {
        float z = zoom(), nz = Math.max(1f, Math.min(MAX, z * f));
        f = nz / z;
        m.postScale(f, f, fx, fy);
        clamp();
    }

    private void clamp() {
        Drawable d = getDrawable();
        if (d == null) return;
        RectF r = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        m.mapRect(r);
        float w = getWidth(), h = getHeight(), dx = 0, dy = 0;
        if (r.width() <= w) dx = (w - r.width()) / 2 - r.left; else if (r.left > 0) dx = -r.left; else if (r.right < w) dx = w - r.right;
        if (r.height() <= h) dy = (h - r.height()) / 2 - r.top; else if (r.top > 0) dy = -r.top; else if (r.bottom < h) dy = h - r.bottom;
        m.postTranslate(dx, dy);
        setImageMatrix(m);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(zoom() > 1.01f);
        changed();
    }

    // ---- face markers (from the photo-quality check): green = eyes open, red = eyes look closed
    private float[] faces; private int fields;
    private final android.graphics.Paint fp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Paint tp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

    /** faces: per face {x, y, eye distance (fractions of the picture), eyes-open probability, ...}; null = none. */
    public void setFaces(float[] f, int fieldsPerFace) { faces = f; fields = fieldsPerFace; invalidate(); }

    @Override protected void onDraw(android.graphics.Canvas c) {
        super.onDraw(c);
        Drawable d = getDrawable();
        if (faces == null || d == null || fields <= 3) return;
        float dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight(), den = getResources().getDisplayMetrics().density;
        fp.setStyle(android.graphics.Paint.Style.STROKE);
        fp.setStrokeWidth(2 * den);
        tp.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
        tp.setFakeBoldText(true);
        RectF r = new RectF();
        for (int i = 0; i + fields <= faces.length; i += fields) {
            float x = faces[i] * dw, y = faces[i + 1] * dh, e = faces[i + 2] * dw;
            boolean open = faces[i + 3] >= 0.5f;
            r.set(x - e, y - 0.9f * e, x + e, y + 1.4f * e);
            m.mapRect(r);
            fp.setColor(open ? 0xFF7DD88F : 0xFFFF6B6B);
            c.drawRoundRect(r, 8 * den, 8 * den, fp);
            String label = open ? "Eyes open" : "Eyes closed";
            float tw = tp.measureText(label), pad = 4 * den, th = tp.getTextSize();
            float lx = r.left, ly = r.top - th - 2 * pad;
            if (ly < 0) ly = r.bottom;
            tp.setColor(open ? 0xCC1E6B2E : 0xE6B3261E);
            c.drawRoundRect(new RectF(lx, ly, lx + tw + 2 * pad, ly + th + 2 * pad), 4 * den, 4 * den, tp);
            tp.setColor(0xFFFFFFFF);
            c.drawText(label, lx + pad, ly + pad + th * 0.85f, tp);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        sgd.onTouchEvent(e);
        if (!sgd.isInProgress()) gd.onTouchEvent(e);
        return true;
    }

    @Override public boolean performClick() { return super.performClick(); }

    private void announceZoom() {
        setStateDescriptionCompat(Math.round(zoom() * 100) + " percent zoom");
    }

    private void setStateDescriptionCompat(String s) {
        if (android.os.Build.VERSION.SDK_INT >= 30) setStateDescription(s);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACT_ZOOM_IN, "Zoom in"));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACT_ZOOM_OUT, "Zoom out"));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACT_RESET, "Fit to screen"));
    }

    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == ACT_ZOOM_IN) { zoomBy(1.5f, getWidth() / 2f, getHeight() / 2f); announceZoom(); return true; }
        if (action == ACT_ZOOM_OUT) { zoomBy(1 / 1.5f, getWidth() / 2f, getHeight() / 2f); announceZoom(); return true; }
        if (action == ACT_RESET) { reset(); return true; }
        return super.performAccessibilityAction(action, args);
    }
}
