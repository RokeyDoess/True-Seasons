package dev.trueseasons.integration;

import java.util.*;

/** Main-thread ledger; renderer callbacks never mutate durable state. */
public final class RenderLedger {
    public record ChunkKey(UUID world, int x, int z) {
        public Region region() { return new Region(world, Math.floorDiv(x, 32), Math.floorDiv(z, 32)); }
    }
    public record Region(UUID world, int x, int z) {}
    public record Revision(ChunkKey chunk, String token) {}
    public static final class Entry {
        private String token;
        private final Set<String> acknowledged;
        private boolean cleanup;
        Entry(String token, Collection<String> acknowledged) {
            this.token = token; this.acknowledged = new HashSet<>(acknowledged);
        }
        public String token() { return token; }
        public Set<String> acknowledged() { return Set.copyOf(acknowledged); }
        public boolean cleanup() { return cleanup; }
    }
    private final Map<ChunkKey, Entry> entries = new LinkedHashMap<>();
    public Map<ChunkKey, Entry> entries() { return Collections.unmodifiableMap(entries); }
    public void restore(ChunkKey chunk, String token, Collection<String> acknowledged) {
        entries.put(chunk, new Entry(token, acknowledged));
    }
    public void cleanup(ChunkKey chunk, boolean value) { entries.get(chunk).cleanup = value; }
    public boolean dirty(ChunkKey chunk) {
        Entry entry = entries.get(chunk);
        if (entry != null && entry.token.isEmpty()) return false;
        entries.put(chunk, new Entry("", List.of())); return true;
    }
    public boolean complete(ChunkKey chunk, String token) {
        Entry entry = entries.get(chunk);
        if (entry == null || !entry.token.isEmpty()) return false;
        entry.token = token; return true;
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
        Entry entry = new Entry(token, List.of()); entry.cleanup = cleanup; entries.put(previous.chunk(), entry); return true;
    }
    public boolean requiresMarkerRecovery(ChunkKey chunk, String marker, boolean saved, boolean deleted) {
        Entry entry = entries.get(chunk);
        return entry != null && (deleted || entry.token.isEmpty() || (!saved && !entry.token.equals(marker)));
    }
    public static boolean sourceDeletionAllowed(boolean restored, boolean provenSaved, boolean loaded) {
        return !loaded && (restored || provenSaved);
    }
    public boolean needs(Revision revision, String map) {
        return matches(revision) && !entries.get(revision.chunk()).acknowledged.contains(map);
    }
    public boolean acknowledge(Revision revision, String map) {
        return matches(revision) && entries.get(revision.chunk()).acknowledged.add(map);
    }
    public boolean retire(ChunkKey chunk, Collection<String> maps) {
        Entry entry = entries.get(chunk);
        if (entry == null || entry.token.isEmpty() || maps.isEmpty() || !entry.acknowledged.containsAll(maps)) return false;
        entries.remove(chunk); return true;
    }
    public int regions() { return (int) entries.keySet().stream().map(ChunkKey::region).distinct().count(); }
}
