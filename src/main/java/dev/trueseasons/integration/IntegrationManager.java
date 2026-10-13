package dev.trueseasons.integration;

import dev.trueseasons.config.Settings;
import java.util.function.Supplier;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.plugin.java.JavaPlugin;

public final class IntegrationManager implements IntegrationAdapter {
    private final IntegrationAdapter blueMap;
    public IntegrationManager(JavaPlugin plugin, Supplier<Settings> settings) throws Exception {
        // Do not load a class referencing BlueMap unless the optional plugin exists.
        if (plugin.getServer().getPluginManager().getPlugin("BlueMap") != null)
            blueMap = new BlueMapIntegration(plugin, settings);
        else blueMap = null;
    }
    @Override public void chunkUpdated(Chunk chunk) { if (blueMap != null) blueMap.chunkUpdated(chunk); }
    @Override public void setRegionReadiness(RegionReadiness readiness) { if (blueMap != null) blueMap.setRegionReadiness(readiness); }
    @Override public void chunkCompleted(Chunk chunk) { if (blueMap != null) blueMap.chunkCompleted(chunk); }
    @Override public void chunkCompleted(Chunk chunk, boolean cleanup) { if (blueMap != null) blueMap.chunkCompleted(chunk, cleanup); }
    @Override public int cleanupPending() { return blueMap == null ? 0 : blueMap.cleanupPending(); }
    @Override public boolean isEnabled() { return blueMap != null && blueMap.isEnabled(); }
    @Override public boolean requiresRecovery(UUID world, int x, int z) { return blueMap != null && blueMap.requiresRecovery(world, x, z); }
    @Override public boolean requiresRecovery(UUID world, int x, int z, String marker) { return blueMap != null && blueMap.requiresRecovery(world, x, z, marker); }
    @Override public void tick() { if (blueMap != null) blueMap.tick(); }
    @Override public int pending() { return blueMap == null ? 0 : blueMap.pending(); }
    @Override public String status() { return blueMap == null ? "not installed" : blueMap.status(); }
    @Override public void close() { if (blueMap != null) blueMap.close(); }
}
