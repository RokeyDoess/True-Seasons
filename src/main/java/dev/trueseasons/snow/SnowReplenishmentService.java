package dev.trueseasons.snow;

import dev.trueseasons.api.SeasonChunkUpdatedEvent;
import dev.trueseasons.config.Settings;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Bounded vanilla-style precipitation attempts on existing, fully migrated, loaded chunks only. */
public final class SnowReplenishmentService implements Listener, AutoCloseable {
    private static final int CHUNKS_PER_TICK = 8, COLUMNS_PER_CHUNK = 8, PRIORITY_CHUNKS = 4096;
    private static final long BUDGET_NANOS = 1_000_000;
    private final JavaPlugin plugin;
    private final SeasonalSnowService snow;
    private final Supplier<Settings> settings;
    private final Supplier<String> version;
    private final Predicate<World> activeAndFinished;
    private final Consumer<Chunk> beforeWrite, afterWrite;
    private final LinkedHashMap<Chunk, BitSet> broken = new LinkedHashMap<>();
    private final ArrayDeque<Chunk> brokenQueue = new ArrayDeque<>();
    private List<Chunk> loaded = List.of();
    private int cursor, ticks;
    private boolean closed;

    /** Root predicate must require winter, enabled snow/weather, no cleanup/pause, cached TPS and worldFinished. */
    public SnowReplenishmentService(JavaPlugin plugin, SeasonalSnowService snow, Supplier<Settings> settings,
            Supplier<String> version, Predicate<World> activeAndFinished,
            Consumer<Chunk> beforeWrite, Consumer<Chunk> afterWrite) {
        this.plugin = plugin; this.snow = snow; this.settings = settings; this.version = version;
        this.activeAndFinished = activeAndFinished; this.beforeWrite = beforeWrite; this.afterWrite = afterWrite;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broken(BlockBreakEvent event) {
        if (event.getBlock().getType() != Material.SNOW || !activeAndFinished.test(event.getBlock().getWorld())) return;
        Chunk chunk = event.getBlock().getChunk();
        if (broken.size() >= PRIORITY_CHUNKS && !broken.containsKey(chunk)) return;
        if (!broken.containsKey(chunk)) { broken.put(chunk, new BitSet(256)); brokenQueue.addLast(chunk); }
        broken.get(chunk).set((event.getBlock().getX() & 15) | ((event.getBlock().getZ() & 15) << 4));
    }
    public void tick() throws IOException {
        if (closed) return;
        ticks++;
        if (ticks == 1 || ticks % 20 == 0) refreshLoaded();
        long deadline = System.nanoTime() + BUDGET_NANOS;
        Set<Chunk> sampled = new HashSet<>(); int attempts = 0;
        for (int inspected = 0; inspected < CHUNKS_PER_TICK * 4 && !brokenQueue.isEmpty()
                && attempts < CHUNKS_PER_TICK / 2 && System.nanoTime() < deadline; inspected++) {
            Chunk chunk = brokenQueue.removeFirst(); BitSet pending = broken.get(chunk);
            if (!chunk.isLoaded() || !activeAndFinished.test(chunk.getWorld())) { broken.remove(chunk); continue; }
            if (!ready(chunk)) { brokenQueue.addLast(chunk); continue; }
            int[] columns = pending.stream().limit(COLUMNS_PER_CHUNK).toArray();
            // One deterministic refill batch per broken chunk; failed/protected columns stay untouched.
            try { refill(chunk, columns, deadline); }
            catch (IOException | RuntimeException failure) { brokenQueue.addFirst(chunk); throw failure; }
            sampled.add(chunk); attempts++;
            if (System.nanoTime() >= deadline) { brokenQueue.addFirst(chunk); return; }
            for (int column : columns) pending.clear(column);
            if (pending.isEmpty()) broken.remove(chunk); else brokenQueue.addLast(chunk);
        }
        int inspected = 0;
        while (!loaded.isEmpty() && attempts < CHUNKS_PER_TICK && inspected < CHUNKS_PER_TICK * 4
                && System.nanoTime() < deadline) {
            Chunk chunk = loaded.get(Math.floorMod(cursor++, loaded.size())); inspected++;
            if (!sampled.add(chunk) || !ready(chunk)) continue;
            int[] columns = new int[COLUMNS_PER_CHUNK];
            for (int i = 0; i < columns.length; i++) columns[i] = ThreadLocalRandom.current().nextInt(256);
            refill(chunk, columns, deadline); attempts++;
        }
    }
    private boolean ready(Chunk chunk) {
        return chunk.isLoaded() && activeAndFinished.test(chunk.getWorld()) && chunk.getWorld().hasStorm()
                && snow.isComplete(chunk, version.get());
    }
    /** Deterministic entry point for live regression, with all world/version/weather gates intact. */
    public SeasonalSnowService.Result refill(Chunk chunk, int[] columns) throws IOException {
        return refill(chunk, columns, Long.MAX_VALUE);
    }
    private SeasonalSnowService.Result refill(Chunk chunk, int[] columns, long deadline) throws IOException {
        if (!ready(chunk)) return new SeasonalSnowService.Result(0, 0, 0, false);
        String appliedVersion = version.get();
        var result = snow.replenishColumns(chunk, appliedVersion, columns, beforeWrite, afterWrite, deadline,
                () -> ready(chunk) && appliedVersion.equals(version.get()));
        if (result.changed()) Bukkit.getPluginManager().callEvent(new SeasonChunkUpdatedEvent(chunk, appliedVersion, false, true));
        return result;
    }
    private void refreshLoaded() {
        List<Chunk> next = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) if (activeAndFinished.test(world)) Collections.addAll(next, world.getLoadedChunks());
        loaded = List.copyOf(next);
        if (loaded.isEmpty()) cursor = 0;
    }
    @Override public void close() { closed = true; loaded = List.of(); broken.clear(); brokenQueue.clear(); HandlerList.unregisterAll(this); }
}
