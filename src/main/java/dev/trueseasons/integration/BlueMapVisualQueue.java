package dev.trueseasons.integration;

import com.flowpowered.math.vector.Vector2i;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Durable per-map recolours. Discovery and queue hydration stay off the server thread. */
final class BlueMapVisualQueue implements AutoCloseable {
    private final DurableRenderJournal journal;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "TrueSeasons-map-palettes"); thread.setDaemon(true); return thread;
    });
    private static final class MapState {
        String target = "vanilla";
        String generation = UUID.randomUUID().toString();
        Set<Vector2i> regions = new LinkedHashSet<>();
        ArrayDeque<Vector2i> rotation = new ArrayDeque<>();
        CompletableFuture<Discovered> discovery;
        Receipt receipt;
        Rebinding rebinding;
    }
    private record Discovered(Set<Vector2i> regions, ArrayDeque<Vector2i> rotation) {}
    private record Receipt(String generation, List<Vector2i> regions, CompletableFuture<Void> durable) {}
    private record Rebinding(String before,String after,String generation,CompletableFuture<Void> durable) {}
    private final Map<String, MapState> maps = new LinkedHashMap<>();
    private CompletableFuture<Boolean> compacting;
    private String failure;
    BlueMapVisualQueue(Path directory) throws IOException {
        journal = new DurableRenderJournal(directory);
        for (var entry : journal.state().entrySet()) {
            String[] key = entry.getKey().split("/", 2);
            String map = new String(Base64.getUrlDecoder().decode(key[0]), StandardCharsets.UTF_8);
            MapState state = maps.computeIfAbsent(map, ignored -> new MapState());
            if (key.length == 1) state.target = new String(entry.getValue(), StandardCharsets.UTF_8);
            else { String[] coordinates = key[1].split(","); Vector2i region = new Vector2i(Integer.parseInt(coordinates[0]), Integer.parseInt(coordinates[1]));
                state.regions.add(region); state.rotation.addLast(region); }
        }
    }
    /** The native world's listRegions only lists saved region coordinates; it never generates chunks. */
    void target(String map, String fingerprint, Callable<Collection<Vector2i>> existingRegions) {
        target(map,fingerprint,List.of(),existingRegions);
    }
    /** Equivalent old targets keep their region receipts and generation during the format upgrade. */
    void target(String map,String fingerprint,String equivalent,Callable<Collection<Vector2i>> existingRegions){
        target(map,fingerprint,equivalent==null?List.of():List.of(equivalent),existingRegions);
    }
    void target(String map,String fingerprint,Collection<String> equivalents,Callable<Collection<Vector2i>> existingRegions){
        MapState state = maps.computeIfAbsent(map, ignored -> new MapState());
        collectRebinding(state);
        if (state.target.equals(fingerprint) && (state.discovery == null || !state.discovery.isCompletedExceptionally())) return;
        if(equivalents.contains(state.target)&&!state.target.equals(fingerprint)){
            regions(map);
            // Let an existing discovery or receipt finish without replacing its work.
            if(state.discovery!=null||state.receipt!=null)return;
            String before=state.target;state.target=fingerprint;
            CompletableFuture<Void> durable=CompletableFuture.runAsync(()->{
                try{journal.put(mapKey(map),fingerprint.getBytes(StandardCharsets.UTF_8),true);}
                catch(IOException failure){throw new CompletionException(failure);}
            },worker);
            state.rebinding=new Rebinding(before,fingerprint,state.generation,durable);
            return;
        }
        state.target = fingerprint;
        state.generation = UUID.randomUUID().toString();
        state.discovery = CompletableFuture.supplyAsync(() -> {
            try {
                Set<Vector2i> discovered = new LinkedHashSet<>(existingRegions.call());
                Map<String, byte[]> changes = new LinkedHashMap<>();
                // Discovery is one serial worker. Remove durable coordinates from the previous target,
                // including a superseded discovery that finished since the target was requested.
                for (String key : journal.state().keySet()) if (key.startsWith(mapKey(map) + "/")) changes.put(key, new byte[0]);
                for (Vector2i region : discovered) changes.put(key(map, region), new byte[]{1});
                changes.put(mapKey(map), fingerprint.getBytes(StandardCharsets.UTF_8));
                journal.putAll(changes, true);
                return new Discovered(discovered, new ArrayDeque<>(discovered));
            } catch (Exception failure) { throw new CompletionException(failure); }
        }, worker);
    }
    String target(String map) { MapState state = maps.get(map); return state == null ? "vanilla" : state.target; }
    Set<String> recordedMaps() { return Set.copyOf(maps.keySet()); }
    String receiptToken(String map) { MapState state = maps.get(map); return state == null ? "" : state.generation; }
    Collection<Vector2i> regions(String map) {
        MapState state = maps.get(map); if (state == null) return List.of();
        collectRebinding(state);
        collectReceipt(state);
        if (state.discovery != null) {
            if (!state.discovery.isDone()) return List.of();
            Discovered discovered = state.discovery.join(); state.regions = discovered.regions; state.rotation = discovered.rotation; state.discovery = null;
        }
        return state.regions;
    }
    Collection<Vector2i> candidates(String map, int budget) {
        regions(map); MapState state = maps.get(map); if (state == null || state.discovery != null || state.receipt != null) return List.of();
        List<Vector2i> result = new ArrayList<>();
        for (int n = Math.min(budget, state.rotation.size()); n > 0; n--) {
            Vector2i region = state.rotation.removeFirst();
            if (state.regions.contains(region)) { result.add(region); state.rotation.addLast(region); }
        }
        return result;
    }
    void acknowledge(String map, String generation, Collection<Vector2i> regions) throws IOException {
        MapState state = maps.get(map);
        if (state == null) return;
        collectReceipt(state);
        if (!state.generation.equals(generation) || state.discovery != null || state.receipt != null) return;
        Map<String, byte[]> changes = new LinkedHashMap<>();
        List<Vector2i> completed = new ArrayList<>();
        for (Vector2i region : regions) if (state.regions.contains(region)) { changes.put(key(map, region), new byte[0]); completed.add(region); }
        if (changes.isEmpty()) return;
        // Never wait on a journal mutex while another map's bulk discovery is persisting.
        // Candidates remain gated until this bounded receipt is durable.
        CompletableFuture<Void> durable = CompletableFuture.runAsync(() -> {
            try { journal.putAll(changes, true); }
            catch (IOException failure) { throw new CompletionException(failure); }
        }, worker);
        state.receipt = new Receipt(generation, List.copyOf(completed), durable);
    }
    private void collectReceipt(MapState state) {
        Receipt receipt = state.receipt;
        if (receipt == null || !receipt.durable.isDone()) return;
        state.receipt = null;
        try {
            receipt.durable.join();
            if (state.generation.equals(receipt.generation) && state.discovery == null) state.regions.removeAll(receipt.regions);
            failure = null;
        } catch (CompletionException failed) {
            if (state.generation.equals(receipt.generation)) failure = "palette receipt persistence failed: " + failed.getCause();
            // Intent remains pending and can safely render again after the failed receipt.
        }
    }
    private void collectRebinding(MapState state){
        Rebinding pending=state.rebinding;if(pending==null||!pending.durable.isDone())return;state.rebinding=null;
        try{pending.durable.join();failure=null;}
        catch(CompletionException failed){
            if(state.generation.equals(pending.generation)&&state.target.equals(pending.after)){
                state.target=pending.before;
                failure="palette fingerprint persistence failed: "+failed.getCause();
            }
        }
    }
    int pending() {
        return pending(maps.keySet());
    }
    /** Add actual world coordinates, collapsing overlaps between maps and terrain. Never wait for discovery. */
    boolean appendPendingRegions(String map,UUID world,boolean restoring,Set<RenderLedger.Region> target){
        MapState state=maps.get(map);if(state==null)return true;
        collectReceipt(state);
        if(state.discovery!=null){
            if(!state.discovery.isDone()||state.discovery.isCompletedExceptionally())return false;
            regions(map);
        }
        for(Vector2i region:state.regions)target.add(new RenderLedger.Region(world,region.getX(),region.getY()));
        return !restoring||state.target.equals("vanilla")||!state.regions.isEmpty();
    }
    int pending(Collection<String> relevantMaps) {
        int count = 0;
        for (String map : relevantMaps) {
            MapState state = maps.get(map); if (state == null) continue; collectRebinding(state);collectReceipt(state);
            count += state.discovery != null ? 1 : state.regions.size();
        }
        return count;
    }
    int restorationPending(Collection<String> relevantMaps) {
        int count = 0;
        for (String map : relevantMaps) {
            MapState state = maps.get(map); if (state == null) continue; collectRebinding(state);collectReceipt(state);
            // An unavailable mapped colour hook may fail before it can establish a vanilla
            // target. Never claim that such a previously seasonal map is safely restored.
            count += !state.target.equals("vanilla") ? Math.max(1, state.regions.size()) : state.discovery != null ? 1 : state.regions.size();
        }
        return count;
    }
    String failure() { return failure; }
    void compact() {
        if (compacting != null && compacting.isDone()) {
            try { compacting.join(); } catch (CompletionException failed) { failure = "palette snapshot persistence failed: " + failed.getCause(); }
            compacting = null;
        }
        // The size query is synchronized too: perform every live journal operation on
        // the worker so neither discovery nor compaction can stall the server thread.
        if (compacting == null) compacting = CompletableFuture.supplyAsync(() ->
                journal.changesSinceSnapshot() >= 2048 && journal.compactAsync(Runnable::run).join(), worker);
    }
    private static String mapKey(String map) { return Base64.getUrlEncoder().withoutPadding().encodeToString(map.getBytes(StandardCharsets.UTF_8)); }
    private static String key(String map, Vector2i region) { return mapKey(map) + "/" + region.getX() + "," + region.getY(); }
    @Override public void close() throws IOException {
        worker.shutdown();
        try { if (!worker.awaitTermination(30, TimeUnit.SECONDS)) throw new IOException("Palette queue persistence still active"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
        journal.close();
    }
}
