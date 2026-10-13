package dev.trueseasons.migration;

import dev.trueseasons.TrueSeasonsPlugin;
import dev.trueseasons.api.*;
import dev.trueseasons.snow.SeasonalSnowService;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.event.*;
import org.bukkit.event.world.*;

/** One processing framework for seasonal jobs AND uninstall restoration. */
public final class ChunkMigrationEngine implements Listener, AutoCloseable {
    public static final class Job {
        public final World world;
        public final Set<ChunkPos> known = new LinkedHashSet<>();
        final ArrayDeque<ChunkPos> queue = new ArrayDeque<>();
        final Set<ChunkPos> queued = new HashSet<>();
        final Set<ChunkPos> done = new HashSet<>(), flight = new HashSet<>();
        final Set<ChunkPos> unavailable = new HashSet<>();
        final Set<ChunkPos> observedFull = new HashSet<>(), indexFailures = new HashSet<>();
        final Set<ChunkPos> diskKnown = new HashSet<>(), newlyGenerated = new HashSet<>();
        final Map<ChunkPos,Integer> columns = new HashMap<>();
        final Map<ChunkPos, Long> failures = new LinkedHashMap<>();
        final Map<ChunkPos, Integer> nextStep = new HashMap<>();
        long scanAt;
        boolean scanning, scanned, completed;
        int generation, removed, placed;
        String error;
        Job(World world) { this.world = world; }
        boolean observeIndexed(ChunkPos pos,boolean full) {
            boolean added=known.add(pos);
            if(!full) {
                if(!observedFull.contains(pos) && !done.contains(pos) && !flight.contains(pos)) unavailable.add(pos);
                return false;
            }
            boolean promoted=unavailable.remove(pos);
            if(added||promoted) { if(!done.contains(pos)&&queued.add(pos))queue.addLast(pos); completed=false; }
            return added||promoted;
        }
        boolean observeFull(ChunkPos pos) {
            observedFull.add(pos); boolean changed=observeIndexed(pos,true);
            if(indexFailures.remove(pos)) {
                failures.remove(pos); if(!done.contains(pos)&&queued.add(pos))queue.addLast(pos); completed=false; changed=true;
            }
            return changed;
        }
        void observeCheckpoint(ChunkPos pos,SavedChunkState saved,String target,boolean cleanup,boolean eligible,boolean recovery) {
            observeIndexed(pos,true);
            if(recovery) { done.remove(pos); completed=false; if(queued.add(pos))queue.addLast(pos); return; }
            if(observedFull.contains(pos))return; // A live observation supersedes an older disk snapshot.
            if(saved.matches(target,cleanup)||!cleanup&&!eligible) {
                done.add(pos); columns.remove(pos); nextStep.remove(pos); queued.remove(pos);
                failures.remove(pos); indexFailures.remove(pos);
            } else {
                done.remove(pos); completed=false; if(queued.add(pos))queue.addLast(pos);
                columns.remove(pos);
                if(saved.pending()&&target.equals(saved.pendingVersion())&&saved.columns()>0&&saved.columns()<=256)
                    columns.put(pos,saved.columns());
            }
        }
        public int total() { return known.size() - unavailable.size(); }
        public int completed() { return done.size(); }
        public int errors() { return failures.size(); }
        public boolean scanning() { return scanning; }
        void markComplete(ChunkPos pos) {
            done.add(pos); queued.remove(pos); nextStep.remove(pos); columns.remove(pos);
        }
        private boolean accounted() { return queued.isEmpty() && flight.isEmpty() && failures.isEmpty()
                && nextStep.isEmpty() && columns.isEmpty() && done.size() + unavailable.size() == known.size(); }
        public boolean finished() { return scanned && !scanning && accounted(); }
        public boolean reportedFinished() { return scanned && accounted() && (completed || !scanning); }
        boolean regionReady(int regionX, int regionZ) {
            if (!scanned || scanning) return false;
            long firstX = (long) regionX * 32, firstZ = (long) regionZ * 32;
            if (firstX < Integer.MIN_VALUE || firstX + 31 > Integer.MAX_VALUE
                    || firstZ < Integer.MIN_VALUE || firstZ + 31 > Integer.MAX_VALUE) return false;
            // A region query is bounded to its 1,024 slots, regardless of world size.
            for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
                ChunkPos pos = new ChunkPos((int) firstX + x, (int) firstZ + z);
                if (!known.contains(pos) || unavailable.contains(pos)) continue;
                if (!done.contains(pos) || flight.contains(pos) || failures.containsKey(pos)
                        || nextStep.containsKey(pos) || columns.containsKey(pos) || queued.contains(pos)) return false;
            }
            return true;
        }
        public int removed() { return removed; }
        public int placed() { return placed; }
        public boolean indexingSavedProgress() { return !scanned; }
        public Set<ChunkPos> unfinishedTerrain() { return Collections.unmodifiableSet(unavailable); }
        public int transitioning() { return nextStep.size(); }
        public double percent() {
            if(!scanned) return 0;
            double value=total()==0?100:100.0*completed()/total();
            return accounted()&&(completed||!scanning)?100:Math.min(99.9,value);
        }
        long coveredColumns() { return done.size()*256L+columns.values().stream().mapToLong(Integer::longValue).sum(); }
        public String error() { return error; }
    }
    private record Ready(Job job, ChunkPos pos, Chunk chunk, boolean loadedBefore, int generation, Throwable error) {}
    private record Priority(Job job, ChunkPos pos) {}
    private record Center(UUID world, ChunkPos pos) {}
    private final TrueSeasonsPlugin plugin;
    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private final ExecutorService scanner = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("TrueSeasons-index").factory());
    private final GeneratedChunkIndex index = new GeneratedChunkIndex();
    private final ArrayDeque<Priority> priorityQueue = new ArrayDeque<>();
    private final Set<Priority> priorityQueued = new HashSet<>();
    private final Map<UUID, Set<ChunkPos>> neighborhoods = new HashMap<>();
    private Map<UUID, Center> centers = Map.of();
    private boolean refreshPlayerAreas;
    private volatile boolean closed;
    private int inFlight, rotating, tickNumber;
    private int applicationTick = -1, applications;
    private long applicationNanos;
    private final TpsPoller tps = new TpsPoller(() -> Bukkit.getTPS()[0]);
    public ChunkMigrationEngine(TrueSeasonsPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }
    public Collection<Job> jobs() { return Collections.unmodifiableCollection(jobs.values()); }
    public double currentTps() { return tps.read(System.currentTimeMillis()); }
    /** No loads or disk reads: render readiness follows the current migration generation. */
    public boolean regionReady(UUID world, int regionX, int regionZ) {
        Job job = jobs.get(world);
        return !closed && job != null && Bukkit.getWorld(world) == job.world && job.regionReady(regionX, regionZ);
    }
    public void invalidatePlayerAreas() { refreshPlayerAreas=true; }
    public void restart() {
        priorityQueue.clear(); priorityQueued.clear(); neighborhoods.clear(); centers = Map.of();
        for (Job job : jobs.values()) {
            job.generation++; job.done.clear(); job.queue.clear(); job.queued.clear(); job.nextStep.clear(); job.columns.clear();
            job.known.stream().filter(pos -> !job.unavailable.contains(pos)).forEach(pos -> enqueue(job, pos));
            job.failures.clear(); job.indexFailures.clear(); job.completed = false; job.error = null;
            job.removed = 0; job.placed = 0;
            // Existing requests finish against their captured generation and requeue.
            scan(job);
            Bukkit.getPluginManager().callEvent(new SeasonMigrationEvent(job.world, plugin.state().cleanup, SeasonMigrationEvent.Phase.START));
        }
    }
    public void addWorld(World world) {
        if (!plugin.eligible(world) && !plugin.state().managedWorlds.containsKey(world.getUID().toString())) return;
        if (jobs.containsKey(world.getUID())) return;
        Job job = new Job(world); jobs.put(world.getUID(), job);
        plugin.state().managedWorlds.put(world.getUID().toString(), world.getName()); plugin.persist();
        for (Chunk chunk : world.getLoadedChunks()) observeLoaded(job,chunk);
        scan(job);
        Bukkit.getPluginManager().callEvent(new SeasonMigrationEvent(world, plugin.state().cleanup, SeasonMigrationEvent.Phase.START));
    }
    private void add(Job job, ChunkPos pos) {
        job.observeFull(pos);
    }
    private void observeLoaded(Job job,Chunk chunk) {
        ChunkPos pos=new ChunkPos(chunk.getX(),chunk.getZ()); add(job,pos);
        boolean cleanup=plugin.state().cleanup||plugin.state().clean;
        boolean complete=cleanup?!plugin.snow().hasMetadata(chunk)
            && !chunk.getPersistentDataContainer().has(new NamespacedKey(plugin,"bluemap_revision"))
            : !plugin.eligible(job.world)||plugin.snow().isComplete(chunk,plugin.version());
        NamespacedKey mapKey=new NamespacedKey(plugin,"bluemap_revision");
        String marker=chunk.getPersistentDataContainer().has(mapKey,org.bukkit.persistence.PersistentDataType.STRING)
            ?chunk.getPersistentDataContainer().get(mapKey,org.bukkit.persistence.PersistentDataType.STRING):null;
        if(complete&&!plugin.integrations().requiresRecovery(job.world.getUID(),pos.x(),pos.z(),marker)) {
            job.markComplete(pos);
        } else {
            job.done.remove(pos); job.completed=false; if(!job.flight.contains(pos))enqueue(job,pos);
            try {
                int cursor=plugin.snow().coveredColumns(chunk,plugin.version());
                if(cursor>0&&cursor<=256)job.columns.put(pos,cursor); else job.columns.remove(pos);
            } catch(java.io.IOException failure) { fail(job,pos,failure); }
        }
    }
    private void enqueue(Job job, ChunkPos pos) { if (job.queued.add(pos)) job.queue.addLast(pos); }
    private void priority(Job job, ChunkPos pos) {
        Priority request = new Priority(job, pos);
        if (job.known.contains(pos) && !job.done.contains(pos) && priorityQueued.add(request)) priorityQueue.addLast(request);
    }
    /** Adds a nearest-first neighborhood hint. Only the saved index is eligible. */
    public void prioritizeAround(World world, ChunkPos center) {
        Job job = jobs.get(world.getUID()); if (job == null) return;
        Set<ChunkPos> local = neighborhoods.computeIfAbsent(world.getUID(), ignored -> new HashSet<>());
        for (ChunkPos pos : PlayerNeighborhood.around(center, plugin.settings().playerRadius)) {
            local.add(pos); priority(job, pos);
        }
    }
    private boolean nearby(Job job, ChunkPos pos) { return neighborhoods.getOrDefault(job.world.getUID(), Set.of()).contains(pos); }
    private void refreshNeighborhoods() {
        Map<UUID, Center> current = new HashMap<>();
        for (var player : Bukkit.getOnlinePlayers()) {
            var location = player.getLocation();
            current.put(player.getUniqueId(), new Center(player.getWorld().getUID(), new ChunkPos(location.getBlockX() >> 4, location.getBlockZ() >> 4)));
        }
        if (current.equals(centers)&&!refreshPlayerAreas) return;
        refreshPlayerAreas=false;
        centers = current; priorityQueue.clear(); priorityQueued.clear(); neighborhoods.clear();
        // Interleave equal-distance rings for all players instead of exhausting player one's area first.
        for (int ring = 0; ring <= plugin.settings().playerRadius; ring++) for (Center center : current.values()) {
            Job job = jobs.get(center.world); if (job == null) continue;
            Set<ChunkPos> local = neighborhoods.computeIfAbsent(center.world, ignored -> new HashSet<>());
            for (ChunkPos pos : PlayerNeighborhood.around(center.pos, ring)) {
                if (Math.max(Math.abs(pos.x() - center.pos.x()), Math.abs(pos.z() - center.pos.z())) != ring) continue;
                local.add(pos); priority(job, pos);
            }
        }
    }
    private void scan(Job job) {
        if (job.scanning || closed) return;
        job.scanning = true; job.scanAt = System.currentTimeMillis();
        var regionDirectory = job.world.getWorldPath().resolve("region");
        scanner.submit(() -> {
            GeneratedChunkIndex.Snapshot chunks; Throwable error = null;
            try { chunks = index.scanSnapshot(regionDirectory); }
            catch (Throwable failure) { chunks = null; error = failure; }
            GeneratedChunkIndex.Snapshot result = chunks; Throwable failure = error;
            dispatch(() -> {
                if (jobs.get(job.world.getUID()) != job) return;
                job.scanning = false;
                if (failure != null) { job.scanned = false; job.error = failure.getMessage(); plugin.getLogger().warning("Index failed for " + job.world.getName() + ": " + job.error); }
                else {
                    Set<ChunkPos> present=new HashSet<>(result.generated()); present.addAll(result.unfinished()); present.addAll(result.unreadable().keySet());
                    for(ChunkPos pos:new ArrayList<>(job.diskKnown)) if(!present.contains(pos)&&!job.flight.contains(pos)
                            && !job.newlyGenerated.contains(pos)&&!job.world.isChunkLoaded(pos.x(),pos.z())) {
                        job.known.remove(pos); job.diskKnown.remove(pos); job.done.remove(pos); job.unavailable.remove(pos);
                        job.observedFull.remove(pos); job.queued.remove(pos); job.nextStep.remove(pos);
                        job.columns.remove(pos); job.failures.remove(pos); job.indexFailures.remove(pos);
                    }
                    job.diskKnown.addAll(present); job.newlyGenerated.removeAll(result.generated());
                    result.generated().forEach(pos -> {
                        job.observeIndexed(pos,true);
                        if(job.indexFailures.remove(pos)) { job.failures.remove(pos); if(!job.done.contains(pos))enqueue(job,pos); }
                        SavedChunkState saved=result.states().get(pos);
                        boolean recovering=plugin.integrations().requiresRecovery(job.world.getUID(),pos.x(),pos.z(),saved.mapRevision());
                        job.observeCheckpoint(pos,saved,plugin.version(),plugin.state().cleanup||plugin.state().clean,plugin.eligible(job.world),recovering);
                    });
                    result.unfinished().forEach(pos -> {
                        job.observeIndexed(pos,false);
                        if(!job.observedFull.contains(pos)&&job.indexFailures.remove(pos))job.failures.remove(pos);
                    });
                    result.unreadable().forEach((pos,reason) -> {
                        if(job.observedFull.contains(pos)||job.done.contains(pos))return;
                        job.known.add(pos); job.unavailable.remove(pos); job.indexFailures.add(pos);
                        job.failures.put(pos,System.currentTimeMillis()+30_000); job.completed=false;
                    });
                    job.queue.removeIf(pos -> job.done.contains(pos)||!job.known.contains(pos));
                    job.scanned = true; job.error = job.indexFailures.isEmpty()?null:job.indexFailures.size()+" saved chunks have unreadable generation metadata";
                }
            });
        });
    }
    private void dispatch(Runnable action) {
        if (closed || !plugin.isEnabled()) return;
        try { Bukkit.getScheduler().runTask(plugin, () -> { if (!closed) action.run(); }); }
        catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { /* shutdown */ }
    }
    public void tick() {
        if (closed) return;
        tickNumber++;
        long now = System.currentTimeMillis();
        for (World world : Bukkit.getWorlds()) addWorld(world);
        if (tickNumber % 5 == 1) refreshNeighborhoods();
        for (Job job : jobs.values()) {
            if (now - job.scanAt >= plugin.settings().rescanSeconds * 1000L) scan(job);
            for (var iterator = job.failures.entrySet().iterator(); iterator.hasNext();) {
                var failure = iterator.next();
                if (failure.getValue() <= now && !job.indexFailures.contains(failure.getKey())) { enqueue(job, failure.getKey()); if (nearby(job, failure.getKey())) priority(job, failure.getKey()); iterator.remove(); }
            }
        }
        // Newly discovered/generated chunks in restored mode still need verification,
        // but must never resume seasonal placement without an explicit season command.
        if (plugin.state().clean && !plugin.state().cleanup && allFinished()) return;
        if(allFinished()) { checkCompletion(); return; }
        if (!allFinished() && !plugin.state().paused && (plugin.state().cleanup || plugin.settings().enabled))
            cachedLowTps = currentTps() < plugin.settings().minTps;
        if (plugin.state().paused || (!plugin.state().cleanup && !plugin.settings().enabled) || lowTps()) return;
        advanceLocalCoverage();
        long deadline = System.nanoTime() + (long) (plugin.settings().millis * 1_000_000);
        // Reserve every fourth tick for the background queue, including one-slot setups.
        if (plugin.settings().prioritize && tickNumber % 4 != 0) prioritizeLoaded();
        List<Job> worlds = new ArrayList<>(jobs.values());
        int attempts = 0;
        while (!worlds.isEmpty() && inFlight < plugin.settings().concurrency && attempts++ < worlds.size() * plugin.settings().concurrency
                && System.nanoTime() < deadline) {
            Job job = worlds.get(Math.floorMod(rotating++, worlds.size()));
            ChunkPos pos = job.queue.pollFirst(); if (pos == null) continue;
            if(!job.queued.remove(pos))continue;
            if (!job.known.contains(pos)||job.done.contains(pos) || job.unavailable.contains(pos) || job.flight.contains(pos) || job.failures.containsKey(pos)) continue;
            if(waitingForCoverage(job,pos)) { enqueue(job,pos); continue; }
            if (nearby(job, pos) && job.nextStep.getOrDefault(pos, 0) > Bukkit.getCurrentTick()) { enqueue(job, pos); continue; }
            load(job, pos);
        }
        checkCompletion();
    }
    private boolean cachedLowTps;
    private boolean lowTps() { return cachedLowTps; }
    private void prioritizeLoaded() {
        int candidates = priorityQueue.size();
        int started = 0;
        while (candidates-- > 0 && !priorityQueue.isEmpty() && inFlight < plugin.settings().concurrency && started < plugin.settings().perTick) {
            Priority request = priorityQueue.removeFirst(); priorityQueued.remove(request);
            Job job = request.job; ChunkPos pos = request.pos;
            if (jobs.get(job.world.getUID()) != job || !job.known.contains(pos)||job.done.contains(pos) || job.unavailable.contains(pos) || job.flight.contains(pos) || job.failures.containsKey(pos)) continue;
            if(waitingForCoverage(job,pos)) { priority(job,pos); continue; }
            if (job.nextStep.getOrDefault(pos, 0) > Bukkit.getCurrentTick()) { priority(job, pos); continue; }
            load(job, pos); started++;
        }
    }
    private void load(Job job, ChunkPos pos) {
        if (Bukkit.getWorld(job.world.getUID()) == null) { enqueue(job, pos); return; }
        try {
            boolean loaded = job.world.isChunkLoaded(pos.x(), pos.z());
            int generation = job.generation;
            job.queued.remove(pos); // A priority load consumes the same logical request as the background queue.
            job.flight.add(pos); inFlight++;
            // Every load explicitly forbids generation. Never use getChunkAt(x,z).
            job.world.getChunkAtAsync(pos.x(), pos.z(), false, false).whenComplete((chunk, failure) -> {
                Runnable apply = () -> finish(new Ready(job, pos, chunk, loaded, generation, failure));
                // Paper completes chunk callbacks on the main thread. Apply in that
                // callback's load lease: adding plugin tickets can generate neighbors.
                if (!closed && plugin.isEnabled() && Bukkit.isPrimaryThread()) apply.run();
                else dispatch(apply);
            });
        } catch (RuntimeException failure) {
            if (job.flight.remove(pos)) inFlight--;
            fail(job, pos, failure);
        }
    }
    private void finish(Ready request) {
        Job job = request.job; ChunkPos pos = request.pos;
        try {
            if (jobs.get(job.world.getUID()) != job) return;
            // A superseded request cannot defer the new season on an old failure.
            if (request.generation != job.generation) { enqueue(job, pos); if (nearby(job, pos)) priority(job, pos); return; }
            if (request.error != null) throw new CompletionException(request.error);
            if (request.chunk == null) {
                fail(job,pos,new IllegalStateException("Indexed FULL chunk could not be loaded; awaiting disk revalidation")); return;
            }
            job.observedFull.add(pos); job.unavailable.remove(pos);
            if (job.done.contains(pos)) return;
            if (!job.world.isChunkLoaded(pos.x(), pos.z()) || plugin.state().paused || lowTps()) { enqueue(job, pos); if (nearby(job, pos)) priority(job, pos); return; }
            int currentTick = Bukkit.getCurrentTick();
            if (applicationTick != currentTick) { applicationTick = currentTick; applications = 0; applicationNanos = 0; }
            if (applications >= plugin.settings().perTick || applicationNanos >= plugin.settings().millis * 1_000_000) {
                enqueue(job, pos); if (nearby(job, pos)) priority(job, pos); return;
            }
            long started = System.nanoTime();
            boolean complete = apply(job, request.chunk);
            applications++; applicationNanos += System.nanoTime() - started;
            if(request.generation!=job.generation) { enqueue(job,pos); if(nearby(job,pos))priority(job,pos); return; }
            if (complete) job.markComplete(pos);
            else {
                job.nextStep.put(pos, Bukkit.getCurrentTick() + plugin.settings().snowStepTicks);
                enqueue(job, pos); if (nearby(job, pos)) priority(job, pos);
            }
            updateCoverage();
        } catch (Exception failure) { fail(job, pos, failure); }
        finally {
            job.flight.remove(pos); inFlight--;
            if (request.chunk != null && Bukkit.getWorld(job.world.getUID()) != null) {
                if (!request.loadedBefore && request.chunk.getPlayersSeeingChunk().isEmpty() && !request.chunk.isForceLoaded()
                        && request.chunk.getPluginChunkTickets().isEmpty()) job.world.unloadChunkRequest(pos.x(), pos.z());
            }
        }
    }
    private boolean apply(Job job, Chunk chunk) throws Exception {
        boolean cleanup = plugin.state().cleanup || plugin.state().clean;
        if (cleanup && !plugin.integrations().isEnabled())
            chunk.getPersistentDataContainer().remove(new NamespacedKey(plugin, "bluemap_revision"));
        if (!cleanup && !plugin.eligible(job.world)) return true;
        String version = plugin.version();
        if (cleanup && !plugin.snow().hasMetadata(chunk)) { plugin.integrations().chunkCompleted(chunk, true); return true; }
        if (!cleanup && plugin.snow().isComplete(chunk, version)) { plugin.integrations().chunkCompleted(chunk, false); return true; }
        // Persist integration intent at the first physical edit. Seasonal metadata
        // alone does not change a map tile and must not request a region render.
        ChunkPos pos = new ChunkPos(chunk.getX(), chunk.getZ());
        boolean gradual = gradual(job,pos);
        int cursor=plugin.snow().coveredColumns(chunk,version);
        int step=gradual?WinterCoveragePolicy.applicationBudget(cursor,globalCoverageColumns(),plugin.settings().snowStepColumns):256;
        if(step==0) { job.columns.put(pos,cursor); return false; }
        SeasonalSnowService.Result result = plugin.snow().applyStep(chunk, plugin.currentSeason(), version, cleanup,Math.min(256,step),
            () -> plugin.integrations().chunkUpdated(chunk));
        if(!result.complete()) job.columns.put(pos,plugin.snow().coveredColumns(chunk,version));
        job.removed += result.removed(); job.placed += result.placed();
        if (result.conflicts() > 0) throw new IllegalStateException(result.conflicts() + " ambiguous snow layers; ownership retained");
        if (result.complete()) {
            plugin.integrations().chunkCompleted(chunk, cleanup);
            Bukkit.getPluginManager().callEvent(new SeasonChunkUpdatedEvent(chunk, cleanup ? "CLEAN" : version, cleanup, result.changed()));
        }
        return result.complete();
    }
    public boolean worldFinished(World world) { Job job=jobs.get(world.getUID()); return job!=null&&job.finished(); }
    public int globalCoverageColumns() { return plugin.state().winterVersion.equals(plugin.version())?plugin.state().winterColumns:0; }
    private boolean gradual(Job job,ChunkPos pos) {
        return !plugin.state().cleanup&&!plugin.state().clean&&plugin.currentSeason()==Season.WINTER
            && plugin.settings().snow&&plugin.settings().snowTransition&&nearby(job,pos);
    }
    private boolean waitingForCoverage(Job job,ChunkPos pos) {
        int cursor=job.columns.getOrDefault(pos,0);
        return cursor<256&&gradual(job,pos)&&globalCoverageColumns()<256&&cursor>=globalCoverageColumns();
    }
    private void advanceLocalCoverage() {
        if(plugin.currentSeason()!=Season.WINTER||plugin.state().cleanup||plugin.state().clean||!plugin.settings().snowTransition
                ||globalCoverageColumns()>=256||tickNumber%plugin.settings().snowStepTicks!=0)return;
        List<Job> active=jobs.values().stream().filter(j -> plugin.eligible(j.world)).toList();
        if(active.isEmpty()||active.stream().anyMatch(j -> !j.scanned||j.scanning||!j.failures.isEmpty()))return;
        int minimumLocal=-1;
        for(Job job:active) for(ChunkPos pos:job.known) {
            if(job.done.contains(pos)||job.unavailable.contains(pos))continue;
            if(!nearby(job,pos))return;
            int cursor=job.columns.getOrDefault(pos,0);
            minimumLocal=minimumLocal<0?cursor:Math.min(minimumLocal,cursor);
        }
        // With only local work remaining, advance one shared stage after every
        // chunk catches up. This avoids the local-cap/global-average deadlock.
        int next=WinterCoveragePolicy.nextStage(globalCoverageColumns(),plugin.settings().snowStepColumns,false,minimumLocal);
        if(next>globalCoverageColumns()) { plugin.state().winterColumns=next; plugin.persist(); }
    }
    private void updateCoverage() {
        if(plugin.currentSeason()!=Season.WINTER||plugin.state().cleanup||plugin.state().clean||globalCoverageColumns()>=256)return;
        List<Job> active=jobs.values().stream().filter(job -> plugin.eligible(job.world)).toList();
        if(active.isEmpty()||active.stream().anyMatch(job -> !job.scanned))return;
        long total=active.stream().mapToLong(job -> job.total()*256L).sum();
        long covered=active.stream().mapToLong(Job::coveredColumns).sum();
        int value=total==0?256:(int)Math.min(256,covered*256/total);
        if(active.stream().anyMatch(job -> !job.finished()))value=Math.min(255,value);
        if(value>plugin.state().winterColumns) { plugin.state().winterColumns=value; plugin.persist(); }
    }
    private void fail(Job job, ChunkPos pos, Throwable failure) {
        job.failures.put(pos, System.currentTimeMillis() + 30_000);
        plugin.getLogger().warning("Chunk " + job.world.getName() + " " + pos + " deferred: " + failure.getMessage());
    }
    private void checkCompletion() {
        updateCoverage();
        for (Job job : jobs.values()) if (job.finished() && !job.completed) {
            job.queue.clear(); // Drop consumed priority/old callback slots once all logical work has settled.
            job.completed = true;
            plugin.getLogger().info((plugin.state().cleanup ? "Cleanup" : "Migration") + " complete: " + job.world.getName() + " (" + job.total() + " chunks)");
            Bukkit.getPluginManager().callEvent(new SeasonMigrationEvent(job.world, plugin.state().cleanup, SeasonMigrationEvent.Phase.COMPLETE));
        }
        if (plugin.state().cleanup && allFinished() && plugin.weather().activeStorms() == 0 && plugin.integrations().cleanupPending() == 0) {
            plugin.state().cleanup = false; plugin.state().clean = true; plugin.persist();
            plugin.getLogger().info("Cleanup complete. Stop the server normally, then remove TrueSeasons.");
        }
    }
    public boolean allFinished() {
        if (jobs.size() < plugin.state().managedWorlds.size()) return false;
        return jobs.values().stream().allMatch(Job::finished);
    }
    public boolean worldEditsComplete() {
        return jobs.size()>=plugin.state().managedWorlds.size()&&jobs.values().stream().allMatch(Job::reportedFinished);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(ChunkLoadEvent event) {
        Job job = jobs.get(event.getWorld().getUID()); if (job == null) return;
        Chunk chunk = event.getChunk(); ChunkPos pos = new ChunkPos(chunk.getX(), chunk.getZ());
        observeLoaded(job,chunk);
        boolean restoring = plugin.state().cleanup || plugin.state().clean;
        if ((!restoring && plugin.eligible(job.world)&&!plugin.snow().isComplete(chunk, plugin.version()))
                || (restoring && plugin.snow().hasMetadata(chunk))) {
            job.done.remove(pos); job.completed = false; if(!job.flight.contains(pos))enqueue(job, pos);
        }
        if (closed || job.flight.contains(pos) || plugin.state().paused || lowTps()
                || (!plugin.state().cleanup && !plugin.settings().enabled)) return;
        // Newly visible chunks join the same radius queue, respecting its application budget.
        boolean nearPlayer = event.getWorld().getPlayers().stream().anyMatch(player ->
            Math.abs((player.getLocation().getBlockX() >> 4) - pos.x()) <= plugin.settings().playerRadius
                && Math.abs((player.getLocation().getBlockZ() >> 4) - pos.z()) <= plugin.settings().playerRadius);
        if (nearPlayer) {
            neighborhoods.computeIfAbsent(event.getWorld().getUID(), ignored -> new HashSet<>()).add(pos);
            priority(job, pos);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPopulate(ChunkPopulateEvent event) {
        // Generation belongs to Minecraft/player exploration. Requeue the finished
        // result even when its earlier protochunk was already present in the index.
        Job job = jobs.get(event.getWorld().getUID()); if (job == null) return;
        ChunkPos pos = new ChunkPos(event.getChunk().getX(), event.getChunk().getZ());
        add(job, pos); job.unavailable.remove(pos); job.done.remove(pos);
        job.newlyGenerated.add(pos);
        job.completed = false; enqueue(job, pos);
        if (nearby(job, pos)) priority(job, pos);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) { addWorld(event.getWorld()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        Job job = jobs.remove(event.getWorld().getUID());
        if (job != null) job.generation++;
    }
    @Override public void close() {
        closed = true; scanner.shutdownNow(); HandlerList.unregisterAll(this);
    }
}
