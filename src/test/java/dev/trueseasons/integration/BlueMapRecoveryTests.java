package dev.trueseasons.integration;

import dev.trueseasons.integration.RenderLedger.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

/** Source deletion, crash-token recovery, and non-mutating inspection of the reported queue. */
public final class BlueMapRecoveryTests {
    private static int assertions;
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        check(!RenderLedger.sourceDeletionAllowed(false, false, false), "A fresh unsaved unloaded edit cannot become a deletion");
        check(!RenderLedger.sourceDeletionAllowed(false, false, true), "A fresh live edit cannot become a deletion");
        check(!RenderLedger.sourceDeletionAllowed(true, true, true), "Live chunks protect old and proven-saved requests");
        check(RenderLedger.sourceDeletionAllowed(true, false, false), "Recovered final intent can recover removed source");
        check(RenderLedger.sourceDeletionAllowed(false, true, false), "A previously proven saved source can later be trimmed");
        RenderLedger ledger = new RenderLedger(); ChunkKey key = new ChunkKey(UUID.randomUUID(), 64, -32);
        check(!ledger.requiresMarkerRecovery(key, null, false, false), "Untracked current chunks never replay for BlueMap");
        ledger.dirty(key);
        check(ledger.requiresMarkerRecovery(key, "old-winter-token", false, false), "A lost dirty winter token still requires actual finalization");
        Revision dirty = new Revision(key, "");
        check(ledger.replaceWithDeletion(dirty, "source-gone", false), "Recovered dirty empty-token intent can become a deletion");
        Revision absent = new Revision(key, "source-gone"); ledger.acknowledge(absent, "surface");
        check(!ledger.retire(key, List.of("surface", "caves")), "Source deletion still awaits every map receipt");
        ledger.acknowledge(absent, "caves"); check(ledger.retire(key, List.of("surface", "caves")), "Deleted source retires after all map cleanups");
        ledger.restore(key, "winter-final", List.of("surface"));
        check(!ledger.requiresMarkerRecovery(key, "winter-final", false, false), "Correct saved marker avoids world replay before background proof");
        check(ledger.requiresMarkerRecovery(key, null, false, false), "Final marker lost after checkpoint requests bounded recovery");
        check(!ledger.requiresMarkerRecovery(key, null, true, false), "Persisted save proof survives temporary marker purge");
        check(ledger.requiresMarkerRecovery(key, "winter-final", true, true), "A recreated deleted chunk needs a new revision");
        Revision winter = new Revision(key, "winter-final");
        check(ledger.replaceWithDeletion(winter, "deleted-final", false), "Trimming a saved source invalidates old map receipts");
        check(ledger.needs(new Revision(key, "deleted-final"), "surface"), "Previously successful map must remove its stale tiles");
        check(!ledger.acknowledge(winter, "caves"), "Old native completion cannot acknowledge new deleted state");
        ledger.dirty(key); ledger.complete(key, "autumn-final");
        check(!ledger.replaceWithDeletion(new Revision(key, "deleted-final"), "stale-delete", false), "A later Autumn edit supersedes an in-flight absence proof");
        check(!ledger.requiresMarkerRecovery(key, "autumn-final", false, false), "New saved Autumn marker hydrates migration without replay");

        Path testRoot = Path.of("build/test-recovery"); Files.createDirectories(testRoot);
        Path folder = Files.createTempDirectory(testRoot, "source-");
        try {
            try { SavedChunkRevision.sourceAbsent(folder, 0, 0); throw new AssertionError("Missing world directory is deletion proof"); }
            catch (IOException expected) { assertions++; }
            Files.createDirectory(folder.resolve("region"));
            check(SavedChunkRevision.regionAbsent(folder, 2, -1), "Known source directory proves a missing region");
            check(SavedChunkRevision.sourceAbsent(folder, 64, -32), "Missing source file is distinct from an unsaved-marker mismatch");
            Path region = folder.resolve("region/r.0.0.mca");
            try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "rw")) { file.setLength(8192); }
            check(!SavedChunkRevision.regionAbsent(folder, 0, 0), "A present region is not whole-region deletion proof");
            check(SavedChunkRevision.sourceAbsent(folder, 4, 7), "A stable zero location proves an individual missing chunk");
            try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "rw")) {
                file.setLength(12288); file.seek((7 * 32 + 4) * 4L); file.writeInt((2 << 8) | 1);
            }
            check(!SavedChunkRevision.sourceAbsent(folder, 4, 7), "A present chunk with an old token remains pending");
            try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "rw")) { file.setLength(100); }
            try { SavedChunkRevision.sourceAbsent(folder, 4, 7); throw new AssertionError("Truncated save is deletion proof"); }
            catch (IOException expected) { assertions++; }
        } finally {
            try (var files = Files.walk(folder)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
        }
        if (args.length == 1) inspectReportedQueue(Path.of(args[0]));
        System.out.println("BlueMap recovery tests passed: " + assertions + " assertions");
    }
    private static void inspectReportedQueue(Path server) throws IOException {
        Path directory = server.resolve("plugins/TrueSeasons/state"); Map<String, byte[]> state = new LinkedHashMap<>();
        long sequence;
        try (DataInputStream input = new DataInputStream(Files.newInputStream(directory.resolve("bluemap.snapshot")))) {
            check(input.readInt() == 0x5453424d && input.readInt() == 1, "Reported snapshot has expected framing");
            sequence = input.readLong(); int count = input.readInt();
            for (int n = 0; n < count; n++) readRecord(input, state, -1);
        }
        Path journal = directory.resolve("bluemap.journal");
        if (Files.exists(journal)) try (DataInputStream input = new DataInputStream(Files.newInputStream(journal))) {
            while (input.available() > 0) readRecord(input, state, sequence);
        }
        Set<String> regions = new HashSet<>(); int pending = 0, missing = 0, saved = 0, dirty = 0;
        for (var entry : state.entrySet()) if (!entry.getKey().equals("__format")) {
            try (DataInputStream row = new DataInputStream(new ByteArrayInputStream(entry.getValue()))) {
                boolean present = row.readBoolean(); String token = "";
                if (present) {
                    pending++; token = row.readUTF(); row.readBoolean(); if (row.readBoolean()) saved++;
                    if (token.isEmpty()) dirty++;
                    int maps = row.readInt(); for (int n = 0; n < maps; n++) row.readUTF();
                }
                if (row.readBoolean()) { row.readUTF(); row.readBoolean(); }
                Path folder = Path.of(row.readUTF()); if (!folder.isAbsolute()) folder = server.resolve(folder);
                String[] fields = entry.getKey().split("/"); int x = Integer.parseInt(fields[1]), z = Integer.parseInt(fields[2]);
                if (present && SavedChunkRevision.sourceAbsent(folder, x, z)) {
                    missing++; regions.add(fields[0] + "/" + Math.floorDiv(x, 32) + "/" + Math.floorDiv(z, 32));
                }
            }
        }
        check(pending == 12033 && missing == pending && saved == 0 && dirty == 0, "Reported idle queue consists of removed-source final revisions");
        check(regions.size() == 12, "Removed-source requests explain the exact twelve stuck regions");
        System.out.println("Original state inspected without modification: " + pending + " absent-source chunks in " + regions.size() + " regions");
    }
    private static void readRecord(DataInputStream input, Map<String, byte[]> state, long after) throws IOException {
        int size = input.readInt(); if (size < 14 || size > 1_048_576) throw new IOException("Invalid journal frame");
        byte[] payload = new byte[size]; input.readFully(payload); int expected = input.readInt();
        CRC32 checksum = new CRC32(); checksum.update(payload); if ((int) checksum.getValue() != expected) throw new IOException("Queue checksum mismatch");
        try (DataInputStream body = new DataInputStream(new ByteArrayInputStream(payload))) {
            long sequence = body.readLong(); String key = body.readUTF(); int count = body.readInt();
            byte[] value = new byte[count]; body.readFully(value);
            if (sequence > after) { if (count == 0) state.remove(key); else state.put(key, value); }
        }
    }
}
