package dev.trueseasons;

import dev.trueseasons.api.*;
import dev.trueseasons.calendar.SeasonCalendar;
import dev.trueseasons.command.SeasonsCommand;
import dev.trueseasons.config.Settings;
import dev.trueseasons.integration.*;
import dev.trueseasons.migration.ChunkMigrationEngine;
import dev.trueseasons.snow.SeasonalSnowService;
import dev.trueseasons.snow.SnowReplenishmentService;
import dev.trueseasons.snow.LocalWinterSnowService;
import dev.trueseasons.state.StateStore;
import dev.trueseasons.state.TerrainTarget;
import dev.trueseasons.visual.SeasonVisualResolver;
import dev.trueseasons.visual.SeasonalVisualState;
import dev.trueseasons.visual.SeasonColorFrame;
import dev.trueseasons.weather.WeatherService;
import dev.trueseasons.presentation.NativeSnowPresentation;
import dev.trueseasons.notification.SeasonAnnouncements;
import java.io.*;
import java.time.Clock;
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
    private SeasonAnnouncements announcements;
    private SnowReplenishmentService replenishment;
    private LocalWinterSnowService localSnow;
    private volatile SeasonalVisualState visualState;
    private volatile SeasonalVisualState mapVisualState;
    private Season season;
    private String version;
    private String seasonVersion;
    private String engineVersion;
    private long lastCalendar;
    private long lastVisualWorlds;
    private long lastDisplayFailure;
    private Clock clock=Clock.systemUTC();

    @Override public void onEnable() {
        try {
            settings = new Settings(this); visuals = new SeasonVisualResolver(settings.visuals, settings.climate,settings.calendar::previous);
            state = new StateStore(getDataFolder().toPath().resolve("state"));
            // A previous completion counter never proves a chunk edit was saved.
            // Revalidate cleanup on startup before reporting removal-safe again.
            if (state.clean) { state.clean = false; state.cleanup = true; state.save(); }
            refreshSeason(false);
            snow = new SeasonalSnowService(this, this::settings, () -> season == Season.WINTER && !state.cleanup && !state.clean);
            snow.setMeltCapture(()->melting()&&state.meltColumns>0?state.meltOrderVersion:"");
            integrations = new IntegrationManager(this, this::settings);
            integrations.setVisuals(()->mapVisualState);
            engine = new ChunkMigrationEngine(this);
            integrations.setRegionReadiness(engine::regionReady);
            Bukkit.getPluginManager().registerEvents(snow, this);
            weather = new WeatherService(this,world->presentation!=null&&presentation.snowRevealPending(world));
            presentation = new NativeSnowPresentation(this, world -> settings.weather && settings.winterSnowfall
                && eligible(world) && season == Season.WINTER && !state.cleanup && !state.clean, () -> settings.climate,
                world -> visualState.active(world.getUID()) ? visualState.season() : null, () -> visuals,
                snow,this::snowCoverageColumns,()->melting()&&!state.meltOrderVersion.isEmpty()?state.meltOrderVersion:version,
                this::winterEnabled,()->settings.snowPlayerBreakCooldownSeconds,this::melting);
            presentation.snowUpdateLimits(settings.snowPresentation);
            presentation.leafUpdateLimits(settings.leafPresentation);
            presentation.colorFrameSource(()->visualState.colors());
            presentation.visualStateSource(()->visualState);
            Bukkit.getPluginManager().registerEvents(weather, this);
            replenishment = new SnowReplenishmentService(this,snow,this::settings,this::version,
                world -> season==Season.WINTER&&eligible(world)&&!state.cleanup&&!state.clean
                    &&settings.enabled&&settings.snow&&settings.weather&&settings.winterSnowfall,
                world -> season==Season.WINTER&&eligible(world)&&!state.cleanup&&!state.clean&&!state.paused
                    &&settings.enabled&&settings.snow&&settings.weather&&settings.winterSnowfall&&world.hasStorm()
                    &&engine.worldFinished(world)&&engine.currentTps()>=settings.minTps,
                integrations::chunkUpdated,chunk -> integrations.chunkCompleted(chunk,false));
            snow.setPlacementGuard(replenishment::placementAllowed);
            presentation.onPreviewBreak(notice->{
                World world=Bukkit.getWorld(NamespacedKey.fromString(notice.dimension()));
                if(world!=null){if(melting(world))snow.clearMeltColumn(world,notice.x(),notice.z());else replenishment.previewBroken(world,notice.x(),notice.z());}
            });
            localSnow = new LocalWinterSnowService(this, snow, this::settings, this::version, this::winterEnabled,
                world -> !state.paused&&localSnowTransition(world)&&engine.currentTps()>=settings.minTps,
                engine::globalCoverageColumns, replenishment, presentation::previewSnow, presentation::previewStateId);
            localSnow.melting(world->melting(world)&&state.meltColumns>0,()->state.meltOrderVersion);
            localSnow.transitioning(this::localSnowTransition);
            localSnow.previewMissing(presentation::previewMissing);
            for (World world : Bukkit.getWorlds()) engine.addWorld(world);
            engineVersion=version;
            announcements=new SeasonAnnouncements(state,this::settings,Bukkit::getOnlinePlayers,this::persist);
            announcements.resume(seasonVersion);
            if(state.cleanup||state.clean)announcements.cancel();
            SeasonsCommand command = new SeasonsCommand(this);
            Bukkit.getPluginManager().registerEvents(command,this);
            Objects.requireNonNull(getCommand("trueseasons")).setExecutor(command);
            getCommand("trueseasons").setTabCompleter(command);
            Bukkit.getServicesManager().register(TrueSeasonsApi.class, this, this, ServicePriority.Normal);
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                String stage="season and calendar update";
                try {
                    if (System.currentTimeMillis() - lastCalendar > 60_000) { refreshSeason(true); lastCalendar = System.currentTimeMillis(); }
                    if (System.currentTimeMillis() - lastVisualWorlds >= 1000) { if(updateVisualState())persist(); lastVisualWorlds = System.currentTimeMillis(); }
                    stage="terrain migration";engine.tick();
                    stage="BlueMap update";if(state.paused)integrations.maintain();else integrations.tick();
                    stage="seasonal weather";weather.tick();
                    stage="snow replenishment";replenishment.tick();
                } catch (Exception failure) { pauseAfterFailure(stage,failure); }
                try {
                    presentation.tick();localSnow.tick();
                    command.tick(Bukkit.getCurrentTick());
                    // At most once per second while a transition awaits completion.
                    if(Bukkit.getCurrentTick()%20==0&&announcements.pending(seasonVersion))
                        announcements.complete(seasonVersion,engine.worldEditsComplete(),integrations.pending());
                } catch (Exception failure) {
                    long now=System.nanoTime();
                    if(lastDisplayFailure==0||now-lastDisplayFailure>=60_000_000_000L){
                        lastDisplayFailure=now;
                        getLogger().log(Level.WARNING,"Seasonal display refresh failed; terrain migration remains independent",failure);
                    }
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
        if (localSnow != null) localSnow.close();
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
    public LocalWinterSnowService localSnow() { return localSnow; }
    public NativeSnowPresentation presentation() { return presentation; }
    public boolean melting(){return settings.snowmelt&&season!=Season.WINTER&&!state.cleanup&&!state.clean&&version.equals(state.meltVersion);}
    public boolean melting(World world){return melting()&&eligible(world);}
    public int snowCoverageColumns(){return melting()?state.meltColumns:engine.globalCoverageColumns();}
    public SeasonalVisualState visualState() { return visualState; }
    public Season currentSeason() { return season; }
    public String version() { return version; }
    public boolean eligible(World world) {
        return world.getEnvironment() == World.Environment.NORMAL && (settings.worlds.isEmpty() || settings.worlds.contains(world.getName()));
    }
    public void persist() {
        try { state.save(); } catch (IOException failure) { throw new UncheckedIOException("Cannot persist job intent", failure); }
    }
    private void pauseAfterFailure(String stage,Exception failure){
        String detail=Objects.toString(failure.getMessage(),failure.getClass().getSimpleName()).replace('\n',' ').replace('\r',' ');
        String reason="Automatic pause during "+stage+": "+detail;
        if(reason.length()>240)reason=reason.substring(0,237)+"...";
        boolean changed=!state.paused||!reason.equals(state.pauseReason);
        if(!state.paused)state.pausedCalendarProgress=getSeasonProgress(null);
        state.paused=true;state.pauseReason=reason;
        if(!changed)return;
        getLogger().log(Level.SEVERE,reason,failure);
        try{state.save();}catch(IOException saveFailure){getLogger().log(Level.SEVERE,"Could not save migration pause reason",saveFailure);}
        if(announcements!=null)try{announcements.paused(reason);}
        catch(RuntimeException noticeFailure){getLogger().log(Level.WARNING,"Could not deliver the migration pause notice",noticeFailure);}
    }
    private void refreshSeason(boolean migrate) {
        SeasonCalendar.State calendar = settings.calendar.at(clock.instant());
        if(state.paused&&state.lastSeason!=null){
            if(season==null){season=state.lastSeason;seasonVersion=!state.currentVersion.isEmpty()?state.currentVersion:!state.winterVersion.isEmpty()?state.winterVersion:calendar.version()+"-r"+state.revision+"-snow4";version=state.terrainTarget==null?seasonVersion:state.terrainTarget.version();}
            if(updateVisualState())persist();return;
        }
        Season next = state.override == null ? calendar.season() : state.override;
        String nextVersion = (state.override == null ? calendar.version() : "manual-" + next) + "-r" + state.revision + "-snow4";
        String rules=terrainRules();
        if(state.terrainTarget==null&&!state.currentVersion.isEmpty())
            state.terrainTarget=new TerrainTarget(state.currentVersion,settings.snow&&state.lastSeason==Season.WINTER,rules);
        TerrainTarget target=TerrainTarget.resolve(state.terrainTarget,nextVersion,settings.snow&&next==Season.WINTER,rules);
        boolean terrainChanged=!target.equals(state.terrainTarget);
        if (nextVersion.equals(seasonVersion)&&!terrainChanged) {
            if(updateVisualState())persist();
            // A failed intent save may have paused after changing the target but before restarting jobs.
            if(migrate&&engine!=null&&!version.equals(engineVersion)&&!state.cleanup&&!state.clean){engine.restart();engineVersion=version;}
            return;
        }
        Season previous = season==null?state.lastSeason:season; season = next; seasonVersion = nextVersion;
        state.terrainTarget=target;version=target.version();state.currentVersion=seasonVersion;
        state.seasonTransition(previous,next,version);
        if(previous==Season.WINTER&&next!=Season.WINTER&&!state.cleanup&&!state.clean
                &&!(settings.snowTransition&&settings.enabled&&settings.snow&&settings.weather&&settings.winterSnowfall)){state.meltColumns=256;state.meltStartColumns=256;}
        updateVisualState();
        if(!state.winterVersion.equals(version)) { state.winterVersion=version; state.winterColumns=0; }
        persist();
        if (migrate && !state.cleanup && !state.clean) {
            if(terrainChanged&&localSnow!=null)localSnow.invalidate();
            if(presentation!=null)presentation.tick();
            if(terrainChanged||!version.equals(engineVersion)){engine.restart();engineVersion=version;}
            if(announcements!=null)announcements.start(previous,next,seasonVersion);
            for (World world : Bukkit.getWorlds()) if (eligible(world))
                Bukkit.getPluginManager().callEvent(new SeasonChangeEvent(world, previous, next));
        }
    }
    private String terrainRules(){
        return settings.snow+"/"+settings.allExposed+"/"+settings.groundCover+"/"+new TreeSet<>(settings.worlds)
            +"/"+(settings.allExposed?"*":new TreeSet<>(settings.surfaces).toString())+"/"+settings.climate.signature(Season.WINTER);
    }
    public void setSeason(Season override) {
        if (state.cleanup) throw new IllegalStateException("Cleanup is active; wait for completion");
        if(state.paused)throw new IllegalStateException("Resume transitions before changing the season");
        state.override = override; state.clean = false; state.revision++; persist();
        refreshSeason(true);
    }
    public void reloadSettings() throws Exception {
        Settings next = new Settings(this);
        SeasonVisualResolver nextVisuals = new SeasonVisualResolver(next.visuals, next.climate,next.calendar::previous);
        boolean snowRulesChanged=settings.snow!=next.snow||settings.allExposed!=next.allExposed
            ||!settings.surfaces.equals(next.surfaces)
            ||!settings.worlds.equals(next.worlds)||settings.groundCover!=next.groundCover||!settings.climate.sameWinter(next.climate);
        boolean areasChanged=settings.playerRadius!=next.playerRadius;
        if(!next.snowmelt){state.meltVersion="";state.meltOrderVersion="";state.meltColumns=0;state.meltStartColumns=0;}
        settings = next; visuals = nextVisuals;
        presentation.snowUpdateLimits(next.snowPresentation);
        presentation.leafUpdateLimits(next.leafPresentation);
        updateVisualState();
        if(areasChanged)engine.invalidatePlayerAreas();
        if(snowRulesChanged)state.revision++;
        persist(); refreshSeason(true);
    }
    public void startCleanup() {
        if (state.cleanup) return;
        state.cleanup = true; state.clean = false; state.paused = false;
        state.pauseReason="";
        state.meltVersion="";state.meltOrderVersion="";state.meltColumns=0;state.meltStartColumns=0;
        updateVisualState(); persist(); engine.restart(); weather.close(); presentation.tick();
        if(announcements!=null)announcements.cancel();
    }
    private boolean winterEnabled(World world) {
        return season == Season.WINTER && eligible(world) && !state.cleanup && !state.clean
            && settings.enabled && settings.snow && settings.weather && settings.winterSnowfall && settings.snowTransition;
    }
    private boolean localSnowTransition(World world){return winterEnabled(world)&&!engine.worldFinished(world)||melting(world)&&state.meltColumns>0;}
    private boolean updateVisualState() {
        SeasonColorFrame frame=state.paused&&state.colorFrame!=null?state.colorFrame:state.override==null&&!state.paused
            ?settings.calendar.colorsAt(clock.instant(),settings.colorOpeningDays):SeasonColorFrame.settled(season);
        if(frame.to()!=season)frame=SeasonColorFrame.settled(season);
        if(frame.from()!=Season.AUTUMN&&frame.to()!=Season.AUTUMN)frame=SeasonColorFrame.settled(season);
        boolean changed=!Objects.equals(state.colorFrame,frame);state.colorFrame=frame;
        Set<UUID> enabledWorlds = new HashSet<>();
        Map<UUID, Long> worldSeeds = new HashMap<>();
        if (!state.cleanup && !state.clean && settings.enabled && visuals.enabled())
            for (World world : Bukkit.getWorlds()) if (eligible(world)) { enabledWorlds.add(world.getUID()); worldSeeds.put(world.getUID(), world.getSeed()); }
        var next = new SeasonalVisualState(visuals, season, enabledWorlds, worldSeeds,frame);
        if (!next.equals(visualState)) visualState = next;
        var endpoint=next.endpoint();if(!endpoint.equals(mapVisualState))mapVisualState=endpoint;
        return changed;
    }
    public void pauseTransitions(boolean paused){
        if(state.paused==paused)return;
        if(paused){refreshSeason(true);state.pausedCalendarProgress=getSeasonProgress(null);}
        state.paused=paused;state.pauseReason=paused?"Paused manually.":"";
        if(!paused)refreshSeason(true);
        persist();
    }
    @Override public Season getCurrentSeason(World world) { return season; }
    @Override public double getSeasonProgress(World world) { return state.override == null ? state.paused?state.pausedCalendarProgress:settings.calendar.at(clock.instant()).progress() : 0; }
    @Override public SeasonVisualProfile getSeasonalVisuals(Location location) {
        World world = Objects.requireNonNull(location.getWorld(), "Location requires a world");
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4))
            throw new IllegalArgumentException("Visual resolution requires an already loaded chunk");
        return visuals.resolve(visualState.colors(), location.getBlock().getBiome().getKey().getKey());
    }
    @Override public String getChunkSeasonalVersion(Chunk chunk) { return snow.version(chunk); }
    @Override public boolean isCleanupActive() { return state.cleanup; }
    @Override public void relinquishSnowOwnership(org.bukkit.block.Block block) { snow.relinquish(block); }
}
