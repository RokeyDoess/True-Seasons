package dev.trueseasons.snow;

import dev.trueseasons.TrueSeasonsPlugin;
import dev.trueseasons.api.Season;
import dev.trueseasons.migration.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.*;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;

/** Disposable, no-player, no-BlueMap completed-winter performance comparison. */
public final class SnowPerformanceRuntimeProbe extends JavaPlugin implements Listener {
    private TrueSeasonsPlugin seasons;
    private World world;
    private boolean prepare, loaded, stopping, skipFirstSampleTick;
    private String version, ownershipBefore;
    private int stable, warmupTicks, sampleTicks;
    private long sampleStart, deadline;
    private Set<ChunkPos> originalHeaders, originalFull;
    private final Map<Chunk, Boolean> originalForced = new LinkedHashMap<>();
    private final List<Double> tickMs = new ArrayList<>();
    private final List<String> observations = new ArrayList<>();

    @Override public void onEnable() {
        try {
            if (!Files.exists(Path.of("snow-performance-clone.txt")))
                throw new IllegalStateException("Dedicated benchmark clone marker required");
            prepare = System.getProperty("trueseasons.benchmark.mode", "").equals("prepare");
            if (!prepare && !System.getProperty("trueseasons.benchmark.mode", "").equals("capture"))
                throw new IllegalStateException("Explicit prepare/capture mode required");
            seasons = (TrueSeasonsPlugin) Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("TrueSeasons"));
            world = Objects.requireNonNull(Bukkit.getWorld("world"));
            Bukkit.getPluginManager().registerEvents(this, this);
            require(Bukkit.getOnlinePlayers().isEmpty(), "No players permitted");
            require(Bukkit.getPluginManager().getPlugin("BlueMap") == null, "BlueMap must be absent");
            require(seasons.currentSeason() == Season.WINTER && !seasons.state().cleanup && !seasons.state().clean,
                "Explicit active winter checkpoint required");
            version = seasons.version();
            deadline = System.nanoTime() + 12L * 60 * 1_000_000_000L;
            CompletableFuture.supplyAsync(() -> {
                try {
                    GeneratedChunkIndex index = new GeneratedChunkIndex();
                    var snapshot = index.scanSnapshot(world.getWorldPath().resolve("region"));
                    return new Object[]{new HashSet<>(index.scan(world.getWorldPath().resolve("region"))), snapshot};
                } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
            }).whenComplete((state, error) -> Bukkit.getScheduler().runTask(this, () -> {
                try {
                    if (error != null) throw new IllegalStateException("Saved FULL preflight failed", error);
                    @SuppressWarnings("unchecked") Set<ChunkPos> headers = (Set<ChunkPos>) state[0];
                    var snapshot = (GeneratedChunkIndex.Snapshot) state[1];
                    originalHeaders = headers; originalFull = new HashSet<>(snapshot.generated());
                    require(snapshot.unreadable().isEmpty(), "All saved chunk statuses readable");
                    // Force-loaded ticket neighbors must already be generated, including a three-chunk halo.
                    for (int x = -11; x <= 10; x++) for (int z = -11; z <= 10; z++)
                        require(originalFull.contains(new ChunkPos(x, z)), "FULL ticket halo at " + x + "," + z);
                    if (!prepare) for (var entry : snapshot.states().entrySet())
                        require(entry.getValue().matches(version, false), "Saved completed checkpoint at " + entry.getKey());
                    note("PRECHECK headers=" + headers.size() + " full=" + originalFull.size() + " stamp=" + version);
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "spark profiler cancel");
                    Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
                } catch (Throwable failure) { fail(failure); }
            }));
        } catch (Throwable failure) { fail(failure); }
    }

    private void tick() {
        if (stopping) return;
        try {
            require(System.nanoTime() < deadline, "Benchmark completed before timeout");
            require(Bukkit.getOnlinePlayers().isEmpty(), "No player connected during benchmark");
            require(seasons.version().equals(version) && !seasons.state().paused, "Winter version remained active");
            // Run after the plugin heartbeat; the same policy is applied to both versions.
            world.setStorm(true); world.setThundering(false);
            world.setWeatherDuration(24_000); world.setClearWeatherDuration(0);
            if (!seasons.engine().allFinished()) { stable = 0; return; }
            if (++stable < 60) return;
            if (!loaded) { loaded = true; loadNext(-8, -8); return; }
            if (originalForced.size() != 256) return;
            if (warmupTicks % 100 == 0) for (Chunk chunk : originalForced.keySet())
                require(chunk.isLoaded() && seasons.snow().isComplete(chunk, version), "Loaded completed central chunk");
            if (!prepare) require(seasons.engine().jobs().stream().allMatch(job -> job.placed() == 0 && job.removed() == 0),
                "Saved checkpoint hydrated without migration snow rewrites");
            if (prepare) { finish(); return; }
            if (++warmupTicks <= 600) {
                if (warmupTicks == 600) {
                    ownershipBefore = ownershipDigest();
                    note("SAMPLING_START loaded=" + world.getLoadedChunks().length + " central=256 ownership=" + ownershipBefore);
                    sampleStart = System.nanoTime();
                    skipFirstSampleTick = true;
                    require(Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        "spark profiler start --timeout 60 --interval 4 --save-to-file"), "Spark local-file command dispatched");
                }
                return;
            }
            sampleTicks++;
            if (sampleTicks % 200 == 0) note("SAMPLE t=" + sampleTicks + " loaded=" + world.getLoadedChunks().length
                + " avg_mspt=" + Bukkit.getServer().getAverageTickTime() + " tps=" + Arrays.toString(Bukkit.getTPS()));
            if (System.nanoTime() - sampleStart >= 65_000_000_000L) {
                require(ownershipBefore.equals(ownershipDigest()), "Owned snow input remained unchanged during empty-refill sample");
                finish();
            }
        } catch (Throwable failure) { fail(failure); }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void tickDuration(ServerTickEndEvent event) {
        if (sampleStart == 0 || stopping || System.nanoTime() - sampleStart > 60_000_000_000L) return;
        if (skipFirstSampleTick) { skipFirstSampleTick = false; return; }
        tickMs.add(event.getTickDuration());
    }

    private void loadNext(int x, int z) {
        world.getChunkAtAsync(x, z, false, false).whenComplete((chunk, error) -> Bukkit.getScheduler().runTask(this, () -> {
            if (stopping) return;
            try {
                if (error != null) throw new IllegalStateException("Non-generating central load failed", error);
                require(chunk != null && chunk.isLoaded(), "Existing FULL central chunk loaded");
                originalForced.put(chunk, chunk.isForceLoaded()); chunk.setForceLoaded(true);
                if (z < 7) loadNext(x, z + 1); else if (x < 7) loadNext(x + 1, -8);
                else note("CENTRAL_LOADED count=" + originalForced.size());
            } catch (Throwable failure) { fail(failure); }
        }));
    }

    private String ownershipDigest() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        var key = new NamespacedKey(seasons, "snow_v1");
        int ownedCount = 0;
        for (Chunk chunk : originalForced.keySet()) {
            digest.update((chunk.getX() + "," + chunk.getZ() + ":").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] bytes = chunk.getPersistentDataContainer().get(key, PersistentDataType.BYTE_ARRAY);
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes == null ? 0 : bytes.length).array());
            if (bytes != null) digest.update(bytes);
            for (var entry : SnowColumns.decode(bytes).entries().entrySet()) {
                ownedCount++;
                int column = entry.getKey();
                digest.update(chunk.getBlock(column & 15, entry.getValue(), column >> 4)
                    .getBlockData().getAsString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        require(ownedCount > 0, "Benchmark central area contains real seasonal snow ownership");
        return HexFormat.of().formatHex(digest.digest());
    }

    private void finish() throws Exception {
        stopping = true;
        Bukkit.getScheduler().cancelTasks(this);
        restoreForced();
        require(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all flush"), "Synchronous checkpoint save dispatched");
        // Full disk revalidation is outside the sample and outside the server thread.
        CompletableFuture.supplyAsync(() -> {
            try {
                GeneratedChunkIndex index = new GeneratedChunkIndex();
                require(originalHeaders.equals(new HashSet<>(index.scan(world.getWorldPath().resolve("region")))), "No new occupied headers");
                var snapshot = index.scanSnapshot(world.getWorldPath().resolve("region"));
                require(originalFull.equals(new HashSet<>(snapshot.generated())), "No new FULL chunks");
                require(snapshot.unreadable().isEmpty(), "Saved statuses readable after run");
                for (var state : snapshot.states().values()) require(state.matches(version, false), "All FULL checkpoints match winter");
                return "PASS";
            } catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
        }).whenComplete((result, error) -> Bukkit.getScheduler().runTask(this, () -> {
            try {
                if (error != null) throw new IllegalStateException("Post-run disk invariant failed", error);
                if (!prepare) require(tickMs.size() >= 1000, "At least 1,000 measured Paper tick events");
                if (!tickMs.isEmpty()) {
                    double mean = tickMs.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
                    List<Double> sorted = tickMs.stream().sorted().toList();
                    note("TICK_OBSERVATIONS count=" + sorted.size() + " mean=" + mean + " p95="
                        + sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * .95) - 1))
                        + " max=" + sorted.getLast());
                }
                note("PROBE_SNOW_PERFORMANCE_PASSED mode=" + (prepare ? "prepare" : "capture") + " jar="
                    + seasons.getPluginMeta().getVersion() + " headers=" + originalHeaders.size() + " full=" + originalFull.size());
                Files.writeString(Path.of("snow-performance-result.txt"), String.join("\n", observations) + "\n");
                Bukkit.shutdown();
            } catch (Throwable failure) { fail(failure); }
        }));
    }

    private void note(String text) { observations.add(Instant.now() + " " + text); getLogger().info(text); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private void restoreForced() { originalForced.forEach((chunk, forced) -> chunk.setForceLoaded(forced)); }
    private void fail(Throwable failure) {
        stopping = true; getLogger().log(Level.SEVERE, "PROBE_SNOW_PERFORMANCE_FAILED", failure);
        try { Files.writeString(Path.of("snow-performance-result.txt"), "FAIL: " + failure); } catch (Exception ignored) {}
        restoreForced(); Bukkit.shutdown();
    }
    @Override public void onDisable() { restoreForced(); }
}
