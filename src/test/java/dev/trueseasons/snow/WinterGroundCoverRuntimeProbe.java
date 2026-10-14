package dev.trueseasons.snow;

import dev.trueseasons.api.Season;
import dev.trueseasons.config.Settings;
import dev.trueseasons.migration.PlayerNeighborhood;
import java.util.*;
import java.util.function.Supplier;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.*;
import org.bukkit.block.data.type.FlowerBed;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Real incremental vegetation/snow ordering, only on a loaded disposable high-altitude fixture. */
public final class WinterGroundCoverRuntimeProbe {
    private WinterGroundCoverRuntimeProbe() {}
    public static int verify(JavaPlugin probe, Supplier<Settings> settings, Chunk chunk) throws Exception {
        if (!chunk.isLoaded() || !Bukkit.isPrimaryThread()) throw new IllegalStateException("Loaded main-thread clone fixture required");
        if (!settings.get().groundCover) throw new IllegalStateException("Ground-cover option must be enabled for this probe");
        SeasonalSnowService snow = new SeasonalSnowService(probe, settings, () -> true);
        var pdc = chunk.getPersistentDataContainer();
        NamespacedKey plants = new NamespacedKey(probe,"ground_cover_v1"), owned = new NamespacedKey(probe,"snow_v1"),
                version = new NamespacedKey(probe,"season_version"), pending = new NamespacedKey(probe,"winter_pending"), cursor = new NamespacedKey(probe,"winter_cursor");
        for (NamespacedKey key : List.of(plants,owned,version,pending,cursor)) if (pdc.has(key)) throw new IllegalStateException("Unused probe namespace required");
        String stamp = "cover-runtime-winter";
        int column = PlayerNeighborhood.columnAt(0,chunk.getX(),chunk.getZ(),stamp);
        int y = chunk.getWorld().getMaxHeight()-4, checks=0;
        Map<Block,BlockState> original=new LinkedHashMap<>(); Map<Block,Biome> biomes=new LinkedHashMap<>();
        for (int at=y;at<chunk.getWorld().getMaxHeight();at++) {
            Block block=chunk.getBlock(column&15,at,column>>4); original.put(block,block.getState()); biomes.put(block,block.getBiome());
        }
        try {
            for(Block block:original.keySet()){block.setType(Material.AIR,false);block.setBiome(Biome.PLAINS);}
            Block support=chunk.getBlock(column&15,y,column>>4), lower=chunk.getBlock(column&15,y+1,column>>4), upper=chunk.getBlock(column&15,y+2,column>>4);
            support.setType(Material.GRASS_BLOCK,false);
            lower.setBlockData(Bukkit.createBlockData("minecraft:leaf_litter[segment_amount=3,facing=west]"),false);
            String exact=lower.getBlockData().getAsString(); int[] intent={0};
            var result=snow.applyStep(chunk,Season.WINTER,stamp,false,1,()->{
                intent[0]++; if(pdc.has(plants)||!lower.getBlockData().getAsString().equals(exact))throw new AssertionError("Intent must precede snapshot/removal");
            });
            if(intent[0]!=1||result.removed()!=1||result.placed()!=1||lower.getType()!=Material.SNOW)throw new AssertionError("Ground cover becomes real snow in the same step"); checks++;
            GroundCoverColumns decoded=GroundCoverColumns.decode(pdc.get(plants,PersistentDataType.BYTE_ARRAY));
            if(decoded.get(column)==null||!decoded.get(column).lower().equals(exact))throw new AssertionError("Exact litter properties are durable");checks++;
            // Dirt below hidden petals can become grass while its region waits.
            // Restoration must retain that natural terrain change and retire the backup.
            snow.applyStep(chunk,Season.SPRING,"cover-runtime-soil-baseline",false,256,()->{});
            support.setType(Material.DIRT,false);
            lower.setBlockData(Bukkit.createBlockData("minecraft:pink_petals[facing=west,flower_amount=4]"),false);
            String petals=lower.getBlockData().getAsString();
            snow.applyStep(chunk,Season.WINTER,"cover-runtime-soil-winter",false,256,()->{});
            if(!pdc.has(plants)||lower.getType()!=Material.SNOW)throw new AssertionError("Petals on dirt are backed up beneath real snow");checks++;
            support.setBlockData(Bukkit.createBlockData("minecraft:grass_block[snowy=true]"),false);
            result=snow.applyStep(chunk,Season.SPRING,"cover-runtime-soil-spring",false,256,()->{});
            if(!result.complete()||result.conflicts()!=0||!lower.getBlockData().getAsString().equals(petals)||pdc.has(plants)||pdc.has(owned))throw new AssertionError("Natural dirt-to-grass conversion must not stall spring restoration");checks++;
            if(support.getType()!=Material.GRASS_BLOCK)throw new AssertionError("Restoring petals must preserve the current grass support");checks++;
            lower.setBlockData(Bukkit.createBlockData(exact),false);
            snow.applyStep(chunk,Season.WINTER,stamp,false,1,()->{});
            result=snow.applyStep(chunk,Season.AUTUMN,"cover-runtime-autumn",false,256,()->{});
            if(!result.complete()||!lower.getBlockData().getAsString().equals(exact)||pdc.has(plants)||pdc.has(owned))throw new AssertionError("Interrupted winter exits to autumn and restores exact cover");checks++;
            // Restart a fresh one-column winter, then exercise actual paired plant placement checks.
            lower.setBlockData(Bukkit.createBlockData("minecraft:tall_grass[half=lower]"),false);
            upper.setBlockData(Bukkit.createBlockData("minecraft:tall_grass[half=upper]"),false);
            result=snow.applyStep(chunk,Season.WINTER,stamp,false,1,()->{});
            if(result.removed()!=2||lower.getType()!=Material.SNOW||!upper.getType().isAir())throw new AssertionError("Both tall halves are hidden before snowfall");checks++;
            result=snow.applyStep(chunk,Season.SPRING,"cover-runtime-spring",false,256,()->{});
            if(!result.complete()||lower.getType()!=Material.TALL_GRASS||upper.getType()!=Material.TALL_GRASS||pdc.has(plants))throw new AssertionError("Tall grass restores both halves on real Paper");checks++;
            // A normal player placement over hidden vegetation relinquishes its backup.
            snow.applyStep(chunk,Season.WINTER,stamp,false,1,()->{});
            BlockState replaced=lower.getState(); lower.setType(Material.DIAMOND_BLOCK,false);
            snow.onPlace(new BlockPlaceEvent(lower,replaced,support,new org.bukkit.inventory.ItemStack(Material.DIAMOND_BLOCK),null,true,EquipmentSlot.HAND));
            snow.applyStep(chunk,Season.SPRING,"cover-runtime-spring-2",false,256,()->{});
            if(lower.getType()!=Material.DIAMOND_BLOCK||pdc.has(plants))throw new AssertionError("Player replacement survives exit without resurrected grass");checks++;
            // A roof is the sampled surface; vegetation below it is never searched or removed.
            lower.setType(Material.POPPY,false);upper.setType(Material.GLASS,false);
            snow.applyStep(chunk,Season.WINTER,stamp,false,1,()->{});
            if(lower.getType()!=Material.POPPY||pdc.has(plants))throw new AssertionError("Sheltered flowers remain unchanged");checks++;
            snow.apply(chunk,Season.SPRING,"cover-runtime-roof-cleanup",true);
            String localVersion="cover-local-runtime";
            int localColumn=PlayerNeighborhood.columnAt(0,chunk.getX(),chunk.getZ(),localVersion);
            int outsideColumn=PlayerNeighborhood.columnAt(64,chunk.getX(),chunk.getZ(),localVersion);
            for(int c:new int[]{localColumn,outsideColumn})for(int at=y;at<chunk.getWorld().getMaxHeight();at++){
                Block b=chunk.getBlock(c&15,at,c>>4);original.putIfAbsent(b,b.getState());biomes.putIfAbsent(b,b.getBiome());
                b.setType(Material.AIR,false);b.setBiome(Biome.PLAINS);
            }
            Block localSupport=chunk.getBlock(localColumn&15,y,localColumn>>4),localPlant=localSupport.getRelative(BlockFace.UP);
            Block outsideSupport=chunk.getBlock(outsideColumn&15,y,outsideColumn>>4);
            localSupport.setType(Material.GRASS_BLOCK,false);localPlant.setType(Material.POPPY,false);outsideSupport.setType(Material.GRASS_BLOCK,false);
            boolean previousStorm=chunk.getWorld().hasStorm();int previousRain=chunk.getWorld().getWeatherDuration(),previousClear=chunk.getWorld().getClearWeatherDuration();
            try{
                chunk.getWorld().setStorm(true);
                var localMethod=SeasonalSnowService.class.getDeclaredMethod("accumulateLocalColumns",Chunk.class,String.class,int.class,int[].class,
                    java.util.function.Consumer.class,java.util.function.Consumer.class,long.class,java.util.function.BooleanSupplier.class);localMethod.setAccessible(true);
                int[] writes={0};
                result=(SeasonalSnowService.Result)localMethod.invoke(snow,chunk,localVersion,64,new int[]{localColumn,outsideColumn},
                    (java.util.function.Consumer<Chunk>)c->writes[0]++,(java.util.function.Consumer<Chunk>)c->{},Long.MAX_VALUE,(java.util.function.BooleanSupplier)()->true);
                if(result.placed()!=1||result.removed()!=1||writes[0]!=1||localPlant.getType()!=Material.SNOW)throw new AssertionError("Local storm hides a plant and places owned real snow");checks++;
                if(outsideSupport.getRelative(BlockFace.UP).getType()!=Material.AIR)throw new AssertionError("Local storm cannot exceed the shared coverage rank");checks++;
                if(pdc.has(version)||pdc.has(pending)||pdc.has(cursor))throw new AssertionError("Local accumulation never certifies migration progress");checks++;
                result=snow.applyStep(chunk,Season.WINTER,localVersion,false,256,()->{});
                if(!result.complete()||localPlant.getType()!=Material.SNOW)throw new AssertionError("Background region completes without removing current local snow");checks++;
                result=snow.apply(chunk,Season.SPRING,"cover-local-cleanup",true);
                if(!result.complete()||localPlant.getType()!=Material.POPPY||pdc.has(plants)||pdc.has(owned))throw new AssertionError("Leaving winter restores locally hidden plants and retires ownership");checks++;
            }finally{
                chunk.getWorld().setStorm(previousStorm);chunk.getWorld().setWeatherDuration(previousRain);chunk.getWorld().setClearWeatherDuration(previousClear);
            }
            // Put the primary fixture back in its baseline state before the species matrix.
            for(Block b:original.keySet())b.setType(Material.AIR,false);
            // Read the production allowlist reflectively because Paper separates plugin loaders.
            Class<?> policy=Class.forName("dev.trueseasons.snow.WinterGroundCover",false,SeasonalSnowService.class.getClassLoader());
            var policyField=policy.getDeclaredField("PLANTS");policyField.setAccessible(true);
            @SuppressWarnings("unchecked") Set<String> allowed=(Set<String>)policyField.get(null);
            List<String> species=new ArrayList<>(allowed);Collections.sort(species);
            for(String name:species) {
                Material material=Material.valueOf(name);
                Material substrate=name.equals("DEAD_BUSH")||name.endsWith("DRY_GRASS")?Material.SAND:name.endsWith("MUSHROOM")?Material.MYCELIUM:Material.GRASS_BLOCK;
                support.setType(substrate,false);lower.setType(Material.AIR,false);upper.setType(Material.AIR,false);
                chunk.getBlock(column&15,y+3,column>>4).setType(Material.AIR,false);
                BlockData bottom=material.createBlockData(),top=null;
                if(bottom instanceof Directional direction && direction.getFaces().contains(BlockFace.EAST))direction.setFacing(BlockFace.EAST);
                if(bottom instanceof FlowerBed bed)bed.setFlowerAmount(bed.getMaximumFlowerAmount());
                if(bottom instanceof Segmentable segments)segments.setSegmentAmount(segments.getMaximumSegmentAmount());
                if(bottom instanceof Bisected half){half.setHalf(Bisected.Half.BOTTOM);top=bottom.clone();((Bisected)top).setHalf(Bisected.Half.TOP);}
                lower.setBlockData(bottom,false);if(top!=null)upper.setBlockData(top,false);
                if(!lower.canPlace(bottom)||top!=null&&!upper.canPlace(top))throw new AssertionError("Unsupported real fixture: "+name+" on "+substrate);checks++;
                String exactBottom=bottom.getAsString(),exactTop=top==null?null:top.getAsString();
                result=snow.applyStep(chunk,Season.WINTER,stamp,false,1,()->{});
                int parts=top==null?1:2;
                if(result.removed()!=parts||result.placed()!=1||lower.getType()!=Material.SNOW||!upper.getType().isAir())
                    throw new AssertionError("Winter must replace every eligible species correctly: "+name);checks++;
                var saved=GroundCoverColumns.decode(pdc.get(plants,PersistentDataType.BYTE_ARRAY)).get(column);
                if(saved==null||!saved.lower().equals(exactBottom)||!Objects.equals(saved.upper(),exactTop))
                    throw new AssertionError("Exact species properties must survive winter: "+name);checks++;
                result=snow.apply(chunk,Season.SPRING,"cover-runtime-species-cleanup",true);
                if(!result.complete()||!lower.getBlockData().getAsString().equals(exactBottom)
                        ||exactTop!=null&&!upper.getBlockData().getAsString().equals(exactTop)||pdc.has(plants)||pdc.has(owned))
                    throw new AssertionError("Species must restore on unchanged suitable support: "+name);checks++;
            }
            probe.getLogger().info("PASS: All "+species.size()+" eligible ground-cover materials use real support checks and round-trip exact block data/halves");
            support.setType(Material.GRASS_BLOCK,false); lower.setBlockData(Bukkit.createBlockData("minecraft:leaf_litter[segment_amount=3,facing=west]"),false);
            upper.setType(Material.AIR,false); String beforeMelt=lower.getBlockData().getAsString();
            snow.applyStep(chunk,Season.WINTER,stamp,false,1,()->{});
            SeasonalSnowService departing=new SeasonalSnowService(probe,settings,()->false);
            BlockState melted=lower.getState();melted.setType(Material.AIR);
            BlockFadeEvent protectedFade=new BlockFadeEvent(lower,melted); departing.protectWinterSnow(protectedFade);
            if(!protectedFade.isCancelled()||!pdc.has(plants))throw new AssertionError("Owned snow remains protected after global winter ends");checks++;
            BlockFadeEvent allowedFade=new BlockFadeEvent(lower,melted);
            var fadePolicy=SeasonalSnowService.class.getDeclaredMethod("protectSnowFade",BlockFadeEvent.class,boolean.class);fadePolicy.setAccessible(true);
            fadePolicy.invoke(departing,allowedFade,false);
            if(allowedFade.isCancelled())throw new AssertionError("Disabled protection must allow native snow melting");
            departing.onFade(allowedFade);lower.setType(Material.AIR,false);
            if(!pdc.has(plants)||pdc.has(owned))throw new AssertionError("Natural melting retires only snow ownership");
            result=departing.apply(chunk,Season.SPRING,"cover-runtime-after-natural-melt",true);
            if(!result.complete()||!lower.getBlockData().getAsString().equals(beforeMelt)||pdc.has(plants))throw new AssertionError("Spring restores exact vegetation after unprotected native melting");checks++;
            probe.getLogger().info("PASS: Departing-winter snow protection and exact plant restoration after permitted natural melting");
            probe.getLogger().info("PASS: Real litter/tall-grass winter replacement, interrupted restore, player replacement and roof preservation");
            return checks;
        } finally {
            try { snow.apply(chunk,Season.SPRING,"cover-runtime-cleanup",true); }
            finally {
                for(BlockState state:original.values())state.update(true,false);
                for(var row:biomes.entrySet())row.getKey().setBiome(row.getValue());
                for(NamespacedKey key:List.of(plants,owned,version,pending,cursor))pdc.remove(key);
            }
        }
    }
}
