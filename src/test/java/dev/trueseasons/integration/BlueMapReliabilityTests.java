package dev.trueseasons.integration;

import dev.trueseasons.integration.RenderLedger.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Regression coverage for revision receipts and real on-disk region save proof. */
public final class BlueMapReliabilityTests {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++; if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        regionCounts();
        RenderLedger ledger = new RenderLedger();
        ChunkKey chunk = new ChunkKey(UUID.randomUUID(), -33, -1);
        check(chunk.region().x() == -2 && chunk.region().z() == -1, "Negative region coordinates");
        check(ledger.dirty(chunk), "Persist intent before first change");
        check(!ledger.dirty(chunk), "Partial steps coalesce into one unfinished intent");
        Revision unfinished = new Revision(chunk, "");
        check(!ledger.needs(unfinished, "surface"), "Partial transitions cannot be retired or dispatched as final");
        check(ledger.complete(chunk, "revision-1"), "Final state creates a revision");
        Revision first = new Revision(chunk, "revision-1");
        check(ledger.needs(first, "surface"), "Final revision awaits first map");
        check(ledger.acknowledge(first, "surface"), "First map receives separate receipt");
        check(!ledger.needs(first, "surface"), "Successful map is not repeatedly rescheduled");
        check(ledger.needs(first, "caves"), "Busy second map retains independent intent");
        check(!ledger.retire(chunk, List.of("surface", "caves")), "Acceptance by one map cannot retire both");
        RenderLedger restarted = new RenderLedger();
        restarted.restore(chunk, ledger.entries().get(chunk).token(), ledger.entries().get(chunk).acknowledged());
        check(!restarted.needs(first, "surface") && restarted.needs(first, "caves"), "Per-map receipts survive restart");
        check(restarted.dirty(chunk), "New change invalidates previous final revision");
        restarted.complete(chunk, "revision-2");
        Revision second = new Revision(chunk, "revision-2");
        check(!restarted.acknowledge(first, "caves"), "Old in-flight completion cannot acknowledge a newer revision");
        check(restarted.needs(second, "surface"), "Previously successful map must render newer state");
        restarted.acknowledge(second, "surface"); restarted.acknowledge(second, "caves");
        check(!restarted.retire(chunk, List.of()), "Unconfigured maps retain pending intent");
        check(restarted.retire(chunk, List.of("surface", "caves")), "Only fulfilled final revision retires");

        Path folder = Files.createTempDirectory("trueseasons-region-proof-");
        try {
            String key = "trueseasons:bluemap_revision";
            for (int compression : List.of(1, 2, 3)) {
                writeChunk(folder, -33, -1, compression, false, key, "partial-token", false);
                check(!SavedChunkRevision.matches(folder, -33, -1, key, "final-token"), "Saved partial state fails proof: compression " + compression);
                writeChunk(folder, -33, -1, compression, false, key, "final-token", false);
                check(SavedChunkRevision.matches(folder, -33, -1, key, "final-token"), "Read final proof: compression " + compression);
                check(!SavedChunkRevision.matches(folder, -33, -1, key, "new-final-token"), "Same season with different revision still waits for save");
            }
            writeChunk(folder, 0, 0, 2, true, key, "external-token", false);
            check(SavedChunkRevision.matches(folder, 0, 0, key, "external-token"), "External chunk files supported");
            writeChunk(folder, 2, 2, 2, false, key, "decoy-token", true);
            check(!SavedChunkRevision.matches(folder, 2, 2, key, "decoy-token"), "Unrelated compound cannot spoof chunk PDC proof");
            check(!SavedChunkRevision.matches(folder, 99, 99, key, "final-token"), "Missing chunk never counts as saved");
            try { SavedChunkRevision.purged(folder, 99, 99, key, "final-token"); throw new AssertionError("Missing region counts as purged"); }
            catch (IOException expected) { assertions++; }
            check(!SavedChunkRevision.purged(folder, -33, -1, key, "final-token"), "An unpurged saved token remains pending");
            check(SavedChunkRevision.purged(folder, 2, 2, key, "decoy-token"), "Readable chunk lacking old token proves purge");
            Path region = folder.resolve("region/r.-2.-1.mca");
            try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "rw")) {
                file.seek(8192); file.writeInt(Integer.MAX_VALUE);
            }
            check(!SavedChunkRevision.matches(folder, -33, -1, key, "final-token"), "Corrupt chunk length cannot release a request");
            try { SavedChunkRevision.purged(folder, -33, -1, key, "final-token"); throw new AssertionError("Corrupt region counts as purged"); }
            catch (IOException expected) { assertions++; }
        } finally {
            Path cleanupRoot=Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            if (!folder.toRealPath().startsWith(cleanupRoot) || folder.toRealPath().equals(cleanupRoot))
                throw new IOException("Test cleanup escaped its temporary directory");
            try (var paths = Files.walk(folder)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("BlueMap reliability tests passed: " + assertions + " assertions");
    }
    private static void regionCounts(){
        RenderLedger ledger=new RenderLedger();Map<ChunkKey,String> expected=new HashMap<>();
        UUID[] worlds={new UUID(1,2),new UUID(2,1)};Random random=new Random(736212);
        for(int step=0;step<12000;step++){
            ChunkKey key=new ChunkKey(worlds[random.nextInt(2)],random.nextInt(140)-70,random.nextInt(140)-70);
            String token="revision-"+step;
            switch(step%6){
                case 0 -> {ledger.restore(key,token,List.of("surface"));expected.put(key,token);}
                case 1 -> {ledger.dirty(key);expected.put(key,"");}
                case 2 -> {
                    boolean changed=ledger.complete(key,token);
                    check(changed=="".equals(expected.get(key)),"Only unfinished revisions complete during randomized tracking");
                    if(changed)expected.put(key,token);
                }
                case 3 -> {
                    String before=expected.getOrDefault(key,"absent");
                    check(ledger.replaceWithDeletion(new Revision(key,before),token,true),"Source deletion replaces or restores its current revision");
                    expected.put(key,token);
                }
                case 4 -> {
                    check(!ledger.acknowledge(new Revision(key,"stale"),"surface"),"Stale callbacks cannot change tracking");
                    check(!ledger.retire(key,List.of("surface","caves")),"Incomplete map receipts keep tracking");
                }
                default -> {
                    String current=expected.get(key);
                    if(current!=null&&!current.isEmpty()){
                        ledger.acknowledge(new Revision(key,current),"surface");
                        check(ledger.retire(key,List.of("surface")),"Fulfilled region member retires");expected.remove(key);
                    }
                }
            }
            check(ledger.regions()==expected.keySet().stream().map(ChunkKey::region).distinct().count(),"Region count matches distinct remaining chunks across worlds and negative coordinates");
        }
        for(var row:new ArrayList<>(expected.entrySet())){
            ChunkKey key=row.getKey();String token=row.getValue();if(token.isEmpty()){token="final";ledger.complete(key,token);}
            ledger.acknowledge(new Revision(key,token),"surface");check(ledger.retire(key,List.of("surface")),"Cleanup retires every remaining receipt");
        }
        check(ledger.regions()==0&&ledger.entries().isEmpty(),"No region survives the last member's retirement");
    }
    private static void writeChunk(Path folder, int x, int z, int compression, boolean external,
                                   String key, String token, boolean decoy) throws IOException {
        Files.createDirectories(folder.resolve("region"));
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream nbt = new DataOutputStream(raw)) {
            nbt.writeByte(10); nbt.writeUTF("");
            nbt.writeByte(9); nbt.writeUTF("sections"); nbt.writeByte(10); nbt.writeInt(1);
            nbt.writeByte(1); nbt.writeUTF("Y"); nbt.writeByte(-4);
            nbt.writeByte(12); nbt.writeUTF("sample"); nbt.writeInt(2); nbt.writeLong(17); nbt.writeLong(23); nbt.writeByte(0);
            nbt.writeByte(10); nbt.writeUTF(decoy ? "unrelated" : "ChunkBukkitValues");
            nbt.writeByte(8); nbt.writeUTF(key); nbt.writeUTF(token); nbt.writeByte(0); nbt.writeByte(0);
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        if (compression == 1) {
            try (GZIPOutputStream stream = new GZIPOutputStream(compressed)) { stream.write(raw.toByteArray()); }
        } else if (compression == 2) {
            try (DeflaterOutputStream stream = new DeflaterOutputStream(compressed)) { stream.write(raw.toByteArray()); }
        } else compressed.write(raw.toByteArray());
        byte[] encoded = compressed.toByteArray();
        if (external) Files.write(folder.resolve("region/c." + x + "." + z + ".mcc"), encoded);
        Path path = folder.resolve("region/r." + Math.floorDiv(x, 32) + "." + Math.floorDiv(z, 32) + ".mca");
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.setLength(12288);
            file.seek((Math.floorMod(x, 32) + Math.floorMod(z, 32) * 32) * 4L); file.writeInt((2 << 8) | 1);
            file.seek(8192); file.writeInt(external ? 1 : encoded.length + 1); file.writeByte(compression | (external ? 128 : 0));
            if (!external) file.write(encoded);
        }
    }
}
