package dev.trueseasons.migration;

import java.util.*;

public final class RegionOrderTests {
    private static int assertions;
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static ChunkPos poll(ChunkMigrationEngine.Job job) {
        ChunkPos next = job.queue.pollFirst(r -> job.regionReady(r.x(),r.z()));
        if (next != null) job.queued.remove(next); return next;
    }
    public static void main(String[] args) {
        var job = new ChunkMigrationEngine.Job(null); job.scanned = true;
        ChunkPos a = new ChunkPos(-32,-32), b = new ChunkPos(-1,-1), other = new ChunkPos(0,-32);
        job.observeIndexed(other,true); job.observeIndexed(b,true); job.observeIndexed(a,true);
        check(poll(job).equals(a), "Region and slot order are independent of input discovery order");
        job.flight.add(a);
        check(poll(job).equals(b), "All queued siblings are selected before another region");
        job.markComplete(b);
        check(poll(job) == null, "Empty queue waits for in-flight sibling rather than starting another region");
        job.markComplete(a);
        check(poll(job) == null, "Final application must release its chunk load before region advance");
        job.flight.remove(a); job.failures.put(a, 1L);
        check(poll(job) == null, "Deferred region work blocks advancement");
        job.failures.clear();
        check(poll(job).equals(other), "Completed region releases the next numeric region");
        ChunkPos discoveredEarlier = new ChunkPos(-64,-64);
        job.observeIndexed(discoveredEarlier,true);
        check(poll(job) == null, "New discovery cannot interrupt the active region");
        job.markComplete(other);
        check(poll(job).equals(discoveredEarlier), "Deferred discovery joins only after the active region finishes");
        job.markComplete(discoveredEarlier);
        check(job.finished(), "Region scheduling preserves total completion");

        // 50x a 4,096-chunk test world: no world-size search for each chunk selection.
        job = new ChunkMigrationEngine.Job(null); job.scanned = true;
        final int total = 204_800;
        for (int i = total - 1; i >= 0; i--) job.observeIndexed(new ChunkPos(i % 640 - 320, i / 640 - 160),true);
        RegionWorkQueue.Region previous = null;
        Set<RegionWorkQueue.Region> completedRegions = new HashSet<>(); int processed = 0;
        for (ChunkPos next; (next = poll(job)) != null;) {
            var region = RegionWorkQueue.Region.of(next);
            if (!region.equals(previous)) {
                if (previous != null) {
                    check(job.regionReady(previous.x(),previous.z()), "Every prior region is complete before the next starts");
                    completedRegions.add(previous);
                }
                check(!completedRegions.contains(region), "No random revisit to a completed region"); previous = region;
            }
            job.markComplete(next); processed++;
        }
        check(processed == total && job.finished(), "All 204,800 chunks settle without holes or generation");
        System.out.println("Region order tests passed: " + assertions + " assertions");
    }
}
