package com.storageplanner.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.ThumbnailUtils;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import com.storageplanner.core.Categories;
import com.storageplanner.core.FileItem;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Asynchronous thumbnails for images and videos, with a memory cache. Read-only. */
public final class Thumbs {
    private Thumbs() {}

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(
            (int) Math.min(48L * 1024, Runtime.getRuntime().maxMemory() / 1024 / 8)) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
    };

    public static boolean canPreview(FileItem f) {
        int k = FileKind.of(f);
        return k == FileKind.IMAGE || k == FileKind.VIDEO || k == FileKind.PDF || k == FileKind.AUDIO;
    }

    /** Loads into iv unless the view has been recycled for another file meanwhile. */
    public static void load(final ImageView iv, final FileItem f, final int px) {
        final String key = f.abs + "@" + px;
        iv.setTag(key);
        Bitmap hit = CACHE.get(key);
        if (hit != null) { iv.setImageBitmap(hit); return; }
        iv.setImageDrawable(null);
        if (!canPreview(f)) return;
        POOL.execute(new Runnable() {
            public void run() {
                if (!key.equals(iv.getTag())) return;          // scrolled away before we started
                Bitmap b = null;
                try { b = make(f, px); } catch (Throwable ignored) {}
                if (b == null) return;
                CACHE.put(key, b);
                final Bitmap fb = b;
                UI.post(new Runnable() { public void run() { if (key.equals(iv.getTag())) iv.setImageBitmap(fb); } });
            }
        });
    }

    @SuppressWarnings("deprecation")
    static Bitmap make(FileItem f, int px) throws Exception {
        File file = new File(f.abs);
        int kind = FileKind.of(f);
        if (kind == FileKind.PDF) return pdfPage(file, 0, px);
        if (kind == FileKind.AUDIO) return audioArt(f.abs, px);
        if (Build.VERSION.SDK_INT >= 29) {
            Size s = new Size(px, px);
            return kind == FileKind.VIDEO ? ThumbnailUtils.createVideoThumbnail(file, s, null)
                                         : ThumbnailUtils.createImageThumbnail(file, s, null);
        }
        if (kind == FileKind.VIDEO) {
            Bitmap b = ThumbnailUtils.createVideoThumbnail(f.abs, px > 256 ? MediaStore.Images.Thumbnails.FULL_SCREEN_KIND
                                                                         : MediaStore.Images.Thumbnails.MINI_KIND);
            return b;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.abs, o);
        int sample = 1;
        while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= px) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(f.abs, o);
        if (b == null) return null;
        int rot = 0;
        try {
            int ori = new ExifInterface(f.abs).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            rot = ori == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : ori == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                : ori == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        } catch (Throwable ignored) {}
        if (rot != 0) {
            Matrix m = new Matrix(); m.postRotate(rot);
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            b = r;
        }
        return b;
    }

    /** First (or n-th) page of a PDF, rendered on white, px wide. */
    static Bitmap pdfPage(File file, int page, int px) throws Exception {
        android.os.ParcelFileDescriptor pfd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
        android.graphics.pdf.PdfRenderer r = null;
        try {
            r = new android.graphics.pdf.PdfRenderer(pfd);
            if (r.getPageCount() <= page) return null;
            android.graphics.pdf.PdfRenderer.Page p = r.openPage(page);
            try {
                int w = Math.max(1, px), h = Math.max(1, (int) ((long) px * p.getHeight() / Math.max(1, p.getWidth())));
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                b.eraseColor(0xFFFFFFFF);
                p.render(b, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return b;
            } finally { p.close(); }
        } finally {
            if (r != null) r.close(); else pfd.close();
        }
    }

    /** Album art embedded in an audio file, if any. */
    static Bitmap audioArt(String path, int px) {
        android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
        try {
            mmr.setDataSource(path);
            byte[] art = mmr.getEmbeddedPicture();
            if (art == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(art, 0, art.length, o);
            int sample = 1;
            while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= px) sample *= 2;
            o = new BitmapFactory.Options(); o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(art, 0, art.length, o);
        } catch (Throwable t) {
            return null;
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
    }
}
