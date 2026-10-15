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
import java.util.function.BiConsumer;
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
    private final NamespacedKey ownedKey, versionKey, pendingKey, cursorKey, localKey;
    private final Supplier<Settings> settings;
    private final Supplier<Boolean> winter;
    private final WinterGroundCover groundCover;
    private BiConsumer<Chunk, SnowColumns> ownershipObserver;
    // Main-thread-only, private default data: canPlace reads it; no caller can mutate it.
    private BlockData replenishmentSnowData;
    public SeasonalSnowService(Plugin plugin, Supplier<Settings> settings) {
        this(plugin, settings, () -> false);
    }
    public SeasonalSnowService(Plugin plugin, Supplier<Settings> settings, Supplier<Boolean> winter) {
        this(plugin, settings, winter, Bukkit::createBlockData);
    }
    SeasonalSnowService(Plugin plugin, Supplier<Settings> settings, Supplier<Boolean> winter,
            java.util.function.Function<String, BlockData> parse) {
        this.settings = settings;
        this.winter = winter;
        groundCover = new WinterGroundCover(plugin, parse);
        ownedKey = new NamespacedKey(plugin, "snow_v1"); versionKey = new NamespacedKey(plugin, "season_version");
        pendingKey = new NamespacedKey(plugin, "winter_pending"); cursorKey = new NamespacedKey(plugin, "winter_cursor");
        localKey = new NamespacedKey(plugin, "winter_local");
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
        return pdc.has(ownedKey) || pdc.has(versionKey) || pdc.has(pendingKey) || pdc.has(cursorKey) || pdc.has(localKey) || groundCover.hasMetadata(chunk);
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
    /** Main-thread-only ownership read; never inspects or loads terrain blocks. */
    public SnowColumns observeOwnership(Chunk chunk) throws IOException { return read(chunk); }
    /** Main-thread-only presentation observer. Null detaches it without changing durable ownership. */
    public void setOwnershipObserver(BiConsumer<Chunk, SnowColumns> observer) { ownershipObserver = observer; }
    void removeOwnershipObserver(BiConsumer<Chunk, SnowColumns> observer) {
        if (ownershipObserver == observer) ownershipObserver = null;
    }
    private void write(Chunk chunk, SnowColumns columns) {
        var pdc = chunk.getPersistentDataContainer();
        if (columns.isEmpty()) pdc.remove(ownedKey);
        else pdc.set(ownedKey, PersistentDataType.BYTE_ARRAY, columns.encode());
        // Publish before physical placements can produce outgoing block packets.
        // Presentation failure must never interrupt the durable world edit it observes.
        if (ownershipObserver != null) try { ownershipObserver.accept(chunk, columns); }
        catch (RuntimeException failure) {
            System.getLogger(SeasonalSnowService.class.getName()).log(System.Logger.Level.WARNING,
                "Could not refresh seasonal snow presentation ownership", failure);
        }
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
                config.surfaces, config.excludedBiomes, height == null ? 1 : height,
                biome -> config.climate.applies(Season.WINTER, biome));
        return replenishColumns(chunk, version, columns, policy, defaultSnowData(), target -> {
            BlockState state = target.getState(); state.setType(Material.SNOW);
            return new BlockFormEvent(target, state).callEvent();
        }, beforeWrite, afterWrite, deadline, () -> winter.get() && settings.get().snow && stillCurrent.getAsBoolean());
    }
    record ReplenishmentPolicy(boolean enabled, boolean allExposed, Set<Material> surfaces,
            List<String> excludedBiomes, int accumulationHeight, Predicate<String> winterBiomes) {
        ReplenishmentPolicy(boolean enabled, boolean allExposed, Set<Material> surfaces,
                List<String> excludedBiomes, int accumulationHeight) {
            this(enabled, allExposed, surfaces, excludedBiomes, accumulationHeight, null);
        }
    }
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
            if (policy.winterBiomes != null && !policy.winterBiomes.test(ground.getBiome().getKey().toString())) continue;
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

    /** Local storm edits are owned, but never certify a region's migration progress. */
    Result accumulateLocalColumns(Chunk chunk, String version, int coverage, int[] columns,
            Consumer<Chunk> beforeWrite, Consumer<Chunk> afterWrite, long deadline,
            BooleanSupplier stillCurrent) throws IOException {
        Settings config = settings.get();
        if (!winter.get() || !config.snow) return new Result(0, 0, 0, false);
        Integer height = chunk.getWorld().getGameRuleValue(GameRules.MAX_SNOW_ACCUMULATION_HEIGHT);
        var policy = new ReplenishmentPolicy(config.snow, config.allExposed, config.surfaces,
            config.excludedBiomes, height == null ? 1 : height,
            biome -> config.climate.applies(Season.WINTER, biome));
        return accumulateLocalColumns(chunk, version, coverage, columns, policy, config.groundCover,
            defaultSnowData(), target -> {
                BlockState state = target.getState(); state.setType(Material.SNOW);
                return new BlockFormEvent(target, state).callEvent();
            }, beforeWrite, afterWrite, deadline,
            () -> winter.get() && settings.get().snow && stillCurrent.getAsBoolean());
    }
    private boolean localCompatible(Chunk chunk, String version) throws IOException {
        var pdc = chunk.getPersistentDataContainer();
        coveredColumns(chunk, version); // Validate transition types and cursor before edits.
        if (pdc.has(localKey) && !pdc.has(localKey, PersistentDataType.STRING))
            throw new IOException("Unexpected local winter metadata type");
        String local = pdc.get(localKey, PersistentDataType.STRING);
        String pending = pdc.get(pendingKey, PersistentDataType.STRING);
        if (pending != null && !version.equals(pending)) return false;
        if (local != null && !version.equals(local)) return false;
        // Unassociated previous winter ownership requires the normal restoration pass.
        return version.equals(local) || version.equals(pending)
            || (!pdc.has(ownedKey) && !groundCover.hasMetadata(chunk));
    }
    private boolean localAllowed(Chunk chunk, String version, BooleanSupplier current) throws IOException {
        return chunk.isLoaded() && chunk.getWorld().hasStorm() && !isComplete(chunk, version)
            && current.getAsBoolean() && localCompatible(chunk, version);
    }
    private static final class LocalAborted extends RuntimeException {}
    /** Isolated terrain overload also exercises local ownership and render-intent ordering. */
    Result accumulateLocalColumns(Chunk chunk, String version, int coverage, int[] columns,
            ReplenishmentPolicy policy, boolean hidePlants, BlockData snowData, Predicate<Block> permit,
            Consumer<Chunk> beforeWrite, Consumer<Chunk> afterWrite, long deadline,
            BooleanSupplier stillCurrent) throws IOException {
        Objects.requireNonNull(version); Objects.requireNonNull(columns);
        if (coverage < 0 || coverage > 256 || columns.length > 256) throw new IllegalArgumentException("Invalid local snow coverage/batch");
        for (int column : columns) if (column < 0 || column > 255) throw new IllegalArgumentException("Invalid snow column");
        if (!policy.enabled || policy.accumulationHeight <= 0 || coverage == 0
                || !localAllowed(chunk, version, stillCurrent)) return new Result(0, 0, 0, false);
        SnowColumns owned = read(chunk);
        WinterGroundCover.State plants = groundCover.read(chunk);
        World world = chunk.getWorld(); List<WinterGroundCover.Surface> surfaces = new ArrayList<>();
        BitSet sampled = new BitSet(256);
        for (int column : columns) {
            if (System.nanoTime() >= deadline) break;
            if (sampled.get(column) || !LocalWinterSnowService.withinCoverage(column, chunk.getX(), chunk.getZ(), version, coverage)) continue;
            sampled.set(column);
            Block ground = world.getHighestBlockAt(chunk.getX() * 16 + (column & 15),
                chunk.getZ() * 16 + (column >> 4), HeightMap.WORLD_SURFACE);
            if (ground.getLightFromSky() != 15 || ground.getLightFromBlocks() >= 10) continue;
            if (!policy.excludedBiomes.isEmpty()
                    && policy.excludedBiomes.stream().anyMatch(ground.getBiome().getKey().getKey()::contains)) continue;
            if (policy.winterBiomes != null && !policy.winterBiomes.test(ground.getBiome().getKey().toString())) continue;
            Integer old = owned.get(column);
            if (old != null && chunk.getBlock(column & 15, old, column >> 4).getType() == Material.SNOW) continue;
            surfaces.add(new WinterGroundCover.Surface(column, ground));
        }
        boolean[] intentCreated = {false};
        Runnable intent = () -> {
            if (intentCreated[0]) return;
            try {
                if (!localAllowed(chunk, version, stillCurrent)) throw new LocalAborted();
                beforeWrite.accept(chunk); intentCreated[0] = true;
                if (!localAllowed(chunk, version, stillCurrent)) throw new LocalAborted();
                chunk.getPersistentDataContainer().set(localKey, PersistentDataType.STRING, version);
            } catch (IOException failure) { throw new UncheckedIOException(failure); }
        };
        int placed = 0, removed = 0;
        try {
            if (hidePlants && System.nanoTime() < deadline)
                removed = groundCover.hideBatch(plants, surfaces, intent);
            List<Refill> placements = new ArrayList<>();
            for (WinterGroundCover.Surface surface : surfaces) {
                if (System.nanoTime() >= deadline || !localAllowed(chunk, version, stillCurrent)) break;
                Block ground = surface.top();
                if (ground.getType() == Material.AIR || ground.getType() == Material.CAVE_AIR || ground.getType() == Material.VOID_AIR)
                    ground = world.getHighestBlockAt(ground.getX(), ground.getZ(), HeightMap.WORLD_SURFACE);
                if (ground.getType() == Material.SNOW || ground.getY() + 1 >= world.getMaxHeight()
                        || (!policy.allExposed && !policy.surfaces.contains(ground.getType()))) continue;
                Block target = ground.getRelative(BlockFace.UP);
                if (canReplenish(target, snowData) && permit.test(target)) placements.add(new Refill(surface.column(), target));
            }
            placements.removeIf(refill -> !canReplenish(refill.target, snowData));
            if (!placements.isEmpty() && localAllowed(chunk, version, stillCurrent)) {
                intent.run();
                for (Refill refill : placements) owned.put(refill.column, refill.target.getY());
                write(chunk, owned);
                for (Refill refill : placements) { refill.target.setType(Material.SNOW, false); placed++; }
            }
        } catch (LocalAborted ignored) { /* Reentrant events may pause or change the season. */ }
        catch (UncheckedIOException failure) { throw failure.getCause(); }
        finally { if (intentCreated[0]) afterWrite.accept(chunk); }
        return new Result(placed, removed, 0, false);
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
        WinterGroundCover.State plants = groundCover.read(chunk); // Validate every backup before any snow or vegetation edit.
        var pdc = chunk.getPersistentDataContainer();
        if ((pdc.has(pendingKey) && !pdc.has(pendingKey, PersistentDataType.STRING))
                || (pdc.has(cursorKey) && !pdc.has(cursorKey, PersistentDataType.INTEGER)))
            throw new IOException("Unexpected winter transition metadata type");
        String pending = pdc.get(pendingKey, PersistentDataType.STRING);
        int cursor = pdc.getOrDefault(cursorKey, PersistentDataType.INTEGER, 0);
        if (cursor < 0 || cursor > 256 || (cursor != 0 && pending == null)) throw new IOException("Invalid winter transition cursor");
        boolean winter = !cleanup && season == Season.WINTER && settings.get().snow;
        if (pdc.has(localKey) && !pdc.has(localKey, PersistentDataType.STRING))
            throw new IOException("Unexpected local winter metadata type");
        boolean resume = winter && (version.equals(pending) || version.equals(pdc.get(localKey, PersistentDataType.STRING)));
        if (winter && isComplete(chunk, version)) return new Result(0, 0, 0, true);
        int placed = 0, removed = 0, conflicts = 0;
        boolean[] physicalIntent = {false};
        Runnable intent = () -> { if (!physicalIntent[0]) { beforePhysicalEdit.run(); physicalIntent[0] = true; } };
        // Restore previous ownership before placing a new season. Replacement blocks
        // are never overwritten; ambiguous snow retains metadata and blocks cleanup.
        if (!resume) for (var entry : new ArrayList<>(owned.entries().entrySet())) {
            int column = entry.getKey();
            Block block = chunk.getBlock(column & 15, entry.getValue(), column >> 4);
            if (expected(block)) {
                intent.run();
                block.setType(Material.AIR, false); removed++; owned.remove(column);
            }
            else if (block.getType() == Material.SNOW) { conflicts++; }
            else owned.remove(column); // original snow is already gone; leave replacement intact
        }
        if (conflicts > 0) { write(chunk, owned); return new Result(0, removed, conflicts, false); }
        Settings config = winter || !plants.columns.isEmpty() ? settings.get() : null;
        var restoration = groundCover.restore(plants, block -> !winter || !config.groundCover
                || !config.climate.applies(Season.WINTER, block.getBiome().getKey().toString()), intent);
        placed += restoration.restored(); conflicts += restoration.conflicts();
        if (conflicts > 0) { write(chunk, owned); return new Result(placed, removed, conflicts, false); }
        boolean complete = true;
        if (winter) {
            if (!version.equals(pending)) cursor = 0; // Local snowfall never advances the region's cursor.
            List<Block> placements = new ArrayList<>();
            List<WinterGroundCover.Surface> surfaces = new ArrayList<>();
            int end = Math.min(256, cursor + columns);
            for (int step = cursor; step < end; step++) {
                int column = PlayerNeighborhood.columnAt(step, chunk.getX(), chunk.getZ(), version);
                int x = chunk.getX() * 16 + (column & 15), z = chunk.getZ() * 16 + (column >> 4);
                // WORLD_SURFACE includes the highest non-air block, so a glass roof,
                // leaf canopy or occupied vegetation shields everything below it.
                Block ground = chunk.getWorld().getHighestBlockAt(x, z, HeightMap.WORLD_SURFACE);
                String biome = ground.getBiome().getKey().getKey();
                if (config.excludedBiomes.stream().anyMatch(biome::contains)) continue;
                if (!config.climate.applies(Season.WINTER, ground.getBiome().getKey().toString())) continue;
                surfaces.add(new WinterGroundCover.Surface(column, ground));
            }
            if (config.groundCover) removed += groundCover.hideBatch(plants, surfaces, intent);
            for (WinterGroundCover.Surface surface : surfaces) {
                int column = surface.column(); Block ground = surface.top();
                // Removed vegetation changes the surface height; ordinary columns need no second height lookup.
                if (ground.getType().isAir()) ground = chunk.getWorld().getHighestBlockAt(ground.getX(), ground.getZ(), HeightMap.WORLD_SURFACE);
                if (ground.getType() == Material.SNOW || (!config.allExposed && !config.surfaces.contains(ground.getType()))) continue;
                if (ground.getY() + 1 >= chunk.getWorld().getMaxHeight()) continue;
                Block target = ground.getRelative(BlockFace.UP);
                if (!target.getType().isAir()) continue;
                if (!target.canPlace(defaultSnowData())) continue;
                owned.put(column, target.getY()); placements.add(target);
            }
            // Record the entire plan once before editing. Saves snapshot this same
            // chunk's blocks and PDC together; no world operation occurs off-thread.
            if (!placements.isEmpty()) {
                intent.run();
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
            pdc.remove(pendingKey); pdc.remove(cursorKey); pdc.remove(localKey);
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
        groundCover.edited(block);
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
    public void onFade(BlockFadeEvent event) {
        // Natural melting is not a player clearing vegetation. Regions can wait hours
        // after the global season changes; retain plant backups until their restoration.
        if (event.getBlock().getType() == Material.SNOW) relinquish(event.getBlock());
        else edited(event.getBlock());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void protectWinterSnow(BlockFadeEvent event) {
        protectSnowFade(event, settings.get().protectSnow);
    }
    void protectSnowFade(BlockFadeEvent event, boolean protect) {
        // Ownership, rather than the global season, determines whether restoration still owes this layer.
        if (protect && expected(event.getBlock()) && owns(event.getBlock())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (settings.get().protectSnow && event.getBlock().getType() == Material.SNOW && owns(event.getBlock())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent event) { event.getBlocks().forEach(block -> pistonEdited(block, event.getDirection())); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) { event.getBlocks().forEach(block -> pistonEdited(block, event.getDirection())); }
    private void pistonEdited(Block block, BlockFace direction) {
        edited(block);
        // Account for destinations conservatively in either movement direction, without loading neighbors.
        for (BlockFace face : new BlockFace[]{direction, direction.getOppositeFace()}) {
            int x = block.getX() + face.getModX(), y = block.getY() + face.getModY(), z = block.getZ() + face.getModZ();
            if (y >= block.getWorld().getMinHeight() && y < block.getWorld().getMaxHeight() && block.getWorld().isChunkLoaded(x >> 4, z >> 4))
                groundCover.edited(block.getWorld().getBlockAt(x, y, z));
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplosion(BlockExplodeEvent event) { event.blockList().forEach(this::edited); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) { event.blockList().forEach(this::edited); }
}
