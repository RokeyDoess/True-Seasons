package dev.trueseasons.migration;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;

/** Explicit read-only benchmark; never part of ordinary checks or a server plugin. */
public final class SavedChunkIndexBenchmark {
    public static void main(String[] args) throws Exception {
        if(args.length<2||args.length>3)throw new IllegalArgumentException("Usage: <region directory> <chunk count to create, or 0 to read existing> [baseline jar for exact comparison]");
        Path directory=Path.of(args[0]).toAbsolutePath();int count=Integer.parseInt(args[1]);
        if(count<0)throw new IllegalArgumentException("Negative fixture size");
        if(count>0){
            if(Files.exists(directory))throw new IllegalArgumentException("Fixture directory must not exist");
            create(directory,count);
        }
        var bean=ManagementFactory.getThreadMXBean();
        var allocations=bean instanceof com.sun.management.ThreadMXBean extended?extended:null;
        if(allocations!=null&&allocations.isThreadAllocatedMemorySupported())allocations.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().threadId();
        for(int run=0;run<4;run++){
            long before=allocations==null?0:allocations.getThreadAllocatedBytes(thread),start=System.nanoTime();
            var snapshot=new GeneratedChunkIndex().scanSnapshot(directory);
            long elapsed=System.nanoTime()-start,allocated=allocations==null?0:allocations.getThreadAllocatedBytes(thread)-before;
            if(count>0&&(snapshot.generated().size()!=count||!snapshot.unfinished().isEmpty()||!snapshot.unreadable().isEmpty()
                    ||snapshot.states().values().stream().anyMatch(state->!state.matches("benchmark-WINTER",false))))
                throw new AssertionError("Benchmark fixtures were not indexed exactly");
            System.out.printf(Locale.ROOT,"run=%d ms=%.3f allocatedMiB=%.3f full=%d unfinished=%d errors=%d stateHash=%d%n",
                run,elapsed/1e6,allocated/1048576.0,snapshot.generated().size(),snapshot.unfinished().size(),snapshot.unreadable().size(),stateHash(snapshot));
        }
        if(args.length==3)compare(directory,Path.of(args[2]));
    }
    private static void compare(Path directory,Path baseline)throws Exception{
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{baseline.toUri().toURL()},null)){
            Class<?> type=loader.loadClass(GeneratedChunkIndex.class.getName());
            Object previous=type.getMethod("scanSnapshot",Path.class).invoke(type.getConstructor().newInstance(),directory);
            var current=new GeneratedChunkIndex().scanSnapshot(directory);
            for(String name:List.of("generated","unfinished")){
                List<?> old=(List<?>)previous.getClass().getMethod(name).invoke(previous);
                List<?> fresh=(List<?>)current.getClass().getMethod(name).invoke(current);
                if(!old.stream().map(Object::toString).toList().equals(fresh.stream().map(Object::toString).toList()))
                    throw new AssertionError("Baseline coordinate order/content differs: "+name);
            }
            for(String name:List.of("states","unreadable")){
                Map<?,?> old=(Map<?,?>)previous.getClass().getMethod(name).invoke(previous);
                Map<?,?> fresh=(Map<?,?>)current.getClass().getMethod(name).invoke(current);
                if(!canonical(old).equals(canonical(fresh)))throw new AssertionError("Baseline checkpoint content differs: "+name);
            }
            System.out.println("Exact baseline comparison passed: all coordinates, checkpoint fields and errors match");
        }
    }
    private static Map<String,String> canonical(Map<?,?> values){
        Map<String,String> result=new HashMap<>();values.forEach((key,value)->result.put(key.toString(),value.toString()));return result;
    }
    private static int stateHash(GeneratedChunkIndex.Snapshot snapshot){
        int result=0;
        for(var row:snapshot.states().entrySet())result+=(31*row.getKey().x()+row.getKey().z())^row.getValue().hashCode();
        return result;
    }
    private static void create(Path directory,int count)throws IOException{
        Files.createDirectories(directory);
        byte[] payload;
        try(var bytes=new ByteArrayOutputStream()){
            try(var data=new DataOutputStream(new DeflaterOutputStream(bytes))){
                data.writeByte(10);data.writeUTF("");
                data.writeByte(8);data.writeUTF("Status");data.writeUTF("minecraft:full");
                // Unrelated terrain arrays are still consumed through the bounded parser.
                data.writeByte(7);data.writeUTF("fixture_terrain");data.writeInt(16384);data.write(new byte[16384]);
                data.writeByte(10);data.writeUTF("ChunkBukkitValues");
                data.writeByte(8);data.writeUTF("trueseasons:season_version");data.writeUTF("benchmark-WINTER");
                data.writeByte(0);data.writeByte(0);
            }
            payload=bytes.toByteArray();
        }
        for(int base=0,region=0;base<count;base+=1024,region++){
            int size=Math.min(1024,count-base);
            try(var file=new RandomAccessFile(directory.resolve("r."+(region-100)+".0.mca").toFile(),"rw")){
                file.setLength((size+2L)*4096);
                byte[] header=new byte[8192];
                var positions=java.nio.ByteBuffer.wrap(header);
                for(int slot=0;slot<size;slot++)positions.putInt(((slot+2)<<8)|1);
                file.write(header);
                for(int slot=0;slot<size;slot++){
                    file.seek((slot+2L)*4096);file.writeInt(payload.length+1);file.writeByte(2);file.write(payload);
                }
            }
        }
    }
}
