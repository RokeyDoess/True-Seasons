package dev.trueseasons.integration;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Durable intent replay, linear write volume, torn writes, and concurrent snapshot fencing. */
public final class BlueMapJournalTests {
    private static int assertions;
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String value(DurableRenderJournal journal, String key) {
        byte[] value = journal.state().get(key); return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }
    private static void crash(DurableRenderJournal journal) throws Exception {
        Field field = DurableRenderJournal.class.getDeclaredField("channel"); field.setAccessible(true);
        ((FileChannel) field.get(journal)).close(); // No snapshot or orderly close: simulate process loss.
    }
    private static final class PausedExecutor implements Executor {
        Runnable work;
        public void execute(Runnable work) { this.work = work; }
        void run() { work.run(); }
    }
    public static void main(String[] args) throws Exception {
        Path tests = Path.of("build/test-journals"); Files.createDirectories(tests);
        Path folder = Files.createTempDirectory(tests, "trueseasons-journal-");
        try {
            deferredFinalCrash(folder.resolve("deferred-final"), 0);
            deferredFinalCrash(folder.resolve("deferred-final-grace"), 1);
            recoveredIntentFence(folder.resolve("recovery-fence"), tests.resolve("recovery-fence-"+UUID.randomUUID()+".jfr"));
            DurableRenderJournal journal = new DurableRenderJournal(folder);
            journal.put("chunk-a", bytes("dirty"), true); crash(journal);
            journal = new DurableRenderJournal(folder);
            check(value(journal, "chunk-a").equals("dirty"), "Intent survives crash before any final marker");
            journal.put("chunk-a", bytes("final-revision-1-unsaved"), true);
            journal.put("chunk-b", bytes("dirty"), true); crash(journal);
            journal = new DurableRenderJournal(folder);
            check(value(journal, "chunk-a").equals("final-revision-1-unsaved"), "Final intent survives without graceful close");
            check(value(journal, "chunk-b").equals("dirty"), "Other unfinished chunks remain independently tracked");
            journal.put("chunk-a", bytes("final-revision-1-saved-map1-purge"), true);
            PausedExecutor executor = new PausedExecutor(); CompletableFuture<Boolean> snapshot = journal.compactAsync(executor);
            journal.put("chunk-a", bytes("final-revision-2-unsaved"), true);
            journal.put("chunk-b", new byte[0], false);
            journal.put("chunk-c", bytes("saved-map1-not-map2"), true);
            byte[] oldJournal = Files.readAllBytes(folder.resolve("bluemap.journal"));
            executor.run(); check(snapshot.join(), "Snapshot completes off-thread"); crash(journal);
            journal = new DurableRenderJournal(folder);
            check(value(journal, "chunk-a").equals("final-revision-2-unsaved"), "Mutation appended during snapshot supersedes snapshot revision");
            check(value(journal, "chunk-b") == null, "Retirement during snapshot remains retired");
            check(value(journal, "chunk-c").equals("saved-map1-not-map2"), "Independent map receipts survive snapshot fencing");
            crash(journal);
            // Simulate a crash after atomic snapshot installation and before old journal truncation.
            Files.write(folder.resolve("bluemap.journal"), oldJournal);
            journal = new DurableRenderJournal(folder);
            check(value(journal, "chunk-a").equals("final-revision-2-unsaved"), "Old duplicated records cannot overwrite a newer snapshot");
            check(value(journal, "chunk-b") == null, "Old journal replay respects the snapshot sequence");
            crash(journal);
            long originalSize = Files.size(folder.resolve("bluemap.journal"));
            try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(folder.resolve("bluemap.journal"), StandardOpenOption.APPEND))) {
                output.writeInt(128); output.writeLong(99); output.writeUTF("torn-intent");
            }
            journal = new DurableRenderJournal(folder);
            check(value(journal, "chunk-a").equals("final-revision-2-unsaved"), "Torn trailing append preserves all prior records");
            check(Files.size(folder.resolve("bluemap.journal")) == originalSize, "Torn append is removed before further writes");
            journal.put("after-torn-write", bytes("dirty"), true); crash(journal);
            journal = new DurableRenderJournal(folder);
            check(value(journal, "after-torn-write").equals("dirty"), "Appending after crash recovery remains valid");
            long before = Files.size(folder.resolve("bluemap.journal"));
            for (int n = 0; n < 5000; n++) {
                journal.put("migration-" + n, bytes("dirty"), false);
                journal.put("migration-" + n, bytes("final-" + n + "-saved-map1-not-map2"), false);
            }
            journal.put("barrier", bytes("durable"), true);
            long written = Files.size(folder.resolve("bluemap.journal")) - before;
            check(written < 1_000_000, "Five thousand chunks write linear-sized deltas instead of growing whole-ledger YAML");
            crash(journal); journal = new DurableRenderJournal(folder);
            check(value(journal, "migration-0").equals("final-0-saved-map1-not-map2"), "Large migration first revision survives restart");
            check(value(journal, "migration-4999").equals("final-4999-saved-map1-not-map2"), "Large migration last revision survives restart");
            journal.close();
            check(Files.size(folder.resolve("bluemap.journal")) == 0, "Graceful close compacts all retained requests");
            journal = new DurableRenderJournal(folder);
            check(value(journal, "migration-4999").equals("final-4999-saved-map1-not-map2"), "Atomic snapshot independently restores retained intent");
            journal.put("checksum-test", bytes("dirty"), true); crash(journal);
            byte[] damaged = Files.readAllBytes(folder.resolve("bluemap.journal")); damaged[damaged.length - 1] ^= 1;
            Files.write(folder.resolve("bluemap.journal"), damaged);
            try { new DurableRenderJournal(folder); throw new AssertionError("Checksum corruption silently accepted"); }
            catch (IOException expected) { assertions++; }
            if (args.length == 1) check(SavedChunkRevision.matches(Path.of(args[0]), 1, -32,
                    "trueseasons:bluemap_revision", "12705f14-ffe9-40a2-91fa-d4eb3a3bb498"), "Real Paper ChunkBukkitValues fixture proves the exact queued final revision");
        } finally {
            if (!folder.toRealPath().startsWith(tests.toRealPath()) || folder.toRealPath().equals(tests.toRealPath()))
                throw new IOException("Test cleanup escaped its journal directory");
            try (var paths = Files.walk(folder)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("BlueMap journal tests passed: " + assertions + " assertions");
    }
    private static void recoveredIntentFence(Path folder, Path capture)throws Exception{
        DurableRenderJournal initial=new DurableRenderJournal(folder);
        initial.put("recovered-edit",bytes("dirty"),false);crash(initial);
        Path file=folder.resolve("bluemap.journal");long prefix=Files.size(file);
        Files.write(file,new byte[]{0,0,0,127,7},StandardOpenOption.APPEND);
        DurableRenderJournal recovered=null;
        try(var recording=new jdk.jfr.Recording()){
            recording.enable("jdk.FileForce").withThreshold(java.time.Duration.ZERO).withStackTrace();recording.start();
            try{recovered=new DurableRenderJournal(folder);}finally{recording.stop();recording.dump(capture);}
            check(value(recovered,"recovered-edit").equals("dirty")&&Files.size(file)==prefix,"Recovered intent remains intact and its torn tail is removed");
            boolean stabilized=false;
            try(var events=new jdk.jfr.consumer.RecordingFile(capture)){
                while(events.hasMoreEvents()){
                    var event=events.readEvent();if(!event.getEventType().getName().equals("jdk.FileForce"))continue;
                    String path=event.getString("path");var trace=event.getStackTrace();
                    if(path!=null&&Path.of(path).toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())&&trace!=null
                            &&trace.getFrames().stream().anyMatch(frame->frame.getMethod().getType().getName().equals(DurableRenderJournal.class.getName())&&frame.getMethod().getName().equals("<init>")))stabilized=true;
                }
            }
            check(stabilized,"Recovered intent is forced before it can authorize another world edit after a process-only crash");
        }finally{if(recovered!=null)recovered.close();}
    }
    private static void deferredFinalCrash(Path folder, int age) throws Exception {
        RenderLedger ledger = new RenderLedger(); FinalRevisionDurability durability = new FinalRevisionDurability();
        RenderLedger.ChunkKey key = new RenderLedger.ChunkKey(UUID.randomUUID(), 3, 7);
        RenderLedger.ChunkKey next = new RenderLedger.ChunkKey(key.world(), 4, 7);
        check(durability.forceBeforeMarker(key), "Fresh final adoption without a durable dirty intent still forces immediately");
        DurableRenderJournal journal = new DurableRenderJournal(folder);
        ledger.dirty(key); journal.put("edited", bytes("dirty"), true); durability.forced(ledger, List.of(key));
        long durablePrefix = Files.size(folder.resolve("bluemap.journal"));
        check(!durability.forceBeforeMarker(key), "Previously forced pre-edit intent permits deferred final durability");
        ledger.complete(key, "unforced-final"); journal.put("edited", bytes("unforced-final"), false); durability.appendedFinal(key, 100);
        check(durability.due(100, 128).isEmpty(), "Final append does not immediately force in its completion tick");
        check(durability.due(101, 128).isEmpty(), "One intervening tick allows the next edit barrier to cover earlier finals");
        check(durability.due(102, 128).equals(List.of(key)), "Idle finals still reach a bounded durability deadline");
        check(durability.due(100 + age, 128).isEmpty(), "Crash fixture loses only a final still inside its grace window");
        crash(journal);
        // Power loss may discard the entire unforced tail even if its marker reached the chunk save first.
        try (FileChannel file = FileChannel.open(folder.resolve("bluemap.journal"), StandardOpenOption.WRITE)) { file.truncate(durablePrefix); }
        journal = new DurableRenderJournal(folder);
        check(value(journal, "edited").equals("dirty"), "Losing an unforced final never loses the earlier durable edit intent");
        RenderLedger recovered = new RenderLedger(); recovered.restore(key, "", List.of());
        FinalRevisionDurability retry = new FinalRevisionDurability(); retry.restored(recovered);
        check(recovered.requiresMarkerRecovery(key, "unforced-final", false, false, true), "Saved-current snow with lost final still requests token finalization");
        check(!retry.forceBeforeMarker(key), "Recovered durable dirty intent can safely finalize without world replay");
        recovered.complete(key, "recovered-final"); journal.put("edited", bytes("recovered-final"), false); retry.appendedFinal(key, 101);
        check(retry.due(102, 128).isEmpty(), "The next pre-edit force can cover a final before a duplicate periodic flush");
        recovered.dirty(next); journal.put("next-edit", bytes("dirty"), true); retry.forced(recovered, List.of(next));
        check(retry.due(102, 128).isEmpty(), "The next mandatory pre-edit force also makes earlier finals durable");
        check(!recovered.acknowledge(new RenderLedger.Revision(key, "unforced-final"), "surface"), "Lost revision receipts cannot acknowledge recovered final tokens");
        crash(journal); journal = new DurableRenderJournal(folder);
        check(value(journal, "edited").equals("recovered-final"), "Shared pre-edit force persists the recovered final across restart");
        check(value(journal, "next-edit").equals("dirty"), "Shared force still protects the next physical edit intent");
        for (int n = 0; n < 130; n++) retry.appendedFinal(new RenderLedger.ChunkKey(key.world(), n, 8), 103);
        check(retry.due(104, 128).isEmpty(), "A burst retains the same short opportunity to share a durability barrier");
        check(retry.due(105, 128).size() == 128, "Deferred final batch remains bounded under a burst");
        retry.forced(recovered, List.of(next));
        check(retry.due(106, 128).isEmpty(), "One global force covers every earlier final, including beyond the batch limit");
        retry.appendedFinal(key, Integer.MAX_VALUE - 1);
        check(retry.due(Integer.MAX_VALUE, 128).isEmpty(), "Tick-counter wrap preserves the intervening-tick grace");
        check(retry.due(Integer.MIN_VALUE, 128).equals(List.of(key)), "Tick-counter wrap cannot strand final durability");
        journal.close();
    }
}
