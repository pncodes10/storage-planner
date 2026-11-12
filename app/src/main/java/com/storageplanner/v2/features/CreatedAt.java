package com.storageplanner.v2.features;

import com.storageplanner.v2.model.*;

/** createdAt choice (PHASE_2 3.2 step 8). modifiedAt is never used. */
public final class CreatedAt {
    private CreatedAt() {}
    public static long[] NONE() { return new long[]{Unknown.TIME, TimeSource.NONE.ordinal()}; }

    /** Returns {time, TimeSource ordinal}. */
    public static long[] choose(ExifInfo exif, VideoInfo video, PdfInfo pdf, NameDate nameDate, long birthTime, long now) {
        if (exif != null && Dates.plausible(exif.dateTimeOriginal, now)) return new long[]{exif.dateTimeOriginal, TimeSource.EXIF.ordinal()};
        if (video != null && Dates.plausible(video.createdAt, now)) return new long[]{video.createdAt, TimeSource.CONTAINER.ordinal()};
        if (pdf != null && Dates.plausible(pdf.createdAt, now)) return new long[]{pdf.createdAt, TimeSource.CONTAINER.ordinal()};
        if (nameDate != null && Dates.plausible(nameDate.at, now)) return new long[]{nameDate.at, TimeSource.FILENAME.ordinal()};
        if (Dates.plausible(birthTime, now)) return new long[]{birthTime, TimeSource.FS_BIRTH.ordinal()};
        return NONE();
    }
}
