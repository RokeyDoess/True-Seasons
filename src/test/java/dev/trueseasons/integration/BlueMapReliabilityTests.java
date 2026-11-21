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
        coordinateHashes();
        receiptSnapshots();
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
            bufferedProofs(folder);
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
    private static byte[] proofPrefix()throws IOException{
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeByte(10);out.writeUTF("");out.writeByte(10);out.writeUTF("ChunkBukkitValues");
        out.writeByte(8);out.writeUTF("trueseasons:bluemap_revision");out.writeUTF("buffered-final");out.writeByte(0);
        return bytes.toByteArray();
    }
    private static void proofPayload(Path folder,byte[] raw,int format)throws Exception{
        Files.createDirectories(folder.resolve("region"));var encoded=new ByteArrayOutputStream();
        if(format==3)encoded.write(raw);
        else try(OutputStream stream=format==1?new GZIPOutputStream(encoded):format==2?new DeflaterOutputStream(encoded)
                :(OutputStream)Class.forName("net.jpountz.lz4.LZ4BlockOutputStream").getConstructor(OutputStream.class).newInstance(encoded)){stream.write(raw);}
        byte[] payload=encoded.toByteArray();int sectors=(payload.length+5+4095)/4096;
        try(var file=new RandomAccessFile(folder.resolve("region/r.0.0.mca").toFile(),"rw")){
            file.setLength((2L+sectors)*4096);file.writeInt((2<<8)|sectors);file.seek(8192);
            file.writeInt(payload.length+1);file.writeByte(format);file.write(payload);
        }
    }
    private static void rejectedProof(Path folder,byte[] raw,String message)throws Exception{
        proofPayload(folder,raw,2);
        try{check(!SavedChunkRevision.matches(folder,0,0,"trueseasons:bluemap_revision","buffered-final"),message);}
        catch(IOException rejected){check(true,message);}
        try{SavedChunkRevision.purged(folder,0,0,"trueseasons:bluemap_revision","different");throw new AssertionError(message+" accepted as purge");}
        catch(IOException rejected){check(true,message+" cannot prove purge");}
    }
    private static void bufferedProofs(Path parent)throws Exception{
        Path folder=Files.createDirectory(parent.resolve("buffered"));
        var bytes=new ByteArrayOutputStream();bytes.write(proofPrefix());var out=new DataOutputStream(bytes);
        out.writeByte(9);out.writeUTF("ignored");out.writeByte(10);out.writeInt(1);
        out.writeByte(8);out.writeUTF("label");out.writeUTF("text\u0000é中\uD83D\uDE03".repeat(1000));
        out.writeByte(7);out.writeUTF("data");out.writeInt(32771);byte[] noise=new byte[32771];new Random(617283).nextBytes(noise);out.write(noise);
        out.writeByte(12);out.writeUTF("longs");out.writeInt(10001);for(int n=0;n<10001;n++)out.writeLong(n*17231L);
        out.writeByte(0);out.writeByte(0);byte[] valid=bytes.toByteArray();
        for(int format=1;format<=4;format++){
            proofPayload(folder,valid,format);byte[] original=Files.readAllBytes(folder.resolve("region/r.0.0.mca"));
            check(SavedChunkRevision.matches(folder,0,0,"trueseasons:bluemap_revision","buffered-final"),"Buffered proof validates complete large records with compression "+format);
            check(SavedChunkRevision.purged(folder,0,0,"trueseasons:bluemap_revision","different"),"Buffered proof distinguishes another revision with compression "+format);
            check(Arrays.equals(original,Files.readAllBytes(folder.resolve("region/r.0.0.mca"))),"Save proof leaves region bytes unchanged");
        }
        rejectedProof(folder,Arrays.copyOf(valid,valid.length-1),"A matching token cannot hide truncated NBT after a large ignored payload");
        for(boolean badKey:new boolean[]{false,true}){
            bytes.reset();bytes.write(proofPrefix());out=new DataOutputStream(bytes);
            out.writeByte(9);out.writeUTF("ignored");out.writeByte(10);out.writeInt(1);out.writeByte(8);
            if(badKey){out.writeShort(1);out.writeByte(0x80);out.writeUTF("value");}
            else{out.writeUTF("label");out.writeShort(1);out.writeByte(0x80);}
            out.writeByte(0);out.writeByte(0);
            rejectedProof(folder,bytes.toByteArray(),"Ignored malformed UTF remains unreadable after an otherwise matching token");
        }
        bytes.reset();bytes.write(proofPrefix());out=new DataOutputStream(bytes);
        for(int n=0;n<66;n++){out.writeByte(10);out.writeUTF("nested");}for(int n=0;n<67;n++)out.writeByte(0);
        rejectedProof(folder,bytes.toByteArray(),"Buffering retains the nesting limit");
        bytes.reset();bytes.write(proofPrefix());out=new DataOutputStream(bytes);out.writeByte(7);out.writeUTF("padding");
        int limit=16*1024*1024;int padding=limit-bytes.size()-4-1;
        out.writeInt(padding);out.write(new byte[padding]);out.writeByte(0);
        check(bytes.size()==limit,"Exact decompressed-limit fixture");proofPayload(folder,bytes.toByteArray(),2);
        check(SavedChunkRevision.matches(folder,0,0,"trueseasons:bluemap_revision","buffered-final"),"The exact read-limit boundary remains valid");
        bytes.reset();bytes.write(proofPrefix());out=new DataOutputStream(bytes);out.writeByte(7);out.writeUTF("padding");out.writeInt(limit);out.write(new byte[limit]);out.writeByte(0);
        rejectedProof(folder,bytes.toByteArray(),"A matching token cannot hide an oversized decompressed record");
        ignoredUtf();
    }
    private static void ignoredUtf()throws Exception{
        var skip=SavedChunkRevision.class.getDeclaredMethod("skipUtf",DataInputStream.class,byte[].class);skip.setAccessible(true);
        List<byte[]> fixtures=new ArrayList<>();
        for(String text:List.of("","ASCII","\u0000é中\uD83D\uDE03","a".repeat(20000)+"中")){
            var bytes=new ByteArrayOutputStream();new DataOutputStream(bytes).writeUTF(text);fixtures.add(bytes.toByteArray());
        }
        var random=new Random(7652361);
        for(int sample=0;sample<3000;sample++){
            int length=sample<256?1:random.nextInt(16);byte[] text=new byte[length];random.nextBytes(text);if(sample<256)text[0]=(byte)sample;
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeShort(length+(sample%7==0?1:0));out.write(text);fixtures.add(bytes.toByteArray());
        }
        for(byte[] bytes:fixtures){
            boolean expected;try(var input=new DataInputStream(new ByteArrayInputStream(bytes))){input.readUTF();expected=true;}catch(IOException invalid){expected=false;}
            for(int size:new int[]{5,8192}){
                boolean actual;try(var input=new DataInputStream(new ByteArrayInputStream(bytes))){skip.invoke(null,input,new byte[size]);actual=true;check(input.available()==0,"Ignored UTF consumes its exact payload");}
                catch(java.lang.reflect.InvocationTargetException invalid){if(!(invalid.getCause() instanceof IOException))throw invalid;actual=false;}
                check(actual==expected,"Ignored UTF accepts and rejects the same byte sequences as DataInputStream");
            }
        }
    }
    private static void receiptSnapshots(){
        var ledger=new RenderLedger();var key=new ChunkKey(new UUID(0,41),0,0);ledger.restore(key,"one",List.of());
        var revision=new Revision(key,"one");var empty=ledger.entries().get(key).acknowledged();
        check(ledger.acknowledge(revision,"surface"),"The first map receipt is recorded");var first=ledger.entries().get(key).acknowledged();
        check(ledger.acknowledge(revision,"caves"),"A second map receipt remains independent");var second=ledger.entries().get(key).acknowledged();
        check(ledger.acknowledge(revision,"overview")&&ledger.acknowledge(revision,"nether"),"Receipt storage grows beyond two maps without losing existing acknowledgements");
        check(empty.isEmpty()&&first.equals(Set.of("surface"))&&second.equals(Set.of("surface","caves")),"Previously returned receipt snapshots remain immutable as later maps finish");
        check(!ledger.acknowledge(revision,"surface")&&!ledger.needs(key.region(),"surface"),"Duplicate receipts do not inflate region acknowledgement counts");
        ledger.dirty(key);ledger.complete(key,"two");
        check(ledger.needs(new Revision(key,"two"),"surface")&&ledger.needs(key.region(),"surface"),"A newer chunk revision clears every old map receipt");
        ledger.restore(key,"three",List.of("surface","surface"));
        check(ledger.entries().get(key).acknowledged().equals(Set.of("surface"))&&!ledger.needs(key.region(),"surface"),"Recovered duplicate map names retain their original set semantics");
        ledger.restore(key,"four",Arrays.asList(null,"surface"));
        check(!ledger.needs(new Revision(key,"four"),null)&&!ledger.acknowledge(new Revision(key,"four"),null),"Legacy nullable ledger keys retain duplicate detection");
        check(ledger.retire(key,Arrays.asList(null,"surface")),"Ledger retirement retains nullable collection semantics");
    }
    private static void coordinateHashes(){
        UUID world=new UUID(1,2),copy=new UUID(1,2),other=new UUID(2,1);
        Set<Integer> hashes=new HashSet<>();Map<ChunkKey,Integer> checkpoints=new HashMap<>();
        for(int x=-224;x<224;x++)for(int z=-224;z<224;z++){
            var key=new ChunkKey(world,x,z);hashes.add(key.hashCode());checkpoints.put(key,x*448+z);
        }
        check(hashes.size()>checkpoints.size()*.99,"Dense pending map queues distribute chunk keys instead of creating coordinate hash bands");
        for(int x=-224;x<224;x++)for(int z=-224;z<224;z++)check(checkpoints.get(new ChunkKey(copy,x,z))==x*448+z,"Independent equal world and chunk keys preserve exact pending revisions");
        Set<Integer> regions=new HashSet<>();
        for(int x=-1000;x<1000;x++)regions.add(new Region(world,x,-31*x).hashCode());
        check(regions.size()>1990,"Sparse diagonal region queues also retain distributed hashes");
        for(int x:new int[]{Integer.MIN_VALUE,-1,0,1,Integer.MAX_VALUE})for(int z:new int[]{Integer.MIN_VALUE,-1,0,1,Integer.MAX_VALUE}){
            var key=new ChunkKey(world,x,z);check(key.equals(new ChunkKey(copy,x,z))&&key.hashCode()==new ChunkKey(copy,x,z).hashCode(),"Coordinate limits preserve world identity and equal-key hashes");
            check(!key.equals(new ChunkKey(other,x,z)),"Worlds with equal UUID hash codes keep separate revisions");
        }
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
            if(step%100==0)verifyRegionIndex(ledger);
        }
        for(var row:new ArrayList<>(expected.entrySet())){
            ChunkKey key=row.getKey();String token=row.getValue();if(token.isEmpty()){token="final";ledger.complete(key,token);}
            ledger.acknowledge(new Revision(key,token),"surface");check(ledger.retire(key,List.of("surface")),"Cleanup retires every remaining receipt");
        }
        check(ledger.regions()==0&&ledger.entries().isEmpty(),"No region survives the last member's retirement");
        verifyRegionIndex(ledger);
    }
    private static void verifyRegionIndex(RenderLedger ledger){
        Map<Region,List<ChunkKey>> expected=new LinkedHashMap<>();
        ledger.entries().keySet().forEach(key->expected.computeIfAbsent(key.region(),ignored->new ArrayList<>()).add(key));
        check(ledger.pendingRegions().equals(List.copyOf(expected.keySet())),"Region dispatch retains the oldest remaining global entry order after replacement and partial retirement");
        var probes=ledger.probes(ledger.entries().size());Set<ChunkKey> visited=new HashSet<>();
        for(var entry:probes)check(visited.add(entry.chunk())&&ledger.entries().get(entry.chunk())==entry,"Probe rotation visits each current entry once without retaining replaced or retired revisions");
        check(visited.equals(ledger.entries().keySet()),"A complete bounded probe cycle covers every pending world and region");
        for(var row:expected.entrySet()){
            check(ledger.members(row.getKey()).stream().map(RenderLedger.Entry::chunk).toList().equals(row.getValue()),"Indexed region membership contains exactly the current revisions in ledger order");
            for(String map:List.of("surface","caves","new-map")){
                boolean needed=row.getValue().stream().anyMatch(key->ledger.needs(new Revision(key,ledger.entries().get(key).token()),map));
                check(ledger.needs(row.getKey(),map)==needed,"Per-map region acknowledgement counts match every current chunk receipt");
                boolean retirable=row.getValue().stream().anyMatch(key->!ledger.entries().get(key).token().isEmpty()&&ledger.entries().get(key).acknowledged().contains(map));
                check(!retirable||ledger.mayRetire(row.getKey(),List.of(map)),"Region retirement filtering cannot hide an acknowledged final revision");
            }
        }
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
