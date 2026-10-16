package com.storageplanner.app;

import android.view.View;

/** One page in the app's back stack. Views are built lazily and rebuilt on theme change. */
public abstract class Screen {
    protected final MainActivity a;
    private View view;

    protected Screen(MainActivity a) { this.a = a; }

    public abstract String title();
    protected abstract View build();

    /** Identifies this screen for restoring the back stack after process death; null = not restorable. */
    public String descriptor() { return null; }

    /** Called whenever the screen becomes visible again (statuses may have changed). */
    public void refresh() {}

    /** Called when the screen stops being visible (another screen on top, back, or app in background). */
    public void onHide() {}

    public final View view() {
        if (view == null) { view = build(); view.setBackgroundColor(Ui.bg); }
        return view;
    }

    public final void invalidate() { view = null; }

    protected int dp(float v) { return Ui.dp(a, v); }
}
