package com.storageplanner.v2.sample;

import com.storageplanner.v2.model.FileRecord;
import com.storageplanner.v2.model.Presence;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Picks the sample: the {@code images} newest images and the {@code videos} newest videos among the PRESENT files offered
 * (most recently modified first; ties broken by path, then volume id, then file id, so the choice is deterministic). The kind
 * is decided by extension (the only kind known at discovery time). Everything else (documents, audio, archives, apps, ...) is
 * never selected. With fewer candidates than asked for, all of them are taken. Bounded memory: one heap per kind.
 */
public final class SampleSelector {
    static final Set<String> IMAGE_EXT = new HashSet<String>(Arrays.asList("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp"));
    static final Set<String> VIDEO_EXT = new HashSet<String>(Arrays.asList("mp4", "mov", "3gp", "mkv", "webm", "avi", "m4v"));

    /** 1 = image, 2 = video, 0 = neither. */
    public static int kindOf(String extension) {
        if (extension == null) return 0;
        String e = extension.toLowerCase(Locale.ROOT);
        if (IMAGE_EXT.contains(e)) return 1;
        if (VIDEO_EXT.contains(e)) return 2;
        return 0;
    }

    private static final class Cand {
        final String fileId, volumeId, path; final long mtime;
        Cand(FileRecord f) { fileId = f.fileId; volumeId = f.volumeId == null ? "" : f.volumeId; path = f.path == null ? "" : f.path; mtime = f.modifiedAt; }
    }

    /** Order of preference: newer first; then smaller path, volume id, file id. */
    private static int better(Cand a, Cand b) {
        if (a.mtime != b.mtime) return a.mtime > b.mtime ? -1 : 1;
        int c = a.path.compareTo(b.path);
        if (c != 0) return c;
        c = a.volumeId.compareTo(b.volumeId);
        if (c != 0) return c;
        return a.fileId.compareTo(b.fileId);
    }

    /** Min-heap on preference: the least preferred kept candidate is on top. */
    private static final Comparator<Cand> WORST_FIRST = new Comparator<Cand>() {
        public int compare(Cand a, Cand b) { return better(b, a); }
    };

    private final int imageCap, videoCap;
    private final PriorityQueue<Cand> images = new PriorityQueue<Cand>(16, WORST_FIRST), videos = new PriorityQueue<Cand>(16, WORST_FIRST);

    public SampleSelector(SampleSpec spec) { imageCap = spec.images; videoCap = spec.videos; }

    /** Offers one record; only PRESENT files with an image or video extension are considered. */
    public void offer(FileRecord f) {
        if (f == null || f.fileId == null || f.presence != Presence.PRESENT) return;
        int k = kindOf(f.extension);
        if (k == 0) return;
        PriorityQueue<Cand> q = k == 1 ? images : videos;
        int cap = k == 1 ? imageCap : videoCap;
        if (cap <= 0) return;
        Cand c = new Cand(f);
        if (q.size() < cap) q.add(c);
        else if (better(c, q.peek()) < 0) { q.poll(); q.add(c); }
    }

    public int selectedImages() { return images.size(); }
    public int selectedVideos() { return videos.size(); }

    /** File ids of the selected files. */
    public Set<String> selected() {
        Set<String> s = new HashSet<String>();
        for (Cand c : images) s.add(c.fileId);
        for (Cand c : videos) s.add(c.fileId);
        return s;
    }

    /** Convenience: select from a whole collection. */
    public static Set<String> select(Iterable<FileRecord> files, SampleSpec spec) {
        SampleSelector s = new SampleSelector(spec);
        for (FileRecord f : files) s.offer(f);
        return s.selected();
    }
}
