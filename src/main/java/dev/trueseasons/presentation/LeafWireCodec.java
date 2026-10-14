package dev.trueseasons.presentation;

import io.netty.buffer.*;
import java.util.*;
import java.util.function.IntPredicate;

/** Read-only original block sampling and independent block-palette rewriting. */
public final class LeafWireCodec {
    private LeafWireCodec() {}
    @FunctionalInterface public interface Mapper { int map(int section, int index, int original); }
    public record Section(short blocks, short fluids, int[] states, byte[] originalBlocks, byte[] biomes) {}
    public static List<Section> read(byte[] bytes, IntPredicate relevant) {
        ByteBuf in = Unpooled.wrappedBuffer(bytes); List<Section> result = new ArrayList<>();
        try {
            while (in.isReadable()) {
                short blocks=in.readShort(), fluids=in.readShort(); int start=in.readerIndex();
                int bits=in.readUnsignedByte();
                if(bits>32)throw new IllegalArgumentException("Invalid block palette width");
                int[] palette=bits==0?new int[]{varInt(in)}:bits<=8?palette(in,256):null;
                boolean needed=palette==null||Arrays.stream(palette).anyMatch(relevant);
                int[] states=needed?new int[4096]:null;
                if(bits==0){if(needed)Arrays.fill(states,palette[0]);}
                else {
                    int per=64/bits; long mask=(1L<<bits)-1;
                    for(int base=0;base<4096;base+=per){long packed=in.readLong();
                        if(needed)for(int j=0;j<per&&base+j<4096;j++){
                            int index=(int)((packed>>>(j*bits))&mask);
                            if(palette!=null&&index>=palette.length)throw new IllegalArgumentException("Block palette index outside palette");
                            states[base+j]=palette==null?index:palette[index];
                        }
                    }
                }
                byte[] original=new byte[in.readerIndex()-start];in.getBytes(start,original);
                start=in.readerIndex(); skipBiome(in);
                byte[] biomes=new byte[in.readerIndex()-start];in.getBytes(start,biomes);
                result.add(new Section(blocks,fluids,states,original,biomes));
            }
            return result;
        }finally{in.release();}
    }
    public static byte[] write(List<Section> sections,int registrySize,Mapper mapper){
        ByteBuf out=Unpooled.buffer();
        try{for(int section=0;section<sections.size();section++){
            Section source=sections.get(section);out.writeShort(source.blocks).writeShort(source.fluids);
            if(source.states==null)out.writeBytes(source.originalBlocks);
            else{
                int[] states=source.states.clone();boolean changed=false;
                for(int i=0;i<4096;i++){int mapped=mapper.map(section,i,states[i]);
                    if(mapped<0||mapped>=registrySize)throw new IllegalArgumentException("Block outside native registry");
                    changed|=mapped!=states[i];states[i]=mapped;
                }
                if(changed)writePalette(out,states,registrySize);else out.writeBytes(source.originalBlocks);
            }
            out.writeBytes(source.biomes);
        }byte[] bytes=new byte[out.readableBytes()];out.getBytes(0,bytes);return bytes;
        }finally{out.release();}
    }
    static void writePalette(ByteBuf out,int[] states,int registrySize){
        Map<Integer,Integer> palette=new LinkedHashMap<>();for(int state:states)palette.computeIfAbsent(state,k->palette.size());
        if(palette.size()==1){out.writeByte(0);varInt(out,states[0]);return;}
        int bits=Math.max(4,32-Integer.numberOfLeadingZeros(palette.size()-1));boolean direct=bits>8;
        if(direct)bits=Math.max(9,32-Integer.numberOfLeadingZeros(registrySize-1));
        out.writeByte(bits);if(!direct){varInt(out,palette.size());for(int id:palette.keySet())varInt(out,id);}
        int per=64/bits;for(int base=0;base<4096;base+=per){long packed=0;
            for(int j=0;j<per&&base+j<4096;j++)packed|=(long)(direct?states[base+j]:palette.get(states[base+j]))<<(j*bits);
            out.writeLong(packed);
        }
    }
    private static int[] palette(ByteBuf in,int max){int size=varInt(in);if(size<1||size>max)throw new IllegalArgumentException("Invalid palette length");
        int[] result=new int[size];for(int i=0;i<size;i++)result[i]=varInt(in);return result;}
    private static void skipBiome(ByteBuf in){int bits=in.readUnsignedByte();if(bits==0){varInt(in);return;}
        if(bits>32)throw new IllegalArgumentException("Invalid biome palette width");if(bits<=3)palette(in,8);
        in.skipBytes(((64+64/bits-1)/(64/bits))*8);}
    private static int varInt(ByteBuf in){int result=0;for(int i=0;i<5;i++){int b=in.readUnsignedByte();result|=(b&127)<<(i*7);if((b&128)==0)return result;}throw new IllegalArgumentException("Invalid VarInt");}
    private static void varInt(ByteBuf out,int value){do{int b=value&127;value>>>=7;out.writeByte(value==0?b:b|128);}while(value!=0);}
}
