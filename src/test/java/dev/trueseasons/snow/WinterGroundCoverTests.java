package dev.trueseasons.snow;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.*;
import org.bukkit.block.data.type.Snow;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.persistence.*;
import org.bukkit.plugin.Plugin;

/** Terrain fixtures forbid loads and require a recoverable snapshot before each plant edit. */
public final class WinterGroundCoverTests {
    private static int checks;
    record Value(PersistentDataType<?, ?> type, Object value) {}
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
    private static Object identity(Object p, String method, Object[] args) {
        return switch (method) { case "equals" -> p == args[0]; case "hashCode" -> System.identityHashCode(p);
            case "toString" -> "PlantFixture"; default -> throw new AssertionError("Unexpected API " + method); };
    }
    static BlockData data(String text) {
        String name = text.substring("minecraft:".length()).split("\\[")[0].toUpperCase(Locale.ROOT);
        Material material = Material.valueOf(name); boolean tall = text.contains("half=");
        Class<? extends BlockData> type = material==Material.SNOW ? Snow.class : tall ? Bisected.class : BlockData.class;
        return proxy(type, (p,m,a) -> switch (m.getName()) {
            case "getAsString" -> text; case "getMaterial" -> material;
            case "getHalf" -> text.contains("half=upper") ? Bisected.Half.TOP : Bisected.Half.BOTTOM;
            case "getLayers" -> 1;
            case "clone" -> data(text);
            case "equals" -> a[0] instanceof BlockData other && text.equals(other.getAsString());
            case "hashCode" -> text.hashCode();
            default -> identity(p,m.getName(),a);
        });
    }
    static final class Fixture {
        final Map<NamespacedKey, Value> metadata = new HashMap<>(); final Map<Integer, BlockData> terrain = new HashMap<>();
        final List<String> order = new ArrayList<>(); int intent, mutations, failY = -1000;
        final Plugin plugin = proxy(Plugin.class, (p,m,a) -> switch(m.getName()) {
            case "getName" -> "TrueSeasons"; case "namespace" -> "trueseasons"; default -> identity(p,m.getName(),a);
        });
        final NamespacedKey key = new NamespacedKey(plugin,"ground_cover_v1");
        final PersistentDataContainer pdc = proxy(PersistentDataContainer.class, (p,m,a) -> switch(m.getName()) {
            case "has" -> { Value v=metadata.get(a[0]); yield v != null && (a.length == 1 || v.type == a[1]); }
            case "get", "getOrDefault" -> { Value v=metadata.get(a[0]); yield v == null ? (a.length == 3 ? a[2] : null) : v.value; }
            case "set" -> { order.add("snapshot"); metadata.put((NamespacedKey)a[0],new Value((PersistentDataType<?,?>)a[1],a[2])); yield null; }
            case "remove" -> { metadata.remove(a[0]); yield null; } default -> identity(p,m.getName(),a);
        });
        final World world = proxy(World.class, (p,m,a) -> switch(m.getName()) {
            case "getMinHeight" -> -64; case "getMaxHeight" -> 320; case "hasStorm", "isChunkLoaded" -> true;
            case "getHighestBlockAt" -> {
                int column = ((int)a[0] & 15) | (((int)a[1] & 15) << 4);
                int highest = terrain.entrySet().stream().filter(row -> Math.floorDiv(row.getKey(),1000) == column
                    && row.getValue().getMaterial() != Material.AIR).mapToInt(row -> Math.floorMod(row.getKey(),1000)).max().orElse(64);
                yield block(column, highest);
            }
            default -> identity(p,m.getName(),a);
        });
        final Chunk chunk = proxy(Chunk.class, (p,m,a) -> switch(m.getName()) {
            case "getPersistentDataContainer" -> pdc; case "getWorld" -> world;
            case "isLoaded" -> true; case "getX", "getZ" -> 0;
            case "getBlock" -> { check((int)a[0] >= 0 && (int)a[0] < 16 && (int)a[2] >= 0 && (int)a[2] < 16,"Only recorded local coordinates are accessed"); yield block((int)a[0] | ((int)a[2] << 4),(int)a[1]); }
            default -> identity(p,m.getName(),a);
        });
        final WinterGroundCover cover = new WinterGroundCover(plugin, WinterGroundCoverTests::data);
        Fixture(String plant) { terrain.put(64,data("minecraft:grass_block")); terrain.put(65,data(plant)); }
        Block block(int y) { return block(0,y); }
        Block block(int column,int y) {
            int cell=column*1000+y;
            return proxy(Block.class, (p,m,a) -> switch(m.getName()) {
                case "getY" -> y; case "getX" -> column&15; case "getZ" -> column>>4; case "getChunk" -> chunk; case "getWorld" -> world;
                case "getLightFromSky" -> (byte)15; case "getLightFromBlocks" -> (byte)0;
                case "getType" -> terrain.getOrDefault(cell,data("minecraft:air")).getMaterial();
                case "getBlockData" -> terrain.getOrDefault(cell,data("minecraft:air"));
                case "getRelative" -> block(column,y + (a[0] == BlockFace.UP ? 1 : a[0] == BlockFace.DOWN ? -1 : 0));
                case "canPlace" -> {
                    BlockData planned=(BlockData)a[0]; Material below=terrain.getOrDefault(cell-1,data("minecraft:air")).getMaterial();
                    yield planned instanceof Bisected half && half.getHalf()==Bisected.Half.TOP
                            ? below==planned.getMaterial() : below==Material.GRASS_BLOCK || below==Material.DIRT || below==Material.MYCELIUM;
                }
                case "setType", "setBlockData" -> {
                    check(intent > 0 && metadata.containsKey(key), "Journal intent and snapshot precede every edit");
                    if(y==failY) throw new IllegalStateException("Block write failed");
                    check(a[1].equals(false),"Plant edits suppress physics and drops");
                    terrain.put(cell,m.getName().equals("setType") ? data("minecraft:"+((Material)a[0]).name().toLowerCase(Locale.ROOT)) : (BlockData)a[0]);
                    mutations++; order.add("edit"+y); yield null;
                }
                default -> identity(p,m.getName(),a);
            });
        }
        Runnable intent() { return () -> { intent++; order.add("intent"); }; }
        int hide(int top) throws IOException { return cover.hide(cover.read(chunk),0,block(top),intent()); }
        WinterGroundCover.Restoration restore() throws IOException { return cover.restore(cover.read(chunk),b -> true,intent()); }
        String at(int y) { return terrain.getOrDefault(y,data("minecraft:air")).getAsString(); }
    }
    public static void main(String[] args) throws Exception {
        var f=new Fixture("minecraft:leaf_litter[segment_amount=3,facing=west]");
        check(f.hide(65)==1,"Leaf litter is removed"); check(f.order.equals(List.of("intent","snapshot","edit65")),"Snapshot precedes vegetation mutation");
        check(f.at(65).equals("minecraft:air") && f.cover.hasMetadata(f.chunk),"Hidden vegetation remains owned");
        check(f.restore().restored()==1 && f.at(65).equals("minecraft:leaf_litter[segment_amount=3,facing=west]"),"Exact decorative block data is restored");
        check(!f.cover.hasMetadata(f.chunk),"Successful restoration retires backup"); check(f.restore().restored()==0,"Restoration is idempotent");
        f=new Fixture("minecraft:poppy");f.terrain.put(64,data("minecraft:stone"));
        check(f.hide(65)==0 && f.at(65).equals("minecraft:poppy"),"Unsupported original decoration remains intact during winter");
        check(f.intent==0 && f.mutations==0 && !f.cover.hasMetadata(f.chunk),"Unrestorable vegetation creates no snapshot or map work");
        f=new Fixture("minecraft:poppy"); f.terrain.put(1064,data("minecraft:grass_block"));f.terrain.put(1065,data("minecraft:fern"));
        var batch=f.cover.read(f.chunk);
        check(f.cover.hideBatch(batch,List.of(new WinterGroundCover.Surface(0,f.block(65)),new WinterGroundCover.Surface(1,f.block(1,65)),new WinterGroundCover.Surface(1,f.block(1,65))),f.intent())==2,
                "Multi-column batch removes each planned plant exactly once");
        check(Collections.frequency(f.order,"snapshot")==1 && f.order.indexOf("snapshot")<f.order.indexOf("edit65"),"All plant plans publish in one snapshot write before first block");
        check(GroundCoverColumns.decode((byte[])f.metadata.get(f.key).value).entries().size()==2,"One batch contains every removal before mutation");
        f.order.clear(); check(f.restore().restored()==2 && Collections.frequency(f.order,"snapshot")==0,"Restoration retires completed batch once without per-plant serialization");
        f=new Fixture("minecraft:tall_grass[half=lower]"); f.terrain.put(66,data("minecraft:tall_grass[half=upper]"));
        check(f.hide(66)==2 && f.at(65).equals("minecraft:air") && f.at(66).equals("minecraft:air"),"Tall plants hide both halves");
        check(f.restore().restored()==2 && f.at(65).contains("half=lower") && f.at(66).contains("half=upper"),"Tall plant topology is restored");
        for(Material material:List.of(Material.OAK_SAPLING,Material.OAK_LEAVES,Material.WHEAT,Material.PITCHER_CROP,Material.TORCHFLOWER_CROP))
            check(!WinterGroundCover.eligible(material),"Saplings, leaves and crops stay: "+material);
        for(Material material:List.of(Material.SHORT_GRASS,Material.FERN,Material.DANDELION,Material.PINK_PETALS,Material.WILDFLOWERS,
                Material.SHORT_DRY_GRASS,Material.TALL_DRY_GRASS,Material.OPEN_EYEBLOSSOM,Material.CLOSED_EYEBLOSSOM))
            check(WinterGroundCover.eligible(material),"Ground cover is eligible: "+material);
        f=new Fixture("minecraft:poppy"); f.hide(65); int old=f.mutations;
        check(f.hide(65)==0 && f.mutations==old && f.cover.hasMetadata(f.chunk),"Repeated winter does not restore or repeatedly edit hidden plants");
        check(f.cover.restore(f.cover.read(f.chunk), b -> false, f.intent()).restored()==0 && f.cover.hasMetadata(f.chunk),"Cold winter retains backup");
        f.terrain.put(65,data("minecraft:diamond_block")); check(f.restore().restored()==0 && f.at(65).contains("diamond_block") && !f.cover.hasMetadata(f.chunk),"Player replacements are never overwritten");
        f=new Fixture("minecraft:poppy"); f.hide(65); f.cover.edited(f.block(65));
        check(!f.cover.hasMetadata(f.chunk) && f.restore().restored()==0,"Breaking covering snow relinquishes plant before later restoration");
        f=new Fixture("minecraft:poppy"); f.hide(65); f.cover.edited(f.block(64));
        check(!f.cover.hasMetadata(f.chunk),"Editing original support relinquishes its hidden vegetation");
        f=new Fixture("minecraft:poppy"); f.hide(65); f.cover.edited(f.block(70));
        check(f.cover.hasMetadata(f.chunk),"Unrelated higher edit preserves exact backup");
        f.terrain.put(64,data("minecraft:stone")); check(f.restore().conflicts()==1 && f.cover.hasMetadata(f.chunk),"Changed support is retained as a recoverable conflict");
        for (String original : List.of("dirt", "grass_block", "mycelium")) {
            for (String current : List.of("dirt", "grass_block", "mycelium")) {
                if (original.equals(current)) continue;
                f=new Fixture("minecraft:pink_petals[facing=west,flower_amount=4]");
                f.terrain.put(64,data("minecraft:"+original)); f.hide(65);
                f.terrain.put(64,data("minecraft:"+current));
                var restored=f.restore();
                check(restored.restored()==1 && restored.conflicts()==0 && !f.cover.hasMetadata(f.chunk),"Natural soil conversion restores plants: "+original+" -> "+current);
                check(f.at(64).equals("minecraft:"+current) && f.at(65).equals("minecraft:pink_petals[facing=west,flower_amount=4]"),"Restoration preserves current soil and exact petal properties");
            }
        }
        f=new Fixture("minecraft:poppy"); f.hide(65); f.terrain.put(65,data("minecraft:snow"));
        check(f.restore().conflicts()==1 && f.at(65).contains("snow") && f.cover.hasMetadata(f.chunk),"Unresolved snow is not replaced with plants");
        f=new Fixture("minecraft:poppy"); f.failY=65;
        try { f.hide(65); throw new AssertionError("Expected failure"); } catch(IllegalStateException expected) { check(f.cover.hasMetadata(f.chunk)&&f.at(65).contains("poppy"),"Snapshot survives crash before first edit"); }
        f.failY=-1000; check(f.hide(65)==1,"Restarted winter consumes durable unfinished plan"); check(f.restore().restored()==1,"Interrupted winter can safely exit out of sequence");
        f=new Fixture("minecraft:tall_grass[half=lower]"); f.terrain.put(66,data("minecraft:tall_grass[half=upper]")); f.failY=65;
        try { f.hide(66); throw new AssertionError("Expected failure"); } catch(IllegalStateException expected) { check(f.at(66).equals("minecraft:air") && f.at(65).contains("tall_grass"),"Partial paired removal leaves recoverable backup"); }
        f.failY=-1000; check(f.restore().restored()==1 && !f.cover.hasMetadata(f.chunk),"Exit repairs partially removed pair without duplicating its lower half");
        f.hide(66); f.failY=66; try { f.restore(); throw new AssertionError("Expected failure"); }
        catch(IllegalStateException expected) { check(f.at(65).contains("half=lower") && f.cover.hasMetadata(f.chunk),"Partial restoration retains backup until both halves finish"); }
        f.failY=-1000; check(f.restore().restored()==1 && !f.cover.hasMetadata(f.chunk),"Restart completes only missing upper half");
        f=new Fixture("minecraft:poppy"); final Fixture denied=f;
        try { f.cover.hide(f.cover.read(f.chunk),0,f.block(65),()->{throw new IllegalStateException("Journal failed");}); throw new AssertionError("Expected failure"); }
        catch(IllegalStateException expected) { check(!denied.cover.hasMetadata(denied.chunk)&&denied.mutations==0,"Failed journal intent leaves plant and snapshot untouched"); }
        testCodec();
        testNaturalFade();
        System.out.println("Winter ground cover tests passed: "+checks+" assertions");
    }
    static void testCodec() throws Exception {
        GroundCoverColumns c=new GroundCoverColumns(); c.put(255,new GroundCoverColumns.Plant(-63,"minecraft:poppy",null,"minecraft:dirt"));
        check(GroundCoverColumns.decode(c.encode()).get(255).equals(c.get(255)),"Sparse negative-height snapshot round trips");
        byte[] valid=c.encode(); for(int n=0;n<valid.length;n++) expectBad(Arrays.copyOf(valid,n));
        expectBad(new byte[]{9}); expectBad(Arrays.copyOf(valid,valid.length+1)); expectBad(new byte[524289]);
        var f=new Fixture("minecraft:poppy"); f.metadata.put(f.key,new Value(PersistentDataType.STRING,"wrong"));
        try { f.cover.read(f.chunk); throw new AssertionError("Expected malformed type"); } catch(IOException expected){check(f.mutations==0 && f.metadata.containsKey(f.key),"Wrong type is fail closed and retained");}
        c=new GroundCoverColumns(); c.put(0,new GroundCoverColumns.Plant(65,"minecraft:diamond_block",null,"minecraft:grass_block"));
        f.metadata.put(f.key,new Value(PersistentDataType.BYTE_ARRAY,c.encode()));
        try { f.cover.read(f.chunk); throw new AssertionError("Expected invalid species"); } catch(IOException expected){check(f.mutations==0,"Metadata cannot claim arbitrary valuable blocks");}
        c=new GroundCoverColumns(); c.put(0,new GroundCoverColumns.Plant(65,"minecraft:tall_grass[half=lower]",null,"minecraft:grass_block"));
        f.metadata.put(f.key,new Value(PersistentDataType.BYTE_ARRAY,c.encode()));
        try { f.cover.read(f.chunk); throw new AssertionError("Expected invalid topology"); } catch(IOException expected){check(f.mutations==0,"Incomplete tall snapshot fails before terrain mutation");}
    }
    static void testNaturalFade() throws Exception {
        var f=new Fixture("minecraft:leaf_litter[segment_amount=3,facing=west]"); f.hide(65);
        f.terrain.put(65,data("minecraft:snow")); SnowColumns owned=new SnowColumns(); owned.put(0,65);
        NamespacedKey snowKey=new NamespacedKey(f.plugin,"snow_v1");
        f.metadata.put(snowKey,new Value(PersistentDataType.BYTE_ARRAY,owned.encode()));
        // The season is already autumn; protected ownership must not consult global winter.
        var snow=new SeasonalSnowService(f.plugin,()->{throw new AssertionError("Isolated fade policy must not read settings");},()->false);
        var protectedFade=new BlockFadeEvent(f.block(65),null); snow.protectSnowFade(protectedFade,true);
        check(protectedFade.isCancelled() && f.cover.hasMetadata(f.chunk),"Owned layer stays protected after global winter ends until its region restores");
        var allowedFade=new BlockFadeEvent(f.block(65),null); snow.protectSnowFade(allowedFade,false);
        check(!allowedFade.isCancelled(),"Disabled protection allows native melting outside winter");
        snow.onFade(allowedFade); f.terrain.put(65,data("minecraft:air"));
        check(!f.metadata.containsKey(snowKey) && f.cover.hasMetadata(f.chunk),"Allowed natural fade releases only snow ownership and retains exact plant backup");
        check(f.restore().restored()==1 && f.at(65).equals("minecraft:leaf_litter[segment_amount=3,facing=west]"),"Plant restoration after natural melting keeps original amount and facing");
        f=new Fixture("minecraft:poppy"); f.hide(65);
        snow=new SeasonalSnowService(f.plugin,()->{throw new AssertionError("Natural support fade needs no settings");});
        snow.onFade(new BlockFadeEvent(f.block(64),null));
        check(!f.cover.hasMetadata(f.chunk),"Non-snow support fade still relinquishes affected vegetation to preserve terrain changes");
    }
    static void expectBad(byte[] bytes) throws Exception {
        try { GroundCoverColumns.decode(bytes); throw new AssertionError("Expected malformed bytes"); } catch(IOException expected){check(true,"Malformed codec rejected");}
    }
    static void check(boolean condition,String label) { checks++; if(!condition)throw new AssertionError(label); }
}
