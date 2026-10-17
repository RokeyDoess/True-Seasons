package dev.trueseasons.integration;

import com.flowpowered.math.vector.Vector2i;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class BlueMapVisualQueueTests {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void discovered(BlueMapVisualQueue queue, String map) throws Exception {
        Field maps = BlueMapVisualQueue.class.getDeclaredField("maps"); maps.setAccessible(true);
        Object state = ((Map<?, ?>) maps.get(queue)).get(map);
        Field discovery = state.getClass().getDeclaredField("discovery"); discovery.setAccessible(true);
        CompletableFuture<?> future = (CompletableFuture<?>) discovery.get(state); if (future != null) future.get(5, TimeUnit.SECONDS);
        queue.regions(map);
    }
    private static void acknowledged(BlueMapVisualQueue queue, String map) throws Exception {
        Field maps = BlueMapVisualQueue.class.getDeclaredField("maps"); maps.setAccessible(true);
        Object state = ((Map<?, ?>) maps.get(queue)).get(map);
        Field receipt = state.getClass().getDeclaredField("receipt"); receipt.setAccessible(true);
        Object pending = receipt.get(state);
        if (pending != null) {
            Field durable = pending.getClass().getDeclaredField("durable"); durable.setAccessible(true);
            ((CompletableFuture<?>) durable.get(pending)).get(5, TimeUnit.SECONDS);
        }
        queue.pending();
    }
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("trueseasons-palette-queue-");
        String map = "world/custom-map";
        List<Vector2i> regions = List.of(new Vector2i(-1, 2), new Vector2i(0, 0), new Vector2i(1, 1));
        AtomicInteger discoveries = new AtomicInteger();
        try (BlueMapVisualQueue queue = new BlueMapVisualQueue(directory)) {
            queue.target(map, "vanilla", () -> { discoveries.incrementAndGet(); return regions; });
            check(discoveries.get() == 0 && queue.pending() == 0, "inactive first startup requests no renders");
            queue.target(map, "autumn-palette1", () -> { discoveries.incrementAndGet(); return regions; }); discovered(queue, map);
            check(queue.pending() == 3, "season change queues existing saved regions");
            check(queue.candidates(map, 1).equals(List.of(regions.get(0))), "candidate scan uses bounded budget");
            check(queue.candidates(map, 1).equals(List.of(regions.get(1))), "blocked region cannot starve later regions");
            queue.target(map, "autumn-palette1", () -> { throw new AssertionError("unchanged reload rediscovered world"); });
            queue.acknowledge(map, "old-palette", List.of(regions.get(0)));
            check(queue.pending() == 3, "obsolete render receipt cannot drop current palette intent");
            queue.acknowledge(map, queue.receiptToken(map), List.of(regions.get(0))); acknowledged(queue, map);
            check(queue.pending() == 2, "saved success retires only completed regions");
        }
        try (BlueMapVisualQueue queue = new BlueMapVisualQueue(directory)) {
            check(queue.target(map).equals("autumn-palette1") && queue.pending() == 2, "restart preserves fingerprint and pending saved-region work");
            queue.target(map, "autumn-palette1", () -> { throw new AssertionError("restart repeated unchanged discovery"); });
            queue.target(map, "spring-palette2", () -> regions); discovered(queue, map);
            queue.acknowledge(map, "autumn-palette1", regions);
            check(queue.pending() == 3, "old in-flight success cannot acknowledge superseding palette");
            queue.target(map, "vanilla", () -> regions); discovered(queue, map);
            check(queue.pending() == 3, "cleanup queues original-colour restoration");
            queue.acknowledge(map, queue.receiptToken(map), regions); acknowledged(queue, map);
            check(queue.pending() == 0, "restoration completes only after all map receipts");
        }
        try (BlueMapVisualQueue queue = new BlueMapVisualQueue(directory)) {
            queue.target(map, "vanilla", () -> { throw new AssertionError("completed cleanup rediscovered world"); });
            check(queue.pending() == 0, "completed unchanged restart does no map work");
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            queue.target(map, "old", () -> { entered.countDown(); release.await(); return List.of(new Vector2i(100, 100)); });
            check(entered.await(5, TimeUnit.SECONDS), "background discovery started");
            queue.target(map, "new", () -> regions); release.countDown(); discovered(queue, map);
            check(new LinkedHashSet<>(queue.regions(map)).equals(new LinkedHashSet<>(regions)), "superseding discovery owns durable pending queue");
        }
        try (BlueMapVisualQueue queue = new BlueMapVisualQueue(directory)) {
            check(queue.target(map).equals("new") && queue.pending() == 3, "only newest discovery survives restart");
            queue.target(map, "retry-palette", () -> { throw new java.io.IOException("temporary saved-region listing failure"); });
            try { discovered(queue, map); throw new AssertionError("failed discovery appeared complete"); }
            catch (ExecutionException expected) { check(queue.pending() > 0, "failed discovery retains outstanding intent"); }
            queue.target(map, "retry-palette", () -> regions); discovered(queue, map);
            check(queue.pending() == 3, "same target retries failed saved-region listing without losing work");
            String oldGeneration = queue.receiptToken(map);
            queue.target(map, "intermediate-palette", () -> regions); discovered(queue, map);
            queue.target(map, "retry-palette", () -> regions); discovered(queue, map);
            queue.acknowledge(map, oldGeneration, regions);
            check(queue.pending() == 3, "A-to-B-to-A old receipt cannot acknowledge newest same-fingerprint generation");
            Field journalField = BlueMapVisualQueue.class.getDeclaredField("journal"); journalField.setAccessible(true);
            Object journal = journalField.get(queue);
            CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
            CompletableFuture<Void> lockOwner = CompletableFuture.runAsync(() -> {
                synchronized (journal) {
                    locked.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("journal lock release timed out"); }
                    catch (InterruptedException failure) { throw new CompletionException(failure); }
                }
            });
            check(locked.await(5, TimeUnit.SECONDS), "another map's bulk-persistence journal lock acquired");
            try {
                long started = System.nanoTime();
                queue.acknowledge(map, queue.receiptToken(map), List.of(regions.get(0)));
                queue.compact();
                check(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(500), "receipt and compaction scheduling never wait on bulk journal mutex");
                check(queue.candidates(map, 128).isEmpty(), "same-map palette candidates wait for durable receipt");
                check(queue.pending() == 3, "undurable receipt still counts as pending restoration work");
            } finally { release.countDown(); lockOwner.get(5, TimeUnit.SECONDS); }
            acknowledged(queue, map);
            check(queue.pending() == 2, "durable async receipt retires exactly its saved map region");
            CountDownLatch oldReceiptLocked = new CountDownLatch(1), oldReceiptRelease = new CountDownLatch(1);
            CompletableFuture<Void> oldReceiptLockOwner = CompletableFuture.runAsync(() -> {
                synchronized (journal) {
                    oldReceiptLocked.countDown();
                    try { if (!oldReceiptRelease.await(5, TimeUnit.SECONDS)) throw new AssertionError("old receipt lock release timed out"); }
                    catch (InterruptedException failure) { throw new CompletionException(failure); }
                }
            });
            check(oldReceiptLocked.await(5, TimeUnit.SECONDS), "old-generation receipt persistence can be delayed");
            try {
                queue.acknowledge(map, queue.receiptToken(map), List.of(regions.get(1)));
                queue.target(map, "intermediate-palette", () -> regions);
                queue.target(map, "retry-palette", () -> regions);
            } finally { oldReceiptRelease.countDown(); oldReceiptLockOwner.get(5, TimeUnit.SECONDS); }
            discovered(queue, map);
            check(queue.pending() == 3, "old async acknowledgement cannot erase rediscovered A-to-B-to-A generation");
        }
        scopedCleanup(regions);
        distinctRegions(regions);
        System.out.println("BlueMap visual queue tests passed");
    }
    private static void distinctRegions(List<Vector2i> regions)throws Exception{
        Path directory=Files.createTempDirectory("trueseasons-distinct-regions-");UUID world=UUID.randomUUID(),other=UUID.randomUUID();
        try(BlueMapVisualQueue queue=new BlueMapVisualQueue(directory)){
            queue.target("surface","winter",()->regions);discovered(queue,"surface");
            Set<RenderLedger.Region> known=new HashSet<>();Vector2i first=regions.getFirst();known.add(new RenderLedger.Region(world,first.getX(),first.getY()));
            check(queue.appendPendingRegions("surface",world,false,known)&&known.size()==regions.size(),"terrain and palette requests in the same region count once");
            queue.target("second-map","winter",()->regions);discovered(queue,"second-map");
            check(queue.appendPendingRegions("second-map",world,false,known)&&known.size()==regions.size(),"two maps of the same world still count distinct world regions");
            check(queue.appendPendingRegions("surface",other,false,known)&&known.size()==regions.size()*2,"equal coordinates in different worlds remain distinct");
            Vector2i replacement=new Vector2i(99,-100);queue.target("surface","spring",()->List.of(replacement));discovered(queue,"surface");
            known.clear();queue.appendPendingRegions("surface",world,false,known);
            check(known.equals(Set.of(new RenderLedger.Region(world,99,-100))),"a new target replaces previous palette regions rather than accumulating old jobs");
            CountDownLatch release=new CountDownLatch(1);
            queue.target("discovering","winter",()->{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("discovery timeout");return regions;});
            try{check(!queue.appendPendingRegions("discovering",world,false,new HashSet<>()),"unfinished discovery reports an unknown count without blocking");}
            finally{release.countDown();}discovered(queue,"discovering");
            queue.acknowledge("surface",queue.receiptToken("surface"),List.of(replacement));acknowledged(queue,"surface");
            known.clear();check(queue.appendPendingRegions("surface",world,false,known)&&known.isEmpty(),"durable completion removes a counted region");
            check(!queue.appendPendingRegions("surface",world,true,known),"unresolved vanilla restoration is not presented as an invented physical region");
        }
    }
    private static void scopedCleanup(List<Vector2i> regions) throws Exception {
        Path directory = Files.createTempDirectory("trueseasons-scoped-palette-cleanup-");
        Set<String> mapped = Set.of("active");
        try (BlueMapVisualQueue queue = new BlueMapVisualQueue(directory)) {
            queue.target("orphan", "autumn", () -> regions); discovered(queue, "orphan");
            queue.target("active", "autumn", () -> regions); discovered(queue, "active");
            check(queue.pending(mapped) == 3, "live pending count excludes unconfigured dormant maps");
            queue.acknowledge("active", queue.receiptToken("active"), regions); acknowledged(queue, "active");
            check(queue.restorationPending(mapped) == 1, "mapped seasonal target cannot claim safe restoration before vanilla target is established");
            queue.target("active", "vanilla", () -> regions); discovered(queue, "active");
            check(queue.restorationPending(mapped) == 3, "configured vanilla restoration still waits for saved render receipts");
            queue.acknowledge("active", queue.receiptToken("active"), regions); acknowledged(queue, "active");
            check(queue.pending(mapped) == 0 && queue.restorationPending(mapped) == 0, "orphan queue cannot keep completed mapped cleanup or removal guidance pending");
            check(queue.pending() == 3, "orphan durable intent is retained for later reactivation");
        }
        try (BlueMapVisualQueue queue = new BlueMapVisualQueue(directory)) {
            check(queue.pending(mapped) == 0 && queue.pending(Set.of("orphan")) == 3, "restart preserves dormant queue and relevant-map pending scope");
            queue.target("orphan", "autumn", () -> { throw new AssertionError("reactivated same palette should resume durable regions"); });
            check(queue.candidates("orphan", 128).size() == 3, "reactivated map resumes its retained palette intent");
        }
    }
}
