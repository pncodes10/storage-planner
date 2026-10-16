package com.storageplanner.test;
import com.storageplanner.core.Classifier;
import java.util.*;
/** Real-phone path conventions -> expected area. */
public final class ClassifierTest {
    public static void main(String[] a) {
        Set<String> inst = new HashSet<String>(Arrays.asList("com.whatsapp", "org.telegram.messenger", "com.pubg.imobile", "com.spotify.music"));
        String[][] cases = {
            {"DCIM/Camera/IMG_20260101_101010.jpg", "camera"},
            {"DCIM/Camera/.trashed-1791000000-IMG_1.jpg", "trash"},
            {"DCIM/Screenshots/Screenshot_20260101.png", "screenshot"},
            {"Pictures/Screenshots/Screenshot_20260101.png", "screenshot"},
            {"DCIM/.thumbnails/1234.jpg", "thumbnails"},
            {"Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-20260101-WA0001.jpg", "wa_image"},
            {"Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/IMG-20260101-WA0002.jpg", "wa_sent"},
            {"Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Private/IMG-1.jpg", "wa_image"},
            {"Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/VID-1.mp4", "wa_video"},
            {"Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/202601/PTT-1.opus", "wa_voice"},
            {"Android/media/com.whatsapp/WhatsApp/Media/.Statuses/abc.jpg", "wa_status"},
            {"Android/media/com.whatsapp/WhatsApp/Databases/msgstore-2026-09-30.1.db.crypt14", "wa_old_backup"},
            {"Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt14", "wa_other"},
            {"WhatsApp/Media/WhatsApp Images/IMG-1.jpg", "wa_image"},
            {"Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images/IMG-1.jpg", "wa_image"},
            {"Android/media/org.telegram.messenger/Telegram/Telegram Video/1.mp4", "telegram_media"},
            {"Android/media/com.zhiliaoapp.musically/cache.bin", "residue"},
            {"Android/obb/com.pubg.imobile/main.obb", "app_obb"},
            {"Android/obb/com.old.game/main.obb", "residue"},
            {"Download/statement (1).pdf", "download"},
            {"Download/file.crdownload", "temp"},
            {"Music/song.mp3", "music"},
            {"Movies/clip.mp4", "movies"},
            {"Documents/a.docx", "document"},
            {".pending-1791000000-IMG.jpg", "temp"},
        };
        int bad = 0;
        for (String[] c : cases) {
            String got = Classifier.classify(c[0], inst)[0];
            if (!got.equals(c[1])) { bad++; System.out.println("FAIL " + c[0] + " -> " + got + " (want " + c[1] + ")"); }
        }
        System.out.println((cases.length - bad) + "/" + cases.length + " classifier cases pass");
    }
}
