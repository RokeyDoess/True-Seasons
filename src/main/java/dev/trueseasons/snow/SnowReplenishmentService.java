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
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Bounded vanilla-style precipitation attempts on existing, fully migrated, loaded chunks only. */
public final class SnowReplenishmentService implements Listener, AutoCloseable {
    private static final int CHUNKS_PER_TICK = 8, COLUMNS_PER_CHUNK = 8;
    private static final long BUDGET_NANOS = 1_000_000;
    private final JavaPlugin plugin;
    private final SeasonalSnowService snow;
    private final Supplier<Settings> settings;
    private final Supplier<String> version;
    private final Predicate<World> winterEnabled;
    private final Predicate<World> activeAndFinished;
    private final Consumer<Chunk> beforeWrite, afterWrite;
    private final SnowBreakCooldown cooldowns;
    private List<Chunk> loaded = List.of();
    private int cursor, ticks;
    private boolean closed;

    /** Root predicate must require winter, enabled snow/weather, no cleanup/pause, cached TPS and worldFinished. */
    public SnowReplenishmentService(JavaPlugin plugin, SeasonalSnowService snow, Supplier<Settings> settings,
            Supplier<String> version, Predicate<World> winterEnabled, Predicate<World> activeAndFinished,
            Consumer<Chunk> beforeWrite, Consumer<Chunk> afterWrite) {
        this(plugin, snow, settings, version, winterEnabled, activeAndFinished, beforeWrite, afterWrite, System::nanoTime);
    }
    SnowReplenishmentService(JavaPlugin plugin, SeasonalSnowService snow, Supplier<Settings> settings,
            Supplier<String> version, Predicate<World> winterEnabled, Predicate<World> activeAndFinished,
            Consumer<Chunk> beforeWrite, Consumer<Chunk> afterWrite, LongSupplier nanoTime) {
        this.plugin = plugin; this.snow = snow; this.settings = settings; this.version = version;
        this.winterEnabled = winterEnabled;
        this.activeAndFinished = activeAndFinished; this.beforeWrite = beforeWrite; this.afterWrite = afterWrite;
        cooldowns = new SnowBreakCooldown(nanoTime, 65536);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broken(BlockBreakEvent event) {
        if (closed || event.isCancelled()) return;
        var block = event.getBlock();
        cooldowns.recordBreak(block.getWorld().getUID(), block.getX(), block.getZ(), block.getType(),
                false, winterEnabled.test(block.getWorld()), settings.get().snowPlayerBreakCooldownSeconds);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void protectBreakGrace(BlockFormEvent event) {
        if (closed || event.isCancelled()) return;
        var block = event.getBlock();
        if (cooldowns.blocksFormation(block.getWorld().getUID(), block.getX(), block.getZ(), event.getNewState().getType(),
                false, winterEnabled.test(block.getWorld()), settings.get().snowPlayerBreakCooldownSeconds)) event.setCancelled(true);
    }
    public void tick() throws IOException {
        if (closed) return;
        ticks++;
        if (ticks == 1 || ticks % 20 == 0) { cooldowns.maintain(128); refreshLoaded(); }
        long deadline = System.nanoTime() + BUDGET_NANOS;
        Set<Chunk> sampled = new HashSet<>(); int attempts = 0;
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
        return !closed && chunk.isLoaded() && activeAndFinished.test(chunk.getWorld()) && chunk.getWorld().hasStorm()
                && snow.isComplete(chunk, version.get());
    }
    /** Deterministic entry point for live regression, with all world/version/weather gates intact. */
    public SeasonalSnowService.Result refill(Chunk chunk, int[] columns) throws IOException {
        return refill(chunk, columns, Long.MAX_VALUE);
    }
    private SeasonalSnowService.Result refill(Chunk chunk, int[] columns, long deadline) throws IOException {
        if (!ready(chunk)) return new SeasonalSnowService.Result(0, 0, 0, false);
        String appliedVersion = version.get();
        UUID world = chunk.getWorld().getUID(); int x = chunk.getX(), z = chunk.getZ();
        int[] available = settings.get().snowPlayerBreakCooldownSeconds <= 0 ? columns
                : cooldowns.available(world, x, z, columns);
        if (available.length == 0) return new SeasonalSnowService.Result(0, 0, 0, true);
        var result = snow.replenishColumns(chunk, appliedVersion, available, beforeWrite, afterWrite, deadline,
                () -> ready(chunk) && appliedVersion.equals(version.get())
                    && (settings.get().snowPlayerBreakCooldownSeconds <= 0 || cooldowns.allAvailable(world, x, z, available)));
        if (result.changed()) Bukkit.getPluginManager().callEvent(new SeasonChunkUpdatedEvent(chunk, appliedVersion, false, true));
        return result;
    }
    private void refreshLoaded() {
        List<Chunk> next = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) if (activeAndFinished.test(world)) Collections.addAll(next, world.getLoadedChunks());
        loaded = List.copyOf(next);
        if (loaded.isEmpty()) cursor = 0;
    }
    /** Share player-clearing grace with ahead-of-region storm accumulation. */
    int[] availableColumns(Chunk chunk, int[] columns) {
        return settings.get().snowPlayerBreakCooldownSeconds <= 0 ? columns
            : cooldowns.available(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ(), columns);
    }
    boolean columnsAvailable(Chunk chunk, int[] columns) {
        return settings.get().snowPlayerBreakCooldownSeconds <= 0
            || cooldowns.allAvailable(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ(), columns);
    }
    @Override public void close() { closed = true; loaded = List.of(); cooldowns.clear(); HandlerList.unregisterAll(this); }
}
