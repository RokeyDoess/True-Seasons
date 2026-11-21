package dev.trueseasons.presentation;

import io.netty.buffer.*;
import java.util.*;
import java.util.function.IntPredicate;

/** Connection-local copy-on-write edits over immutable original packet sections. */
final class ChunkPacketEdits {
    private final List<LeafWireCodec.Section> sections;
    private final boolean[] dirty,writable;
    private final int[] counts;
    private final int registrySize;
    private final int encodedSize;
    private final IntPredicate air;
    ChunkPacketEdits(List<LeafWireCodec.Section> source,int registrySize,IntPredicate air){
        sections=new ArrayList<>(source);dirty=new boolean[source.size()];writable=new boolean[source.size()];
        counts=new int[source.size()];for(int i=0;i<counts.length;i++)counts[i]=source.get(i).blocks();
        int size=0;for(var section:source)size=Math.addExact(size,4+section.originalBlocks().length+section.biomes().length);
        encodedSize=size;
        this.registrySize=registrySize;this.air=air;
    }
    List<LeafWireCodec.Section> sections(){return sections;}
    int encodedSize(){return encodedSize;}
    int state(int section,int index){
        var source=sections.get(section);if(source.states()!=null)return source.states()[index];
        int singleton=LeafWireCodec.singleState(source.originalBlocks());
        return singleton>=0?singleton:states(section)[index];
    }
    int[] states(int section){
        var source=sections.get(section);
        if(source.states()==null){
            int[] decoded=LeafWireCodec.blockStates(source.originalBlocks());
            sections.set(section,new LeafWireCodec.Section(source.blocks(),source.fluids(),decoded,source.originalBlocks(),source.biomes()));
            writable[section]=true;return decoded;
        }
        return source.states();
    }
    /** Install shared read-only samples; the first edit still copies them. */
    void samples(int section,int[] states){
        var source=sections.get(section);
        if(source.states()==null)sections.set(section,new LeafWireCodec.Section(source.blocks(),source.fluids(),states,source.originalBlocks(),source.biomes()));
    }
    void set(int section,int index,int state){
        if(state<0||state>=registrySize)throw new IllegalArgumentException("Block outside native registry");
        int[] states=states(section);int previous=states[index];if(previous==state)return;
        if(!writable[section]){
            states=states.clone();var source=sections.get(section);
            sections.set(section,new LeafWireCodec.Section(source.blocks(),source.fluids(),states,source.originalBlocks(),source.biomes()));
            writable[section]=true;
        }
        counts[section]+=(air.test(previous)?1:0)-(air.test(state)?1:0);
        if(counts[section]<0||counts[section]>4096)throw new IllegalArgumentException("Invalid mapped section block count");
        states[index]=state;dirty[section]=true;
    }
    void writeBlocks(ByteBuf out,int section){
        var source=sections.get(section);out.writeShort(counts[section]).writeShort(source.fluids());
        if(dirty[section])LeafWireCodec.writePalette(out,source.states(),registrySize);else out.writeBytes(source.originalBlocks());
    }
    boolean changed(){for(boolean value:dirty)if(value)return true;return false;}
    byte[] bytes(){
        ByteBuf out=Unpooled.buffer(encodedSize);
        try{for(int i=0;i<sections.size();i++){writeBlocks(out,i);out.writeBytes(sections.get(i).biomes());}
            byte[] bytes=new byte[out.readableBytes()];out.getBytes(0,bytes);return bytes;
        }finally{out.release();}
    }
}
