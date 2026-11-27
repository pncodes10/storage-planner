package com.storageplanner.app;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * The "Mirage model" box at the top of the Algorithm Lab and v2 vs v3: whether the model is on the phone, a button to
 * download it now (the first analysis also downloads it by itself), and one to remove it to free the space.
 */
final class MirageCard {
    private MirageCard() {}

    static void addTo(final MainActivity a, LinearLayout root) {
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(Ui.surface, Ui.dp(a, 8)));
        box.setPadding(Ui.dp(a, 10), Ui.dp(a, 8), Ui.dp(a, 10), Ui.dp(a, 8));
        final TextView status = Ui.text(a, "", 13, Ui.text, false);
        final ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setVisibility(View.GONE);
        final Button go = Ui.button(a, "Download Mirage model (22 MB)", true);
        final Button remove = Ui.button(a, "Remove Mirage model (frees 22 MB)", false);
        remove.setContentDescription("Remove the Mirage model from this phone. The next analysis downloads it again.");
        box.addView(status);
        box.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        box.addView(go, new LinearLayout.LayoutParams(-1, -2));
        box.addView(remove, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = Ui.dp(a, 10);
        root.addView(box, lp);

        final Runnable refresh = new Runnable() {
            public void run() {
                boolean have = MirageModel.installed(a), busy = MirageModel.busy();
                go.setVisibility(have || busy ? View.GONE : View.VISIBLE);
                remove.setVisibility(have && !busy ? View.VISIBLE : View.GONE);
                if (busy && bar.getVisibility() != View.VISIBLE) status.setText("Mirage model: being downloaded by the analysis…");
                else if (!busy) status.setText(have
                        ? "Mirage model: on this phone (22 MB). It stays when the app is updated."
                        : !MirageEngine.supported()
                        ? "Mirage model: not downloaded. Mirage runs only on 64-bit ARM phones, so it can't run on this one."
                        : "Mirage model: not downloaded. It is not part of the app, so updates stay small. The next analysis "
                          + "downloads it by itself (22 MB, one time, Wi-Fi or mobile data), or download it now. It is checked "
                          + "against a fixed checksum and kept in the app's private storage.");
            }
        };
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                go.setVisibility(View.GONE);
                bar.setProgress(0);
                bar.setVisibility(View.VISIBLE);
                status.setText("Downloading the Mirage model...");
                MirageModel.download(a, new MirageModel.Listener() {
                    public void progress(long done, long total) {
                        bar.setProgress((int) (1000L * done / Math.max(1, total)));
                        status.setText(String.format(Locale.US, "Downloading the Mirage model... %.1f of %.1f MB", done / 1e6, total / 1e6));
                    }
                    public void done(String error) {
                        bar.setVisibility(View.GONE);
                        refresh.run();
                        if (error != null) status.setText("Mirage model: " + error + ". Try again.");
                    }
                });
            }
        });
        remove.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (MirageModel.busy()) return;
                MirageModel.remove(a);
                refresh.run();
                a.toast("Mirage model removed. The next analysis downloads it again.");
            }
        });
        refresh.run();
    }
}
