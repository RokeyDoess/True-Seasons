package dev.trueseasons.integration;

import dev.trueseasons.integration.RenderLedger.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;

/** Source deletion, durable checkpoint recovery and final map receipts. */
public final class BlueMapRecoveryTests {
    private static int assertions;
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        check(!RenderLedger.sourceDeletionAllowed(false, false, false), "A fresh unsaved unloaded edit cannot become a deletion");
        check(!RenderLedger.sourceDeletionAllowed(false, false, true), "A fresh live edit cannot become a deletion");
        check(!RenderLedger.sourceDeletionAllowed(true, true, true), "Live chunks protect old and proven-saved requests");
        check(RenderLedger.sourceDeletionAllowed(true, false, false), "Recovered final intent can recover removed source");
        check(RenderLedger.sourceDeletionAllowed(false, true, false), "A previously proven saved source can later be trimmed");
        RenderLedger ledger = new RenderLedger(); ChunkKey key = new ChunkKey(UUID.randomUUID(), 64, -32);
        check(!ledger.requiresMarkerRecovery(key, null, false, false), "Untracked current chunks never replay for BlueMap");
        ledger.dirty(key);
        check(ledger.requiresMarkerRecovery(key, "old-winter-token", false, false), "A lost dirty winter token still requires actual finalization");
        Revision dirty = new Revision(key, "");
        check(ledger.replaceWithDeletion(dirty, "source-gone", false), "Recovered dirty empty-token intent can become a deletion");
        Revision absent = new Revision(key, "source-gone"); ledger.acknowledge(absent, "surface");
        check(!ledger.retire(key, List.of("surface", "caves")), "Source deletion still awaits every map receipt");
        ledger.acknowledge(absent, "caves"); check(ledger.retire(key, List.of("surface", "caves")), "Deleted source retires after all map cleanups");
        ledger.restore(key, "winter-final", List.of("surface"));
        check(!ledger.requiresMarkerRecovery(key, "winter-final", false, false), "Correct saved marker avoids world replay before background proof");
        check(ledger.requiresMarkerRecovery(key, null, false, false), "Final marker lost after checkpoint requests bounded recovery");
        check(!ledger.requiresMarkerRecovery(key, "older-disk-token", false, false, false), "Fresh owned final does not reopen world work from an old disk snapshot");
        check(ledger.requiresMarkerRecovery(key, "older-disk-token", false, false, true), "Restored mismatched final still needs bounded marker recovery");
        check(!ledger.requiresMarkerRecovery(key, "winter-final", false, false, true), "Matching restored marker avoids world replay before save-proof scan");
        check(!ledger.requiresMarkerRecovery(key, null, true, false), "Persisted save proof survives temporary marker purge");
        check(ledger.requiresMarkerRecovery(key, "winter-final", true, true), "A recreated deleted chunk needs a new revision");
        Revision winter = new Revision(key, "winter-final");
        check(ledger.replaceWithDeletion(winter, "deleted-final", false), "Trimming a saved source invalidates old map receipts");
        check(ledger.needs(new Revision(key, "deleted-final"), "surface"), "Previously successful map must remove its stale tiles");
        check(!ledger.acknowledge(winter, "caves"), "Old native completion cannot acknowledge new deleted state");
        ledger.dirty(key); ledger.complete(key, "autumn-final");
        check(!ledger.replaceWithDeletion(new Revision(key, "deleted-final"), "stale-delete", false), "A later Autumn edit supersedes an in-flight absence proof");
        check(!ledger.requiresMarkerRecovery(key, "autumn-final", false, false), "New saved Autumn marker hydrates migration without replay");
        ledger.dirty(key);
        check(ledger.requiresMarkerRecovery(key, "old-autumn-token", false, false, false), "Fresh incomplete dirty intent still requires world completion");

        Path testRoot = Path.of("build/test-recovery"); Files.createDirectories(testRoot);
        Path folder = Files.createTempDirectory(testRoot, "source-");
        try {
            recoverySymbols();sourceFolderBinding();sourceFolderEdits();recoveredRows(folder.resolve("queue"));
            try { SavedChunkRevision.sourceAbsent(folder, 0, 0); throw new AssertionError("Missing world directory is deletion proof"); }
            catch (IOException expected) { assertions++; }
            Files.createDirectory(folder.resolve("region"));
            check(SavedChunkRevision.regionAbsent(folder, 2, -1), "Known source directory proves a missing region");
            check(SavedChunkRevision.sourceAbsent(folder, 64, -32), "Missing source file is distinct from an unsaved-marker mismatch");
            Path region = folder.resolve("region/r.0.0.mca");
            try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "rw")) { file.setLength(8192); }
            check(!SavedChunkRevision.regionAbsent(folder, 0, 0), "A present region is not whole-region deletion proof");
            check(SavedChunkRevision.sourceAbsent(folder, 4, 7), "A stable zero location proves an individual missing chunk");
            try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "rw")) {
                file.setLength(12288); file.seek((7 * 32 + 4) * 4L); file.writeInt((2 << 8) | 1);
            }
            check(!SavedChunkRevision.sourceAbsent(folder, 4, 7), "A present chunk with an old token remains pending");
            // Exact readable payload, without padding to the end of its allocation.
            var bytes = new ByteArrayOutputStream(); var payload = new DataOutputStream(bytes);
            payload.writeByte(10); payload.writeUTF(""); payload.writeByte(10); payload.writeUTF("ChunkBukkitValues");
            payload.writeByte(8); payload.writeUTF("trueseasons:bluemap_revision"); payload.writeUTF("unpadded-final");
            payload.writeByte(0); payload.writeByte(0); byte[] nbt = bytes.toByteArray();
            try (RandomAccessFile file = new RandomAccessFile(region.toFile(),"rw")) {
                file.setLength(8192L+5+nbt.length); file.seek(8192); file.writeInt(nbt.length+1); file.writeByte(3); file.write(nbt);
            }
            check(!SavedChunkRevision.sourceAbsent(folder,4,7), "Unpadded final sector is valid present terrain");
            check(SavedChunkRevision.matches(folder,4,7,"trueseasons:bluemap_revision","unpadded-final"), "Exact saved final marker proves an unpadded payload");
            try (RandomAccessFile file = new RandomAccessFile(region.toFile(),"rw")) { file.setLength(file.length()-1); }
            check(!SavedChunkRevision.sourceAbsent(folder,4,7), "A damaged nonzero header is never mistaken for deletion");
            try { SavedChunkRevision.matches(folder,4,7,"trueseasons:bluemap_revision","unpadded-final"); throw new AssertionError("Truncated payload is saved proof"); }
            catch(IOException expected) { assertions++; }
            try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "rw")) { file.setLength(100); }
            try { SavedChunkRevision.sourceAbsent(folder, 4, 7); throw new AssertionError("Truncated save is deletion proof"); }
            catch (IOException expected) { assertions++; }
        } finally {
            try (var files = Files.walk(folder)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
        }
        System.out.println("BlueMap recovery tests passed: " + assertions + " assertions");
    }
    private static void sourceFolderBinding(){
        var ledger=new RenderLedger();var sources=ledger.folders();var restored=ledger.restored();
        UUID world=new UUID(7,12);Path target=Path.of("world/current"),equal=Path.of("world/current"),old=Path.of("world/previous");
        Path[] paths={null,equal,equal,old,old,null,target,Path.of("world/current"),null};
        var expected=new LinkedHashSet<ChunkKey>();var changed=new LinkedHashSet<ChunkKey>();
        ChunkKey unrelated=new ChunkKey(new UUID(7,13),0,0);sources.put(unrelated,old);
        for(int n=0;n<paths.length;n++){
            ChunkKey key=new ChunkKey(world,n,0);ledger.restore(key,"final-"+n,List.of("surface"));restored.add(key);
            if(paths[n]!=null)sources.put(key,paths[n]);
            if(!target.equals(paths[n]))expected.add(key);
        }
        Region region=new Region(world,0,0);
        check(!ledger.sourceCandidate(region),"Missing source folders cannot support a region absence proof");
        ledger.bindSourceFolder(region,target,sources,changed);
        check(changed.equals(expected),"Only missing or changed source paths require a checkpoint");
        check(ledger.sourceCandidate(region),"Binding missing folders updates tracked region readiness");
        for(int n=0;n<paths.length;n++){
            ChunkKey key=new ChunkKey(world,n,0);var entry=ledger.entries().get(key);
            check(sources.get(key).equals(target)&&entry.token().equals("final-"+n)&&entry.acknowledged().equals(Set.of("surface")),"Source binding preserves final revisions and map receipts");
            if(target.equals(paths[n]))check(sources.get(key)==paths[n],"Equal immutable source paths retain their existing identity");
        }
        check(sources.get(unrelated)==old,"Binding one region leaves unrelated sources untouched");
        changed.clear();ledger.bindSourceFolder(region,target,sources,changed);
        check(changed.isEmpty(),"Repeated world binding creates no extra checkpoint work");
        Path replacement=Path.of("world/replacement");ledger.bindSourceFolder(region,replacement,sources,changed);
        check(changed.size()==paths.length&&ledger.sourceCandidate(region),"A later source change updates every member without losing proof eligibility");
    }
    private static void sourceFolderEdits(){
        var ledger=new RenderLedger();var sources=ledger.folders();var random=new Random(32871);
        UUID world=new UUID(9,14);Path[] paths={Path.of("world/a"),Path.of("world/b"),Path.of("world/a")};
        for(int step=0;step<4096;step++){
            ChunkKey key=new ChunkKey(world,random.nextInt(96),random.nextInt(2));Path path=paths[random.nextInt(paths.length)];
            switch(random.nextInt(8)){
                case 0 -> ledger.restore(key,"final-"+step,List.of("surface"));
                case 1 -> sources.put(key,path);
                case 2 -> sources.remove(key);
                case 3 -> {var entry=sources.entrySet().stream().filter(row->row.getKey().equals(key)).findFirst();entry.ifPresent(row->row.setValue(path));}
                case 4 -> sources.keySet().remove(key);
                case 5 -> ledger.retire(key,List.of("surface"));
                case 6 -> {sources.put(key,path);ledger.restore(key,"restored-"+step,List.of("surface"));}
                case 7 -> {if(step%17==0)sources.clear();else ledger.dirty(key);}
            }
            if(step%13!=0)continue;
            Region region=new Region(world,random.nextInt(3),0);Path target=paths[random.nextInt(paths.length)];
            var before=new HashMap<>(sources);var expected=new HashSet<ChunkKey>();var changed=new HashSet<ChunkKey>();
            for(var entry:ledger.members(region))if(!target.equals(before.get(entry.chunk())))expected.add(entry.chunk());
            ledger.bindSourceFolder(region,target,sources,changed);
            check(changed.equals(expected),"Source binding remains exact through replacements, retirement and collection-view edits");
            for(var entry:ledger.members(region))before.put(entry.chunk(),target);
            check(sources.equals(before),"Binding affects exactly the current members of the selected region");
        }
        var external=new HashMap<ChunkKey,Path>();var changed=new HashSet<ChunkKey>();Region region=new Region(world,0,0);
        ledger.bindSourceFolder(region,paths[1],external,changed);
        check(changed.size()==ledger.members(region).size(),"Untracked source maps cannot reuse another map's folder summary");
    }
    private static void recoverySymbols()throws Exception{
        var symbols=new RenderRecoverySymbols();String id=new UUID(0,3).toString();
        ChunkKey first=symbols.key(id+"/-33/2147483647"),second=symbols.key(new String(id)+"/-2147483648/-1");
        check(first.world()==second.world()&&first.x()==-33&&second.x()==Integer.MIN_VALUE&&first.z()==Integer.MAX_VALUE,"Recovery shares immutable world identifiers without changing signed coordinates");
        check(symbols.key(id+"/+12/-0/ignored").equals(new ChunkKey(first.world(),12,0)),"Direct coordinate parsing preserves the existing signed and suffix behavior");
        UUID other=new UUID(9,11);
        for(int n=0;n<100;n++){
            UUID selected=n%3==0?first.world():other;
            check(symbols.key(selected+"/"+n+"/"+(-n)).equals(new ChunkKey(selected,n,-n)),"Repeated and alternating world prefixes cannot leak a previous world's identity");
        }
        String map=new String("surface");check(symbols.map(map)==symbols.map(new String("surface")),"Repeated map labels share one recovery symbol");
        check(!symbols.map("Surface").equals(symbols.map("surface")),"Case-sensitive map identifiers remain distinct");
        String path="world/../original";check(symbols.folder(path)==symbols.folder(new String(path))&&symbols.folder(path).equals(Path.of(path)),"Shared folders retain exact Path semantics without normalization");
        for(int n=0;n<2048;n++){
            UUID world=new UUID(1,n);var key=symbols.key(world+"/"+(-n)+"/"+n);
            check(key.equals(new ChunkKey(world,-n,n))&&symbols.map("map-"+n).equals("map-"+n)&&symbols.folder("folder-"+n).equals(Path.of("folder-"+n)),"Recovery remains exact beyond its sharing budget");
        }
        for(String field:List.of("worlds","maps","folders")){Field cache=RenderRecoverySymbols.class.getDeclaredField(field);cache.setAccessible(true);check(((Map<?,?>)cache.get(symbols)).size()<=256,"Recovery symbol tables remain bounded for many unique names");}
        var longSymbols=new RenderRecoverySymbols();String longName="x".repeat(2048);
        check(longSymbols.map(longName).equals(longName)&&longSymbols.folder(longName).equals(Path.of(longName)),"Oversized symbols remain fully supported without retaining them in recovery caches");
        for(String field:List.of("maps","folders")){Field cache=RenderRecoverySymbols.class.getDeclaredField(field);cache.setAccessible(true);check(((Map<?,?>)cache.get(longSymbols)).isEmpty(),"Recovery caches bound individual symbol size as well as entry count");}
        try{symbols.key("invalid-world/0/0");throw new AssertionError("Invalid world identifier cached");}catch(IllegalArgumentException expected){assertions++;}
    }
    private static Object allocate(Class<?> type)throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field field=unsafe.getDeclaredField("theUnsafe");field.setAccessible(true);
        return unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),type);
    }
    private static void set(Object target,String name,Object value)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static Object get(Object target,String name)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    @SuppressWarnings("unchecked") private static void recoveredRows(Path folder)throws Exception{
        var integration=(BlueMapIntegration)allocate(BlueMapIntegration.class);var ledger=new RenderLedger();
        set(integration,"ledger",ledger);set(integration,"readable",ledger.readable());set(integration,"deleted",ledger.deletions());set(integration,"folders",ledger.folders());set(integration,"restored",ledger.restored());
        Method factory=BlueMapIntegration.class.getDeclaredMethod("purgeIndex");factory.setAccessible(true);Object purges=factory.invoke(integration);
        set(integration,"trackedPurges",purges);set(integration,"purges",purges);Method bind=BlueMapIntegration.class.getDeclaredMethod("bindPurgeIndex");bind.setAccessible(true);bind.invoke(integration);
        Method restore=BlueMapIntegration.class.getDeclaredMethod("restoreJournal");restore.setAccessible(true);
        UUID world=new UUID(4,7);NamespacedRow[] expected=new NamespacedRow[64];
        try(var journal=new DurableRenderJournal(folder)){
            set(integration,"journal",journal);journal.put("__format",new byte[]{1},true);
            for(int n=0;n<expected.length;n++){
                var row=expected[n]=new NamespacedRow(new ChunkKey(world,n-32,-n),n%5!=0,n%7==0?"":"revision-"+n,n%3==0,n%2==0,n%4==0,n%6==0,n%8==0);
                journal.put(world+"/"+row.key.x()+"/"+row.key.z(),row.encode(),false);
            }
            journal.put("__format",new byte[]{1},true);restore.invoke(integration);
            var saved=(Map<ChunkKey,String>)get(integration,"readable");var folders=(Map<ChunkKey,Path>)get(integration,"folders");var deleted=(Set<ChunkKey>)get(integration,"deleted");
            for(var row:expected){
                var entry=ledger.entries().get(row.key);
                check((entry!=null)==row.entry,"Queue recovery retains exact pending-entry membership");
                if(entry!=null)check(entry.token().equals(row.token)&&entry.cleanup()==row.cleanup&&entry.acknowledged().equals(Set.of("surface","caves")),"Recovery preserves dirty/final tokens, cleanup flags and map receipts");
                check(saved.containsKey(row.key)==(row.entry&&row.saved)&&Objects.equals(saved.get(row.key),row.entry&&row.saved?row.token:null),"Recovery preserves saved-proof values independently of dirty/final state");
                check(((Map<?,?>)purges).containsKey(row.key)==row.purge&&folders.get(row.key).equals(Path.of("world/../original")),"Recovery preserves overlapping metadata purges and their source folder");
                check(deleted.contains(row.key)==(!row.legacy&&row.deleted),"Older rows without a deletion flag remain supported");
            }
            var dirty=new LinkedHashSet<>(ledger.entries().keySet());var required=new LinkedHashSet<>(dirty);
            set(integration,"checkpointDirty",dirty);set(integration,"durabilityRequired",required);
            set(integration,"finalDurability",new FinalRevisionDurability());
            var checkpoint=new ArrayList<ChunkKey>(dirty){
                @Override public boolean contains(Object value){throw new AssertionError("Bulk checkpoint repeatedly scans its revision list");}
            };
            Method persist=BlueMapIntegration.class.getDeclaredMethod("persist",Collection.class,boolean.class);persist.setAccessible(true);
            persist.invoke(integration,checkpoint,true);
            check(dirty.isEmpty()&&required.isEmpty(),"A forced bulk checkpoint clears exactly its written revisions without list membership scans");
            for(ChunkKey key:checkpoint)check(journal.state().containsKey(key.world()+"/"+key.x()+"/"+key.z()),"Every checkpoint revision remains durable after bookkeeping is cleared");
            byte[] invalid;
            try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){out.writeBoolean(true);out.writeUTF("final");out.writeBoolean(false);out.writeBoolean(false);out.writeInt(10_001);out.flush();invalid=bytes.toByteArray();}
            journal.put(world+"/99/99",invalid,true);
            try{restore.invoke(integration);throw new AssertionError("Invalid receipt count accepted");}catch(InvocationTargetException failure){check(failure.getCause() instanceof IOException,"Oversized receipt counts remain rejected before list allocation");}
            check(Arrays.equals(journal.state().get(world+"/99/99"),invalid),"A decoding failure leaves the durable source intact for recovery");
        }
    }
    private record NamespacedRow(ChunkKey key,boolean entry,String token,boolean cleanup,boolean saved,boolean purge,boolean deleted,boolean legacy){
        byte[] encode()throws IOException{
            var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){
                out.writeBoolean(entry);if(entry){out.writeUTF(token);out.writeBoolean(cleanup);out.writeBoolean(saved);out.writeInt(2);out.writeUTF("surface");out.writeUTF("caves");}
                out.writeBoolean(purge);if(purge){out.writeUTF("purge-"+key.x());out.writeBoolean(cleanup);}
                out.writeUTF("world/../original");if(!legacy)out.writeBoolean(deleted);
            }return bytes.toByteArray();
        }
    }
}
