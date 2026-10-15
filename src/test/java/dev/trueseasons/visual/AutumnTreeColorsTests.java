package dev.trueseasons.visual;

import java.util.*;
import dev.trueseasons.visual.AutumnTreeColors.*;

/** Original block fixtures; no Bukkit world, packet, chunk generation, or renderer dependency. */
public final class AutumnTreeColorsTests {
    private record Position(int x, int y, int z) {}
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    private static final class World implements Sampler {
        final Map<Position,Kind> blocks = new HashMap<>();
        int reads;
        Set<Integer> unavailableChunks = new HashSet<>();
        @Override public Kind kindAt(int x, int y, int z) {
            reads++;
            return unavailableChunks.contains(Math.floorDiv(x,16)) ? Kind.UNKNOWN
                    : blocks.getOrDefault(new Position(x,y,z), Kind.OTHER);
        }
        void set(int x,int y,int z,Kind kind) { blocks.put(new Position(x,y,z),kind); }
        void tree(int x,int z, String species) {
            for (int dx=-3; dx<=3; dx++) for(int dz=-3; dz<=3; dz++) for(int y=4;y<=6;y++)
                if (Math.abs(dx)+Math.abs(dz)<=4) set(x+dx,y,z+dz,AutumnTreeColors.kind("minecraft:"+species+"_leaves"));
            for(int y=0;y<=5;y++) set(x,y,z,AutumnTreeColors.kind("minecraft:"+species+"_log"));
        }
    }
    public static void main(String[] args) {
        classification(); wholeTrees(); touchingCrowns(); decorative(); crossChunk(); branches(); bounded(); invalidation();
        System.out.println("Autumn tree colors: " + checks + " assertions passed");
    }
    private static void classification() {
        for (String species : List.of("oak","birch","jungle","acacia","dark_oak","mangrove","cherry")) {
            check(AutumnTreeColors.kind("minecraft:"+species+"_leaves").leaves(), species+" leaves eligible");
            check(AutumnTreeColors.kind("minecraft:"+species+"_log").log(), species+" logs eligible");
            check(AutumnTreeColors.kind("minecraft:stripped_"+species+"_wood").log(), species+" stripped wood classified");
        }
        for (String name : List.of("spruce_leaves","birch_sapling","red_poplar_leaves","orange_poplar_leaves",
                "yellow_poplar_leaves","pale_oak_leaves","azalea_leaves","flowering_azalea_leaves","oak_planks","mangrove_roots"))
            check(AutumnTreeColors.kind("minecraft:"+name)==Kind.OTHER,name+" preserved");
        check(AutumnTreeColors.kind("custom:oak_leaves")==Kind.OTHER,"custom leaves preserved");
        check(AutumnTreeColors.kind(null)==Kind.UNKNOWN,"missing blocks remain unavailable");
        check(Appearance.ORIGINAL.blockKey()==null,"original has no substitute");
        check(Appearance.RED.blockKey().equals("minecraft:red_poplar_leaves"),"cherry target key");
    }
    private static void wholeTrees() {
        for (String species : List.of("oak","birch","jungle","acacia","dark_oak","mangrove","cherry")) {
            World world = new World(); world.tree(15,-17,species);
            Session view = new Session(987654321,world);
            Appearance expected=view.appearanceAt(16,5,-17);
            check(expected!=Appearance.ORIGINAL,species+" paints");
            for (var entry: world.blocks.entrySet()) if(entry.getValue().leaves()) {
                Position p=entry.getKey();
                check(view.appearanceAt(p.x,p.y,p.z)==expected,species+" entire crown uses trunk color");
                check(new Session(987654321,world).appearanceAt(p.x,p.y,p.z)==expected,species+" restart/order stability");
            }
            if(species.equals("cherry")) check(expected==Appearance.RED,"cherry strictly red");
            check(world.reads < 100000, species+" reads bounded even across independent sessions");
        }
        Set<Appearance> variants=EnumSet.noneOf(Appearance.class);
        for(int i=0;i<40;i++) {
            World world=new World(); world.tree(i*12,0,"birch");
            variants.add(new Session(123,world).appearanceAt(i*12+1,5,0));
        }
        check(variants.equals(EnumSet.of(Appearance.YELLOW,Appearance.ORANGE,Appearance.RED)),"one species has all three colors");
        Set<Appearance> seeded=EnumSet.noneOf(Appearance.class);
        World world=new World();world.tree(0,0,"oak");
        for(int i=0;i<40;i++) seeded.add(new Session(i,world).appearanceAt(1,5,0));
        check(seeded.size()==3,"world seed contributes stable variation");
    }
    private static void touchingCrowns() {
        World world=new World();world.tree(0,0,"oak");world.tree(6,0,"oak");
        Session forward=new Session(20,world),reverse=new Session(20,world);
        Appearance left=forward.appearanceAt(1,5,0),right=forward.appearanceAt(5,5,0);
        check(left!=Appearance.ORIGINAL && right!=Appearance.ORIGINAL,"touching crowns resolve both trunks");
        for(int x=-2;x<=2;x++) if(x!=0) check(forward.appearanceAt(x,5,0)==left,"left crown stays with its trunk");
        for(int x=4;x<=8;x++) if(x!=6) check(forward.appearanceAt(x,5,0)==right,"right crown stays with its trunk");
        Appearance center=forward.appearanceAt(3,5,0);
        for(int x=8;x>=-2;x--) if(x!=0 && x!=6) reverse.appearanceAt(x,5,0);
        check(reverse.appearanceAt(3,5,0)==center,"canopy tie independent of visitation order");
        // Different species never claim one another's logs.
        World mixed=new World();mixed.tree(0,0,"oak");mixed.tree(6,0,"birch");
        check(new Session(20,mixed).appearanceAt(1,5,0)==left,"nearby birch cannot anchor oak");
        World interrupted=new World();interrupted.set(0,5,0,Kind.OAK_LOG);interrupted.set(1,5,0,Kind.BIRCH_LEAVES);interrupted.set(2,5,0,Kind.OAK_LEAVES);
        World reference=new World();reference.set(0,5,0,Kind.OAK_LOG);reference.set(1,5,0,Kind.OAK_LEAVES);
        check(new Session(20,interrupted).appearanceAt(2,5,0)==new Session(20,reference).appearanceAt(1,5,0),"mixed intersecting leaves do not break matching trunk path");
    }
    private static void decorative(){
        World floating=new World();floating.set(3,5,3,Kind.BIRCH_LEAVES);
        Appearance expected=new Session(99,floating).appearanceAt(3,5,3);
        check(expected!=Appearance.ORIGINAL,"trunkless leaves have seasonal appearance");
        Sampler persistent=new Sampler(){public Kind kindAt(int x,int y,int z){return Kind.BIRCH_LEAVES;}public boolean persistentAt(int x,int y,int z){return true;}};
        Session hedge=new Session(99,persistent);
        for(int x=-100;x<100;x++)check(hedge.appearanceAt(x,20,-1)==expected,"persistent hedge coherent without neighbors or trunk");
    }
    private static void crossChunk() {
        World world=new World();world.tree(15,0,"birch");
        Session complete=new Session(2,world);
        Appearance expected=complete.appearanceAt(14,5,0);
        check(complete.appearanceAt(16,5,0)==expected,"crosschunk canopy shares trunk anchor");
        world.unavailableChunks.add(1);
        Session partial=new Session(2,world);
        check(partial.appearanceAt(14,5,0)==Appearance.ORIGINAL,"missing trunk halo fails safely");
        world.unavailableChunks.clear();
        check(partial.appearanceAt(14,5,0)==expected,"unknown not cached permanently");
        World negative=new World();negative.tree(-16,0,"oak");
        Session negatives=new Session(88,negative);
        check(negatives.appearanceAt(-17,5,0)==negatives.appearanceAt(-15,5,0),"negative chunk boundary stable");
    }
    private static void branches() {
        World world=new World();world.tree(0,0,"acacia");
        world.set(1,6,0,Kind.ACACIA_LOG);world.set(2,7,0,Kind.ACACIA_LOG);
        world.set(3,7,0,Kind.ACACIA_LEAVES);
        Session view=new Session(3,world);
        check(view.appearanceAt(-1,5,0)==view.appearanceAt(3,7,0),"diagonal acacia branch resolves same trunk");
        World giant=new World();
        for(int y=0;y<100;y++) for(int x=0;x<2;x++) for(int z=0;z<2;z++)giant.set(x,y,z,Kind.JUNGLE_LOG);
        giant.set(-1,98,0,Kind.JUNGLE_LEAVES);giant.set(2,98,1,Kind.JUNGLE_LEAVES);
        Session large=new Session(30,giant);
        check(large.appearanceAt(-1,98,0)!=Appearance.ORIGINAL,"large jungle trunk fits budget");
        check(large.appearanceAt(-1,98,0)==large.appearanceAt(2,98,1),"two by two tall trunk stable");
    }
    private static void bounded() {
        World world=new World();world.tree(0,0,"oak");Session session=new Session(1,world);
        for(var entry:world.blocks.entrySet()) if(entry.getValue().leaves()) {Position p=entry.getKey();session.appearanceAt(p.x,p.y,p.z);}
        check(world.reads<1000,"sample cache amortizes complete canopy traversal");
        int before=world.reads;
        for(var entry:world.blocks.entrySet()) if(entry.getValue().leaves()) {Position p=entry.getKey();session.appearanceAt(p.x,p.y,p.z);}
        check(world.reads==before,"repeat canopy lookup has zero sampler reads");
        World huge=new World();
        huge.set(0,0,0,Kind.OAK_LEAVES);
        for(int x=1;x<4000;x++)huge.set(x,0,0,Kind.OAK_LOG);
        check(new Session(1,huge).appearanceAt(0,0,0)==Appearance.ORIGINAL,"oversized log network left unchanged");
        check(huge.reads<=AutumnTreeColors.MAX_SEARCH_READS+1,"adversarial log network bounded reads");
        World floating=new World();for(int x=-10;x<=10;x++)for(int y=-10;y<=10;y++)for(int z=-10;z<=10;z++)floating.set(x,y,z,Kind.OAK_LEAVES);
        check(new Session(1,floating).appearanceAt(0,0,0)!=Appearance.ORIGINAL,"trunkless leaf mass receives stable decorative fallback");
        check(floating.reads<=AutumnTreeColors.MAX_LEAF_SEARCH+1,"unsupported leaf mass bounded reads");
    }
    private static void invalidation() {
        World world=new World();world.tree(0,0,"oak");Session view=new Session(5,world);
        check(view.appearanceAt(1,5,0)!=Appearance.ORIGINAL,"initial tree colored");
        world.blocks.replaceAll((position,kind)->kind.log()?Kind.OTHER:kind);view.invalidate();
        check(view.appearanceAt(1,5,0)!=Appearance.ORIGINAL,"trunkless surviving leaves remain seasonal after invalidation");
        world.set(100,100,100,Kind.CHERRY_LEAVES);
        check(view.appearanceAt(100,100,100)==Appearance.RED,"cherry hardcoded red including standalone leaves");
    }
}
