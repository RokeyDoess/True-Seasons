package dev.trueseasons.migration;

import java.util.*;
import java.util.function.Predicate;

/** One active region, a square spiral from the origin, and bounded slot selection. */
final class RegionWorkQueue {
    record Region(int x, int z) implements Comparable<Region> {
        static Region of(ChunkPos pos) { return new Region(Math.floorDiv(pos.x(), 32), Math.floorDiv(pos.z(), 32)); }
        @Override public int compareTo(Region other) {
            long ring=Math.max(Math.abs((long)x),Math.abs((long)z));
            long otherRing=Math.max(Math.abs((long)other.x),Math.abs((long)other.z));
            int order=Long.compare(ring,otherRing);
            return order!=0?order:Long.compare(offset(ring),other.offset(otherRing));
        }
        private long offset(long ring){
            if(ring==0)return 0;
            if(x==ring&&z>-ring)return z+ring-1;
            if(z==ring)return 2*ring+ring-1-x;
            if(x==-ring)return 4*ring+ring-1-z;
            return 6*ring+x+ring-1;
        }
    }
    private static final Comparator<ChunkPos> SLOT = Comparator.comparingInt(pos -> Math.floorMod(pos.z(),32)*32+Math.floorMod(pos.x(),32));
    private final NavigableMap<Region, NavigableSet<ChunkPos>> regions = new TreeMap<>();
    private Region active;
    void addLast(ChunkPos pos) { regions.computeIfAbsent(Region.of(pos), ignored -> new TreeSet<>(SLOT)).add(pos); }
    void add(ChunkPos pos) { addLast(pos); }
    int size() { return regions.values().stream().mapToInt(Set::size).sum(); }
    ChunkPos peek() { var members = active == null ? (regions.isEmpty() ? null : regions.firstEntry().getValue()) : regions.get(active); return members == null ? null : members.first(); }
    void remove(ChunkPos pos) {
        Region region = Region.of(pos); var members = regions.get(region);
        if (members != null && members.remove(pos) && members.isEmpty()) regions.remove(region);
    }
    ChunkPos pollFirst(Predicate<Region> complete) {
        if (active != null && !regions.containsKey(active) && complete.test(active)) active = null;
        if (active == null) {
            if (regions.isEmpty()) return null;
            active = regions.firstKey();
        }
        var members = regions.get(active);
        if (members == null) return null; // In-flight/failed work holds the region until proved complete.
        ChunkPos pos = members.pollFirst();
        if (members.isEmpty()) regions.remove(active);
        return pos;
    }
    Region active() { return active; }
    boolean isEmpty() { return regions.isEmpty(); }
    void clear() { regions.clear(); active = null; }
    void removeIf(Predicate<ChunkPos> predicate) {
        for (var iterator = regions.values().iterator(); iterator.hasNext();) {
            var members = iterator.next(); members.removeIf(predicate); if (members.isEmpty()) iterator.remove();
        }
    }
}
