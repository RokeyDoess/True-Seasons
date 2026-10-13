package dev.trueseasons.snow;

import java.io.*;
import java.util.*;

/** Sparse, bounded, versioned exact plant snapshots. No terrain/global state is retained. */
public final class GroundCoverColumns {
    public record Plant(int y, String lower, String upper, String support) {
        public Plant {
            if (y < Short.MIN_VALUE || y >= Short.MAX_VALUE) throw new IllegalArgumentException("Invalid plant height");
            validate(lower); if (upper != null) validate(upper); validate(support);
        }
        private static void validate(String data) {
            if (data == null || data.isEmpty() || data.length() > 512 || !data.startsWith("minecraft:"))
                throw new IllegalArgumentException("Invalid plant block data");
        }
    }
    private final Map<Integer, Plant> plants = new TreeMap<>();
    public void put(int column, Plant plant) {
        if (column < 0 || column >= 256) throw new IllegalArgumentException("Invalid plant column");
        plants.put(column, Objects.requireNonNull(plant));
    }
    public Plant get(int column) { return plants.get(column); }
    public void remove(int column) { plants.remove(column); }
    public boolean isEmpty() { return plants.isEmpty(); }
    public Map<Integer, Plant> entries() { return Collections.unmodifiableMap(plants); }
    public byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeByte(1); out.writeShort(plants.size());
            for (var row : plants.entrySet()) {
                Plant p = row.getValue(); out.writeByte(row.getKey()); out.writeShort(p.y());
                out.writeUTF(p.lower()); out.writeBoolean(p.upper() != null);
                if (p.upper() != null) out.writeUTF(p.upper()); out.writeUTF(p.support());
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }
    public static GroundCoverColumns decode(byte[] bytes) throws IOException {
        var result = new GroundCoverColumns(); if (bytes == null) return result;
        if (bytes.length > 524288) throw new IOException("Oversized plant metadata");
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readUnsignedByte() != 1) throw new IOException("Unknown plant metadata schema");
        int count = in.readUnsignedShort(); if (count > 256) throw new IOException("Too many plant snapshots");
        try {
            for (int i = 0; i < count; i++) {
                int column = in.readUnsignedByte(); int y = in.readShort(); String lower = in.readUTF();
                String upper = in.readBoolean() ? in.readUTF() : null; String support = in.readUTF();
                if (result.get(column) != null) throw new IOException("Duplicate plant column");
                result.put(column, new Plant(y, lower, upper, support));
            }
        } catch (IllegalArgumentException ex) { throw new IOException("Invalid plant snapshot", ex); }
        if (in.available() != 0) throw new IOException("Trailing plant metadata");
        return result;
    }
}
