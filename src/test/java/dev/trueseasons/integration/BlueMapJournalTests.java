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
}
