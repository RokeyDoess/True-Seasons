package dev.trueseasons.snow;

import dev.trueseasons.api.Season;
import dev.trueseasons.config.Settings;
import dev.trueseasons.migration.PlayerNeighborhood;
import java.io.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.BooleanSupplier;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.type.Snow;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public final class SeasonalSnowService implements Listener {
    public record Result(int placed, int removed, int conflicts, boolean complete) {
        public boolean changed() { return placed > 0 || removed > 0; }
    }
    private final NamespacedKey ownedKey, versionKey, pendingKey, cursorKey;
    private final Supplier<Settings> settings;
    private final Supplier<Boolean> winter;
    // Main-thread-only, private default data: canPlace reads it; no caller can mutate it.
    private BlockData replenishmentSnowData;
    public SeasonalSnowService(Plugin plugin, Supplier<Settings> settings) {
        this(plugin, settings, () -> false);
    }
    public SeasonalSnowService(Plugin plugin, Supplier<Settings> settings, Supplier<Boolean> winter) {
        this.settings = settings;
        this.winter = winter;
        ownedKey = new NamespacedKey(plugin, "snow_v1"); versionKey = new NamespacedKey(plugin, "season_version");
        pendingKey = new NamespacedKey(plugin, "winter_pending"); cursorKey = new NamespacedKey(plugin, "winter_cursor");
    }
    public String version(Chunk chunk) {
        var pdc = chunk.getPersistentDataContainer();
        return pdc.has(versionKey, PersistentDataType.STRING) ? pdc.get(versionKey, PersistentDataType.STRING) : null;
    }
    public boolean hasPending(Chunk chunk) {
        var pdc = chunk.getPersistentDataContainer();
        return pdc.has(pendingKey) || pdc.has(cursorKey);
    }
    public boolean isComplete(Chunk chunk, String version) { return version != null && version.equals(version(chunk)) && !hasPending(chunk); }
    public int coveredColumns(Chunk chunk,String version) throws IOException {
        if(isComplete(chunk,version))return 256;
        var pdc=chunk.getPersistentDataContainer();
        if(pdc.has(pendingKey)&&!pdc.has(pendingKey,PersistentDataType.STRING)
            ||pdc.has(cursorKey)&&!pdc.has(cursorKey,PersistentDataType.INTEGER))throw new IOException("Unexpected winter transition metadata type");
        String pending=pdc.get(pendingKey,PersistentDataType.STRING);
        int cursor=pdc.getOrDefault(cursorKey,PersistentDataType.INTEGER,0);
        if(cursor<0||cursor>256||pending==null&&cursor!=0)throw new IOException("Invalid winter transition cursor");
        return Objects.equals(pending,version)?cursor:0;
    }
    public boolean hasMetadata(Chunk chunk) {
        var pdc = chunk.getPersistentDataContainer();
        return pdc.has(ownedKey) || pdc.has(versionKey) || pdc.has(pendingKey) || pdc.has(cursorKey);
    }
    private SnowColumns read(Chunk chunk) throws IOException {
        var pdc = chunk.getPersistentDataContainer();
        if (pdc.has(ownedKey) && !pdc.has(ownedKey, PersistentDataType.BYTE_ARRAY))
            throw new IOException("Unexpected snow metadata type");
        SnowColumns columns = SnowColumns.decode(pdc.get(ownedKey, PersistentDataType.BYTE_ARRAY));
        for (int y : columns.entries().values())
            if (y < chunk.getWorld().getMinHeight() || y >= chunk.getWorld().getMaxHeight())
                throw new IOException("Snow height outside world bounds");
        return columns;
    }
    private void write(Chunk chunk, SnowColumns columns) {
        var pdc = chunk.getPersistentDataContainer();
        if (columns.isEmpty()) pdc.remove(ownedKey);
        else pdc.set(ownedKey, PersistentDataType.BYTE_ARRAY, columns.encode());
    }
    private boolean expected(Block block) { return block.getBlockData() instanceof Snow snow && snow.getLayers() == 1; }

    /** Refill sampled exposed columns after migration, without reopening or changing migration progress. */
    public Result replenishColumns(Chunk chunk, String version, int[] columns,
            Consumer<Chunk> beforeWrite, Consumer<Chunk> afterWrite) throws IOException {
        return replenishColumns(chunk, version, columns, beforeWrite, afterWrite, Long.MAX_VALUE);
    }
    Result replenishColumns(Chunk chunk, String version, int[] columns,
            Consumer<Chunk> beforeWrite, Consumer<Chunk> afterWrite, long deadline) throws IOException {
        return replenishColumns(chunk, version, columns, beforeWrite, afterWrite, deadline, () -> true);
    }
    Result replenishColumns(Chunk chunk, String version, int[] columns,
            Consumer<Chunk> beforeWrite, Consumer<Chunk> afterWrite, long deadline,
            BooleanSupplier stillCurrent) throws IOException {
        if (!winter.get() || !chunk.isLoaded() || !isComplete(chunk, version) || !chunk.getWorld().hasStorm())
            return new Result(0, 0, 0, false);
        Settings config = settings.get();
        if (!config.snow) return new Result(0, 0, 0, false);
        Integer height = chunk.getWorld().getGameRuleValue(GameRules.MAX_SNOW_ACCUMULATION_HEIGHT);
        if (height != null && height <= 0) return new Result(0, 0, 0, false);
        ReplenishmentPolicy policy = new ReplenishmentPolicy(config.snow, config.allExposed,
                config.surfaces, config.excludedBiomes, height == null ? 1 : height);
        return replenishColumns(chunk, version, columns, policy, defaultSnowData(), target -> {
            BlockState state = target.getState(); state.setType(Material.SNOW);
            return new BlockFormEvent(target, state).callEvent();
        }, beforeWrite, afterWrite, deadline, () -> winter.get() && settings.get().snow && stillCurrent.getAsBoolean());
    }
    record ReplenishmentPolicy(boolean enabled, boolean allExposed, Set<Material> surfaces,
            List<String> excludedBiomes, int accumulationHeight) {}
    private record Refill(int column, Block target) {}
    /** The isolated policy/data overload lets regression tests exercise real metadata/write ordering. */
    Result replenishColumns(Chunk chunk, String version, int[] columns, ReplenishmentPolicy policy,
            BlockData snowData, Predicate<Block> permit, Consumer<Chunk> beforeWrite,
            Consumer<Chunk> afterWrite, long deadline) throws IOException {
        return replenishColumns(chunk, version, columns, policy, snowData, permit, beforeWrite,
                afterWrite, deadline, () -> true);
    }
    Result replenishColumns(Chunk chunk, String version, int[] columns, ReplenishmentPolicy policy,
            BlockData snowData, Predicate<Block> permit, Consumer<Chunk> beforeWrite,
            Consumer<Chunk> afterWrite, long deadline, BooleanSupplier stillCurrent) throws IOException {
        Objects.requireNonNull(columns); Objects.requireNonNull(beforeWrite); Objects.requireNonNull(afterWrite);
        if (columns.length > 256) throw new IllegalArgumentException("At most 256 sampled columns per chunk");
        for (int column : columns) if (column < 0 || column > 255) throw new IllegalArgumentException("Invalid snow column");
        if (!chunk.isLoaded() || !isComplete(chunk, version) || !policy.enabled || policy.accumulationHeight <= 0
                || !chunk.getWorld().hasStorm()) return new Result(0, 0, 0, false);
        SnowColumns owned = null; List<Refill> placements = null; BitSet sampled = new BitSet(256);
        World world = chunk.getWorld();
        for (int column : columns) {
            if (System.nanoTime() >= deadline) break;
            if (sampled.get(column)) continue; sampled.set(column);
            Block ground = world.getHighestBlockAt(chunk.getX() * 16 + (column & 15),
                    chunk.getZ() * 16 + (column >> 4), HeightMap.WORLD_SURFACE);
            // Never grow, replace, or claim existing snow, plants, flowers or leaf litter.
            if (ground.getType() == Material.SNOW || ground.getY() + 1 >= world.getMaxHeight()
                    || (!policy.allExposed && !policy.surfaces.contains(ground.getType()))) continue;
            if (!policy.excludedBiomes.isEmpty()) {
                String biome = ground.getBiome().getKey().getKey();
                if (policy.excludedBiomes.stream().anyMatch(biome::contains)) continue;
            }
            Block target = ground.getRelative(BlockFace.UP);
            if (!canReplenish(target, snowData)) continue;
            // Completed winter chunks overwhelmingly contain snow already. Decode the
            // full ownership bitmap only when a valid empty target actually needs it.
            // Unknown/malformed metadata still fails before any formation event or write.
            if (owned == null) owned = read(chunk);
            Integer oldHeight = owned.get(column);
            if (oldHeight != null && oldHeight != target.getY()) {
                Block previous = chunk.getBlock(column & 15, oldHeight, column >> 4);
                // Preserve recoverable ownership; never hide an unresolved snow layer under a new roof.
                if (previous.getType() == Material.SNOW) continue;
            }
            if (!permit.test(target)) continue;
            if (placements == null) placements = new ArrayList<>();
            placements.add(new Refill(column, target));
        }
        if (placements == null) return new Result(0, 0, 0, true);
        // Formation listeners can change other sampled blocks during planning. Recheck the
        // complete batch before claiming ownership, so their replacements remain untouched.
        placements.removeIf(refill -> !canReplenish(refill.target, snowData));
        if (placements.isEmpty()) return new Result(0, 0, 0, true);
        if (!commitAllowed(chunk, version, world, stillCurrent)) return new Result(0, 0, 0, false);
        beforeWrite.accept(chunk); // Durable map intent must precede ownership and physical edits.
        if (!commitAllowed(chunk, version, world, stillCurrent)) {
            afterWrite.accept(chunk); // Retire the already-created intent without claiming or changing snow.
            return new Result(0, 0, 0, false);
        }
        for (Refill refill : placements) owned.put(refill.column, refill.target.getY());
        write(chunk, owned);       // Ownership of the whole batch must precede every snow block.
        int placed = 0;
        try {
            for (Refill refill : placements) { refill.target.setType(Material.SNOW, false); placed++; }
        } finally {
            // Finalize a map revision even if a later block in a batch fails after earlier writes.
            // Durable pre-write intent still survives if finalization itself fails.
            afterWrite.accept(chunk);
        }
        return new Result(placed, 0, 0, true);
    }
    private boolean commitAllowed(Chunk chunk, String version, World world, BooleanSupplier stillCurrent) {
        return chunk.isLoaded() && world.hasStorm() && isComplete(chunk, version) && stillCurrent.getAsBoolean();
    }
    private static boolean canReplenish(Block target, BlockData snowData) {
        Material type = target.getType();
        return (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR)
                && target.getLightFromSky() == 15 && target.getLightFromBlocks() < 10 && target.canPlace(snowData);
    }
    private BlockData defaultSnowData() {
        if (replenishmentSnowData == null) replenishmentSnowData = Material.SNOW.createBlockData();
        return replenishmentSnowData;
    }

    public Result apply(Chunk chunk, Season season, String version, boolean cleanup) throws IOException {
        return applyStep(chunk, season, version, cleanup, 256);
    }
    /** Incremental writes are durable in chunk PDC; a partial chunk is never marked complete. */
    public Result applyStep(Chunk chunk, Season season, String version, boolean cleanup, int columns) throws IOException {
        return applyStep(chunk, season, version, cleanup, columns, () -> {});
    }
    /** Report durable render intent only for batches that will actually edit visible snow. */
    public Result applyStep(Chunk chunk, Season season, String version, boolean cleanup, int columns,
            Runnable beforePhysicalEdit) throws IOException {
        Objects.requireNonNull(beforePhysicalEdit);
        if (columns < 1 || columns > 256) throw new IllegalArgumentException("Columns per step must be 1..256");
        SnowColumns owned = read(chunk);
        var pdc = chunk.getPersistentDataContainer();
        if ((pdc.has(pendingKey) && !pdc.has(pendingKey, PersistentDataType.STRING))
                || (pdc.has(cursorKey) && !pdc.has(cursorKey, PersistentDataType.INTEGER)))
            throw new IOException("Unexpected winter transition metadata type");
        String pending = pdc.get(pendingKey, PersistentDataType.STRING);
        int cursor = pdc.getOrDefault(cursorKey, PersistentDataType.INTEGER, 0);
        if (cursor < 0 || cursor > 256 || (cursor != 0 && pending == null)) throw new IOException("Invalid winter transition cursor");
        boolean winter = !cleanup && season == Season.WINTER && settings.get().snow;
        boolean resume = winter && version.equals(pending);
        if (winter && isComplete(chunk, version)) return new Result(0, 0, 0, true);
        int placed = 0, removed = 0, conflicts = 0;
        boolean physicalIntent = false;
        // Restore previous ownership before placing a new season. Replacement blocks
        // are never overwritten; ambiguous snow retains metadata and blocks cleanup.
        if (!resume) for (var entry : new ArrayList<>(owned.entries().entrySet())) {
            int column = entry.getKey();
            Block block = chunk.getBlock(column & 15, entry.getValue(), column >> 4);
            if (expected(block)) {
                if (!physicalIntent) { beforePhysicalEdit.run(); physicalIntent = true; }
                block.setType(Material.AIR, false); removed++; owned.remove(column);
            }
            else if (block.getType() == Material.SNOW) { conflicts++; }
            else owned.remove(column); // original snow is already gone; leave replacement intact
        }
        if (conflicts > 0) { write(chunk, owned); return new Result(0, removed, conflicts, false); }
        boolean complete = true;
        if (winter) {
            if (!resume) cursor = 0;
            Settings config = settings.get();
            List<Block> placements = new ArrayList<>();
            int end = Math.min(256, cursor + columns);
            for (int step = cursor; step < end; step++) {
                int column = PlayerNeighborhood.columnAt(step, chunk.getX(), chunk.getZ(), version);
                int x = chunk.getX() * 16 + (column & 15), z = chunk.getZ() * 16 + (column >> 4);
                // WORLD_SURFACE includes the highest non-air block, so a glass roof,
                // leaf canopy or occupied vegetation shields everything below it.
                Block ground = chunk.getWorld().getHighestBlockAt(x, z, HeightMap.WORLD_SURFACE);
                if (ground.getType() == Material.SNOW || (!config.allExposed && !config.surfaces.contains(ground.getType()))) continue;
                String biome = ground.getBiome().getKey().getKey();
                if (config.excludedBiomes.stream().anyMatch(biome::contains)) continue;
                if (ground.getY() + 1 >= chunk.getWorld().getMaxHeight()) continue;
                Block target = ground.getRelative(BlockFace.UP);
                if (!target.getType().isAir()) continue;
                if (!target.canPlace(defaultSnowData())) continue;
                owned.put(column, target.getY()); placements.add(target);
            }
            // Record the entire plan once before editing. Saves snapshot this same
            // chunk's blocks and PDC together; no world operation occurs off-thread.
            if (!placements.isEmpty()) {
                if (!physicalIntent) { beforePhysicalEdit.run(); physicalIntent = true; }
                write(chunk, owned);
                for (Block target : placements) { target.setType(Material.SNOW, false); placed++; }
            }
            complete = end == 256;
            if (!complete) {
                pdc.set(pendingKey, PersistentDataType.STRING, version);
                pdc.set(cursorKey, PersistentDataType.INTEGER, end);
            }
        }
        write(chunk, owned);
        if (complete) {
            pdc.remove(pendingKey); pdc.remove(cursorKey);
            if (cleanup) pdc.remove(versionKey);
            else pdc.set(versionKey, PersistentDataType.STRING, version);
        }
        return new Result(placed, removed, conflicts, complete);
    }
    public void relinquish(Block block) {
        try {
            Chunk chunk = block.getChunk(); SnowColumns owned = read(chunk);
            int column = (block.getX() & 15) | ((block.getZ() & 15) << 4);
            if (Objects.equals(owned.get(column), block.getY())) { owned.remove(column); write(chunk, owned); }
        } catch (IOException ignored) { /* Preserve unrecognized ownership for administrator recovery. */ }
    }
    private boolean owns(Block block) {
        try { return Objects.equals(read(block.getChunk()).get((block.getX() & 15) | ((block.getZ() & 15) << 4)), block.getY()); }
        catch (IOException ignored) { return false; }
    }
    private void edited(Block block) {
        relinquish(block);
        // If the support is being moved/destroyed, remove our snow before it can move.
        Block above = block.getRelative(BlockFace.UP);
        if (owns(above) && expected(above)) { above.setType(Material.AIR, false); relinquish(above); }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) { edited(event.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) { edited(event.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMultiPlace(BlockMultiPlaceEvent event) { event.getReplacedBlockStates().forEach(state -> edited(state.getBlock())); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntity(EntityChangeBlockEvent event) { edited(event.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) { edited(event.getBlock()); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void protectWinterSnow(BlockFadeEvent event) {
        if (winter.get() && settings.get().protectSnow && expected(event.getBlock()) && owns(event.getBlock())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (settings.get().protectSnow && event.getBlock().getType() == Material.SNOW && owns(event.getBlock())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent event) { event.getBlocks().forEach(this::edited); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) { event.getBlocks().forEach(this::edited); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplosion(BlockExplodeEvent event) { event.blockList().forEach(this::edited); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) { event.blockList().forEach(this::edited); }
}
