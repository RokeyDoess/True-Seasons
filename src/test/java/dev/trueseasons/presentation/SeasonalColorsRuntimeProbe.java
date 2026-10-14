package dev.trueseasons.presentation;

import dev.trueseasons.TrueSeasonsPlugin;
import dev.trueseasons.api.Season;
import dev.trueseasons.integration.BlueMapVisibleColorProbe;
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
    @Override public void onEnable() {
        if (!Files.exists(Path.of("seasonal-colors-clone.txt"))) throw new IllegalStateException("Controlled colour clone required");
        seasons=(TrueSeasonsPlugin)Bukkit.getPluginManager().getPlugin("TrueSeasons");
        world=Bukkit.getWorlds().stream().filter(w->w.getEnvironment()==World.Environment.NORMAL).findFirst().orElseThrow();
        Bukkit.getScheduler().runTaskTimer(this,()->{
            try {
                if(++ticks>12000)throw new AssertionError("Colour render/cleanup timed out: "+seasons.integrations().status());
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
                    seasons.startCleanup();phase=2;
                }else if(phase==2 && seasons.state().clean && seasons.integrations().pending()==0){
                    if(seasons.visualState().active(world.getUID()))throw new AssertionError("Cleanup left seasonal colours active");
                    Files.writeString(Path.of("seasonal-colors-result.txt"),visibleResult+"\nPASS: Real BlueMap autumn renders and vanilla restoration drained; no manual map update.");
                    getLogger().info("PROBE_SEASONAL_COLORS_PASSED");Bukkit.shutdown();
                }
            }catch(Throwable failure){getLogger().log(java.util.logging.Level.SEVERE,"PROBE_SEASONAL_COLORS_FAILED",failure);Bukkit.shutdown();}
        },20,1);
    }
}
