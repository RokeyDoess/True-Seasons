package dev.trueseasons.ambience;

import dev.trueseasons.TrueSeasonsPlugin;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** Read-only API boundary checks, called inside the disposable Paper test server. */
public final class FireflyRuntimeProbe {
    private FireflyRuntimeProbe() {}
    public static int verify(TrueSeasonsPlugin plugin) throws Exception {
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Primary thread required");
        if(plugin.settings().fireflies.sounds())throw new IllegalStateException("Probe requires default disabled sounds");
        class Habitat {
            boolean loaded=true,storm;
            long time=18000;
            Biome biome=Biome.PLAINS;
            int chunks,particles;
            World world;
            Block block(int y){return proxy(Block.class,(object,method,args)->switch(method.getName()){
                case "getType" -> y==63?Material.WATER:y==64?Material.GRASS_BLOCK:Material.AIR;
                case "getLightFromSky" -> (byte)15;
                default -> throw new AssertionError("Unexpected block operation: "+method.getName());
            });}
        }
        Habitat h=new Habitat();
        Chunk chunk=proxy(Chunk.class,(object,method,args)->{
            if(method.getName().equals("getBlock"))return h.block((int)args[1]);
            throw new AssertionError("Unexpected chunk operation: "+method.getName());
        });
        h.world=proxy(World.class,(object,method,args)->switch(method.getName()){
            case "getName" -> plugin.getServer().getWorlds().getFirst().getName();
            case "getEnvironment" -> World.Environment.NORMAL;
            case "getTime" -> h.time;
            case "hasStorm" -> h.storm;
            case "isChunkLoaded" -> h.loaded;
            case "getHighestBlockYAt" -> {if(!h.loaded)throw new AssertionError("Unloaded height read");yield 64;}
            case "getBiome" -> {if(!h.loaded)throw new AssertionError("Unloaded biome read");yield h.biome;}
            case "getMinHeight" -> 0;
            case "getMaxHeight" -> 320;
            case "getChunkAt" -> {if(!h.loaded)throw new AssertionError("Fireflies requested an unloaded chunk");h.chunks++;yield chunk;}
            default -> throw new AssertionError("Unexpected world operation: "+method.getName());
        });
        Player player=proxy(Player.class,(object,method,args)->switch(method.getName()){
            case "getWorld" -> h.world;
            case "getLocation" -> new Location(h.world,8,65,8);
            case "spawnParticle" -> {
                if(args[0]!=Particle.FIREFLY||(int)args[2]!=1)throw new AssertionError("Unexpected ambient particle");
                h.particles++;yield null;
            }
            default -> throw new AssertionError("Unexpected player operation (sounds must stay off): "+method.getName());
        });
        var service=new SummerFireflyService(plugin);
        Method emit=SummerFireflyService.class.getDeclaredMethod("emit",Player.class,long.class);emit.setAccessible(true);
        if(!(boolean)emit.invoke(service,player,20L)||h.particles!=1||h.chunks>4)throw new AssertionError("Native firefly at loaded summer water edge with bounded chunk lookups");
        h.loaded=false;int reads=h.chunks;
        if((boolean)emit.invoke(service,player,40L)||h.chunks!=reads)throw new AssertionError("Unloaded candidate must be skipped without terrain reads");
        h.loaded=true;h.biome=Biome.OCEAN;
        if((boolean)emit.invoke(service,player,60L))throw new AssertionError("Ocean must be excluded");
        h.biome=Biome.SNOWY_PLAINS;
        if((boolean)emit.invoke(service,player,80L))throw new AssertionError("Summer-exempt cold biome must be excluded");
        h.biome=Biome.PLAINS;h.time=6000;
        if((boolean)emit.invoke(service,player,100L))throw new AssertionError("Daytime must be excluded");
        h.time=18000;h.storm=true;
        if((boolean)emit.invoke(service,player,120L)||h.particles!=1)throw new AssertionError("Storm must be excluded without extra particles");
        AtomicInteger shown=new AtomicInteger();
        var budget=FireflyBudget.visit(java.util.Collections.nCopies(100,player),0,16,8,p->{shown.incrementAndGet();return true;});
        if(budget.particles()!=8||shown.get()!=8)throw new AssertionError("Particle cap must be shared across players");
        h.storm=false;
        World excluded=proxy(World.class,(object,method,args)->switch(method.getName()){
            case "getName" -> "firefly-excluded-test-world";
            case "getEnvironment" -> World.Environment.NETHER;
            default -> throw new AssertionError("Excluded viewers must require no terrain or time reads: "+method.getName());
        });
        Player excludedPlayer=proxy(Player.class,(object,method,args)->{
            if(method.getName().equals("getWorld"))return excluded;
            throw new AssertionError("Excluded viewer must not be sampled: "+method.getName());
        });
        var online=new java.util.ArrayList<Player>(java.util.Collections.nCopies(100,excludedPlayer));online.add(player);
        var wasted=FireflyBudget.visit(online,0,16,8,p->{try{return (boolean)emit.invoke(service,p,140L);}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}});
        if(wasted.attempts()!=16||wasted.particles()!=0)throw new AssertionError("Mixed-world regression fixture must reproduce spent turns without habitat samples");
        Method eligible=SummerFireflyService.class.getDeclaredMethod("eligibleViewers",java.util.Collection.class);eligible.setAccessible(true);
        @SuppressWarnings("unchecked") var viewers=(java.util.List<Player>)eligible.invoke(service,online);
        if(viewers.size()!=1||viewers.getFirst()!=player)throw new AssertionError("Unsupported worlds consumed eligible summer viewer turns");
        var shared=FireflyBudget.visit(viewers,100,16,8,p->{try{return (boolean)emit.invoke(service,p,160L);}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}});
        if(shared.attempts()!=8||shared.particles()!=8)throw new AssertionError("Eligible player failed to use the unchanged shared allowance");
        h.time=6000;
        if(!((java.util.List<?>)eligible.invoke(service,java.util.List.of(player))).isEmpty())throw new AssertionError("Daytime viewer consumed habitat turns");
        h.time=18000;h.storm=true;
        if(!((java.util.List<?>)eligible.invoke(service,java.util.List.of(player))).isEmpty())throw new AssertionError("Stormy viewer consumed habitat turns");
        return 12;
    }
    private static <T> T proxy(Class<T> type,InvocationHandler handler){
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));
    }
}
