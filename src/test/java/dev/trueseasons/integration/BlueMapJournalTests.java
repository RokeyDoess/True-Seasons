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
            bufferedReplay(folder.resolve("buffered-replay"));
            snapshotGenerations(folder.resolve("snapshot-generations"));
            recoveryReaders(folder.resolve("recovery-readers"));
            recoveryRecordBounds(folder.resolve("recovery-bounds"));
            corruptSnapshotSizing(folder.resolve("snapshot-sizing"));
            canonicalFrameEncoding(folder.resolve("frame-encoding"));
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
    private static void bufferedReplay(Path folder)throws Exception{
        Map<String,byte[]> expected=new LinkedHashMap<>();
        DurableRenderJournal journal=new DurableRenderJournal(folder);
        for(int n=0;n<32;n++){
            byte[] value=new byte[9_001+n];Arrays.fill(value,(byte)n);expected.put("saved-"+n,value);
        }
        journal.putAll(expected,true);journal.close();
        journal=new DurableRenderJournal(folder);
        byte[] large=new byte[90_003];new Random(19).nextBytes(large);
        journal.put("large-record",large,false);expected.put("large-record",large);
        journal.put("saved-7",new byte[0],false);expected.remove("saved-7");
        journal.put("saved-15",bytes("newer"),true);expected.put("saved-15",bytes("newer"));
        Path log=folder.resolve("bluemap.journal");byte[] prefix=Files.readAllBytes(log);
        journal.put("torn-record",large,true);crash(journal);
        byte[] complete=Files.readAllBytes(log),last=Arrays.copyOfRange(complete,prefix.length,complete.length);
        for(int length:new int[]{1,3,20,last.length-1}){
            Files.write(log,prefix);Files.write(log,Arrays.copyOf(last,length),StandardOpenOption.APPEND);
            journal=new DurableRenderJournal(folder);Map<String,byte[]> actual=journal.state();
            check(Files.size(log)==prefix.length,"Buffered replay truncates only the unvalidated trailing frame");
            check(actual.keySet().equals(expected.keySet()),"Buffered snapshot and journal replay retain exact keys and tombstones");
            for(var entry:expected.entrySet())check(Arrays.equals(actual.get(entry.getKey()),entry.getValue()),"Frames crossing read-buffer boundaries retain exact values");
            journal.put("after-recovery",bytes("durable"),true);crash(journal);
            journal=new DurableRenderJournal(folder);
            check(value(journal,"after-recovery").equals("durable"),"Appending after buffered torn-tail recovery starts at the validated boundary");
            crash(journal);
        }
    }
    private static void snapshotGenerations(Path folder)throws Exception{
        DurableRenderJournal journal=new DurableRenderJournal(folder);Map<String,String> expected=new LinkedHashMap<>();
        for(int n=0;n<128;n++){String key="chunk-"+n;expected.put(key,"initial-"+n);journal.put(key,bytes(expected.get(key)),false);}
        byte[] detached=bytes("original");journal.put("detached",detached,true);expected.put("detached","original");detached[0]='X';
        journal.state().get("detached")[0]='Y';check(value(journal,"detached").equals("original"),"Input arrays and returned state copies cannot mutate frozen snapshot values");
        PausedExecutor executor=new PausedExecutor();var first=journal.compactAsync(executor);
        check(journal.compactAsync(task->{throw new AssertionError("Duplicate snapshot worker");})==first,"An outstanding snapshot cannot create another generation or worker");
        journal.put("chunk-7",bytes("replacement"),true);expected.put("chunk-7","replacement");
        journal.put("chunk-15",new byte[0],true);expected.remove("chunk-15");
        executor.run();first.join();
        for(var row:expected.entrySet())check(value(journal,row.getKey()).equals(row.getValue()),"Post-capture replacements retain precedence over a frozen base");
        check(value(journal,"chunk-15")==null,"Post-capture tombstones still hide entries present in the frozen generation");
        PausedExecutor secondExecutor=new PausedExecutor();var second=journal.compactAsync(secondExecutor);
        Thread worker=new Thread(secondExecutor.work,"journal-snapshot-test");worker.start();
        for(int n=0;n<512;n++){String key="chunk-"+(n%128),value="concurrent-"+n;journal.put(key,bytes(value),false);expected.put(key,value);}
        worker.join();second.join();journal.put("barrier",bytes("durable"),true);expected.put("barrier","durable");
        crash(journal);journal=new DurableRenderJournal(folder);
        check(journal.state().size()==expected.size(),"Concurrent generation replay preserves exact key membership");
        for(var row:expected.entrySet())check(value(journal,row.getKey()).equals(row.getValue()),"Concurrent snapshot installation and tail replay retain the latest complete value");
        DurableRenderJournal current=journal;
        try{current.compactAsync(task->{throw new RejectedExecutionException("executor closed");});throw new AssertionError("Rejected snapshot hidden");}catch(RejectedExecutionException expectedFailure){assertions++;}
        journal.put("after-rejection",bytes("retained"),true);expected.put("after-rejection","retained");
        Path blocked=folder.resolve("bluemap.snapshot.tmp");Files.createDirectory(blocked);
        PausedExecutor failedExecutor=new PausedExecutor();var failed=journal.compactAsync(failedExecutor);failedExecutor.run();
        try{failed.join();throw new AssertionError("Failed snapshot accepted");}catch(CompletionException expectedFailure){assertions++;}
        check(value(journal,"after-rejection").equals("retained"),"A failed disk snapshot cannot discard the newly published base or its overlay");Files.delete(blocked);
        journal.put("after-failure",bytes("retry"),true);expected.put("after-failure","retry");
        PausedExecutor retryExecutor=new PausedExecutor();var retry=journal.compactAsync(retryExecutor);retryExecutor.run();retry.join();
        check(!journal.compactAsync(task->{throw new AssertionError("Unchanged snapshot performed background work");}).join(),"An unchanged compacted queue skips duplicate copying and disk work");
        journal.close();journal=new DurableRenderJournal(folder);
        for(var row:expected.entrySet())check(value(journal,row.getKey()).equals(row.getValue()),"Retry after snapshot failure preserves all durable values");
        journal.close();
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
    private static void recoveryReaders(Path folder)throws Exception{
        try(var journal=new DurableRenderJournal(folder)){
            journal.put("retained",bytes("original"),true);journal.put("removed",bytes("old"),false);
            PausedExecutor executor=new PausedExecutor();var snapshot=journal.compactAsync(executor);
            journal.put("retained",bytes("replacement"),true);journal.put("removed",new byte[0],true);
            journal.put("new",bytes("latest"),true);
            check(journal.contains("retained")&&journal.contains("new")&&!journal.contains("removed")&&!journal.contains("missing"),"Recovery membership respects replacements and tombstones over a frozen snapshot");
            for(int round=0;round<2;round++){
                Map<String,String> read=new LinkedHashMap<>();
                journal.readRecords((key,input)->{byte[] value=input.readAllBytes();read.put(key,new String(value,StandardCharsets.UTF_8));Arrays.fill(value,(byte)0);});
                check(read.equals(Map.of("retained","replacement","new","latest")),"Independent recovery readers retain exact live values without exposing mutable journal arrays");
            }
            IOException failure=new IOException("decoder rejected record");
            try{journal.readRecords((key,input)->{throw failure;});throw new AssertionError("Decoder failure hidden");}
            catch(IOException expected){check(expected==failure,"Recovery propagates a decoder failure without replacing its cause");}
            executor.run();snapshot.join();
            check(value(journal,"retained").equals("replacement")&&!journal.contains("removed"),"A failed decoder cannot alter later durable state or snapshot installation");
        }
        try(var journal=new DurableRenderJournal(folder)){
            Map<String,String> restored=new LinkedHashMap<>();journal.readRecords((key,input)->restored.put(key,new String(input.readAllBytes(),StandardCharsets.UTF_8)));
            check(restored.equals(Map.of("retained","replacement","new","latest")),"Recovery readers preserve exact values across a real close and restart");
        }
    }
    private static void recoveryRecordBounds(Path folder)throws Exception{
        Map<String,String> expected=new LinkedHashMap<>();expected.put("large","\u96ea".repeat(20_000));
        expected.put("small","ok");expected.put("unicode","\u0000\u03a9\ud83c\udf42\ud800");
        try(var journal=new DurableRenderJournal(folder)){
            for(var row:expected.entrySet()){
                var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeUTF(row.getValue());}
                journal.put(row.getKey(),bytes.toByteArray(),false);
            }
            Map<String,String> decoded=new LinkedHashMap<>();
            journal.readRecords((key,input)->{decoded.put(key,input.readUTF());check(input.read()==-1,"A reused decoder sees EOF at the current record boundary");});
            check(decoded.equals(expected),"A reused UTF buffer preserves large, short, null, multibyte and surrogate values exactly");
            journal.put("broken",new byte[]{0,5,65},true);journal.put("following",new byte[]{66,67,68,69,70},true);
            try{journal.readRecords((key,input)->{if(key.equals("broken"))input.readUTF();});throw new AssertionError("Short record borrowed bytes from the next record");}
            catch(EOFException expectedFailure){assertions++;}
            check(Arrays.equals(journal.state().get("following"),new byte[]{66,67,68,69,70}),"A failed decode cannot consume or alter a following record");
        }
    }
    private static void corruptSnapshotSizing(Path folder)throws Exception{
        try(var journal=new DurableRenderJournal(folder)){journal.put("one-record",bytes("retained"),true);}
        Path file=folder.resolve("bluemap.snapshot");byte[] snapshot=Files.readAllBytes(file);
        java.nio.ByteBuffer.wrap(snapshot).putInt(16,10_000_000);Files.write(file,snapshot);
        var threads=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();threads.setThreadAllocatedMemoryEnabled(true);
        long id=Thread.currentThread().threadId(),before=threads.getThreadAllocatedBytes(id);
        try{new DurableRenderJournal(folder);throw new AssertionError("Incomplete snapshot accepted");}catch(EOFException expected){assertions++;}
        check(threads.getThreadAllocatedBytes(id)-before<4L*1024*1024,"A plausible but corrupt record count cannot cause a large eager hash-table allocation");
        check(Arrays.equals(Files.readAllBytes(file),snapshot),"Sizing failure leaves the original snapshot available for recovery");
    }
    private static void canonicalFrameEncoding(Path folder)throws Exception{
        String[] keys={"ascii","\u0000\u03a9\ud83c\udf42\ud800","\u96ea".repeat(21_800),"short","last"};
        var expected=new ByteArrayOutputStream();
        try(var journal=new DurableRenderJournal(folder)){
            for(int n=0;n<keys.length;n++){
                byte[] value=new byte[n==2?90_003:n+1];new Random(91+n).nextBytes(value);
                journal.put(keys[n],value,true);expected.write(canonicalFrame(n+1,keys[n],value));
            }
            byte[] before=Files.readAllBytes(folder.resolve("bluemap.journal"));
            check(Arrays.equals(before,expected.toByteArray()),"Journal frames remain byte-for-byte compatible with DataOutput framing and modified UTF across scratch-buffer reuse");
            try{journal.put("\u96ea".repeat(21_846),new byte[]{1},true);throw new AssertionError("Oversized UTF key accepted");}catch(UTFDataFormatException expectedFailure){assertions++;}
            try{journal.put("too-large",new byte[1_048_576],true);throw new AssertionError("Oversized record accepted");}catch(IOException expectedFailure){assertions++;}
            check(Arrays.equals(Files.readAllBytes(folder.resolve("bluemap.journal")),before),"Rejected frame sizes leave the journal position, sequence and durable prefix intact");
            journal.put("after-rejection",bytes("valid"),true);check(value(journal,"after-rejection").equals("valid"),"Scratch encoding remains usable after a rejected frame");
        }
        try(var journal=new DurableRenderJournal(folder)){for(String key:keys)check(journal.contains(key),"Canonical UTF keys recover after snapshot installation");}
    }
    private static byte[] canonicalFrame(long at,String key,byte[] value)throws IOException{
        var bodyBytes=new ByteArrayOutputStream();try(var body=new DataOutputStream(bodyBytes)){body.writeLong(at);body.writeUTF(key);body.writeInt(value.length);body.write(value);}
        byte[] body=bodyBytes.toByteArray();var checksum=new java.util.zip.CRC32();checksum.update(body);
        var frameBytes=new ByteArrayOutputStream();try(var frame=new DataOutputStream(frameBytes)){frame.writeInt(body.length);frame.write(body);frame.writeInt((int)checksum.getValue());}
        return frameBytes.toByteArray();
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
