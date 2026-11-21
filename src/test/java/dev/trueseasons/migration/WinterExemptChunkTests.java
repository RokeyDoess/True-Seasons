package dev.trueseasons.migration;

import dev.trueseasons.climate.SeasonClimatePolicy;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Saved biome proofs must never bypass restoration, live changes or incomplete terrain. */
public final class WinterExemptChunkTests {
    private static int checks;
    private static final ChunkPos POS=new ChunkPos(0,0);
    private static final SavedChunkStatus.WinterScope SCOPE=new SavedChunkStatus.WinterScope(
        new SeasonClimatePolicy(SeasonClimatePolicy.HOT,SeasonClimatePolicy.COLD),-1,2);
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private record Section(int y,List<String> palette,long[] data){}
    private static Section hot(int y){return new Section(y,List.of("minecraft:jungle"),null);}
    private static byte[] nbt(List<Section> sections,String metadata,int metadataType)throws IOException{
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeByte(10);out.writeUTF("");out.writeByte(8);out.writeUTF("Status");out.writeUTF("minecraft:full");
        out.writeByte(9);out.writeUTF("sections");out.writeByte(10);out.writeInt(sections.size());
        for(Section section:sections){
            out.writeByte(1);out.writeUTF("Y");out.writeByte(section.y);
            out.writeByte(10);out.writeUTF("biomes");
            out.writeByte(9);out.writeUTF("palette");out.writeByte(8);out.writeInt(section.palette.size());
            for(String biome:section.palette)out.writeUTF(biome);
            if(section.data!=null){out.writeByte(12);out.writeUTF("data");out.writeInt(section.data.length);for(long value:section.data)out.writeLong(value);}
            out.writeByte(0);out.writeByte(0);
        }
        if(metadata!=null){
            out.writeByte(10);out.writeUTF("ChunkBukkitValues");out.writeByte(metadataType);out.writeUTF("trueseasons:"+metadata);
            if(metadataType==8)out.writeUTF("old-version");else if(metadataType==3)out.writeInt(1);else if(metadataType==7){out.writeInt(1);out.writeByte(1);}
            out.writeByte(0);
        }
        out.writeByte(0);return bytes.toByteArray();
    }
    private static SavedChunkState read(Path directory,List<Section> sections,String metadata,int type)throws IOException{
        try(var file=new RandomAccessFile(directory.resolve("r.0.0.mca").toFile(),"rw")){
            byte[] bytes=nbt(sections,metadata,type);int sectors=(bytes.length+5+4095)/4096;
            file.setLength((2L+sectors)*4096);file.seek(0);file.writeInt(2<<8|sectors);
            file.seek(8192);file.writeInt(bytes.length+1);file.writeByte(3);file.write(bytes);
        }
        return new GeneratedChunkIndex().scanSnapshot(directory,true,SCOPE).states().get(POS);
    }
    public static void main(String[] args)throws Exception{
        Path directory=Files.createTempDirectory("winter-exempt-");
        try{
            List<Section> hot=List.of(hot(-1),hot(0),hot(1));
            SavedChunkState exempt=read(directory,hot,null,0);
            check(exempt.winterExempt(),"Every height is proven exempt without loading the chunk");
            check(read(directory,hot,"season_version",8).winterExempt(),"A valid old version alone does not require block changes");
            for(String metadata:List.of("snow_v1","ground_cover_v1","melt_v1","winter_pending","winter_cursor","winter_local","winter_order","unknown_future_backup"))
                check(!read(directory,hot,metadata,7).winterExempt(),"Never bypass saved ownership or work: "+metadata);
            check(!read(directory,hot,"season_version",3).winterExempt(),"Malformed version still gets validated live");
            check(!read(directory,List.of(hot(-1),hot(0)),null,0).winterExempt(),"Missing height section has no proof");
            check(!read(directory,List.of(hot(-1),hot(0),hot(0),hot(1)),null,0).winterExempt(),"Duplicate section has no proof");
            check(!read(directory,List.of(new Section(-1,List.of("minecraft:plains"),null),hot(0),hot(1)),null,0).winterExempt(),"Even a cave biome prevents whole-chunk exclusion");
            check(!read(directory,List.of(hot(-1),new Section(0,List.of("minecraft:jungle","minecraft:plains"),new long[]{0}),hot(1)),null,0).winterExempt(),"Mixed palette follows the normal path");
            check(read(directory,List.of(hot(-1),new Section(0,List.of("minecraft:jungle","minecraft:desert"),new long[]{-1}),hot(1)),null,0).winterExempt(),"Packed hot-only palette is exempt");
            for(long[] invalid:new long[][]{null,new long[0],new long[2]})
                check(!read(directory,List.of(hot(-1),new Section(0,List.of("minecraft:jungle","minecraft:desert"),invalid),hot(1)),null,0).winterExempt(),"Incomplete packed biome data has no proof");
            check(!read(directory,List.of(hot(-1),new Section(0,List.of("minecraft:jungle","minecraft:desert","minecraft:badlands"),new long[]{-1,-1}),hot(1)),null,0).winterExempt(),"Out-of-range packed biome references have no proof");
            check(!read(directory,List.of(hot(-1),new Section(0,List.of(),null),hot(1)),null,0).winterExempt(),"Empty palette has no proof");
            check(!read(directory,List.of(hot(-1),new Section(0,List.of("INVALID:jungle"),null),hot(1)),null,0).winterExempt(),"Invalid biome key has no proof");
            check(!read(directory,List.of(hot(-1),new Section(0,List.of("minecraft:jungle_"+"x".repeat(300)),null),hot(1)),null,0).winterExempt(),"Oversized keys do not enter the biome decision cache");
            var job=new ChunkMigrationEngine.Job(null);job.scanned=true;
            job.observeCheckpoint(POS,exempt,"new-winter",false,true,false,true);
            check(job.finished()&&job.queue.isEmpty(),"Exempt saved terrain needs no live load");
            job=new ChunkMigrationEngine.Job(null);job.scanned=true;job.observeFull(POS);
            job.observeCheckpoint(POS,exempt,"new-winter",false,true,false,true);
            check(!job.finished()&&job.queued.contains(POS),"Live observation supersedes the saved biome proof");
            job=new ChunkMigrationEngine.Job(null);job.scanned=true;
            job.observeCheckpoint(POS,exempt,"new-winter",false,true,true,true);
            check(!job.finished(),"Map recovery still receives a live visit");
            check(!new GeneratedChunkIndex().scanSnapshot(directory).states().get(POS).winterExempt(),"Ordinary index reads never invent a climate proof");
        }finally{
            Files.deleteIfExists(directory.resolve("r.0.0.mca"));Files.delete(directory);
        }
        System.out.println("Winter-exempt saved chunks: "+checks+" assertions passed");
    }
}
