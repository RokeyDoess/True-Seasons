package dev.trueseasons.migration;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

/** Distinguishes saved full terrain from generation scaffolding using actual region/NBT fixtures. */
public final class GeneratedChunkSnapshotTests {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private record Tag(int type, String name, Object value) {}
    private static Tag text(String name, String value) { return new Tag(8, name, value); }
    private static Tag integer(String name, int value) { return new Tag(3, name, value); }
    private static void tag(DataOutputStream data, Tag tag) throws IOException {
        data.writeByte(tag.type()); data.writeUTF(tag.name());
        switch (tag.type()) {
            case 3 -> data.writeInt((Integer) tag.value());
            case 8 -> data.writeUTF((String) tag.value());
            case 7 -> { byte[] bytes = (byte[]) tag.value(); data.writeInt(bytes.length); data.write(bytes); }
            case 10 -> { for (Tag child : (Tag[]) tag.value()) tag(data, child); data.writeByte(0); }
            default -> throw new AssertionError("Unsupported fixture tag " + tag.type());
        }
    }
    private static byte[] status(String status, boolean legacy, Tag... metadata) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeByte(10); data.writeUTF("");
        if (legacy) { data.writeByte(10); data.writeUTF("Level"); }
        data.writeByte(8); data.writeUTF("Status"); data.writeUTF(status);
        if (metadata.length > 0) tag(data, new Tag(10, "ChunkBukkitValues", metadata));
        data.writeByte(0);
        if (legacy) data.writeByte(0);
        return bytes.toByteArray();
    }
    private static byte[] payload(byte[] nbt, int compression) throws IOException {
        if (compression == 3) return nbt;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream stream = compression == 1 ? new GZIPOutputStream(bytes) : new DeflaterOutputStream(bytes)) { stream.write(nbt); }
        return bytes.toByteArray();
    }
    private static byte[] wrongStatusType() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeByte(10); data.writeUTF("");
        data.writeByte(3); data.writeUTF("Status"); data.writeInt(1); data.writeByte(0);
        return bytes.toByteArray();
    }
    private static void chunk(RandomAccessFile file, int slot, int sector, byte[] nbt, int compression) throws IOException {
        byte[] bytes = payload(nbt, compression);
        if (bytes.length + 5 > 4096) throw new AssertionError("Fixture exceeded one sector");
        file.seek(slot * 4L); file.writeInt((sector << 8) | 1);
        file.seek(sector * 4096L); file.writeInt(bytes.length + 1); file.writeByte(compression); file.write(bytes);
    }
    private static void checkpoints(Path parent) throws IOException {
        Path directory = Files.createDirectory(parent.resolve("checkpoints"));
        Path region = directory.resolve("r.0.0.mca");
        String version = "manual-WINTER-r12-snow3";
        String versionKey = "trueseasons:season_version", pendingKey = "trueseasons:winter_pending", cursorKey = "trueseasons:winter_cursor";
        byte[] owned = new byte[33]; owned[0] = 1; // Valid empty ownership bitmap; metadata still requires cleanup.
        byte[][] fixtures = {
            status("minecraft:full", false, text(versionKey,version), new Tag(7,"trueseasons:snow_v1",owned)),
            status("full", true, text(versionKey,version)),
            status("minecraft:full", false, text(versionKey,version), text(pendingKey,version), integer(cursorKey,128)),
            status("minecraft:full", false, text(versionKey,version), integer(pendingKey,128)),
            status("minecraft:full", false, text(versionKey,version), text(cursorKey,"malformed")),
            status("minecraft:full", false, text(versionKey,version), integer(cursorKey,0)),
            status("minecraft:full", false, integer(versionKey,12)),
            status("minecraft:full", false, new Tag(7,"trueseasons:snow_v1",owned)),
            status("minecraft:full", false, text("trueseasons:bluemap_revision","map-token")),
            status("minecraft:full", false),
            status("minecraft:full", false, text("otherplugin:season_version",version), integer("otherplugin:winter_cursor",128),
                    new Tag(10,"otherplugin:data",new Tag[]{text(versionKey,version),text("trueseasons:bluemap_revision","nested") })),
            status("minecraft:full", false, text(versionKey,version), text("trueseasons:bluemap_revision","map-token")),
            status("full", true, text(versionKey,version), text(pendingKey,version), integer(cursorKey,64)),
            status("minecraft:full", false, integer("trueseasons:bluemap_revision",123))
        };
        try (RandomAccessFile file = new RandomAccessFile(region.toFile(),"rw")) {
            file.setLength((fixtures.length + 2L) * 4096);
            for (int slot = 0; slot < fixtures.length; slot++) chunk(file,slot,slot+2,fixtures[slot],slot==1?1:2);
        }
        byte[] before = Files.readAllBytes(region);
        GeneratedChunkIndex index = new GeneratedChunkIndex();
        var snapshot = index.scanSnapshot(directory);
        var states = snapshot.states();
        check(states.size() == fixtures.length && snapshot.unreadable().isEmpty(), "Every readable FULL fixture supplies a saved metadata checkpoint");
        SavedChunkState modern = states.get(new ChunkPos(0,0));
        check(modern.full() && version.equals(modern.version()) && !modern.pending() && modern.matches(version,false),
                "Modern ChunkBukkitValues can hydrate a completed matching season");
        check(!modern.matches("next-winter",false), "A previous revision cannot hydrate a new migration");
        check(!modern.matches(version,true), "A seasonal version or owned snow prevents saved cleanup completion");
        SavedChunkState legacy = states.get(new ChunkPos(1,0));
        check(version.equals(legacy.version()) && legacy.matches(version,false), "Legacy Level.ChunkBukkitValues hydrates the matching season");
        SavedChunkState partial = states.get(new ChunkPos(2,0));
        check(partial.pending() && partial.columns()==128 && !partial.matches(version,false), "A matching stamp with a partial cursor cannot hydrate completed work");
        check(!partial.matches(version,true), "Pending snow remains an explicit cleanup obligation");
        check(states.get(new ChunkPos(3,0)).pending() && !states.get(new ChunkPos(3,0)).matches(version,false),
                "A malformed pending type cannot hide behind a matching version");
        check(states.get(new ChunkPos(4,0)).pending() && !states.get(new ChunkPos(4,0)).matches(version,false),
                "A malformed cursor type cannot hide behind a matching version");
        check(states.get(new ChunkPos(5,0)).pending() && !states.get(new ChunkPos(5,0)).matches(version,false),
                "An orphan zero cursor still prevents premature completion hydration");
        SavedChunkState malformedVersion = states.get(new ChunkPos(6,0));
        check(malformedVersion.version()==null && malformedVersion.seasonMetadata() && !malformedVersion.matches(version,false)
                && !malformedVersion.matches(version,true), "Malformed version metadata must be revalidated rather than skipped");
        check(states.get(new ChunkPos(7,0)).seasonMetadata() && !states.get(new ChunkPos(7,0)).matches(version,true),
                "Even an empty retained snow ownership tag prevents saved cleanup completion");
        SavedChunkState mapOnly = states.get(new ChunkPos(8,0));
        check(!mapOnly.seasonMetadata() && mapOnly.mapMarker() && !mapOnly.matches(version,true), "A temporary map marker prevents cleanup completion after snow metadata is gone");
        SavedChunkState restored = states.get(new ChunkPos(9,0));
        check(!restored.seasonMetadata() && !restored.mapMarker() && restored.matches(version,true) && !restored.matches(version,false),
                "A fully restored chunk can hydrate cleanup but cannot hydrate winter placement");
        SavedChunkState unrelated = states.get(new ChunkPos(10,0));
        check(unrelated.version()==null && !unrelated.pending() && !unrelated.seasonMetadata() && !unrelated.mapMarker()
                && unrelated.matches(version,true), "Unrelated plugin metadata and nested similarly named tags are ignored");
        SavedChunkState mappedComplete = states.get(new ChunkPos(11,0));
        check(mappedComplete.matches(version,false) && !mappedComplete.matches(version,true), "A map receipt does not invalidate completed terrain but still prevents cleanup hydration");
        SavedChunkState legacyPartial = states.get(new ChunkPos(12,0));
        check(legacyPartial.pending() && legacyPartial.columns()==64 && !legacyPartial.matches(version,false), "Legacy pending cursors survive snapshot reading without becoming completed terrain");
        check(states.get(new ChunkPos(13,0)).mapMarker() && !states.get(new ChunkPos(13,0)).matches(version,true),
                "A malformed map marker still requires cleanup instead of being ignored");
        check(Arrays.equals(before,Files.readAllBytes(region)), "Saved checkpoint hydration checks never edit world data");
        check(index.scanSnapshot(directory).states().equals(states), "Saved metadata checkpoints are stable across repeated reads");
    }
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("trueseasons-status-regression-");
        try {
            Path region = directory.resolve("r.-1.2.mca");
            try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "rw")) {
                file.setLength(11 * 4096L);
                chunk(file, 0, 2, status("minecraft:full", false), 2);
                chunk(file, 1, 3, status("minecraft:noise", false), 2);
                chunk(file, 2, 4, status("minecraft:features", false), 3);
                chunk(file, 3, 5, status("full", false), 1);
                chunk(file, 4, 6, status("full", true), 2);
                chunk(file, 5, 7, status("surface", true), 2);
                chunk(file, 6, 8, new byte[]{10, 0}, 2);
                chunk(file, 7, 9, new byte[]{10, 0, 0, 0}, 2);
                chunk(file, 8, 10, wrongStatusType(), 2);
            }
            byte[] before = Files.readAllBytes(region);
            GeneratedChunkIndex index = new GeneratedChunkIndex();
            var snapshot = index.scanSnapshot(directory);
            List<ChunkPos> full = List.of(new ChunkPos(-32,64), new ChunkPos(-29,64), new ChunkPos(-28,64));
            List<ChunkPos> proto = List.of(new ChunkPos(-31,64), new ChunkPos(-30,64), new ChunkPos(-27,64));
            check(snapshot.generated().equals(full), "Only FULL status joins migration across current, plain and legacy NBT");
            check(snapshot.states().keySet().equals(Set.copyOf(full)), "Only FULL terrain supplies saved completion checkpoints");
            check(snapshot.unfinished().equals(proto), "Noise, features and surface statuses remain unfinished terrain");
            check(snapshot.unreadable().keySet().equals(Set.of(new ChunkPos(-26,64),new ChunkPos(-25,64),new ChunkPos(-24,64))),
                    "Truncated NBT, missing Status and malformed Status types remain explicit unreadable errors");
            check(snapshot.unreadable().values().stream().allMatch(value -> value != null && !value.isBlank()), "Unreadable terrain carries a diagnostic");
            check(index.scan(directory).size() == 9, "Legacy occupied-header scanning still includes proto and unreadable entries");
            check(Arrays.equals(before, Files.readAllBytes(region)), "Snapshot indexing never changes a saved region");
            var repeated = index.scanSnapshot(directory);
            check(repeated.generated().equals(snapshot.generated()) && repeated.unfinished().equals(snapshot.unfinished())
                    && repeated.unreadable().keySet().equals(snapshot.unreadable().keySet()), "An unchanged snapshot is stable across repeated scans");
            var empty = index.scanSnapshot(directory.resolve("missing"));
            check(empty.generated().isEmpty() && empty.unfinished().isEmpty() && empty.unreadable().isEmpty(), "Missing region directories remain empty without being created");
            check(!Files.exists(directory.resolve("missing")), "Snapshot indexing never creates a world directory");
            checkpoints(directory);
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("Generated chunk snapshot tests passed: " + assertions + " assertions");
    }
}
