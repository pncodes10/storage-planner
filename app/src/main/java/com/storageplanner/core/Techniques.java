package com.storageplanner.core;

import java.util.HashMap;
import java.util.Map;

/** Plain-language explanation and "how to act" for every recommendation. */
public final class Techniques {
    private Techniques() {}

    public static final String CACHE = "Clear app caches";
    public static final String JUNK = "Junk: thumbnails, temp, leftovers";
    public static final String TRASH = "Empty the trash";
    public static final String DUPLICATES = "Exact duplicates";
    public static final String SENT = "WhatsApp Sent copies";
    public static final String INSTALLED_APK = "Installers already installed";
    public static final String WA_BACKUP = "Old WhatsApp backups";
    public static final String TELEGRAM = "Telegram downloaded media";
    public static final String ARCHIVE = "Archive idle apps";
    public static final String SCREENSHOTS = "Lossless WebP screenshots";
    public static final String PHOTOS = "Re-encode photos (HEIC/WebP)";
    public static final String CHAT_IMAGES = "Re-encode chat images";
    public static final String VIDEOS = "Re-encode videos (HEVC)";
    public static final String MUSIC = "Music to 128 kbps Opus/AAC";
    public static final String UNINSTALL_GAMES = "Uninstall unused games";
    public static final String UNINSTALL_APPS = "Uninstall unused apps";
    public static final String BURSTS = "Burst near-duplicates";
    public static final String BLURRY = "Possibly blurry photos";
    public static final String OLD_DOWNLOADS = "Old downloads and installers";
    public static final String OTP_SHOTS = "Screenshots of one-time codes";
    public static final String OLD_PAYMENTS = "Old payment screenshots";
    public static final String EXPIRED_TICKETS = "Tickets for past trips and shows";
    public static final String GREETINGS = "Greeting images from chats";
    public static final String ACCIDENTAL = "Accidental shots";
    public static final String EXPIRED_OFFERS = "Expired coupons and offers";
    public static final String ORDER_UPDATES = "Old delivery, ride and food order updates";
    public static final String SHORT_LIVED = "Short-lived screenshots: maps, weather, tokens";
    public static final String SHOPPING = "Old shopping screenshots";
    public static final String OLD_RECEIPTS = "Small receipts past any return window";
    public static final String FORWARDS = "Memes and forwards from chats";
    public static final String OLD_QR_CODES = "QR codes you have already used";
    /** Deletions the user picked while browsing pictures (recorded as Review.CHOSEN + PICKED). */
    public static final String PICKED = "Pictures you picked to delete";

    public static final class Info {
        public final String what, how;
        Info(String what, String how) { this.what = what; this.how = how; }
    }

    private static final Map<String, Info> INFO = new HashMap<String, Info>();
    static {
        put(CACHE, "Temporary files that apps rebuild automatically. Clearing them loses nothing (you stay signed in).",
            "Open each app below (tap it) > Storage > Clear cache. Android does not let one app clear another app's cache.");
        put(JUNK, "Gallery thumbnail caches, unfinished downloads, expired WhatsApp statuses and files left behind by apps you uninstalled.",
            "Delete them with your file manager. Nothing you use is lost.");
        put(TRASH, "Photos and videos you already deleted. Android removes them by itself after 30 days.",
            "Gallery > Trash (or Recycle bin) > Empty.");
        put(DUPLICATES, "Byte-for-byte identical copies of a file that is also somewhere else on the phone. One copy is always kept (shown in each item).",
            "Delete the listed copies in your file manager. Open an item to see which copy is kept.");
        put(SENT, "Compressed copies WhatsApp made of photos you sent, while the original is still in your camera roll.",
            "Delete them from WhatsApp > Media > Sent, or with your file manager.");
        put(INSTALLED_APK, "Installer files (.apk) for apps that are already installed.",
            "Delete them from Downloads.");
        put(WA_BACKUP, "Older local chat backups. WhatsApp keeps the newest one; your Google Drive backup is separate.",
            "Delete them from WhatsApp/Databases with your file manager.");
        put(TELEGRAM, "Media Telegram downloaded for you. It stays available in Telegram's cloud and downloads again when opened.",
            "Telegram > Settings > Data and storage > Storage usage > Clear cache.");
        put(ARCHIVE, "Apps you have not opened in 60+ days. Archiving removes the app but keeps your data and its icon; tap the icon to get it back.",
            "Tap the app > Archive (Android 15+).");
        put(SCREENSHOTS, "The same pixels in a smaller file format (lossless WebP).",
            "Optional: convert with an image converter app.");
        put(PHOTOS, "Photos re-encoded to a modern format at a quality that looks identical (checked with the same SSIM/PSNR test as the study).",
            "Use Google Photos 'Storage saver' backup or a HEIC/WebP converter. Keep the originals until you have looked at the results.");
        put(CHAT_IMAGES, "Chat images re-encoded to a modern format at a quality that looks identical.",
            "Optional: convert with an image converter app.");
        put(VIDEOS, "Videos recorded in H.264 at a high bitrate. HEVC (H.265) keeps the same look at roughly half the size.",
            "Re-encode with a video converter app, or on a computer (for example HandBrake, H.265 preset), then replace the originals.");
        put(MUSIC, "Music files above 256 kbps. At 128 kbps Opus/AAC most listeners hear no difference.",
            "Re-download at standard quality from your music service, or convert the files.");
        put(UNINSTALL_GAMES, "Games you have not opened in 120+ days. Uninstalling removes the game and its downloaded data.",
            "Make sure progress is synced to your account first, then tap the game > Uninstall.");
        put(UNINSTALL_APPS, "Apps you have not opened in 120+ days. Uninstalling removes the app and its data (archiving needs Android 15).",
            "Tap the app > Uninstall. You can reinstall it later from the Play Store.");
        put(PICKED, "Pictures you selected for deletion while browsing your picture categories.",
            "Delete them in your gallery or file manager.");
        put(OTP_SHOTS, "Screenshots of one-time passwords and login codes. A code stops working within minutes.",
            "Delete them in your gallery (Screenshots).");
        put(OLD_PAYMENTS, "Payment confirmations that are no longer needed: small payments (under ₹2,000) after about 4 months, larger ones after about 22 months, failed ones after about 3 weeks. "
            + "Payments for rent, fees, EMIs, insurance or tax, payments of ₹10,000 or more, pending ones, and any whose amount could not be read are never suggested. Your bank statement and the payment app keep the record.",
            "Check each one, then delete it in your gallery.");
        put(EXPIRED_TICKETS, "Train, flight, bus and movie tickets whose date passed about two weeks ago. Cancelled tickets are kept until the refund has had time to arrive.",
            "Check each one (some are needed for refunds or expense claims), then delete it.");
        put(GREETINGS, "Good-morning, festival and other greeting images received in chats more than about 11 days ago.",
            "Delete them in WhatsApp (Settings > Storage and data > Manage storage) or your gallery.");
        put(ACCIDENTAL, "Very dark, covered or badly shaken shots, usually taken by accident.",
            "Look at each one; delete it in your gallery if nothing is in it.");
        put(EXPIRED_OFFERS, "Screenshots of coupons, promo codes, vouchers and offers whose validity date has passed (or, with no date, saved more than about 11 weeks ago), "
            + "and sale ads (\"Buy 1 Get 1\", \"Sale is live\") received or saved more than about two weeks ago.",
            "Check each one, then delete it in your gallery.");
        put(ORDER_UPDATES, "Order tracking, delivery, cab and food order screens from weeks ago. The app that sent them keeps the history.",
            "Check each one (a return or complaint may still be open), then delete it.");
        put(SHORT_LIVED, "Directions and weather (after a week or two), parking and queue slips, and appointments and reservations whose day passed about 10 days ago.",
            "Delete them in your gallery (Screenshots).");
        put(SHOPPING, "Product pages and offers you took screenshots of more than about 3 months ago. Prices and stock have changed since.",
            "Delete them in your gallery (Screenshots).");
        put(OLD_RECEIPTS, "Receipts for small purchases (under ₹1,000) older than about 10 months, with no warranty on them. Large purchases and anything with a warranty are always kept.",
            "Check each one, then delete it.");
        put(OLD_QR_CODES, "Pictures of QR codes and barcodes (payment, links, anything else) saved or received about 3 weeks ago or more. "
            + "A code is used once; scan it again from the shop or the sender if you need it. Wi-Fi codes are kept, because they hold the password.",
            "Check each one, then delete it in your gallery.");
        put(FORWARDS, "Memes, jokes and forwarded posts received in chats more than about 7 weeks ago.",
            "Delete them in WhatsApp (Settings > Storage and data > Manage storage) or your gallery.");
        put(BURSTS, "Photos taken within minutes of each other that look almost identical. The better one is kept: eyes open, faces visible, sharper, better lit.",
            "Look at each one; delete the extras in your gallery if you agree.");
        put(BLURRY, "Photos much softer than the rest of your photos. Some may be intentional (motion, low light, close-ups).",
            "Look at each one before deleting.");
        put(OLD_DOWNLOADS, "Downloads you have not touched for 6+ months, and installers for apps that are no longer installed.",
            "Check each one; delete what you no longer need.");
    }

    private static void put(String t, String what, String how) { INFO.put(t, new Info(what, how)); }

    public static Info info(String technique) {
        Info i = INFO.get(technique);
        return i != null ? i : new Info("", "");
    }
}
