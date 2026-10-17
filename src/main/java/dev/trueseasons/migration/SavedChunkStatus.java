package dev.trueseasons.migration;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

/** Read-only generation status lookup. Runs on the index worker, never the server thread. */
final class SavedChunkStatus {
    private static final int LIMIT=16*1024*1024;
    static boolean full(Path directory, ChunkPos pos) throws IOException {
        return read(directory,pos).full();
    }
    static SavedChunkState read(Path directory, ChunkPos pos) throws IOException {
        Path region=directory.resolve("r."+Math.floorDiv(pos.x(),32)+"."+Math.floorDiv(pos.z(),32)+".mca");
        try(RandomAccessFile file=new RandomAccessFile(region.toFile(),"r")) {
            long slot=(Math.floorMod(pos.x(),32)+Math.floorMod(pos.z(),32)*32)*4L;
            byte[] scratch=new byte[5];file.seek(slot);file.readFully(scratch,0,4);
            return read(file,directory,pos,integer(scratch),scratch);
        }
    }
    /** One read-only region handle and scratch buffer can serve every occupied slot. */
    static SavedChunkState read(RandomAccessFile file,Path directory,ChunkPos pos,int location,byte[] scratch)throws IOException{
        int offset=location>>>8,sectors=location&255;
        if(offset<2||sectors==0)throw new IOException("Chunk disappeared during index scan");
        file.seek(offset*4096L);file.readFully(scratch,0,5);
        int size=integer(scratch),format=scratch[4]&255;
        if(size<1)throw new IOException("Invalid chunk length");
        byte[] bytes;
        if((format&128)!=0){
            Path external=directory.resolve("c."+pos.x()+"."+pos.z()+".mcc");
            if(Files.size(external)>LIMIT)throw new IOException("External chunk exceeds status read limit");
            try(InputStream input=Files.newInputStream(external)){
                bytes=input.readNBytes(LIMIT+1);
                if(bytes.length>LIMIT)throw new IOException("External chunk exceeds status read limit");
            }
        }else{
            if(size>LIMIT||size>sectors*4096-4)throw new IOException("Invalid chunk length");
            bytes=new byte[size-1];file.readFully(bytes);
        }
        Parsed parsed=new Parsed();
        try(InputStream stream=decode(new ByteArrayInputStream(bytes),format&127);
            DataInputStream input=new DataInputStream(new BufferedInputStream(new Bounded(stream),256))){
            if(input.readUnsignedByte()!=10)throw new IOException("Chunk NBT root is not a compound");
            input.readUTF();compound(input,"",0,parsed);
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
    private static void compound(DataInputStream input,String path,int depth,Parsed parsed) throws IOException {
        if(depth>64) throw new IOException("Chunk NBT nesting exceeds limit");
        for(int type;(type=input.readUnsignedByte())!=0;) {
            String name=input.readUTF();
            if(name.equals("Status")&&(path.isEmpty()||path.equals("Level"))) {
                if(type!=8) throw new IOException("Unexpected generation Status type");
                parsed.status=input.readUTF();
            } else if(type==10&&path.isEmpty()&&name.equals("Level")) compound(input,"Level",depth+1,parsed);
            else if(type==10&&(path.isEmpty()||path.equals("Level"))&&name.equals("ChunkBukkitValues")) pdc(input,depth+1,parsed);
            else skip(input,type,depth+1);
        }
    }
    private static void pdc(DataInputStream input,int depth,Parsed parsed) throws IOException {
        for(int type;(type=input.readUnsignedByte())!=0;) {
            String name=input.readUTF();
            switch(name) {
                case "trueseasons:season_version" -> { parsed.seasonMetadata=true; if(type==8)parsed.version=input.readUTF(); else skip(input,type,depth+1); }
                case "trueseasons:winter_pending" -> { parsed.seasonMetadata=true; parsed.pending=true; if(type==8)parsed.pendingVersion=input.readUTF(); else skip(input,type,depth+1); }
                case "trueseasons:winter_cursor" -> { parsed.seasonMetadata=true; parsed.pending=true; if(type==3)parsed.columns=input.readInt(); else skip(input,type,depth+1); }
                case "trueseasons:snow_v1", "trueseasons:ground_cover_v1", "trueseasons:winter_local", "trueseasons:melt_v1" -> { parsed.seasonMetadata=true; skip(input,type,depth+1); }
                case "trueseasons:bluemap_revision" -> { parsed.mapMarker=true; if(type==8)parsed.mapRevision=input.readUTF(); else skip(input,type,depth+1); }
                default -> skip(input,type,depth+1);
            }
        }
    }
    private static int length(DataInputStream input) throws IOException {
        int size=input.readInt(); if(size<0||size>LIMIT) throw new IOException("Invalid NBT length"); return size;
    }
    private static void skip(DataInputStream input,int type,int depth) throws IOException {
        if(depth>64) throw new IOException("Chunk NBT nesting exceeds limit");
        switch(type) {
            case 1 -> input.readByte(); case 2 -> input.readShort(); case 3 -> input.readInt(); case 4 -> input.readLong();
            case 5 -> input.readFloat(); case 6 -> input.readDouble(); case 8 -> input.readUTF();
            case 7 -> input.skipNBytes(length(input)); case 11 -> input.skipNBytes(length(input)*4L); case 12 -> input.skipNBytes(length(input)*8L);
            case 9 -> { int child=input.readUnsignedByte(),count=length(input); if(child==0&&count!=0)throw new IOException("Invalid NBT list"); for(int n=0;n<count;n++)skip(input,child,depth+1); }
            case 10 -> { for(int child;(child=input.readUnsignedByte())!=0;) { input.readUTF(); skip(input,child,depth+1); } }
            default -> throw new IOException("Unknown NBT type "+type);
        }
    }
    private static final class Bounded extends FilterInputStream {
        private int remaining=LIMIT;
        Bounded(InputStream stream) { super(stream); }
        public int read() throws IOException { if(remaining--<=0)throw new IOException("Chunk status exceeds read limit"); return super.read(); }
        public int read(byte[] bytes,int offset,int length) throws IOException {
            if(remaining<=0)throw new IOException("Chunk status exceeds read limit");
            int read=super.read(bytes,offset,Math.min(length,remaining)); if(read>0)remaining-=read; return read;
        }
        public long skip(long length) throws IOException { long skipped=super.skip(Math.min(length,remaining)); remaining-=(int)skipped; return skipped; }
    }
}
