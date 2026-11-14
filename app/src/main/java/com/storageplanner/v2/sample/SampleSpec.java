package com.storageplanner.v2.sample;

import java.util.Locale;

/**
 * How big a sample run is (the app's "Quick v2 test" button): the newest {@code images} images and the newest {@code videos}
 * videos among the files discovery finds, and nothing else. The one place the two numbers are defined.
 */
public final class SampleSpec {
    public static final int SAMPLE_IMAGES = 1000, SAMPLE_VIDEOS = 100;
    /** What the button runs. */
    public static final SampleSpec DEFAULT = new SampleSpec(SAMPLE_IMAGES, SAMPLE_VIDEOS);

    public final int images, videos;

    public SampleSpec(int images, int videos) {
        this.images = Math.max(0, images);
        this.videos = Math.max(0, videos);
    }

    /** "Sample result: 1,000 newest photos and 100 newest videos only." (shown on every screen that shows a sample plan). */
    public String notice() {
        return "Sample result: " + count(images) + " newest photos and " + count(videos) + " newest videos only.";
    }

    private static String count(int n) { return String.format(Locale.US, "%,d", n); }
}
