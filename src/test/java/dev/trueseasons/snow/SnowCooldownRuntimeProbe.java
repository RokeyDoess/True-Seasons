package dev.trueseasons.snow;

import dev.trueseasons.TrueSeasonsPlugin;
import dev.trueseasons.migration.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;
import dev.trueseasons.config.Settings;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.event.block.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Real Paper block/event regression, run only in the existing disposable server clone. */
public final class SnowCooldownRuntimeProbe extends JavaPlugin {
    private SnowReplenishmentService refill;
    private int checks;
    private boolean pauseBeforeProbe;
    private final List<BlockState> originals = new ArrayList<>();
    @Override public void onEnable() {
        try {
            if (!Files.exists(Path.of("verification-result.txt"))) throw new IllegalStateException("Verification clone required");
            var seasons = (TrueSeasonsPlugin) Bukkit.getPluginManager().getPlugin("TrueSeasons");
            World world = Bukkit.getWorlds().stream().filter(w -> w.getEnvironment() == World.Environment.NORMAL).findFirst().orElseThrow();
            String[] position = Files.readString(Path.of("fixture.txt")).strip().split(",");
            world.getChunkAtAsync(Integer.parseInt(position[0]), Integer.parseInt(position[1]), false, false)
                .thenAccept(chunk -> {
                    try {
                        Objects.requireNonNull(chunk); pauseBeforeProbe = seasons.state().paused; seasons.state().paused = true;
                        int top = world.getMaxHeight() - 3;
                        for (int y = top - 1; y < world.getMaxHeight(); y++) originals.add(chunk.getBlock(2, y, 0).getState());
                        chunk.getBlock(2, top, 0).setType(Material.STONE, false);
                        chunk.getBlock(2, top + 1, 0).setType(Material.SNOW, false);
                        chunk.getBlock(2, top + 2, 0).setType(Material.AIR, false);
                        // Paper's light engine needs time to light a newly created high-altitude fixture.
                        Bukkit.getScheduler().runTaskLater(this, () -> {
                            // No player holds this remote fixture loaded during the delay.
                            // Reacquire its existing chunk and verify inside Paper's load lease.
                            world.getChunkAtAsync(chunk.getX(), chunk.getZ(), false, false).whenComplete((loaded, error) -> {
                                try {
                                    if (error != null) throw new IllegalStateException("Fixture reload failed", error);
                                    verify(seasons, world, Objects.requireNonNull(loaded));
                                } catch (Throwable failure) { fail(failure); }
                                finally { if (refill != null) refill.close(); Bukkit.shutdown(); }
                            });
                        }, 20L);
                    } catch (Throwable failure) { fail(failure); Bukkit.shutdown(); }
                });
        } catch (Throwable failure) { fail(failure); Bukkit.shutdown(); }
    }
    private void verify(TrueSeasonsPlugin seasons, World world, Chunk chunk) throws Exception {
        Set<ChunkPos> before = new HashSet<>(new GeneratedChunkIndex().scan(world.getWorldPath().resolve("region")));
        boolean paused = pauseBeforeProbe, raining = world.hasStorm();
        int weatherDuration = world.getWeatherDuration(), clearDuration = world.getClearWeatherDuration();
        var versionKey = new NamespacedKey(seasons, "season_version");
        String savedVersion = chunk.getPersistentDataContainer().get(versionKey, PersistentDataType.STRING);
        int top = world.getMaxHeight() - 3;
        SeasonalSnowService snow = new SeasonalSnowService(seasons, seasons::settings, () -> true);
        AtomicLong time = new AtomicLong();
        String stamp = seasons.version();
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (p, method, args) -> switch (method.getName()) {
                case "getWorld" -> world; case "getName" -> "SnowCooldownProbe";
                case "getUniqueId" -> new UUID(0, 1); case "toString" -> "SnowCooldownProbe";
                case "equals" -> p == args[0]; case "hashCode" -> System.identityHashCode(p);
                default -> throw new AssertionError("Unexpected player call: " + method.getName());
            });
        try {
            seasons.state().paused = true;
            checks += SnowMigrationIntentRuntimeProbe.verify(this, seasons::settings, chunk);
            checks += WinterGroundCoverRuntimeProbe.verify(this, seasons::settings, chunk);
            chunk.getPersistentDataContainer().set(versionKey, PersistentDataType.STRING, stamp);
            var factory = SnowReplenishmentService.class.getDeclaredConstructor(JavaPlugin.class, SeasonalSnowService.class,
                Supplier.class, Supplier.class, java.util.function.Predicate.class, java.util.function.Predicate.class,
                Consumer.class, Consumer.class, LongSupplier.class);
            factory.setAccessible(true); // Paper isolates plugin loaders, even for the same Java package.
            refill = factory.newInstance(this, snow, (Supplier<Settings>) seasons::settings, (Supplier<String>) () -> stamp,
                (Predicate<World>) w -> w == world, (Predicate<World>) w -> w == world,
                (Consumer<Chunk>) c -> {}, (Consumer<Chunk>) c -> {}, (LongSupplier) time::get);
            check(seasons.settings().snowPlayerBreakCooldownSeconds == 60, "Existing config gains the 60-second snow-break default");
            chunk.getBlock(2, top, 0).setType(Material.STONE, false);
            Block target = chunk.getBlock(2, top + 1, 0); target.setType(Material.SNOW, false);
            chunk.getBlock(2, top + 2, 0).setType(Material.AIR, false);
            world.setStorm(false);
            refill.broken(new BlockBreakEvent(target, player)); target.setType(Material.AIR, false);
            world.setStorm(true);
            check(target.getLightFromSky() == 15 && target.getLightFromBlocks() < 10 && target.canPlace(Material.SNOW.createBlockData()),
                "Live snow fixture has settled sky lighting and vanilla support");
            check(refill.refill(chunk, new int[]{2}).placed() == 0 && target.getType() == Material.AIR,
                "A clear-weather break stays clear when winter rain starts");
            for (int i = 0; i < 200; i++) refill.tick();
            check(target.getType() == Material.AIR, "Normal random snowfall attempts cannot bypass player grace");
            chunk.getBlock(2, top, 0).setType(Material.AIR, false);
            chunk.getBlock(2, top - 1, 0).setType(Material.STONE, false);
            target = chunk.getBlock(2, top, 0);
            check(refill.refill(chunk, new int[]{2}).placed() == 0 && target.getType() == Material.AIR,
                "Digging the support lower does not bypass horizontal-column grace");
            BlockState formed = target.getState(); formed.setType(Material.SNOW);
            BlockFormEvent formation = new BlockFormEvent(target, formed); formation.callEvent();
            check(formation.isCancelled(), "Real Paper snow-formation events respect player grace");
            chunk.getBlock(2, top, 0).setType(Material.STONE, false);
            target = chunk.getBlock(2, top + 1, 0);
            time.set(59_999_999_999L);
            check(refill.refill(chunk, new int[]{2}).placed() == 0, "The spot remains protected until the full grace expires");
            time.set(60_000_000_000L);
            check(refill.refill(chunk, new int[]{2}).placed() == 1 && target.getType() == Material.SNOW,
                "Snow becomes eligible again at expiry without restarting migration");
            var owns = SeasonalSnowService.class.getDeclaredMethod("owns", Block.class); owns.setAccessible(true);
            check((boolean) owns.invoke(snow, target), "Post-grace real snow retains seasonal ownership");
            refill.broken(new BlockBreakEvent(target, player)); snow.relinquish(target); target.setType(Material.AIR, false);
            time.set(119_999_999_999L);
            check(refill.refill(chunk, new int[]{2}).placed() == 0, "Another successful break starts a fresh grace period");
            time.set(120_000_000_000L);
            check(refill.refill(chunk, new int[]{2}).placed() == 1, "Repeated breaks do not make snow permanently unbreakable");
            BlockBreakEvent cancelled = new BlockBreakEvent(target, player); cancelled.setCancelled(true); refill.broken(cancelled);
            snow.relinquish(target); target.setType(Material.AIR, false);
            check(refill.refill(chunk, new int[]{2}).placed() == 1, "Canceled breaks do not start a new cooldown");
            check(before.equals(new HashSet<>(new GeneratedChunkIndex().scan(world.getWorldPath().resolve("region")))),
                "Cooldown verification loads no new terrain");
        } finally {
            if (refill != null) refill.close();
            snow.apply(chunk, dev.trueseasons.api.Season.SPRING, stamp, true);
            for (BlockState original : originals) original.update(true, false);
            if (savedVersion == null) chunk.getPersistentDataContainer().remove(versionKey);
            else chunk.getPersistentDataContainer().set(versionKey, PersistentDataType.STRING, savedVersion);
            seasons.state().paused = paused;
            world.setStorm(raining); world.setWeatherDuration(weatherDuration); world.setClearWeatherDuration(clearDuration);
        }
        Files.writeString(Path.of("snow-cooldown-result.txt"), "PASS: " + checks + " real Paper snow-break cooldown checks; no generated terrain.");
        getLogger().info("PROBE_SNOW_COOLDOWN_PASSED " + checks + " checks");
    }
    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++; getLogger().info("PASS: " + message);
    }
    private void fail(Throwable failure) {
        getLogger().log(java.util.logging.Level.SEVERE, "PROBE_SNOW_COOLDOWN_FAILED", failure);
        try { Files.writeString(Path.of("snow-cooldown-result.txt"), "FAIL: " + failure); } catch (Exception ignored) {}
    }
    @Override public void onDisable() { if (refill != null) refill.close(); }
}
