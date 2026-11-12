package com.storageplanner.v2test;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Random;

/** A temporary directory tree for tests. */
public final class TempTree implements AutoCloseable {
    private final Path root;
    private TempTree(Path root) { this.root = root; }

    public static TempTree create() throws IOException {
        Path p = Paths.get(System.getProperty("java.io.tmpdir"), "v2t-" + System.nanoTime());
        Files.createDirectories(p);
        return new TempTree(p);
    }

    public Path root() { return root; }
    public Path path(String rel) { return rel.isEmpty() ? root : root.resolve(rel); }

    public Path file(String rel, byte[] data) throws IOException {
        Path p = path(rel);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.write(p, data);
        return p;
    }
    public Path file(String rel, int size, long seed) throws IOException {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return file(rel, b);
    }
    public Path dir(String rel) throws IOException { return Files.createDirectories(path(rel)); }
    public Path symlink(String rel, String target) throws IOException {
        Path p = path(rel);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        return Files.createSymbolicLink(p, Paths.get(target));
    }
    public void mtime(String rel, long ms) throws IOException { Files.setLastModifiedTime(path(rel), FileTime.fromMillis(ms)); }
    public long mtime(String rel) throws IOException { return Files.getLastModifiedTime(path(rel), LinkOption.NOFOLLOW_LINKS).toMillis(); }
    public void append(String rel, byte[] data) throws IOException { Files.write(path(rel), data, StandardOpenOption.APPEND); }
    /** temp + ATOMIC_MOVE → new inode at the same path. */
    public void replaceAtomically(String rel, byte[] data) throws IOException {
        Path p = path(rel);
        Path tmp = p.resolveSibling(p.getFileName() + ".rep-" + System.nanoTime());
        Files.write(tmp, data);
        Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    public void delete(String rel) throws IOException { deleteTree(path(rel)); }
    public void rename(String from, String to) throws IOException {
        Path t = path(to);
        if (t.getParent() != null) Files.createDirectories(t.getParent());
        Files.move(path(from), t);
    }

    public void close() throws IOException { deleteTree(root); }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException { Files.delete(f); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException { Files.delete(d); return FileVisitResult.CONTINUE; }
        });
    }
}
