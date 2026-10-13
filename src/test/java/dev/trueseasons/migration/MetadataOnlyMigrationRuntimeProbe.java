package dev.trueseasons.migration;

import dev.trueseasons.TrueSeasonsPlugin;
import dev.trueseasons.api.Season;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

/** Real engine/BlueMap regression on the completed disposable clone; never install on a real server. */
public final class MetadataOnlyMigrationRuntimeProbe extends JavaPlugin {
    private TrueSeasonsPlugin seasons;
    private World world;
    private Chunk fixture;
    private Path regions;
    private Set<ChunkPos> before;
    private final Map<Block, String> playerBlocks = new LinkedHashMap<>();
    private Object mapIntegration;
    private Field scheduled;
    private long baseline, began;
    private int checks;
    private String phase = "startup", autumnVersion;
    private CompletableFuture<SavedChunkState> reading;
    private long lastRead;

    @Override public void onEnable() {
        try {
            if (!Files.exists(Path.of("verification-result.txt")) || !Files.exists(Path.of("fixture.txt")))
                throw new IllegalStateException("Completed disposable verification clone required");
            seasons = (TrueSeasonsPlugin) Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("TrueSeasons"));
            world = Bukkit.getWorlds().stream().filter(seasons::eligible).findFirst().orElseThrow();
            regions = world.getWorldPath().resolve("region"); began = System.currentTimeMillis();
            Field adapter = seasons.integrations().getClass().getDeclaredField("blueMap"); adapter.setAccessible(true);
            mapIntegration = Objects.requireNonNull(adapter.get(seasons.integrations()), "Enabled BlueMap adapter required");
            scheduled = mapIntegration.getClass().getDeclaredField("regionRendersScheduled"); scheduled.setAccessible(true);
            Bukkit.getScheduler().runTaskTimer(this, () -> { try { poll(); } catch (Throwable failure) { fail(failure); } }, 20L, 20L);
        } catch (Throwable failure) { fail(failure); }
    }
    private void poll() throws Exception {
        if (phase.equals("finished")) return;
        if (System.currentTimeMillis() - began > 240_000) throw new AssertionError("Metadata-only verification timed out in " + phase);
        if (phase.equals("startup")) {
            if (!seasons.engine().allFinished() || seasons.state().cleanup || !seasons.state().clean
                    || seasons.integrations().pending() != 0 || !seasons.integrations().status().startsWith("connected")) return;
            check(seasons.integrations().isEnabled(), "BlueMap is installed, enabled and connected");
            check(seasons.state().override == Season.SPRING && seasons.state().clean, "Startup restores the completed spring/cleanup checkpoint");
            baseline = scheduled.getLong(mapIntegration);
            check(baseline == 0, "Clean startup schedules no TrueSeasons region renders");
            before = new HashSet<>(new GeneratedChunkIndex().scan(regions));
            String[] coordinates = Files.readString(Path.of("fixture.txt")).strip().split(",");
            phase = "loading";
            world.getChunkAtAsync(Integer.parseInt(coordinates[0]), Integer.parseInt(coordinates[1]), false, false).thenAccept(chunk -> {
                try {
                    if (!Bukkit.isPrimaryThread()) throw new AssertionError("Fixture continuation must run on the server thread");
                    fixture = Objects.requireNonNull(chunk);
                    check(!seasons.snow().hasMetadata(fixture), "Representative fixture has no seasonal snow before autumn");
                    for (int[] column : new int[][]{{1, 260, 0}, {7, 270, 0}, {10, 260, 0}, {5, 259, 0}}) {
                        Block block = fixture.getBlock(column[0], column[1], column[2]);
                        playerBlocks.put(block, block.getBlockData().getAsString());
                    }
                    seasons.setSeason(Season.AUTUMN); autumnVersion = seasons.version(); phase = "migration";
                    getLogger().info("Metadata-only autumn migration started; baseline render requests " + baseline);
                } catch (Throwable failure) { fail(failure); }
            });
            return;
        }
        if (phase.equals("migration")) {
            if (!seasons.engine().allFinished()) return;
            check(seasons.currentSeason() == Season.AUTUMN && seasons.version().equals(autumnVersion), "Real root engine completes the explicit autumn migration");
            check(seasons.engine().jobs().stream().allMatch(job -> job.placed() == 0 && job.removed() == 0), "Metadata-only migration places and removes zero snow blocks");
            check(seasons.integrations().pending() == 0, "Metadata-only migration creates no pending render regions");
            check(scheduled.getLong(mapIntegration) == baseline, "Metadata-only migration schedules no TrueSeasons BlueMap renders");
            check(seasons.snow().isComplete(fixture, autumnVersion), "Representative loaded chunk has the finalized autumn stamp");
            check(playerBlocks.entrySet().stream().allMatch(entry -> entry.getValue().equals(entry.getKey().getBlockData().getAsString())), "Player diamond block, glass, vegetation and roof support remain unchanged");
            world.save(); phase = "saved";
        }
        if (phase.equals("saved")) {
            if (reading != null && reading.isDone()) {
                SavedChunkState saved = reading.join(); reading = null;
                if (saved != null && saved.matches(autumnVersion, false)) {
                    check(saved.full() && !saved.pending(), "Representative saved chunk persists its finalized autumn season stamp");
                    check(!saved.mapMarker(), "Metadata-only save creates no BlueMap revision marker");
                    check(before.equals(new HashSet<>(new GeneratedChunkIndex().scan(regions))), "Region-header occupied slots stay unchanged; no terrain is generated");
                    check(seasons.integrations().pending() == 0 && scheduled.getLong(mapIntegration) == baseline, "No deferred render requests appear after the metadata save");
                    Files.writeString(Path.of("metadata-only-result.txt"), "PASS: " + checks + " real metadata-only migration checks; zero snow edits, zero TrueSeasons region renders, no generated terrain.");
                    getLogger().info("PROBE_METADATA_ONLY_PASSED " + checks + " checks");
                    phase = "finished"; Bukkit.shutdown(); return;
                }
            }
            if (reading == null && System.currentTimeMillis() - lastRead >= 1000) {
                lastRead = System.currentTimeMillis(); ChunkPos position = new ChunkPos(fixture.getX(), fixture.getZ());
                reading = CompletableFuture.supplyAsync(() -> {
                    try {
                        // Paper isolates plugin packages. Read the root helper reflectively on a file-only worker.
                        Class<?> status = Class.forName("dev.trueseasons.migration.SavedChunkStatus");
                        Method read = status.getDeclaredMethod("read", Path.class, ChunkPos.class); read.setAccessible(true);
                        return (SavedChunkState) read.invoke(null, regions, position);
                    } catch (ReflectiveOperationException | RuntimeException failure) { return null; }
                });
            }
        }
    }
    private void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; getLogger().info("PASS: " + label); }
    private void fail(Throwable failure) {
        phase = "finished";
        getLogger().log(java.util.logging.Level.SEVERE, "PROBE_METADATA_ONLY_FAILED", failure);
        try { Files.writeString(Path.of("metadata-only-result.txt"), "FAIL: " + failure); } catch (Exception ignored) {}
        Bukkit.shutdown();
    }
}
