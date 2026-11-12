package com.storageplanner.v2test;

import com.storageplanner.v2.device.BlobStore;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** BlobStore over a directory: atomic replace via temp file + fsync + ATOMIC_MOVE. */
public class DirBlobStore implements BlobStore {
    private final Path dir;
    private long tmpSeq;
    public DirBlobStore(Path dir) throws IOException { this.dir = dir; Files.createDirectories(dir); }
    public Path dir() { return dir; }

    public InputStream read(String name) throws IOException {
        Path p = dir.resolve(name);
        return Files.exists(p) ? new BufferedInputStream(Files.newInputStream(p)) : null;
    }
    public OutputStream append(String name) throws IOException { return new FileOutputStream(dir.resolve(name).toFile(), true); }
    public void sync(String name) throws IOException {
        try (FileChannel ch = FileChannel.open(dir.resolve(name), StandardOpenOption.WRITE)) { ch.force(true); }
    }
    public void truncate(String name) throws IOException {
        try (FileChannel ch = FileChannel.open(dir.resolve(name), StandardOpenOption.WRITE, StandardOpenOption.CREATE)) { ch.truncate(0); ch.force(true); }
    }
    public long length(String name) { Path p = dir.resolve(name); try { return Files.exists(p) ? Files.size(p) : -1; } catch (IOException e) { return -1; } }

    public RandomRead openRead(String name) throws IOException {
        Path p = dir.resolve(name);
        if (!Files.exists(p)) return null;
        final FileChannel ch = FileChannel.open(p, StandardOpenOption.READ);
        return new RandomRead() {
            public int read(long pos, byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                return ch.read(java.nio.ByteBuffer.wrap(b, off, len), pos);
            }
            public long length() throws IOException { return ch.size(); }
            public void close() throws IOException { ch.close(); }
        };
    }

    public AtomicWrite replace(final String name) throws IOException {
        final Path tmp = dir.resolve(name + ".tmp-" + (++tmpSeq));
        final FileOutputStream fos = new FileOutputStream(tmp.toFile());
        final OutputStream buffered = new BufferedOutputStream(fos, 1 << 16);
        return new AtomicWrite() {
            boolean done;
            public OutputStream stream() { return buffered; }
            public void commit() throws IOException {
                buffered.flush();
                fos.getChannel().force(true);
                buffered.close();
                Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                done = true;
            }
            public void abort() {
                if (done) return;
                try { buffered.close(); } catch (IOException ignored) { }
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            }
        };
    }
}
