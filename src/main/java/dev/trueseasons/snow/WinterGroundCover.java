package dev.trueseasons.snow;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Reversible sky-exposed vegetation; snapshots are published before physical edits. */
final class WinterGroundCover {
    private static final Set<String> PLANTS = Set.of("SHORT_GRASS", "TALL_GRASS", "SHORT_DRY_GRASS", "TALL_DRY_GRASS", "FERN", "LARGE_FERN", "DEAD_BUSH",
            "DANDELION", "POPPY", "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET", "RED_TULIP", "ORANGE_TULIP", "WHITE_TULIP",
            "PINK_TULIP", "OXEYE_DAISY", "CORNFLOWER", "LILY_OF_THE_VALLEY", "WITHER_ROSE", "SUNFLOWER", "LILAC",
            "ROSE_BUSH", "PEONY", "PINK_PETALS", "WILDFLOWERS", "OPEN_EYEBLOSSOM", "CLOSED_EYEBLOSSOM", "LEAF_LITTER", "BUSH", "FIREFLY_BUSH",
            "RED_MUSHROOM", "BROWN_MUSHROOM", "TORCHFLOWER", "PITCHER_PLANT");
    private final NamespacedKey key;
    private final Function<String, BlockData> parse;
    WinterGroundCover(Plugin plugin) { this(plugin, Bukkit::createBlockData); }
    WinterGroundCover(Plugin plugin, Function<String, BlockData> parse) {
        key = new NamespacedKey(plugin, "ground_cover_v1"); this.parse = parse;
    }
    static boolean eligible(Material material) { return PLANTS.contains(material.name()); }
    private static boolean air(Material material) { return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR; }
    boolean hasMetadata(Chunk chunk) { return chunk.getPersistentDataContainer().has(key); }
    record Parsed(GroundCoverColumns.Plant snapshot, BlockData lower, BlockData upper, BlockData support) {}
    final class State {
        final Chunk chunk; final GroundCoverColumns columns; final Map<Integer, Parsed> parsed = new HashMap<>();
        State(Chunk chunk, GroundCoverColumns columns) { this.chunk = chunk; this.columns = columns; }
    }
    State read(Chunk chunk) throws IOException {
        var pdc = chunk.getPersistentDataContainer();
        if (pdc.has(key) && !pdc.has(key, PersistentDataType.BYTE_ARRAY)) throw new IOException("Unexpected plant metadata type");
        State state = new State(chunk, GroundCoverColumns.decode(pdc.get(key, PersistentDataType.BYTE_ARRAY)));
        for (var row : state.columns.entries().entrySet()) {
            var p = row.getValue(); int min = chunk.getWorld().getMinHeight(), max = chunk.getWorld().getMaxHeight();
            if (p.y() <= min || p.y() >= max || p.upper() != null && p.y() + 1 >= max)
                throw new IOException("Plant snapshot outside world height");
            try {
                BlockData lower = parse.apply(p.lower()), upper = p.upper() == null ? null : parse.apply(p.upper());
                BlockData support = parse.apply(p.support());
                if (!eligible(lower.getMaterial()) || air(support.getMaterial())) throw new IllegalArgumentException("Invalid plant/support");
                if (upper == null && lower instanceof Bisected || upper != null && (!(lower instanceof Bisected l)
                        || !(upper instanceof Bisected u) || l.getHalf() != Bisected.Half.BOTTOM || u.getHalf() != Bisected.Half.TOP
                        || lower.getMaterial() != upper.getMaterial())) throw new IllegalArgumentException("Invalid paired plant");
                state.parsed.put(row.getKey(), new Parsed(p, lower, upper, support));
            } catch (IllegalArgumentException ex) { throw new IOException("Unrecognized plant snapshot", ex); }
        }
        return state;
    }
    private void write(State state) {
        var pdc = state.chunk.getPersistentDataContainer();
        if (state.columns.isEmpty()) pdc.remove(key); else pdc.set(key, PersistentDataType.BYTE_ARRAY, state.columns.encode());
    }
    record Surface(int column, Block top) {}
    private record Removal(int column, Parsed plant, List<Block> blocks) {}
    /** Publish one complete bounded snapshot batch before any vegetation block is removed. */
    int hideBatch(State state, List<Surface> surfaces, Runnable beforeEdit) {
        List<Removal> removals = new ArrayList<>(); Set<Integer> seen = new HashSet<>();
        for (Surface surface : surfaces) if (seen.add(surface.column)) {
            Removal plan = plan(state, surface.column, surface.top); if (plan != null) removals.add(plan);
        }
        if (removals.isEmpty()) return 0;
        beforeEdit.run(); // A failed journal leaves both snapshots and all plants untouched.
        for (Removal plan : removals) {
            state.columns.put(plan.column, plan.plant.snapshot); state.parsed.put(plan.column, plan.plant);
        }
        write(state); int removed = 0;
        for (Removal plan : removals) for (Block block : plan.blocks) { block.setType(Material.AIR, false); removed++; }
        return removed;
    }
    int hide(State state, int column, Block top, Runnable beforeEdit) {
        return hideBatch(state, List.of(new Surface(column, top)), beforeEdit);
    }
    /** top is WORLD_SURFACE, never a search beneath a roof or leaf canopy. */
    private Removal plan(State state, int column, Block top) {
        Parsed previous = state.parsed.get(column);
        if (previous != null) {
            if (!eligible(top.getType())) return null; // A new roof/canopy still shields any unfinished removal.
            Block lower = state.chunk.getBlock(column & 15, previous.snapshot.y(), column >> 4);
            Block upper = previous.upper == null ? null : lower.getRelative(BlockFace.UP);
            if (occupied(lower, previous.lower) && lower.getType() != Material.SNOW
                    || upper != null && occupied(upper, previous.upper) && upper.getType() != Material.SNOW) return null;
            List<Block> blocks = new ArrayList<>(2);
            if (upper != null && upper.getBlockData().getAsString().equals(previous.upper.getAsString())) {
                blocks.add(upper);
            }
            if (lower.getBlockData().getAsString().equals(previous.lower.getAsString())) {
                blocks.add(lower);
            }
            return blocks.isEmpty() ? null : new Removal(column, previous, blocks);
        }
        if (!eligible(top.getType())) return null;
        Block lower = top, upper = null; BlockData data = top.getBlockData();
        if (data instanceof Bisected half) {
            if (half.getHalf() != Bisected.Half.TOP) return null;
            upper = top; lower = top.getRelative(BlockFace.DOWN);
            if (!(lower.getBlockData() instanceof Bisected bottom) || bottom.getHalf() != Bisected.Half.BOTTOM
                    || lower.getType() != top.getType()) return null;
        }
        Block support = lower.getRelative(BlockFace.DOWN);
        if (support.getY() < state.chunk.getWorld().getMinHeight() || air(support.getType())) return null;
        // Preserve unusual/world-edited vegetation that could not safely be restored even now.
        // This check applies only to NEW snapshots; unfinished durable plans remain recoverable.
        if (!lower.canPlace(lower.getBlockData()) || upper != null && !upper.canPlace(upper.getBlockData())) return null;
        var snapshot = new GroundCoverColumns.Plant(lower.getY(), lower.getBlockData().getAsString(),
                upper == null ? null : upper.getBlockData().getAsString(), support.getBlockData().getAsString());
        Parsed parsed = new Parsed(snapshot, lower.getBlockData(), upper == null ? null : upper.getBlockData(), support.getBlockData());
        return new Removal(column, parsed, upper == null ? List.of(lower) : List.of(upper, lower));
    }
    record Restoration(int restored, int conflicts) {}
    Restoration restore(State state, Predicate<Block> allowed, Runnable beforeEdit) {
        int restored = 0, conflicts = 0;
        try { for (var row : new ArrayList<>(state.columns.entries().entrySet())) {
            int column = row.getKey(); Parsed plant = state.parsed.get(column);
            Block lower = state.chunk.getBlock(column & 15, plant.snapshot.y(), column >> 4);
            if (!allowed.test(lower)) continue;
            Block upper = plant.upper == null ? null : lower.getRelative(BlockFace.UP);
            // A replacement wins. Snow remains recoverable until its owner removes it safely.
            if (occupied(lower, plant.lower) || upper != null && occupied(upper, plant.upper)) {
                if (lower.getType() == Material.SNOW || upper != null && upper.getType() == Material.SNOW) { conflicts++; continue; }
                state.columns.remove(column); continue;
            }
            if (!sameSupport(lower.getRelative(BlockFace.DOWN).getBlockData(), plant.support)
                    || air(lower.getType()) && !lower.canPlace(plant.lower)) { conflicts++; continue; }
            if (air(lower.getType()) || upper != null && air(upper.getType())) {
                beforeEdit.run();
                // Keep snapshot until BOTH halves are restored: partial write/restart is idempotent.
                if (air(lower.getType())) { lower.setBlockData(plant.lower, false); restored++; }
                if (upper != null && air(upper.getType())) { upper.setBlockData(plant.upper, false); restored++; }
            }
            state.columns.remove(column);
        } } finally { write(state); }
        return new Restoration(restored, conflicts);
    }
    private static boolean occupied(Block block, BlockData original) {
        return !air(block.getType()) && !block.getBlockData().getAsString().equals(original.getAsString());
    }
    private static boolean sameSupport(BlockData actual, BlockData original) {
        // Grass/mycelium can spread onto dirt while a plant is hidden. Keep the
        // current ground and let canPlace validate the plant instead of blocking
        // an entire region on this natural change. Other supports stay exact.
        if (naturalSoil(actual.getMaterial()) && naturalSoil(original.getMaterial())
                && actual.getMaterial() != original.getMaterial()) return true;
        if (actual instanceof Snowable a && original instanceof Snowable o) {
            actual = a.clone(); original = o.clone(); ((Snowable) actual).setSnowy(false); ((Snowable) original).setSnowy(false);
        }
        return actual.getAsString().equals(original.getAsString());
    }
    private static boolean naturalSoil(Material material) {
        return material == Material.DIRT || material == Material.GRASS_BLOCK || material == Material.MYCELIUM;
    }
    void edited(Block block) {
        Chunk chunk = block.getChunk(); if (!hasMetadata(chunk)) return;
        try {
            var pdc = chunk.getPersistentDataContainer();
            if (!pdc.has(key, PersistentDataType.BYTE_ARRAY)) return;
            // Player edits require only the sparse coordinate/height lookup, never reconstruction
            // of every saved BlockData in a vegetated chunk. Unknown other snapshots stay verbatim.
            State state = new State(chunk, GroundCoverColumns.decode(pdc.get(key, PersistentDataType.BYTE_ARRAY)));
            int column = (block.getX() & 15) | ((block.getZ() & 15) << 4);
            var plant = state.columns.get(column);
            if (plant != null && block.getY() >= plant.y() - 1 && block.getY() <= plant.y() + (plant.upper() == null ? 0 : 1)) {
                state.columns.remove(column); write(state);
            }
        } catch (IOException ignored) { /* Keep unknown backups for administrator recovery. */ }
    }
}
