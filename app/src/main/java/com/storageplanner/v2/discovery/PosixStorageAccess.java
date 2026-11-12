package com.storageplanner.v2.discovery;

import com.storageplanner.v2.device.*;
import com.storageplanner.v2.model.AccessGrant;
import com.storageplanner.v2.model.Tri;
import com.storageplanner.v2.model.VolumeKind;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure java.nio.file StorageAccess (JVM, and Android Mode B/D). */
public final class PosixStorageAccess implements StorageAccess {
    public static final class Root {
        public String volumeId; public VolumeKind kind = VolumeKind.DIRECTORY; public String label, path;
        public Tri fileKeyStable = Tri.UNKNOWN, caseInsensitive = Tri.FALSE;
        public List<String> restrictedPrefixes;
    }

    private static final Pattern KEY = Pattern.compile("dev=([0-9a-fA-F]+),ino=(\\d+)");
    private static final Pattern INO = Pattern.compile("ino=(\\d+)");

    private final Map<String, Root> roots = new LinkedHashMap<String, Root>();
    private final List<AccessGrant> grants;

    public PosixStorageAccess(List<Root> roots, List<AccessGrant> grants) {
        for (Root r : roots) this.roots.put(r.volumeId, r);
        this.grants = grants == null ? new ArrayList<AccessGrant>() : grants;
    }

    /** toRealPath() string, or the normalized absolute path if that fails. */
    public static String canonical(String path) {
        try { return Paths.get(path).toRealPath().toString(); }
        catch (IOException e) { return Paths.get(path).toAbsolutePath().normalize().toString(); }
        catch (RuntimeException e) { return path; }
    }

    private Path rootPath(String volumeId) {
        Root r = roots.get(volumeId);
        return r == null ? null : Paths.get(r.path).toAbsolutePath().normalize();
    }

    /** Resolves root/raw; null if outside the root or not resolvable. */
    private Path resolve(String volumeId, String raw) {
        Path root = rootPath(volumeId);
        if (root == null) return null;
        try {
            Path p = raw.isEmpty() ? root : root.resolve(raw);
            if (!p.normalize().startsWith(root)) return null;
            return p;
        } catch (InvalidPathException e) { return null; }
    }

    public List<VolumeInfo> volumes() {
        List<VolumeInfo> l = new ArrayList<VolumeInfo>();
        for (Root r : roots.values()) {
            VolumeInfo v = new VolumeInfo();
            v.volumeId = r.volumeId; v.kind = r.kind; v.label = r.label; v.root = r.path;
            v.fileKeyStable = r.fileKeyStable; v.caseInsensitive = r.caseInsensitive;
            v.restrictedPrefixes = r.restrictedPrefixes == null ? new ArrayList<String>() : new ArrayList<String>(r.restrictedPrefixes);
            Path p = Paths.get(r.path);
            v.mounted = Files.isDirectory(p);
            if (r.kind != VolumeKind.DIRECTORY && v.mounted) {
                try { FileStore fs = Files.getFileStore(p); v.totalBytes = fs.getTotalSpace(); v.freeBytes = fs.getUsableSpace(); }
                catch (IOException e) { v.totalBytes = -1; v.freeBytes = -1; }
            }
            l.add(v);
        }
        return l;
    }

    public List<AccessGrant> grants() {
        List<AccessGrant> l = new ArrayList<AccessGrant>();
        for (AccessGrant g : grants) l.add(g.copy());
        return l;
    }

    public DirListing list(String volumeId, String dirPath) {
        Path p = resolve(volumeId, dirPath);
        if (p == null) return DirListing.failed(IoStatus.PERMISSION_DENIED, "outside root");
        DirListing l = new DirListing();
        l.status = IoStatus.OK;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
            for (Path e : ds) l.entries.add(entry(e));
        } catch (DirectoryIteratorException e) {
            l.status = IoStatus.PARTIAL_IO_ERROR; l.error = String.valueOf(e.getCause());
        } catch (IOException e) {
            return DirListing.failed(IoStatus.of(e), e.toString());
        } catch (RuntimeException e) {
            return DirListing.failed(IoStatus.IO_ERROR, e.toString());
        }
        return l;
    }

    private static DirEntry entry(Path e) {
        DirEntry d = new DirEntry();
        d.name = e.getFileName().toString();
        try {
            BasicFileAttributes a = Files.readAttributes(e, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            d.kind = kindOf(a);
            d.size = a.size();
            d.mtime = a.lastModifiedTime().toMillis();
            d.inode = inode(a.fileKey());
            d.physicalKey = physicalKey(a.fileKey());
        } catch (IOException x) {
            d.kind = EntryKind.UNKNOWN; d.size = -1;
        }
        return d;
    }

    static EntryKind kindOf(BasicFileAttributes a) {
        if (a.isSymbolicLink()) return EntryKind.SYMLINK;
        if (a.isDirectory()) return EntryKind.DIRECTORY;
        if (a.isRegularFile()) return EntryKind.FILE;
        if (a.isOther()) return EntryKind.OTHER;
        return EntryKind.UNKNOWN;
    }

    static long inode(Object key) {
        if (key == null) return -1;
        Matcher m = INO.matcher(key.toString());
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    static String physicalKey(Object key) {
        if (key == null) return null;
        Matcher m = KEY.matcher(key.toString());
        return m.find() ? m.group(1) + ":" + m.group(2) : null;
    }

    public FileStat stat(String volumeId, String path) {
        Path p = resolve(volumeId, path);
        if (p == null) return FileStat.failed(IoStatus.PERMISSION_DENIED, "outside root");
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            FileStat s = new FileStat();
            s.status = IoStatus.OK;
            s.kind = kindOf(a);
            s.size = a.size();
            s.mtime = a.lastModifiedTime().toMillis();
            s.inode = inode(a.fileKey());
            s.physicalKey = physicalKey(a.fileKey());
            s.readable = Tri.of(Files.isReadable(p));
            s.writable = Tri.of(Files.isWritable(p));
            if (a.isSymbolicLink()) {
                try { s.linkTarget = Files.readSymbolicLink(p).toString(); } catch (IOException e) { s.linkTarget = null; }
            }
            return s;
        } catch (IOException e) {
            return FileStat.failed(IoStatus.of(e), e.toString());
        } catch (RuntimeException e) {
            return FileStat.failed(IoStatus.IO_ERROR, e.toString());
        }
    }

    public ReadableFile open(String volumeId, String path) throws IOException {
        Path p = resolve(volumeId, path);
        if (p == null) throw new AccessDeniedException(path, null, "outside root");
        final FileChannel ch = FileChannel.open(p, StandardOpenOption.READ);
        return new ReadableFile() {
            public long size() throws IOException { return ch.size(); }
            public int read(long position, byte[] buf, int off, int len) throws IOException {
                long size = ch.size();
                if (position >= size) return -1;
                int n = (int) Math.min(len, size - position);
                return ch.read(ByteBuffer.wrap(buf, off, n), position);
            }
            public void close() throws IOException { ch.close(); }
        };
    }

    public String dirIdentity(String volumeId, String dirPath) {
        Path p = resolve(volumeId, dirPath);
        if (p == null) return null;
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
            String k = physicalKey(a.fileKey());
            if (k != null) return "ino:" + k;
            if (a.fileKey() != null) return "ino:" + a.fileKey();
            return "path:" + canonical(p.toString());
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public String localPath(String volumeId, String path) {
        Path p = resolve(volumeId, path);
        return p == null ? null : p.toString();
    }

    public String resolveLink(String volumeId, String path) {
        Path p = resolve(volumeId, path);
        Path root = rootPath(volumeId);
        if (p == null || root == null) return null;
        try {
            Path real = p.toRealPath();
            Path realRoot = root.toRealPath();
            if (!real.startsWith(realRoot)) return null;
            return realRoot.relativize(real).toString().replace(java.io.File.separatorChar, '/');
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
