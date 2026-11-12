package com.storageplanner.v2test;

import com.storageplanner.v2.discovery.FileIds;

public final class FileIdsTest {
    public static int run(String[] args) {
        T.section("FileIds");
        T.eq("golden 1", "e555bafe3de22350be0aed7b0ddeed74", FileIds.of("primary", "DCIM/Camera/IMG_1.jpg", ""));
        T.eq("golden 2", "3c9b8dfb7dae38927f8db7a5f42f92ec", FileIds.of("primary", "DCIM/Camera/IMG_1.jpg", "ino:12345"));
        T.eq("golden 3", "4ceb62ed957ae351e46058e691e3503a", FileIds.of("uuid:1A2B-3C4D", "Download/é.pdf", ""));
        T.eq("golden 4", "b5ab5da22a47e601a5ad59746c3408bf", FileIds.of("primary", "", ""));
        T.eq("golden 5", "9b1fb29a38bf631f6ba81eb1e4e588cd", FileIds.of("primary", "a/bc", ""));
        String id = FileIds.of("primary", "x/y.jpg", "ino:7");
        T.eq("length 32", 32, id.length());
        T.check("lower-case hex", id.matches("[0-9a-f]{32}"));
        T.check("volume changes id", !id.equals(FileIds.of("primary@10", "x/y.jpg", "ino:7")));
        T.check("path changes id", !id.equals(FileIds.of("primary", "x/z.jpg", "ino:7")));
        T.check("token changes id", !id.equals(FileIds.of("primary", "x/y.jpg", "ino:8")));
        T.check("separator ambiguity", !FileIds.of("a", "bc", "").equals(FileIds.of("ab", "c", "")));
        return T.finish("FileIdsTest");
    }
}
