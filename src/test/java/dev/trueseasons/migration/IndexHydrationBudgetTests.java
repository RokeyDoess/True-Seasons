package dev.trueseasons.migration;

import java.lang.reflect.*;
import java.util.*;

/** Verify the real hydrator's count/deadline bounds without a server-sized callback. */
public final class IndexHydrationBudgetTests {
    private static int checks;
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    public static void main(String[] args) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe"); Field singleton = unsafe.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        Object engine = unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),ChunkMigrationEngine.class);
        var job = new ChunkMigrationEngine.Job(null); job.scanning = true;
        for (int i = 0; i < 204_800; i++) job.diskKnown.add(new ChunkPos(i,0));
        Class<?> type = Class.forName("dev.trueseasons.migration.ChunkMigrationEngine$IndexApply");
        Constructor<?> constructor = type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        Object work = constructor.newInstance(job,new GeneratedChunkIndex.Snapshot(List.of(),List.of(),Map.of(),Map.of()),Set.copyOf(job.diskKnown));
        Field indexing = ChunkMigrationEngine.Job.class.getDeclaredField("indexing"); indexing.setAccessible(true); indexing.set(job,work);
        Field iterator = type.getDeclaredField("previous"); iterator.setAccessible(true);
        Method apply = ChunkMigrationEngine.class.getDeclaredMethod("applyIndex",ChunkMigrationEngine.Job.class,long.class); apply.setAccessible(true);
        apply.invoke(engine,job,0L);
        check(!job.scanned && job.scanning && indexing.get(job) == work, "Expired budget preserves pending index work");
        apply.invoke(engine,job,Long.MAX_VALUE);
        int remaining = 0; for (var rows = (Iterator<?>)iterator.get(work); rows.hasNext();) { rows.next(); remaining++; }
        check(remaining == 204_800 - 256, "One large-world hydration call examines at most 256 records");
        check(!job.scanned && job.scanning, "Partial hydration cannot release migration or BlueMap readiness");
        apply.invoke(engine,job,Long.MAX_VALUE);
        check(job.scanned && !job.scanning && indexing.get(job) == null, "Exhausted phases release readiness only after completion");
        check(job.diskKnown.size() == 204_800, "Present saved terrain is not removed by bounded pruning");
        System.out.println("Index hydration budget tests passed: " + checks + " assertions");
    }
}
