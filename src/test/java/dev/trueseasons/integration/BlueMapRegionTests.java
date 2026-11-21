package dev.trueseasons.integration;

import dev.trueseasons.integration.RenderLedger.*;
import dev.trueseasons.integration.RegionRenderGate.*;
import java.util.*;

/** Exercises the production whole-region dispatch gate with migration and save races. */
public final class BlueMapRegionTests {
    private static int assertions;
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        preparedRegions();
        proofCounters();
        RenderLedger ledger = new RenderLedger(); RegionRenderGate gate = new RegionRenderGate();
        UUID world = UUID.randomUUID(); ChunkKey first = new ChunkKey(world, 0, 0), sibling = new ChunkKey(world, 31, 31);
        ChunkKey remote = new ChunkKey(world, 32, 0); Region region = first.region();
        Map<ChunkKey, String> saved = new HashMap<>(); Set<ChunkKey> deleted = new HashSet<>(); Set<Region> staging = new HashSet<>();
        IntegrationAdapter.RegionReadiness ready = (id, x, z) -> true;
        IntegrationAdapter.RegionReadiness indexing = (id, x, z) -> false;
        ledger.restore(first, "first-final", List.of()); saved.put(first, "first-final");
        Batch batch = gate.snapshot(ledger, saved, deleted).get(region);
        check(gate.state(batch, indexing, staging, 100, 30_000) == State.MIGRATING, "Initial indexing cannot dispatch a saved subset");
        check(gate.state(batch, (id, x, z) -> x != 0, staging, 100, 30_000) == State.MIGRATING, "Paused incomplete/local-transition region remains blocked");
        check(gate.state(batch, ready, staging, 100, 30_000) == State.READY, "A paused but already complete region can render");
        ledger.dirty(sibling); gate.changed(region, 100);
        ledger.restore(remote, "remote-final", List.of()); saved.put(remote, "remote-final");
        Map<Region, Batch> batches = gate.snapshot(ledger, saved, deleted);
        check(gate.state(batches.get(region), ready, staging, 40_000, 30_000) == State.SAVING, "A dirty sibling blocks the entire region");
        check(gate.state(batches.get(remote.region()), ready, staging, 40_000, 30_000) == State.READY, "Other regions continue independently");
        ledger.complete(sibling, "sibling-final"); gate.changed(region, 200);
        batch = gate.snapshot(ledger, saved, deleted).get(region);
        check(gate.state(batch, ready, staging, 40_000, 30_000) == State.SAVING, "Finalized sibling awaiting disk proof still blocks region");
        saved.put(sibling, "older-partial");
        batch = gate.snapshot(ledger, saved, deleted).get(region);
        check(gate.state(batch, ready, staging, 40_000, 30_000) == State.SAVING, "Old saved token cannot satisfy a newer final revision");
        saved.put(sibling, "sibling-final");
        batch = gate.snapshot(ledger, saved, deleted).get(region);
        check(batch.revisions().size() == 2, "One batch captures every tracked region member");
        check(gate.state(batch, ready, staging, 300, 30_000) == State.COALESCING, "A recent replenishment burst waits for existing flush window");
        check(gate.state(batch, ready, staging, 30_200, 30_000) == State.READY, "Quiet complete and saved region becomes ready");
        check(gate.state(batch, indexing, staging, 90_000, 30_000) == State.MIGRATING, "Quiet timeout never substitutes for terrain completion");
        gate.changed(region, 59_900);
        batch = gate.snapshot(ledger, saved, deleted).get(region);
        check(gate.state(batch, ready, staging, 60_100, 30_000) == State.READY, "Continuous bursts cannot postpone a complete saved region forever");
        gate.submitted(batch, 60_100); gate.changed(region, 60_200);
        batch = gate.snapshot(ledger, saved, deleted).get(region);
        check(gate.state(batch, ready, staging, 60_300, 30_000) == State.COALESCING, "Later replenishment gets its own bounded coalescing window");
        gate.submitted(batch, 60_400);
        Batch captured = batch;
        check(gate.state(captured, (id, x, z) -> { gate.changed(region, 90_500); return true; }, staging, 100_000, 30_000) == State.STALE,
                "Reentrant readiness invalidation cannot submit stale region snapshot");
        check(gate.state(captured, ready, staging, 130_000, 30_000) == State.STALE, "Old snapshot remains invalid after the quiet window");
        batch = gate.snapshot(ledger, saved, deleted).get(region);
        check(gate.state(batch, (id, x, z) -> false, staging, 130_000, 30_000) == State.MIGRATING, "New terrain blocks old completion readiness");
        staging.add(region);
        check(gate.state(batch, ready, staging, 130_000, 30_000) == State.RECOVERING, "Partially staged source deletion cannot render a subset");
        staging.clear(); deleted.add(first); deleted.add(sibling);
        batch = gate.snapshot(ledger, saved, deleted).get(region);
        check(gate.state(batch, indexing, staging, 90_600, 30_000) == State.READY, "Fully removed source bypasses migration and quiet gates");
        deleted.remove(sibling);
        batch = gate.snapshot(ledger, saved, deleted).get(region);
        check(gate.state(batch, indexing, staging, 130_000, 30_000) == State.MIGRATING, "Mixed deletion/live region retains migration barrier");
        deleted.add(sibling); saved.remove(sibling);
        batch = gate.snapshot(ledger, saved, deleted).get(region);
        check(gate.state(batch, ready, staging, 130_000, 30_000) == State.SAVING, "Deletion still requires its durable readable absence proof");
        saved.put(sibling, "sibling-final");
        List<Revision> flight = gate.snapshot(ledger, saved, deleted).get(region).revisions();
        for (Revision revision : flight) ledger.acknowledge(revision, "surface");
        check(flight.stream().noneMatch(revision -> ledger.needs(revision, "surface")), "Acknowledged map does not receive duplicate calls");
        check(flight.stream().allMatch(revision -> ledger.needs(revision, "caves")), "Other maps retain separate completion receipts");
        ledger.dirty(first); ledger.complete(first, "replenished-final"); gate.changed(region, 150_000);
        check(!ledger.acknowledge(flight.getFirst(), "caves"), "Old region receipt cannot acknowledge a newer replenishment token");
        check(ledger.needs(new Revision(first, "replenished-final"), "surface"), "Previously successful map renders changed final state again");
        check(gate.state(gate.snapshot(ledger, saved, deleted).get(region), ready, staging, 210_000, 30_000) == State.SAVING,
                "Bounded coalescing cannot bypass the new token's save proof");
        check(!gate.paletteReady(region, gate.snapshot(ledger, saved, deleted), ready, staging, 210_000, 30_000),
                "Visual-only request cannot bypass physical final-save proof");
        Region cleanRegion = new Region(world, 30, 40);
        check(gate.paletteReady(cleanRegion, Map.of(), ready, staging, 210_000, 30_000), "Clean saved palette region needs no synthetic chunk metadata");
        check(!gate.paletteReady(cleanRegion, Map.of(), (id, x, z) -> { gate.changed(cleanRegion, 210_001); return true; }, staging, 210_000, 30_000),
                "First physical intent discovered by clean-region readiness blocks palette request");
        System.out.println("BlueMap region tests passed: " + assertions + " assertions");
    }
    private static void preparedRegions(){
        var ledger=new RenderLedger();var gate=new RegionRenderGate();UUID world=new UUID(0,17);
        var first=new ChunkKey(world,-1,-1);var sibling=new ChunkKey(world,-2,-1);Region region=first.region();
        Map<ChunkKey,String> saved=new HashMap<>();Set<ChunkKey> deleted=new HashSet<>();Set<Region> staging=new HashSet<>();
        IntegrationAdapter.RegionReadiness ready=(id,x,z)->true,waiting=(id,x,z)->false;
        ledger.restore(first,"one",List.of());saved.put(first,"one");ledger.restore(sibling,"two",List.of());saved.put(sibling,"two");
        var prepared=gate.prepare(region,ledger,saved,deleted,ready,staging,100000,30000,true);
        check(prepared.state()==State.READY&&prepared.batch().equals(gate.snapshot(ledger,saved,deleted).get(region)),"Ready-region preparation captures the same complete revisions and save barrier as a full snapshot");
        check(gate.prepare(region,ledger,saved,deleted,waiting,staging,100000,30000,true).state()==State.MIGRATING,"Unfinished terrain is rejected before constructing revision lists");
        saved.remove(sibling);
        check(gate.prepare(region,ledger,saved,deleted,ready,staging,100000,30000,true).state()==State.SAVING,"One unsaved sibling blocks an otherwise ready region");
        saved.put(sibling,"two");gate.changed(region,100000);
        check(gate.prepare(region,ledger,saved,deleted,ready,staging,100001,30000,true).state()==State.COALESCING,"Prepared regions keep the existing quiet window");
        deleted.add(first);deleted.add(sibling);
        prepared=gate.prepare(region,ledger,saved,deleted,waiting,staging,100001,30000,true);
        check(prepared.state()==State.READY&&prepared.batch().deletionOnly(),"Wholly deleted saved regions retain their migration and quiet-window exemption");
        staging.add(region);
        check(gate.prepare(region,ledger,saved,deleted,ready,staging,100001,30000,true).state()==State.RECOVERING,"Deletion staging blocks partial region submission");
        staging.clear();deleted.clear();
        var stale=gate.prepare(region,ledger,saved,deleted,(id,x,z)->{gate.changed(region,200000);return true;},staging,200000,30000,true);
        check(stale.state()==State.STALE&&stale.batch()==null,"Readiness that discovers new physical work cannot capture a newer subset as its old candidate");
        prepared=gate.prepare(region,ledger,saved,deleted,ready,staging,300000,30000,true);gate.changed(region,300001);
        check(gate.state(prepared.batch(),ready,staging,400000,30000)==State.STALE,"The final submission check retains the selected batch's original revision serial");
        Region clean=new Region(world,90,90);
        prepared=gate.prepare(clean,ledger,saved,deleted,ready,staging,400000,30000,false);
        check(prepared.state()==State.READY&&prepared.batch().revisions().isEmpty(),"Palette-only regions need no synthetic chunk revisions");
        gate.changed(clean,400001);
        check(gate.state(prepared.batch(),ready,staging,500000,30000)==State.STALE,"A clean palette candidate also becomes stale after its first physical intent");
        Region newlyObserved=new Region(world,100,100);
        check(gate.prepare(newlyObserved,ledger,saved,deleted,(id,x,z)->{gate.changed(newlyObserved,500001);return true;},staging,500000,30000,false).state()==State.STALE,"First physical work during palette readiness is rejected immediately");
    }
    private static void proofCounters(){
        var ledger=new RenderLedger();var gate=new RegionRenderGate();var saved=ledger.readable();var deleted=ledger.deletions();
        var folders=ledger.folders();var restored=ledger.restored();
        Map<ChunkKey,Boolean> purges=dev.trueseasons.state.TrackedCollections.map(new HashMap<>(),(key,before,after)->ledger.purgeChanged(key,Boolean.TRUE.equals(before),Boolean.TRUE.equals(after)),()->true);
        ledger.cleanupPurges(key->Boolean.TRUE.equals(purges.get(key)));
        UUID first=new UUID(0,51),second=new UUID(0,52);Set<UUID> configured=Set.of(first);Random random=new Random(812733);
        for(int step=0;step<1600;step++){
            int n=random.nextInt(64);var key=new ChunkKey((n&1)==0?first:second,(n/16)*32+(n&15),(n/32)*32+(n&15));String token="revision-"+(step%7);
            switch(step%22){
                case 0->ledger.restore(key,token,List.of());
                case 1->saved.put(key,token);
                case 2->saved.compute(key,(k,old)->stepToken(k));
                case 3->deleted.add(key);
                case 4->ledger.dirty(key);
                case 5->ledger.complete(key,token);
                case 6->{var entry=ledger.entries().get(key);if(entry!=null)saved.put(key,entry.token());}
                case 7->deleted.remove(key);
                case 8->{if(!saved.isEmpty())saved.entrySet().iterator().next().setValue(token);}
                case 9->{if(!saved.isEmpty()){var iterator=saved.keySet().iterator();iterator.next();iterator.remove();}}
                case 10->saved.values().remove(token);
                case 11->deleted.removeIf(value->value.x()<32);
                case 12->{var entry=ledger.entries().get(key);if(entry!=null&&!entry.token().isEmpty()){ledger.acknowledge(new Revision(key,entry.token()),"surface");ledger.retire(key,List.of("surface"));}}
                case 13->saved.merge(key,token,(old,value)->null);
                case 14->{if(step%64==14){saved.clear();deleted.clear();}else saved.replaceAll((k,old)->old==null?null:"replacement");}
                case 15->ledger.replaceWithDeletion(new Revision(key,ledger.entries().containsKey(key)?ledger.entries().get(key).token():"absent"),token,false);
                case 16->{if(ledger.entries().containsKey(key))ledger.cleanup(key,random.nextBoolean());}
                case 17->{if(random.nextBoolean())purges.put(key,random.nextBoolean());else purges.remove(key);}
                case 18->folders.put(key,java.nio.file.Path.of("source-"+(n%3)));
                case 19->restored.add(key);
                case 20->folders.remove(key);
                default->restored.remove(key);
            }
            int transitions=0,awaiting=0,ready=0,deletions=0,unmapped=0;
            for(var row:ledger.entries().entrySet()){
                if(row.getValue().token().isEmpty())transitions++;
                else if(!row.getValue().token().equals(saved.get(row.getKey())))awaiting++;
                else if(deleted.contains(row.getKey()))deletions++;else ready++;
                if(!configured.contains(row.getKey().world()))unmapped++;
            }
            check(ledger.counts(configured).equals(new RenderLedger.Counts(transitions,awaiting,ready,deletions,unmapped)),"Incremental map status matches the complete current ledger through direct and view mutations");
            Set<ChunkKey> cleanup=new HashSet<>();int purgeCount=0;
            for(var row:purges.entrySet())if(Boolean.TRUE.equals(row.getValue())){cleanup.add(row.getKey());purgeCount++;}
            for(var row:ledger.entries().entrySet())if(row.getValue().cleanup()&&!row.getValue().token().equals(saved.get(row.getKey())))cleanup.add(row.getKey());
            check(purgeCount+ledger.cleanupPending()==cleanup.size(),"Cleanup counters retain the exact union of unfinished cleanup revisions and pending metadata purges");
            var snapshot=gate.snapshot(ledger,saved,deleted);
            for(var batch:snapshot.values()){
                check(ledger.saved(batch.region())==batch.saved()&&ledger.deleted(batch.region())==batch.deletionOnly(),"Region save and deletion counters match complete token snapshots");
                var live=ledger.members(batch.region()).stream().filter(entry->!deleted.contains(entry.chunk())).toList();
                boolean sourceCandidate=!live.isEmpty()&&live.stream().allMatch(entry->folders.containsKey(entry.chunk())&&(restored.contains(entry.chunk())||entry.token().equals(saved.get(entry.chunk()))));
                check(ledger.sourceCandidate(batch.region())==sourceCandidate,"Source-deletion eligibility matches every current non-deleted member, its folder and its saved or restored proof");
                var prepared=gate.prepare(batch.region(),ledger,saved,deleted,(id,x,z)->true,Set.of(),100000,30000,true);
                check(prepared.state()==gate.state(batch,(id,x,z)->true,Set.of(),100000,30000),"Tracked proof fast paths keep the same render readiness");
            }
        }
        saved.put(new ChunkKey(first,0,0),null);saved.clear();deleted.clear();
        check(ledger.counts(configured).ready()==0&&ledger.counts(configured).sourceDeletions()==0,"Clearing proof views cannot retain a saved or deleted readiness count");
    }
    private static String stepToken(ChunkKey key){return "computed-"+key.x();}
}
