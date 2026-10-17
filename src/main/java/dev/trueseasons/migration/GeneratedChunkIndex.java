package dev.trueseasons.migration;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Read-only saved terrain discovery and checkpoints; never loads or generates chunks. */
public final class GeneratedChunkIndex {
    public record Snapshot(List<ChunkPos> generated,List<ChunkPos> unfinished,Map<ChunkPos,String> unreadable,Map<ChunkPos,SavedChunkState> states) {}
    public Snapshot scanSnapshot(Path regionDirectory) throws IOException {
        List<ChunkPos> generated=new ArrayList<>(), unfinished=new ArrayList<>();
        Map<ChunkPos,String> unreadable=new LinkedHashMap<>();
        Map<ChunkPos,SavedChunkState> states=new LinkedHashMap<>();
        for(Path path:regions(regionDirectory)){
            Matcher match=NAME.matcher(path.getFileName().toString());match.matches();
            int rx=Integer.parseInt(match.group(1)),rz=Integer.parseInt(match.group(2));
            try(RandomAccessFile file=new RandomAccessFile(path.toFile(),"r")){
                int[] locations=locations(file,path);byte[] scratch=new byte[5];
                for(int slot=0;slot<locations.length;slot++){
                    if(Thread.currentThread().isInterrupted())throw new IOException("Index scan cancelled");
                    if(locations[slot]==0)continue;
                    ChunkPos pos=position(rx,rz,slot);
                    try{SavedChunkState state=SavedChunkStatus.read(file,regionDirectory,pos,locations[slot],scratch);
                        if(state.full()){generated.add(pos);states.put(pos,state);}else unfinished.add(pos);}
                    catch(IOException failure){unreadable.put(pos,failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage());}
                }
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
        long length=file.length(),sectors=(length+4095)/4096;
        if(length<8192)throw new IOException("Truncated region header: "+path);
        byte[] header=new byte[4096];file.seek(0);file.readFully(header);
        var bytes=java.nio.ByteBuffer.wrap(header);int[] locations=new int[1024];
        for(int slot=0;slot<locations.length;slot++){
            int entry=bytes.getInt(),offset=entry>>>8,count=entry&255;
            if(entry!=0&&(offset<2||count==0||(long)offset+count>sectors))throw new IOException("Invalid region location: "+path+" slot "+slot);
            locations[slot]=entry;
        }
        return locations;
    }
}
