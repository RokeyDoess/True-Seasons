package dev.trueseasons.migration;

public record ChunkPos(int x, int z) {
    public long key() { return ((long) x << 32) | (z & 0xffffffffL); }
    /** Nearby grid coordinates must not collapse into the record's default 31*x+z buckets. */
    @Override public int hashCode(){
        long value=key();
        value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;
        value=(value^(value>>>27))*0x94d049bb133111ebL;
        value^=value>>>31;
        return (int)(value^(value>>>32));
    }
    public int regionX() { return Math.floorDiv(x, 32); }
    public int regionZ() { return Math.floorDiv(z, 32); }
}
