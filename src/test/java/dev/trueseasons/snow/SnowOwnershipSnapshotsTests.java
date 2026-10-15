package dev.trueseasons.snow;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import org.bukkit.*;
import org.bukkit.event.world.*;
import org.bukkit.persistence.PersistentDataType;

/** Read-only packet views must track durable ownership without terrain reads. */
public final class SnowOwnershipSnapshotsTests {
    private static int checks;
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
    public static void main(String[] args) throws Exception {
        UUID id = UUID.randomUUID();
        var fixture = new SnowReplenishmentTests.Fixture();
        World world = proxy(World.class, (p,m,a) -> switch(m.getName()) {
            case "getUID" -> id; case "getName" -> "ownership-fixture";
            default -> invoke(m, fixture.world, a);
        });
        Chunk chunk = proxy(Chunk.class, (p,m,a) -> m.getName().equals("getWorld") ? world : invoke(m, fixture.chunk, a));
        Logger logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        List<LogRecord> logs = new ArrayList<>();
        logger.addHandler(new Handler() {
            public void publish(LogRecord record) { logs.add(record); }
            public void flush() {} public void close() {}
        });
        int[] notifications = {0};
        var snapshots = new SnowOwnershipSnapshots(fixture.snow, changed -> {
            notifications[0]++; fixture.order.add("snapshot");
        }, logger);
        var view = snapshots.world(id);
        check(view == snapshots.world(id), "World view identity is stable before first owned chunk");
        check(view.snapshot(4, -3) == SnowOwnershipSnapshots.Snapshot.EMPTY, "Unknown chunks safely expose no ownership");
        snapshots.onLoad(new ChunkLoadEvent(chunk, false));
        check(view.snapshot(4,-3) == SnowOwnershipSnapshots.Snapshot.EMPTY && notifications[0] == 1,
            "Loading an empty chunk publishes a known empty snapshot");
        fixture.order.clear();
        var result = fixture.snow.replenishColumns(chunk, "finished-winter", new int[]{0},
            new SeasonalSnowService.ReplenishmentPolicy(true, true, Set.of(), List.of(), 1),
            fixture.snowData, block -> true, ignored -> { fixture.before++; fixture.order.add("intent"); },
            ignored -> { fixture.after++; fixture.order.add("revision"); }, Long.MAX_VALUE);
        check(result.placed() == 1 && fixture.order.equals(List.of("intent", "ownership", "snapshot", "block", "revision")),
            "Snapshot publication follows durable ownership and precedes physical placement");
        var first = view.snapshot(4,-3);
        check(first.heights().equals(Map.of(0,65)), "Snapshot exposes exact owned column height");
        try { first.heights().put(1, 2); throw new AssertionError("Mutable snapshot"); }
        catch (UnsupportedOperationException expected) { check(true, "Published snapshot is immutable"); }
        Map<Integer,Integer> mutable = new HashMap<>(Map.of(4,-64));
        var copied = new SnowOwnershipSnapshots.Snapshot(mutable); mutable.put(4,70);
        check(copied.heights().equals(Map.of(4,-64)), "Snapshot copies caller maps and preserves signed heights");
        check(CompletableFuture.supplyAsync(() -> view.snapshot(4,-3).heights().get(0)).get() == 65,
            "Packet thread reads only safely published immutable ownership");
        fixture.metadata.put(fixture.owned, new SnowReplenishmentTests.Value(PersistentDataType.BYTE_ARRAY,new byte[]{99}));
        snapshots.onLoad(new ChunkLoadEvent(chunk, false));
        snapshots.onLoad(new ChunkLoadEvent(chunk, false));
        check(view.snapshot(4,-3) == SnowOwnershipSnapshots.Snapshot.EMPTY && logs.size() == 1,
            "Malformed ownership fails open and warns once while chunk stays loaded");
        check(first.heights().equals(Map.of(0,65)), "Replacing cache entry cannot mutate retained reader snapshot");
        try { fixture.snow.observeOwnership(chunk); throw new AssertionError("Expected malformed metadata"); }
        catch (java.io.IOException expected) { check(true, "Presentation handling leaves migration metadata failures intact"); }
        fixture.metadata.remove(fixture.owned);
        snapshots.onLoad(new ChunkLoadEvent(chunk, false));
        snapshots.onUnload(new ChunkUnloadEvent(chunk));
        check(view.snapshot(4,-3) == SnowOwnershipSnapshots.Snapshot.EMPTY, "Chunk unload removes its ownership without loading terrain");
        snapshots.onWorldUnload(new WorldUnloadEvent(world));
        check(snapshots.world(id) != view && view.snapshot(4,-3) == SnowOwnershipSnapshots.Snapshot.EMPTY,
            "World unload clears retained views and permits a fresh world lifetime");
        // Callback failure must not abort already-owned physical snow writes.
        var failing = new SnowOwnershipSnapshots(fixture.snow, changed -> { throw new IllegalStateException("test callback"); }, logger);
        fixture.column(0).target = Material.AIR;
        fixture.before = fixture.after = 0;
        result = fixture.snow.replenishColumns(chunk, "finished-winter", new int[]{0},
            new SeasonalSnowService.ReplenishmentPolicy(true, true, Set.of(), List.of(), 1),
            fixture.snowData, block -> true, ignored -> fixture.before++, ignored -> fixture.after++, Long.MAX_VALUE);
        check(result.placed() == 1 && failing.world(id).snapshot(4,-3).heights().equals(Map.of(0,65)),
            "Notification failure preserves snapshot publication and physical placement");
        snapshots.close(); snapshots.close();
        fixture.column(0).target = Material.AIR; fixture.before = fixture.after = 0;
        result = fixture.snow.replenishColumns(chunk, "finished-winter", new int[]{1},
            new SeasonalSnowService.ReplenishmentPolicy(true, true, Set.of(), List.of(), 1),
            fixture.snowData, block -> true, ignored -> fixture.before++, ignored -> fixture.after++, Long.MAX_VALUE);
        var retained = failing.world(id);
        check(result.placed() == 1 && retained.snapshot(4,-3).heights().containsKey(1),
            "Closing an older owner cannot detach its replacement observer");
        failing.close();
        check(retained.snapshot(4,-3) == SnowOwnershipSnapshots.Snapshot.EMPTY
                && failing.world(id).snapshot(4,-3) == SnowOwnershipSnapshots.Snapshot.EMPTY,
            "Close clears retained world views and closed reads remain empty");
        System.out.println("Snow ownership snapshot tests passed: " + checks + " assertions");
    }
    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target,args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
