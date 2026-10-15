package dev.trueseasons.snow;

import dev.trueseasons.TrueSeasonsPlugin;
import dev.trueseasons.api.Season;
import dev.trueseasons.migration.*;
import java.lang.reflect.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.persistence.PersistentDataType;

/** Disposable-server gate check: tick real migration without loading any queued chunks. */
public final class SnowmeltRuntimeProbe {
    private static Object get(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static void set(Object object,String name,Object value)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    @SuppressWarnings("unchecked")
    public static int verify(TrueSeasonsPlugin plugin,Chunk chunk)throws Exception{
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Server thread required");
        var state=plugin.state();var config=plugin.settings();var engine=plugin.engine();
        Map<String,Object> savedState=new LinkedHashMap<>(),savedConfig=new LinkedHashMap<>(),savedEngine=new LinkedHashMap<>();
        for(String name:List.of("cleanup","clean","paused","meltVersion","meltOrderVersion","meltColumns","meltStartColumns"))savedState.put(name,get(state,name));
        for(String name:List.of("snowmelt","enabled","worlds","minTps"))savedConfig.put(name,get(config,name));
        for(String name:List.of("tps","inFlight","rotating","tickNumber","applicationTick","applications","applicationNanos","cachedLowTps"))savedEngine.put(name,get(engine,name));
        Map<UUID,ChunkMigrationEngine.Job> jobs=(Map<UUID,ChunkMigrationEngine.Job>)get(engine,"jobs");var oldJobs=new LinkedHashMap<>(jobs);
        var oldManaged=new LinkedHashMap<>(state.managedWorlds);Season oldSeason=plugin.currentSeason();String oldVersion=plugin.version();
        byte[] metadata=chunk.getPersistentDataContainer().serializeToBytes();
        var target=chunk.getBlock(2,chunk.getWorld().getMaxHeight()-2,0);var original=target.getState();
        var ground=target.getRelative(org.bukkit.block.BlockFace.DOWN);var oldGround=ground.getState();
        var above=target.getRelative(org.bukkit.block.BlockFace.UP);var oldAbove=above.getState();int checks=0;
        try{
            for(var key:new HashSet<>(chunk.getPersistentDataContainer().getKeys()))if(key.getNamespace().equals("trueseasons"))chunk.getPersistentDataContainer().remove(key);
            SnowColumns columns=new SnowColumns();columns.put(2,target.getY());
            chunk.getPersistentDataContainer().set(new NamespacedKey(plugin,"snow_v1"),PersistentDataType.BYTE_ARRAY,columns.encode());
            chunk.getPersistentDataContainer().set(new NamespacedKey(plugin,"season_version"),PersistentDataType.STRING,"melt-probe-winter");
            target.setType(Material.SNOW,false);
            ground.setType(Material.GRASS_BLOCK,false);above.setType(Material.AIR,false);
            var lower=Material.TALL_GRASS.createBlockData();var upper=lower.clone();((org.bukkit.block.data.Bisected)upper).setHalf(org.bukkit.block.data.Bisected.Half.TOP);
            GroundCoverColumns cover=new GroundCoverColumns();cover.put(2,new GroundCoverColumns.Plant(target.getY(),lower.getAsString(),upper.getAsString(),ground.getBlockData().getAsString()));
            chunk.getPersistentDataContainer().set(new NamespacedKey(plugin,"ground_cover_v1"),PersistentDataType.BYTE_ARRAY,cover.encode());
            set(plugin,"season",Season.SPRING);set(plugin,"version","melt-probe-spring");
            state.cleanup=false;state.clean=false;state.paused=true;state.meltVersion=plugin.version();state.meltColumns=64;state.meltStartColumns=64;state.meltOrderVersion="melt-probe-winter";
            set(config,"snowmelt",true);set(config,"enabled",true);set(config,"worlds",Set.of(chunk.getWorld().getName()));
            set(config,"minTps",18.5);
            state.managedWorlds.clear();state.managedWorlds.put(chunk.getWorld().getUID().toString(),chunk.getWorld().getName());
            var constructor=ChunkMigrationEngine.Job.class.getDeclaredConstructor(World.class);constructor.setAccessible(true);var job=constructor.newInstance(chunk.getWorld());
            set(job,"scanned",true);set(job,"scanAt",System.currentTimeMillis());job.known.add(new ChunkPos(chunk.getX(),chunk.getZ()));
            jobs.clear();jobs.put(chunk.getWorld().getUID(),job);
            job.known.add(new ChunkPos(chunk.getX()+1,chunk.getZ()));
            set(engine,"tps",new TpsPoller(()->20));
            for(int i=0;i<30;i++)engine.tick();
            check(state.meltColumns==64&&target.getType()==Material.SNOW,"Paused melting leaves coverage and physical snow unchanged");checks++;
            state.paused=false;set(engine,"tps",new TpsPoller(()->0));for(int i=0;i<30;i++)engine.tick();
            check(state.meltColumns==64&&target.getType()==Material.SNOW,"Low TPS cannot advance melting or physical restoration");checks++;
            set(engine,"tps",new TpsPoller(()->20));set(job,"scanned",false);engine.tick();
            check(state.meltColumns==64,"Saved progress is indexed before the fade starts");checks++;
            set(job,"scanned",true);
            for(int i=0;i<5;i++){engine.tick();check(target.getType()==Material.SNOW,"Physical snow stays intact throughout the fade");checks++;}
            check(state.meltColumns==64,"Snowmelt does not advance without physical migration progress");checks++;
            Method apply=engine.getClass().getDeclaredMethod("apply",ChunkMigrationEngine.Job.class,Chunk.class);apply.setAccessible(true);
            check((boolean)apply.invoke(engine,job,chunk)&&target.getBlockData().equals(lower)&&above.getBlockData().equals(upper),"The region pass restores both plant halves while visible melt coverage is still nonzero");checks++;
            var preview=plugin.snow().meltPreview(chunk,state.meltOrderVersion,data->0,true);
            check(preview.melting()&&preview.columns().containsKey(2),"Restoration saves a durable receipt for the remaining client snow");checks++;
            set(job,"done",new HashSet<>(Set.of(new ChunkPos(chunk.getX(),chunk.getZ()))));Method update=engine.getClass().getDeclaredMethod("updateCoverage");update.setAccessible(true);update.invoke(engine);
            check(state.meltColumns==32,"Half of terrain restoration halves the remaining snow coverage");checks++;
            var saved=new dev.trueseasons.state.StateStore(plugin.getDataFolder().toPath().resolve("state"));
            check(saved.meltColumns==32&&saved.meltStartColumns==64,"Restart checkpoints retain both the original melt target and its current progress");checks++;
            set(job,"done",new HashSet<>(job.known));update.invoke(engine);
            check(state.meltColumns==0,"Physical completion drives remaining coverage to zero");checks++;
            check(plugin.snow().isComplete(chunk,plugin.version()),"Restoration still saves the ordinary seasonal completion marker");checks++;
            check(plugin.presentation().diagnostics().viewers()==0,"Diagnostics read real connection counters without creating viewers");checks++;
        }finally{
            original.update(true,false);oldGround.update(true,false);oldAbove.update(true,false);chunk.getPersistentDataContainer().readFromBytes(metadata,true);
            jobs.clear();jobs.putAll(oldJobs);state.managedWorlds.clear();state.managedWorlds.putAll(oldManaged);
            set(plugin,"season",oldSeason);set(plugin,"version",oldVersion);
            for(var entry:savedState.entrySet())set(state,entry.getKey(),entry.getValue());
            for(var entry:savedConfig.entrySet())set(config,entry.getKey(),entry.getValue());
            for(var entry:savedEngine.entrySet())set(engine,entry.getKey(),entry.getValue());
            plugin.persist();
        }
        return checks;
    }
    private static void check(boolean condition,String text){if(!condition)throw new AssertionError(text);}
}
