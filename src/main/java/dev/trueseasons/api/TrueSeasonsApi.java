package dev.trueseasons.api;

import org.bukkit.*;

/** Obtain via Bukkit's ServicesManager. All calls are on the server thread. */
public interface TrueSeasonsApi {
    Season getCurrentSeason(World world);
    double getSeasonProgress(World world);
    /** Requires an already loaded chunk; never generates a chunk to inspect its biome. */
    SeasonVisualProfile getSeasonalVisuals(Location location);
    String getChunkSeasonalVersion(Chunk chunk);
    boolean isCleanupActive();
    /** Editors must call before replacing a tracked snow block with identical data. */
    void relinquishSnowOwnership(org.bukkit.block.Block block);
}
