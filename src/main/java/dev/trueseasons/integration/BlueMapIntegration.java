package dev.trueseasons.integration;

import com.flowpowered.math.vector.Vector2i;
import de.bluecolored.bluemap.api.*;
import dev.trueseasons.config.Settings;
import dev.trueseasons.integration.RenderLedger.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Final revisions are retired only after saved-chunk proof and a receipt from every map. */
public final class BlueMapIntegration implements IntegrationAdapter, Listener {
    private record Probe(Revision revision, Path folder, boolean purge, boolean absenceAllowed, List<Revision> regionMembers) {
        Probe(Revision revision, Path folder, boolean purge, boolean absenceAllowed) { this(revision, folder, purge, absenceAllowed, List.of()); }
    }
    private record Proof(Revision revision, boolean purge, boolean absent, String error, boolean observationOnly, List<Revision> regionMembers, Path source) {}
    private record DeletionBatch(Region region, ArrayDeque<Revision> remaining, Path source) {}
    private record Purge(String token, boolean cleanup) {}
    private record Flight(BlueMapRenderBridge.Ticket ticket, List<Revision> revisions) {}
    private final JavaPlugin plugin;
    private final Supplier<Settings> settings;
    private final Path file;
    private final DurableRenderJournal journal;
    private final Set<ChunkKey> checkpointDirty = new LinkedHashSet<>(), durabilityRequired = new LinkedHashSet<>();
    private final ExecutorService persistence = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "TrueSeasons-render-journal"); thread.setDaemon(true); return thread;
    });
    private final NamespacedKey revisionKey, seasonKey;
    private final RenderLedger ledger = new RenderLedger();
    private final Map<ChunkKey, String> readable = new HashMap<>();
    private final Map<ChunkKey, Path> folders = new HashMap<>();
    private final Set<ChunkKey> restored = new HashSet<>(), deleted = new HashSet<>();
    private final Map<ChunkKey, String> proofErrors = new HashMap<>();
    private final ArrayDeque<DeletionBatch> deletionBatches = new ArrayDeque<>();
    private final Set<Region> deletionStaging = new HashSet<>();
    private final Map<ChunkKey, Purge> purges = new LinkedHashMap<>();
    private final Set<ChunkKey> purgeLoaded = new HashSet<>(), loading = new HashSet<>();
    private final Map<String, Flight> flights = new HashMap<>();
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "TrueSeasons-saved-chunks"); thread.setDaemon(true); return thread;
    });
    private CompletableFuture<List<Proof>> checking;
    private CompletableFuture<Boolean> compacting;
    private RenderManager manager;
    private BlueMapRenderBridge bridge;
    private long lastFlush, lastRenderAttempt, lastProbe, lastCompact, lastRetire, lastWorldRefresh, lastStatus;
    private final Map<UUID, BlueMapWorld> mappedWorlds = new LinkedHashMap<>();
    private BlueMapAPI mappedApi;
    private int probeCursor, purgeCursor;
    private boolean closed;
    private String detail = "waiting for BlueMap";
    private String lastFailure;
    public BlueMapIntegration(JavaPlugin plugin, Supplier<Settings> settings) throws Exception {
        this.plugin = plugin; this.settings = settings;
        revisionKey = new NamespacedKey(plugin, "bluemap_revision"); seasonKey = new NamespacedKey(plugin, "season_version");
        file = plugin.getDataFolder().toPath().resolve("state/bluemap.yml");
        journal = new DurableRenderJournal(file.getParent());
        Map<String, byte[]> recovered = journal.state();
        boolean journalAuthority = recovered.containsKey("__format") || journal.hasSnapshot();
        if (Files.exists(file) && !journalAuthority) {
            YamlConfiguration data = new YamlConfiguration(); data.load(file.toFile());
            if (data.getInt("schema") == 1) {
                for (String value : data.getStringList("regions")) {
                    String[] fields = value.split(",");
                    ledger.restore(new ChunkKey(UUID.fromString(fields[0]), Integer.parseInt(fields[1]) * 32,
                            Integer.parseInt(fields[2]) * 32), "legacy", List.of());
                }
            } else if (data.getInt("schema") == 3) {
                for (Map<?, ?> row : data.getMapList("chunks")) {
                    ChunkKey key = rowKey(row); List<String> maps = new ArrayList<>();
                    if (row.get("acknowledged") instanceof Collection<?> values) values.forEach(v -> maps.add(v.toString()));
                    String token = Objects.toString(row.get("revision"), "");
                    ledger.restore(key, token, maps); ledger.cleanup(key, Boolean.TRUE.equals(row.get("cleanup")));
                    if (Boolean.TRUE.equals(row.get("saved"))) readable.put(key, token);
                    if (row.get("folder") != null) folders.put(key, Path.of(row.get("folder").toString()));
                }
                for (Map<?, ?> row : data.getMapList("purges")) {
                    ChunkKey key = rowKey(row);
                    purges.put(key, new Purge(row.get("revision").toString(), Boolean.TRUE.equals(row.get("cleanup"))));
                    if (row.get("folder") != null) folders.put(key, Path.of(row.get("folder").toString()));
                }
            } else throw new IOException("Unknown BlueMap queue schema");
        }
        if (journalAuthority) {
            for (var row : recovered.entrySet()) if (!row.getKey().equals("__format")) restore(row.getKey(), row.getValue());
        } else {
            Map<String, byte[]> migration = new LinkedHashMap<>();
            Set<ChunkKey> keys = new LinkedHashSet<>(ledger.entries().keySet()); keys.addAll(purges.keySet());
            for (ChunkKey key : keys) migration.put(journalKey(key), encode(key));
            migration.put("__format", new byte[]{1}); journal.putAll(migration, true);
        }
        restored.addAll(ledger.entries().keySet());
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }
    private static ChunkKey rowKey(Map<?, ?> row) {
        return new ChunkKey(UUID.fromString(row.get("world").toString()), ((Number) row.get("x")).intValue(), ((Number) row.get("z")).intValue());
    }
    private static ChunkKey key(Chunk chunk) { return new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()); }
    /** Used by core cleanup even when BlueMap is absent or disabled. */
    public static void clearMetadata(Chunk chunk, JavaPlugin plugin) {
        chunk.getPersistentDataContainer().remove(new NamespacedKey(plugin, "bluemap_revision"));
    }
    @Override public void chunkUpdated(Chunk chunk) {
        ChunkKey key = key(chunk);
        if (!settings.get().blueMap && !ledger.entries().containsKey(key)) return;
        readable.remove(key); purges.remove(key); purgeLoaded.remove(key); restored.remove(key); deleted.remove(key); proofErrors.remove(key);
        if (ledger.dirty(key) || durabilityRequired.contains(key)) persist(Set.of(key), true); // Before editing the world.
        chunk.getPersistentDataContainer().remove(revisionKey);
    }
    @Override public void chunkCompleted(Chunk chunk) {
        chunkCompleted(chunk, !chunk.getPersistentDataContainer().has(seasonKey));
    }
    @Override public void chunkCompleted(Chunk chunk, boolean cleanup) {
        ChunkKey key = key(chunk); RenderLedger.Entry entry = ledger.entries().get(key);
        if (entry == null) { if (cleanup) clearMetadata(chunk, plugin); return; }
        restored.remove(key); proofErrors.remove(key);
        boolean deletion = deleted.remove(key);
        if (!entry.token().isEmpty() && (entry.cleanup() != cleanup || deletion)) {
            readable.remove(key); purges.remove(key); purgeLoaded.remove(key);
            ledger.dirty(key); entry = ledger.entries().get(key);
        }
        if (entry.token().isEmpty() || entry.token().equals("legacy")) {
            if (entry.token().equals("legacy")) ledger.dirty(key);
            ledger.complete(key, UUID.randomUUID().toString()); ledger.cleanup(key, cleanup);
            folders.putIfAbsent(key, fallbackFolder(chunk.getWorld())); persist(Set.of(key), true);
        }
        if (durabilityRequired.contains(key)) persist(Set.of(key), true);
        entry = ledger.entries().get(key);
        if (!entry.token().equals(readable.get(key)))
            chunk.getPersistentDataContainer().set(revisionKey, PersistentDataType.STRING, ledger.entries().get(key).token());
    }
    private static Path fallbackFolder(World world) {
        return world.getWorldPath().toAbsolutePath().normalize();
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void loaded(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk(); ChunkKey key = key(chunk);
        Purge purge = purges.get(key);
        if (purge != null && purge.token.equals(chunk.getPersistentDataContainer().get(revisionKey, PersistentDataType.STRING))) {
            chunk.getPersistentDataContainer().remove(revisionKey); purgeLoaded.add(key);
        } else if (!ledger.entries().containsKey(key)) chunk.getPersistentDataContainer().remove(revisionKey);
    }
    @Override public void tick() {
        if (closed) return;
        long now = System.currentTimeMillis();
        if (!durabilityRequired.isEmpty()) persist(new ArrayList<>(durabilityRequired), true);
        Optional<BlueMapAPI> instance = BlueMapAPI.getInstance();
        if (instance.isPresent() && (mappedApi != instance.get() || now - lastWorldRefresh >= 1000)) {
            lastWorldRefresh = now; mappedWorlds.clear(); mappedApi = instance.get();
            BlueMapAPI api = instance.get();
            for (ChunkKey key : ledger.entries().keySet()) if (!mappedWorlds.containsKey(key.world())) {
                World world = Bukkit.getWorld(key.world());
                if (world != null) api.getWorld(world).ifPresent(mapped -> mappedWorlds.put(key.world(), mapped));
            }
            mappedWorlds.forEach((id, world) -> ledger.entries().keySet().stream().filter(k -> k.world().equals(id)).forEach(k -> {
                Path folder = world.getSaveFolder().toAbsolutePath().normalize(); if (!folder.equals(folders.put(k, folder))) checkpointDirty.add(k);
            }));
        }
        if (instance.isEmpty()) { mappedWorlds.clear(); mappedApi = null; }
        Map<UUID, BlueMapWorld> worlds = mappedWorlds;
        Set<ChunkKey> newlySaved = new LinkedHashSet<>();
        if (checking != null && checking.isDone()) {
            for (Proof proof : checking.join()) {
                ChunkKey key = proof.revision.chunk();
                if (!proof.source.equals(folders.get(key))) continue;
                if (proof.error != null) { proofErrors.put(key, proof.error); continue; }
                proofErrors.remove(key);
                if (proof.observationOnly) continue;
                if (!proof.regionMembers.isEmpty()) {
                    Region region = key.region(); World world = Bukkit.getWorld(key.world());
                    if (world == null || hasLoadedChunk(world, region) || !deletionStaging.add(region)) continue;
                    deletionBatches.addLast(new DeletionBatch(region, new ArrayDeque<>(proof.regionMembers), proof.source)); continue;
                }
                if (proof.absent) {
                    if (deletionStaging.contains(key.region())) continue;
                    World world = Bukkit.getWorld(key.world());
                    if (world == null || world.isChunkLoaded(key.x(), key.z())) continue;
                    RenderLedger.Entry entry = ledger.entries().get(key); Purge purge = purges.get(key);
                    if (proof.purge) {
                        if (purge == null || !purge.token.equals(proof.revision.token())) continue;
                        if (entry != null && !entry.token().equals(proof.revision.token())) continue;
                    } else if (!ledger.unchanged(proof.revision)
                            || !RenderLedger.sourceDeletionAllowed(restored.contains(key), proof.revision.token().equals(readable.get(key)), false)) continue;
                    boolean cleanup = entry != null ? entry.cleanup() : purge.cleanup;
                    if (!ledger.replaceWithDeletion(proof.revision, UUID.randomUUID().toString(), cleanup)) continue;
                    purges.remove(key); purgeLoaded.remove(key); restored.remove(key); deleted.add(key);
                    readable.put(key, ledger.entries().get(key).token()); newlySaved.add(key);
                    continue;
                }
                if (proof.purge) {
                    Purge purge = purges.get(key);
                    if (purge != null && purge.token.equals(proof.revision.token())) {
                        purges.remove(key); purgeLoaded.remove(key); if (!ledger.entries().containsKey(key)) folders.remove(key); checkpointDirty.add(key);
                    }
                } else if (ledger.matches(proof.revision)) {
                    readable.put(key, proof.revision.token()); newlySaved.add(key);
                    // Cleanup may finish its metadata purge while rendering is disabled or unavailable.
                    // The saved proof is persisted separately and survives removing the temporary token.
                    if (ledger.entries().get(key).cleanup() && !deleted.contains(key)) purges.putIfAbsent(key, new Purge(proof.revision.token(), true));
                }
            }
            checking = null;
        }
        int deletionBudget = Math.max(0, 128 - newlySaved.size());
        while (deletionBudget > 0 && !deletionBatches.isEmpty()) {
            DeletionBatch batch = deletionBatches.peekFirst(); World world = Bukkit.getWorld(batch.region.world());
            if (world == null || hasLoadedChunk(world, batch.region) || (!batch.remaining.isEmpty() && !batch.source.equals(folders.get(batch.remaining.peekFirst().chunk())))) {
                deletionBatches.removeFirst(); deletionStaging.remove(batch.region); continue;
            }
            while (deletionBudget > 0 && !batch.remaining.isEmpty()) {
                deletionBudget--; Revision previous = batch.remaining.removeFirst(); ChunkKey key = previous.chunk();
                RenderLedger.Entry entry = ledger.entries().get(key);
                if (entry == null || !ledger.unchanged(previous) || deleted.contains(key)
                        || !RenderLedger.sourceDeletionAllowed(restored.contains(key), entry.token().equals(readable.get(key)), world.isChunkLoaded(key.x(), key.z()))) continue;
                if (!ledger.replaceWithDeletion(previous, UUID.randomUUID().toString(), entry.cleanup())) continue;
                purges.remove(key); purgeLoaded.remove(key); restored.remove(key); deleted.add(key); proofErrors.remove(key);
                readable.put(key, ledger.entries().get(key).token()); newlySaved.add(key);
            }
            if (batch.remaining.isEmpty()) { deletionBatches.removeFirst(); deletionStaging.remove(batch.region); }
        }
        // One forced append for the bounded proof batch, before any temporary token is purged.
        persist(newlySaved, true);
        try {
            if (instance.isPresent() && settings.get().blueMap) {
                RenderManager current = instance.get().getRenderManager();
                if (manager != current) { discardFlights(); manager = current; bridge = null; }
                if (bridge == null) bridge = new BlueMapRenderBridge(manager);
                for (var iterator = flights.entrySet().iterator(); iterator.hasNext();) {
                    var flight = iterator.next();
                    if (flight.getValue().ticket.finished()) {
                        if (flight.getValue().ticket.successful()) {
                            lastFailure = null;
                            for (Revision revision : flight.getValue().revisions)
                                if (ledger.acknowledge(revision, flight.getKey())) checkpointDirty.add(revision.chunk());
                        } else lastFailure = flight.getKey() + ": " + flight.getValue().ticket.failure();
                        iterator.remove();
                    } else if (!bridge.scheduled(flight.getValue().ticket)) {
                        flight.getValue().ticket.cancel(); lastFailure = flight.getKey() + ": render task was removed; retry pending"; iterator.remove();
                    }
                }
                if (now - lastRetire >= 1000) {
                    lastRetire = now;
                    Map<UUID, List<String>> mapIds = new HashMap<>();
                    worlds.forEach((id, world) -> mapIds.put(id, world.getMaps().stream().map(BlueMapMap::getId).toList()));
                    for (ChunkKey key : new ArrayList<>(ledger.entries().keySet())) {
                        if (deletionStaging.contains(key.region())) continue;
                        List<String> ids = mapIds.get(key.world()); if (ids == null) continue;
                        RenderLedger.Entry entry = ledger.entries().get(key);
                        if (ledger.retire(key, ids)) {
                            if (!deleted.contains(key) && !entry.token().equals("legacy") && !entry.cleanup()) purges.put(key, new Purge(entry.token(), false));
                            deleted.remove(key); restored.remove(key); proofErrors.remove(key);
                            if (!purges.containsKey(key)) folders.remove(key);
                            readable.remove(key); checkpointDirty.add(key);
                        }
                    }
                }
            } else {
                discardFlights(); manager = null; bridge = null;
            }
        } catch (ReflectiveOperationException | RuntimeException failure) { lastFailure = "cannot schedule render: " + failure.getMessage(); }
        // Lost receipts safely replay renders. Keep ordinary checkpoint work bounded per tick.
        persist(checkpointDirty.stream().limit(32).toList(), false);
        if (compacting != null && compacting.isCompletedExceptionally()) {
            detail = "requests retained; snapshot retry pending"; compacting = null;
        }
        if ((compacting == null || compacting.isDone()) && (journal.changesSinceSnapshot() >= 4096 || now - lastCompact >= 60_000)) {
            lastCompact = now; compacting = journal.compactAsync(persistence);
        }
        purgeChunks();
        if (checking == null && now - lastProbe >= 1000) {
            lastProbe = now;
            List<Probe> probes = new ArrayList<>(); List<ChunkKey> keys = new ArrayList<>(ledger.entries().keySet());
            Map<Region, List<Revision>> candidateRegions = new LinkedHashMap<>(); Set<Region> excluded = new HashSet<>(deletionStaging);
            for (var row : ledger.entries().entrySet()) {
                ChunkKey key = row.getKey(); RenderLedger.Entry entry = row.getValue();
                if (deleted.contains(key)) continue;
                if (!folders.containsKey(key) || !RenderLedger.sourceDeletionAllowed(restored.contains(key), entry.token().equals(readable.get(key)), false)) {
                    excluded.add(key.region()); continue;
                }
                candidateRegions.computeIfAbsent(key.region(), ignored -> new ArrayList<>()).add(new Revision(key, entry.token()));
            }
            int regionBudget = 16;
            for (var region : candidateRegions.entrySet()) {
                if (regionBudget == 0) break;
                World world = Bukkit.getWorld(region.getKey().world());
                if (excluded.contains(region.getKey()) || world == null || hasLoadedChunk(world, region.getKey())) continue;
                Revision first = region.getValue().getFirst();
                probes.add(new Probe(first, folders.get(first.chunk()), false, true, List.copyOf(region.getValue()))); regionBudget--;
            }
            for (int n = 0; n < Math.min(128, keys.size()); n++) {
                ChunkKey key = keys.get(Math.floorMod(probeCursor++, keys.size())); RenderLedger.Entry entry = ledger.entries().get(key);
                World world = Bukkit.getWorld(key.world());
                boolean absence = !deleted.contains(key) && world != null && RenderLedger.sourceDeletionAllowed(restored.contains(key), entry.token().equals(readable.get(key)), world.isChunkLoaded(key.x(), key.z()));
                if (folders.containsKey(key) && ((!entry.token().isEmpty() && !entry.token().equals(readable.get(key))) || absence))
                    probes.add(new Probe(new Revision(key, entry.token()), folders.get(key), false, absence));
                else if (entry.token().equals("legacy")) { readable.put(key, "legacy"); checkpointDirty.add(key); }
            }
            List<ChunkKey> purgeKeys = new ArrayList<>(purges.keySet());
            for (int n = 0; n < Math.min(128, purgeKeys.size()); n++) {
                ChunkKey key = purgeKeys.get(Math.floorMod(purgeCursor++, purgeKeys.size()));
                World world = Bukkit.getWorld(key.world()); boolean absence = world != null && !world.isChunkLoaded(key.x(), key.z());
                if ((purgeLoaded.contains(key) || absence) && folders.containsKey(key)) probes.add(new Probe(new Revision(key, purges.get(key).token), folders.get(key), true, absence));
            }
            checking = CompletableFuture.supplyAsync(() -> {
                List<Proof> proven = new ArrayList<>();
                for (Probe probe : probes) {
                    ChunkKey key = probe.revision.chunk();
                    try {
                        if (!probe.regionMembers.isEmpty()) {
                            if (SavedChunkRevision.regionAbsent(probe.folder, key.region().x(), key.region().z()))
                                proven.add(new Proof(probe.revision, false, true, null, false, probe.regionMembers, probe.folder));
                            continue;
                        }
                        if (probe.absenceAllowed && SavedChunkRevision.sourceAbsent(probe.folder, key.x(), key.z())) {
                            proven.add(new Proof(probe.revision, probe.purge, true, null, false, List.of(), probe.folder)); continue;
                        }
                        if (probe.revision.token().isEmpty()) continue;
                        boolean provenSaved = probe.purge
                                ? SavedChunkRevision.purged(probe.folder, key.x(), key.z(), revisionKey.toString(), probe.revision.token())
                                : SavedChunkRevision.matches(probe.folder, key.x(), key.z(), revisionKey.toString(), probe.revision.token());
                        proven.add(new Proof(probe.revision, probe.purge, false, null, !provenSaved, List.of(), probe.folder));
                    } catch (IOException failure) { proven.add(new Proof(probe.revision, probe.purge, false, failure.getMessage(), false, List.of(), probe.folder)); }
                }
                return proven;
            }, reader);
        }
        if (now - lastStatus >= 1000) { lastStatus = now; refreshStatus(instance); }
        if (!settings.get().blueMap || instance.isEmpty()) return;
        if (bridge == null || now - lastFlush < settings.get().blueMapSeconds * 1000L || now - lastRenderAttempt < 1000) return;
        lastRenderAttempt = now;
        try {
            int remaining = settings.get().blueMapRegions; boolean submitted = false;
            for (BlueMapWorld world : worlds.values()) for (BlueMapMap map : world.getMaps()) {
                if (remaining == 0 || flights.containsKey(map.getId()) || map.isFrozen()) continue;
                Map<Region, List<Revision>> regions = new LinkedHashMap<>();
                for (var entry : ledger.entries().entrySet()) {
                    ChunkKey key = entry.getKey(); Revision revision = new Revision(key, entry.getValue().token());
                    if (world != worlds.get(key.world()) || !entry.getValue().token().equals(readable.get(key)) || !ledger.needs(revision, map.getId())) continue;
                    if (deletionStaging.contains(key.region())) continue;
                    if (regions.containsKey(key.region()) || regions.size() < remaining)
                        regions.computeIfAbsent(key.region(), ignored -> new ArrayList<>()).add(revision);
                }
                if (regions.isEmpty()) continue;
                BlueMapRenderBridge.Ticket ticket = bridge.submit(map, regions.keySet().stream().map(r -> new Vector2i(r.x(), r.z())).toList());
                if (ticket != null) {
                    flights.put(map.getId(), new Flight(ticket, regions.values().stream().flatMap(Collection::stream).toList())); remaining -= regions.size(); submitted = true;
                }
            }
            if (submitted) lastFlush = now;
            refreshStatus(instance);
        } catch (ReflectiveOperationException | RuntimeException failure) { lastFailure = "cannot schedule render: " + failure.getMessage(); refreshStatus(instance); }
    }
    private void purgeChunks() {
        int budget = settings.get().perTick;
        for (var row : purges.entrySet()) {
            if (budget == 0 || loading.size() >= settings.get().concurrency) break;
            ChunkKey key = row.getKey(); if (purgeLoaded.contains(key) || loading.contains(key)) continue;
            World world = Bukkit.getWorld(key.world()); if (world == null) continue;
            budget--; boolean wasLoaded = world.isChunkLoaded(key.x(), key.z()); loading.add(key);
            world.getChunkAtAsync(key.x(), key.z(), false).whenComplete((chunk, failure) -> {
                if (closed) return;
                Runnable completion = () -> {
                    loading.remove(key); if (closed || failure != null || chunk == null || !row.getValue().equals(purges.get(key))) return;
                    if (row.getValue().token.equals(chunk.getPersistentDataContainer().get(revisionKey, PersistentDataType.STRING)))
                        chunk.getPersistentDataContainer().remove(revisionKey);
                    purgeLoaded.add(key);
                    if (!wasLoaded && !chunk.isForceLoaded() && chunk.getPluginChunkTickets().isEmpty()
                            && chunk.getPlayersSeeingChunk().isEmpty()) world.unloadChunkRequest(key.x(), key.z());
                };
                if (Bukkit.isPrimaryThread()) completion.run();
                else Bukkit.getScheduler().runTask(plugin, completion);
            });
        }
    }
    private static String journalKey(ChunkKey key) { return key.world() + "/" + key.x() + "/" + key.z(); }
    private static boolean hasLoadedChunk(World world, Region region) {
        for (Chunk chunk : world.getLoadedChunks()) if (Math.floorDiv(chunk.getX(), 32) == region.x() && Math.floorDiv(chunk.getZ(), 32) == region.z()) return true;
        return false;
    }
    private void discardFlights() {
        if (bridge != null) flights.values().forEach(flight -> bridge.cancel(flight.ticket));
        flights.clear();
    }
    private void refreshStatus(Optional<BlueMapAPI> instance) {
        int transitions = 0, awaitingSave = 0, ready = 0, sourceDeletions = 0, unmapped = 0, frozen = 0;
        Map<UUID, Boolean> configured = new HashMap<>();
        for (var world : mappedWorlds.entrySet()) {
            Collection<BlueMapMap> maps = world.getValue().getMaps(); configured.put(world.getKey(), !maps.isEmpty());
            for (BlueMapMap map : maps) if (map.isFrozen()) frozen++;
        }
        for (var row : ledger.entries().entrySet()) {
            ChunkKey key = row.getKey(); RenderLedger.Entry entry = row.getValue();
            if (entry.token().isEmpty()) transitions++;
            else if (!entry.token().equals(readable.get(key))) awaitingSave++;
            else if (deleted.contains(key)) sourceDeletions++;
            else ready++;
            if (!configured.getOrDefault(key.world(), false)) unmapped++;
        }
        StringBuilder status = new StringBuilder(settings.get().blueMap ? (instance.isPresent() ? "connected" : "waiting for BlueMap") : "disabled; render requests retained");
        status.append("; ").append(flights.size()).append(" render batches; ").append(purges.size()).append(" metadata purges");
        if (transitions > 0) status.append("; waiting for completed transitions: ").append(transitions).append(" chunks");
        if (awaitingSave > 0) status.append("; waiting for saved final revisions/recovery: ").append(awaitingSave).append(" chunks");
        if (sourceDeletions > 0) status.append("; removed-source map cleanup queued: ").append(sourceDeletions).append(" chunks");
        if (!deletionStaging.isEmpty()) status.append("; recovering removed regions: ").append(deletionStaging.size());
        if (ready > 0 && flights.isEmpty()) status.append("; saved revisions queued for next render batch: ").append(ready).append(" chunks");
        if (unmapped > 0 && instance.isPresent()) status.append("; waiting for configured map/world: ").append(unmapped).append(" chunks");
        if (frozen > 0) status.append("; frozen maps: ").append(frozen);
        if (manager != null && !manager.isRunning()) status.append("; renderer stopped");
        if (!proofErrors.isEmpty()) status.append("; unreadable source: ").append(proofErrors.values().iterator().next());
        if (lastFailure != null) status.append("; retrying: ").append(lastFailure);
        detail = status.toString();
    }
    private byte[] encode(ChunkKey key) throws IOException {
        RenderLedger.Entry entry = ledger.entries().get(key); Purge purge = purges.get(key);
        if (entry == null && purge == null) return new byte[0];
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeBoolean(entry != null);
            if (entry != null) {
                output.writeUTF(entry.token()); output.writeBoolean(entry.cleanup()); output.writeBoolean(entry.token().equals(readable.get(key)));
                Set<String> acknowledgements = entry.acknowledged(); output.writeInt(acknowledgements.size());
                for (String map : acknowledgements) output.writeUTF(map);
            }
            output.writeBoolean(purge != null);
            if (purge != null) { output.writeUTF(purge.token); output.writeBoolean(purge.cleanup); }
            output.writeUTF(folders.containsKey(key) ? folders.get(key).toString() : "");
            output.writeBoolean(deleted.contains(key));
        }
        return bytes.toByteArray();
    }
    private void restore(String id, byte[] bytes) throws IOException {
        String[] fields = id.split("/");
        ChunkKey key = new ChunkKey(UUID.fromString(fields[0]), Integer.parseInt(fields[1]), Integer.parseInt(fields[2]));
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readBoolean()) {
                String token = input.readUTF(); boolean cleanup = input.readBoolean(), saved = input.readBoolean();
                int count = input.readInt(); if (count < 0 || count > 10_000) throw new IOException("Invalid map receipt count");
                List<String> maps = new ArrayList<>(); for (int n = 0; n < count; n++) maps.add(input.readUTF());
                ledger.restore(key, token, maps); ledger.cleanup(key, cleanup); if (saved) readable.put(key, token);
            }
            if (input.readBoolean()) purges.put(key, new Purge(input.readUTF(), input.readBoolean()));
            String folder = input.readUTF(); if (!folder.isEmpty()) folders.put(key, Path.of(folder));
            if (input.available() > 0 && input.readBoolean()) deleted.add(key);
            if (input.read() != -1) throw new IOException("Trailing BlueMap state data");
        }
    }
    private void persist(Collection<ChunkKey> keys, boolean force) {
        if (keys.isEmpty()) return;
        try {
            Map<String, byte[]> changes = new LinkedHashMap<>(); for (ChunkKey key : keys) changes.put(journalKey(key), encode(key));
            journal.putAll(changes, force); checkpointDirty.removeAll(keys); durabilityRequired.removeAll(keys);
        } catch (IOException failure) {
            checkpointDirty.addAll(keys); if (force) durabilityRequired.addAll(keys);
            throw new UncheckedIOException("Cannot persist BlueMap requests", failure);
        }
    }
    @Override public int cleanupPending() {
        Set<ChunkKey> keys = new HashSet<>(); purges.forEach((key, purge) -> { if (purge.cleanup) keys.add(key); });
        ledger.entries().forEach((key, entry) -> { if (entry.cleanup() && !entry.token().equals(readable.get(key))) keys.add(key); }); return keys.size();
    }
    @Override public boolean isEnabled() { return settings.get().blueMap; }
    @Override public boolean requiresRecovery(UUID world, int x, int z) {
        return requiresRecovery(world, x, z, null);
    }
    @Override public boolean requiresRecovery(UUID world, int x, int z, String marker) {
        ChunkKey key = new ChunkKey(world, x, z); RenderLedger.Entry entry = ledger.entries().get(key);
        return ledger.requiresMarkerRecovery(key, marker, entry != null && entry.token().equals(readable.get(key)), deleted.contains(key));
    }
    @Override public int pending() { return ledger.regions(); }
    @Override public String status() { return detail; }
    @Override public void close() {
        closed = true; HandlerList.unregisterAll(this); if (bridge != null) flights.values().forEach(flight -> bridge.cancel(flight.ticket));
        reader.shutdownNow(); persist(new ArrayList<>(checkpointDirty), true);
        try { journal.close(); } catch (IOException failure) { throw new UncheckedIOException("Cannot close BlueMap journal", failure); }
        finally { persistence.shutdown(); }
    }
}
