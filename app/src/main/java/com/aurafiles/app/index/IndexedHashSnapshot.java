package com.aurafiles.app.index;

/** Lightweight projection used during rescans to preserve hashes without N+1 queries. */
public class IndexedHashSnapshot {
    public String uri;
    public long size;
    public long modifiedAt;
    public String sha256;
    public String quickHash;
}
