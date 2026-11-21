package dev.trueseasons.integration;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;

/** Constant-size ordered changes, with atomic snapshots built away from the server thread. */
public final class DurableRenderJournal implements AutoCloseable {
    private static final int MAGIC = 0x5453424d, VERSION = 1, MAX_FRAME = 1_048_576;
    private final Path snapshot, journal;
    /** Frozen generations let workers prepare snapshots without a full map copy on the caller. */
    private static final class State {
        private final Map<String,byte[]> changes;
        private final boolean direct;
        private State previous;
        private Map<String,byte[]> base;
        State(){this(0);}
        State(int expected){direct=true;base=Map.of();changes=expected==0?new LinkedHashMap<>():LinkedHashMap.newLinkedHashMap(expected);}
        State(State previous){direct=false;this.previous=previous;changes=new LinkedHashMap<>();}
        void put(String key,byte[] value){
            if(direct&&value.length==0)changes.remove(key);else changes.put(key,value);
        }
        boolean contains(String key){
            byte[] value=changes.get(key);if(value!=null)return value.length!=0;
            return previous==null?base.containsKey(key):previous.contains(key);
        }
        Map<String,byte[]> materialize(){
            if(direct)return changes;
            var resolved=new LinkedHashMap<>(previous==null?base:previous.materialize());
            changes.forEach((key,value)->{if(value.length==0)resolved.remove(key);else resolved.put(key,value);});
            return resolved;
        }
        void rebase(Map<String,byte[]> resolved){base=resolved;previous=null;}
    }
    private State state=new State();
    private final List<byte[]> tail = new ArrayList<>();
    private FileChannel channel;
    private long sequence, snapshotSequence;
    private CompletableFuture<Boolean> compaction;
    private final FrameWriter appends=new FrameWriter();
    public DurableRenderJournal(Path directory) throws IOException {
        Files.createDirectories(directory);
        snapshot = directory.resolve("bluemap.snapshot"); journal = directory.resolve("bluemap.journal");
        long validBytes = 0;
        try(ReplayInput replay=new ReplayInput()){
        if (Files.exists(snapshot)) {
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(snapshot), 65_536))) {
                if (input.readInt() != MAGIC || input.readInt() != VERSION) throw new IOException("Unknown BlueMap snapshot format");
                sequence = snapshotSequence = input.readLong(); int count = input.readInt();
                if (count < 0 || count > 10_000_000) throw new IOException("Invalid BlueMap snapshot size");
                // Limit hints from untrusted headers by both file size and a small fixed ceiling.
                if(count>1024){
                    int expected=(int)Math.min(count,Math.min(262_144L,Math.max(0,(Files.size(snapshot)-20)/22)));
                    state=new State(expected);
                }
                for (int n = 0; n < count; n++) apply(readFrame(input,replay.checksum), true,replay);
                if (input.read() != -1) throw new IOException("Trailing BlueMap snapshot data");
            }
        }
        if (Files.exists(journal)) {
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(journal), 65_536))) {
                while (true) {
                    byte[] frame;
                    try { frame = readFrame(input,replay.checksum); }
                    catch (EOFException tornWrite) { break; }
                    // Count validated frames, not the buffered stream's read-ahead position.
                    apply(frame, false,replay); validBytes += frame.length;
                }
            }
        }
        }
        channel = FileChannel.open(journal, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            channel.truncate(validBytes); channel.position(validBytes);
            // After a process-only crash, readable tail records may still be in the OS cache.
            // Stabilize records newer than the forced snapshot before trusting recovered intent.
            if (sequence > snapshotSequence) channel.force(false);
        } catch (IOException failure) {
            try { channel.close(); } catch (IOException closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }
    public synchronized boolean hasSnapshot() { return Files.exists(snapshot); }
    public synchronized Map<String, byte[]> state() {
        Map<String, byte[]> copy = new LinkedHashMap<>(); state.materialize().forEach((key, value) -> copy.put(key, value.clone())); return copy;
    }
    synchronized boolean contains(String key){return state.contains(key);}
    @FunctionalInterface interface RecordReader {void read(String key,DataInputStream input)throws IOException;}
    private static final class RecordCursor extends ByteArrayInputStream{
        private static final byte[] EMPTY=new byte[0];
        RecordCursor(){super(EMPTY);}
        void select(byte[] record){select(record,0,record.length);}
        void select(byte[] record,int from,int length){Objects.checkFromIndexSize(from,length,record.length);buf=record;pos=mark=from;count=from+length;}
        void clear(){select(EMPTY);}
    }
    private static final class ReplayInput extends DataInputStream{
        private final RecordCursor cursor;
        final CRC32 checksum=new CRC32();
        ReplayInput(){this(new RecordCursor());}
        private ReplayInput(RecordCursor cursor){super(cursor);this.cursor=cursor;}
        void select(byte[] frame){cursor.select(frame,4,frame.length-8);}
        @Override public void close()throws IOException{try{super.close();}finally{cursor.clear();}}
    }
    /** The read-only cursor is valid during its callback and cannot read past that record. */
    synchronized void readRecords(RecordReader reader)throws IOException{
        RecordCursor cursor=new RecordCursor();
        try(var input=new DataInputStream(cursor)){
            for(var row:state.materialize().entrySet()){cursor.select(row.getValue());reader.read(row.getKey(),input);}
        }finally{cursor.clear();}
    }
    public synchronized long changesSinceSnapshot() { return sequence - snapshotSequence; }
    public synchronized void put(String key, byte[] value, boolean force) throws IOException {
        putAll(Map.of(key, value), force);
    }
    /** An empty value deletes a key. A forced batch must return before changing chunk metadata. */
    public synchronized void putAll(Map<String, byte[]> changes, boolean force) throws IOException {
        for (var entry : changes.entrySet()) {
            byte[] frame = appends.frame(sequence + 1, entry.getKey(), entry.getValue());
            long before = channel.position();
            try { writeFully(channel, ByteBuffer.wrap(frame)); }
            catch (IOException failure) {
                try { channel.truncate(before); channel.position(before); }
                catch (IOException rollback) { failure.addSuppressed(rollback); channel.close(); }
                throw failure;
            }
            sequence++; tail.add(frame);
            state.put(entry.getKey(),entry.getValue().clone());
        }
        if (force) channel.force(false);
    }
    public synchronized CompletableFuture<Boolean> compactAsync(Executor executor) {
        if (compaction != null && !compaction.isDone()) return compaction;
        if(sequence==snapshotSequence&&hasSnapshot())try{
            if(channel.position()==0)return CompletableFuture.completedFuture(false);
        }catch(IOException failure){return CompletableFuture.failedFuture(failure);}
        long at=sequence;State captured=state,replacement=new State(captured);state=replacement;
        try{
            compaction = CompletableFuture.supplyAsync(() -> {
                Map<String,byte[]> resolved=captured.materialize();
                // Publish only the frozen base; concurrent post-capture changes retain precedence.
                synchronized(this){replacement.rebase(resolved);}
                try { compact(at, resolved); return true; }
                catch (IOException failure) { throw new CompletionException(failure); }
            }, executor);
        }catch(RuntimeException|Error failure){state=captured;throw failure;}
        return compaction;
    }
    private void compact(long at, Map<String, byte[]> captured) throws IOException {
        Path temporarySnapshot = snapshot.resolveSibling(snapshot.getFileName() + ".tmp");
        try (FileChannel output = FileChannel.open(temporarySnapshot, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                OutputStream buffered=new BufferedOutputStream(Channels.newOutputStream(output),65_536)) {
            ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
            try (DataOutputStream header = new DataOutputStream(headerBytes)) {
                header.writeInt(MAGIC); header.writeInt(VERSION); header.writeLong(at); header.writeInt(captured.size());
            }
            buffered.write(headerBytes.toByteArray());
            FrameWriter writer=new FrameWriter();
            for (var entry : captured.entrySet()) buffered.write(writer.frame(at, entry.getKey(), entry.getValue()));
            buffered.flush();
            output.force(true);
        }
        // An old journal is safe with the new snapshot: replay discards records through 'at'.
        atomicMove(temporarySnapshot, snapshot);
        synchronized (this) {
            List<byte[]> newer = new ArrayList<>();
            for (byte[] frame : tail) if (ByteBuffer.wrap(frame).getLong(4) > at) newer.add(frame);
            Path temporaryJournal = journal.resolveSibling(journal.getFileName() + ".tmp");
            try (FileChannel output = FileChannel.open(temporaryJournal, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                    OutputStream buffered=new BufferedOutputStream(Channels.newOutputStream(output),65_536)) {
                for (byte[] frame : newer) buffered.write(frame);buffered.flush();output.force(true);
            }
            channel.close();
            try { atomicMove(temporaryJournal, journal); }
            finally { channel = FileChannel.open(journal, StandardOpenOption.WRITE); channel.position(channel.size()); }
            tail.clear(); tail.addAll(newer); snapshotSequence = at;
        }
    }
    private void apply(byte[] frame,boolean snapshotEntry,ReplayInput input)throws IOException{
            input.select(frame);
            long at = input.readLong(); String key = input.readUTF(); int length = input.readInt();
            if (snapshotEntry && at != snapshotSequence) throw new IOException("Invalid BlueMap snapshot sequence");
            if (length < 0 || length > MAX_FRAME) throw new IOException("Invalid BlueMap record size");
            byte[] value = new byte[length]; input.readFully(value);
            if (input.read() != -1) throw new IOException("Trailing BlueMap record data");
            if (snapshotEntry || at > snapshotSequence) {
                if (!snapshotEntry && at <= sequence) throw new IOException("Unordered BlueMap journal");
                state.put(key,value);
                sequence = Math.max(sequence, at); if (!snapshotEntry) tail.add(frame);
            }
    }
    private static final class FrameOutput extends OutputStream{
        private static final byte[] EMPTY=new byte[0];
        private byte[] bytes=EMPTY;
        private int position;
        void select(byte[] frame){bytes=frame;position=4;}
        void clear(){bytes=EMPTY;position=0;}
        @Override public void write(int value){Objects.checkIndex(position,bytes.length-4);bytes[position++]=(byte)value;}
        @Override public void write(byte[] source,int offset,int length){
            Objects.checkFromIndexSize(offset,length,source.length);Objects.checkFromIndexSize(position,length,bytes.length-4);
            System.arraycopy(source,offset,bytes,position,length);position+=length;
        }
    }
    /** Each writer has one owner; snapshot writers never share append scratch buffers. */
    private static final class FrameWriter{
        private final FrameOutput output=new FrameOutput();
        private final DataOutputStream data=new DataOutputStream(output);
        private final CRC32 checksum=new CRC32();
        byte[] frame(long at,String key,byte[] value)throws IOException{
            int utfLength=0;
            for(int n=0;n<key.length();n++){
                char c=key.charAt(n);utfLength+=c>=1&&c<=127?1:c<=2047?2:3;
                if(utfLength>65_535)throw new UTFDataFormatException("BlueMap journal key exceeds the modified UTF limit");
            }
            long length=14L+utfLength+value.length;if(length>MAX_FRAME)throw new IOException("BlueMap record too large");
            byte[] frame=new byte[(int)length+8];putInt(frame,0,(int)length);output.select(frame);
            try{
                data.writeLong(at);data.writeUTF(key);data.writeInt(value.length);data.write(value);
                if(output.position!=frame.length-4)throw new IOException("Incomplete BlueMap frame encoding");
                checksum.reset();checksum.update(frame,4,(int)length);putInt(frame,frame.length-4,(int)checksum.getValue());return frame;
            }finally{output.clear();}
        }
    }
    private static byte[] readFrame(DataInput input,CRC32 checksum) throws IOException {
        int length = input.readInt(); if (length < 14 || length > MAX_FRAME) throw new IOException("Invalid BlueMap frame length");
        byte[] frame=new byte[length+8];putInt(frame,0,length);input.readFully(frame,4,length);int expected=input.readInt();
        checksum.reset();checksum.update(frame,4,length);
        if ((int) checksum.getValue() != expected) throw new IOException("BlueMap journal checksum mismatch");
        putInt(frame,length+4,expected);return frame;
    }
    private static void putInt(byte[] bytes,int offset,int value){
        bytes[offset]=(byte)(value>>>24);bytes[offset+1]=(byte)(value>>>16);bytes[offset+2]=(byte)(value>>>8);bytes[offset+3]=(byte)value;
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
        synchronized (this) { compact(sequence, state.materialize()); channel.force(true); channel.close(); }
    }
}
