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
        check(job.regionProcessed(-1,-1),"Already-known completed terrain can release its processing region during a background rescan");
        check(!job.regionProcessed(0,0),"A background rescan cannot make unfinished known terrain count as processed");
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
        slowScanCooldown();
        String error=ChunkMigrationEngine.indexError(1,new ChunkPos(-32,17),"Unexpected saved ChunkBukkitValues type");
        check(error.contains("1 saved chunk has")&&error.contains("(-32, 17)")&&error.contains("ChunkBukkitValues"),"Unreadable chunk diagnostics identify an affected coordinate and reason");
        String bounded=ChunkMigrationEngine.indexError(2,new ChunkPos(0,0),"bad\n".repeat(100));
        check(bounded.length()<200&&!bounded.contains("\n")&&!bounded.contains("\r")&&bounded.endsWith("..."),"Index failure details remain bounded and single-line");
        check(ChunkMigrationEngine.indexError(2,null,null).equals("2 saved chunks have unreadable metadata"),"Failure counts remain useful when a live chunk superseded the sampled error");
        var errors=new ChunkMigrationEngine.Job(null);ChunkPos resolved=new ChunkPos(-32,17),remaining=new ChunkPos(0,0);
        errors.indexFailures.add(resolved);errors.indexFailures.add(remaining);errors.describeIndexFailure(resolved,"bad metadata");
        check(errors.error().contains("(-32, 17)"),"A sampled failure identifies a currently unreadable chunk");
        errors.indexFailures.remove(resolved);errors.describeIndexFailure(resolved,"bad metadata");
        check(errors.error().equals("1 saved chunk has unreadable metadata"),"A live recovery never leaves a stale coordinate in the diagnostic");
        errors.indexFailures.clear();errors.describeIndexFailure(resolved,"bad metadata");
        check(errors.error()==null,"Recovering all unreadable chunks clears the diagnostic");
        System.out.println("Region readiness tests passed: " + checks + " assertions");
    }
    private static void slowScanCooldown(){
        var job=new ChunkMigrationEngine.Job(null);job.scanned=true;
        ChunkPos pos=new ChunkPos(0,0);job.observeIndexed(pos,true);job.markComplete(pos);
        job.beginScan(1000);
        check(!job.rescanDue(61000,30),"A scan exceeding its interval cannot enqueue another scan while active");
        check(!job.regionReady(0,0),"A slow active scan still holds final map readiness");
        job.finishScan(61000);
        check(job.regionReady(0,0)&&job.finished(),"Completing a slow scan opens map readiness and completion");
        check(!job.rescanDue(61000,30)&&!job.rescanDue(90999,30),"Cooldown after a slow scan leaves a full interval for rendering and progress");
        check(job.rescanDue(91000,30),"Another discovery check becomes due after the complete interval");
        job.beginScan(91000);job.finishScan(125000);job.scanned=false;
        check(!job.rescanDue(125000,30)&&!job.rescanDue(154999,30),"A failed long scan also avoids immediate repeated disk work");
        check(job.rescanDue(155000,30)&&!job.regionReady(0,0),"Failure retry waits for its interval without authorizing unverified rendering");
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
