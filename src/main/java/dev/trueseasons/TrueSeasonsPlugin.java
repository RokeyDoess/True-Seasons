package dev.trueseasons;

import dev.trueseasons.api.*;
import dev.trueseasons.calendar.SeasonCalendar;
import dev.trueseasons.command.SeasonsCommand;
import dev.trueseasons.config.Settings;
import dev.trueseasons.integration.*;
import dev.trueseasons.migration.ChunkMigrationEngine;
import dev.trueseasons.snow.SeasonalSnowService;
import dev.trueseasons.snow.SnowReplenishmentService;
import dev.trueseasons.state.StateStore;
import dev.trueseasons.visual.SeasonVisualResolver;
import dev.trueseasons.weather.WeatherService;
import dev.trueseasons.presentation.NativeSnowPresentation;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class TrueSeasonsPlugin extends JavaPlugin implements TrueSeasonsApi {
    private Settings settings;
    private StateStore state;
    private SeasonVisualResolver visuals;
    private SeasonalSnowService snow;
    private IntegrationManager integrations;
    private ChunkMigrationEngine engine;
    private WeatherService weather;
    private NativeSnowPresentation presentation;
    private SnowReplenishmentService replenishment;
    private Season season;
    private String version;
    private long lastCalendar;

    @Override public void onEnable() {
        try {
            settings = new Settings(this); visuals = new SeasonVisualResolver(settings.visuals, settings.climate);
            state = new StateStore(getDataFolder().toPath().resolve("state"));
            // A previous completion counter never proves a chunk edit was saved.
            // Revalidate cleanup on startup before reporting removal-safe again.
            if (state.clean) { state.clean = false; state.cleanup = true; state.save(); }
            refreshSeason(false);
            snow = new SeasonalSnowService(this, this::settings, () -> season == Season.WINTER && !state.cleanup && !state.clean);
            integrations = new IntegrationManager(this, this::settings);
            engine = new ChunkMigrationEngine(this);
            integrations.setRegionReadiness(engine::regionReady);
            Bukkit.getPluginManager().registerEvents(snow, this);
            weather = new WeatherService(this);
            presentation = new NativeSnowPresentation(this, world -> settings.weather && settings.winterSnowfall
                && eligible(world) && season == Season.WINTER && !state.cleanup && !state.clean, () -> settings.climate);
            Bukkit.getPluginManager().registerEvents(weather, this);
            replenishment = new SnowReplenishmentService(this,snow,this::settings,this::version,
                world -> season==Season.WINTER&&eligible(world)&&!state.cleanup&&!state.clean
                    &&settings.enabled&&settings.snow&&settings.weather&&settings.winterSnowfall,
                world -> season==Season.WINTER&&eligible(world)&&!state.cleanup&&!state.clean&&!state.paused
                    &&settings.enabled&&settings.snow&&settings.weather&&settings.winterSnowfall&&world.hasStorm()
                    &&engine.worldFinished(world)&&engine.currentTps()>=settings.minTps,
                integrations::chunkUpdated,chunk -> integrations.chunkCompleted(chunk,false));
            for (World world : Bukkit.getWorlds()) engine.addWorld(world);
            SeasonsCommand command = new SeasonsCommand(this);
            Objects.requireNonNull(getCommand("trueseasons")).setExecutor(command);
            getCommand("trueseasons").setTabCompleter(command);
            Bukkit.getServicesManager().register(TrueSeasonsApi.class, this, this, ServicePriority.Normal);
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                try {
                    if (System.currentTimeMillis() - lastCalendar > 60_000) { refreshSeason(true); lastCalendar = System.currentTimeMillis(); }
                    engine.tick(); integrations.tick(); weather.tick(); presentation.tick(); replenishment.tick();
                } catch (Exception failure) {
                    getLogger().log(Level.SEVERE, "Stopping migrations after a state/integration failure", failure);
                    state.paused = true;
                }
            }, 1L, 1L);
            getLogger().info("Current season: " + season + " (" + version + "), zone " + settings.calendar.zone());
        } catch (Exception failure) {
            getLogger().log(Level.SEVERE, "TrueSeasons could not safely initialize", failure);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable() {
        if (engine != null) engine.close();
        if (replenishment != null) replenishment.close();
        if (presentation != null) presentation.close();
        if (weather != null) try { weather.close(); } catch (Exception failure) { getLogger().log(Level.SEVERE, "Could not restore temporary weather", failure); }
        if (integrations != null) try { integrations.close(); } catch (Exception failure) { getLogger().log(Level.SEVERE, "Could not save map queue", failure); }
        HandlerList.unregisterAll(this); Bukkit.getServicesManager().unregisterAll(this);
        if (state != null) try { state.save(); } catch (IOException failure) { getLogger().log(Level.SEVERE, "Could not save job intent", failure); }
    }
    public Settings settings() { return settings; }
    public StateStore state() { return state; }
    public SeasonalSnowService snow() { return snow; }
    public IntegrationManager integrations() { return integrations; }
    public ChunkMigrationEngine engine() { return engine; }
    public WeatherService weather() { return weather; }
    public SnowReplenishmentService replenishment() { return replenishment; }
    public Season currentSeason() { return season; }
    public String version() { return version; }
    public boolean eligible(World world) {
        return world.getEnvironment() == World.Environment.NORMAL && (settings.worlds.isEmpty() || settings.worlds.contains(world.getName()));
    }
    public void persist() {
        try { state.save(); } catch (IOException failure) { throw new UncheckedIOException("Cannot persist job intent", failure); }
    }
    private void refreshSeason(boolean migrate) {
        SeasonCalendar.State calendar = settings.calendar.at(Instant.now());
        Season next = state.override == null ? calendar.season() : state.override;
        String nextVersion = (state.override == null ? calendar.version() : "manual-" + next) + "-r" + state.revision + "-snow4";
        if (nextVersion.equals(version)) return;
        Season previous = season; season = next; version = nextVersion;
        if(!state.winterVersion.equals(version)) { state.winterVersion=version; state.winterColumns=0; persist(); }
        if (migrate && !state.cleanup && !state.clean) {
            engine.restart();
            for (World world : Bukkit.getWorlds()) if (eligible(world))
                Bukkit.getPluginManager().callEvent(new SeasonChangeEvent(world, previous, next));
        }
    }
    public void setSeason(Season override) {
        if (state.cleanup) throw new IllegalStateException("Cleanup is active; wait for completion");
        state.override = override; state.clean = false; state.revision++; persist();
        refreshSeason(true);
    }
    public void reloadSettings() throws Exception {
        Settings next = new Settings(this);
        SeasonVisualResolver nextVisuals = new SeasonVisualResolver(next.visuals, next.climate);
        boolean snowRulesChanged=settings.snow!=next.snow||settings.allExposed!=next.allExposed
            ||!settings.surfaces.equals(next.surfaces)
            ||!settings.worlds.equals(next.worlds)||settings.groundCover!=next.groundCover||!settings.climate.sameWinter(next.climate);
        boolean areasChanged=settings.playerRadius!=next.playerRadius;
        settings = next; visuals = nextVisuals;
        if(areasChanged)engine.invalidatePlayerAreas();
        if(snowRulesChanged)state.revision++;
        persist(); refreshSeason(true);
    }
    public void startCleanup() {
        if (state.cleanup) return;
        state.cleanup = true; state.clean = false; state.paused = false; persist(); engine.restart(); weather.close(); presentation.tick();
    }
    @Override public Season getCurrentSeason(World world) { return season; }
    @Override public double getSeasonProgress(World world) { return state.override == null ? settings.calendar.at(Instant.now()).progress() : 0; }
    @Override public SeasonVisualProfile getSeasonalVisuals(Location location) {
        World world = Objects.requireNonNull(location.getWorld(), "Location requires a world");
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4))
            throw new IllegalArgumentException("Visual resolution requires an already loaded chunk");
        return visuals.resolve(season, location.getBlock().getBiome().getKey().getKey());
    }
    @Override public String getChunkSeasonalVersion(Chunk chunk) { return snow.version(chunk); }
    @Override public boolean isCleanupActive() { return state.cleanup; }
    @Override public void relinquishSnowOwnership(org.bukkit.block.Block block) { snow.relinquish(block); }
}
