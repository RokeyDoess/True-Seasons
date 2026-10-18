package dev.trueseasons.visual;

import java.util.*;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

/** Shared presentation-only tree selection. Samplers must return original blocks without loading terrain. */
public final class AutumnTreeColors {
    private AutumnTreeColors() {}

    public enum Species { OAK, BIRCH, JUNGLE, ACACIA, DARK_OAK, MANGROVE, CHERRY }
    public enum Appearance {
        ORIGINAL(null), YELLOW("minecraft:yellow_poplar_leaves"),
        ORANGE("minecraft:orange_poplar_leaves"), RED("minecraft:red_poplar_leaves"), BLOSSOM("minecraft:flowering_azalea_leaves");
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
        /** Vanilla persistent leaves are decorative, and need no trunk search. */
        default boolean persistentAt(int x, int y, int z) { return false; }
    }
    public static boolean isLeaf(Kind kind) { return kind != null && kind.leaves(); }
    public static boolean isLog(Kind kind) { return kind != null && kind.log(); }
    public static boolean springEligible(Kind kind){return kind.leaves()&&(kind.species()==Species.OAK||kind.species()==Species.BIRCH||kind.species()==Species.DARK_OAK);}

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

    private record Position(int x, int y, int z) {
        Position offset(int dx, int dy, int dz) { return new Position(x + dx, y + dy, z + dz); }
    }
    private static final int[][] SIDES = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private static final Comparator<Position> ANCHOR_ORDER = Comparator.comparingInt(Position::y)
            .thenComparingInt(Position::x).thenComparingInt(Position::z);
    public static final int MAX_LEAF_SEARCH = 1536;
    public static final int MAX_LOG_SEARCH = 4096;
    public static final int MAX_SEARCH_READS = 8192;
    private static final int SAMPLE_CACHE = 16384, LEAF_CACHE = 4096, LOG_CACHE = 8192;

    /** Use one session per immutable view/render batch; invalidate after original block availability changes. */
    public static final class Session {
        private final long worldSeed;
        private final Sampler sampler;
        private final SpringBlossoms spring;
        private final Long2ObjectLinkedOpenHashMap<Kind> samples = new Long2ObjectLinkedOpenHashMap<>(128,0.5f);
        private record Selection(Appearance color,int threshold,boolean blossoms){
            Appearance at(int coverage){return coverage>=threshold?color:Appearance.ORIGINAL;}
        }
        private final Map<Position, Selection> colors = bounded(LEAF_CACHE);
        // A null value marks a known connected log component that exceeds the search limit.
        // Unavailable terrain is never cached this way; it must remain retryable on arrival.
        private final Map<Position, Position> anchors = bounded(LOG_CACHE);
        // Leaf paths extend at most seven blocks from a query. Reuse that bounded
        // local volume rather than allocating a hash set and queues for every leaf.
        private int[] leafVisits;
        private int leafEpoch;
        private final ArrayList<Position> firstLayer=new ArrayList<>(),secondLayer=new ArrayList<>(),nearbyLogs=new ArrayList<>();
        private boolean incomplete;
        public Session(long worldSeed, Sampler sampler) {
            this(worldSeed,sampler,SpringBlossoms.DEFAULT);
        }
        public Session(long worldSeed,Sampler sampler,SpringBlossoms spring){
            this.worldSeed = worldSeed; this.sampler = Objects.requireNonNull(sampler);this.spring=Objects.requireNonNull(spring);
        }
        public void invalidate() { samples.clear(); colors.clear(); anchors.clear(); }
        public Appearance appearanceAt(int x, int y, int z) {
            return appearanceAt(x,y,z,SeasonColorFrame.COMPLETE);
        }
        public Appearance appearanceAt(int x,int y,int z,int coverage){
            if(coverage<0||coverage>SeasonColorFrame.COMPLETE)throw new IllegalArgumentException("Invalid autumn coverage");
            incomplete=false;
            if(coverage==0)return Appearance.ORIGINAL;
            Position position = new Position(x,y,z);
            Kind source = sample(position);
            if (!source.leaves()) return Appearance.ORIGINAL;
            if (source.species() == Species.CHERRY&&coverage==SeasonColorFrame.COMPLETE) return Appearance.RED;
            if (sampler.persistentAt(x,y,z)) return coverage<128?Appearance.ORIGINAL:source.species()==Species.CHERRY?Appearance.RED:Appearance.ORANGE;
            Selection cached = colors.get(position);
            if (cached != null) return cached.at(coverage);
            return selection(position,source).at(coverage);
        }
        public Appearance springAppearanceAt(int x,int y,int z,int coverage){
            if(coverage<0||coverage>SeasonColorFrame.COMPLETE)throw new IllegalArgumentException("Invalid spring coverage");
            incomplete=false;
            // Ordinary positions need no tree traversal or sampled block cache.
            if(coverage==0||spring.treesPercent()==0||!spring.leaf(worldSeed,x,y,z))return Appearance.ORIGINAL;
            Position position=new Position(x,y,z);Kind source=sample(position);
            if(!springEligible(source)||sampler.persistentAt(x,y,z))return Appearance.ORIGINAL;
            Selection selected=colors.get(position);if(selected==null)selected=selection(position,source);
            return selected.blossoms&&coverage>=selected.threshold?Appearance.BLOSSOM:Appearance.ORIGINAL;
        }
        private Selection selection(Position position,Kind source){
            SearchBudget budget = new SearchBudget();
            Position anchor = nearestAnchor(position, source.species(), budget);
            // Incomplete/unavailable searches are deliberately not memoized as completed trees.
            if (anchor == null && budget.incomplete) { incomplete=true;return new Selection(source.species()==Species.CHERRY?Appearance.RED:Appearance.ORANGE,128,false); }
            // Intersecting crowns can overwrite the leaf path back to the original tree.
            // Complete, trunkless foliage still participates, just like a planted hedge.
            Appearance color = source.species()==Species.CHERRY?Appearance.RED:anchor == null ? Appearance.ORANGE : color(anchor, source.species());
            int threshold=anchor==null?128:1+(int)Long.remainderUnsigned(mix(worldSeed^mix(anchor.x)^Long.rotateLeft(mix(anchor.z),17)^Long.rotateLeft(mix(anchor.y),39)^0x61c8864680b583ebL),SeasonColorFrame.COMPLETE);
            boolean blossoms=anchor!=null&&springEligible(source)&&spring.tree(mix(worldSeed^mix(anchor.x)^Long.rotateLeft(mix(anchor.z),23)^Long.rotateLeft(mix(anchor.y),41)^0xa0761d6478bd642fL));
            Selection selection=new Selection(color,threshold,blossoms);colors.put(position,selection);
            return selection;
        }
        /** The last query could not resolve its tree using the currently available bounded samples. */
        public boolean incomplete(){return incomplete;}
        private Kind sample(Position position) {
            return sample(position.x,position.y,position.z);
        }
        private Kind sample(int x,int y,int z) {
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
                if(samples.size()>SAMPLE_CACHE)samples.removeFirst();
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
            firstLayer.clear();secondLayer.clear();firstLayer.add(start);
            ArrayList<Position> layer=firstLayer,next=secondLayer;
            for (int distance = 1; distance <= 7; distance++) {
                next.clear();nearbyLogs.clear();
                boolean unavailable = false;
                for (Position position : layer) for (int[] side : SIDES) {
                    int x=position.x+side[0],y=position.y+side[1],z=position.z+side[2];
                    int local=((x-start.x+7)*15+(y-start.y+7))*15+(z-start.z+7);
                    if(leafVisits[local]==leafEpoch)continue;
                    leafVisits[local]=leafEpoch;
                    if (++visited > MAX_LEAF_SEARCH) { budget.incomplete=true; return null; }
                    Kind kind = searchSample(x,y,z,budget);
                    if (kind == Kind.UNKNOWN) unavailable = true;
                    else if (kind.species() == species && kind.log()) nearbyLogs.add(new Position(x,y,z));
                    // A neighboring tree can replace part of a crown with another species.
                    // Traverse leaves of either species, but only matching logs anchor it.
                    else if (kind.leaves()) next.add(new Position(x,y,z));
                }
                if (unavailable) return null;
                if (!nearbyLogs.isEmpty()) {
                    Position best = null;
                    for (Position log : nearbyLogs) {
                        Position anchor = trunkAnchor(log, species, budget);
                        if (anchor == null) return null;
                        if (best == null || ANCHOR_ORDER.compare(anchor, best) < 0) best = anchor;
                    }
                    return best;
                }
                if (next.isEmpty()) return null;
                ArrayList<Position> swap=layer;layer=next;next=swap;
            }
            return null;
        }
        private Position trunkAnchor(Position log, Species species, SearchBudget budget) {
            Position cached = anchors.get(log);
            if (cached != null) return cached;
            if (anchors.containsKey(log)) { budget.incomplete=true; return null; }
            ArrayDeque<Position> pending = new ArrayDeque<>(); pending.add(log);
            Set<Position> component = new HashSet<>(); component.add(log);
            Set<Position> examined = new HashSet<>(); examined.add(log);
            Position lowest = log;
            while (!pending.isEmpty()) {
                Position current = pending.removeFirst();
                if (ANCHOR_ORDER.compare(current, lowest) < 0) lowest = current;
                // Acacia and large-tree branches can connect diagonally.
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    Position neighbor = current.offset(dx,dy,dz);
                    if (!examined.add(neighbor)) continue;
                    if (examined.size() > MAX_LOG_SEARCH) {
                        for(Position position:component)anchors.put(position,null);
                        budget.incomplete=true;return null;
                    }
                    Kind kind = searchSample(neighbor, budget);
                    if (kind == Kind.UNKNOWN) return null;
                    if (kind.species() == species && kind.log()) {
                        component.add(neighbor); pending.add(neighbor);
                    }
                }
            }
            for (Position position : component) anchors.put(position, lowest);
            return lowest;
        }
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
