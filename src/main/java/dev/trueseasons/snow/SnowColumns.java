package dev.trueseasons.snow;

import java.io.*;
import java.util.*;

/** Versioned sparse column bitmap + signed heights: <= 545 bytes per chunk. */
public final class SnowColumns {
    private short[] heights;
    private final long[] present = new long[4];
    private int size;
    private Map<Integer,Integer> view;
    public void put(int column, int height) {
        if (column < 0 || column >= 256 || height < Short.MIN_VALUE || height > Short.MAX_VALUE)
            throw new IllegalArgumentException("Invalid snow column/height");
        if (heights == null) heights = new short[256];
        long bit = 1L << column;
        if ((present[column >>> 6] & bit) == 0) { present[column >>> 6] |= bit; size++; }
        heights[column] = (short) height;
    }
    public Integer get(int column) { return contains(column) ? (int) heights[column] : null; }
    boolean contains(int column) { return column >= 0 && column < 256 && (present[column >>> 6] & (1L << column)) != 0; }
    public void remove(int column) {
        if (contains(column)) { present[column >>> 6] &= ~(1L << column); size--; }
    }
    public boolean isEmpty() { return size == 0; }
    SnowColumns copy() {
        SnowColumns result=new SnowColumns();result.size=size;
        result.heights=heights==null?null:heights.clone();
        System.arraycopy(present,0,result.present,0,present.length);return result;
    }
    public Map<Integer, Integer> entries() {
        if (view == null) view = Collections.unmodifiableMap(new AbstractMap<>() {
            @Override public int size() { return size; }
            @Override public Integer get(Object key) { return key instanceof Integer column ? SnowColumns.this.get(column) : null; }
            @Override public Set<Entry<Integer,Integer>> entrySet() {
                return new AbstractSet<>() {
                    @Override public int size() { return size; }
                    @Override public Iterator<Entry<Integer,Integer>> iterator() {
                        return new Iterator<>() {
                            int next = nextColumn(0);
                            @Override public boolean hasNext() { return next < 256; }
                            @Override public Entry<Integer,Integer> next() {
                                if (!hasNext()) throw new NoSuchElementException();
                                int column = next; next = nextColumn(column + 1);
                                return new SimpleImmutableEntry<>(column, (int) heights[column]);
                            }
                        };
                    }
                };
            }
        });
        return view;
    }
    /** Allocation-free traversal for terrain work; 256 is the end sentinel. */
    int nextColumn(int from) {
        if (from < 0) throw new IllegalArgumentException("Negative column");
        if (from >= 256) return 256;
        int word = from >>> 6;
        long bits = present[word] & (-1L << from);
        while (bits == 0 && ++word < 4) bits = present[word];
        return word == 4 ? 256 : word * 64 + Long.numberOfTrailingZeros(bits);
    }
    int heightAt(int column) {
        if (!contains(column)) throw new NoSuchElementException("Missing snow column");
        return heights[column];
    }
    public byte[] encode() {
        byte[] bytes = new byte[33 + size * 2]; bytes[0] = 1; int offset = 33;
        for (int column = nextColumn(0); column < 256; column = nextColumn(column + 1)) {
            int height = heights[column];
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
