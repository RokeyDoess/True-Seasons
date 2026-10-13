package dev.trueseasons.migration;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Reads ONLY location headers. No coordinates outside saved regions, no writes. */
public final class GeneratedChunkIndex {
    public record Snapshot(List<ChunkPos> generated,List<ChunkPos> unfinished,Map<ChunkPos,String> unreadable,Map<ChunkPos,SavedChunkState> states) {}
    public Snapshot scanSnapshot(Path regionDirectory) throws IOException {
        List<ChunkPos> generated=new ArrayList<>(), unfinished=new ArrayList<>();
        Map<ChunkPos,String> unreadable=new LinkedHashMap<>();
        Map<ChunkPos,SavedChunkState> states=new LinkedHashMap<>();
        for(ChunkPos pos:scan(regionDirectory)) {
            if(Thread.currentThread().isInterrupted())throw new IOException("Index scan cancelled");
            try { SavedChunkState state=SavedChunkStatus.read(regionDirectory,pos); if(state.full()) { generated.add(pos); states.put(pos,state); } else unfinished.add(pos); }
            catch(IOException failure) { unreadable.put(pos,failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage()); }
        }
        return new Snapshot(List.copyOf(generated),List.copyOf(unfinished),Map.copyOf(unreadable),Map.copyOf(states));
    }
    private static final Pattern NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    public List<ChunkPos> scan(Path regionDirectory) throws IOException {
        if (!Files.isDirectory(regionDirectory)) return List.of();
        List<ChunkPos> chunks = new ArrayList<>();
        try (var files = Files.list(regionDirectory)) {
            for (Path path : files.sorted().toList()) {
                Matcher match = NAME.matcher(path.getFileName().toString());
                if (!match.matches()) continue;
                int rx = Integer.parseInt(match.group(1)), rz = Integer.parseInt(match.group(2));
                try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
                    long sectors = (file.length() + 4095) / 4096;
                    if (file.length() < 8192) throw new IOException("Truncated region header: " + path);
                    for (int slot = 0; slot < 1024; slot++) {
                        int entry = file.readInt(), offset = entry >>> 8, count = entry & 255;
                        if (entry == 0) continue;
                        if (offset < 2 || count == 0 || (long) offset + count > sectors)
                            throw new IOException("Invalid region location: " + path + " slot " + slot);
                        chunks.add(new ChunkPos(Math.addExact(Math.multiplyExact(rx, 32), slot % 32),
                                Math.addExact(Math.multiplyExact(rz, 32), slot / 32)));
                    }
                }
            }
        }
        return chunks;
    }
}
