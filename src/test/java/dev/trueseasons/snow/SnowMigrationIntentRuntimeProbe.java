package dev.trueseasons.snow;

import dev.trueseasons.api.Season;
import dev.trueseasons.config.Settings;
import dev.trueseasons.migration.PlayerNeighborhood;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Supplier;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Small real-placement callback test, called only on an already-loaded disposable fixture. */
public final class SnowMigrationIntentRuntimeProbe {
    private SnowMigrationIntentRuntimeProbe() {}
    public static int verify(JavaPlugin probe, Supplier<Settings> settings, Chunk chunk) throws Exception {
        if (!chunk.isLoaded() || !Bukkit.isPrimaryThread()) throw new IllegalStateException("Loaded main-thread verification fixture required");
        SeasonalSnowService snow = new SeasonalSnowService(probe, settings, () -> true);
        NamespacedKey owned = new NamespacedKey(probe, "snow_v1"), pending = new NamespacedKey(probe, "winter_pending"), cursor = new NamespacedKey(probe, "winter_cursor"), version = new NamespacedKey(probe, "season_version");
        var pdc = chunk.getPersistentDataContainer();
        if (pdc.has(owned) || pdc.has(pending) || pdc.has(cursor) || pdc.has(version)) throw new IllegalStateException("Probe snow namespace must be unused");
        String first = "intent-placement-v1", second = "intent-placement-v2";
        int a = PlayerNeighborhood.columnAt(0, chunk.getX(), chunk.getZ(), first);
        int b = PlayerNeighborhood.columnAt(0, chunk.getX(), chunk.getZ(), second);
        int y = chunk.getWorld().getMaxHeight() - 3;
        Map<Block, BlockState> original = new LinkedHashMap<>();
        for (int column : new int[]{a, b}) for (int height = y; height < chunk.getWorld().getMaxHeight(); height++) {
            Block block = chunk.getBlock(column & 15, height, column >> 4); original.putIfAbsent(block, block.getState());
        }
        int checks = 0;
        try {
            for (int column : new int[]{a, b}) {
                chunk.getBlock(column & 15, y, column >> 4).setType(Material.STONE, false);
                chunk.getBlock(column & 15, y + 1, column >> 4).setType(Material.AIR, false);
                chunk.getBlock(column & 15, y + 2, column >> 4).setType(Material.AIR, false);
            }
            Block targetA = chunk.getBlock(a & 15, y + 1, a >> 4);
            int[] calls = {0};
            var placed = snow.applyStep(chunk, Season.WINTER, first, false, 1, () -> {
                calls[0]++;
                if (pdc.has(owned) || targetA.getType() != Material.AIR) throw new AssertionError("New placement intent must precede ownership and snow");
            });
            if (placed.placed() != 1 || calls[0] != 1 || targetA.getType() != Material.SNOW) throw new AssertionError("Actual snow placement calls intent exactly once"); checks++;
            byte[] recorded = pdc.get(owned, PersistentDataType.BYTE_ARRAY);
            var owns = SeasonalSnowService.class.getDeclaredMethod("owns", Block.class); owns.setAccessible(true);
            if (recorded == null || !(boolean) owns.invoke(snow, targetA)) throw new AssertionError("Actual new snow is owned before publication"); checks++;
            Field data = SeasonalSnowService.class.getDeclaredField("replenishmentSnowData"); data.setAccessible(true); Object cached = data.get(snow);
            if (cached == null) throw new AssertionError("Real first placement initializes private default data lazily"); checks++;
            calls[0] = 0;
            var mixed = snow.applyStep(chunk, Season.WINTER, second, false, 1, () -> {
                calls[0]++;
                if (targetA.getType() != Material.SNOW || !pdc.has(owned)) throw new AssertionError("Mixed batch intent must precede its first owned snow removal");
            });
            if (mixed.removed() != 1 || mixed.placed() != 1 || calls[0] != 1) throw new AssertionError("One intent covers real removal plus new placement"); checks++;
            if (data.get(snow) != cached) throw new AssertionError("Migration reuses private default block data between placement batches"); checks++;
            probe.getLogger().info("PASS: Real migration placement/mixed-batch intent ordering and private block-data reuse");
            return checks;
        } finally {
            snow.apply(chunk, Season.SPRING, second, true);
            for (BlockState state : original.values()) state.update(true, false);
            pdc.remove(owned); pdc.remove(pending); pdc.remove(cursor); pdc.remove(version);
        }
    }
}
