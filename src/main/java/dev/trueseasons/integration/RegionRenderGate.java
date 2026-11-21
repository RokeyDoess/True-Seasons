package dev.trueseasons.integration;

import dev.trueseasons.integration.RenderLedger.*;
import java.util.*;

/** Whole-region save barrier and bounded burst coalescing; used only on the main thread. */
final class RegionRenderGate {
    enum State { READY, MIGRATING, SAVING, COALESCING, RECOVERING, STALE }
    record Batch(Region region, List<Revision> revisions, boolean saved, boolean deletionOnly, long serial) {}
    record Prepared(State state,Batch batch) {}
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
    boolean paletteReady(Region region, Map<Region, Batch> batches, IntegrationAdapter.RegionReadiness readiness,
            Set<Region> staging, long now, long quietMillis) {
        Batch batch = batches.get(region);
        if (batch != null) return state(batch, readiness, staging, now, quietMillis) == State.READY;
        long captured = serial(region);
        // Readiness can create the first physical intent in a previously clean region.
        return !staging.contains(region) && readiness.ready(region.world(), region.x(), region.z()) && captured == serial(region);
    }
    Map<Region, Batch> snapshot(RenderLedger ledger, Map<ChunkKey, String> readable, Set<ChunkKey> deleted) {
        Map<Region, List<Revision>> members = new LinkedHashMap<>();
        ledger.entries().forEach((key, entry) -> members.computeIfAbsent(key.region(), ignored -> new ArrayList<>()).add(new Revision(key, entry.token())));
        Map<Region, Batch> batches = new LinkedHashMap<>();
        members.forEach((region, revisions) -> batches.put(region, new Batch(region, List.copyOf(revisions),
                revisions.stream().allMatch(revision -> !revision.token().isEmpty() && revision.token().equals(readable.get(revision.chunk()))),
                revisions.stream().allMatch(revision -> deleted.contains(revision.chunk())), serial(region))));
        return batches;
    }
    /** Inspect one region, and materialize revisions only when a caller can submit them. */
    Prepared prepare(Region region,RenderLedger ledger,Map<ChunkKey,String> readable,Set<ChunkKey> deleted,
            IntegrationAdapter.RegionReadiness readiness,Set<Region> staging,long now,long quietMillis,boolean capture){
        Collection<RenderLedger.Entry> members=ledger.members(region);long captured=serial(region);
        boolean clean=members.isEmpty(),deletionOnly;
        if(ledger.tracksDeletions(deleted))deletionOnly=!clean&&ledger.deleted(region);
        else{
            deletionOnly=!clean&&!deleted.isEmpty();
            if(deletionOnly)for(var entry:members)if(!deleted.contains(entry.chunk())){deletionOnly=false;break;}
        }
        State barrier=readiness(region,deletionOnly,captured,readiness,staging);
        if(barrier!=State.READY)return new Prepared(barrier,null);
        if(!clean){
            if(ledger.tracksReadable(readable)){
                if(!ledger.saved(region))return new Prepared(State.SAVING,null);
            }else for(var entry:members)if(entry.token().isEmpty()||!entry.token().equals(readable.get(entry.chunk())))return new Prepared(State.SAVING,null);
            Change change=changes.get(region);
            if(!deletionOnly&&change!=null&&now-change.last<quietMillis&&now-change.first<quietMillis*2)return new Prepared(State.COALESCING,null);
        }
        List<Revision> revisions=List.of();
        if(capture&&!clean){
            var collected=new ArrayList<Revision>(members.size());for(var entry:members)collected.add(new Revision(entry.chunk(),entry.token()));revisions=List.copyOf(collected);
        }
        return new Prepared(State.READY,new Batch(region,revisions,true,deletionOnly,captured));
    }
    private State readiness(Region region,boolean deletionOnly,long captured,IntegrationAdapter.RegionReadiness readiness,Set<Region> staging){
        if(captured!=serial(region))return State.STALE;
        if(staging.contains(region))return State.RECOVERING;
        if(!deletionOnly&&!readiness.ready(region.world(),region.x(),region.z()))return State.MIGRATING;
        return captured!=serial(region)?State.STALE:State.READY;
    }
    State state(Batch batch, IntegrationAdapter.RegionReadiness readiness, Set<Region> staging, long now, long quietMillis) {
        State barrier=readiness(batch.region,batch.deletionOnly,batch.serial,readiness,staging);
        if(barrier!=State.READY)return barrier;
        if (!batch.saved) return State.SAVING;
        Change change = changes.get(batch.region);
        if (!batch.deletionOnly && change != null && now - change.last < quietMillis && now - change.first < quietMillis * 2)
            return State.COALESCING;
        return State.READY;
    }
}
