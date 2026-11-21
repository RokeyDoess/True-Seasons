package dev.trueseasons.presentation;

import io.netty.buffer.*;
import java.util.*;

/** Differential composition, immutable originals and native palette-width boundaries. */
public final class ChunkPacketEditsTests {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static int leaves(int original){return original==2?3:original;}
    private static int snow(int section,int index,int original){
        if(original==4&&(index&255)%3==0)return 0;
        if(original==0&&(index>>8)==5&&(index&255)%5==0)return 4;
        return original;
    }
    public static void main(String[] args){
        Random random=new Random(6271);
        for(int fixture=0;fixture<40;fixture++){
            ByteBuf wire=Unpooled.buffer();byte[] bytes;
            try{
                for(int section=0;section<1+fixture%6;section++){
                    int[] states=new int[4096];int count=0;
                    for(int i=0;i<states.length;i++){
                        states[i]=fixture%4==0?0:fixture%4==1?1:random.nextInt(fixture%4==2?8:512);
                        if(states[i]!=0)count++;
                    }
                    wire.writeShort(count).writeShort(0);LeafWireCodec.writePalette(wire,states,512);
                    int[] biomes=new int[64];for(int i=0;i<64;i++)biomes[i]=fixture%3==0?0:random.nextInt(fixture%3==1?4:32);
                    BiomeWireCodec.writeBiomes(wire,biomes,32,false,32);
                }
                bytes=new byte[wire.readableBytes()];wire.readBytes(bytes);
            }finally{wire.release();}
            byte[] original=bytes.clone();
            var source=LeafWireCodec.read(bytes,id->id==2||id==4||id==5);
            List<int[]> originals=new ArrayList<>();for(var section:source)originals.add(section.states()==null?null:section.states().clone());
            ChunkPacketEdits edits=new ChunkPacketEdits(source,512,id->id==0);
            for(int section=0;section<source.size();section++)for(int i=0;i<4096;i++){
                int state=edits.state(section,i);int painted=leaves(state);if(state!=painted)edits.set(section,i,painted);
            }
            for(int section=0;section<source.size();section++)for(int i=0;i<4096;i++){
                int state=edits.state(section,i);int painted=snow(section,i,state);if(state!=painted)edits.set(section,i,painted);
            }
            byte[] leafOnly=LeafWireCodec.write(LeafWireCodec.read(bytes,id->true),512,(section,i,state)->leaves(state));
            byte[] separate=LeafWireCodec.write(LeafWireCodec.read(leafOnly,id->true),512,ChunkPacketEditsTests::snow,id->id==0);
            var expected=BiomeWireCodec.chunk(separate,32,224,id->id%2==0?id+32:id);
            var actual=BiomeWireCodec.chunk(edits,32,224,id->id%2==0?id+32:id);
            check(Arrays.equals(actual.sections(),expected.sections()),"Combined output matches successive leaf, snow and biome transforms");
            check(Arrays.equals(actual.biomes(),expected.biomes()),"Original biome restoration data stays exact");
            check(Arrays.equals(bytes,original),"Outgoing transformations preserve original packet bytes");
            for(int section=0;section<source.size();section++)check(Arrays.equals(source.get(section).states(),originals.get(section)),"Shared original block arrays remain unchanged");
            check(Arrays.equals(new ChunkPacketEdits(source,512,id->id==0).bytes(),original),"A second viewer starts from the original palette and counts");
        }
        ByteBuf packed=Unpooled.buffer();byte[] compact;
        try{packed.writeShort(0).writeShort(0);LeafWireCodec.writePalette(packed,new int[4096],8);packed.writeByte(0).writeByte(0);compact=new byte[packed.readableBytes()];packed.readBytes(compact);}finally{packed.release();}
        var source=LeafWireCodec.read(compact,id->false);int[] shared=new int[4096];
        var first=new ChunkPacketEdits(source,8,id->id==0);var second=new ChunkPacketEdits(source,8,id->id==0);
        first.samples(0,shared);second.samples(0,shared);first.set(0,5,3);
        check(shared[5]==0&&second.state(0,5)==0&&first.state(0,5)==3,"Lazily supplied samples remain independent between viewers");
        check(LeafWireCodec.hasState(source.getFirst().originalBlocks(),id->id==0)&&!LeafWireCodec.hasState(source.getFirst().originalBlocks(),id->id==3),"Packed singleton membership needs no full block array");
        check(source.getFirst().states()==null,"Installing deferred samples never changes shared parsed sections");
        singletonBiomes();
        System.out.println("Shared chunk edits: "+checks+" assertions passed");
    }
    private static void singletonBiomes(){
        for(int id:new int[]{0,31,127,128,511}){
            ByteBuf wire=Unpooled.buffer();byte[] bytes;
            try{
                wire.writeShort(0).writeShort(0).writeByte(0).writeByte(0);
                int[] cells=new int[64];Arrays.fill(cells,id);
                BiomeWireCodec.writeBiomes(wire,cells,512,false,512);
                bytes=new byte[wire.readableBytes()];wire.readBytes(bytes);
            }finally{wire.release();}
            byte[] original=bytes.clone();
            var result=BiomeWireCodec.chunk(bytes,512,4096,biome->biome+3584);
            var edits=new ChunkPacketEdits(LeafWireCodec.read(bytes,state->false),8,state->state==0);
            var combined=BiomeWireCodec.chunk(edits,512,4096,biome->biome+3584);
            check(Arrays.equals(result.sections(),combined.sections()),"Singleton biomes agree across packet composition paths");
            check(Arrays.equals(result.biomes(),combined.biomes()),"Singleton biome restoration bytes agree across paths");
            ByteBuf mapped=Unpooled.wrappedBuffer(result.sections()),restored=Unpooled.wrappedBuffer(result.biomes());
            try{
                mapped.skipBytes(6);
                check(Arrays.stream(BiomeWireCodec.readBiomes(mapped)).allMatch(value->value==id+3584),"Singleton mapping preserves all biome cells across VarInt boundaries");
                check(Arrays.stream(BiomeWireCodec.readBiomes(restored)).allMatch(value->value==id),"Singleton restoration preserves the original biome");
                check(!mapped.isReadable()&&!restored.isReadable(),"Singleton encodings contain exactly one section");
            }finally{mapped.release();restored.release();}
            check(Arrays.equals(bytes,original),"Singleton encoding leaves source bytes unchanged");
            for(int invalid:new int[]{-1,4096}){
                boolean rejected=false;
                try{BiomeWireCodec.chunk(bytes,512,4096,biome->invalid);}catch(IllegalArgumentException expected){rejected=true;}
                check(rejected,"Singleton mapping rejects states outside the delivered registry");
            }
            boolean rejected=false;
            try{BiomeWireCodec.chunk(bytes,512,511,biome->0);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"Singleton mapping rejects a registry smaller than its original palette");
            rejected=false;
            try{BiomeWireCodec.chunk(bytes,id,4096,biome->0);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"Singleton mapping rejects unknown original biome IDs");
        }
    }
}
