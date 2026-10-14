package dev.trueseasons.snow;

import dev.trueseasons.api.Season;
import dev.trueseasons.migration.PlayerNeighborhood;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.persistence.PersistentDataType;

/** Local storm must own edits without advancing or completing region migration. */
public final class LocalWinterSnowTests {
    private static int checks;
    private static final String VERSION = "storm-winter";
    private static final SeasonalSnowService.ReplenishmentPolicy POLICY =
        new SeasonalSnowService.ReplenishmentPolicy(true, true, Set.of(), List.of(), 1);
    private static int column(int rank) { return PlayerNeighborhood.columnAt(rank, 4, -3, VERSION); }
    private static SnowReplenishmentTests.Fixture fixture() {
        var f = new SnowReplenishmentTests.Fixture();
        f.metadata.put(f.version, new SnowReplenishmentTests.Value(PersistentDataType.STRING, "spring"));
        return f;
    }
    private static SeasonalSnowService.Result local(SnowReplenishmentTests.Fixture f, int coverage,
            long deadline, int... columns) throws IOException {
        return f.snow.accumulateLocalColumns(f.chunk, VERSION, coverage, columns, POLICY, false, f.snowData,
            target -> { f.formationCalls++; f.permitMutation.accept(target); return f.allowed; },
            chunk -> { f.before++; f.order.add("intent"); f.beforeMutation.run(); },
            chunk -> { f.after++; f.order.add("revision"); }, deadline, f.stillCurrent);
    }
    public static void main(String[] args) throws Exception {
        for (String salt : List.of(VERSION, "second-winter")) for (int cap : List.of(0, 1, 16, 127, 256)) {
            Set<Integer> enabled = new HashSet<>();
            for (int c = 0; c < 256; c++) if (LocalWinterSnowService.withinCoverage(c, -11, 8, salt, cap)) enabled.add(c);
            check(enabled.size() == cap, "Every percentage allows exactly the shared number of permuted columns");
            for (int rank = 0; rank < 256; rank++) check(enabled.contains(PlayerNeighborhood.columnAt(rank, -11, 8, salt)) == (rank < cap),
                "Local cap follows the migration permutation and is monotonic");
        }
        var f = fixture(); var result = local(f, 16, Long.MAX_VALUE, column(0), column(0), column(15), column(16));
        check(result.placed() == 2 && !result.complete() && f.heightReads == 2, "Local placement deduplicates samples and enforces coverage before terrain access");
        check(f.order.equals(List.of("intent", "ownership", "block", "block", "revision")), "Local map intent and batch ownership precede terrain edits");
        check(f.snow.version(f.chunk).equals("spring") && !f.snow.hasPending(f.chunk)
            && f.snow.coveredColumns(f.chunk, VERSION) == 0 && !f.snow.isComplete(f.chunk, VERSION),
            "Local snowfall never changes version, pending cursor, coverage bookkeeping or completion");
        var localKey = new NamespacedKey(f.plugin, "winter_local");
        check(f.metadata.get(localKey).value().equals(VERSION), "Same-version ownership is associated with a separate local marker");
        // A second snow-only local batch may refill a player-cleared column through the service grace gate.
        f.column(column(0)).target = Material.AIR; f.before = f.after = 0; f.order.clear();
        check(local(f, 16, Long.MAX_VALUE, column(0)).placed() == 1, "Local marker permits repeated same-version bounded work");
        f.before = f.after = 0; f.order.clear();
        var restoration = f;
        result = f.snow.applyStep(f.chunk, Season.SPRING, "next-spring", false, 256,
            () -> { restoration.before++; restoration.order.add("intent"); });
        check(result.removed() == 2 && result.complete() && !f.metadata.containsKey(localKey), "Later season restores locally owned snow and retires the local marker");

        f = fixture(); f.loaded = false; check(local(f, 16, Long.MAX_VALUE, column(0)).placed() == 0 && f.heightReads == 0, "Unloaded terrain is never read or loaded");
        f = fixture(); f.raining = false; check(local(f, 16, Long.MAX_VALUE, column(0)).placed() == 0 && f.heightReads == 0, "No storm means no local edits");
        f = fixture(); check(local(f, 0, Long.MAX_VALUE, column(0)).placed() == 0 && f.heightReads == 0, "Zero shared coverage forbids physical edits");
        f = fixture(); check(local(f, 16, 0, column(0)).placed() == 0 && f.heightReads == 0, "Expired tick budget stops before terrain queries");
        f = fixture(); f.allowed = false; check(local(f, 16, Long.MAX_VALUE, column(0)).placed() == 0 && f.before == 0 && !f.metadata.containsKey(localKey), "Cancelled formation creates no intent or ownership");
        f = fixture(); f.metadata.put(f.version, new SnowReplenishmentTests.Value(PersistentDataType.STRING, VERSION));
        check(local(f, 256, Long.MAX_VALUE, column(0)).placed() == 0 && f.heightReads == 0, "Fully migrated chunks remain the existing replenisher's responsibility");
        f = fixture(); f.metadata.put(localKey, new SnowReplenishmentTests.Value(PersistentDataType.STRING, "previous-winter"));
        check(local(f, 16, Long.MAX_VALUE, column(0)).placed() == 0 && f.heightReads == 0, "A different local winter waits for ordered restoration");
        f = fixture(); SnowColumns previous = new SnowColumns(); previous.put(column(0), 65);
        f.metadata.put(f.owned, new SnowReplenishmentTests.Value(PersistentDataType.BYTE_ARRAY, previous.encode()));
        check(local(f, 16, Long.MAX_VALUE, column(0)).placed() == 0 && f.before == 0, "Unassociated old ownership is never relabeled as current local snow");
        f = fixture(); f.metadata.put(f.pending, new SnowReplenishmentTests.Value(PersistentDataType.STRING, VERSION));
        check(local(f, 16, Long.MAX_VALUE, column(0)).placed() == 1 && f.snow.coveredColumns(f.chunk, VERSION) == 0, "Local work can accompany the region's current pending version without advancing its cursor");
        f = fixture(); var changed = f; f.permitMutation = target -> changed.raining = false;
        check(local(f, 16, Long.MAX_VALUE, column(0)).placed() == 0 && f.before == 0, "Weather change during formation stops intent and ownership");
        f = fixture(); boolean[] active = {true}; f.stillCurrent = () -> active[0]; f.beforeMutation = () -> active[0] = false;
        check(local(f, 16, Long.MAX_VALUE, column(0)).placed() == 0 && f.before == 1 && f.after == 1 && !f.metadata.containsKey(localKey), "A pause during intent retires the callback without local ownership or snow");
        f = fixture(); f.metadata.put(localKey, new SnowReplenishmentTests.Value(PersistentDataType.INTEGER, 1));
        try { local(f, 16, Long.MAX_VALUE, column(0)); throw new AssertionError("Expected malformed local marker failure"); }
        catch (IOException expected) { check(f.before == 0 && f.heightReads == 0, "Malformed local metadata fails before all physical edits"); }
        f = fixture(); local(f, 16, Long.MAX_VALUE, column(0)); f.before = f.after = 0; f.order.clear();
        var merged = f;
        try {
            f.snow.applyStep(f.chunk, Season.WINTER, VERSION, false, 16,
                () -> { merged.before++; merged.order.add("intent"); });
            throw new AssertionError("Fixture must stop at its isolated settings boundary");
        } catch (AssertionError settingsBoundary) {
            check(settingsBoundary.getMessage().contains("Isolated column policy"), "Merge test reaches the background winter settings stage");
            check(f.column(column(0)).target == Material.SNOW && f.before == 0 && f.order.isEmpty(),
                "The first background winter pass preserves same-version local snow instead of removing it");
            check(!f.snow.hasPending(f.chunk) && f.snow.coveredColumns(f.chunk, VERSION) == 0,
                "Local ownership alone does not resume or fabricate the background cursor");
        }
        groundCover(); nativeGuard();
        System.out.println("Local winter snow tests passed: " + checks + " assertions");
    }
    private static void groundCover() throws Exception {
        var f = new WinterGroundCoverTests.Fixture("minecraft:poppy");
        var snow = new SeasonalSnowService(f.plugin, () -> null,
            () -> true, WinterGroundCoverTests::data);
        var result = snow.accumulateLocalColumns(f.chunk, VERSION, 256, new int[]{0}, POLICY, true,
            WinterGroundCoverTests.data("minecraft:snow[layers=1]"), block -> true,
            chunk -> { f.intent++; f.order.add("intent"); }, chunk -> f.order.add("deferred"), Long.MAX_VALUE, () -> true);
        check(result.placed() == 1 && result.removed() == 1 && !result.complete(), "Local winter hides exposed flowers and covers their support with one owned layer");
        check(f.at(65).contains("snow") && f.cover.hasMetadata(f.chunk) && f.intent == 1, "A single durable intent covers plant hiding and snow placement");
        var owned = new NamespacedKey(f.plugin,"snow_v1");
        check(SnowColumns.decode((byte[])f.metadata.get(owned).value()).get(0) == 65, "Locally covering a flower retains snow ownership at its original height");
        check(snow.version(f.chunk) == null && !snow.hasPending(f.chunk), "Plant hiding still leaves the region entirely unprocessed");
        f.order.clear();
        result = snow.applyStep(f.chunk, Season.SPRING, "spring", false, 256,
            () -> { f.intent++; f.order.add("intent"); });
        check(result.complete() && result.removed() == 1 && result.placed() == 1 && f.at(65).contains("poppy"), "Later migration restores exact locally hidden vegetation");
        check(!snow.hasPending(f.chunk) && !f.metadata.containsKey(owned) && !f.cover.hasMetadata(f.chunk), "Local winter restoration retires both backups");

        var interrupted = new WinterGroundCoverTests.Fixture("minecraft:fern");
        var second = new SeasonalSnowService(interrupted.plugin, () -> null, () -> true, WinterGroundCoverTests::data);
        boolean[] active = {true};
        result = second.accumulateLocalColumns(interrupted.chunk, VERSION, 256, new int[]{0}, POLICY, true,
            WinterGroundCoverTests.data("minecraft:snow[layers=1]"), block -> { active[0] = false; return true; },
            chunk -> interrupted.intent++, chunk -> interrupted.order.add("deferred"), Long.MAX_VALUE, () -> active[0]);
        check(result.removed() == 1 && result.placed() == 0 && interrupted.at(65).contains("air")
                && interrupted.cover.hasMetadata(interrupted.chunk) && interrupted.order.contains("deferred"),
            "An event stopping after plant hiding retains recoverable backup and dirty intent");
        second.applyStep(interrupted.chunk, Season.SPRING, "spring", false, 256, () -> interrupted.intent++);
        check(interrupted.at(65).contains("fern"), "A halted local batch's plants restore without a snow placement");
    }
    private static void nativeGuard() {
        var f = new WinterGroundCoverTests.Fixture("minecraft:poppy");
        var snow = new SeasonalSnowService(f.plugin, () -> null);
        boolean[] enabled = {true}; int[] cap = {0};
        var guard = new LocalWinterSnowService(snow, () -> null, () -> VERSION, world -> enabled[0],
            world -> false, () -> cap[0], null, chunk -> {}, chunk -> {});
        BlockState state = (BlockState)Proxy.newProxyInstance(BlockState.class.getClassLoader(),
            new Class<?>[]{BlockState.class}, (p,m,a) -> {
                if (m.getName().equals("getType")) return Material.SNOW;
                throw new AssertionError("Unexpected new state query " + m.getName());
            });
        var event = new BlockFormEvent(f.block(65), state); guard.capNaturalSnow(event);
        check(event.isCancelled(), "Native snowfall cannot place ahead of zero shared coverage");
        cap[0] = 256; event = new BlockFormEvent(f.block(65), state); guard.capNaturalSnow(event);
        check(!event.isCancelled(), "Native snowfall inside shared coverage remains permitted");
        cap[0] = 0; enabled[0] = false; event = new BlockFormEvent(f.block(65), state); guard.capNaturalSnow(event);
        check(!event.isCancelled(), "Non-managed winter weather remains untouched");
        enabled[0] = true; f.metadata.put(new NamespacedKey(f.plugin,"season_version"),
            new WinterGroundCoverTests.Value(PersistentDataType.STRING, VERSION));
        event = new BlockFormEvent(f.block(65), state); guard.capNaturalSnow(event);
        check(!event.isCancelled(), "Completed region chunks may retain ordinary native snowfall despite the global local cap");
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
}
