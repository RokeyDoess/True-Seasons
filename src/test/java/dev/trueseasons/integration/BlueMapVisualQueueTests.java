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
        equivalentUpgrade(regions);
        equivalentUpgradeFailure(regions);
        System.out.println("BlueMap visual queue tests passed");
    }
    private static Object state(BlueMapVisualQueue queue,String map)throws Exception{
        Field maps=BlueMapVisualQueue.class.getDeclaredField("maps");maps.setAccessible(true);
        return ((Map<?,?>)maps.get(queue)).get(map);
    }
    private static CompletableFuture<?> rebinding(BlueMapVisualQueue queue,String map)throws Exception{
        Object state=state(queue,map);Field field=state.getClass().getDeclaredField("rebinding");field.setAccessible(true);
        Object pending=field.get(state);if(pending==null)return CompletableFuture.completedFuture(null);
        Field durable=pending.getClass().getDeclaredField("durable");durable.setAccessible(true);
        return (CompletableFuture<?>)durable.get(pending);
    }
    private static void rebound(BlueMapVisualQueue queue,String map)throws Exception{
        rebinding(queue,map).get(5,TimeUnit.SECONDS);queue.pending();
    }
    private static void equivalentUpgrade(List<Vector2i> regions)throws Exception{
        Path directory=Files.createTempDirectory("trueseasons-palette-upgrade-");String map="surface";
        Callable<Collection<Vector2i>> noDiscovery=()->{throw new AssertionError("Equivalent palette upgrade rediscovered finished terrain");};
        try(BlueMapVisualQueue queue=new BlueMapVisualQueue(directory)){
            queue.target(map,"legacy",()->regions);discovered(queue,map);
            queue.acknowledge(map,queue.receiptToken(map),regions);acknowledged(queue,map);
        }
        try(BlueMapVisualQueue queue=new BlueMapVisualQueue(directory)){
            String generation=queue.receiptToken(map);
            queue.target(map,"scoped",List.of("older-legacy","legacy"),noDiscovery);rebound(queue,map);
            check(queue.target(map).equals("scoped")&&queue.pending()==0,"equivalent completed palette changes target without reopening regions");
            check(queue.receiptToken(map).equals(generation),"equivalent upgrade keeps outstanding render generations valid");
        }
        try(BlueMapVisualQueue queue=new BlueMapVisualQueue(directory)){
            check(queue.target(map).equals("scoped")&&queue.pending()==0,"equivalent fingerprint remains durable across restart");
            queue.target("second-format","older-legacy",()->regions);discovered(queue,"second-format");
            queue.acknowledge("second-format",queue.receiptToken("second-format"),regions);acknowledged(queue,"second-format");
            queue.target("second-format","scoped",List.of("older-legacy","legacy"),noDiscovery);rebound(queue,"second-format");
            check(queue.target("second-format").equals("scoped")&&queue.pending()==0,"either published equivalent format upgrades without new renders");
            queue.target(map,"scoped","legacy",noDiscovery);
            queue.target(map,"legacy-partial",()->regions);discovered(queue,map);
            queue.acknowledge(map,queue.receiptToken(map),List.of(regions.getFirst()));acknowledged(queue,map);
        }
        try(BlueMapVisualQueue queue=new BlueMapVisualQueue(directory)){
            String generation=queue.receiptToken(map);
            queue.target(map,"scoped-partial","legacy-partial",noDiscovery);
            queue.acknowledge(map,generation,List.of(regions.get(1)));acknowledged(queue,map);rebound(queue,map);
            check(queue.pending()==1&&queue.regions(map).contains(regions.get(2)),"upgrade keeps pending coordinates and accepts an existing in-flight receipt");
        }
        try(BlueMapVisualQueue queue=new BlueMapVisualQueue(directory)){
            check(queue.target(map).equals("scoped-partial")&&queue.pending()==1,"partial upgraded receipt survives restart");
            String generation=queue.receiptToken(map);
            queue.target(map,"actually-changed","unrelated-legacy",()->regions);discovered(queue,map);
            check(!queue.receiptToken(map).equals(generation)&&queue.pending()==3,"a changed palette still starts a fresh complete generation");
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            queue.target(map,"discovering-legacy",()->{entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("discovery release timeout");return regions;});
            check(entered.await(5,TimeUnit.SECONDS),"legacy discovery is in progress");
            generation=queue.receiptToken(map);
            try{
                queue.target(map,"discovering-scoped","discovering-legacy",noDiscovery);
                check(queue.target(map).equals("discovering-legacy"),"equivalent upgrade waits for pending discovery without replacing it");
            }finally{release.countDown();}
            discovered(queue,map);queue.target(map,"discovering-scoped","discovering-legacy",noDiscovery);rebound(queue,map);
            check(queue.receiptToken(map).equals(generation)&&queue.pending()==3,"finished discovery upgrades without dropping its regions");
            Field field=BlueMapVisualQueue.class.getDeclaredField("journal");field.setAccessible(true);Object journal=field.get(queue);
            CountDownLatch locked=new CountDownLatch(1),unlock=new CountDownLatch(1);
            CompletableFuture<Void> lock=CompletableFuture.runAsync(()->{synchronized(journal){locked.countDown();try{if(!unlock.await(5,TimeUnit.SECONDS))throw new AssertionError("receipt release timeout");}catch(InterruptedException failure){throw new CompletionException(failure);}}});
            check(locked.await(5,TimeUnit.SECONDS),"receipt persistence can be delayed during upgrade");
            try{
                queue.acknowledge(map,generation,List.of(regions.getFirst()));
                queue.target(map,"receipt-scoped","discovering-scoped",noDiscovery);
                check(queue.target(map).equals("discovering-scoped"),"upgrade preserves an undurable receipt and retries later");
            }finally{unlock.countDown();lock.get(5,TimeUnit.SECONDS);}
            acknowledged(queue,map);queue.target(map,"receipt-scoped","discovering-scoped",noDiscovery);rebound(queue,map);
            check(queue.pending()==2&&queue.receiptToken(map).equals(generation),"delayed receipt retires only its region across upgrade");
            queue.target(map,"next-equivalent","receipt-scoped",noDiscovery);
            queue.target(map,"new-content",()->List.of(new Vector2i(90,91)));discovered(queue,map);
            check(queue.pending()==1&&queue.regions(map).contains(new Vector2i(90,91)),"a content change supersedes an in-flight equivalent upgrade in worker order");
        }
        try(BlueMapVisualQueue queue=new BlueMapVisualQueue(directory)){
            check(queue.target(map).equals("new-content")&&queue.pending()==1,"superseding content target and coordinates persist together");
        }
    }
    private static void equivalentUpgradeFailure(List<Vector2i> regions)throws Exception{
        Path directory=Files.createTempDirectory("trueseasons-palette-upgrade-failure-");String map="surface";
        try(BlueMapVisualQueue queue=new BlueMapVisualQueue(directory)){
            queue.target(map,"legacy",()->regions);discovered(queue,map);String generation=queue.receiptToken(map);
            Field field=BlueMapVisualQueue.class.getDeclaredField("journal");field.setAccessible(true);Object journal=field.get(queue);
            Field channelField=DurableRenderJournal.class.getDeclaredField("channel");channelField.setAccessible(true);
            var original=(java.nio.channels.FileChannel)channelField.get(journal);original.close();
            try{
                queue.target(map,"scoped","legacy",()->{throw new AssertionError("failed equivalent upgrade rediscovered regions");});
                try{rebinding(queue,map).get(5,TimeUnit.SECONDS);throw new AssertionError("Closed journal accepted upgrade");}
                catch(ExecutionException expected){queue.pending();}
                check(queue.target(map).equals("legacy")&&queue.pending()==3&&queue.receiptToken(map).equals(generation),"failed fingerprint write restores retryable target without losing pending regions");
                check(queue.failure()!=null,"failed equivalent upgrade exposes persistence error");
            }finally{
                var recovered=java.nio.channels.FileChannel.open(directory.resolve("bluemap.journal"),StandardOpenOption.WRITE);recovered.position(recovered.size());channelField.set(journal,recovered);
            }
            queue.target(map,"scoped","legacy",()->{throw new AssertionError("recovery rediscovered regions");});rebound(queue,map);
            check(queue.target(map).equals("scoped")&&queue.pending()==3&&queue.failure()==null,"equivalent upgrade retries and clears its error after durability recovers");
        }
        try(BlueMapVisualQueue queue=new BlueMapVisualQueue(directory)){
            check(queue.target(map).equals("scoped")&&queue.pending()==3,"recovered upgrade remains durable without losing pending regions");
        }
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
