package dev.trueseasons.visual;

import java.util.*;
import it.unimi.dsi.fastutil.longs.*;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/** Shared presentation-only tree selection. Samplers must return original blocks without loading terrain. */
public final class AutumnTreeColors {
    private AutumnTreeColors() {}

    public enum Species { OAK, BIRCH, JUNGLE, ACACIA, DARK_OAK, MANGROVE, CHERRY }
    public enum Appearance {
        ORIGINAL(null), YELLOW("minecraft:yellow_poplar_leaves"),
        ORANGE("minecraft:orange_poplar_leaves"), RED("minecraft:red_poplar_leaves");
        private final String blockKey;
        Appearance(String blockKey) { this.blockKey = blockKey; }
        public String blockKey() { return blockKey; }
    }
    public enum Kind {
        UNKNOWN(null, false), OTHER(null, false),
        OAK_LEAVES(Species.OAK, true), OAK_LOG(Species.OAK, false),
        BIRCH_LEAVES(Species.BIRCH, true), BIRCH_LOG(Species.BIRCH, false),
        JUNGLE_LEAVES(Species.JUNGLE, true), JUNGLE_LOG(Species.JUNGLE, false),
        ACACIA_LEAVES(Species.ACACIA, true), ACACIA_LOG(Species.ACACIA, false),
        DARK_OAK_LEAVES(Species.DARK_OAK, true), DARK_OAK_LOG(Species.DARK_OAK, false),
        MANGROVE_LEAVES(Species.MANGROVE, true), MANGROVE_LOG(Species.MANGROVE, false),
        CHERRY_LEAVES(Species.CHERRY, true), CHERRY_LOG(Species.CHERRY, false);
        private final Species species;
        private final boolean leaves;
        Kind(Species species, boolean leaves) { this.species = species; this.leaves = leaves; }
        public Species species() { return species; }
        public boolean leaves() { return leaves; }
        public boolean log() { return species != null && !leaves; }
    }
    @FunctionalInterface public interface Sampler {
        Kind kindAt(int x, int y, int z);
        /** Immutable identity of the original packet chunk, or null when unavailable. */
        default Object chunkRevision(int x,int z){return null;}
        /** Optional view-wide identity, replaced whenever original samples or availability change. */
        default Object revisionEpoch(){return null;}
        /** Vanilla persistent leaves are decorative, and need no trunk search. */
        default boolean persistentAt(int x, int y, int z) { return false; }
    }
    public static boolean isLeaf(Kind kind) { return kind != null && kind.leaves(); }
    public static boolean isLog(Kind kind) { return kind != null && kind.log(); }

    /** Exact vanilla species classification; fixed-color evergreen and precolored leaves are excluded. */
    public static Kind kind(String key) {
        if (key == null) return Kind.UNKNOWN;
        if (!key.startsWith("minecraft:")) return Kind.OTHER;
        String name = key.substring(10);
        boolean leaves = name.endsWith("_leaves");
        if (leaves) name = name.substring(0, name.length() - 7);
        else {
            if (name.startsWith("stripped_")) name = name.substring(9);
            if (name.endsWith("_log")) name = name.substring(0, name.length() - 4);
            else if (name.endsWith("_wood")) name = name.substring(0, name.length() - 5);
            else return Kind.OTHER;
        }
        return switch (name) {
            case "oak" -> leaves ? Kind.OAK_LEAVES : Kind.OAK_LOG;
            case "birch" -> leaves ? Kind.BIRCH_LEAVES : Kind.BIRCH_LOG;
            case "jungle" -> leaves ? Kind.JUNGLE_LEAVES : Kind.JUNGLE_LOG;
            case "acacia" -> leaves ? Kind.ACACIA_LEAVES : Kind.ACACIA_LOG;
            case "dark_oak" -> leaves ? Kind.DARK_OAK_LEAVES : Kind.DARK_OAK_LOG;
            case "mangrove" -> leaves ? Kind.MANGROVE_LEAVES : Kind.MANGROVE_LOG;
            case "cherry" -> leaves ? Kind.CHERRY_LEAVES : Kind.CHERRY_LOG;
            default -> Kind.OTHER;
        };
    }

    private record Position(int x, int y, int z) {}
    private static final int[][] SIDES = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private static final Comparator<Position> ANCHOR_ORDER = Comparator.comparingInt(Position::y)
            .thenComparingInt(Position::x).thenComparingInt(Position::z);
    public static final int MAX_LEAF_SEARCH = 1536;
    public static final int MAX_LOG_SEARCH = 4096;
    public static final int MAX_SEARCH_READS = 8192;
    private static final int SAMPLE_CACHE = 16384, LEAF_CACHE = 4096, LOG_CACHE = 8192;

    private record PlanKey(long seed,Position position) {
        @Override public int hashCode(){return ((Long.hashCode(seed)*31+position.x)*31+position.y)*31+position.z;}
        @Override public boolean equals(Object other){return other instanceof PlanKey key&&seed==key.seed&&position.x==key.position.x&&position.y==key.position.y&&position.z==key.position.z;}
    }
    private record Dependencies(long[] chunks,Object[] revisions) {}
    private record Anchor(Position position,Dependencies dependencies) {}
    private record Selection(Appearance color,int threshold){
        Appearance at(int coverage){return coverage>=threshold?color:Appearance.ORIGINAL;}
    }
    private record Plan(Selection selection,Dependencies dependencies) {}
    /** Bounded cross-view plans verified against original terrain dependencies. */
    public static final class SharedPlans {
        private final LinkedHashMap<PlanKey,Plan> entries=new LinkedHashMap<>(128,.75f,true);
        private int weight;
        private Plan get(PlanKey key,Session session){
            Plan plan;synchronized(this){plan=entries.get(key);}if(plan==null)return null;
            return session.valid(plan.dependencies)?plan:null;
        }
        private synchronized void put(PlanKey key,Plan plan){
            Plan old=entries.put(key,plan);if(old!=null)weight-=old.dependencies.chunks.length+1;
            weight+=plan.dependencies.chunks.length+1;
            while(entries.size()>32768||weight>262144){var it=entries.entrySet().iterator();weight-=it.next().getValue().dependencies.chunks.length+1;it.remove();}
        }
        public synchronized int size(){return entries.size();}
        public synchronized void clear(){entries.clear();weight=0;}
    }

    /** Use one session per immutable view/render batch; invalidate after original block availability changes. */
    public static final class Session {
        private static final int SHARED_WRITE_LIMIT=8192;
        private int sharedWrites;
        private final long worldSeed;
        private final Sampler sampler;
        private final SharedPlans shared;
        private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Object> dependencies=new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        private boolean shareable=true;
        private Dependencies frozenDependencies,lastDependencies,sharedDependencies;
        private final Map<Selection,Plan> sharedSelections=new IdentityHashMap<>();
        private final Set<Dependencies> validated=Collections.newSetFromMap(new IdentityHashMap<>());
        private Object validationEpoch;
        private boolean valid(Dependencies deps){
            Object epoch=sampler.revisionEpoch();
            if(epoch!=validationEpoch){validated.clear();validationEpoch=epoch;}
            if(epoch!=null&&validated.contains(deps))return true;
            for(int i=0;i<deps.chunks.length;i++){
                long key=deps.chunks[i];
                if(sampler.chunkRevision((int)key,(int)(key>>32))!=deps.revisions[i])return false;
            }
            if(epoch!=null){if(validated.size()>=256)validated.clear();validated.add(deps);}
            return true;
        }
        private void dependency(int x,int z){
            if(shared==null||!shareable)return;
            long key=((long)z<<32)|(x&0xffffffffL);
            if(dependencies.containsKey(key))return;
            Object token=sampler.chunkRevision(x,z);
            if(token==null||dependencies.size()>=128){shareable=false;return;}
            dependencies.put(key,token);
            frozenDependencies=null;
        }
        private Dependencies freezeDependencies(){
            if(frozenDependencies!=null)return frozenDependencies;
            Dependencies previous=lastDependencies;
            if(previous!=null&&previous.chunks.length==dependencies.size()){
                boolean same=true;
                for(int i=0;i<previous.chunks.length;i++)if(dependencies.get(previous.chunks[i])!=previous.revisions[i]){same=false;break;}
                if(same)return frozenDependencies=previous;
            }
            long[] chunks=new long[dependencies.size()];Object[] revisions=new Object[chunks.length];int i=0;
            for(var row:dependencies.long2ObjectEntrySet()){chunks[i]=row.getLongKey();revisions[i++]=row.getValue();}
            return lastDependencies=frozenDependencies=new Dependencies(chunks,revisions);
        }
        private void inherit(Dependencies previous){
            if(shared==null||!shareable)return;
            if(previous==null){shareable=false;return;}
            for(int i=0;i<previous.chunks.length;i++){
                long key=previous.chunks[i];if(dependencies.containsKey(key))continue;
                if(dependencies.size()>=128){shareable=false;return;}
                dependencies.put(key,previous.revisions[i]);frozenDependencies=null;
            }
        }
        private final TerrainSamples samples = new TerrainSamples();
        /** Bounded two-way native-coordinate cache; each set retains its most recent sample. */
        private static final class TerrainSamples {
            private long[] keys=new long[256];
            private Kind[] values=new Kind[256];
            private byte[] victims=new byte[128];
            private int size;
            private int bucket(long key){return ((int)it.unimi.dsi.fastutil.HashCommon.mix(key)&(victims.length-1));}
            Kind getAndMoveToLast(long key){
                int bucket=bucket(key),first=bucket<<1;
                Kind value=values[first];
                if(value!=null&&keys[first]==key){victims[bucket]=1;return value;}
                value=values[first+1];
                if(value!=null&&keys[first+1]==key){victims[bucket]=0;return value;}
                return null;
            }
            void putAndMoveToLast(long key,Kind value){
                if(keys.length<SAMPLE_CACHE&&size>=keys.length*3/4)grow();
                int bucket=bucket(key),first=bucket<<1;
                int offset=values[first]==null?0:values[first+1]==null?1:victims[bucket];
                int at=first+offset;if(values[at]==null)size++;
                keys[at]=key;values[at]=value;victims[bucket]=(byte)(offset^1);
            }
            private void grow(){
                long[] oldKeys=keys;Kind[] oldValues=values;
                keys=new long[oldKeys.length<<1];values=new Kind[keys.length];victims=new byte[keys.length>>1];size=0;
                for(int i=0;i<oldKeys.length;i++)if(oldValues[i]!=null)putAndMoveToLast(oldKeys[i],oldValues[i]);
            }
            int size(){return size;}
            void clear(){Arrays.fill(values,null);Arrays.fill(victims,(byte)0);size=0;}
        }
        private final Map<Position, Selection> colors = bounded(LEAF_CACHE);
        private final Map<Position,Selection> treeSelections=bounded(LOG_CACHE);
        // A null value marks a known connected log component that exceeds the search limit.
        // Unavailable terrain is never cached this way; it must remain retryable on arrival.
        private final Map<Position, Anchor> anchors = bounded(LOG_CACHE);
        // Leaf paths extend at most seven blocks from a query. Reuse that bounded
        // local volume rather than allocating a hash set and queues for every leaf.
        private int[] leafVisits;
        private int leafEpoch;
        private final IntArrayList firstLayer=new IntArrayList(),secondLayer=new IntArrayList(),nearbyLogs=new IntArrayList();
        private final LongArrayFIFOQueue logPending=new LongArrayFIFOQueue();
        private final LongArrayList logComponent=new LongArrayList();
        private final LongOpenHashSet logExamined=new LongOpenHashSet();
        private boolean incomplete;
        public Session(long worldSeed, Sampler sampler) {
            this(worldSeed,sampler,null);
        }
        public Session(long worldSeed,Sampler sampler,SharedPlans shared){
            this.worldSeed=worldSeed;this.sampler=Objects.requireNonNull(sampler);this.shared=shared;
        }
        public void invalidate() { samples.clear(); colors.clear();treeSelections.clear(); anchors.clear();dependencies.clear();frozenDependencies=null;validated.clear();sharedSelections.clear();lastDependencies=sharedDependencies=null;sharedWrites=0;shareable=true; }
        public Appearance appearanceAt(int x, int y, int z) {
            return appearanceAt(x,y,z,SeasonColorFrame.COMPLETE);
        }
        public Appearance appearanceAt(int x,int y,int z,int coverage){
            if(coverage<0||coverage>SeasonColorFrame.COMPLETE)throw new IllegalArgumentException("Invalid autumn coverage");
            incomplete=false;
            if(coverage==0)return Appearance.ORIGINAL;
            Position position = new Position(x,y,z);
            // Original block edits invalidate this session; completed selections need no resampling.
            Selection cached = colors.get(position);
            if (cached != null) return cached.at(coverage);
            // Completed trees depend only on samples used by this query, including cached trunks.
            // Bound publication from large scans while keeping shared lookups available.
            if(shared!=null){dependencies.clear();frozenDependencies=null;shareable=sharedWrites<SHARED_WRITE_LIMIT;}
            Kind source = sample(position);
            if (!source.leaves()) return Appearance.ORIGINAL;
            if (source.species() == Species.CHERRY&&coverage==SeasonColorFrame.COMPLETE) return Appearance.RED;
            if (sampler.persistentAt(x,y,z)) return coverage<128?Appearance.ORIGINAL:source.species()==Species.CHERRY?Appearance.RED:Appearance.ORANGE;
            PlanKey key=new PlanKey(worldSeed,position);
            Plan plan=shared==null?null:shared.get(key,this);
            if(plan!=null){colors.put(position,plan.selection);return plan.selection.at(coverage);}
            Selection selected=selection(position,source);
            if(shared!=null&&shareable&&!incomplete){
                freezeDependencies();
                if(sharedDependencies!=frozenDependencies){sharedSelections.clear();sharedDependencies=frozenDependencies;}

                Plan reusable=sharedSelections.get(selected);
                if(reusable==null){reusable=new Plan(selected,frozenDependencies);if(sharedSelections.size()>=LOG_CACHE)sharedSelections.clear();sharedSelections.put(selected,reusable);}
                shared.put(key,reusable);sharedWrites++;
            }
            return selected.at(coverage);
        }
            private Selection selection(Position position,Kind source){
            SearchBudget budget = new SearchBudget();
            Position anchor = nearestAnchor(position, source.species(), budget);
            // Incomplete/unavailable searches are deliberately not memoized as completed trees.
            if (anchor == null && budget.incomplete) { incomplete=true;return new Selection(source.species()==Species.CHERRY?Appearance.RED:Appearance.ORANGE,128); }
            Selection known=anchor==null?null:treeSelections.get(anchor);
            if(known!=null){colors.put(position,known);return known;}
            // Intersecting crowns can overwrite the leaf path back to the original tree.
            // Complete, trunkless foliage still participates, just like a planted hedge.
            Appearance color = source.species()==Species.CHERRY?Appearance.RED:anchor == null ? Appearance.ORANGE : color(anchor, source.species());
            int threshold=anchor==null?128:1+(int)Long.remainderUnsigned(mix(worldSeed^mix(anchor.x)^Long.rotateLeft(mix(anchor.z),17)^Long.rotateLeft(mix(anchor.y),39)^0x61c8864680b583ebL),SeasonColorFrame.COMPLETE);
            Selection selection=new Selection(color,threshold);colors.put(position,selection);if(anchor!=null)treeSelections.put(anchor,selection);
            return selection;
        }
        /** The last query could not resolve its tree using the currently available bounded samples. */
        public boolean incomplete(){return incomplete;}
        private Kind sample(Position position) {
            return sample(position.x,position.y,position.z);
        }
        private Kind sample(int x,int y,int z) {
            dependency(x>>4,z>>4);
            // Native block coordinates fit without loss. Bypass the cache outside
            // that range instead of allowing wrapped coordinates to alias a block.
            if(x < -33_554_432 || x > 33_554_431 || z < -33_554_432 || z > 33_554_431 || y < -2048 || y > 2047)
                return Objects.requireNonNull(sampler.kindAt(x,y,z));
            long key=((long)x&0x3ffffffL)<<38|((long)z&0x3ffffffL)<<12|((long)y&0xfffL);
            Kind cached = samples.getAndMoveToLast(key);
            if (cached != null) return cached;
            Kind kind = Objects.requireNonNull(sampler.kindAt(x,y,z));
            // UNKNOWN may become available without rebuilding this session.
            if (kind != Kind.UNKNOWN) {
                samples.putAndMoveToLast(key,kind);
            }
            return kind;
        }
        private Kind searchSample(Position position, SearchBudget budget) {
            return searchSample(position.x,position.y,position.z,budget);
        }
        private Kind searchSample(int x,int y,int z,SearchBudget budget) {
            Kind result = budget.remaining-- > 0 ? sample(x,y,z) : Kind.UNKNOWN;
            if (result == Kind.UNKNOWN) budget.incomplete = true;
            return result;
        }
        private Position nearestAnchor(Position start, Species species, SearchBudget budget) {
            if(leafVisits==null)leafVisits=new int[15*15*15];
            if(++leafEpoch==0){Arrays.fill(leafVisits,0);leafEpoch=1;}
            leafVisits[(7*15+7)*15+7]=leafEpoch;
            int visited=1;
            firstLayer.clear();secondLayer.clear();firstLayer.add((7*15+7)*15+7);
            IntArrayList layer=firstLayer,next=secondLayer;
            for (int distance = 1; distance <= 7; distance++) {
                next.clear();nearbyLogs.clear();
                boolean unavailable = false;
                for (int at=0;at<layer.size();at++) for (int[] side : SIDES) {
                    int position=layer.getInt(at);
                    int x=start.x+position/225-7+side[0],y=start.y+position/15%15-7+side[1],z=start.z+position%15-7+side[2];
                    int local=((x-start.x+7)*15+(y-start.y+7))*15+(z-start.z+7);
                    if(leafVisits[local]==leafEpoch)continue;
                    leafVisits[local]=leafEpoch;
                    if (++visited > MAX_LEAF_SEARCH) { budget.incomplete=true; return null; }
                    Kind kind = searchSample(x,y,z,budget);
                    if (kind == Kind.UNKNOWN) unavailable = true;
                    else if (kind.species() == species && kind.log()) nearbyLogs.add(local);
                    // A neighboring tree can replace part of a crown with another species.
                    // Traverse leaves of either species, but only matching logs anchor it.
                    else if (kind.leaves()) next.add(local);
                }
                if (unavailable) return null;
                if (!nearbyLogs.isEmpty()) {
                    Position best = null;
                    for (int at=0;at<nearbyLogs.size();at++) {
                        int log=nearbyLogs.getInt(at);
                        Position anchor = trunkAnchor(new Position(start.x+log/225-7,start.y+log/15%15-7,start.z+log%15-7), species, budget);
                        if (anchor == null) return null;
                        if (best == null || ANCHOR_ORDER.compare(anchor, best) < 0) best = anchor;
                    }
                    return best;
                }
                if (next.isEmpty()) return null;
                IntArrayList swap=layer;layer=next;next=swap;
            }
            return null;
        }
        private Position trunkAnchor(Position log, Species species, SearchBudget budget) {
            Anchor cached = anchors.get(log);
            if (cached != null) {inherit(cached.dependencies);return cached.position;}
            if (anchors.containsKey(log)) { budget.incomplete=true; return null; }
            logPending.clear();logComponent.clear();logExamined.clear();
            logPending.enqueue(0);logComponent.add(0);logExamined.add(0);
            Position lowest = log;
            while (!logPending.isEmpty()) {
                long current=logPending.dequeueLong();
                int cx=relative(current,42),cy=relative(current,21),cz=relative(current,0);
                int x=log.x+cx,y=log.y+cy,z=log.z+cz;
                if(y<lowest.y||y==lowest.y&&(x<lowest.x||x==lowest.x&&z<lowest.z))lowest=new Position(x,y,z);
                // Acacia and large-tree branches can connect diagonally.
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    long neighbor=relative(cx+dx,cy+dy,cz+dz);
                    if (!logExamined.add(neighbor)) continue;
                    if (logExamined.size() > MAX_LOG_SEARCH) {
                        for(int at=0;at<logComponent.size();at++){long point=logComponent.getLong(at);anchors.put(new Position(log.x+relative(point,42),log.y+relative(point,21),log.z+relative(point,0)),null);}
                        budget.incomplete=true;return null;
                    }
                    Kind kind = searchSample(x+dx,y+dy,z+dz, budget);
                    if (kind == Kind.UNKNOWN) return null;
                    if (kind.species() == species && kind.log()) {
                        logComponent.add(neighbor); logPending.enqueue(neighbor);
                    }
                }
            }
            Anchor resolved=new Anchor(lowest,shared!=null&&shareable?freezeDependencies():null);
            for(int at=0;at<logComponent.size();at++){long point=logComponent.getLong(at);anchors.put(new Position(log.x+relative(point,42),log.y+relative(point,21),log.z+relative(point,0)),resolved);}
            return lowest;
        }
        // Relative search coordinates remain within MAX_LOG_SEARCH steps, even
        // for samplers outside Minecraft's native packed-coordinate range.
        private static long relative(int x,int y,int z){return ((long)x&0x1fffffL)<<42|((long)y&0x1fffffL)<<21|((long)z&0x1fffffL);}
        private static int relative(long point,int shift){return (int)((point>>>shift)&0x1fffffL)<<11>>11;}
        private Appearance color(Position anchor, Species species) {
            long hash = worldSeed ^ mix(anchor.x) ^ Long.rotateLeft(mix(anchor.z), 21)
                    ^ Long.rotateLeft(mix(anchor.y), 42) ^ mix(species.ordinal() + 1L);
            return switch ((int) Long.remainderUnsigned(mix(hash), 3)) {
                case 0 -> Appearance.YELLOW;
                case 1 -> Appearance.ORANGE;
                default -> Appearance.RED;
            };
        }
    }
    private static final class SearchBudget { int remaining = MAX_SEARCH_READS; boolean incomplete; }
    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
    private static <V> Map<Position,V> bounded(int capacity) {
        return new LinkedHashMap<>(128, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Position,V> entry) { return size() > capacity; }
        };
    }
}
