package dev.trueseasons.migration;

/** Real migration bookkeeping with no world/chunk implementation to accidentally load. */
public final class RegionReadinessTests {
    private static int checks;
    public static void main(String[] args) {
        var job = new ChunkMigrationEngine.Job(null);
        ChunkPos first = new ChunkPos(-32, -32), last = new ChunkPos(-1, -1), other = new ChunkPos(0, 0);
        check(!job.regionReady(-1, -1), "Initial indexing blocks even an apparently empty region");
        job.scanned = true;
        check(job.regionReady(-1, -1), "An indexed region with no generated work is complete");
        job.observeIndexed(first, true);
        check(!job.regionReady(-1, -1), "Newly indexed FULL terrain blocks the region");
        job.done.add(first); job.queued.clear(); job.queue.clear();
        check(job.regionReady(-1, -1), "Completed generated terrain opens region rendering");
        job.observeIndexed(last, true);
        check(!job.regionReady(-1, -1), "A remaining far-corner chunk blocks the whole region");
        job.done.add(last); job.queued.clear(); job.queue.clear();
        check(job.regionReady(-1, -1), "All known generated chunks finish the region");
        job.observeIndexed(other, true);
        check(job.regionReady(-1, -1) && !job.regionReady(0, 0), "Other regions can keep migrating without holding this completed region");
        job.scanning = true;
        check(!job.regionReady(-1, -1), "An unfinished discovery snapshot cannot authorize rendering");
        job.scanning = false;
        job.flight.add(first);
        check(!job.regionReady(-1, -1), "An in-flight callback still blocks a stale done entry");
        job.flight.clear(); job.failures.put(last, Long.MAX_VALUE);
        check(!job.regionReady(-1, -1), "A deferred chunk prevents a false completed region");
        job.failures.clear(); job.nextStep.put(first, 1);
        check(!job.regionReady(-1, -1), "A local snow transition must finish before rendering");
        job.nextStep.clear(); job.columns.put(first, 256);
        check(!job.regionReady(-1, -1), "Terminal coverage still needs final seasonal metadata");
        job.columns.clear(); job.queued.add(first);
        check(!job.regionReady(-1, -1), "Requeued current-generation work blocks old completion");
        job.queued.clear();
        ChunkPos proto = new ChunkPos(-2, -2); job.observeIndexed(proto, false);
        check(job.regionReady(-1, -1), "Indexed protochunks are excluded without generating them");
        job.observeFull(proto);
        check(!job.regionReady(-1, -1), "Actual proto-to-FULL promotion immediately closes the region");
        job.done.add(proto); job.queued.remove(proto); job.queue.remove(proto);
        check(job.regionReady(-1, -1), "Finishing newly explored terrain reopens that region");
        job.done.clear();
        check(!job.regionReady(-1, -1), "A season restart cannot reuse prior region completion");
        check(!job.regionReady(Integer.MAX_VALUE, 0), "Malformed region coordinates fail safely");
        System.out.println("Region readiness tests passed: " + checks + " assertions");
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
