package dev.trueseasons.presentation;

import dev.trueseasons.TrueSeasonsPlugin;
import dev.trueseasons.api.Season;
import dev.trueseasons.state.StateStore;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.*;
import java.util.Objects;

/** Main-thread clock jumps in a disposable server, before any tick can edit terrain. */
public final class SeasonColorClockRuntimeProbe {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    public static int verify(TrueSeasonsPlugin plugin)throws Exception{
        if(!Files.exists(Path.of("region-verification-clone.txt")))throw new IllegalStateException("Disposable region clone required");
        Field clock=TrueSeasonsPlugin.class.getDeclaredField("clock");clock.setAccessible(true);Object original=clock.get(plugin);
        Method refresh=TrueSeasonsPlugin.class.getDeclaredMethod("refreshSeason",boolean.class);refresh.setAccessible(true);
        Field season=TrueSeasonsPlugin.class.getDeclaredField("season"),version=TrueSeasonsPlugin.class.getDeclaredField("version");season.setAccessible(true);version.setAccessible(true);
        checks=0;
        try{
            clock.set(plugin,Clock.fixed(Instant.parse("2026-09-02T07:00:00Z"),ZoneOffset.UTC));plugin.setSeason(null);
            check(plugin.currentSeason()==Season.AUTUMN&&plugin.visualState().colors().progress()>0&&plugin.visualState().colors().progress()<256,"Automatic season starts at the current opening-week stage");
            var partial=plugin.visualState().colors();
            plugin.setSeason(Season.AUTUMN);check(plugin.visualState().colors().progress()==256,"Manual season command changes colors immediately");
            plugin.setSeason(null);check(plugin.visualState().colors().equals(partial),"Calendar sync returns directly to the current stage");
            Field adapter=plugin.integrations().getClass().getDeclaredField("blueMap");adapter.setAccessible(true);
            Object map=adapter.get(plugin.integrations());Field source=map.getClass().getDeclaredField("visualSource");source.setAccessible(true);
            var mapSource=(java.util.function.Supplier<?>)source.get(map);Object endpoint=mapSource.get();
            check(((dev.trueseasons.visual.SeasonalVisualState)endpoint).colors().progress()==256,"BlueMap receives the final season while players see a partial transition");
            clock.set(plugin,Clock.fixed(Instant.parse("2026-09-04T07:00:00Z"),ZoneOffset.UTC));refresh.invoke(plugin,false);
            check(plugin.visualState().colors().progress()>partial.progress()&&mapSource.get()==endpoint,"Advancing player colors does not change the map target or request another color pass");
            partial=plugin.visualState().colors();
            plugin.pauseTransitions(true);String target=plugin.version();double progress=plugin.getSeasonProgress(null);
            int winter=plugin.state().winterColumns,melt=plugin.state().meltColumns;
            clock.set(plugin,Clock.fixed(Instant.parse("2026-12-03T08:00:00Z"),ZoneOffset.UTC));refresh.invoke(plugin,false);
            check(plugin.version().equals(target)&&plugin.currentSeason()==Season.AUTUMN&&plugin.visualState().colors().equals(partial),"Pause freezes the season, migration target and color stage across a calendar boundary");
            check(plugin.getSeasonProgress(null)==progress&&plugin.state().winterColumns==winter&&plugin.state().meltColumns==melt,"Pause retains calendar and snow progress");
            StateStore restored=new StateStore(plugin.getDataFolder().toPath().resolve("state"));
            check(restored.paused&&restored.colorFrame.equals(partial)&&restored.currentVersion.equals(target),"Paused target is durable on disk");
            season.set(plugin,null);version.set(plugin,null);refresh.invoke(plugin,false);
            check(plugin.currentSeason()==Season.AUTUMN&&plugin.version().equals(target)&&plugin.visualState().colors().equals(partial),"Startup while paused restores the saved target rather than the current calendar");
            plugin.pauseTransitions(false);
            check(plugin.currentSeason()==Season.WINTER&&plugin.visualState().colors().progress()>0&&plugin.visualState().colors().progress()<256,"Calendar resume resynchronizes directly to the current winter stage");
            plugin.setSeason(Season.SUMMER);String manual=plugin.version();var appearance=plugin.visualState().colors();
            plugin.pauseTransitions(true);clock.set(plugin,Clock.fixed(Instant.parse("2027-02-01T08:00:00Z"),ZoneOffset.UTC));refresh.invoke(plugin,false);plugin.pauseTransitions(false);
            check(plugin.version().equals(manual)&&plugin.currentSeason()==Season.SUMMER&&Objects.equals(plugin.visualState().colors(),appearance),"Manual resume keeps its saved target and settled colors");
            return checks;
        }finally{
            clock.set(plugin,original);
            if(plugin.state().paused)plugin.pauseTransitions(false);
            plugin.setSeason(Season.SPRING);
        }
    }
}
