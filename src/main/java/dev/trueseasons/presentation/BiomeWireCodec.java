package dev.trueseasons.presentation;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.*;
import java.util.function.IntPredicate;

/** Paper/Minecraft 26.3 palette format: fixed-size packed longs, with no length prefix. */
public final class BiomeWireCodec {
    private BiomeWireCodec() {}
    public record ChunkData(byte[] sections, byte[] biomes) {}

    public static ChunkData chunk(byte[] source, int originalCount, boolean winter) {
        return chunk(source, originalCount, winter, id -> true);
    }
    public static ChunkData chunk(byte[] source, int originalCount, boolean winter, IntPredicate winterBiome) {
        ByteBuf in = Unpooled.wrappedBuffer(source), out = Unpooled.buffer(), biomes = Unpooled.buffer();
        try {
            while (in.isReadable()) {
                out.writeShort(in.readShort()); // nonempty block count
                out.writeShort(in.readShort()); // fluid count, introduced before 26.3
                int start = in.readerIndex();
                skipPalette(in, 4096, 8);
                out.writeBytes(in, start, in.readerIndex() - start);
                int[] ids = readBiomes(in);
                writeBiomes(out, ids, originalCount, winter, Math.multiplyExact(originalCount, 2), winterBiome);
                writeBiomes(biomes, ids, originalCount, false, originalCount);
            }
            return new ChunkData(bytes(out), bytes(biomes));
        } finally { in.release(); out.release(); biomes.release(); }
    }

    public static byte[] biomes(byte[] source, int originalCount, boolean winter) {
        return biomes(source, originalCount, winter, id -> true);
    }
    public static byte[] biomes(byte[] source, int originalCount, boolean winter, IntPredicate winterBiome) {
        ByteBuf in = Unpooled.wrappedBuffer(source), out = Unpooled.buffer();
        try {
            while (in.isReadable()) writeBiomes(out, readBiomes(in), originalCount, winter, Math.multiplyExact(originalCount, 2), winterBiome);
            return bytes(out);
        } finally { in.release(); out.release(); }
    }

    static int[] readBiomes(ByteBuf in) {
        int bits = in.readUnsignedByte();
        int[] palette = bits == 0 ? new int[]{readVarInt(in)} : bits <= 3 ? readPalette(in) : null;
        int[] result = new int[64];
        if (bits == 0) { Arrays.fill(result, palette[0]); return result; }
        if (bits > 32) throw new IllegalArgumentException("Unsupported biome palette width " + bits);
        int perLong = 64 / bits; long mask = (1L << bits) - 1;
        for (int base = 0; base < 64; base += perLong) {
            long value = in.readLong();
            for (int j = 0; j < perLong && base + j < 64; j++) {
                int id = (int) ((value >>> (j * bits)) & mask);
                result[base + j] = palette == null ? id : palette[id];
            }
        }
        return result;
    }

    private static void writeBiomes(ByteBuf out, int[] ids, int count, boolean winter) {
        writeBiomes(out, ids, count, winter, Math.multiplyExact(count, 2));
    }

    static void writeBiomes(ByteBuf out, int[] ids, int count, boolean winter, int registrySize) {
        writeBiomes(out, ids, count, winter, registrySize, id -> true);
    }
    private static void writeBiomes(ByteBuf out, int[] ids, int count, boolean winter, int registrySize, IntPredicate winterBiome) {
        LinkedHashMap<Integer, Integer> palette = new LinkedHashMap<>();
        int[] values = new int[64];
        for (int i = 0; i < 64; i++) {
            if (ids[i] < 0 || ids[i] >= count) throw new IllegalArgumentException("Unknown original biome id " + ids[i]);
            values[i] = ids[i] + (winter && winterBiome.test(ids[i]) ? count : 0);
            palette.computeIfAbsent(values[i], key -> palette.size());
        }
        if (palette.size() == 1) { out.writeByte(0); writeVarInt(out, values[0]); return; }
        int bits = 32 - Integer.numberOfLeadingZeros(palette.size() - 1);
        boolean direct = bits > 3;
        if (direct) bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(registrySize - 1));
        out.writeByte(bits);
        if (!direct) { writeVarInt(out, palette.size()); for (int id : palette.keySet()) writeVarInt(out, id); }
        int perLong = 64 / bits;
        for (int base = 0; base < 64; base += perLong) {
            long value = 0;
            for (int j = 0; j < perLong && base + j < 64; j++) {
                int id = direct ? values[base + j] : palette.get(values[base + j]);
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
