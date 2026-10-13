package dev.trueseasons.integration;

import dev.trueseasons.integration.RenderLedger.*;
import java.util.*;

/** Whole-region save barrier and bounded burst coalescing; used only on the main thread. */
final class RegionRenderGate {
    enum State { READY, MIGRATING, SAVING, COALESCING, RECOVERING, STALE }
    record Batch(Region region, List<Revision> revisions, boolean saved, boolean deletionOnly, long serial) {}
    private static final class Change {
        long serial, first, last;
        Change(long serial, long now) { this.serial = serial; first = last = now; }
    }
    private final Map<Region, Change> changes = new HashMap<>();
    private long serial;
    void changed(Region region, long now) {
        Change change = changes.computeIfAbsent(region, ignored -> new Change(0, now));
        change.serial = ++serial; change.last = now;
    }
    void submitted(Batch batch, long now) {
        Change change = changes.get(batch.region);
        if (change != null && change.serial == batch.serial) change.first = now;
    }
    void retain(Set<Region> pending) { changes.keySet().retainAll(pending); }
    private long serial(Region region) { Change change = changes.get(region); return change == null ? 0 : change.serial; }
    Map<Region, Batch> snapshot(RenderLedger ledger, Map<ChunkKey, String> readable, Set<ChunkKey> deleted) {
        Map<Region, List<Revision>> members = new LinkedHashMap<>();
        ledger.entries().forEach((key, entry) -> members.computeIfAbsent(key.region(), ignored -> new ArrayList<>()).add(new Revision(key, entry.token())));
        Map<Region, Batch> batches = new LinkedHashMap<>();
        members.forEach((region, revisions) -> batches.put(region, new Batch(region, List.copyOf(revisions),
                revisions.stream().allMatch(revision -> !revision.token().isEmpty() && revision.token().equals(readable.get(revision.chunk()))),
                revisions.stream().allMatch(revision -> deleted.contains(revision.chunk())), serial(region))));
        return batches;
    }
    State state(Batch batch, IntegrationAdapter.RegionReadiness readiness, Set<Region> staging, long now, long quietMillis) {
        if (batch.serial != serial(batch.region)) return State.STALE;
        if (staging.contains(batch.region)) return State.RECOVERING;
        if (!batch.deletionOnly && !readiness.ready(batch.region.world(), batch.region.x(), batch.region.z())) return State.MIGRATING;
        // A readiness callback may itself observe terrain or edit a chunk. Never submit its stale snapshot.
        if (batch.serial != serial(batch.region)) return State.STALE;
        if (!batch.saved) return State.SAVING;
        Change change = changes.get(batch.region);
        if (!batch.deletionOnly && change != null && now - change.last < quietMillis && now - change.first < quietMillis * 2)
            return State.COALESCING;
        return State.READY;
    }
}
