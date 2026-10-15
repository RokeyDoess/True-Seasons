package dev.trueseasons.snow;

import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.event.*;
import org.bukkit.event.world.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Main-thread ownership publication with read-only packet-thread views. */
public final class SnowOwnershipSnapshots implements Listener, AutoCloseable {
    public record Snapshot(Map<Integer, Integer> heights) {
        public static final Snapshot EMPTY = new Snapshot(Map.of());
        public Snapshot { heights = Map.copyOf(heights); }
    }
    public static final class WorldView {
        private final ConcurrentHashMap<Long, Snapshot> chunks = new ConcurrentHashMap<>();
        /** Packet-thread safe; no Bukkit objects or terrain reads are retained. */
        public Snapshot snapshot(int x, int z) { return chunks.getOrDefault(key(x, z), Snapshot.EMPTY); }
        private boolean observed(int x, int z) { return chunks.containsKey(key(x, z)); }
        private void publish(int x, int z, Snapshot snapshot) { chunks.put(key(x, z), snapshot); }
        private void forget(int x, int z) { chunks.remove(key(x, z)); }
        private void clear() { chunks.clear(); }
    }
    private static final WorldView EMPTY_WORLD = new WorldView();
    private final ConcurrentHashMap<UUID, WorldView> worlds = new ConcurrentHashMap<>();
    private final SeasonalSnowService snow;
    private final Consumer<Chunk> onChanged;
    private final Logger logger;
    private final BiConsumer<Chunk, SnowColumns> observer = this::publish;
    // Bounded by loaded chunks, with removal on successful reads and unload.
    private final Map<UUID, Set<Long>> warned = new HashMap<>();
    private volatile boolean closed;

    public SnowOwnershipSnapshots(JavaPlugin plugin, SeasonalSnowService snow, Consumer<Chunk> onChanged) {
        this(snow, onChanged, plugin.getLogger());
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (World world : Bukkit.getWorlds()) for (Chunk chunk : world.getLoadedChunks()) capture(chunk);
    }
    SnowOwnershipSnapshots(SeasonalSnowService snow, Consumer<Chunk> onChanged, Logger logger) {
        this.snow = Objects.requireNonNull(snow);
        this.onChanged = Objects.requireNonNull(onChanged);
        this.logger = Objects.requireNonNull(logger);
        snow.setOwnershipObserver(observer);
    }
    /** Stable view for this loaded world; retaining it never retains the Bukkit world. */
    public WorldView world(UUID id) {
        Objects.requireNonNull(id);
        return closed ? EMPTY_WORLD : worlds.computeIfAbsent(id, ignored -> new WorldView());
    }
    private static long key(int x, int z) { return ((long)x << 32) | (z & 0xffffffffL); }
    private void publish(Chunk chunk, SnowColumns columns) {
        if (closed) return;
        UUID id = chunk.getWorld().getUID();
        Snapshot snapshot = columns.isEmpty() ? Snapshot.EMPTY : new Snapshot(columns.entries());
        world(id).publish(chunk.getX(), chunk.getZ(), snapshot);
        Set<Long> failures = warned.get(id);
        if (failures != null) {
            failures.remove(key(chunk.getX(), chunk.getZ()));
            if (failures.isEmpty()) warned.remove(id);
        }
        notifyChanged(chunk);
    }
    private void notifyChanged(Chunk chunk) {
        try { onChanged.accept(chunk); }
        catch (RuntimeException failure) {
            logger.warning("Could not refresh snow presentation for chunk " + chunk.getX() + "," + chunk.getZ()
                + ": " + failure.getMessage());
        }
    }
    private void capture(Chunk chunk) {
        if (closed) return;
        try { publish(chunk, snow.observeOwnership(chunk)); }
        catch (IOException failure) {
            UUID id = chunk.getWorld().getUID();
            world(id).publish(chunk.getX(), chunk.getZ(), Snapshot.EMPTY);
            if (warned.computeIfAbsent(id, ignored -> new HashSet<>()).add(key(chunk.getX(), chunk.getZ())))
                logger.warning("Snow presentation skipped unreadable ownership in " + chunk.getWorld().getName()
                    + " chunk " + chunk.getX() + "," + chunk.getZ() + ": " + failure.getMessage());
            notifyChanged(chunk);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(ChunkLoadEvent event) { capture(event.getChunk()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerLoad(PlayerChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        if (!world(chunk.getWorld().getUID()).observed(chunk.getX(), chunk.getZ())) capture(chunk);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk(); UUID id = chunk.getWorld().getUID();
        WorldView view = worlds.get(id);
        if (view != null) view.forget(chunk.getX(), chunk.getZ());
        Set<Long> failures = warned.get(id);
        if (failures != null) {
            failures.remove(key(chunk.getX(), chunk.getZ()));
            if (failures.isEmpty()) warned.remove(id);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        UUID id = event.getWorld().getUID(); WorldView old = worlds.remove(id);
        if (old != null) old.clear();
        warned.remove(id);
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        snow.removeOwnershipObserver(observer);
        HandlerList.unregisterAll(this);
        worlds.values().forEach(WorldView::clear);
        worlds.clear(); warned.clear();
    }
}
