package dev.trueseasons.snow;

import java.io.*;
import java.util.*;

/** Versioned sparse column bitmap + signed heights: <= 549 bytes per chunk. */
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
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(1);
            BitSet bits = new BitSet(256);
            heights.keySet().forEach(bits::set);
            out.write(Arrays.copyOf(bits.toByteArray(), 32));
            for (int height : heights.values()) out.writeShort(height);
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }
    public static SnowColumns decode(byte[] bytes) throws IOException {
        SnowColumns result = new SnowColumns();
        if (bytes == null) return result;
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readUnsignedByte() != 1) throw new IOException("Unknown snow metadata schema");
        byte[] bitmap = in.readNBytes(32);
        if (bitmap.length != 32) throw new IOException("Truncated snow bitmap");
        BitSet bits = BitSet.valueOf(bitmap);
        for (int column = bits.nextSetBit(0); column >= 0; column = bits.nextSetBit(column + 1))
            result.put(column, (int) in.readShort());
        if (in.available() != 0) throw new IOException("Trailing snow metadata");
        return result;
    }
}
