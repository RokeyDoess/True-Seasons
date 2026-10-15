package dev.trueseasons.snow;

import dev.trueseasons.api.Season;
import dev.trueseasons.config.Settings;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataType;

/** Controlled loaded-chunk comparison; never run on an ordinary server or player terrain. */
public final class SnowPassRuntimeProbe {
    private SnowPassRuntimeProbe() {}
    public static int verify(JavaPlugin probe,Supplier<Settings> settings,Chunk chunk)throws Exception{
        if(!Files.exists(Path.of("verification-result.txt"))||!Bukkit.isPrimaryThread()||!chunk.isLoaded())throw new IllegalStateException("Disposable loaded verification fixture required");
        Path baseline=Path.of("../../build/libs/TrueSeasons-0.1.11.jar").toAbsolutePath().normalize();
        if(!Files.isRegularFile(baseline)){probe.getLogger().info("Skipping optional old/new comparison: local 0.1.11 jar unavailable");return 0;}
        List<BlockState> original=new ArrayList<>();Map<Block,Biome> biomes=new LinkedHashMap<>();
        int y=chunk.getWorld().getMaxHeight()-5;
        for(int c=0;c<256;c++)for(int yy=y;yy<chunk.getWorld().getMaxHeight();yy++)original.add(chunk.getBlock(c&15,yy,c>>4).getState());
        for(int x=0;x<16;x+=4)for(int z=0;z<16;z+=4)for(int yy=y&~3;yy<chunk.getWorld().getMaxHeight();yy+=4){Block at=chunk.getBlock(x,yy,z);biomes.put(at,at.getBiome());at.setBiome(Biome.PLAINS);}
        String[] keys={"snow_v1","season_version","winter_pending","winter_cursor","winter_local","winter_order","ground_cover_v1"};
        var current=new SeasonalSnowService(probe,settings,()->true);int checks=0;
        class BaselineLoader extends URLClassLoader {
            BaselineLoader()throws Exception{super(new java.net.URL[]{baseline.toUri().toURL()},SeasonalSnowService.class.getClassLoader());}
            @Override protected synchronized Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
                if(name.startsWith("dev.trueseasons.snow.")||name.equals("dev.trueseasons.migration.PlayerNeighborhood")){
                    Class<?> found=findLoadedClass(name);if(found==null)found=findClass(name);if(resolve)resolveClass(found);return found;
                }
                return super.loadClass(name,resolve);
            }
        }
        try(var loader=new BaselineLoader()){
            Class<?> oldClass=loader.loadClass(SeasonalSnowService.class.getName());
            Object old=oldClass.getConstructor(Plugin.class,Supplier.class,Supplier.class).newInstance(probe,settings,(Supplier<Boolean>)()->true);
            Method oldStep=oldClass.getMethod("applyStep",Chunk.class,Season.class,String.class,boolean.class,int.class,Runnable.class);
            Method newStep=SeasonalSnowService.class.getMethod("applyStep",Chunk.class,Season.class,String.class,boolean.class,int.class,Runnable.class);
            Object[] services={old,current};Method[] methods={oldStep,newStep};
            var bean=ManagementFactory.getThreadMXBean();com.sun.management.ThreadMXBean allocation=bean instanceof com.sun.management.ThreadMXBean b&&b.isThreadAllocatedMemorySupported()?b:null;
            if(allocation!=null&&!allocation.isThreadAllocatedMemoryEnabled())allocation.setThreadAllocatedMemoryEnabled(true);
            double[][] elapsed=new double[2][7],allocated=new double[2][7];
            for(int round=-6;round<7;round++)for(int offset=0;offset<2;offset++){
                int arm=Math.floorMod(round+offset,2);double total=0,bytes=0;
                for(int repeat=0;repeat<4;repeat++){
                    methods[arm].invoke(services[arm],chunk,Season.SPRING,"comparison-cleanup",true,256,(Runnable)()->{});
                    for(int c=0;c<256;c++){
                        chunk.getBlock(c&15,y,c>>4).setType(Material.GRASS_BLOCK,false);
                        chunk.getBlock(c&15,y+1,c>>4).setBlockData(Bukkit.createBlockData("minecraft:leaf_litter[segment_amount="+(c%4+1)+",facing="+List.of("north","east","south","west").get((c/4)%4)+"]"),false);
                        for(int yy=y+2;yy<chunk.getWorld().getMaxHeight();yy++)chunk.getBlock(c&15,yy,c>>4).setType(Material.AIR,false);
                    }
                    long beforeBytes=allocation==null?0:allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()),start=System.nanoTime();
                    Object result=methods[arm].invoke(services[arm],chunk,Season.WINTER,"comparison-winter",false,256,(Runnable)()->{});
                    total+=(System.nanoTime()-start)/1_000_000.0;
                    if(allocation!=null)bytes+=allocation.getThreadAllocatedBytes(Thread.currentThread().threadId())-beforeBytes;
                    if(!(boolean)result.getClass().getMethod("complete").invoke(result)||(int)result.getClass().getMethod("placed").invoke(result)!=256)throw new AssertionError("Comparison must winterize all 256 fixture columns");
                }
                if(round>=0){elapsed[arm][round]=total/4;allocated[arm][round]=bytes/4/1024;}
                methods[arm].invoke(services[arm],chunk,Season.SPRING,"comparison-cleanup",true,256,(Runnable)()->{});
            }
            for(int arm=0;arm<2;arm++){Arrays.sort(elapsed[arm]);Arrays.sort(allocated[arm]);}
            String report=String.format(Locale.ROOT,"Controlled loaded flat-litter chunk; seven alternating rounds of four passes after warmup.\n0.1.11 median: %.3f ms/chunk, %.1f KiB allocated/chunk\nCurrent median: %.3f ms/chunk, %.1f KiB allocated/chunk\nMeasures applyStep only; excludes resets, disk journal, loading, packet traffic, BlueMap and whole-migration/tick timing. Same real Paper fixture restored between arms.\n",elapsed[0][3],allocated[0][3],elapsed[1][3],allocated[1][3]);
            Files.writeString(Path.of("snow-pass-comparison.txt"),report);probe.getLogger().info(report);checks++;
            // A legacy partial cursor is rechecked within its chunk, without missing shuffled columns.
            for(int c=0;c<256;c++){chunk.getBlock(c&15,y,c>>4).setType(Material.STONE,false);for(int yy=y+1;yy<chunk.getWorld().getMaxHeight();yy++)chunk.getBlock(c&15,yy,c>>4).setType(Material.AIR,false);}
            current.applyStep(chunk,Season.WINTER,"legacy-partial",false,16);
            var pdc=chunk.getPersistentDataContainer();pdc.remove(new NamespacedKey(probe,"winter_order"));pdc.set(new NamespacedKey(probe,"winter_cursor"),PersistentDataType.INTEGER,128);
            var resumed=current.applyStep(chunk,Season.WINTER,"legacy-partial",false,256);
            if(!resumed.complete()||current.observeOwnership(chunk).entries().size()!=256)throw new AssertionError("Legacy cursor skipped shuffled columns");checks++;
            current.apply(chunk,Season.SPRING,"cleanup",true);
            int[] publications={0};current.setOwnershipObserver((c,columns)->publications[0]++);
            current.apply(chunk,Season.WINTER,"single-publication",false);
            if(publications[0]!=1)throw new AssertionError("A physical placement plan must publish ownership only once");checks++;
            current.apply(chunk,Season.SPRING,"cleanup",true);
            pdc.set(new NamespacedKey(probe,"winter_order"),PersistentDataType.INTEGER,99);
            try{current.apply(chunk,Season.WINTER,"unknown-order",false);throw new AssertionError("Unknown order must fail before edits");}catch(java.io.IOException expected){checks++;}
            pdc.remove(new NamespacedKey(probe,"winter_order"));
            current.setPlacementGuard(block->(block.getX()&15)!=0||(block.getZ()&15)!=0);
            var guarded=current.apply(chunk,Season.WINTER,"cleared-column",false);
            if(!guarded.complete()||guarded.placed()!=255||current.observeOwnership(chunk).get(0)!=null)throw new AssertionError("Cleared columns must complete without reclaiming snow during grace");checks++;
            current.setPlacementGuard(null);current.apply(chunk,Season.SPRING,"cleanup",true);
            boolean storm=chunk.getWorld().hasStorm();chunk.getWorld().setStorm(true);
            var fakePlayer=(org.bukkit.entity.Player)java.lang.reflect.Proxy.newProxyInstance(org.bukkit.entity.Player.class.getClassLoader(),new Class<?>[]{org.bukkit.entity.Player.class},(p,m,a)->switch(m.getName()){
                case "hashCode"->1;case "equals"->p==a[0];default->throw new AssertionError("Preview must not inspect fake viewer: "+m.getName());
            });
            Chunk visible=(Chunk)java.lang.reflect.Proxy.newProxyInstance(Chunk.class.getClassLoader(),new Class<?>[]{Chunk.class},(p,m,a)->{
                if(m.getName().equals("getPlayersSeeingChunk"))return Set.of(fakePlayer);
                try{return m.invoke(chunk,a);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
            });
            java.util.concurrent.atomic.AtomicReference<SnowOwnershipSnapshots.Preview> published=new java.util.concurrent.atomic.AtomicReference<>();
            byte[] before=pdc.serializeToBytes();
            try(var local=new LocalWinterSnowService(probe,current,settings,()->"read-only-preview",world->false,world->true,()->128,null,
                    (world,x,z,preview)->published.set(preview),data->data.getMaterial().ordinal())){
                int[] columns=new int[256];for(int c=0;c<256;c++)columns[c]=c;
                local.refresh(visible,columns);
                if(published.get()==null||published.get().columns().size()!=256)throw new AssertionError("Real Paper preview planning did not cover the eligible fixture");checks++;
                if(!Arrays.equals(before,pdc.serializeToBytes())||chunk.getBlock(0,y+1,0).getType()!=Material.AIR)throw new AssertionError("Local previews changed physical terrain or durable metadata");checks++;
            }finally{chunk.getWorld().setStorm(storm);}
            if(published.get()!=null)throw new AssertionError("Closing preview planning must retire its snapshots");checks++;
            return checks;
        }finally{
            try{current.apply(chunk,Season.SPRING,"comparison-final-cleanup",true);}finally{
                for(BlockState state:original)state.update(true,false);
                for(var row:biomes.entrySet())row.getKey().setBiome(row.getValue());
                for(String key:keys)chunk.getPersistentDataContainer().remove(new NamespacedKey(probe,key));
            }
        }
    }
}
