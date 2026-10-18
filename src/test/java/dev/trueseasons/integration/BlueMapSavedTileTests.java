package dev.trueseasons.integration;

import com.flowpowered.math.vector.Vector2i;
import de.bluecolored.bluemap.core.map.TextureGallery;
import de.bluecolored.bluemap.core.map.hires.*;
import de.bluecolored.bluemap.core.map.mask.Mask;
import de.bluecolored.bluemap.core.resources.pack.PackVersion;
import de.bluecolored.bluemap.core.resources.pack.datapack.DataPack;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.storage.*;
import de.bluecolored.bluemap.core.storage.compression.CompressedInputStream;
import de.bluecolored.bluemap.core.util.*;
import de.bluecolored.bluemap.core.util.math.Color;
import de.bluecolored.bluemap.core.world.*;
import de.bluecolored.bluemap.core.world.biome.Biome;
import de.bluecolored.bluemap.core.world.mca.MCAWorld;
import dev.trueseasons.api.Season;
import dev.trueseasons.climate.SeasonClimatePolicy;
import dev.trueseasons.visual.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;

/** Full native model/texture -> saved PRBM regression, including idle renderer replacement. */
public final class BlueMapSavedTileTests {
    private static int assertions;
    private static void check(boolean condition,String description) { assertions++; if (!condition) throw new AssertionError(description); }
    private static Object field(Object object,String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static final class SavedTiles implements GridStorage {
        final Path folder;
        SavedTiles(Path folder) { this.folder = folder; }
        public OutputStream write(int x,int z) throws IOException { return Files.newOutputStream(folder.resolve(x+","+z+".prbm")); }
        public CompressedInputStream read(int x,int z) throws IOException { return new CompressedInputStream(Files.newInputStream(folder.resolve(x+","+z+".prbm"))); }
        public void delete(int x,int z) throws IOException { Files.deleteIfExists(folder.resolve(x+","+z+".prbm")); }
        public boolean exists(int x,int z) { return Files.exists(folder.resolve(x+","+z+".prbm")); }
        public ItemStorage cell(int x,int z) { throw new UnsupportedOperationException(); }
        public Stream<GridStorage.Cell> stream() { return Stream.empty(); }
        public boolean isClosed() { return false; }
    }
    private static final class SavedWorld implements World {
        final Map<String,BlockState> blocks = new HashMap<>();
        final Map<String,BlockState> parsed = new HashMap<>();
        Biome biome=Biome.DEFAULT;
        final Chunk chunk = new Chunk() {
            public boolean isGenerated() { return true; }
            public boolean hasLightData() { return true; }
            public BlockState getBlockState(int x,int y,int z) { return blocks.getOrDefault(x+","+y+","+z,BlockState.AIR); }
            public LightData getLightData(int x,int y,int z,LightData output) { return output.set(15,0); }
            public Biome getBiome(int x,int y,int z) { return biome; }
            public int getMinY(int x,int z) { return 0; }
            public int getMaxY(int x,int z) { return 16; }
        };
        void put(int x,int y,int z,String id) { blocks.put(x+","+y+","+z,parsed.computeIfAbsent(id,BlockState::fromString)); }
        public String getId() { return "saved-fixture"; }
        public DimensionType getDimensionType() { return DimensionType.OVERWORLD; }
        public Grid getChunkGrid() { return new Grid(16); }
        public Grid getRegionGrid() { return new Grid(512); }
        public Chunk getChunkAtBlock(int x,int z) { return chunk; }
        public Chunk getChunk(int x,int z) { return chunk; }
        public Region<Chunk> getRegion(int x,int z) { throw new AssertionError("No terrain generation or region mutation"); }
        public Collection<Vector2i> listRegions() { return List.of(Vector2i.ZERO); }
        public void preloadRegionChunks(int x,int z,Predicate<Vector2i> filter) {}
        public void invalidateChunkCache() {}
        public void invalidateChunkCache(int x,int z) {}
        public void iterateEntities(int x,int y,int z,int w,Consumer<Entity> consumer) {}
    }
    public static final class NativeMap {
        final HiresModelManager manager;
        NativeMap(World world,SavedTiles storage,ResourcePack pack,TextureGallery gallery,RenderSettings settings) {
            this(world,storage,pack,gallery,settings,Grid.UNIT);
        }
        NativeMap(World world,SavedTiles storage,ResourcePack pack,TextureGallery gallery,RenderSettings settings,Grid grid) {
            manager = new HiresModelManager(world,storage,pack,gallery,settings,grid);
        }
        public HiresModelManager getHiresModelManager() { return manager; }
    }
    private static byte[] render(NativeMap map,SavedTiles storage,int x,int z,Map<String,Integer> lowres) throws IOException {
        map.manager.render(new Vector2i(x,z),(bx,bz,color,height,light) -> lowres.put(bx+","+bz,color.getInt()),true);
        return Files.readAllBytes(storage.folder.resolve(x+","+z+".prbm"));
    }
    private static int colorOffset(byte[] tile) {
        byte[] tag = {'c','o','l','o','r',0,103};
        outer: for (int offset=0;offset<tile.length-tag.length;offset++) {
            for (int j=0;j<tag.length;j++) if (tile[offset+j] != tag[j]) continue outer;
            return (offset+tag.length+3)&~3;
        }
        throw new AssertionError("Saved hires tile has no color attribute");
    }
    static boolean hasRgb(byte[] tile,int rgb) {
        int offset = colorOffset(tile);
        int vertices = (tile[2]&255) | ((tile[3]&255)<<8) | ((tile[4]&255)<<16);
        // Grass has untinted dirt faces too. Inspect only its stored color attribute,
        // across all vertices, without mistaking unrelated attributes for tint bytes.
        for (int i=0;i<vertices*3 && offset+i+2<tile.length;i+=3)
            if ((tile[offset+i]&255)==((rgb>>16)&255)&&(tile[offset+i+1]&255)==((rgb>>8)&255)&&(tile[offset+i+2]&255)==(rgb&255)) return true;
        return false;
    }
    private static void invalidateRenderers(NativeMap map) throws Exception {
        ThreadLocal<?> passes = (ThreadLocal<?>) field(map.manager,"renderPasses");
        for (Object pass : (Collection<?>) passes.get()) {
            Object nativePass = BlueMapSeasonalColors.nativePass(pass);
            if (!nativePass.getClass().getSimpleName().equals("BlockRenderPass")) continue;
            Object cache = field(field(nativePass,"blockRenderer"),"blockRenderers");
            Method invalidate = cache.getClass().getMethod("invalidateAll"); invalidate.setAccessible(true); invalidate.invoke(cache);
        }
    }
    public static void main(String[] args) throws Exception {
        Path resources = Path.of(args.length==0 ? "run/seasonal-colors-verification-0.1.9/bluemap" : args[0]);
        ResourcePack pack = new ResourcePack(new PackVersion(86,0));
        pack.loadResources(List.of(resources.resolve("minecraft-client-26.3.jar"),resources.resolve("defaultBlockstates.zip"),resources.resolve("resourceExtensions.zip")));
        TextureGallery gallery = new TextureGallery(); gallery.put(pack.getTextures());
        RenderSettings settings = (RenderSettings) Proxy.newProxyInstance(RenderSettings.class.getClassLoader(),new Class<?>[]{RenderSettings.class},(proxy,method,arguments)-> {
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy,method,arguments);
            return switch(method.getName()) {
                case "getRenderMask" -> Mask.ALL;
                case "getRemoveCavesBelowY" -> Integer.MIN_VALUE;
                case "getCaveDetectionOceanFloor" -> 0;
                case "getAmbientLight" -> 1f;
                case "isSaveHiresLayer" -> true;
                default -> false;
            };
        });
        Path output = Files.createTempDirectory("trueseasons-saved-tiles-"); SavedTiles storage = new SavedTiles(output);
        SavedWorld source = new SavedWorld(); source.put(0,8,0,"minecraft:grass_block");
        source.put(1,8,0,"minecraft:short_grass");
        int column = 4;
        for (String species : List.of("oak","birch","cherry","spruce")) {
            source.put(column,7,0,"minecraft:"+species+"_log");
            source.put(column,8,0,"minecraft:"+species+"_leaves[distance=1,persistent=false,waterlogged=false]"); column+=4;
        }
        NativeMap map = new NativeMap(source,storage,pack,gallery,settings); Map<String,Integer> lowres = new HashMap<>();
        Map<String,BlockState> original = Map.copyOf(source.blocks);
        byte[] nativeGrass=render(map,storage,0,0,lowres); int nativeGrassLow=lowres.get("0,0");
        YamlConfiguration config = new YamlConfiguration(); config.set("enabled",true);
        for (Season season : Season.values()) config.set("palettes.temperate."+season.key(),List.of("9C9657","D98637"));
        SeasonVisualResolver resolver = new SeasonVisualResolver(config,new SeasonClimatePolicy(SeasonClimatePolicy.HOT,SeasonClimatePolicy.COLD));
        UUID id = UUID.randomUUID();
        BlueMapSeasonalColors hook = new BlueMapSeasonalColors(map,id);
        hook.update(new SeasonalVisualState(resolver,Season.AUTUMN,Set.of(id),Map.of(id,1234L)));
        byte[] autumnGrass=render(map,storage,0,0,lowres);
        check(hasRgb(autumnGrass,0x9C9657),"actual saved grass hires tile stores configured autumn RGB");
        check(!Arrays.equals(nativeGrass,autumnGrass),"actual saved grass tile changes from vanilla");
        check(lowres.get("0,0")!=nativeGrassLow,"actual textured grass lowres colour changes too");
        byte[] autumnShortGrass=render(map,storage,1,0,lowres);
        check(hasRgb(autumnShortGrass,0x9C9657),"actual saved short grass tile stores autumn RGB");
        invalidateRenderers(map);
        check(hasRgb(render(map,storage,0,0,lowres),0x9C9657),"expired/recreated native renderer retains autumn colours");
        var selection = new AutumnTreeColors.Session(1234L,(x,y,z)->AutumnTreeColors.kind(source.chunk.getBlockState(x,y,z).getId().toString()));
        column=4;
        for (String species : List.of("oak","birch","cherry")) {
            byte[] autumn=render(map,storage,column,0,lowres); int autumnLow=lowres.get(column+",0");
            var appearance=selection.appearanceAt(column,8,0);
            check(appearance!=AutumnTreeColors.Appearance.ORIGINAL,species+" selects coloured native leaves");
            if (species.equals("cherry")) check(appearance==AutumnTreeColors.Appearance.RED,"cherry is always red");
            hook.update(new SeasonalVisualState(resolver,Season.AUTUMN,Set.of()));
            source.put(column,8,0,appearance.blockKey()+"[distance=1,persistent=false,waterlogged=false]");
            byte[] vanillaPoplar=render(map,storage,column,0,lowres);
            check(Arrays.equals(autumn,vanillaPoplar),species+" saved hires geometry/materials exactly match vanilla "+appearance.blockKey());
            check(autumnLow==lowres.get(column+",0"),species+" saved lowres colour exactly matches native coloured leaves");
            source.blocks.clear();source.blocks.putAll(original);
            hook.update(new SeasonalVisualState(resolver,Season.AUTUMN,Set.of(id),Map.of(id,1234L))); column+=4;
        }
        byte[] autumnSpruce=render(map,storage,16,0,lowres);
        // Persistent decorative leaves need no matching log or surrounding loaded crown.
        source.put(20,8,0,"minecraft:birch_leaves[distance=7,persistent=true,waterlogged=false]");
        byte[] decorative=render(map,storage,20,0,lowres);
        var decorativeSelection=new AutumnTreeColors.Session(1234L,new AutumnTreeColors.Sampler(){
            public AutumnTreeColors.Kind kindAt(int x,int y,int z){return AutumnTreeColors.Kind.BIRCH_LEAVES;}
            public boolean persistentAt(int x,int y,int z){return true;}
        });
        check(decorativeSelection.appearanceAt(20,8,0)==AutumnTreeColors.Appearance.ORANGE,"decorative hedge uses the agreed orange appearance");
        hook.update(new SeasonalVisualState(resolver,Season.AUTUMN,Set.of()));
        source.put(20,8,0,"minecraft:orange_poplar_leaves[distance=7,persistent=true,waterlogged=false]");
        check(Arrays.equals(decorative,render(map,storage,20,0,lowres)),"decorative hedge saved tile exactly matches native selected poplar");
        source.blocks.clear();source.blocks.putAll(original);
        hook.update(new SeasonalVisualState(resolver,Season.AUTUMN,Set.of(id),Map.of(id,1234L)));
        long springSeed=0;
        for(;;springSeed++){
            var springSelection=new AutumnTreeColors.Session(springSeed,(x,y,z)->AutumnTreeColors.kind(source.chunk.getBlockState(x,y,z).getId().toString()));
            if(springSelection.springAppearanceAt(4,8,0,256)==AutumnTreeColors.Appearance.BLOSSOM)break;
        }
        hook.update(new SeasonalVisualState(resolver,Season.SPRING,Set.of(id),Map.of(id,springSeed)));
        byte[] blossoms=render(map,storage,4,0,lowres);int blossomLow=lowres.get("4,0");
        hook.update(new SeasonalVisualState(resolver,Season.SPRING,Set.of()));
        source.put(4,8,0,"minecraft:flowering_azalea_leaves[distance=1,persistent=false,waterlogged=false]");
        check(Arrays.equals(blossoms,render(map,storage,4,0,lowres)),"Saved spring blossom geometry/materials exactly match native flowering leaves");
        check(blossomLow==lowres.get("4,0"),"Saved spring lowres color matches native flowering leaves");
        scatteredSpring(pack,gallery,settings,output,config);
        source.blocks.clear();source.blocks.putAll(original);
        hook.update(new SeasonalVisualState(resolver,Season.AUTUMN,Set.of(id),Map.of(id,1234L)));
        source.biome=(Biome)Proxy.newProxyInstance(Biome.class.getClassLoader(),new Class<?>[]{Biome.class},(proxy,method,arguments)->
            method.getName().equals("getKey")?Key.minecraft("desert"):method.invoke(Biome.DEFAULT,arguments));
        // Compare the hook against the exact same native biome and saved block data.
        byte[] hotGrass=render(map,storage,0,0,lowres),hotBirch=render(map,storage,8,0,lowres);
        hook.update(new SeasonalVisualState(resolver,Season.AUTUMN,Set.of()));
        check(Arrays.equals(hotGrass,render(map,storage,0,0,lowres)),"hot autumn biome saved grass remains native");
        check(Arrays.equals(hotBirch,render(map,storage,8,0,lowres)),"hot autumn biome saved birch remains native");
        source.biome=Biome.DEFAULT;
        hook.close();
        check(Arrays.equals(autumnSpruce,render(map,storage,16,0,lowres)),"spruce native saved tile remains unchanged");
        check(Arrays.equals(nativeGrass,render(map,storage,0,0,lowres)),"detaching presentation restores exact native grass tile");
        check(source.blocks.equals(original),"all original saved fixture blocks remain unchanged");
        System.out.println("BlueMap saved tile tests passed: "+assertions+" assertions; tiles: "+output);
        if(args.length>1&&args[1].equals("--benchmark")) benchmark(pack,gallery,settings,output);
        if(args.length>2&&args[1].equals("--mca-benchmark")) mcaBenchmark(pack,gallery,settings,output,resources,Path.of(args[2]));
    }
    private static void scatteredSpring(ResourcePack pack,TextureGallery gallery,RenderSettings settings,Path folder,YamlConfiguration config)throws Exception{
        SavedWorld forest=new SavedWorld();
        String properties="[distance=1,persistent=false,waterlogged=false]";
        for(int x=6;x<=10;x++)for(int z=6;z<=10;z++)for(int y=5;y<=7;y++)forest.put(x,y,z,"minecraft:birch_leaves"+properties);
        for(int y=0;y<=5;y++)forest.put(8,y,8,"minecraft:birch_log");
        var originals=Map.copyOf(forest.blocks);long seed=0;AutumnTreeColors.Session selection;
        for(;;seed++){
            selection=new AutumnTreeColors.Session(seed,(x,y,z)->AutumnTreeColors.kind(originals.getOrDefault(x+","+y+","+z,BlockState.AIR).getId().toString()));
            if(selection.springAppearanceAt(6,5,6,256)==AutumnTreeColors.Appearance.BLOSSOM)break;
        }
        UUID world=UUID.randomUUID();SavedTiles saved=new SavedTiles(folder.resolve("scattered"));Files.createDirectories(saved.folder);
        NativeMap map=new NativeMap(forest,saved,pack,gallery,settings,new Grid(32));var hook=new BlueMapSeasonalColors(map,world);
        hook.update(new SeasonalVisualState(new SeasonVisualResolver(config),Season.SPRING,Set.of(world),Map.of(world,seed)));
        var lowres=new HashMap<String,Integer>();byte[] actual=render(map,saved,0,0,lowres);int actualLow=lowres.get("0,0");int flowers=0,ordinary=0;
        for(var row:originals.entrySet()){
            if(!AutumnTreeColors.kind(row.getValue().getId().toString()).leaves())continue;
            String[] xyz=row.getKey().split(",");int x=Integer.parseInt(xyz[0]),y=Integer.parseInt(xyz[1]),z=Integer.parseInt(xyz[2]);
            if(selection.springAppearanceAt(x,y,z,256)==AutumnTreeColors.Appearance.BLOSSOM){forest.put(x,y,z,AutumnTreeColors.Appearance.BLOSSOM.blockKey()+properties);flowers++;}else ordinary++;
        }
        check(flowers>0&&ordinary>flowers,"Saved spring canopy keeps ordinary leaves among scattered flowers");
        var nativeConfig=new YamlConfiguration();for(String key:config.getKeys(true))if(!config.isConfigurationSection(key))nativeConfig.set(key,config.get(key));nativeConfig.set("spring-blossoms.enabled",false);
        hook.update(new SeasonalVisualState(new SeasonVisualResolver(nativeConfig),Season.SPRING,Set.of(world),Map.of(world,seed)));
        check(Arrays.equals(actual,render(map,saved,0,0,lowres)),"Full saved spring canopy exactly matches native mixed flowering and ordinary leaves");
        check(actualLow==lowres.get("0,0"),"Mixed spring lowres color matches the same native blocks and seasonal biome");hook.close();
    }
    private static void benchmark(ResourcePack pack,TextureGallery gallery,RenderSettings settings,Path folder) throws Exception {
        SavedWorld forest=new SavedWorld();
        for(int x=0;x<32;x++)for(int z=0;z<32;z++)forest.put(x,3,z,"minecraft:grass_block");
        for(int tx=0;tx<4;tx++)for(int tz=0;tz<4;tz++) {
            int x=4+tx*7,z=4+tz*7;String species=(tx+tz)%2==0?"oak":"birch";
            for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)for(int y=8;y<=11;y++)
                if(dx*dx+dz*dz<=12)forest.put(x+dx,y,z+dz,"minecraft:"+species+"_leaves[distance=1,persistent=false,waterlogged=false]");
            for(int y=4;y<=10;y++)forest.put(x,y,z,"minecraft:"+species+"_log");
        }
        SavedTiles storage=new SavedTiles(folder); NativeMap map=new NativeMap(forest,storage,pack,gallery,settings,new Grid(32));
        UUID world=UUID.randomUUID(); BlueMapSeasonalColors colors=new BlueMapSeasonalColors(map,world);
        YamlConfiguration config=new YamlConfiguration();config.set("enabled",true);
        for(Season season:Season.values())config.set("palettes.temperate."+season.key(),List.of("9C9657","D98637"));
        Map<String,Long> elapsed=new LinkedHashMap<>();
        for(String mode:List.of("native","tint-only","autumn-trees","spring-blossoms")) {
            config.set("autumn-trees.enabled",mode.equals("autumn-trees"));
            var resolver=new SeasonVisualResolver(config,new SeasonClimatePolicy(SeasonClimatePolicy.HOT,SeasonClimatePolicy.COLD));
            colors.update(new SeasonalVisualState(resolver,mode.equals("spring-blossoms")?Season.SPRING:Season.AUTUMN,mode.equals("native")?Set.of():Set.of(world),Map.of(world,1234L)));
            Map<String,Integer> lowres=new HashMap<>();
            render(map,storage,0,0,lowres); // Warm up native models and the selected hook path.
            long[] samples=new long[3];
            for(int i=0;i<samples.length;i++) { long start=System.nanoTime();render(map,storage,0,0,lowres);samples[i]=System.nanoTime()-start; }
            Arrays.sort(samples);elapsed.put(mode,samples[1]/1_000_000);
        }
        colors.close();
        String report="Dense 32x32 forest: "+forest.blocks.values().stream().filter(state->state.getId().getValue().endsWith("_leaves")).count()
            +" leaves; median complete saved-tile milliseconds "+elapsed;
        System.out.println(report);Files.writeString(folder.resolve("forest-benchmark.txt"),report);
    }
    private static void mcaBenchmark(ResourcePack pack,TextureGallery gallery,RenderSettings settings,Path folder,Path resources,Path savedWorld) throws Exception {
        DataPack data=new DataPack(new PackVersion(86,0));
        data.loadResources(List.of(resources.resolve("minecraft-client-26.3.jar"),resources.resolve("defaultBlockstates.zip"),resources.resolve("resourceExtensions.zip"),savedWorld.getParent().resolve("versions/26.3/paper-26.3.jar")));
        MCAWorld source=MCAWorld.load(savedWorld,DataPack.DIMENSION_OVERWORLD,DataPack.DIMENSION_TYPE_OVERWORLD,data);
        Vector2i chosen=null;int best=-1,chunks=0;
        regionLoop:for(var region:source.listRegions())for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++) {
            int x=(region.getX()*32+cx)*16,z=(region.getY()*32+cz)*16;Chunk chunk=source.getChunkAtBlock(x,z);
            if(!chunk.isGenerated())continue;
            int leaves=0;
            for(int sx=0;sx<16;sx+=2)for(int sz=0;sz<16;sz+=2)for(int y=chunk.getMaxY(x+sx,z+sz);y>=Math.max(chunk.getMinY(x+sx,z+sz),40);y--)
                if(AutumnTreeColors.kind(chunk.getBlockState(x+sx,y,z+sz).getId().toString()).leaves())leaves++;
            if(leaves>best){best=leaves;chosen=new Vector2i(Math.floorDiv(x,32),Math.floorDiv(z,32));}
            if(++chunks>=128)break regionLoop;
        }
        if(chosen==null)throw new AssertionError("No generated source chunks for saved forest benchmark");
        SavedTiles storage=new SavedTiles(folder);NativeMap map=new NativeMap(source,storage,pack,gallery,settings,new Grid(32));
        UUID world=UUID.randomUUID();BlueMapSeasonalColors colors=new BlueMapSeasonalColors(map,world);
        YamlConfiguration config=new YamlConfiguration();config.set("enabled",true);
        for(Season season:Season.values())config.set("palettes.temperate."+season.key(),List.of("9C9657","D98637"));
        Map<String,Long> elapsed=new LinkedHashMap<>();
        for(String mode:List.of("native","tint-only","autumn-trees","spring-blossoms","native-repeat")) {
            config.set("autumn-trees.enabled",mode.equals("autumn-trees"));
            var resolver=new SeasonVisualResolver(config,new SeasonClimatePolicy(SeasonClimatePolicy.HOT,SeasonClimatePolicy.COLD));
            colors.update(new SeasonalVisualState(resolver,mode.equals("spring-blossoms")?Season.SPRING:Season.AUTUMN,mode.startsWith("native")?Set.of():Set.of(world),Map.of(world,1234L)));
            Map<String,Integer> lowres=new HashMap<>();render(map,storage,chosen.getX(),chosen.getY(),lowres);
            long[] samples=new long[3];
            for(int i=0;i<samples.length;i++){long start=System.nanoTime();render(map,storage,chosen.getX(),chosen.getY(),lowres);samples[i]=System.nanoTime()-start;}
            Arrays.sort(samples);elapsed.put(mode,samples[1]/1_000_000);
        }
        colors.close();
        String report="Saved MCA forest tile "+chosen+", selected from "+chunks+" generated chunks (sampled "+best+" leaves): median saved-render milliseconds "+elapsed;
        System.out.println(report);Files.writeString(folder.resolve("mca-forest-benchmark.txt"),report);
    }
}
