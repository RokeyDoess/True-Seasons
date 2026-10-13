package dev.trueseasons.migration;

import java.lang.reflect.*;
import java.util.*;
import org.bukkit.Chunk;
import org.bukkit.World;

/** Standalone completion regressions; no server or generated world is required. */
public final class MigrationBookkeepingTests {
    private static int assertions;

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static ChunkMigrationEngine.Job scanned() {
        ChunkMigrationEngine.Job job = new ChunkMigrationEngine.Job(null);
        job.scanned = true;
        return job;
    }

    private static void setEngineField(Object engine, String name, Object value) throws ReflectiveOperationException {
        Field field = ChunkMigrationEngine.class.getDeclaredField(name);
        field.setAccessible(true); field.set(engine, value);
    }

    private static void obsoleteCallback(Throwable error) throws ReflectiveOperationException {
        UUID id = UUID.randomUUID();
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, values) -> {
                    if (method.getName().equals("getUID")) return id;
                    throw new AssertionError("Obsolete callback must not access the world: " + method.getName());
                });
        ChunkPos pos = new ChunkPos(-4, 9);
        ChunkMigrationEngine.Job job = new ChunkMigrationEngine.Job(world);
        job.known.add(pos); job.flight.add(pos); job.generation = 2;
        // Bypass listener/scanner setup: this branch needs only bookkeeping and must
        // never access the unset plugin, its logger or a running Bukkit server.
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        Object engine = unsafeType.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), ChunkMigrationEngine.class);
        setEngineField(engine, "jobs", Map.of(id, job));
        setEngineField(engine, "inFlight", 1);
        Class<?> ready = Class.forName("dev.trueseasons.migration.ChunkMigrationEngine$Ready");
        Constructor<?> constructor = ready.getDeclaredConstructor(ChunkMigrationEngine.Job.class, ChunkPos.class, Chunk.class,
                boolean.class, int.class, Throwable.class);
        constructor.setAccessible(true);
        Object request = constructor.newInstance(job, pos, null, false, 1, error);
        Method finish = ChunkMigrationEngine.class.getDeclaredMethod("finish", ready); finish.setAccessible(true);
        finish.invoke(engine, request);
        check(job.queued.equals(Set.of(pos)) && job.queue.size() == 1 && job.queue.peek().equals(pos),
                "An obsolete " + (error == null ? "null" : "failed") + " callback requeues the current generation exactly once");
        check(job.failures.isEmpty(), "An obsolete callback cannot defer the current generation behind an old error");
        Field flights = ChunkMigrationEngine.class.getDeclaredField("inFlight"); flights.setAccessible(true);
        check(job.flight.isEmpty() && flights.getInt(engine) == 0, "An obsolete callback releases its captured load accounting");
        check(job.done.isEmpty() && job.unavailable.isEmpty(), "An obsolete callback cannot classify current work as finished or unavailable");
    }

    private static void savedCheckpoints() {
        String target = "manual-WINTER-r14-snow3";
        SavedChunkState complete = new SavedChunkState(true,target,null,false,0,true,false,null);
        ChunkPos pos = new ChunkPos(0,0);
        // The null world makes any accidental chunk traversal/loading fail immediately.
        ChunkMigrationEngine.Job bulk = scanned();
        bulk.columns.put(pos,128); bulk.nextStep.put(pos,10); bulk.failures.put(pos,Long.MAX_VALUE); bulk.indexFailures.add(pos);
        for (int x=0;x<500;x++) bulk.observeCheckpoint(new ChunkPos(x,0),complete,target,false,true,false);
        bulk.queue.removeIf(bulk.done::contains); // The scanner removes hydrated entries once per entire result batch.
        check(bulk.completed()==500 && bulk.total()==500 && bulk.finished(), "Saved matching checkpoints restore complete progress without live chunk traversal");
        check(bulk.queue.isEmpty() && bulk.queued.isEmpty() && bulk.flight.isEmpty(), "Bulk hydration leaves no load requests for saved-current terrain");
        check(bulk.columns.isEmpty() && bulk.nextStep.isEmpty() && bulk.failures.isEmpty() && bulk.indexFailures.isEmpty(),
                "A completed saved checkpoint clears obsolete partial and retry obligations");

        ChunkMigrationEngine.Job job = scanned();
        job.observeCheckpoint(pos,complete,target,false,true,false); job.queue.removeIf(job.done::contains); job.completed=true;
        SavedChunkState partial = new SavedChunkState(true,target,target,true,128,true,false,null);
        job.observeCheckpoint(pos,partial,target,false,true,false);
        check(!job.done.contains(pos) && !job.completed && !job.finished() && job.queued.contains(pos), "A current partial snapshot reopens earlier saved-only completion");
        check(job.columns.get(pos)==128 && job.coveredColumns()==128, "Reopened saved partial progress contributes its cursor once rather than counting done plus partial");
        SavedChunkState terminal = new SavedChunkState(true,target,target,true,256,true,false,null);
        job.observeCheckpoint(pos,terminal,target,false,true,false);
        check(!job.done.contains(pos) && job.columns.get(pos)==256 && !job.finished(), "A cursor at256 remains queued for metadata finalization rather than being declared complete");
        check(job.queue.size()==1 && job.queued.equals(Set.of(pos)), "Repeated incomplete snapshots do not duplicate the same load request");
        SavedChunkState stalePending = new SavedChunkState(true,"previous-winter","previous-winter",true,192,true,false,null);
        job.observeCheckpoint(pos,stalePending,target,false,true,false);
        check(job.columns.isEmpty() && job.coveredColumns()==0 && !job.done.contains(pos), "Stale pending versions cannot retain coverage in the current transition");

        job = scanned(); job.observeCheckpoint(pos,complete,target,false,true,false); job.queue.removeIf(job.done::contains);
        job.observeCheckpoint(pos,complete,target,false,true,true);
        check(!job.done.contains(pos) && job.queued.contains(pos) && job.queue.size()==1, "A saved-current chunk requiring map recovery queues one metadata recovery request");
        check(job.columns.isEmpty() && job.nextStep.isEmpty() && job.placed()==0 && job.removed()==0,
                "Map recovery alone does not invent snow accumulation, edit counts or a snow cursor");

        job = scanned(); job.observeFull(pos); job.done.add(pos); job.queue.clear(); job.queued.clear();
        job.observeCheckpoint(pos,stalePending,target,false,true,false);
        check(job.done.contains(pos) && job.finished() && job.queue.isEmpty() && job.columns.isEmpty(),
                "An older disk snapshot cannot reopen a newer live-observed completed chunk");
        job.done.remove(pos); job.columns.put(pos,64); job.nextStep.put(pos,10); job.queue.add(pos); job.queued.add(pos);
        job.observeCheckpoint(pos,complete,target,false,true,false);
        check(!job.done.contains(pos) && job.columns.get(pos)==64 && job.nextStep.containsKey(pos) && !job.finished(),
                "An older completed disk snapshot cannot hide newer live-observed partial work");

        SavedChunkState restored = new SavedChunkState(true,null,null,false,0,false,false,null);
        job = scanned(); job.observeCheckpoint(pos,restored,target,true,true,false); job.queue.removeIf(job.done::contains);
        check(job.finished() && job.completed()==1, "Metadata-free saved terrain hydrates restored cleanup progress");
        SavedChunkState mapPending = new SavedChunkState(true,null,null,false,0,false,true,"map-token");
        job.observeCheckpoint(pos,mapPending,target,true,true,false);
        check(!job.done.contains(pos) && job.queued.contains(pos) && !job.finished(), "A later map-marker checkpoint reopens saved-only cleanup completion");
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        ChunkPos pos = new ChunkPos(-13, 27);
        ChunkMigrationEngine.Job job = new ChunkMigrationEngine.Job(null);
        check(!job.finished(), "Completion waits for the first disk scan");
        check(job.percent() == 0, "Initial indexing never advertises 100 percent before any saved terrain is discovered");
        job.scanned = true;
        job.scanning = true;
        check(!job.finished(), "A rescan must settle before completion is reported");
        job.scanning = false;
        check(job.finished(), "An empty settled world is complete");

        job = scanned();
        job.known.add(pos);
        job.queue.add(pos);
        job.queued.add(pos);
        check(!job.finished(), "Queued generated work blocks completion");
        job.queue.remove(pos);
        job.queued.remove(pos);
        job.flight.add(pos);
        check(!job.finished(), "An async request blocks completion after leaving the queue");
        job.flight.remove(pos);
        job.failures.put(pos, 0L);
        check(!job.finished(), "Retryable failures cannot be reported as migration success");
        job.failures.clear();
        check(!job.finished(), "Known unfinished work cannot disappear merely because queues are empty");

        job.nextStep.put(pos, 5);
        check(!job.finished(), "An unloaded partial transition still blocks completion");
        job.nextStep.clear();
        job.done.add(pos);
        check(job.finished(), "Fully migrated generated work finishes after callbacks settle");
        check(job.completed() == 1, "Completed counter counts migrated coordinates");

        job = scanned();
        job.known.add(pos);
        job.unavailable.add(pos);
        check(job.finished(), "An indexed proto chunk does not block existing-world migration");
        check(job.total() == 0 && job.completed() == 0, "Proto chunks never inflate the migrated counter");
        job.unavailable.remove(pos);
        check(!job.finished(), "A newly full chunk must become unfinished when proto status is removed");
        job.queue.add(pos);
        job.queued.add(pos);
        check(job.total() == 1 && !job.finished(), "Newly generated terrain rejoins the migration workload");
        job.queue.clear();
        job.queued.clear();
        job.done.add(pos);
        check(job.finished() && job.completed() == 1, "Only an actual completed migration finishes new terrain");

        job.nextStep.put(pos, 10);
        check(!job.finished(), "A pending transition cannot be hidden by an obsolete done entry");
        check(job.percent() == 99.9, "Pending transition work cannot be advertised as 100 percent");

        ChunkPos proto = new ChunkPos(-14, 27);
        job = scanned();
        check(job.observeIndexed(pos, true), "The index admits newly discovered full terrain");
        job.done.add(pos);
        job.queue.clear(); job.queued.clear();
        check(!job.observeIndexed(proto, false), "A newly indexed proto chunk adds no eligible migration work");
        check(job.finished() && job.total() == 1 && job.completed() == 1, "Proto discovery preserves full-terrain completion");
        job.completed = true;
        for (int scan = 0; scan < 3; scan++) {
            check(!job.observeIndexed(proto, false), "An unchanged proto chunk stays unavailable on rescan");
            check(job.finished() && job.completed && job.total() == 1 && job.completed() == 1,
                    "Repeated disk scans keep completed progress at 100 percent");
            check(job.percent() == 100, "Unchanged proto rescans preserve displayed 100 percent");
            check(job.queue.isEmpty() && job.queued.isEmpty(), "Repeated proto scans enqueue no migration work");
        }
        check(job.observeIndexed(proto, true), "Only an actual full-status promotion creates eligible work");
        check(!job.unavailable.contains(proto) && job.total() == 2 && !job.finished(), "A promoted proto chunk requires migration");
        job.done.add(proto); job.queue.clear(); job.queued.clear();
        check(job.finished() && job.completed() == 2, "Migrating the promoted terrain restores completion");

        job = scanned();
        job.observeFull(pos);
        job.done.add(pos); job.queue.clear(); job.queued.clear();
        check(!job.observeIndexed(pos, false), "An older proto snapshot cannot undo a live full-chunk observation");
        check(job.done.contains(pos) && !job.unavailable.contains(pos) && job.finished(), "Live full terrain retains its completed state after a stale scan");

        job.observeIndexed(proto, true);
        job.columns.put(proto, 128);
        check(job.coveredColumns() == 384, "Global winter coverage includes completed terrain and partial durable coverage");
        job.columns.remove(proto); job.done.add(proto); job.queue.clear(); job.queued.clear();
        check(job.coveredColumns() == 512, "Finalized terrain contributes full coverage exactly once");

        job = scanned();
        job.known.add(pos); job.indexFailures.add(pos); job.failures.put(pos, Long.MAX_VALUE);
        job.observeFull(pos);
        check(!job.indexFailures.contains(pos) && !job.failures.containsKey(pos), "A live full chunk resolves its earlier unreadable generation status");
        check(job.observedFull.contains(pos) && !job.finished(), "Recovered full terrain still requires an actual completed migration");

        obsoleteCallback(new IllegalStateException("Old generation load failure"));
        obsoleteCallback(null);
        savedCheckpoints();

        job = scanned(); job.observeIndexed(pos,true);
        job.nextStep.put(pos,1); job.columns.put(pos,256);
        job.markComplete(pos);
        check(job.finished() && job.percent()==100, "Consumed priority deque slots do not prolong a completed world migration");
        check(job.queue.isEmpty() && job.queued.isEmpty(), "Completion removes a queued region slot without searching the world queue");
        check(job.columns.isEmpty() && job.nextStep.isEmpty(), "Completing a priority chunk retires its partial transition obligations");
        ChunkPos fresh = new ChunkPos(44,55); job.observeIndexed(fresh,true);
        check(!job.finished() && job.queued.equals(Set.of(fresh)), "Genuine new work still blocks completion alongside an obsolete deque slot");

        System.out.println("Migration bookkeeping tests passed: " + assertions + " assertions");
    }
}
