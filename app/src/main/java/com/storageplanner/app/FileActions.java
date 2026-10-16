package com.storageplanner.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.widget.Toast;

import com.storageplanner.core.FileItem;

import java.util.List;

/** Hand a file to other apps (open with, share) or open its folder in a file manager. Never modifies files. */
public final class FileActions {
    private FileActions() {}

    /** "Open with": every installed app that can view this kind of file. */
    public static void openWith(Activity a, FileItem f) {
        Uri uri = FileAccessProvider.uriFor(f);
        String mime = FileKind.mime(f);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.setClipData(ClipData.newRawUri(f.name(), uri));
        List<?> handlers = a.getPackageManager().queryIntentActivities(i, 0);
        if (handlers.isEmpty() && !"*/*".equals(mime)) {           // retry with a generic type
            i.setDataAndType(uri, "*/*");
            handlers = a.getPackageManager().queryIntentActivities(i, 0);
        }
        if (handlers.isEmpty()) { toast(a, "No app on this phone can open ." + FileKind.ext(f.name()) + " files."); return; }
        try { a.startActivity(Intent.createChooser(i, "Open " + f.name() + " with")); }
        catch (Throwable t) { toast(a, "Could not open this file."); }
    }

    public static void share(Activity a, FileItem f) {
        Uri uri = FileAccessProvider.uriFor(f);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(FileKind.mime(f));
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.setClipData(ClipData.newRawUri(f.name(), uri));
        try { a.startActivity(Intent.createChooser(i, "Share " + f.name())); }
        catch (Throwable t) { toast(a, "No app can share this file."); }
    }

    /**
     * Open the folder that holds the file. Android has no single standard for this, so try the system
     * Files app (DocumentsUI), then file managers that accept "resource/folder"; finally copy the path.
     */
    public static void showInFolder(Activity a, FileItem f) {
        int slash = f.rel.lastIndexOf('/');
        String dir = slash < 0 ? "" : f.rel.substring(0, slash);
        Uri doc = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + dir);
        Intent i1 = new Intent(Intent.ACTION_VIEW);
        i1.setDataAndType(doc, DocumentsContract.Document.MIME_TYPE_DIR);
        i1.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (tryStart(a, i1)) return;
        Intent i2 = new Intent(Intent.ACTION_VIEW);
        i2.setDataAndType(doc, "resource/folder");
        if (tryStart(a, i2)) return;
        try {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Folder", "/" + dir));
            toast(a, "No file manager could open the folder. Its path was copied: /" + dir);
        } catch (Throwable t) {
            toast(a, "Folder: /" + dir);
        }
    }

    private static boolean tryStart(Activity a, Intent i) {
        try {
            if (a.getPackageManager().queryIntentActivities(i, 0).isEmpty()) return false;
            a.startActivity(i);
            return true;
        } catch (Throwable t) { return false; }
    }

    private static void toast(Activity a, String s) { Toast.makeText(a, s, Toast.LENGTH_LONG).show(); }
}
