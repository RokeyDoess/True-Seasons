package dev.trueseasons.integration;

import dev.trueseasons.integration.RenderLedger.*;
import dev.trueseasons.integration.RegionRenderGate.*;
import java.util.*;

/** Exercises the production whole-region dispatch gate with migration and save races. */
public final class BlueMapRegionTests {
    private static int assertions;
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
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
}
