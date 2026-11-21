package dev.trueseasons.presentation;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.*;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/** Paper/Minecraft 26.3 palette format: fixed-size packed longs, with no length prefix. */
public final class BiomeWireCodec {
    private BiomeWireCodec() {}
    public record ChunkData(byte[] sections, byte[] biomes) {}

    public static ChunkData chunk(byte[] source, int originalCount, boolean winter) {
        return chunk(source, originalCount, winter, id -> true);
    }
    public static ChunkData chunk(byte[] source, int originalCount, boolean winter, IntPredicate winterBiome) {
        return chunk(source, originalCount, Math.multiplyExact(originalCount, 2), id -> id + (winter && winterBiome.test(id) ? originalCount : 0));
    }
    public static ChunkData chunk(byte[] source, int originalCount, int registrySize, IntUnaryOperator mapping) {
        ByteBuf in = Unpooled.wrappedBuffer(source), out = Unpooled.buffer(), biomes = Unpooled.buffer();
        int[] ids = new int[64], values = new int[64], palette = new int[8];
        try {
            while (in.isReadable()) {
                out.writeShort(in.readShort()); // nonempty block count
                out.writeShort(in.readShort()); // fluid count, introduced before 26.3
                int start = in.readerIndex();
                skipPalette(in, 4096, 8);
                out.writeBytes(in, start, in.readerIndex() - start);
                mapBiomes(in,out,biomes,originalCount,registrySize,mapping,ids,values,palette);
            }
            return new ChunkData(bytes(out), bytes(biomes));
        } finally { in.release(); out.release(); biomes.release(); }
    }
    /** Encode all block presentation layers and original-biome bookkeeping in one pass. */
    static ChunkData chunk(ChunkPacketEdits source,int originalCount,int registrySize,IntUnaryOperator mapping){
        ByteBuf out=Unpooled.buffer(Math.addExact(source.encodedSize(),source.sections().size()*64)),biomes=Unpooled.buffer();
        int[] ids=new int[64],values=new int[64],palette=new int[8];
        try{
            for(int section=0;section<source.sections().size();section++){
                source.writeBlocks(out,section);
                ByteBuf input=Unpooled.wrappedBuffer(source.sections().get(section).biomes());
                try{mapBiomes(input,out,biomes,originalCount,registrySize,mapping,ids,values,palette);
                    if(input.isReadable())throw new IllegalArgumentException("Trailing section biome bytes");}
                finally{input.release();}
            }
            return new ChunkData(bytes(out),bytes(biomes));
        }finally{out.release();biomes.release();}
    }

    public static byte[] biomes(byte[] source, int originalCount, boolean winter) {
        return biomes(source, originalCount, winter, id -> true);
    }
    public static byte[] biomes(byte[] source, int originalCount, boolean winter, IntPredicate winterBiome) {
        return biomes(source, originalCount, Math.multiplyExact(originalCount, 2), id -> id + (winter && winterBiome.test(id) ? originalCount : 0));
    }
    public static byte[] biomes(byte[] source, int originalCount, int registrySize, IntUnaryOperator mapping) {
        ByteBuf in = Unpooled.wrappedBuffer(source), out = Unpooled.buffer();
        int[] ids = new int[64], values = new int[64], palette = new int[8];
        try {
            while (in.isReadable()) {
                mapBiomes(in,out,null,originalCount,registrySize,mapping,ids,values,palette);
            }
            return bytes(out);
        } finally { in.release(); out.release(); }
    }

    /** A singleton section needs one checked mapping, without expanding 64 identical cells. */
    private static void mapBiomes(ByteBuf in,ByteBuf out,ByteBuf originals,int count,int registrySize,
            IntUnaryOperator mapping,int[] ids,int[] values,int[] palette){
        int bits=in.readUnsignedByte();
        if(bits==0){
            int id=readVarInt(in);
            if(registrySize<count)throw new IllegalArgumentException("Registry smaller than original palette");
            if(id<0||id>=count)throw new IllegalArgumentException("Unknown original biome id "+id);
            int mapped=mapping.applyAsInt(id);
            if(mapped<0||mapped>=registrySize)throw new IllegalArgumentException("Mapped biome outside client registry");
            out.writeByte(0);writeVarInt(out,mapped);
            if(originals!=null){originals.writeByte(0);writeVarInt(originals,id);}
            return;
        }
        readBiomes(in,ids,bits);
        writeMappedBiomes(out,ids,count,registrySize,mapping,values,palette);
        if(originals!=null)writeMappedBiomes(originals,ids,count,count,id->id,values,palette);
    }

    static int[] readBiomes(ByteBuf in) {
        int[] result = new int[64];
        readBiomes(in, result);
        return result;
    }
    private static void readBiomes(ByteBuf in, int[] result) {
        readBiomes(in,result,in.readUnsignedByte());
    }
    private static void readBiomes(ByteBuf in,int[] result,int bits){
        if (bits == 0) { Arrays.fill(result, readVarInt(in)); return; }
        int[] palette = bits <= 3 ? readPalette(in) : null;
        if (bits > 32) throw new IllegalArgumentException("Unsupported biome palette width " + bits);
        int perLong = 64 / bits; long mask = (1L << bits) - 1;
        for (int base = 0; base < 64; base += perLong) {
            long value = in.readLong();
            for (int j = 0; j < perLong && base + j < 64; j++) {
                int id = (int) ((value >>> (j * bits)) & mask);
                result[base + j] = palette == null ? id : palette[id];
            }
        }
    }

    private static void writeBiomes(ByteBuf out, int[] ids, int count, boolean winter) {
        writeBiomes(out, ids, count, winter, Math.multiplyExact(count, 2));
    }

    static void writeBiomes(ByteBuf out, int[] ids, int count, boolean winter, int registrySize) {
        writeBiomes(out, ids, count, winter, registrySize, id -> true);
    }
    private static void writeBiomes(ByteBuf out, int[] ids, int count, boolean winter, int registrySize, IntPredicate winterBiome) {
        writeMappedBiomes(out, ids, count, registrySize, id -> id + (winter && winterBiome.test(id) ? count : 0));
    }
    private static void writeMappedBiomes(ByteBuf out, int[] ids, int count, int registrySize, IntUnaryOperator mapping) {
        writeMappedBiomes(out, ids, count, registrySize, mapping, new int[64], new int[8]);
    }
    /** Scratch belongs to this encode operation; no mutable buffers cross connections. */
    private static void writeMappedBiomes(ByteBuf out, int[] ids, int count, int registrySize,
            IntUnaryOperator mapping, int[] values, int[] palette) {
        if (registrySize < count) throw new IllegalArgumentException("Registry smaller than original palette");
        int size = 0;
        for (int i = 0; i < 64; i++) {
            if (ids[i] < 0 || ids[i] >= count) throw new IllegalArgumentException("Unknown original biome id " + ids[i]);
            values[i] = mapping.applyAsInt(ids[i]);
            if (values[i] < 0 || values[i] >= registrySize) throw new IllegalArgumentException("Mapped biome outside client registry");
            if (size <= 8) {
                int index = 0;
                while (index < size && palette[index] != values[i]) index++;
                if (index == size) { if (size < 8) palette[size] = values[i]; size++; }
            }
        }
        if (size == 1) { out.writeByte(0); writeVarInt(out, values[0]); return; }
        boolean direct = size > 8;
        int bits = direct ? Math.max(4, 32 - Integer.numberOfLeadingZeros(registrySize - 1))
            : 32 - Integer.numberOfLeadingZeros(size - 1);
        out.writeByte(bits);
        if (!direct) { writeVarInt(out, size); for (int index = 0; index < size; index++) writeVarInt(out, palette[index]); }
        int perLong = 64 / bits;
        for (int base = 0; base < 64; base += perLong) {
            long value = 0;
            for (int j = 0; j < perLong && base + j < 64; j++) {
                int id = values[base + j];
                if (!direct) {
                    int index = 0;
                    while (palette[index] != id) index++;
                    id = index;
                }
                value |= (long) id << (j * bits);
            }
            out.writeLong(value);
        }
    }

    private static void skipPalette(ByteBuf in, int entries, int indirectLimit) {
        int bits = in.readUnsignedByte();
        if (bits == 0) { readVarInt(in); return; }
        if (bits <= indirectLimit) { int length = readVarInt(in); for (int i = 0; i < length; i++) readVarInt(in); }
        if (bits > 32) throw new IllegalArgumentException("Unsupported block palette width " + bits);
        int perLong = 64 / bits;
        in.skipBytes(((entries + perLong - 1) / perLong) * 8);
    }
    private static int[] readPalette(ByteBuf in) {
        int length = readVarInt(in);
        if (length < 1 || length > 8) throw new IllegalArgumentException("Invalid biome palette size " + length);
        int[] palette = new int[length]; for (int i = 0; i < length; i++) palette[i] = readVarInt(in); return palette;
    }
    private static byte[] bytes(ByteBuf buffer) { byte[] result = new byte[buffer.readableBytes()]; buffer.getBytes(buffer.readerIndex(), result); return result; }
    private static int readVarInt(ByteBuf in) {
        int result = 0;
        for (int i = 0; i < 5; i++) { int b = in.readUnsignedByte(); result |= (b & 127) << (i * 7); if ((b & 128) == 0) return result; }
        throw new IllegalArgumentException("Oversized VarInt");
    }
    private static void writeVarInt(ByteBuf out, int value) {
        do { int b = value & 127; value >>>= 7; out.writeByte(value == 0 ? b : b | 128); } while (value != 0);
    }
}
