package dev.trueseasons.integration;

import com.flowpowered.math.vector.Vector2i;
import de.bluecolored.bluemap.api.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Uses the installed BlueMap RenderTask interface to exercise actual receipt concurrency. */
public final class BlueMapReceiptTests {
    public static final class Api implements RenderManager {
        @SuppressWarnings("unused") private final Object renderManager = new Object();
        public boolean scheduleMapUpdateTask(BlueMapMap map, boolean force) { return false; }
        public boolean scheduleMapUpdateTask(BlueMapMap map, Collection<Vector2i> regions, boolean force) { return false; }
        public boolean scheduleMapPurgeTask(BlueMapMap map) { return false; }
        public int renderQueueSize() { return 0; }
        public int renderThreadCount() { return 0; }
        public boolean isRunning() { return true; }
        public void start() {} public void start(int threads) {} public void stop() {}
    }
    public static final class World {
        int evictions;
        public void invalidateChunkCache(int x, int z) { evictions++; }
    }
    public static final class Region {
        public volatile boolean cancelled;
        private final Vector2i tileMin = Vector2i.ZERO, tileSize = new Vector2i(1, 1);
    }
    public static final class Cell { boolean modified; public boolean isModified() { return modified; } }
    public static final class TileInfo { Object state; public Object getState() { return state; } }
    public static final class Storage {
        private final Map<String, Cell> cells = new HashMap<>();
        final TileInfo info = new TileInfo();
        public TileInfo get(int x, int z) { return info; }
    }
    public static final class Layer { private final Map<String, Object> pendingChanges = new HashMap<>(); }
    public static final class Lowres { private final Layer[] layers = {new Layer()}; }
    public static final class MapFixture {
        final Storage storage = new Storage(); final Lowres lowres = new Lowres();
        boolean failLowres, failState; int saves;
        public Storage getMapTileState() { return storage; }
        public Storage getMapChunkState() { return storage; }
        public Storage getMapRegionState() { return storage; }
        public Lowres getLowresTileManager() { return lowres; }
        public void save() {
            saves++; if (!failLowres) lowres.layers[0].pendingChanges.clear();
            if (!failState) storage.cells.values().forEach(c -> c.modified = false);
        }
    }
    private static final class Work implements InvocationHandler {
        volatile boolean more = true;
        volatile boolean running;
        int finalSaves;
        boolean fail;
        boolean failFinalSave;
        CountDownLatch entered, release;
        CountDownLatch finalEntered, finalRelease;
        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Exception {
            return switch (method.getName()) {
                case "hasMoreWork" -> more;
                case "doWork" -> {
                    running = true;
                    try {
                        more = false;
                        if (entered != null) { entered.countDown(); release.await(); }
                        if (fail) throw new IllegalStateException("simulated storage error");
                    } finally { running = false; }
                    yield null;
                }
                case "cancel" -> { more = false; yield null; }
                case "getDescription" -> "test storage save";
                case "estimateProgress" -> more ? 0.0 : 1.0;
                case "getDetail" -> Optional.empty();
                case "contains", "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> null;
            };
        }
    }
    private record Fixture(BlueMapRenderBridge.Ticket ticket, Object proxy, Work work, World world, Region region) {}
    private static int assertions;
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static Class<?> taskType;
    private static Fixture fixture() throws Exception {
        BlueMapRenderBridge bridge = new BlueMapRenderBridge(new Api());
        Work work = new Work(); World world = new World(); Region region = new Region();
        Object delegate = Proxy.newProxyInstance(taskType.getClassLoader(), new Class<?>[]{taskType}, work);
        BlueMapRenderBridge.Ticket ticket = bridge.new Ticket(delegate, world,
                World.class.getMethod("invalidateChunkCache", int.class, int.class),
                List.of(new Vector2i(-1, 2)), List.of(region), List.of(Region.class.getField("cancelled")), () -> {
                    if (work.running) throw new IllegalStateException("final save overlapped tile worker");
                    work.finalSaves++;
                    if (work.finalEntered != null) {
                        work.finalEntered.countDown();
                        try { work.finalRelease.await(); }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new ReflectiveOperationException(failure); }
                    }
                    if (work.failFinalSave) throw new ReflectiveOperationException("simulated final map-save failure");
                });
        Object proxy = Proxy.newProxyInstance(taskType.getClassLoader(), new Class<?>[]{taskType}, ticket);
        return new Fixture(ticket, proxy, work, world, region);
    }
    private static void work(Fixture fixture) throws Exception { taskType.getMethod("doWork").invoke(fixture.proxy); }
    private static boolean more(Fixture fixture) throws Exception { return (boolean) taskType.getMethod("hasMoreWork").invoke(fixture.proxy); }
    public static void main(String[] args) throws Exception {
        taskType = Class.forName("de.bluecolored.bluemap.common.rendermanager.RenderTask");
        Fixture accepted = fixture();
        check(!accepted.ticket.finished() && !accepted.ticket.successful(), "Scheduling acceptance is not a fulfilled render");
        work(accepted); check(more(accepted) && !accepted.ticket.finished(), "Native completion still awaits save barrier");
        work(accepted); check(!more(accepted) && accepted.ticket.successful(), "Actual work and final map-save completion acknowledge");
        check(accepted.work.finalSaves == 1, "Final save executes exactly once");
        check(accepted.world.evictions == 1024, "Invalidate only the requested region's chunk cache");
        Fixture cancellation = fixture(); cancellation.ticket.cancel(); more(cancellation);
        check(!cancellation.ticket.successful(), "Renderer cancellation preserves intent");
        Fixture error = fixture(); error.work.fail = true; work(error); more(error);
        check(!error.ticket.successful(), "Failed storage/render work cannot acknowledge");
        Fixture finalError = fixture(); work(finalError); finalError.work.failFinalSave = true; work(finalError); more(finalError);
        check(!finalError.ticket.successful(), "Final save failure preserves intent even after native rendering completed");
        Fixture cancelledRegion = fixture(); work(cancelledRegion); cancelledRegion.region.cancelled = true; work(cancelledRegion); more(cancelledRegion);
        check(!cancelledRegion.ticket.successful(), "BlueMap's internally cancelled region cannot acknowledge");
        Fixture concurrent = fixture(); concurrent.work.entered = new CountDownLatch(1); concurrent.work.release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> running = executor.submit(() -> { try { work(concurrent); } catch (Exception failure) { throw new RuntimeException(failure); } });
            check(concurrent.work.entered.await(5, TimeUnit.SECONDS), "Storage-save worker entered");
            check(more(concurrent) && !concurrent.ticket.finished(), "Native no-more-work while tile write is active is not completion");
            Future<?> saving = executor.submit(() -> { try { work(concurrent); } catch (Exception failure) { throw new RuntimeException(failure); } });
            check(concurrent.work.finalSaves == 0, "Final save does not start before tile workers drain");
            concurrent.work.release.countDown(); running.get(5, TimeUnit.SECONDS); saving.get(5, TimeUnit.SECONDS); more(concurrent);
            check(concurrent.ticket.successful(), "Receipt follows storage worker return");
            check(concurrent.work.finalSaves == 1, "Drained batch saves once after active tile writes");
        } finally { concurrent.work.release.countDown(); executor.shutdownNow(); }
        Fixture finalSave = fixture(); work(finalSave);
        finalSave.work.finalEntered = new CountDownLatch(1); finalSave.work.finalRelease = new CountDownLatch(1);
        ExecutorService storageWorker = Executors.newSingleThreadExecutor();
        try {
            Future<?> saving = storageWorker.submit(() -> { try { work(finalSave); } catch (Exception failure) { throw new RuntimeException(failure); } });
            check(finalSave.work.finalEntered.await(5, TimeUnit.SECONDS), "Final storage worker entered");
            check(more(finalSave) && !finalSave.ticket.finished() && !finalSave.ticket.successful(), "Receipt remains pending throughout the extra final save");
            finalSave.work.finalRelease.countDown(); saving.get(5, TimeUnit.SECONDS); more(finalSave);
            check(finalSave.ticket.successful(), "Receipt follows final storage save return");
        } finally { finalSave.work.finalRelease.countDown(); storageWorker.shutdownNow(); }
        Object renderError = new Object(), chunkError = new Object();
        Region region = new Region(); MapFixture map = new MapFixture();
        List<Object> regions = List.of(region); List<Field> cancellationFields = List.of(Region.class.getField("cancelled"));
        map.storage.info.state = renderError;
        try { BlueMapRenderBridge.finishMap(map, regions, cancellationFields, renderError, chunkError); throw new AssertionError("Swallowed render error acknowledged"); }
        catch (ReflectiveOperationException expected) { assertions++; }
        map.storage.info.state = chunkError;
        try { BlueMapRenderBridge.finishMap(map, regions, cancellationFields, renderError, chunkError); throw new AssertionError("Chunk error acknowledged"); }
        catch (ReflectiveOperationException expected) { assertions++; }
        map.storage.info.state = new Object(); map.failLowres = true; map.lowres.layers[0].pendingChanges.put("tile", new Object());
        try { BlueMapRenderBridge.finishMap(map, regions, cancellationFields, renderError, chunkError); throw new AssertionError("Swallowed lowres save error acknowledged"); }
        catch (ReflectiveOperationException expected) { assertions++; }
        map.failLowres = false; map.failState = true; Cell cell = new Cell(); cell.modified = true; map.storage.cells.put("cell", cell);
        try { BlueMapRenderBridge.finishMap(map, regions, cancellationFields, renderError, chunkError); throw new AssertionError("Swallowed render-state save error acknowledged"); }
        catch (ReflectiveOperationException expected) { assertions++; }
        map.failState = false; BlueMapRenderBridge.finishMap(map, regions, cancellationFields, renderError, chunkError);
        check(!cell.modified && map.lowres.layers[0].pendingChanges.isEmpty(), "Successful final save proves no unsaved render state or tiles");
        System.out.println("BlueMap receipt tests passed: " + assertions + " assertions");
    }
}
