package dev.trueseasons.weather;

import dev.trueseasons.TrueSeasonsPlugin;
import dev.trueseasons.api.*;
import dev.trueseasons.config.Settings;
import dev.trueseasons.state.StateStore;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Weather restoration waits for the actual client reveal, without a timer or terrain restart. */
public final class WinterStormRevealTests {
    private static int checks;
    public static void main(String[] args)throws Exception{
        try(Fixture fixture=new Fixture()){
            fixture.start();check(fixture.rain&&!fixture.thunder&&fixture.weather.activeStorms()==1,"Migration starts the native opening storm");
            fixture.terrain.set(true);fixture.reveal.set(true);fixture.complete();
            check(fixture.rain&&fixture.weather.activeStorms()==1,"Terrain completion event retains storm while a viewer is catching up");
            for(int tick=0;tick<1500;tick++)fixture.weather.tick();
            check(fixture.rain&&fixture.weather.activeStorms()==1,"A reveal backlog has no fixed storm deadline");
            var clear=new WeatherChangeEvent(fixture.world,false,WeatherChangeEvent.Cause.NATURAL);fixture.weather.keepMigrationStorm(clear);
            check(clear.isCancelled(),"Natural clearing waits for pending ground and layer updates too");
            fixture.reveal.set(false);fixture.weather.tick();
            check(fixture.weather.activeStorms()==0&&!fixture.rain&&fixture.thunder,"Final reveal restores previous rain and thunder exactly");
            check(fixture.rainTicks==321&&fixture.thunderTicks==654&&fixture.clearTicks==987,"All original weather durations are restored");
            int writes=fixture.writes;fixture.weather.tick();fixture.weather.tick();fixture.complete();
            check(fixture.writes==writes,"Finished weather is not repeatedly restored");
            fixture.start();check(fixture.weather.activeStorms()==0,"The same completed winter does not start a second opening storm");
        }
        try(Fixture fixture=new Fixture()){
            fixture.start();fixture.reveal.set(false);fixture.weather.tick();
            check(fixture.weather.activeStorms()==1,"Unfinished terrain still holds the storm with no viewers");
            fixture.plugin.state().paused=true;fixture.weather.tick();
            check(fixture.rain,"Pausing migration does not end the storm");
            fixture.terrain.set(true);fixture.complete();check(!fixture.rain,"With no pending client work completion restores weather immediately");
        }
        for(String exit:List.of("spring","cleanup","weather-disabled","storm-disabled","new-version"))try(Fixture fixture=new Fixture()){
            fixture.start();fixture.terrain.set(true);fixture.reveal.set(true);
            switch(exit){
                case "spring"->set(fixture.plugin,TrueSeasonsPlugin.class,"season",Season.SPRING);
                case "cleanup"->fixture.plugin.state().cleanup=true;
                case "weather-disabled"->set(fixture.settings,Settings.class,"weather",false);
                case "storm-disabled"->set(fixture.settings,Settings.class,"winterStorm",false);
                case "new-version"->set(fixture.plugin,TrueSeasonsPlugin.class,"version","next-winter");
            }
            fixture.weather.tick();check(!fixture.rain&&fixture.weather.activeStorms()==0,"Pending packets cannot prevent restoration after "+exit);
        }
        try(Fixture fixture=new Fixture()){
            fixture.start();fixture.terrain.set(true);fixture.reveal.set(true);fixture.weather.close();
            check(!fixture.rain&&fixture.weather.activeStorms()==0,"Shutdown restores weather without waiting on disconnected clients");
            fixture.weather=fixture.open();fixture.start();check(fixture.weather.activeStorms()==0,"A purely client backlog does not restart the storm after a reconnect/restart");
        }
        try(Fixture fixture=new Fixture()){
            fixture.start();fixture.weather.close();fixture.weather=fixture.open();fixture.start();
            check(fixture.rain&&fixture.weather.activeStorms()==1,"Real unfinished terrain resumes its opening storm after restart");
        }
        System.out.println("Winter storm reveal tests passed: "+checks+" assertions");
    }
    private static final class Fixture implements AutoCloseable{
        final Path folder=Files.createTempDirectory("trueseasons-storm-reveal-");
        final Settings settings=allocate(Settings.class);
        final TrueSeasonsPlugin plugin=allocate(TrueSeasonsPlugin.class);
        final UUID id=UUID.randomUUID();
        final AtomicBoolean terrain=new AtomicBoolean(),reveal=new AtomicBoolean();
        boolean rain,thunder=true;int rainTicks=321,thunderTicks=654,clearTicks=987,writes;
        final World world=(World)Proxy.newProxyInstance(World.class.getClassLoader(),new Class<?>[]{World.class},(p,method,args)->switch(method.getName()){
            case "getUID"->id;case "getName"->"storm-fixture";case "getEnvironment"->World.Environment.NORMAL;
            case "hasStorm"->rain;case "isThundering"->thunder;case "getWeatherDuration"->rainTicks;
            case "getThunderDuration"->thunderTicks;case "getClearWeatherDuration"->clearTicks;
            case "setStorm"->{rain=(boolean)args[0];writes++;yield null;}
            case "setThundering"->{thunder=(boolean)args[0];writes++;yield null;}
            case "setWeatherDuration"->{rainTicks=(int)args[0];writes++;yield null;}
            case "setThunderDuration"->{thunderTicks=(int)args[0];writes++;yield null;}
            case "setClearWeatherDuration"->{clearTicks=(int)args[0];writes++;yield null;}
            case "equals"->p==args[0];case "hashCode"->System.identityHashCode(p);case "toString"->"storm-fixture";
            default->throw new AssertionError("Unexpected world call: "+method.getName());
        });
        WeatherService weather;
        Fixture()throws Exception{
            Files.createDirectories(folder.resolve("state"));
            set(settings,Settings.class,"worlds",Set.of());set(settings,Settings.class,"weather",true);set(settings,Settings.class,"winterStorm",true);
            set(plugin,TrueSeasonsPlugin.class,"settings",settings);set(plugin,TrueSeasonsPlugin.class,"state",new StateStore(folder.resolve("state")));
            set(plugin,TrueSeasonsPlugin.class,"season",Season.WINTER);set(plugin,TrueSeasonsPlugin.class,"version","winter-one");
            set(plugin,JavaPlugin.class,"dataFolder",folder.toFile());Logger logger=Logger.getAnonymousLogger();logger.setUseParentHandlers(false);set(plugin,JavaPlugin.class,"logger",logger);
            weather=open();
        }
        WeatherService open()throws Exception{return new WeatherService(plugin,w->reveal.get(),w->terrain.get(),key->key.equals(id)?world:null);}
        void start(){weather.migration(new SeasonMigrationEvent(world,false,SeasonMigrationEvent.Phase.START));}
        void complete(){weather.migration(new SeasonMigrationEvent(world,false,SeasonMigrationEvent.Phase.COMPLETE));}
        public void close(){weather.close();}
    }
    private static <T>T allocate(Class<T> type)throws Exception{Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field field=unsafe.getDeclaredField("theUnsafe");field.setAccessible(true);return type.cast(unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),type));}
    private static void set(Object target,Class<?> owner,String name,Object value)throws Exception{Field field=owner.getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static void check(boolean result,String message){if(!result)throw new AssertionError(message);checks++;}
}
