package dev.trueseasons.integration;

import org.bukkit.Chunk;
import java.util.UUID;
import java.util.function.Supplier;
import dev.trueseasons.visual.SeasonalVisualState;

/** Future DH support implements its own adapter; core never imports DH types. */
public interface IntegrationAdapter extends AutoCloseable {
    @FunctionalInterface interface RegionReadiness {
        /** All known generated chunks in this region have finished current world edits. */
        boolean ready(UUID world, int regionX, int regionZ);
    }
    default void setRegionReadiness(RegionReadiness readiness) {}
    default void setVisuals(Supplier<SeasonalVisualState> visuals) {}
    void chunkUpdated(Chunk chunk);
    /** Called after all changes and seasonal metadata for this chunk are complete. */
    void chunkCompleted(Chunk chunk);
    default void chunkCompleted(Chunk chunk, boolean cleanup) { chunkCompleted(chunk); }
    default int cleanupPending() { return 0; }
    default boolean isEnabled() { return false; }
    /** Saved-current chunks still need a non-generating load if their render token was never saved. */
    default boolean requiresRecovery(UUID world, int x, int z) { return false; }
    /** A matching marker read from a saved snapshot/loaded complete chunk needs no world replay. */
    default boolean requiresRecovery(UUID world, int x, int z, String marker) { return requiresRecovery(world, x, z); }
    void tick();
    int pending();
    String status();
    @Override void close();
}
