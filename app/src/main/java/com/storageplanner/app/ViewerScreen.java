package com.storageplanner.app;

import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.graphics.pdf.PdfRenderer;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import com.storageplanner.core.FileItem;
import com.storageplanner.core.Report;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-app viewer: zoomable photo, video player, audio player, PDF pages, text. Read-only.
 * Media is released whenever the screen is hidden, and re-opened (at the same position) when shown again.
 */
public final class ViewerScreen extends Screen {
    private final FileItem f;
    private final int kind;
    private FrameLayout stage;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int resumeMs;

    // live media
    private VideoView video;
    private MediaPlayer player;
    private SeekBar seek;
    private TextView time;
    private Button playBtn;
    private AudioFocusRequest focusReq;
    private PdfRenderer pdf;
    private ParcelFileDescriptor pdfFd;
    private ExecutorService pdfThread;
    private final Runnable tick = new Runnable() {
        public void run() {
            if (player != null) {
                try {
                    int p = player.getCurrentPosition(), d = player.getDuration();
                    seek.setMax(Math.max(1, d)); seek.setProgress(p);
                    time.setText(fmt(p) + " / " + fmt(d));
                } catch (Throwable ignored) {}
                ui.postDelayed(this, 250);
            }
        }
    };

    public ViewerScreen(MainActivity a, FileItem f) { super(a); this.f = f; this.kind = FileKind.of(f); }

    public String title() { return f.name(); }

    @Override public String descriptor() { return "viewer:" + f.rel; }

    protected View build() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        stage = new FrameLayout(a);
        stage.setBackgroundColor(kind == FileKind.TEXT ? Ui.bg : 0xFF000000);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout info = new LinearLayout(a);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(16), dp(10), dp(16), dp(4));
        TextView name = Ui.text(a, f.name(), 16, Ui.text, true); Ui.ellipsizeMiddle(name);
        int slash = f.rel.lastIndexOf('/');
        TextView sub = Ui.text(a, (slash < 0 ? "Internal storage" : f.rel.substring(0, slash)) + " · " + Report.size(f.size)
                + " · " + Ui.date(f.mtime), 13, Ui.text2, false);
        Ui.ellipsizeMiddle(sub);
        info.addView(name); info.addView(sub);
        root.addView(info);

        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout btns = Ui.row(a);
        btns.setPadding(dp(12), dp(6), dp(12), dp(12));
        addBtn(btns, "Open with…", true, new View.OnClickListener() { public void onClick(View v) { FileActions.openWith(a, f); } });
        addBtn(btns, "Show in folder", false, new View.OnClickListener() { public void onClick(View v) { FileActions.showInFolder(a, f); } });
        addBtn(btns, "Share", false, new View.OnClickListener() { public void onClick(View v) { FileActions.share(a, f); } });
        hs.addView(btns);
        root.addView(hs);
        stage.post(new Runnable() { public void run() { if (stage.getChildCount() == 0) refresh(); } });
        return root;
    }

    private void addBtn(LinearLayout row, String label, boolean primary, View.OnClickListener l) {
        Button b = Ui.button(a, label, primary);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(10);
        row.addView(b, lp);
    }

    @Override public void refresh() {
        if (stage == null) return;
        release();
        stage.removeAllViews();
        try {
            switch (kind) {
                case FileKind.IMAGE: showImage(); break;
                case FileKind.VIDEO: showVideo(); break;
                case FileKind.AUDIO: showAudio(); break;
                case FileKind.PDF: showPdf(); break;
                case FileKind.TEXT: showText(); break;
                default: showNone("There is no preview for this kind of file inside the app.");
            }
        } catch (Throwable t) {
            showNone("This file can't be shown here (" + t.getClass().getSimpleName() + ").");
        }
    }

    @Override public void onHide() { release(); }

    // ------------------------------------------------------------------ photo
    private void showImage() {
        final ZoomImageView iv = new ZoomImageView(a);
        iv.setContentDescription("Photo " + f.name() + ". Pinch or double tap to zoom; zoom actions are available.");
        stage.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        final ProgressBar pb = spinner();
        final int px = Math.max(a.getResources().getDisplayMetrics().widthPixels, a.getResources().getDisplayMetrics().heightPixels) * 3 / 2;
        new Thread(new Runnable() {
            public void run() {
                Bitmap b = null;
                try { b = Thumbs.make(f, px); } catch (Throwable ignored) {}
                final Bitmap fb = b;
                ui.post(new Runnable() {
                    public void run() {
                        stage.removeView(pb);
                        if (fb != null) iv.setImageBitmap(fb);
                        else showNone("This photo can't be decoded on this phone. Try Open with…");
                    }
                });
            }
        }, "viewer-image").start();
    }

    // ------------------------------------------------------------------ video
    private void showVideo() {
        final VideoView vv = new VideoView(a);
        video = vv;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        stage.addView(vv, lp);
        final MediaController mc = new MediaController(a);
        mc.setAnchorView(vv);
        vv.setMediaController(mc);
        vv.setContentDescription("Video " + f.name() + ". Double tap to show playback controls.");
        vv.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            public void onPrepared(MediaPlayer mp) {
                if (video != vv) return;                       // left the screen while loading
                if (resumeMs > 0) vv.seekTo(resumeMs);
                vv.start();
                try { mc.show(3000); } catch (Throwable ignored) {}
            }
        });
        vv.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer mp, int w, int x) {
                if (video != vv) return true;
                video = null;
                showNone("This video format can't be played here. Try Open with…");
                return true;
            }
        });
        vv.setVideoPath(f.abs);
    }

    // ------------------------------------------------------------------ audio
    private void showAudio() {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(24), dp(24), dp(24), dp(24));
        final ImageView art = new ImageView(a);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout artBox = new FrameLayout(a);
        GradientDrawable g = Ui.round(0xFF2A2A30, dp(16));
        artBox.setBackground(g);
        artBox.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND); artBox.setClipToOutline(true);
        TextView note = Ui.text(a, "♪", 64, 0xFFBDBDC8, false); note.setGravity(Gravity.CENTER);
        note.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        artBox.addView(note, new FrameLayout.LayoutParams(-1, -1));
        artBox.addView(art, new FrameLayout.LayoutParams(-1, -1));
        box.addView(artBox, new LinearLayout.LayoutParams(dp(180), dp(180)));
        Thumbs.load(art, f, dp(180));

        String title = f.name(), artist = "";
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(f.abs);
            String t = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String ar = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (t != null && !t.trim().isEmpty()) title = t;
            if (ar != null) artist = ar;
        } catch (Throwable ignored) {
        } finally { try { mmr.release(); } catch (Throwable ignored) {} }
        TextView tt = Ui.text(a, title, 18, 0xFFFFFFFF, true); tt.setGravity(Gravity.CENTER); Ui.ellipsizeMiddle(tt);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2); tl.topMargin = dp(16);
        box.addView(tt, tl);
        if (!artist.isEmpty()) { TextView at = Ui.text(a, artist, 14, 0xFFC9C5D0, false); at.setGravity(Gravity.CENTER); box.addView(at, new LinearLayout.LayoutParams(-1, -2)); }

        seek = new SeekBar(a);
        seek.setContentDescription("Playback position");
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, dp(48)); sl.topMargin = dp(16);
        box.addView(seek, sl);
        time = Ui.text(a, "0:00 / 0:00", 13, 0xFFC9C5D0, false); time.setGravity(Gravity.CENTER);
        box.addView(time, new LinearLayout.LayoutParams(-1, -2));
        playBtn = Ui.button(a, "Pause", true);
        playBtn.setEnabled(false);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(160), -2); pl.topMargin = dp(12);
        box.addView(playBtn, pl);
        stage.addView(box, new FrameLayout.LayoutParams(-1, -1));

        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            public void onPrepared(MediaPlayer mp) {
                if (player != mp) return;
                if (resumeMs > 0) mp.seekTo(resumeMs);
                requestFocus();
                mp.start();
                playBtn.setEnabled(true);
                playBtn.setText("Pause");
                ui.post(tick);
            }
        });
        player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            public void onCompletion(MediaPlayer mp) { playBtn.setText("Play"); resumeMs = 0; }
        });
        player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer mp, int w, int x) {
                release();
                showNone("This audio format can't be played here. Try Open with…");
                return true;
            }
        });
        playBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (player == null) return;
                if (player.isPlaying()) { player.pause(); playBtn.setText("Play"); }
                else { requestFocus(); player.start(); playBtn.setText("Pause"); ui.post(tick); }
            }
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) { if (fromUser && player != null) player.seekTo(p); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        try {
            player.setDataSource(f.abs);
            player.prepareAsync();
        } catch (Throwable t) {
            release();
            showNone("This audio file can't be opened here. Try Open with…");
        }
    }

    private void requestFocus() {
        try {
            AudioManager am = (AudioManager) a.getSystemService(android.content.Context.AUDIO_SERVICE);
            if (am == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()).build();
                am.requestAudioFocus(focusReq);
            }
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ PDF
    private void showPdf() throws Exception {
        pdfFd = ParcelFileDescriptor.open(new File(f.abs), ParcelFileDescriptor.MODE_READ_ONLY);
        pdf = new PdfRenderer(pdfFd);
        pdfThread = Executors.newSingleThreadExecutor();
        final int pages = pdf.getPageCount();
        final int width = a.getResources().getDisplayMetrics().widthPixels;
        final PdfRenderer renderer = pdf;
        final ExecutorService exec = pdfThread;
        ListView lv = new ListView(a);
        lv.setBackgroundColor(0xFF3A3A40);
        lv.setDividerHeight(dp(8));
        lv.setAdapter(new BaseAdapter() {
            public int getCount() { return pages; }
            public Object getItem(int i) { return i; }
            public long getItemId(int i) { return i; }
            public View getView(final int i, View v, ViewGroup parent) {
                final ImageView iv = v instanceof ImageView ? (ImageView) v : new ImageView(a);
                iv.setAdjustViewBounds(true);
                iv.setBackgroundColor(0xFFFFFFFF);
                iv.setMinimumHeight(width * 11 / 8);
                iv.setImageDrawable(null);
                iv.setTag(i);
                iv.setContentDescription("Page " + (i + 1) + " of " + pages);
                exec.execute(new Runnable() {
                    public void run() {
                        if (!Integer.valueOf(i).equals(iv.getTag()) || pdf != renderer) return;
                        Bitmap b = null;
                        try {
                            synchronized (renderer) {
                                PdfRenderer.Page p = renderer.openPage(i);
                                try {
                                    b = Bitmap.createBitmap(width, Math.max(1, (int) ((long) width * p.getHeight() / Math.max(1, p.getWidth()))), Bitmap.Config.ARGB_8888);
                                    b.eraseColor(0xFFFFFFFF);
                                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                                } finally { p.close(); }
                            }
                        } catch (Throwable ignored) { b = null; }
                        final Bitmap fb = b;
                        ui.post(new Runnable() { public void run() { if (fb != null && Integer.valueOf(i).equals(iv.getTag())) { iv.setMinimumHeight(0); iv.setImageBitmap(fb); } } });
                    }
                });
                return iv;
            }
        });
        stage.addView(lv, new FrameLayout.LayoutParams(-1, -1));
        TextView count = Ui.text(a, pages + (pages == 1 ? " page" : " pages"), 12, 0xFFFFFFFF, true);
        count.setBackground(Ui.round(0x99000000, dp(10)));
        count.setPadding(dp(10), dp(4), dp(10), dp(4));
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END); cl.setMargins(0, dp(10), dp(10), 0);
        stage.addView(count, cl);
    }

    // ------------------------------------------------------------------ text
    private void showText() throws Exception {
        final int limit = 512 * 1024;
        FileInputStream in = new FileInputStream(f.abs);
        byte[] buf;
        int n;
        try {
            buf = new byte[(int) Math.min(limit, Math.max(0, f.size))];
            n = 0; int r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
        } finally { in.close(); }
        String s = new String(buf, 0, n, StandardCharsets.UTF_8);
        if (f.size > limit) s += "\n\n… (showing the first 512 KB of " + Report.size(f.size) + ")";
        ScrollView sv = new ScrollView(a);
        TextView tv = Ui.text(a, s, 13, Ui.text, false);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));
        sv.addView(tv);
        stage.addView(sv, new FrameLayout.LayoutParams(-1, -1));
    }

    // ------------------------------------------------------------------ helpers
    private void showNone(String msg) {
        stage.removeAllViews();
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(32), dp(32), dp(32), dp(32));
        TextView ext = Ui.text(a, ListScreen.ext(f), 28, 0xFFBDBDC8, true); ext.setGravity(Gravity.CENTER);
        ext.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(ext);
        TextView t = Ui.text(a, msg, 15, 0xFFE6E1E5, false); t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(12);
        box.addView(t, lp);
        stage.setBackgroundColor(0xFF1E1F24);
        stage.addView(box, new FrameLayout.LayoutParams(-1, -1));
    }

    private ProgressBar spinner() {
        ProgressBar pb = new ProgressBar(a);
        pb.setContentDescription("Loading");
        stage.addView(pb, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        return pb;
    }

    /** Stop and free every media resource; remembers the playback position. */
    private void release() {
        ui.removeCallbacks(tick);
        if (video != null) {
            try { resumeMs = video.getCurrentPosition(); video.stopPlayback(); } catch (Throwable ignored) {}
            video = null;
        }
        if (player != null) {
            MediaPlayer p = player; player = null;
            try { if (p.isPlaying()) resumeMs = p.getCurrentPosition(); } catch (Throwable ignored) {}
            try { p.release(); } catch (Throwable ignored) {}
        }
        if (focusReq != null && Build.VERSION.SDK_INT >= 26) {
            try { ((AudioManager) a.getSystemService(android.content.Context.AUDIO_SERVICE)).abandonAudioFocusRequest(focusReq); } catch (Throwable ignored) {}
            focusReq = null;
        }
        if (pdfThread != null) { pdfThread.shutdownNow(); pdfThread = null; }
        if (pdf != null) {
            final PdfRenderer r = pdf; pdf = null;
            try { synchronized (r) { r.close(); } } catch (Throwable ignored) {}
            pdfFd = null;                                   // closed by PdfRenderer.close()
        }
    }

    private static String fmt(int ms) {
        int s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
