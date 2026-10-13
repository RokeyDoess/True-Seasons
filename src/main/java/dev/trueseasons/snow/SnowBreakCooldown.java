package dev.trueseasons.snow;

import java.util.*;
import java.util.function.LongSupplier;
import org.bukkit.Material;

/** Bounded monotonic column grace; keys retain coordinates, never a World, Chunk or Block. */
final class SnowBreakCooldown {
    record Column(UUID world, int chunkX, int chunkZ, int column) {
        Column {
            Objects.requireNonNull(world);
            if (column < 0 || column > 255) throw new IllegalArgumentException("Invalid snow column");
        }
        static Column at(UUID world, int x, int z) {
            return new Column(world, x >> 4, z >> 4, (x & 15) | ((z & 15) << 4));
        }
    }
    private final LongSupplier time;
    private final int maximum;
    private final LinkedHashMap<Column, Long> expiry = new LinkedHashMap<>();
    SnowBreakCooldown(LongSupplier time, int maximum) {
        this.time = Objects.requireNonNull(time);
        if (maximum < 1) throw new IllegalArgumentException("Cooldown capacity must be positive");
        this.maximum = maximum;
    }
    void record(Column column, int seconds) {
        if (seconds < 0 || seconds > 86400) throw new IllegalArgumentException("Cooldown seconds must be 0..86400");
        expiry.remove(column);
        if (seconds == 0) return;
        if (expiry.size() >= maximum) maintain(128);
        if (expiry.size() >= maximum) {
            Iterator<Column> oldest = expiry.keySet().iterator(); oldest.next(); oldest.remove();
        }
        // Subtraction-based deadline checks remain valid through System.nanoTime wraparound.
        expiry.put(column, time.getAsLong() + seconds * 1_000_000_000L);
    }
    void recordBreak(UUID world, int x, int z, Material type, boolean cancelled, boolean winterEnabled, int seconds) {
        if (!cancelled && winterEnabled && (type == Material.SNOW || type == Material.SNOW_BLOCK))
            record(Column.at(world, x, z), seconds);
    }
    boolean blocksFormation(UUID world, int x, int z, Material type, boolean cancelled, boolean winterEnabled, int seconds) {
        return !cancelled && winterEnabled && seconds > 0 && type == Material.SNOW && blocked(Column.at(world, x, z));
    }
    boolean blocked(Column column) {
        Long deadline = expiry.get(column);
        if (deadline == null) return false;
        if (deadline - time.getAsLong() > 0) return true;
        expiry.remove(column); return false;
    }
    void maintain(int budget) {
        long now = time.getAsLong();
        int count = Math.min(Math.max(0, budget), expiry.size());
        for (int i = 0; i < count; i++) {
            Iterator<Map.Entry<Column, Long>> iterator = expiry.entrySet().iterator();
            var entry = iterator.next(); Column column = entry.getKey(); long deadline = entry.getValue();
            iterator.remove();
            if (deadline - now > 0) expiry.put(column, deadline); // Rotate so later expirations also get inspected.
        }
    }
    int[] available(UUID world, int chunkX, int chunkZ, int[] columns) {
        Objects.requireNonNull(columns);
        if (columns.length > 256) throw new IllegalArgumentException("At most 256 sampled columns");
        int[] result = new int[columns.length]; int size = 0;
        for (int column : columns) if (!blocked(new Column(world, chunkX, chunkZ, column))) result[size++] = column;
        return Arrays.copyOf(result, size);
    }
    boolean allAvailable(UUID world, int chunkX, int chunkZ, int[] columns) {
        for (int column : columns) if (blocked(new Column(world, chunkX, chunkZ, column))) return false;
        return true;
    }
    int size() { return expiry.size(); }
    void clear() { expiry.clear(); }
}
