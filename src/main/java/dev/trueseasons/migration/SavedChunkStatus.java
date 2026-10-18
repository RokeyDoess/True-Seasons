package dev.trueseasons.migration;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

/** Read-only generation status lookup. Runs on the index worker, never the server thread. */
final class SavedChunkStatus {
    private static final int LIMIT=16*1024*1024;
    /** Owned by one region reader; encoded bytes must never share the inflater's scratch storage. */
    static final class ReadBuffers {
        final byte[] scratch=new byte[8192];
        private byte[] encoded=new byte[8192];
        private byte[] encoded(int size){
            if(encoded.length<size)encoded=new byte[Math.min(LIMIT,Math.max(size,encoded.length*2))];
            return encoded;
        }
    }
    static boolean full(Path directory, ChunkPos pos) throws IOException {
        return read(directory,pos).full();
    }
    static SavedChunkState read(Path directory, ChunkPos pos) throws IOException {
        Path region=directory.resolve("r."+Math.floorDiv(pos.x(),32)+"."+Math.floorDiv(pos.z(),32)+".mca");
        try(RandomAccessFile file=new RandomAccessFile(region.toFile(),"r")) {
            long slot=(Math.floorMod(pos.x(),32)+Math.floorMod(pos.z(),32)*32)*4L;
            byte[] scratch=new byte[8192];file.seek(slot);file.readFully(scratch,0,4);
            return read(file,directory,pos,integer(scratch),scratch);
        }
    }
    /** One read-only region handle and scratch buffer can serve every occupied slot. */
    static SavedChunkState read(RandomAccessFile file,Path directory,ChunkPos pos,int location,byte[] scratch)throws IOException{
        return read(file,directory,pos,location,scratch,null);
    }
    static SavedChunkState read(RandomAccessFile file,Path directory,ChunkPos pos,int location,ReadBuffers buffers)throws IOException{
        return read(file,directory,pos,location,buffers.scratch,buffers);
    }
    private static SavedChunkState read(RandomAccessFile file,Path directory,ChunkPos pos,int location,byte[] scratch,ReadBuffers buffers)throws IOException{
        int offset=location>>>8,sectors=location&255;
        if(offset<2||sectors==0)throw new IOException("Chunk disappeared during index scan");
        file.seek(offset*4096L);file.readFully(scratch,0,5);
        int size=integer(scratch),format=scratch[4]&255;
        if(size<1)throw new IOException("Invalid chunk length");
        byte[] bytes;int length;
        if((format&128)!=0){
            Path external=directory.resolve("c."+pos.x()+"."+pos.z()+".mcc");
            if(Files.size(external)>LIMIT)throw new IOException("External chunk exceeds status read limit");
            try(InputStream input=Files.newInputStream(external)){
                bytes=input.readNBytes(LIMIT+1);
                if(bytes.length>LIMIT)throw new IOException("External chunk exceeds status read limit");
                length=bytes.length;
            }
        }else{
            if(size>LIMIT||size>sectors*4096-4)throw new IOException("Invalid chunk length");
            length=size-1;bytes=buffers==null?new byte[length]:buffers.encoded(length);file.readFully(bytes,0,length);
        }
        Parsed parsed=new Parsed();
        try(InputStream stream=decode(new ByteArrayInputStream(bytes,0,length),format&127);
            DataInputStream input=new DataInputStream(new BufferedInputStream(new Bounded(stream,scratch),256))){
            if(input.readUnsignedByte()!=10)throw new IOException("Chunk NBT root is not a compound");
            input.readUTF();compound(input,"",0,parsed,scratch);
        }
        long slot=(Math.floorMod(pos.x(),32)+Math.floorMod(pos.z(),32)*32)*4L;
        file.seek(slot);file.readFully(scratch,0,4);
        if(integer(scratch)!=location)throw new IOException("Chunk moved during status read");
        if(parsed.status==null)throw new IOException("Missing chunk generation Status");
        return new SavedChunkState(parsed.status.equals("full")||parsed.status.equals("minecraft:full"),
            parsed.version,parsed.pendingVersion,parsed.pending,parsed.columns,parsed.seasonMetadata,parsed.mapMarker,parsed.mapRevision);
    }
    private static int integer(byte[] bytes){return (bytes[0]&255)<<24|(bytes[1]&255)<<16|(bytes[2]&255)<<8|bytes[3]&255;}
    private static InputStream decode(InputStream input,int format) throws IOException {
        return switch(format) {
            case 1 -> new GZIPInputStream(input);
            case 2 -> new InflaterInputStream(input);
            case 3 -> input;
            case 4 -> {
                try { yield (InputStream)Class.forName("net.jpountz.lz4.LZ4BlockInputStream").getConstructor(InputStream.class).newInstance(input); }
                catch(ReflectiveOperationException failure) { throw new IOException("LZ4 status decoder unavailable",failure); }
            }
            default -> throw new IOException("Unknown region compression "+format);
        };
    }
    private static final class Parsed {
        String status,version,pendingVersion,mapRevision;
        boolean pending,seasonMetadata,mapMarker;
        int columns;
    }
    private static void compound(DataInputStream input,String path,int depth,Parsed parsed,byte[] scratch) throws IOException {
        if(depth>64) throw new IOException("Chunk NBT nesting exceeds limit");
        for(int type;(type=input.readUnsignedByte())!=0;) {
            String name=input.readUTF();
            if(name.equals("Status")&&(path.isEmpty()||path.equals("Level"))) {
                if(type!=8) throw new IOException("Unexpected generation Status type");
                parsed.status=input.readUTF();
            } else if(type==10&&path.isEmpty()&&name.equals("Level")) compound(input,"Level",depth+1,parsed,scratch);
            else if((path.isEmpty()||path.equals("Level"))&&name.equals("ChunkBukkitValues")) {
                if(type!=10)throw new IOException("Unexpected saved ChunkBukkitValues type");
                pdc(input,depth+1,parsed,scratch);
            }
            else skip(input,type,depth+1,scratch);
        }
    }
    private static void pdc(DataInputStream input,int depth,Parsed parsed,byte[] scratch) throws IOException {
        for(int type;(type=input.readUnsignedByte())!=0;) {
            String name=input.readUTF();
            switch(name) {
                case "trueseasons:season_version" -> { parsed.seasonMetadata=true; if(type==8)parsed.version=input.readUTF(); else skip(input,type,depth+1,scratch); }
                case "trueseasons:winter_pending" -> { parsed.seasonMetadata=true; parsed.pending=true; if(type==8)parsed.pendingVersion=input.readUTF(); else skip(input,type,depth+1,scratch); }
                case "trueseasons:winter_cursor" -> { parsed.seasonMetadata=true; parsed.pending=true; if(type==3)parsed.columns=input.readInt(); else skip(input,type,depth+1,scratch); }
                case "trueseasons:snow_v1", "trueseasons:ground_cover_v1", "trueseasons:winter_local", "trueseasons:winter_order", "trueseasons:melt_v1" -> { parsed.seasonMetadata=true; skip(input,type,depth+1,scratch); }
                case "trueseasons:bluemap_revision" -> { parsed.mapMarker=true; if(type==8)parsed.mapRevision=input.readUTF(); else skip(input,type,depth+1,scratch); }
                default -> skip(input,type,depth+1,scratch);
            }
        }
    }
    private static int length(DataInputStream input) throws IOException {
        int size=input.readInt(); if(size<0||size>LIMIT) throw new IOException("Invalid NBT length"); return size;
    }
    /** Validate ignored modified UTF without allocating a String for every palette entry/key. */
    private static void skipUtf(DataInputStream input,byte[] scratch)throws IOException{
        int length=input.readUnsignedShort();byte[] bytes=length<=scratch.length?scratch:new byte[length];
        input.readFully(bytes,0,length);
        for(int at=0;at<length;){
            int value=bytes[at]&255;
            if(value<128){at++;continue;}
            if((value>>4)==12||(value>>4)==13){
                if(at+1>=length||(bytes[at+1]&192)!=128)throw new UTFDataFormatException("Malformed ignored NBT string");at+=2;
            }else if((value>>4)==14){
                if(at+2>=length||(bytes[at+1]&192)!=128||(bytes[at+2]&192)!=128)throw new UTFDataFormatException("Malformed ignored NBT string");at+=3;
            }else throw new UTFDataFormatException("Malformed ignored NBT string");
        }
    }
    private static void skip(DataInputStream input,int type,int depth,byte[] scratch) throws IOException {
        if(depth>64) throw new IOException("Chunk NBT nesting exceeds limit");
        switch(type) {
            case 1 -> input.skipNBytes(1); case 2 -> input.skipNBytes(2); case 3,5 -> input.skipNBytes(4); case 4,6 -> input.skipNBytes(8);
            case 8 -> skipUtf(input,scratch);
            case 7 -> input.skipNBytes(length(input)); case 11 -> input.skipNBytes(length(input)*4L); case 12 -> input.skipNBytes(length(input)*8L);
            case 9 -> {
                int child=input.readUnsignedByte(),count=length(input);if(child==0&&count!=0)throw new IOException("Invalid NBT list");
                int width=switch(child){case 1->1;case 2->2;case 3,5->4;case 4,6->8;default->0;};
                if(width>0){if(depth+1>64&&count>0)throw new IOException("Chunk NBT nesting exceeds limit");input.skipNBytes(count*(long)width);}
                else for(int n=0;n<count;n++)skip(input,child,depth+1,scratch);
            }
            case 10 -> { for(int child;(child=input.readUnsignedByte())!=0;) { skipUtf(input,scratch); skip(input,child,depth+1,scratch); } }
            default -> throw new IOException("Unknown NBT type "+type);
        }
    }
    private static final class Bounded extends FilterInputStream {
        private int remaining=LIMIT;
        private final byte[] scratch;
        Bounded(InputStream stream,byte[] scratch) { super(stream);this.scratch=scratch; }
        public int read() throws IOException { if(remaining--<=0)throw new IOException("Chunk status exceeds read limit"); return super.read(); }
        public int read(byte[] bytes,int offset,int length) throws IOException {
            if(remaining<=0)throw new IOException("Chunk status exceeds read limit");
            int read=super.read(bytes,offset,Math.min(length,remaining)); if(read>0)remaining-=read; return read;
        }
        public long skip(long length) throws IOException {
            long target=Math.min(Math.max(0,length),remaining),skipped=0;
            while(skipped<target){int read=in.read(scratch,0,(int)Math.min(scratch.length,target-skipped));if(read<0)break;if(read==0)break;skipped+=read;remaining-=read;}
            return skipped;
        }
    }
}
