package dev.trueseasons.integration;

import dev.trueseasons.state.TrackedCollections;
import java.util.*;
import java.util.function.Predicate;
import java.nio.file.Path;

/** Main-thread ledger; renderer callbacks never mutate durable state. */
public final class RenderLedger {
    public record ChunkKey(UUID world, int x, int z) {
        public Region region() { return new Region(world, Math.floorDiv(x, 32), Math.floorDiv(z, 32)); }
        @Override public int hashCode(){return coordinateHash(world,x,z);}
    }
    public record Region(UUID world, int x, int z) {
        @Override public int hashCode(){return coordinateHash(world,x,z);}
    }
    /** Dense grids must not collapse into the record's default coordinate diagonals. */
    private static int coordinateHash(UUID world,int x,int z){
        long value=((long)x<<32)|(z&0xffffffffL);
        if(world!=null)value^=world.getMostSignificantBits()^Long.rotateLeft(world.getLeastSignificantBits(),17);
        value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;
        value=(value^(value>>>27))*0x94d049bb133111ebL;
        value^=value>>>31;return (int)(value^(value>>>32));
    }
    public record Revision(ChunkKey chunk, String token) {}
    public static final class Entry {
        private String token;
        private Set<String> acknowledged;
        private boolean cleanup;
        private ChunkKey chunk;
        private Group group;
        private Entry previous,next;
        private long order;
        private boolean sourceEligible;
        Entry(String token, Collection<String> acknowledged) {
            this.token = token;
            if(acknowledged.isEmpty())this.acknowledged=Set.of();
            else if(acknowledged.size()<=2){
                var iterator=acknowledged.iterator();String first=iterator.next(),second=iterator.hasNext()?iterator.next():first;
                this.acknowledged=first==null||second==null?new HashSet<>(acknowledged)
                    :first.equals(second)?Set.of(first):Set.of(first,second);
            }else this.acknowledged=new HashSet<>(acknowledged);
        }
        private boolean acknowledged(String map){return map==null?acknowledged instanceof HashSet&&acknowledged.contains(null):acknowledged.contains(map);}
        private boolean acknowledge(String map){
            if(acknowledged instanceof HashSet)return acknowledged.add(map);
            if(map!=null&&acknowledged.contains(map))return false;
            if(map==null||acknowledged.size()==2){acknowledged=new HashSet<>(acknowledged);return acknowledged.add(map);}
            acknowledged=acknowledged.isEmpty()?Set.of(map):Set.of(acknowledged.iterator().next(),map);return true;
        }
        public String token() { return token; }
        public Set<String> acknowledged() { return Set.copyOf(acknowledged); }
        public boolean cleanup() { return cleanup; }
        ChunkKey chunk(){return chunk;}
    }
    private static final class Group {
        final Region region;
        // Reuse ledger entries instead of retaining a second keyed table for every region.
        Entry first,last;
        int size,version;
        final Collection<Entry> view=new AbstractCollection<>(){
            @Override public int size(){return size;}
            @Override public Iterator<Entry> iterator(){
                return new Iterator<>(){
                    Entry next=first;final int expected=version;
                    @Override public boolean hasNext(){if(expected!=version)throw new ConcurrentModificationException();return next!=null;}
                    @Override public Entry next(){if(!hasNext())throw new NoSuchElementException();Entry value=next;next=value.next;return value;}
                };
            }
        };
        final Map<String,Integer> acknowledged=new HashMap<>();
        int finals,saved,deleted,savedDeleted,cleanupPending,cleanupOverlap,sourceEligible;
        int sourcePaths;
        Path sourceFolder;
        boolean mixedSources;
        Group(Region region){this.region=region;}
        void addSource(Path folder){
            if(folder==null)return;
            if(sourcePaths++==0){sourceFolder=folder;mixedSources=false;}
            else if(!mixedSources&&!Objects.equals(sourceFolder,folder)){mixedSources=true;sourceFolder=null;}
        }
        void removeSource(Path folder){
            if(folder!=null&&--sourcePaths==0){sourceFolder=null;mixedSources=false;}
        }
        void add(Entry entry){if(!entry.token.isEmpty()){finals++;if(!entry.acknowledged.isEmpty())entry.acknowledged.forEach(map->acknowledged.merge(map,1,Integer::sum));}}
        void remove(Entry entry){if(!entry.token.isEmpty()){finals--;if(!entry.acknowledged.isEmpty())entry.acknowledged.forEach(map->acknowledged.compute(map,(key,count)->count==1?null:count-1));}}
    }
    private final Map<ChunkKey, Entry> entries = new LinkedHashMap<>();
    private final Map<ChunkKey,Entry> entryView=Collections.unmodifiableMap(entries);
    private final Map<Region,Group> regionMembers = new HashMap<>();
    private final NavigableMap<Long,Region> regionOrder=new TreeMap<>();
    private Map<ChunkKey,String> readProofs;
    private Set<ChunkKey> deletedProofs;
    private Map<ChunkKey,Path> sourceFolders;
    private Set<ChunkKey> restoredProofs;
    private Entry probeCursor;
    private Predicate<ChunkKey> cleanupPurges=key->false;
    private long sequence;
    Map<ChunkKey,String> readable(){
        if(readProofs==null)readProofs=TrackedCollections.map(new HashMap<ChunkKey,String>(),this::proofChanged,()->true);
        return readProofs;
    }
    Set<ChunkKey> deletions(){
        if(deletedProofs==null)deletedProofs=TrackedCollections.set(new HashSet<ChunkKey>(),this::deletionChanged,()->true);
        return deletedProofs;
    }
    Map<ChunkKey,Path> folders(){
        if(sourceFolders==null)sourceFolders=TrackedCollections.map(new HashMap<ChunkKey,Path>(),this::folderChanged,()->true);
        return sourceFolders;
    }
    Set<ChunkKey> restored(){
        if(restoredProofs==null)restoredProofs=TrackedCollections.set(new HashSet<ChunkKey>(),this::sourceChanged,()->true);
        return restoredProofs;
    }
    boolean tracksSources(Map<ChunkKey,Path> folders,Set<ChunkKey> restored,Map<ChunkKey,String> readable,Set<ChunkKey> deleted){
        return sourceFolders!=null&&sourceFolders==folders&&restoredProofs!=null&&restoredProofs==restored&&tracksReadable(readable)&&tracksDeletions(deleted);
    }
    boolean sourceCandidate(Region region){Group group=regionMembers.get(region);return group!=null&&group.size>group.deleted&&group.sourceEligible==group.size-group.deleted;}
    private void folderChanged(ChunkKey key,Path before,Path after){
        Entry entry=entries.get(key);
        if(entry!=null){entry.group.removeSource(before);entry.group.addSource(after);refreshSource(entry);}
    }
    private void sourceChanged(ChunkKey key){Entry entry=entries.get(key);if(entry!=null)refreshSource(entry);}
    private void refreshSource(Entry entry){
        boolean eligible=sourceFolders!=null&&sourceFolders.containsKey(entry.chunk)&&(deletedProofs==null||!deletedProofs.contains(entry.chunk))
            &&(restoredProofs!=null&&restoredProofs.contains(entry.chunk)||readProofs!=null&&entry.token.equals(readProofs.get(entry.chunk)));
        if(eligible!=entry.sourceEligible){entry.group.sourceEligible+=eligible?1:-1;entry.sourceEligible=eligible;}
    }
    boolean tracksReadable(Map<ChunkKey,String> values){return readProofs!=null&&readProofs==values;}
    boolean tracksDeletions(Set<ChunkKey> values){return deletedProofs!=null&&deletedProofs==values;}
    boolean saved(Region region){Group group=regionMembers.get(region);return group!=null&&group.saved==group.size;}
    boolean deleted(Region region){Group group=regionMembers.get(region);return group!=null&&group.deleted==group.size;}
    private boolean proven(Entry entry){return readProofs!=null&&!entry.token.isEmpty()&&entry.token.equals(readProofs.get(entry.chunk));}
    private void proofChanged(ChunkKey key,String before,String after){
        Entry entry=entries.get(key);if(entry==null)return;
        int change=(entry.token.equals(after)?1:0)-(entry.token.equals(before)?1:0);
        if(!entry.token.isEmpty()){entry.group.saved+=change;if(deletedProofs!=null&&deletedProofs.contains(key))entry.group.savedDeleted+=change;}
        if(entry.cleanup){entry.group.cleanupPending-=change;if(cleanupPurges.test(key))entry.group.cleanupOverlap-=change;}
        refreshSource(entry);
    }
    private void deletionChanged(ChunkKey key){
        Entry entry=entries.get(key);if(entry==null)return;int change=deletedProofs.contains(key)?1:-1;
        entry.group.deleted+=change;if(proven(entry))entry.group.savedDeleted+=change;
        refreshSource(entry);
    }
    private void proofContribution(Entry entry,int direction){
        boolean matches=readProofs!=null&&entry.token.equals(readProofs.get(entry.chunk)),saved=!entry.token.isEmpty()&&matches,deleted=deletedProofs!=null&&deletedProofs.contains(entry.chunk);
        if(saved)entry.group.saved+=direction;if(deleted)entry.group.deleted+=direction;if(saved&&deleted)entry.group.savedDeleted+=direction;
        if(entry.cleanup&&!matches){entry.group.cleanupPending+=direction;if(cleanupPurges.test(entry.chunk))entry.group.cleanupOverlap+=direction;}
        if(direction>0)refreshSource(entry);
        else if(entry.sourceEligible){entry.group.sourceEligible--;entry.sourceEligible=false;}
    }
    void cleanupPurges(Predicate<ChunkKey> source){
        cleanupPurges=Objects.requireNonNull(source);
        for(Group group:regionMembers.values()){
            group.cleanupOverlap=0;
            for(Entry entry:group.view)if(entry.cleanup&&!entry.token.equals(readProofs==null?null:readProofs.get(entry.chunk))&&source.test(entry.chunk))group.cleanupOverlap++;
        }
    }
    void purgeChanged(ChunkKey key,boolean before,boolean after){
        Entry entry=entries.get(key);if(entry!=null&&entry.cleanup&&!entry.token.equals(readProofs==null?null:readProofs.get(key)))entry.group.cleanupOverlap+=(after?1:0)-(before?1:0);
    }
    int cleanupPending(){int count=0;for(Group group:regionMembers.values())count+=group.cleanupPending-group.cleanupOverlap;return count;}
    record Counts(int transitions,int awaitingSave,int ready,int sourceDeletions,int unmapped){}
    Counts counts(Set<UUID> configured){
        int transitions=0,awaitingSave=0,ready=0,deletions=0,unmapped=0;
        for(Group group:regionMembers.values()){
            transitions+=group.size-group.finals;awaitingSave+=group.finals-group.saved;ready+=group.saved-group.savedDeleted;deletions+=group.savedDeleted;
            if(!configured.contains(group.region.world()))unmapped+=group.size;
        }
        return new Counts(transitions,awaitingSave,ready,deletions,unmapped);
    }
    Set<UUID> worlds(){Set<UUID> result=new LinkedHashSet<>();for(Group group:regionMembers.values())result.add(group.region.world());return result;}
    private void put(ChunkKey chunk,Entry entry){
        Entry old=entries.put(chunk,entry);
        entry.chunk=old==null?chunk:old.chunk;entry.order=old==null?++sequence:old.order;
        Group group=old==null?regionMembers.computeIfAbsent(chunk.region(),Group::new):old.group;
        entry.group=group;
        if(old==null){
            entry.previous=group.last;
            if(group.last==null){group.first=entry;regionOrder.put(entry.order,group.region);}else group.last.next=entry;
            group.last=entry;group.size++;
            if(sourceFolders!=null)group.addSource(sourceFolders.get(chunk));
        }else{
            group.remove(old);proofContribution(old,-1);entry.previous=old.previous;entry.next=old.next;
            if(entry.previous==null)group.first=entry;else entry.previous.next=entry;
            if(entry.next==null)group.last=entry;else entry.next.previous=entry;
            if(probeCursor==old)probeCursor=entry;
            old.group=null;old.previous=null;old.next=null;
        }
        group.version++;group.add(entry);proofContribution(entry,1);
        if(probeCursor==null)probeCursor=entry;
    }
    public Map<ChunkKey, Entry> entries() { return entryView; }
    /** Same order as first occurrence in the global ledger, including partial retirement. */
    List<Region> pendingRegions(){return List.copyOf(regionOrder.values());}
    Collection<Entry> members(Region region){Group group=regionMembers.get(region);return group==null?List.of():group.view;}
    void bindSourceFolder(Region region,Path folder,Map<ChunkKey,Path> sources,Set<ChunkKey> changed){
        Group group=regionMembers.get(region);if(group==null)return;
        boolean tracked=sources==sourceFolders;
        if(tracked&&group.sourcePaths==group.size&&!group.mixedSources&&folder.equals(group.sourceFolder))return;
        Path previous=null;boolean matches=false;
        for(var entry:group.view){
            Path source=sources.get(entry.chunk());
            // Restored entries share immutable paths; compare a run only once.
            if(source!=previous){matches=folder.equals(source);previous=source;}
            if(!matches){sources.put(entry.chunk(),folder);changed.add(entry.chunk());}
        }
        if(tracked){group.sourcePaths=group.size;group.sourceFolder=folder;group.mixedSources=false;}
    }
    boolean needs(Region region,String map){Group group=regionMembers.get(region);return group!=null&&group.finals>group.acknowledged.getOrDefault(map,0);}
    boolean mayRetire(Region region,Collection<String> maps){
        Group group=regionMembers.get(region);if(group==null||group.finals==0||maps.isEmpty())return false;
        for(String map:maps)if(group.acknowledged.getOrDefault(map,0)==0)return false;return true;
    }
    /** One bounded, allocation-light cycle across current region members. */
    List<Entry> probes(int maximum){
        if(maximum<0)throw new IllegalArgumentException("Negative probe count");
        int count=Math.min(maximum,entries.size());var result=new ArrayList<Entry>(count);
        for(int n=0;n<count;n++){Entry entry=probeCursor;result.add(entry);probeCursor=nextProbe(entry);}
        return result;
    }
    private Entry nextProbe(Entry entry){
        if(entry.next!=null)return entry.next;
        var next=regionOrder.higherEntry(entry.group.first.order);if(next==null)next=regionOrder.firstEntry();
        return regionMembers.get(next.getValue()).first;
    }
    public void restore(ChunkKey chunk, String token, Collection<String> acknowledged) {
        put(chunk, new Entry(token, acknowledged));
    }
    public void cleanup(ChunkKey chunk, boolean value) {
        Entry entry=entries.get(chunk);
        if(entry.cleanup!=value&&!entry.token.equals(readProofs==null?null:readProofs.get(chunk))){int change=value?1:-1;entry.group.cleanupPending+=change;if(cleanupPurges.test(chunk))entry.group.cleanupOverlap+=change;}
        entry.cleanup=value;
    }
    public boolean dirty(ChunkKey chunk) {
        Entry entry = entries.get(chunk);
        if (entry != null && entry.token.isEmpty()) return false;
        put(chunk, new Entry("", List.of())); return true;
    }
    public boolean complete(ChunkKey chunk, String token) {
        Entry entry = entries.get(chunk);
        if (entry == null || !entry.token.isEmpty()) return false;
        entry.group.remove(entry);proofContribution(entry,-1);entry.token = token;entry.group.add(entry);proofContribution(entry,1);return true;
    }
    public boolean matches(Revision revision) {
        Entry entry = entries.get(revision.chunk());
        return entry != null && !entry.token.isEmpty() && entry.token.equals(revision.token());
    }
    public boolean unchanged(Revision revision) {
        Entry entry = entries.get(revision.chunk());
        return entry != null && entry.token.equals(revision.token());
    }
    public boolean replaceWithDeletion(Revision previous, String token, boolean cleanup) {
        if (entries.containsKey(previous.chunk()) && !unchanged(previous)) return false;
        Entry entry = new Entry(token, List.of()); entry.cleanup = cleanup; put(previous.chunk(), entry); return true;
    }
    public boolean requiresMarkerRecovery(ChunkKey chunk, String marker, boolean saved, boolean deleted) {
        return requiresMarkerRecovery(chunk, marker, saved, deleted, true);
    }
    public boolean requiresMarkerRecovery(ChunkKey chunk, String marker, boolean saved, boolean deleted, boolean restored) {
        Entry entry = entries.get(chunk);
        return entry != null && (deleted || entry.token.isEmpty() || (restored && !saved && !entry.token.equals(marker)));
    }
    public static boolean sourceDeletionAllowed(boolean restored, boolean provenSaved, boolean loaded) {
        return !loaded && (restored || provenSaved);
    }
    public boolean needs(Revision revision, String map) {
        return matches(revision) && !entries.get(revision.chunk()).acknowledged(map);
    }
    public boolean acknowledge(Revision revision, String map) {
        if(!matches(revision))return false;
        Entry entry=entries.get(revision.chunk());if(!entry.acknowledge(map))return false;
        entry.group.acknowledged.merge(map,1,Integer::sum);return true;
    }
    public boolean retire(ChunkKey chunk, Collection<String> maps) {
        Entry entry = entries.get(chunk);
        if (entry == null || entry.token.isEmpty() || maps.isEmpty()) return false;
        for(String map:maps)if(!entry.acknowledged(map))return false;
        entries.remove(chunk);
        if(sourceFolders!=null)entry.group.removeSource(sourceFolders.get(chunk));
        Group group=entry.group;boolean first=entry.previous==null;group.remove(entry);proofContribution(entry,-1);
        if(probeCursor==entry){probeCursor=nextProbe(entry);if(probeCursor==entry)probeCursor=null;}
        if(first)group.first=entry.next;else entry.previous.next=entry.next;
        if(entry.next==null)group.last=entry.previous;else entry.next.previous=entry.previous;
        if(first){regionOrder.remove(entry.order);if(group.first!=null)regionOrder.put(group.first.order,group.region);}
        group.size--;group.version++;entry.group=null;entry.previous=null;entry.next=null;
        if(group.size==0)regionMembers.remove(group.region);
        return true;
    }
    public int regions() { return regionMembers.size(); }
}
