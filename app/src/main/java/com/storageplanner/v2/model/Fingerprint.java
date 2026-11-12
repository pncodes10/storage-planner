package com.storageplanner.v2.model;

/** Immutable (size, mtime, inode) of a file at one point in time. */
public final class Fingerprint {
    public final long size, mtime, inode;
    public Fingerprint(long size, long mtime, long inode) { this.size = size; this.mtime = mtime; this.inode = inode; }
    /** Sizes equal, mtimes equal, and inodes equal when both known. */
    public boolean sameAs(Fingerprint o) {
        if (o == null) return false;
        if (size != o.size || mtime != o.mtime) return false;
        return inode < 0 || o.inode < 0 || inode == o.inode;
    }
    @Override public boolean equals(Object x) {
        if (!(x instanceof Fingerprint)) return false;
        Fingerprint o = (Fingerprint) x; return size == o.size && mtime == o.mtime && inode == o.inode;
    }
    @Override public int hashCode() { return (int) (size * 31 + mtime * 17 + inode); }
    @Override public String toString() { return "fp(" + size + "," + mtime + "," + inode + ")"; }
}
