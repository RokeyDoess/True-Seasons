package dev.trueseasons.snow;

import java.io.*;
import java.util.*;
import java.nio.ByteBuffer;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/** Sparse, bounded, versioned exact plant snapshots. No terrain/global state is retained. */
public final class GroundCoverColumns {
    public record Plant(int y, String lower, String upper, String support) {
        public Plant {
            if (y < Short.MIN_VALUE || y >= Short.MAX_VALUE) throw new IllegalArgumentException("Invalid plant height");
            validate(lower); if (upper != null) validate(upper); validate(support);
        }
        private static void validate(String data) {
            if (data == null || data.isEmpty() || data.length() > 512 || !data.startsWith("minecraft:"))
                throw new IllegalArgumentException("Invalid plant block data");
        }
    }
    private Plant[] plants;
    private int size;
    private Map<Integer,Plant> view;
    public void put(int column, Plant plant) {
        if (column < 0 || column >= 256) throw new IllegalArgumentException("Invalid plant column");
        Objects.requireNonNull(plant);if(plants==null)plants=new Plant[256];
        if(plants[column]==null)size++;plants[column]=plant;
    }
    public Plant get(int column) { return plants!=null&&column>=0&&column<256?plants[column]:null; }
    public void remove(int column) { if(get(column)!=null){plants[column]=null;size--;} }
    public boolean isEmpty() { return size==0; }
    int size(){return size;}
    int nextColumn(int from){if(from<0)throw new IllegalArgumentException("Negative column");if(plants!=null)for(int column=from;column<256;column++)if(plants[column]!=null)return column;return 256;}
    public Map<Integer, Plant> entries() {
        if(view==null)view=Collections.unmodifiableMap(new AbstractMap<>(){
            @Override public int size(){return size;}
            @Override public Plant get(Object key){return key instanceof Integer column?GroundCoverColumns.this.get(column):null;}
            @Override public Set<Entry<Integer,Plant>> entrySet(){return new AbstractSet<>(){
                @Override public int size(){return size;}
                @Override public Iterator<Entry<Integer,Plant>> iterator(){return new Iterator<>(){
                    int next=nextColumn(0);
                    public boolean hasNext(){return next<256;}
                    public Entry<Integer,Plant> next(){if(!hasNext())throw new NoSuchElementException();int column=next;next=nextColumn(column+1);return new SimpleImmutableEntry<>(column,plants[column]);}
                };}
            };}
        });
        return view;
    }
    public byte[] encode() {
        Map<String,byte[]> strings=new HashMap<>();int length=3;
        for(int column=nextColumn(0);column<256;column=nextColumn(column+1)){
            Plant plant=plants[column];length+=4+utf(strings,plant.lower()).length+utf(strings,plant.support()).length;
            if(plant.upper()!=null)length+=utf(strings,plant.upper()).length;
        }
        byte[] bytes=new byte[length];ByteBuffer out=ByteBuffer.wrap(bytes);out.put((byte)1).putShort((short)size);
        for(int column=nextColumn(0);column<256;column=nextColumn(column+1)){
            Plant plant=plants[column];out.put((byte)column).putShort((short)plant.y()).put(strings.get(plant.lower())).put((byte)(plant.upper()==null?0:1));
            if(plant.upper()!=null)out.put(strings.get(plant.upper()));out.put(strings.get(plant.support()));
        }
        return bytes;
    }
    private static final Map<String,byte[]> ENCODED=new LinkedHashMap<>(64,.75f,true);
    private static int encodedWeight;
    private static byte[] utf(Map<String,byte[]> strings,String value){
        byte[] encoded = strings.get(value);
        if (encoded == null) {
            synchronized(ENCODED){encoded=ENCODED.get(value);}
            if(encoded==null){
                try{var bytes=new ByteArrayOutputStream();new DataOutputStream(bytes).writeUTF(value);encoded=bytes.toByteArray();}
                catch(IOException impossible){throw new UncheckedIOException(impossible);}
                synchronized(ENCODED){
                    byte[] existing=ENCODED.get(value);
                    if(existing!=null)encoded=existing;
                    else{
                        ENCODED.put(value,encoded);encodedWeight+=encoded.length+value.length()*2+64;
                        while(ENCODED.size()>512||encodedWeight>524288){var it=ENCODED.entrySet().iterator();var row=it.next();encodedWeight-=row.getValue().length+row.getKey().length()*2+64;it.remove();}
                    }
                }
            }
            strings.put(value,encoded);
        }
        return encoded;
    }
    public static GroundCoverColumns decode(byte[] bytes) throws IOException {
        var result = new GroundCoverColumns(); if (bytes == null) return result;
        if (bytes.length > 524288) throw new IOException("Oversized plant metadata");
        var source = new SnapshotInput(bytes); var in = new DataInputStream(source);
        if (in.readUnsignedByte() != 1) throw new IOException("Unknown plant metadata schema");
        int count = in.readUnsignedShort(); if (count > 256) throw new IOException("Too many plant snapshots");
        try {
            for (int i = 0; i < count; i++) {
                int column = in.readUnsignedByte(); int y = in.readShort(); String lower = source.utf(in);
                String upper = in.readBoolean() ? source.utf(in) : null; String support = source.utf(in);
                if (result.get(column) != null) throw new IOException("Duplicate plant column");
                result.put(column, new Plant(y, lower, upper, support));
            }
        } catch (IllegalArgumentException ex) { throw new IOException("Invalid plant snapshot", ex); }
        if (in.available() != 0) throw new IOException("Trailing plant metadata");
        return result;
    }
    /** Decoder-local source; DataInputStream retains its standard modified-UTF decoding and buffers. */
    private static final class SnapshotInput extends InputStream {
        private final byte[] bytes;
        private final Int2ObjectOpenHashMap<Utf> strings = new Int2ObjectOpenHashMap<>(8);
        private int offset;
        private record Utf(int start, int end, String value, Utf next) {}
        SnapshotInput(byte[] bytes) { this.bytes = bytes; }
        /** Reuse only byte-identical strings already validated by DataInputStream. */
        String utf(DataInputStream input) throws IOException {
            if (bytes.length - offset < 2) throw new EOFException();
            int end = offset + 2 + ((bytes[offset] & 255) << 8 | bytes[offset + 1] & 255);
            if (end > bytes.length) throw new EOFException();
            int hash = 1;
            for (int at = offset; at < end; at++) hash = 31 * hash + bytes[at];
            Utf first = strings.get(hash);
            for (Utf row = first; row != null; row = row.next)
                if (Arrays.equals(bytes, offset, end, bytes, row.start, row.end)) {
                    offset = end; return row.value;
                }
            int start = offset;
            String value = input.readUTF();
            strings.put(hash, new Utf(start, end, value, first));
            return value;
        }
        @Override public int read() { return offset < bytes.length ? bytes[offset++] & 255 : -1; }
        @Override public int read(byte[] target, int start, int length) {
            Objects.checkFromIndexSize(start, length, target.length);
            if (length == 0) return 0;
            if (offset == bytes.length) return -1;
            int count = Math.min(length, bytes.length - offset);
            System.arraycopy(bytes, offset, target, start, count); offset += count;
            return count;
        }
        @Override public long skip(long length) {
            int count = (int) Math.min(Math.max(0, length), bytes.length - offset);
            offset += count; return count;
        }
        @Override public int available() { return bytes.length - offset; }
    }
}
