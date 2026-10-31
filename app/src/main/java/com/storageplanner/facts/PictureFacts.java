package com.storageplanner.facts;

import com.storageplanner.core.FileItem;
import com.storageplanner.tools.category.PhotoCategory;

/**
 * Everything the tools found about one picture, plus the file details an algorithm may use. Algorithms read only
 * this (never pixels, files or the device), so every version can be run and compared on the same pictures.
 */
public final class PictureFacts {
    // the file
    public String path;            // relative to the storage root, '/' separated
    public String area;            // storage area (camera, screenshot, wa_image, download, ...)
    public long size;              // bytes
    public long savedAt;           // file date, epoch ms
    public int width, height;
    public boolean screenshot;     // taken with the screenshot key
    public boolean camera;         // from the camera folder
    public boolean chatImage;      // received in a chat app (WhatsApp, Telegram)

    // what the tools found
    public String category;        // tools/category: PhotoCategory id; null = not sorted
    public String text;            // tools/ocr: words read, lower case; null if none (never kept for ID documents)
    public boolean textPending;    // worth reading, but not read yet
    public String code;            // tools/codes: what a QR code or barcode is for; null if none
    public long dateInPicture;     // tools/ocr: a date read in the text (trip, receipt), 0 if none
    public int faces = -1;         // tools/faces: faces found; -1 = not checked

    public static PictureFacts of(FileItem f) {
        PictureFacts p = new PictureFacts();
        p.path = f.rel; p.area = f.area == null ? "" : f.area; p.size = f.size; p.savedAt = f.mtime;
        p.width = f.width; p.height = f.height;
        p.screenshot = PhotoCategory.isScreenshot(f);
        p.camera = PhotoCategory.isCamera(f);
        p.chatImage = p.area.startsWith("wa_") || p.area.equals("telegram_media");
        p.category = f.category; p.text = f.text; p.textPending = f.textPending; p.code = f.code;
        p.dateInPicture = f.catDate; p.faces = f.faces;
        return p;
    }

    public double ageDays(long now) { return (now - savedAt) / 86400000.0; }
}
