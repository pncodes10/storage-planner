package com.storageplanner.app;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

/** The "Mirage model" box at the top of the Algorithm Lab and v2 vs v3: whether the model is on the phone, and a button to download it. */
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
        box.addView(status);
        box.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        box.addView(go, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = Ui.dp(a, 10);
        root.addView(box, lp);

        final Runnable refresh = new Runnable() {
            public void run() {
                boolean have = MirageModel.installed(a);
                go.setVisibility(have || MirageModel.busy() ? View.GONE : View.VISIBLE);
                if (!MirageModel.busy()) status.setText(have
                        ? "Mirage model: on this phone (22 MB). It stays when the app is updated."
                        : "Mirage model: not downloaded. It is not part of the app, so updates stay small; download it once over Wi-Fi (22 MB, "
                          + "checked against a fixed checksum, kept in the app's private storage). Mirage runs, and the Lab buttons that use it work, only after this.");
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
                        status.setText(String.format(Locale.US, "Downloading the Mirage model... %.1f of %.1f MB", done / 1048576.0, total / 1048576.0));
                    }
                    public void done(String error) {
                        bar.setVisibility(View.GONE);
                        refresh.run();
                        if (error != null) { status.setText(error + " Try again."); }
                    }
                });
            }
        });
        refresh.run();
    }
}
