package com.storageplanner.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import com.storageplanner.core.FileItem;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Read-only, NOT exported. Lets another app (gallery, player, PDF viewer, file manager) open one file
 * the user picked, through a temporary per-URI read grant. Only regular files inside shared storage
 * are served; there is no write, insert, update or delete.
 * URI form: content://com.storageplanner.files/f/<path relative to shared storage>
 */
public final class FileAccessProvider extends ContentProvider {
    public static final String AUTHORITY = "com.storageplanner.files";

    public static Uri uriFor(FileItem f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("f").appendEncodedPath(encode(f.rel)).build();
    }

    private static String encode(String rel) {
        StringBuilder sb = new StringBuilder();
        for (String part : rel.split("/")) { if (sb.length() > 0) sb.append('/'); sb.append(Uri.encode(part)); }
        return sb.toString();
    }

    @Override public boolean onCreate() { return true; }

    private File resolve(Uri uri) throws FileNotFoundException {
        java.util.List<String> seg = uri.getPathSegments();
        if (seg.size() < 2 || !"f".equals(seg.get(0))) throw new FileNotFoundException("bad uri");
        StringBuilder rel = new StringBuilder();
        for (int i = 1; i < seg.size(); i++) { if (i > 1) rel.append('/'); rel.append(seg.get(i)); }
        try {
            File root = Environment.getExternalStorageDirectory().getCanonicalFile();
            File f = new File(root, rel.toString()).getCanonicalFile();
            if (!f.getPath().startsWith(root.getPath() + "/") || !f.isFile()) throw new FileNotFoundException("not a shared file");
            return f;
        } catch (IOException e) {
            throw new FileNotFoundException("not a shared file");
        }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode == null || !mode.equals("r")) throw new SecurityException("read-only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        File f;
        try { f = resolve(uri); } catch (FileNotFoundException e) { return null; }
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null) return null;
        String e = FileKind.ext(name);
        if ("opus".equals(e)) return "audio/ogg";
        if ("apk".equals(e)) return "application/vnd.android.package-archive";
        String m = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(e);
        return m != null ? m : "application/octet-stream";
    }

    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException("read-only"); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException("read-only"); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException("read-only"); }
}
