package dev.trueseasons.presentation;

import dev.trueseasons.TrueSeasonsPlugin;
import dev.trueseasons.api.Season;
import dev.trueseasons.integration.BlueMapVisibleColorProbe;
import dev.trueseasons.integration.BlueMapPaletteScopeProbe;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;

/** End-to-end colour render and restoration receipts in a guarded disposable clone. */
public final class SeasonalColorsRuntimeProbe extends JavaPlugin {
    private TrueSeasonsPlugin seasons;
    private World world;
    private int ticks, phase;
    private CompletableFuture<String> visibleColors;
    private String visibleResult;
    private Map<String,String> completedPalettes;
    private int reloadTick;
    private String originalVisuals,originalConfig;
    private boolean verifyUpgrade;
    @Override public void onEnable() {
        if (!Files.exists(Path.of("seasonal-colors-clone.txt"))) throw new IllegalStateException("Controlled colour clone required");
        seasons=(TrueSeasonsPlugin)Bukkit.getPluginManager().getPlugin("TrueSeasons");
        world=Bukkit.getWorlds().stream().filter(w->w.getEnvironment()==World.Environment.NORMAL).findFirst().orElseThrow();
        verifyUpgrade=Files.exists(Path.of("palette-legacy-upgrade.txt"));
        if(verifyUpgrade)try{
            var saved=BlueMapPaletteScopeProbe.snapshot(seasons.integrations());
            if(saved.values().stream().noneMatch(value->value.startsWith(seasons.visualState().legacyFingerprint(world.getUID())+"/generation=")))throw new AssertionError("Expected saved published-format autumn target");
        }catch(Exception failure){throw new IllegalStateException("Upgrade fixture unavailable",failure);}
        Bukkit.getScheduler().runTaskTimer(this,()->{
            try {
                if(++ticks>12000)throw new AssertionError("Colour render/cleanup timed out: "+seasons.integrations().status());
                if(phase==0&&verifyUpgrade)BlueMapPaletteScopeProbe.snapshot(seasons.integrations());
                if(phase==0 && seasons.integrations().status().startsWith("connected") && seasons.engine().worldFinished(world)
                        && seasons.integrations().pending()==0){
                    if(!seasons.visualState().active(world.getUID())||seasons.currentSeason()!=Season.AUTUMN)
                        throw new AssertionError("Autumn colour snapshot inactive");
                    if(seasons.integrations().status().contains("seasonal colours unavailable"))throw new AssertionError(seasons.integrations().status());
                    var snapshot=seasons.visualState();
                    var map=de.bluecolored.bluemap.api.BlueMapAPI.getInstance().orElseThrow().getWorld(world).orElseThrow()
                        .getMaps().stream().filter(candidate->!candidate.isFrozen()).findFirst().orElseThrow();
                    visibleColors=CompletableFuture.supplyAsync(()->{
                        try{return BlueMapVisibleColorProbe.verify(map,snapshot);}
                        catch(Exception failure){throw new CompletionException(failure);}
                    });phase=1;
                }else if(phase==1 && visibleColors.isDone()){
                    visibleResult=visibleColors.join();getLogger().info(visibleResult);
                    completedPalettes=BlueMapPaletteScopeProbe.snapshot(seasons.integrations());
                    Path visuals=seasons.getDataFolder().toPath().resolve("visuals.yml"),config=seasons.getDataFolder().toPath().resolve("config.yml");
                    originalVisuals=Files.readString(visuals);originalConfig=Files.readString(config);
                    var colors=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(visuals.toFile());
                    colors.set("palettes.temperate.spring",List.of("123456","ABCDEF"));colors.save(visuals.toFile());
                    var settings=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(config.toFile());
                    settings.set("climate.summer-excluded-biome-fragments",List.of("snowy","frozen","minecraft:grove","ice_spikes","cold_ocean","deep_dark"));settings.save(config.toFile());
                    String before=seasons.visualState().fingerprint(world.getUID());seasons.reloadSettings();
                    if(!before.equals(seasons.visualState().fingerprint(world.getUID())))throw new AssertionError("Inactive-season edit changed autumn map target");
                    reloadTick=ticks;phase=2;
                }else if(phase==2||phase==3){
                    if(!completedPalettes.equals(BlueMapPaletteScopeProbe.snapshot(seasons.integrations())))throw new AssertionError("Inactive-season reload reopened completed map regions");
                    if(ticks-reloadTick>=120){
                        if(phase==2){
                            Files.writeString(seasons.getDataFolder().toPath().resolve("visuals.yml"),originalVisuals);
                            Files.writeString(seasons.getDataFolder().toPath().resolve("config.yml"),originalConfig);
                            seasons.reloadSettings();reloadTick=ticks;phase=3;
                        }else{
                            getLogger().info("PALETTE_SCOPE_PASSED: inactive-season edits and restoration scheduled no extra palette renders");
                            seasons.startCleanup();phase=4;
                        }
                    }
                }else if(phase==4 && seasons.state().clean && seasons.integrations().pending()==0){
                    if(seasons.visualState().active(world.getUID()))throw new AssertionError("Cleanup left seasonal colours active");
                    Files.writeString(Path.of("seasonal-colors-result.txt"),visibleResult+"\nPASS: Inactive-season reloads preserved completed map regions. Real BlueMap autumn renders and vanilla restoration drained; no manual map update.");
                    getLogger().info("PROBE_SEASONAL_COLORS_PASSED");Bukkit.shutdown();
                }
            }catch(Throwable failure){getLogger().log(java.util.logging.Level.SEVERE,"PROBE_SEASONAL_COLORS_FAILED",failure);Bukkit.shutdown();}
        },20,1);
    }
}
