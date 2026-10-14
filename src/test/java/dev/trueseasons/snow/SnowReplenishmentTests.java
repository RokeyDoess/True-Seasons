package dev.trueseasons.snow;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.type.Snow;
import org.bukkit.block.data.BlockData;
import org.bukkit.persistence.*;
import org.bukkit.plugin.Plugin;

/** Deterministic terrain/metadata fixtures: forbidden chunk-load APIs and incorrect write ordering throw. */
public final class SnowReplenishmentTests {
    private static int checks;
    private static final String VERSION = "finished-winter";
    private static final SeasonalSnowService.ReplenishmentPolicy DEFAULT = new SeasonalSnowService.ReplenishmentPolicy(true, true, Set.of(), List.of(), 1);
    record Value(PersistentDataType<?, ?> type, Object value) {}
    private static <T> T proxy(Class<T> type, InvocationHandler handler) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler)); }
    private static Object identity(Object proxy, String name, Object[] args) {
        return switch (name) { case "equals" -> proxy == args[0]; case "hashCode" -> System.identityHashCode(proxy); case "toString" -> "SnowRefillFixture"; default -> throw new AssertionError("Unexpected API: " + name); };
    }
    static final class Column {
        Material support = Material.STONE, target = Material.AIR;
        int height = 64, sky = 15, light, layers = 1;
        boolean survives = true;
        Block ground, above;
    }
    static final class Fixture {
        final Map<NamespacedKey, Value> metadata = new HashMap<>();
        final Map<Integer, Column> columns = new HashMap<>();
        final List<String> order = new ArrayList<>();
        final Plugin plugin = proxy(Plugin.class, (p, m, a) -> switch (m.getName()) { case "getName" -> "TrueSeasons"; case "namespace" -> "trueseasons"; default -> identity(p, m.getName(), a); });
        final NamespacedKey owned = new NamespacedKey(plugin, "snow_v1"), version = new NamespacedKey(plugin, "season_version"), pending = new NamespacedKey(plugin, "winter_pending");
        final Snow snowData = proxy(Snow.class, (p, m, a) -> m.getName().equals("getLayers") ? 1 : identity(p, m.getName(), a));
        final SeasonalSnowService snow = new SeasonalSnowService(plugin, () -> { throw new AssertionError("Isolated column policy should not read plugin settings"); });
        boolean loaded = true, raining = true, allowed = true;
        Consumer<Block> permitMutation = target -> {};
        Runnable beforeMutation = () -> {};
        BooleanSupplier stillCurrent = () -> true;
        int failColumn = -1;
        int maxHeight = 320, heightReads, ownershipReads, ownershipTouches, formationCalls, before, after;
        Block oldSnow;
        final PersistentDataContainer pdc;
        final World world;
        final Chunk chunk;
        Fixture() {
            metadata.put(version, new Value(PersistentDataType.STRING, VERSION));
            pdc = proxy(PersistentDataContainer.class, (p, m, a) -> switch (m.getName()) {
                case "has" -> { if (a[0].equals(owned)) ownershipTouches++; Value v = metadata.get(a[0]); yield v != null && (a.length == 1 || v.type.getPrimitiveType().equals(((PersistentDataType<?, ?>) a[1]).getPrimitiveType())); }
                case "get", "getOrDefault" -> { if (a[0].equals(owned)) { ownershipReads++; ownershipTouches++; } Value v = metadata.get(a[0]); yield v == null ? (a.length == 3 ? a[2] : null) : v.value; }
                case "set" -> { if (a[0].equals(owned)) order.add("ownership"); metadata.put((NamespacedKey) a[0], new Value((PersistentDataType<?, ?>) a[1], a[2])); yield null; }
                case "remove" -> { metadata.remove(a[0]); yield null; }
                default -> identity(p, m.getName(), a);
            });
            world = proxy(World.class, (p, m, a) -> switch (m.getName()) {
                case "hasStorm" -> raining; case "getMinHeight" -> -64; case "getMaxHeight" -> maxHeight;
                case "getHighestBlockAt" -> {
                    check(loaded, "No height lookup on unloaded terrain"); check(a[2] == HeightMap.WORLD_SURFACE, "Occupying plants and roofs are included in exposure"); heightReads++;
                    yield column(((int) a[0] & 15) | (((int) a[1] & 15) << 4)).ground;
                }
                default -> identity(p, m.getName(), a);
            });
            chunk = proxy(Chunk.class, (p, m, a) -> switch (m.getName()) {
                case "isLoaded" -> loaded; case "getWorld" -> world; case "getX" -> 4; case "getZ" -> -3; case "getPersistentDataContainer" -> pdc;
                case "getBlock" -> { Column c = column((int) a[0] | ((int) a[2] << 4)); yield oldSnow != null && (int) a[1] < c.height ? oldSnow : (int) a[1] == c.height + 1 ? c.above : c.ground; }
                default -> identity(p, m.getName(), a);
            });
        }
        Column column(int id) {
            return columns.computeIfAbsent(id, ignored -> {
                Column c = new Column();
                c.ground = proxy(Block.class, (p, m, a) -> switch (m.getName()) {
                    case "getType" -> c.support; case "getY" -> c.height; case "getRelative" -> { check(a[0] == BlockFace.UP, "Only the exposed surface above is sampled"); yield c.above; }
                    case "getLightFromSky" -> (byte) c.sky; case "getLightFromBlocks" -> (byte) c.light;
                    default -> identity(p, m.getName(), a);
                });
                c.above = proxy(Block.class, (p, m, a) -> switch (m.getName()) {
                    case "getType" -> c.target; case "getY" -> c.height + 1; case "getLightFromSky" -> (byte) c.sky; case "getLightFromBlocks" -> (byte) c.light;
                    case "getBlockData" -> c.target == Material.SNOW
                            ? proxy(Snow.class, (data, method, values) -> method.getName().equals("getLayers") ? c.layers : identity(data, method.getName(), values))
                            : proxy(BlockData.class, (data, method, values) -> method.getName().equals("getMaterial") ? c.target : identity(data, method.getName(), values));
                    case "canPlace" -> { check(a[0] == snowData, "Vanilla snow support check uses the actual planned snow data"); yield c.survives; }
                    case "setType" -> {
                        if (id == failColumn) throw new IllegalStateException("Simulated block write failure");
                        if (a[0] == Material.AIR) {
                            check(before == 1 && after == 0, "Render intent precedes actual migration snow removal");
                            order.add("remove"); c.target = Material.AIR; yield null;
                        }
                        check(a[0] == Material.SNOW && a[1].equals(false), "Replenishment writes exactly one snow layer without physics");
                        check(before == 1 && after == 0, "Map intent precedes writes and final revision follows them");
                        Value value = metadata.get(owned);
                        check(value != null && Objects.equals(SnowColumns.decode((byte[]) value.value).get(id), c.height + 1), "Durable ownership precedes every physical block");
                        order.add("block"); c.target = Material.SNOW; yield null;
                    }
                    default -> identity(p, m.getName(), a);
                });
                return c;
            });
        }
        SeasonalSnowService.Result refill(int... samples) throws IOException { return refill(DEFAULT, Long.MAX_VALUE, samples); }
        SeasonalSnowService.Result refill(SeasonalSnowService.ReplenishmentPolicy policy, long deadline, int... samples) throws IOException {
            return snow.replenishColumns(chunk, VERSION, samples, policy, snowData, target -> { formationCalls++; permitMutation.accept(target); return allowed; },
                c -> { before++; order.add("intent"); beforeMutation.run(); }, c -> { after++; order.add("revision"); }, deadline, stillCurrent);
        }
    }
    public static void main(String[] args) throws Exception {
        Fixture f = new Fixture();
        var result = f.refill(0, 0, 1);
        check(result.placed() == 2 && f.heightReads == 2, "Batched duplicate samples place each exposed column once");
        check(f.order.equals(List.of("intent", "ownership", "block", "block", "revision")), "Map intent, ownership, blocks and final map revision have durable order");
        check(f.snow.isComplete(f.chunk, VERSION) && !f.snow.hasPending(f.chunk), "Refill does not reopen a migration or change its completion version");
        f = new Fixture(); f.loaded = false; check(f.refill(0).placed() == 0 && f.heightReads == 0, "Unloaded chunks are skipped without terrain or load access");
        f = new Fixture(); f.raining = false; check(f.refill(0).placed() == 0 && f.heightReads == 0, "Clear weather never replenishes snow");
        f = new Fixture(); f.metadata.put(f.version, new Value(PersistentDataType.STRING, "stale")); check(f.refill(0).placed() == 0 && f.heightReads == 0, "Stale migration versions cannot replenish");
        f = new Fixture(); f.metadata.put(f.pending, new Value(PersistentDataType.STRING, VERSION)); check(f.refill(0).placed() == 0 && f.heightReads == 0, "Pending migration cannot replenish even when the saved version matches");
        f = new Fixture(); check(f.refill(new SeasonalSnowService.ReplenishmentPolicy(true, true, Set.of(), List.of(), 0), Long.MAX_VALUE, 0).placed() == 0, "Vanilla snow accumulation gamerule zero is respected");
        f = new Fixture(); check(f.refill(new SeasonalSnowService.ReplenishmentPolicy(false, true, Set.of(), List.of(), 1), Long.MAX_VALUE, 0).placed() == 0, "Disabled physical snow cannot replenish");
        f = new Fixture(); f.column(0).light = 10; check(f.refill(0).placed() == 0 && f.before == 0, "Block light 10 prevents placement and map writes");
        f = new Fixture(); f.column(0).light = 9; check(f.refill(0).placed() == 1, "Vanilla block light threshold permits light 9");
        f = new Fixture(); f.column(0).sky = 14; check(f.refill(0).placed() == 0, "Sheltered skylight prevents snow");
        f = new Fixture(); f.column(0).survives = false; check(f.refill(0).placed() == 0, "Unsupported surfaces retain their blocks");
        f = new Fixture(); f.column(0).support = Material.SNOW; f.column(0).layers = 5; check(f.refill(0).placed() == 0 && !f.metadata.containsKey(f.owned), "Existing multilayer snow is neither grown nor claimed");
        for (Material plant : List.of(Material.SHORT_GRASS, Material.DANDELION, Material.LEAF_LITTER)) {
            f = new Fixture(); f.column(0).support = plant; f.column(0).survives = false;
            check(f.refill(0).placed() == 0 && f.column(0).support == plant && !f.metadata.containsKey(f.owned), "Ground cover is preserved: " + plant);
        }
        f = new Fixture(); f.column(0).target = Material.DIAMOND_BLOCK; check(f.refill(0).placed() == 0 && f.column(0).target == Material.DIAMOND_BLOCK, "Occupied targets and player replacements remain intact");
        f = new Fixture(); f.column(0).height = 319; check(f.refill(0).placed() == 0, "Build ceiling prevents placement outside the world");
        f = new Fixture(); f.allowed = false; check(f.refill(0).placed() == 0 && f.before == 0, "Cancelled vanilla formation makes no ownership, terrain or map changes");
        f = new Fixture(); check(f.refill(DEFAULT, 0L, 0).placed() == 0 && f.heightReads == 0, "Expired work budget makes no terrain lookup or block changes");
        f = new Fixture(); check(f.refill(new SeasonalSnowService.ReplenishmentPolicy(true, false, Set.of(Material.GRASS_BLOCK), List.of(), 1), Long.MAX_VALUE, 0).placed() == 0, "Configured surface whitelist remains respected");
        f = new Fixture(); Fixture changed = f;
        f.permitMutation = target -> changed.column(0).target = Material.DIAMOND_BLOCK;
        check(f.refill(0).placed() == 0 && f.column(0).target == Material.DIAMOND_BLOCK && f.before == 0 && !f.metadata.containsKey(f.owned), "Formation listeners' replacement blocks are rechecked before ownership or writes");
        f = new Fixture(); Fixture interrupted = f; f.failColumn = 1;
        try { f.refill(0, 1); throw new AssertionError("Expected second block write failure"); }
        catch (IllegalStateException expected) {
            check(interrupted.column(0).target == Material.SNOW && interrupted.column(1).target == Material.AIR && interrupted.after == 1, "Partially successful write batch still finalizes its map revision");
        }
        f = new Fixture(); Fixture seasonChanged = f; boolean[] winter = {true};
        f.stillCurrent = () -> winter[0]; f.permitMutation = target -> winter[0] = false;
        check(f.refill(0).placed() == 0 && f.before == 0 && f.after == 0 && !f.metadata.containsKey(f.owned) && seasonChanged.column(0).target == Material.AIR,
                "Season/cleanup change inside formation event aborts before intent, ownership or blocks");
        f = new Fixture(); Fixture weatherChanged = f; f.permitMutation = target -> weatherChanged.raining = false;
        check(f.refill(0).placed() == 0 && f.before == 0 && f.after == 0 && !f.metadata.containsKey(f.owned) && f.column(0).target == Material.AIR,
                "Clear weather chosen inside formation event aborts before all writes");
        f = new Fixture(); Fixture versionChanged = f;
        f.permitMutation = target -> versionChanged.metadata.put(versionChanged.version, new Value(PersistentDataType.STRING, "next-season"));
        check(f.refill(0).placed() == 0 && f.before == 0 && f.after == 0 && !f.metadata.containsKey(f.owned) && f.column(0).target == Material.AIR,
                "Changed completion metadata inside formation event aborts before all writes");
        f = new Fixture(); Fixture lateChanged = f; boolean[] active = {true};
        f.stillCurrent = () -> active[0]; f.beforeMutation = () -> active[0] = false;
        check(f.refill(0).placed() == 0 && f.before == 1 && f.after == 1 && !f.metadata.containsKey(f.owned) && lateChanged.column(0).target == Material.AIR,
                "Change during map-intent callback retires that intent without claiming or writing snow");
        byte[] malformed = new byte[]{9, 4, 3};
        f = new Fixture(); f.metadata.put(f.owned, new Value(PersistentDataType.BYTE_ARRAY, malformed));
        f.column(0).support = Material.SNOW; f.column(1).light = 10; f.column(2).survives = false;
        check(f.refill(0, 1, 2).placed() == 0 && f.ownershipTouches == 0 && f.formationCalls == 0 && f.order.isEmpty(),
                "Already snowy and unsuitable samples never read/decode ownership, plan events, serialize or write");
        check(f.metadata.get(f.owned).value == malformed, "A no-op sample preserves malformed ownership bytes untouched");
        f = new Fixture(); f.metadata.put(f.owned, new Value(PersistentDataType.BYTE_ARRAY, malformed));
        try { f.refill(0); throw new AssertionError("Eligible target must reject unknown ownership schema"); }
        catch (IOException expected) {
            check(f.ownershipReads == 1 && f.formationCalls == 0 && f.order.isEmpty() && f.column(0).target == Material.AIR,
                    "Eligible malformed ownership fails once before formation events, intent or physical changes");
            check(f.metadata.get(f.owned).value == malformed, "Rejected ownership bytes are never rewritten or discarded");
        }
        f = new Fixture(); f.metadata.put(f.owned, new Value(PersistentDataType.STRING, "unknown"));
        f.column(0).support = Material.SNOW;
        check(f.refill(0).placed() == 0 && f.ownershipTouches == 0, "A no-op sample does not touch malformed ownership type");
        f.column(0).support = Material.STONE;
        try { f.refill(0); throw new AssertionError("Eligible target must reject wrong ownership type"); }
        catch (IOException expected) { check(f.formationCalls == 0 && f.before == 0 && f.after == 0, "Eligible wrong ownership type fails safely before any event or writes"); }
        f = new Fixture(); f.refill(0, 1);
        check(f.ownershipReads == 1, "A useful multi-column batch decodes ownership exactly once");
        f = new Fixture(); SnowColumns held = new SnowColumns(); held.put(0, 63);
        byte[] heldBytes = held.encode(); f.metadata.put(f.owned, new Value(PersistentDataType.BYTE_ARRAY, heldBytes));
        f.oldSnow = proxy(Block.class, (p, m, a) -> m.getName().equals("getType") ? Material.SNOW : identity(p, m.getName(), a));
        check(f.refill(0).placed() == 0 && f.formationCalls == 0 && f.before == 0 && f.metadata.get(f.owned).value == heldBytes,
                "Lazy decoding still preserves owned snow beneath a new roof and blocks ambiguous new ownership");
        System.out.println("Snow replenishment tests passed: " + checks + " assertions");
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
