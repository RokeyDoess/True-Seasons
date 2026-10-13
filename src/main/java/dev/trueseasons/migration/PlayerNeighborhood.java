package dev.trueseasons.migration;

import java.util.*;

/** Center first, then square rings; includes the entire configured view neighborhood. */
public final class PlayerNeighborhood {
    private PlayerNeighborhood() {}
    public static List<ChunkPos> around(ChunkPos center, int radius) {
        if (radius < 0 || radius > 16) throw new IllegalArgumentException("Radius must be 0..16 chunks");
        List<ChunkPos> result = new ArrayList<>();
        result.add(center);
        for (int ring = 1; ring <= radius; ring++)
            for (int dz = -ring; dz <= ring; dz++) for (int dx = -ring; dx <= ring; dx++)
                if (Math.max(Math.abs(dx), Math.abs(dz)) == ring)
                    result.add(new ChunkPos(center.x() + dx, center.z() + dz));
        return List.copyOf(result);
    }
    public static int columnAt(int cursor, int chunkX, int chunkZ, String version) {
        // Odd multiplication permutes all 256 columns. Salt avoids visible rows.
        if (cursor < 0 || cursor >= 256) throw new IllegalArgumentException("Column cursor out of range");
        return (cursor * 149 + Objects.hash(chunkX, chunkZ, version)) & 255;
    }
}
