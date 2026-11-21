package dev.trueseasons.migration;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Read-only saved terrain discovery and checkpoints; never loads or generates chunks. */
public final class GeneratedChunkIndex {
    public record Snapshot(List<ChunkPos> generated,List<ChunkPos> unfinished,Map<ChunkPos,String> unreadable,Map<ChunkPos,SavedChunkState> states) {}
    private static final int MAX_CACHED_REGIONS=8192,MAX_HEADER_BYTES=8*1024*1024;
    /** Exact headers, stored sparsely only when that uses fewer bytes. */
    static final class RegionHeader {
        private final byte[] dense;
        private final int[] sparse;
        RegionHeader(byte[] bytes){
            int count=0;for(int slot=0;slot<1024;slot++)if(integer(bytes,slot*4)!=0||integer(bytes,4096+slot*4)!=0)count++;
            if(count*12>=bytes.length){dense=bytes;sparse=null;return;}
            dense=null;sparse=new int[count*3];int at=0;
            for(int slot=0;slot<1024;slot++){
                int location=integer(bytes,slot*4),timestamp=integer(bytes,4096+slot*4);
                if(location!=0||timestamp!=0){sparse[at++]=slot;sparse[at++]=location;sparse[at++]=timestamp;}
            }
        }
        int bytes(){return dense==null?sparse.length*4:dense.length;}
        boolean matches(byte[] bytes){
            if(dense!=null)return Arrays.equals(dense,bytes);
            int at=0;
            for(int slot=0;slot<1024;slot++){
                int location=integer(bytes,slot*4),timestamp=integer(bytes,4096+slot*4);
                if(location==0&&timestamp==0)continue;
                if(at==sparse.length||sparse[at]!=slot||sparse[at+1]!=location||sparse[at+2]!=timestamp)return false;
                at+=3;
            }
            return at==sparse.length;
        }
        boolean matchesSlot(int slot,byte[] bytes){
            if(dense!=null)return integer(dense,slot*4)==integer(bytes,slot*4)&&integer(dense,4096+slot*4)==integer(bytes,4096+slot*4);
            int low=0,high=sparse.length/3-1;
            while(low<=high){int middle=(low+high)>>>1,at=middle*3,value=sparse[at];
                if(value<slot)low=middle+1;else if(value>slot)high=middle-1;
                else return sparse[at+1]==integer(bytes,slot*4)&&sparse[at+2]==integer(bytes,4096+slot*4);
            }
            return integer(bytes,slot*4)==0&&integer(bytes,4096+slot*4)==0;
        }
    }
    private static int integer(byte[] bytes,int at){return (bytes[at]&255)<<24|(bytes[at+1]&255)<<16|(bytes[at+2]&255)<<8|bytes[at+3]&255;}
    private record RegionMemo(RegionHeader header,java.nio.file.attribute.FileTime modified,long length,Object fileKey,BitSet full) {}
    private int headerBytes;
    private void forget(Path path){RegionMemo old=catalog.remove(path);if(old!=null)headerBytes-=old.header.bytes();}
    private void remember(Path path,RegionMemo memo){
        forget(path);
        if(catalog.size()<MAX_CACHED_REGIONS&&memo.header.bytes()<=MAX_HEADER_BYTES-headerBytes){catalog.put(path,memo);headerBytes+=memo.header.bytes();}
    }
    private final Map<Path,RegionMemo> catalog=new LinkedHashMap<>();
    private long decoded,unchanged;
    public long decodedChunks(){return decoded;}
    public long unchangedChunks(){return unchanged;}
    public Snapshot scanSnapshot(Path regionDirectory) throws IOException { return scanSnapshot(regionDirectory,true); }
    /** Omitted checkpoint entries mean unchanged FULL terrain, never proof of completion. */
    public Snapshot scanSnapshot(Path regionDirectory,boolean audit) throws IOException {
        return scanSnapshot(regionDirectory,audit,null);
    }
    Snapshot scanSnapshot(Path regionDirectory,boolean audit,SavedChunkStatus.WinterScope scope) throws IOException {
        regionDirectory=regionDirectory.toAbsolutePath().normalize();
        List<Path> paths=regions(regionDirectory);Set<Path> present=new HashSet<>(paths);
        for(var iterator=catalog.entrySet().iterator();iterator.hasNext();){
            var entry=iterator.next();
            if(entry.getKey().getParent().equals(regionDirectory)&&!present.contains(entry.getKey())){
                headerBytes-=entry.getValue().header.bytes();iterator.remove();
            }
        }
        List<ChunkPos> generated=new ArrayList<>(), unfinished=new ArrayList<>();
        Map<ChunkPos,String> unreadable=new LinkedHashMap<>();
        Map<ChunkPos,SavedChunkState> states=new LinkedHashMap<>();decoded=0;unchanged=0;
        for(Path path:paths){
            Matcher match=NAME.matcher(path.getFileName().toString());match.matches();
            int rx=Integer.parseInt(match.group(1)),rz=Integer.parseInt(match.group(2));
            var attributes=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class);
            RegionMemo old=catalog.get(path);
            try(RandomAccessFile file=new RandomAccessFile(path.toFile(),"r")){
                long length=file.length();
                if(length<8192)throw new IOException("Truncated region header: "+path);
                byte[] header=new byte[8192];file.readFully(header);int[] locations=locations(header,length,path);
                boolean sameFile=old!=null&&Objects.equals(old.fileKey,attributes.fileKey());
                boolean sameHeader=sameFile&&old.header.matches(header);
                boolean ambiguous=sameHeader&&(!old.modified.equals(attributes.lastModifiedTime())||old.length!=attributes.size());
                SavedChunkStatus.ReadBuffers buffers=null;BitSet full=new BitSet(1024);
                for(int slot=0;slot<locations.length;slot++){
                    if(Thread.currentThread().isInterrupted())throw new IOException("Index scan cancelled");
                    if(locations[slot]==0)continue;
                    ChunkPos pos=position(rx,rz,slot);
                    boolean stable=!audit&&sameFile&&!ambiguous&&old.full.get(slot)
                        &&(sameHeader||old.header.matchesSlot(slot,header));
                    if(stable){generated.add(pos);full.set(slot);unchanged++;continue;}
                    try{decoded++;if(buffers==null)buffers=new SavedChunkStatus.ReadBuffers();SavedChunkState state=SavedChunkStatus.read(file,regionDirectory,pos,locations[slot],buffers,scope);
                        if(state.full()){
                            generated.add(pos);states.put(pos,state);
                            // External payloads can change independently of the region header.
                            if(!buffers.external)full.set(slot);
                        }else unfinished.add(pos);}
                    catch(IOException failure){unreadable.put(pos,failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage());}
                }
                byte[] after=new byte[8192];file.seek(0);file.readFully(after);
                var end=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class);
                if(Arrays.equals(header,after)&&attributes.lastModifiedTime().equals(end.lastModifiedTime())&&attributes.size()==end.size()){
                    remember(path,new RegionMemo(sameHeader?old.header:new RegionHeader(header),end.lastModifiedTime(),end.size(),end.fileKey(),full));
                }else forget(path);
            }
        }
        return new Snapshot(List.copyOf(generated),List.copyOf(unfinished),Map.copyOf(unreadable),Map.copyOf(states));
    }
    private static final Pattern NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    public List<ChunkPos> scan(Path regionDirectory) throws IOException {
        List<ChunkPos> chunks = new ArrayList<>();
        for(Path path:regions(regionDirectory)){
            Matcher match=NAME.matcher(path.getFileName().toString());match.matches();
            int rx=Integer.parseInt(match.group(1)),rz=Integer.parseInt(match.group(2));
            try(RandomAccessFile file=new RandomAccessFile(path.toFile(),"r")){
                int[] locations=locations(file,path);
                for(int slot=0;slot<locations.length;slot++)if(locations[slot]!=0)chunks.add(position(rx,rz,slot));
            }
        }
        return chunks;
    }
    private static List<Path> regions(Path directory)throws IOException{
        if(!Files.isDirectory(directory))return List.of();
        try(var files=Files.list(directory)){return files.filter(path->NAME.matcher(path.getFileName().toString()).matches()).sorted().toList();}
    }
    private static ChunkPos position(int rx,int rz,int slot){
        return new ChunkPos(Math.addExact(Math.multiplyExact(rx,32),slot%32),Math.addExact(Math.multiplyExact(rz,32),slot/32));
    }
    private static int[] locations(RandomAccessFile file,Path path)throws IOException{
        long length=file.length();
        if(length<8192)throw new IOException("Truncated region header: "+path);
        byte[] header=new byte[4096];file.seek(0);file.readFully(header);
        return locations(header,length,path);
    }
    private static int[] locations(byte[] header,long length,Path path)throws IOException{
        long sectors=(length+4095)/4096;
        if(length<8192)throw new IOException("Truncated region header: "+path);
        var bytes=java.nio.ByteBuffer.wrap(header);int[] locations=new int[1024];
        for(int slot=0;slot<locations.length;slot++){
            int entry=bytes.getInt(),offset=entry>>>8,count=entry&255;
            if(entry!=0&&(offset<2||count==0||(long)offset+count>sectors))throw new IOException("Invalid region location: "+path+" slot "+slot);
            locations[slot]=entry;
        }
        return locations;
    }
}
