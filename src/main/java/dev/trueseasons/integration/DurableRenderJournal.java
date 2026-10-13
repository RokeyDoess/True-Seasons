package dev.trueseasons.integration;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;

/** Constant-size ordered changes, with atomic snapshots built away from the server thread. */
public final class DurableRenderJournal implements AutoCloseable {
    private static final int MAGIC = 0x5453424d, VERSION = 1, MAX_FRAME = 1_048_576;
    private final Path snapshot, journal;
    private final Map<String, byte[]> state = new LinkedHashMap<>();
    private final List<byte[]> tail = new ArrayList<>();
    private FileChannel channel;
    private long sequence, snapshotSequence;
    private CompletableFuture<Boolean> compaction;
    public DurableRenderJournal(Path directory) throws IOException {
        Files.createDirectories(directory);
        snapshot = directory.resolve("bluemap.snapshot"); journal = directory.resolve("bluemap.journal");
        if (Files.exists(snapshot)) {
            try (DataInputStream input = new DataInputStream(Files.newInputStream(snapshot))) {
                if (input.readInt() != MAGIC || input.readInt() != VERSION) throw new IOException("Unknown BlueMap snapshot format");
                sequence = snapshotSequence = input.readLong(); int count = input.readInt();
                if (count < 0 || count > 10_000_000) throw new IOException("Invalid BlueMap snapshot size");
                for (int n = 0; n < count; n++) apply(readFrame(input), true);
                if (input.read() != -1) throw new IOException("Trailing BlueMap snapshot data");
            }
        }
        long validBytes = 0;
        if (Files.exists(journal)) {
            try (RandomAccessFile input = new RandomAccessFile(journal.toFile(), "r")) {
                while (input.getFilePointer() < input.length()) {
                    byte[] frame;
                    try { frame = readFrame(input); }
                    catch (EOFException tornWrite) { break; }
                    apply(frame, false); validBytes = input.getFilePointer();
                }
            }
        }
        channel = FileChannel.open(journal, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        channel.truncate(validBytes); channel.position(validBytes);
    }
    public synchronized boolean hasSnapshot() { return Files.exists(snapshot); }
    public synchronized Map<String, byte[]> state() {
        Map<String, byte[]> copy = new LinkedHashMap<>(); state.forEach((key, value) -> copy.put(key, value.clone())); return copy;
    }
    public synchronized long changesSinceSnapshot() { return sequence - snapshotSequence; }
    public synchronized void put(String key, byte[] value, boolean force) throws IOException {
        putAll(Map.of(key, value), force);
    }
    /** An empty value deletes a key. A forced batch must return before changing chunk metadata. */
    public synchronized void putAll(Map<String, byte[]> changes, boolean force) throws IOException {
        for (var entry : changes.entrySet()) {
            byte[] frame = frame(sequence + 1, entry.getKey(), entry.getValue());
            long before = channel.position();
            try { writeFully(channel, ByteBuffer.wrap(frame)); }
            catch (IOException failure) {
                try { channel.truncate(before); channel.position(before); }
                catch (IOException rollback) { failure.addSuppressed(rollback); channel.close(); }
                throw failure;
            }
            sequence++; tail.add(frame);
            if (entry.getValue().length == 0) state.remove(entry.getKey()); else state.put(entry.getKey(), entry.getValue().clone());
        }
        if (force) channel.force(false);
    }
    public synchronized CompletableFuture<Boolean> compactAsync(Executor executor) {
        if (compaction != null && !compaction.isDone()) return compaction;
        long at = sequence; Map<String, byte[]> captured = new LinkedHashMap<>(state);
        compaction = CompletableFuture.supplyAsync(() -> {
            try { compact(at, captured); return true; }
            catch (IOException failure) { throw new CompletionException(failure); }
        }, executor);
        return compaction;
    }
    private void compact(long at, Map<String, byte[]> captured) throws IOException {
        Path temporarySnapshot = snapshot.resolveSibling(snapshot.getFileName() + ".tmp");
        try (FileChannel output = FileChannel.open(temporarySnapshot, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
            try (DataOutputStream header = new DataOutputStream(headerBytes)) {
                header.writeInt(MAGIC); header.writeInt(VERSION); header.writeLong(at); header.writeInt(captured.size());
            }
            writeFully(output, ByteBuffer.wrap(headerBytes.toByteArray()));
            for (var entry : captured.entrySet()) writeFully(output, ByteBuffer.wrap(frame(at, entry.getKey(), entry.getValue())));
            output.force(true);
        }
        // An old journal is safe with the new snapshot: replay discards records through 'at'.
        atomicMove(temporarySnapshot, snapshot);
        synchronized (this) {
            List<byte[]> newer = new ArrayList<>();
            for (byte[] frame : tail) if (ByteBuffer.wrap(frame).getLong(4) > at) newer.add(frame);
            Path temporaryJournal = journal.resolveSibling(journal.getFileName() + ".tmp");
            try (FileChannel output = FileChannel.open(temporaryJournal, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                for (byte[] frame : newer) writeFully(output, ByteBuffer.wrap(frame)); output.force(true);
            }
            channel.close();
            try { atomicMove(temporaryJournal, journal); }
            finally { channel = FileChannel.open(journal, StandardOpenOption.WRITE); channel.position(channel.size()); }
            tail.clear(); tail.addAll(newer); snapshotSequence = at;
        }
    }
    private void apply(byte[] frame, boolean snapshotEntry) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(frame, 4, frame.length - 8))) {
            long at = input.readLong(); String key = input.readUTF(); int length = input.readInt();
            if (snapshotEntry && at != snapshotSequence) throw new IOException("Invalid BlueMap snapshot sequence");
            if (length < 0 || length > MAX_FRAME) throw new IOException("Invalid BlueMap record size");
            byte[] value = new byte[length]; input.readFully(value);
            if (input.read() != -1) throw new IOException("Trailing BlueMap record data");
            if (snapshotEntry || at > snapshotSequence) {
                if (!snapshotEntry && at <= sequence) throw new IOException("Unordered BlueMap journal");
                if (value.length == 0) state.remove(key); else state.put(key, value);
                sequence = Math.max(sequence, at); if (!snapshotEntry) tail.add(frame);
            }
        }
    }
    private static byte[] frame(long at, String key, byte[] value) throws IOException {
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
        try (DataOutputStream body = new DataOutputStream(bodyBytes)) { body.writeLong(at); body.writeUTF(key); body.writeInt(value.length); body.write(value); }
        byte[] body = bodyBytes.toByteArray(); if (body.length > MAX_FRAME) throw new IOException("BlueMap record too large");
        CRC32 checksum = new CRC32(); checksum.update(body);
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(framed)) { output.writeInt(body.length); output.write(body); output.writeInt((int) checksum.getValue()); }
        return framed.toByteArray();
    }
    private static byte[] readFrame(DataInput input) throws IOException {
        int length = input.readInt(); if (length < 14 || length > MAX_FRAME) throw new IOException("Invalid BlueMap frame length");
        byte[] body = new byte[length]; input.readFully(body); int expected = input.readInt();
        CRC32 checksum = new CRC32(); checksum.update(body);
        if ((int) checksum.getValue() != expected) throw new IOException("BlueMap journal checksum mismatch");
        ByteBuffer frame = ByteBuffer.allocate(length + 8); frame.putInt(length).put(body).putInt(expected); return frame.array();
    }
    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException { while (buffer.hasRemaining()) channel.write(buffer); }
    private static void atomicMove(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }
    @Override public void close() throws IOException {
        CompletableFuture<Boolean> pending;
        synchronized (this) { pending = compaction; }
        if (pending != null) try { pending.join(); } catch (CompletionException ignored) { /* Retained journal permits a fresh snapshot. */ }
        synchronized (this) { compact(sequence, new LinkedHashMap<>(state)); channel.force(true); channel.close(); }
    }
}
