package dev.trueseasons.snow;

/** A stable keyed shuffle of all 256 surface columns, with an exact inverse. */
public final class SnowColumnOrder {
    private final byte[] columns = new byte[256], ranks = new byte[256];
    public SnowColumnOrder(int x, int z, String version) {
        long seed = mix(((long)x << 32) ^ (z & 0xffffffffL) ^ mix(version.hashCode()));
        for (int i = 0; i < 256; i++) columns[i] = (byte)i;
        for (int i = 255; i > 0; i--) {
            seed += 0x9e3779b97f4a7c15L;
            int j = (int)Long.remainderUnsigned(mix(seed), i + 1);
            byte swap = columns[i]; columns[i] = columns[j]; columns[j] = swap;
        }
        for (int i = 0; i < 256; i++) ranks[columns[i] & 255] = (byte)i;
    }
    public int column(int rank) { check(rank); return columns[rank] & 255; }
    public int rank(int column) { check(column); return ranks[column] & 255; }
    private static void check(int value) {
        if (value < 0 || value > 255) throw new IllegalArgumentException("Snow column/rank must be 0..255");
    }
    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
