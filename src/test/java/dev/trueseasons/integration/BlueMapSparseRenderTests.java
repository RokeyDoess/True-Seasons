package dev.trueseasons.integration;

import com.flowpowered.math.vector.Vector2i;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.common.rendermanager.RenderTask;
import de.bluecolored.bluemap.core.map.*;
import de.bluecolored.bluemap.core.map.mask.Mask;
import de.bluecolored.bluemap.core.resources.pack.PackVersion;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.storage.file.FileStorage;
import de.bluecolored.bluemap.core.storage.compression.Compression;
import de.bluecolored.bluemap.core.util.Grid;
import de.bluecolored.bluemap.core.world.*;
import de.bluecolored.bluemap.core.world.biome.Biome;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.lang.management.ManagementFactory;

/** Exercises native region preparation, tile rendering, saved hashes and final storage receipts. */
public final class BlueMapSparseRenderTests {
    public static final class Terrain implements World {
        final BlockState grass=BlockState.fromString("minecraft:grass_block");
        final Chunk chunk=new Chunk(){
            public boolean isGenerated(){return true;}public boolean hasLightData(){return true;}
            public BlockState getBlockState(int x,int y,int z){return y==0?grass:BlockState.AIR;}
            public LightData getLightData(int x,int y,int z,LightData out){return out.set(15,0);}
            public Biome getBiome(int x,int y,int z){return Biome.DEFAULT;}
            public int getMinY(int x,int z){return 0;}public int getMaxY(int x,int z){return 1;}
        };
        public String getId(){return "flat";}public DimensionType getDimensionType(){return DimensionType.OVERWORLD;}
        public Grid getChunkGrid(){return new Grid(16);}public Grid getRegionGrid(){return new Grid(512);}
        public Chunk getChunkAtBlock(int x,int z){return chunk;}public Chunk getChunk(int x,int z){return chunk;}
        public Region<Chunk> getRegion(int x,int z){return new Region<>(){
            public void iterateAllChunks(ChunkConsumer<Chunk> consumer){for(int dx=0;dx<32;dx++)for(int dz=0;dz<32;dz++){
                int cx=x*32+dx,cz=z*32+dz;if(consumer.filter(cx,cz,100+dx*32+dz))consumer.accept(cx,cz,chunk);
            }}
            public Chunk emptyChunk(){return Chunk.EMPTY_CHUNK;}public boolean exists(){return true;}
        };}
        public Collection<Vector2i> listRegions(){return List.of(Vector2i.ZERO);}
        public void preloadRegionChunks(int x,int z,Predicate<Vector2i> filter){}
        public void invalidateChunkCache(){}public void invalidateChunkCache(int x,int z){}
        public void iterateEntities(int x,int y,int z,int width,Consumer<Entity> consumer){}
    }
    private static int checks;
    private static void check(boolean value,String text){checks++;if(!value)throw new AssertionError(text);}
    public static void main(String[] args)throws Exception{
        Path resources=Path.of(args.length==0?"build/test-resources/bluemap":args[0]);
        ResourcePack pack=new ResourcePack(new PackVersion(86,0));
        pack.loadResources(List.of(resources.resolve("minecraft-client-26.3.jar"),resources.resolve("defaultBlockstates.zip"),resources.resolve("resourceExtensions.zip")));
        MapSettings settings=(MapSettings)Proxy.newProxyInstance(MapSettings.class.getClassLoader(),new Class<?>[]{MapSettings.class},(proxy,method,values)->{
            if(method.isDefault())return InvocationHandler.invokeDefault(proxy,method,values);
            return switch(method.getName()){
                case "isEnableHires"->true;case "getRenderMask"->Mask.ALL;case "getRemoveCavesBelowY"->Integer.MIN_VALUE;
                case "getHiresTileSize","getLowresTileSize"->32;case "getLodCount"->1;case "getLodFactor"->2;
                case "getSkyLight","getAmbientLight"->1f;case "getSkyColor","getVoidColor"->"#ffffff";
                case "getStartPos"->Vector2i.ZERO;
                default->{Class<?> type=method.getReturnType();yield type==int.class?0:type==long.class?0L:type==float.class?0f:type==boolean.class?false:null;}
            };
        });
        Path directory=Files.createTempDirectory("trueseasons-sparse-map-");
        try(FileStorage storage=new FileStorage(directory,Compression.NONE,false)){
            storage.initialize();BmMap map=new BmMap("flat","flat",new Terrain(),storage.map("flat"),pack,settings);
            Set<Vector2i> rendered=new HashSet<>();map.getHiresModelManager().addTileUpdateListener(rendered::add);
            Class<?> impl=Class.forName("de.bluecolored.bluemap.common.api.BlueMapMapImpl");
            BlueMapMap api=(BlueMapMap)impl.getConstructors()[0].newInstance(map,null,null);
            BlueMapReceiptTests.Api manager=new BlueMapReceiptTests.Api();BlueMapRenderBridge bridge=new BlueMapRenderBridge(manager);
            run(bridge,manager,api,List.of(Vector2i.ZERO),List.of(),rendered,"full");
            check(rendered.size()==289,"A complete region renders all native map tiles");
            map.getMapChunkState().set(0,0,-123);rendered.clear();
            boolean scoped=run(bridge,manager,api,List.of(),List.of(new Vector2i(12,12)),rendered,"maintenance");
            if(scoped){
                check(rendered.size()<=9&&rendered.contains(map.getHiresModelManager().getTileGrid().getCell(new Vector2i(192,192))),"Maintenance renders exact dirty tiles and a one-chunk geometry/light margin");
                check(map.getMapChunkState().get(0,0)==-123,"Unrelated saved chunk hashes are never marked current by partial rendering");
                check(map.getMapChunkState().get(12,12)==100+12*32+12,"Rendered dirty chunks receive native saved hash checkpoints");
                rendered.clear();run(bridge,manager,api,List.of(),List.of(new Vector2i(31,31)),rendered,"boundary");
                check(rendered.contains(new Vector2i(16,16)),"Boundary maintenance includes neighboring region tiles");
            }
            System.out.println("BlueMap sparse rendering: "+checks+" assertions passed");
        }finally{try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private static boolean run(BlueMapRenderBridge bridge,BlueMapReceiptTests.Api manager,BlueMapMap map,List<Vector2i> full,List<Vector2i> dirty,Set<Vector2i> rendered,String name)throws Exception{
        long start=System.nanoTime(),cpu=((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
        BlueMapRenderBridge.Ticket ticket;boolean scoped=true;
        try{Method method=bridge.getClass().getDeclaredMethod("submit",BlueMapMap.class,Collection.class,Collection.class);method.setAccessible(true);ticket=(BlueMapRenderBridge.Ticket)method.invoke(bridge,map,full,dirty);}
        catch(NoSuchMethodException legacy){scoped=false;ticket=bridge.submit(map,dirty.isEmpty()?full:List.of(Vector2i.ZERO));}
        Field nativeManager=manager.getClass().getDeclaredField("renderManager");nativeManager.setAccessible(true);
        RenderTask task=((BlueMapReceiptTests.NativeManager)nativeManager.get(manager)).getScheduledRenderTasks().getLast();
        for(int n=0;task.hasMoreWork();n++){if(n>20000)throw new AssertionError("Render did not finish");task.doWork();}
        check(ticket.successful(),"Native saved map receipt succeeds: "+ticket.failure());
        System.out.printf(Locale.ROOT,"MAP_PROFILE phase=%s tiles=%d seconds=%.3f processCpuMs=%.3f%n",name,rendered.size(),(System.nanoTime()-start)/1e9,(((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime()-cpu)/1e6);
        return scoped;
    }
}
