package dev.trueseasons.migration;

public record ChunkPos(int x, int z) {
    public long key() { return ((long) x << 32) | (z & 0xffffffffL); }
    public int regionX() { return Math.floorDiv(x, 32); }
    public int regionZ() { return Math.floorDiv(z, 32); }
}
