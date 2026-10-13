package dev.trueseasons.integration;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

/** Reads only region files. A unique final PDC token proves the whole edited chunk is readable. */
public final class SavedChunkRevision {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private SavedChunkRevision() {}
    public static boolean regionAbsent(Path folder, int x, int z) throws IOException {
        Path directory = folder.resolve("region");
        if (!Files.isDirectory(directory)) throw new IOException("World region directory is not readable");
        Path region = directory.resolve("r." + x + "." + z + ".mca");
        if (Files.notExists(region)) return true;
        if (!Files.isRegularFile(region)) throw new IOException("Region file is not readable");
        return false;
    }

    /** A known region directory plus a missing file/empty location is source deletion, not a corrupt save. */
    public static boolean sourceAbsent(Path folder, int x, int z) throws IOException {
        Path directory = folder.resolve("region");
        if (!Files.isDirectory(directory)) throw new IOException("World region directory is not readable");
        Path region = directory.resolve("r." + Math.floorDiv(x, 32) + "." + Math.floorDiv(z, 32) + ".mca");
        if (Files.notExists(region)) return true;
        if (!Files.isRegularFile(region)) throw new IOException("Region file is not readable");
        try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "r")) {
            if (file.length() < 8192) throw new IOException("Region header not readable");
            long position = (Math.floorMod(x, 32) + Math.floorMod(z, 32) * 32) * 4L;
            file.seek(position); int location = file.readInt();
            if (location != 0) {
                int sector = location >>> 8, sectors = location & 255;
                // Paper can leave unused tail padding unwritten in the final allocated
                // sector. Presence uses rounded allocation; final proof still reads
                // the exact payload below and rejects truncated chunk bytes.
                if (sector < 2 || sectors == 0 || (long) sector + sectors > (file.length() + 4095) / 4096)
                    throw new IOException("Invalid region location");
                return false;
            }
            file.seek(position);
            if (file.readInt() != location) throw new IOException("Chunk location changed during read");
            return true;
        }
    }

    public static boolean matches(Path folder, int x, int z, String key, String token) throws IOException {
        return observe(folder, x, z, key, token, false);
    }
    /** A purge requires a readable chunk with the old token absent, never a missing/corrupt file. */
    public static boolean purged(Path folder, int x, int z, String key, String token) throws IOException {
        return !observe(folder, x, z, key, token, true);
    }
    private static boolean observe(Path folder, int x, int z, String key, String token, boolean strict) throws IOException {
        Path region = folder.resolve("region/r." + Math.floorDiv(x, 32) + "." + Math.floorDiv(z, 32) + ".mca");
        if (!Files.exists(region)) { if (strict) throw new IOException("Region not readable"); return false; }
        int index = Math.floorMod(x, 32) + Math.floorMod(z, 32) * 32;
        try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "r")) {
            if (file.length() < 8192) { if (strict) throw new IOException("Region header not readable"); return false; }
            file.seek(index * 4L); int location = file.readInt();
            int sector = location >>> 8, sectors = location & 255;
            if (sector < 2 || sectors == 0) { if (strict) throw new IOException("Chunk not readable"); return false; }
            file.seek(sector * 4096L); int size = file.readInt(); int compression = file.readUnsignedByte();
            byte[] encoded;
            if ((compression & 128) != 0) {
                Path external = folder.resolve("region/c." + x + "." + z + ".mcc");
                if (Files.size(external) > MAX_BYTES) throw new IOException("Oversized external chunk");
                encoded = Files.readAllBytes(external);
            } else {
                if (size < 1 || size > MAX_BYTES || size > sectors * 4096 - 4) {
                    if (strict) throw new IOException("Chunk length not readable"); return false;
                }
                encoded = new byte[size - 1]; file.readFully(encoded);
            }
            boolean matched;
            try (InputStream decoded = decompress(new ByteArrayInputStream(encoded), compression & 127);
                 DataInputStream input = new DataInputStream(new LimitedInputStream(decoded))) {
                if (input.readUnsignedByte() != 10) { if (strict) throw new IOException("Chunk NBT not readable"); return false; }
                input.readUTF(); matched = compound(input, key, token, "", 0);
            }
            // A concurrent region relocation makes this observation inconclusive.
            file.seek(index * 4L);
            if (file.readInt() != location) { if (strict) throw new IOException("Chunk moved during read"); return false; }
            return matched;
        }
    }
    private static InputStream decompress(InputStream stream, int compression) throws IOException {
        return switch (compression) {
            case 1 -> new GZIPInputStream(stream);
            case 2 -> new InflaterInputStream(stream);
            case 3 -> stream;
            case 4 -> {
                try {
                    yield (InputStream) Class.forName("net.jpountz.lz4.LZ4BlockInputStream")
                            .getConstructor(InputStream.class).newInstance(stream);
                } catch (ReflectiveOperationException failure) { throw new IOException("LZ4 region reader unavailable", failure); }
            }
            default -> throw new IOException("Unknown region compression " + compression);
        };
    }
    private static boolean compound(DataInputStream in, String key, String token, String path, int depth) throws IOException {
        if (depth > 64) throw new IOException("NBT nesting too deep");
        boolean matched = false;
        for (int type; (type = in.readUnsignedByte()) != 0;) {
            String name = in.readUTF();
            if (type == 8 && (path.equals("ChunkBukkitValues") || path.equals("Level/ChunkBukkitValues")
                    || path.equals("BukkitValues") || path.equals("Level/BukkitValues")) && name.equals(key)) {
                matched |= in.readUTF().equals(token);
            } else if (type == 10) {
                matched |= compound(in, key, token, path.isEmpty() ? name : path + "/" + name, depth + 1);
            } else skip(in, type, depth + 1);
        }
        return matched;
    }
    private static int length(DataInputStream in) throws IOException {
        int value = in.readInt();
        if (value < 0 || value > MAX_BYTES) throw new IOException("Invalid NBT length");
        return value;
    }
    private static void skip(DataInputStream in, int type, int depth) throws IOException {
        if (depth > 64) throw new IOException("NBT nesting too deep");
        switch (type) {
            case 1 -> in.readByte(); case 2 -> in.readShort(); case 3 -> in.readInt(); case 4 -> in.readLong();
            case 5 -> in.readFloat(); case 6 -> in.readDouble(); case 8 -> in.readUTF();
            case 7 -> in.skipNBytes(length(in));
            case 11 -> in.skipNBytes(length(in) * 4L); case 12 -> in.skipNBytes(length(in) * 8L);
            case 9 -> {
                int child = in.readUnsignedByte(), count = length(in);
                if (child == 0 && count != 0) throw new IOException("Invalid NBT list");
                for (int n = 0; n < count; n++) skip(in, child, depth + 1);
            }
            case 10 -> {
                for (int child; (child = in.readUnsignedByte()) != 0;) { in.readUTF(); skip(in, child, depth + 1); }
            }
            default -> throw new IOException("Invalid NBT tag " + type);
        }
    }
    private static final class LimitedInputStream extends FilterInputStream {
        private int remaining = MAX_BYTES;
        LimitedInputStream(InputStream in) { super(in); }
        @Override public int read() throws IOException {
            if (remaining-- <= 0) throw new IOException("Chunk exceeds read limit");
            return super.read();
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) throw new IOException("Chunk exceeds read limit");
            int read = super.read(b, off, Math.min(len, remaining)); if (read > 0) remaining -= read; return read;
        }
        @Override public long skip(long n) throws IOException {
            long skipped = super.skip(Math.min(n, remaining)); remaining -= (int) skipped; return skipped;
        }
    }
}
