package dev.trueseasons.snow;

import dev.trueseasons.api.SeasonChunkUpdatedEvent;
import dev.trueseasons.config.Settings;
import dev.trueseasons.migration.PlayerNeighborhood;
import java.io.IOException;
import java.util.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Loaded player terrain receives gradual storm snow without touching the region queue. */
public final class LocalWinterSnowService implements Listener, AutoCloseable {
    private static final int CHUNKS_PER_TICK = 8, COLUMNS_PER_CHUNK = 8, INSPECTIONS_PER_TICK = 32;
    private static final long BUDGET_NANOS = 1_000_000;
    private final SeasonalSnowService snow;
    private final Supplier<Settings> settings;
    private final Supplier<String> version;
    private final Predicate<World> winterEnabled, active;
    private final IntSupplier coverage;
    private final SnowReplenishmentService grace;
    private final Consumer<Chunk> beforeWrite, afterWrite;
    private List<Chunk> loaded = List.of();
    private int cursor, ticks;
    private boolean closed;

    /** Active predicate includes cached TPS, pause/cleanup, and all winter snow/weather gates. */
    public LocalWinterSnowService(JavaPlugin plugin, SeasonalSnowService snow, Supplier<Settings> settings,
            Supplier<String> version, Predicate<World> winterEnabled, Predicate<World> active,
            IntSupplier coverage, SnowReplenishmentService grace,
            Consumer<Chunk> beforeWrite, Consumer<Chunk> afterWrite) {
        this(snow, settings, version, winterEnabled, active, coverage, grace, beforeWrite, afterWrite);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }
    LocalWinterSnowService(SeasonalSnowService snow, Supplier<Settings> settings,
            Supplier<String> version, Predicate<World> winterEnabled, Predicate<World> active,
            IntSupplier coverage, SnowReplenishmentService grace,
            Consumer<Chunk> beforeWrite, Consumer<Chunk> afterWrite) {
        this.snow = snow; this.settings = settings; this.version = version;
        this.winterEnabled = winterEnabled; this.active = active; this.coverage = coverage;
        this.grace = grace; this.beforeWrite = beforeWrite; this.afterWrite = afterWrite;
    }

    /** Inverse of the migration's odd-multiplier permutation, with the same version salt. */
    static boolean withinCoverage(int column, int chunkX, int chunkZ, String version, int coverage) {
        if (column < 0 || column > 255 || coverage < 0 || coverage > 256)
            throw new IllegalArgumentException("Invalid local snow coverage");
        int rank = ((column - Objects.hash(chunkX, chunkZ, version)) * 189) & 255;
        return rank < coverage; // 149 * 189 == 1 (mod 256).
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void capNaturalSnow(BlockFormEvent event) {
        if (closed || event.isCancelled() || event.getNewState().getType() != Material.SNOW) return;
        var block = event.getBlock(); World world = block.getWorld();
        if (!winterEnabled.test(world) || !world.isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) return;
        Chunk chunk = block.getChunk(); String current = version.get();
        if (!snow.isComplete(chunk, current)
                && !withinCoverage((block.getX() & 15) | ((block.getZ() & 15) << 4),
                    chunk.getX(), chunk.getZ(), current, coverage.getAsInt())) event.setCancelled(true);
    }
    private boolean nearby(Chunk chunk) {
        int radius = settings.get().playerRadius;
        Location scratch = new Location(null, 0, 0, 0);
        for (Player player : chunk.getWorld().getPlayers()) {
            Location at = player.getLocation(scratch);
            if (Math.abs((long) chunk.getX() - (at.getBlockX() >> 4)) <= radius
                    && Math.abs((long) chunk.getZ() - (at.getBlockZ() >> 4)) <= radius) return true;
        }
        return false;
    }
    private boolean nearbySnapshot(Chunk chunk, Map<World, long[]> centers) {
        long[] positions = centers.computeIfAbsent(chunk.getWorld(), world -> {
            List<Player> players = world.getPlayers(); long[] result = new long[players.size()];
            Location scratch = new Location(null, 0, 0, 0);
            for (int i = 0; i < players.size(); i++) {
                Location at = players.get(i).getLocation(scratch);
                result[i] = ((long) (at.getBlockX() >> 4) << 32) | ((at.getBlockZ() >> 4) & 0xffffffffL);
            }
            return result;
        });
        int radius = settings.get().playerRadius;
        for (long at : positions) if (Math.abs((long) chunk.getX() - (int) (at >> 32)) <= radius
            && Math.abs((long) chunk.getZ() - (int) at) <= radius) return true;
        return false;
    }
    private boolean activeChunk(Chunk chunk) {
        return !closed && chunk.isLoaded() && active.test(chunk.getWorld()) && chunk.getWorld().hasStorm()
            && !snow.isComplete(chunk, version.get());
    }
    private boolean ready(Chunk chunk) {
        return activeChunk(chunk) && nearby(chunk);
    }
    public void tick() throws IOException {
        if (closed) return;
        ticks++;
        long deadline = System.nanoTime() + Math.min(BUDGET_NANOS, (long) (settings.get().millis * 1_000_000));
        if (ticks == 1 || ticks % 20 == 0) {
            List<Chunk> next = new ArrayList<>();
            for (World world : Bukkit.getWorlds()) if (active.test(world) && !world.getPlayers().isEmpty())
                Collections.addAll(next, world.getLoadedChunks());
            loaded = List.copyOf(next);
        }
        int attempted = 0; Set<Chunk> sampled = new HashSet<>();
        Map<World, long[]> playerCenters = new HashMap<>();
        for (int inspected = 0; !loaded.isEmpty() && inspected < INSPECTIONS_PER_TICK
                && attempted < CHUNKS_PER_TICK && System.nanoTime() < deadline; inspected++) {
            Chunk chunk = loaded.get(Math.floorMod(cursor++, loaded.size()));
            // Inspecting distant loaded terrain shares one player-position snapshot per world.
            // The actual write path still verifies live positions after reentrant formation events.
            if (!sampled.add(chunk) || !activeChunk(chunk) || !nearbySnapshot(chunk, playerCenters)) continue;
            String current = version.get(); int shared = coverage.getAsInt();
            if (shared <= 0) continue;
            int step = Math.min(COLUMNS_PER_CHUNK, settings.get().snowStepColumns);
            int[] columns = new int[Math.min(shared, step)];
            for (int i = 0; i < columns.length; i++) columns[i] = PlayerNeighborhood.columnAt(
                Math.floorMod((ticks / settings.get().snowStepTicks) * step + i, shared),
                chunk.getX(), chunk.getZ(), current);
            accumulate(chunk, columns, deadline); attempted++;
        }
    }
    /** Deterministic live verification path keeps every activity, location, grace and coverage gate. */
    public SeasonalSnowService.Result accumulate(Chunk chunk, int[] columns) throws IOException {
        return accumulate(chunk, columns, Long.MAX_VALUE);
    }
    private SeasonalSnowService.Result accumulate(Chunk chunk, int[] columns, long deadline) throws IOException {
        if (!ready(chunk)) return new SeasonalSnowService.Result(0, 0, 0, false);
        String current = version.get(); int shared = coverage.getAsInt();
        int[] available = grace.availableColumns(chunk, columns);
        if (available.length == 0) return new SeasonalSnowService.Result(0, 0, 0, false);
        var result = snow.accumulateLocalColumns(chunk, current, shared, available, beforeWrite, afterWrite,
            deadline, () -> ready(chunk) && current.equals(version.get()) && shared <= coverage.getAsInt()
                && grace.columnsAvailable(chunk, available));
        if (result.changed()) Bukkit.getPluginManager().callEvent(new SeasonChunkUpdatedEvent(chunk, current, false, true));
        return result;
    }
    @Override public void close() { closed = true; loaded = List.of(); HandlerList.unregisterAll(this); }
}
