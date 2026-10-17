package dev.trueseasons.snow;

import java.util.*;
import org.bukkit.Material;

/** Planning must be read-only and preserve physical-snow placement guardrails. */
public final class SnowPreviewPlanningTests {
    private static int checks;
    private static final SeasonalSnowService.ReplenishmentPolicy POLICY=new SeasonalSnowService.ReplenishmentPolicy(true,true,Set.of(),List.of(),1);
    private static Map<Integer,SnowOwnershipSnapshots.PreviewColumn> plan(SnowReplenishmentTests.Fixture f,int...columns){
        return f.snow.previewColumns(f.chunk,columns,POLICY,f.snowData,data->data.getMaterial().ordinal(),null);
    }
    public static void main(String[]args){
        var f=new SnowReplenishmentTests.Fixture();
        var before=new HashMap<>(f.metadata);var result=plan(f,0,0,1);
        check(result.size()==2&&f.heightReads==2,"Preview planning deduplicates surface columns");
        check(result.get(0).y()==65&&result.get(0).supportState()==Material.STONE.ordinal()&&result.get(0).airState()==Material.AIR.ordinal(),"Immutable candidates retain exact position and source states");
        check(f.metadata.equals(before)&&f.order.isEmpty()&&f.before==0&&f.after==0&&f.formationCalls==0&&f.ownershipTouches==0,"Planning changes no blocks, metadata, progress, formation events or render intent");
        check(f.column(0).target==Material.AIR&&f.column(1).target==Material.AIR,"Preview snow is absent from the physical world");
        f=new SnowReplenishmentTests.Fixture();f.loaded=false;check(plan(f,0).isEmpty()&&f.heightReads==0,"Unloaded chunks are never inspected or loaded");
        f=new SnowReplenishmentTests.Fixture();f.column(0).survives=false;check(plan(f,0).isEmpty(),"Unsupported surfaces do not receive previews");
        f=new SnowReplenishmentTests.Fixture();f.column(0).target=Material.DIAMOND_BLOCK;check(plan(f,0).isEmpty()&&f.column(0).target==Material.DIAMOND_BLOCK,"Occupied targets and player replacements are preserved");
        f=new SnowReplenishmentTests.Fixture();f.column(0).sky=14;check(plan(f,0).isEmpty(),"Sheltered positions cannot receive previews");
        f=new SnowReplenishmentTests.Fixture();f.column(0).light=10;check(plan(f,0).isEmpty(),"Vanilla block-light rules apply to previews");
        f=new SnowReplenishmentTests.Fixture();f.column(0).height=319;check(plan(f,0).isEmpty(),"Build-height ceiling applies to previews");
        f=new SnowReplenishmentTests.Fixture();f.column(0).support=Material.SHORT_GRASS;f.column(0).survives=false;
        check(plan(f,0).isEmpty()&&f.column(0).support==Material.SHORT_GRASS,"Ground cover is left for the ordered physical pass");
        distribution();biomes();visibleKeys();
        System.out.println("Snow preview planning tests passed: "+checks+" assertions");
    }
    private static void visibleKeys(){
        var terrain=new SnowReplenishmentTests.Fixture();int[] resolves={0};
        boolean[] online={true},valid={true},dead={false},unavailable={false};int[] visibilityCalls={0};
        var world=(org.bukkit.World)java.lang.reflect.Proxy.newProxyInstance(org.bukkit.World.class.getClassLoader(),new Class<?>[]{org.bukkit.World.class},(p,m,a)->switch(m.getName()){
            case "isChunkLoaded"->(int)a[0]==4&&(int)a[1]==-3;
            case "getChunkAt"->{check((int)a[0]==4&&(int)a[1]==-3&&a.length==3&&Boolean.FALSE.equals(a[2]),"Visible collection only resolves already-loaded coordinates with generation disabled");resolves[0]++;yield terrain.chunk;}
            default->throw new AssertionError("Unexpected visibility world access "+m.getName());
        });
        var player=(org.bukkit.entity.Player)java.lang.reflect.Proxy.newProxyInstance(org.bukkit.entity.Player.class.getClassLoader(),new Class<?>[]{org.bukkit.entity.Player.class},(p,m,a)->switch(m.getName()){
            case "getWorld"->world;
            case "isOnline"->online[0];case "isValid"->valid[0];case "isDead"->dead[0];
            case "getSentChunkKeys"->{visibilityCalls[0]++;if(unavailable[0])throw new NullPointerException("Paper player chunk loader is detached");yield new LinkedHashSet<>(List.of(((long)-77<<32)|999L,((long)-3<<32)|4L));}
            case "isChunkSent"->{visibilityCalls[0]++;if(unavailable[0])throw new IllegalStateException("Player is reconfiguring");yield true;}
            default->throw new AssertionError("Visibility must use keys without loading wrappers: "+m.getName());
        });
        Set<org.bukkit.Chunk> visible=new HashSet<>();LocalWinterSnowService.collectVisible(player,visible,4);
        check(visible.equals(Set.of(terrain.chunk))&&resolves[0]==1,"Unloaded sent keys cause no load and signed chunk coordinates remain exact");
        LocalWinterSnowService.collectVisible(player,visible,1);check(resolves[0]==1,"A full visible-cache budget performs no additional chunk resolution");
        visible.clear();dead[0]=true;int calls=visibilityCalls[0];LocalWinterSnowService.collectVisible(player,visible,4);
        check(visible.isEmpty()&&visibilityCalls[0]==calls,"Death skips Paper's detached chunk loader without pausing migration");
        dead[0]=false;valid[0]=false;LocalWinterSnowService.collectVisible(player,visible,4);
        check(visibilityCalls[0]==calls,"An invalid respawning player performs no visibility query");
        valid[0]=true;online[0]=false;LocalWinterSnowService.collectVisible(player,visible,4);
        check(visibilityCalls[0]==calls,"Disconnected players perform no visibility query");
        online[0]=true;unavailable[0]=true;LocalWinterSnowService.collectVisible(player,visible,4);
        check(visible.isEmpty()&&resolves[0]==1,"Transient Paper loader failure is treated as an unavailable view without terrain loads");
        check(!dev.trueseasons.presentation.PlayerChunkVisibility.sent(player,0),"Single-chunk preview notifications also tolerate a detached loader");
        unavailable[0]=false;LocalWinterSnowService.collectVisible(player,visible,4);
        check(visible.size()==1&&dev.trueseasons.presentation.PlayerChunkVisibility.sent(player,0),"Normal visibility resumes after the player loader returns");
    }
    private static void biomes(){
        int[] lookups={0};
        var policy=new dev.trueseasons.climate.SeasonClimatePolicy(dev.trueseasons.climate.SeasonClimatePolicy.HOT,List.of());
        var cache=new WinterBiomeDecisions(policy,block->{lookups[0]++;return block.getY()<0?"minecraft:plains":"minecraft:jungle";});
        for(int y:new int[]{-64,0})for(int x=-16;x<0;x++)for(int z=-32;z<-16;z++){
            final int xx=x,yy=y,zz=z;
            var block=(org.bukkit.block.Block)java.lang.reflect.Proxy.newProxyInstance(org.bukkit.block.Block.class.getClassLoader(),new Class<?>[]{org.bukkit.block.Block.class},(p,m,a)->switch(m.getName()){
                case "getX"->xx;case "getY"->yy;case "getZ"->zz;default->throw new AssertionError("Unexpected biome cache world access: "+m.getName());
            });
            check(cache.allows(block)==(y<0),"Vertical biome cells retain separate hot-biome decisions at negative coordinates");
        }
        check(lookups[0]==32,"Two 256-column surface scans resolve only their 32 distinct three-dimensional biome cells");
    }
    private static void distribution(){
        for(int seed=0;seed<32;seed++){
            var order=new SnowColumnOrder(seed-16,seed*19,"winter-"+seed);var same=new SnowColumnOrder(seed-16,seed*19,"winter-"+seed);
            BitSet found=new BitSet(256);int[] quadrant=new int[4];int diagonal=0;
            for(int rank=0;rank<256;rank++){
                int column=order.column(rank);check(!found.get(column),"Shuffle visits each surface exactly once");found.set(column);
                check(order.rank(column)==rank&&same.column(rank)==column,"Shuffle inversion and restart determinism hold");
                if(rank<64)quadrant[((column&15)>>3)|((column>>7)<<1)]++;
                if(rank>0&&((column-order.column(rank-1))&255)==149)diagonal++;
            }
            for(int count:quadrant)check(count>=5&&count<=28,"Early coverage is spread across the chunk quadrants");
            check(diagonal<10,"Shuffle removes the former repeated diagonal stride");
        }
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
