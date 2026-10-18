package dev.trueseasons.snow;

import java.io.*;
import java.util.*;

/** Versioned sparse column bitmap + signed heights: <= 545 bytes per chunk. */
public final class SnowColumns {
    private final Map<Integer, Integer> heights = new TreeMap<>();
    public void put(int column, int height) {
        if (column < 0 || column >= 256 || height < Short.MIN_VALUE || height > Short.MAX_VALUE)
            throw new IllegalArgumentException("Invalid snow column/height");
        heights.put(column, height);
    }
    public Integer get(int column) { return heights.get(column); }
    public void remove(int column) { heights.remove(column); }
    public boolean isEmpty() { return heights.isEmpty(); }
    public Map<Integer, Integer> entries() { return Collections.unmodifiableMap(heights); }
    public byte[] encode() {
        byte[] bytes = new byte[33 + heights.size() * 2]; bytes[0] = 1; int offset = 33;
        for (var entry : heights.entrySet()) {
            int column = entry.getKey(), height = entry.getValue();
            bytes[1 + (column >>> 3)] |= (byte) (1 << (column & 7));
            bytes[offset++] = (byte) (height >>> 8); bytes[offset++] = (byte) height;
        }
        return bytes;
    }
    public static SnowColumns decode(byte[] bytes) throws IOException {
        SnowColumns result = new SnowColumns();
        if (bytes == null) return result;
        if (bytes.length == 0) throw new EOFException();
        if ((bytes[0] & 255) != 1) throw new IOException("Unknown snow metadata schema");
        if (bytes.length < 33) throw new IOException("Truncated snow bitmap");
        int count = 0;
        for (int at = 1; at <= 32; at++) count += Integer.bitCount(bytes[at] & 255);
        // Validate the whole bounded record before building ownership entries.
        int expected = 33 + count * 2;
        if (bytes.length < expected) throw new EOFException();
        if (bytes.length > expected) throw new IOException("Trailing snow metadata");
        int offset = 33;
        for (int at = 1; at <= 32; at++) {
            int bits = bytes[at] & 255;
            while (bits != 0) {
                int column = (at - 1) * 8 + Integer.numberOfTrailingZeros(bits);
                result.put(column, (short) ((bytes[offset] & 255) << 8 | (bytes[offset + 1] & 255)));
                offset += 2; bits &= bits - 1;
            }
        }
        return result;
    }
}
