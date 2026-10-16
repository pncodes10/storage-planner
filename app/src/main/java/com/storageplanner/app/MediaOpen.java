package com.storageplanner.app;

import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.Settings;
import android.widget.Toast;

import com.storageplanner.core.Categories;
import com.storageplanner.core.FileItem;

/** Opens a file in the phone's own viewer (gallery, video player, PDF viewer) via MediaStore. Read-only. */
public final class MediaOpen {
    private MediaOpen() {}

    @SuppressWarnings("deprecation")
    public static void open(Activity a, FileItem f) {
        Uri table;
        String mime;
        if (Categories.isImage(f)) { table = MediaStore.Images.Media.EXTERNAL_CONTENT_URI; mime = "image/*"; }
        else if (Categories.isVideo(f)) { table = MediaStore.Video.Media.EXTERNAL_CONTENT_URI; mime = "video/*"; }
        else if ("mp3".equals(f.type) || "flac".equals(f.type) || "ogg".equals(f.type) || "m4a".equals(f.type)) {
            table = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; mime = "audio/*";
        } else { table = MediaStore.Files.getContentUri("external"); mime = "pdf".equals(f.type) ? "application/pdf" : "*/*"; }
        Cursor c = null;
        try {
            c = a.getContentResolver().query(table, new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DATA + "=?", new String[]{f.abs}, null);
            if (c != null && c.moveToFirst()) {
                Uri uri = ContentUris.withAppendedId(table, c.getLong(0));
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, mime);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                a.startActivity(Intent.createChooser(i, "Open with"));
                return;
            }
            Toast.makeText(a, "This file is not in the media library, so it can't be opened from here. Path: " + f.rel, Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(a, "No app can open this file.", Toast.LENGTH_LONG).show();
        } finally {
            if (c != null) c.close();
        }
    }

    public static void appSettings(Activity a, String pkg) {
        try {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
        } catch (Throwable ignored) {}
    }
}
