package dev.trueseasons.weather;

import dev.trueseasons.TrueSeasonsPlugin;
import dev.trueseasons.api.*;
import dev.trueseasons.state.StateStore;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Function;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.*;
import org.bukkit.event.weather.*;

public final class WeatherService implements Listener, AutoCloseable {
    private record Storm(boolean rain, boolean thunder, int rainTicks, int thunderTicks, int clearTicks, String version) {}
    private boolean restoring;
    private final TrueSeasonsPlugin plugin;
    private final Path file;
    private final Predicate<World> revealPending,terrainFinished;
    private final Function<UUID,World> worlds;
    private final Map<UUID, Storm> storms = new HashMap<>();
    private final Map<UUID, String> lastStorm = new HashMap<>();
    public WeatherService(TrueSeasonsPlugin plugin) throws Exception {
        this(plugin,world->false);
    }
    public WeatherService(TrueSeasonsPlugin plugin,Predicate<World> revealPending) throws Exception {
        this(plugin,revealPending,world->plugin.engine().worldFinished(world),Bukkit::getWorld);
    }
    WeatherService(TrueSeasonsPlugin plugin,Predicate<World> revealPending,Predicate<World> terrainFinished,Function<UUID,World> worlds) throws Exception {
        this.plugin = plugin; file = plugin.getDataFolder().toPath().resolve("state/weather.yml");
        this.revealPending=Objects.requireNonNull(revealPending);this.terrainFinished=Objects.requireNonNull(terrainFinished);this.worlds=Objects.requireNonNull(worlds);
        if (Files.exists(file)) {
            var data = new YamlConfiguration(); data.load(file.toFile());
            if (data.getInt("schema") != 1 && data.getInt("schema") != 2) throw new IOException("Unknown weather state schema");
            var section = data.getConfigurationSection("worlds");
            if (section != null) for (String key : section.getKeys(false)) {
                UUID id = UUID.fromString(key); String root = "worlds." + key + ".";
                lastStorm.put(id, data.getString(root + "version", ""));
                if (data.contains(root + "deadline") || data.getBoolean(root+"active")) storms.put(id, new Storm(data.getBoolean(root + "rain"),
                    data.getBoolean(root + "thunder"), data.getInt(root + "rain-ticks"), data.getInt(root + "thunder-ticks"), data.getInt(root+"clear-ticks"), data.getString(root+"version","")));
            }
        }
    }
    private boolean active(org.bukkit.World world) {
        return plugin.settings().weather && plugin.eligible(world) && !plugin.state().cleanup && !plugin.state().clean;
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void migration(SeasonMigrationEvent event) {
        if (event.getPhase() == SeasonMigrationEvent.Phase.START && !event.isCleanup()) startWinterStorm(event.getWorld());
        if (event.getPhase() == SeasonMigrationEvent.Phase.COMPLETE
                &&(!active(event.getWorld())||plugin.currentSeason()!=Season.WINTER||!plugin.settings().winterStorm||!revealPending.test(event.getWorld())))
            restoreStorm(event.getWorld().getUID());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void season(SeasonChangeEvent event) {
        if (event.getCurrentSeason() == Season.WINTER) startWinterStorm(event.getWorld());
        else restoreStorm(event.getWorld().getUID());
    }
    private void startWinterStorm(World world) {
        if (!active(world) || plugin.currentSeason() != Season.WINTER || !plugin.settings().winterStorm
                || plugin.version().equals(lastStorm.get(world.getUID())) && !storms.containsKey(world.getUID())) return;
        Storm previous = storms.get(world.getUID());
        if(previous!=null&&previous.version.equals(plugin.version())) { holdStorm(world); return; }
        Storm next = new Storm(previous == null ? world.hasStorm() : previous.rain,
            previous == null ? world.isThundering() : previous.thunder,
            previous == null ? world.getWeatherDuration() : previous.rainTicks,
            previous == null ? world.getThunderDuration() : previous.thunderTicks,
            previous == null ? world.getClearWeatherDuration() : previous.clearTicks,
            plugin.version());
        storms.put(world.getUID(), next); lastStorm.put(world.getUID(), plugin.version()); save();
        holdStorm(world); world.setThundering(false);
        plugin.getLogger().info("Winter snowstorm started in " + world.getName());
    }
    private void save() {
        try {
            var data = new YamlConfiguration(); data.set("schema", 2);
            for (var entry : lastStorm.entrySet()) {
                String root = "worlds." + entry.getKey() + "."; data.set(root + "version", entry.getValue());
                Storm storm = storms.get(entry.getKey()); if (storm == null) continue;
                data.set(root + "rain", storm.rain); data.set(root + "thunder", storm.thunder);
                data.set(root + "rain-ticks", storm.rainTicks); data.set(root + "thunder-ticks", storm.thunderTicks);
                data.set(root + "clear-ticks",storm.clearTicks);
                data.set(root + "active", true);
            }
            StateStore.atomicWrite(file, data.saveToString());
        } catch (IOException failure) { throw new UncheckedIOException("Cannot persist temporary storm restoration", failure); }
    }
    private void restoreStorm(UUID id) {
        Storm storm = storms.get(id); World world = worlds.apply(id);
        if (storm == null || world == null) return;
        restoring=true;
        try {
            world.setStorm(storm.rain); world.setThundering(storm.thunder);
            world.setWeatherDuration(storm.rainTicks); world.setThunderDuration(storm.thunderTicks);
            world.setClearWeatherDuration(storm.clearTicks);
        } finally { restoring=false; }
        storms.remove(id); save();
    }
    public int activeStorms() { return storms.size(); }
    private void holdStorm(World world) {
        if(world.getClearWeatherDuration()>0)world.setClearWeatherDuration(0);
        if(!world.hasStorm())world.setStorm(true);
        if(world.getWeatherDuration()<1200)world.setWeatherDuration(6000);
    }
    public void tick() {
        for (UUID id : new ArrayList<>(storms.keySet())) {
            Storm storm = storms.get(id); World world = worlds.apply(id);
            if (world != null && (!active(world) || !storm.version.equals(plugin.version())
                    || plugin.currentSeason() != Season.WINTER || !plugin.settings().winterStorm || !migrationPending(world))) restoreStorm(id);
            else if(world!=null)holdStorm(world);
        }
    }
    private boolean migrationPending(World world){return !terrainFinished.test(world)||revealPending.test(world);}
    /** Restore only the temporary winter-entry storm; native snowfall follows the whole season. */
    @Override public void close() {
        for (UUID id : new ArrayList<>(storms.keySet())) {
            World world=worlds.apply(id);
            boolean resume=world!=null&&active(world)&&plugin.currentSeason()==Season.WINTER&&!terrainFinished.test(world);
            restoreStorm(id);
            if(resume) { lastStorm.remove(id); save(); }
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void keepMigrationStorm(WeatherChangeEvent event) {
        if(!restoring&&!event.toWeatherState()&&storms.containsKey(event.getWorld().getUID())&&active(event.getWorld())
            &&plugin.currentSeason()==Season.WINTER&&plugin.settings().winterStorm&&migrationPending(event.getWorld()))event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void rain(WeatherChangeEvent event) {
        if (!event.toWeatherState() || event.getCause() != WeatherChangeEvent.Cause.NATURAL || !active(event.getWorld())) return;
        double chance = plugin.settings().weatherConfig.getDouble("rain-acceptance." + plugin.currentSeason().key(), 1);
        if (ThreadLocalRandom.current().nextDouble() > chance) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void thunder(ThunderChangeEvent event) {
        if (!event.toThunderState() || event.getCause() != ThunderChangeEvent.Cause.NATURAL || !active(event.getWorld())) return;
        double chance = plugin.settings().weatherConfig.getDouble("thunder-acceptance." + plugin.currentSeason().key(), 1);
        if (ThreadLocalRandom.current().nextDouble() > chance) event.setCancelled(true);
    }
}
