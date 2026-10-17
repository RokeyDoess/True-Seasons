package dev.trueseasons.presentation;

import dev.trueseasons.api.Season;
import dev.trueseasons.climate.SeasonClimatePolicy;
import dev.trueseasons.snow.SnowOwnershipSnapshots;
import dev.trueseasons.visual.SeasonVisualResolver;
import io.netty.buffer.*;
import io.netty.channel.embedded.EmbeddedChannel;
import it.unimi.dsi.fastutil.shorts.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

/** Whole connection pipeline: original biomes, autumn blocks and gradual real snow. */
public final class SeasonalPresentationPacketTests {
    private static int checks;
    private static PaperLeafPackets leaves;
    private static PaperSnowPackets snowPackets;
    private static NativeLeafRegistry registry;
    private static Object nativeIds;
    private static int air,stone,snow,birch;
    private static final String VERSION="composite-winter";
    private static final SeasonClimatePolicy POLICY=new SeasonClimatePolicy(SeasonClimatePolicy.HOT,SeasonClimatePolicy.COLD);
    public static void main(String[] args)throws Exception{
        type("net.minecraft.SharedConstants").getMethod("tryDetectVersion").invoke(null);
        type("net.minecraft.server.Bootstrap").getMethod("bootStrap").invoke(null);
        leaves=new PaperLeafPackets();snowPackets=new PaperSnowPackets(leaves);registry=leaves.registry;
        nativeIds=type("net.minecraft.world.level.block.Block").getField("BLOCK_STATE_REGISTRY").get(null);
        air=defaultId("AIR");stone=defaultId("STONE");snow=defaultId("SNOW");birch=defaultId("BIRCH_LEAVES");
        Object property=type("net.minecraft.world.level.block.state.properties.BlockStateProperties").getField("PERSISTENT").get(null);
        birch=id(registry.states[birch].getClass().getMethod("setValue",type("net.minecraft.world.level.block.state.properties.Property"),Comparable.class).invoke(registry.states[birch],property,true));
        pipeline();respawnAndDisconnect();alreadyVisibleWinterEntry();stormRevealBarrier();previewDigging();sharedSnowBudget();snowmelt();meltDigging();
        System.out.println("Seasonal presentation packet tests passed: "+checks+" assertions");
        type("org.apache.logging.log4j.LogManager").getMethod("shutdown").invoke(null);
    }
    private static void pipeline()throws Exception{
        var owner=owners();publish(owner,0,0,plane(2));
        var leafContext=new PaperLeafPackets.Context(42,0,16,true);
        var initialSnow=new PaperSnowPackets.Context(VERSION,64,0,16,owner);
        Map<String,PaperLeafPackets.Context> leafWorlds=new HashMap<>(Map.of("minecraft:overworld",leafContext));
        Map<String,PaperSnowPackets.Context> snowWorlds=new HashMap<>(Map.of("minecraft:overworld",initialSnow));
        var resolver=resolver();var handler=handler(resolver,leafWorlds,snowWorlds);EmbeddedChannel channel=new EmbeddedChannel(handler);
        try{
            Object registryPacket=biomeRegistry();channel.writeOutbound(registryPacket);Object expanded=channel.readOutbound();
            check(((List<?>)call(expanded,"entries")).size()==3*PaperBiomePackets.COLOR_BANKS,"Full pipeline publishes the bounded seasonal stage registry");
            check(((List<?>)call(registryPacket,"entries")).size()==3,"Original registry remains unchanged");
            handler.setPresentation(false,Season.AUTUMN,POLICY);
            Object login=login("minecraft:overworld");channel.writeOutbound(login);check(channel.readOutbound()==login,"Login selects snow and leaf contexts before first chunk");
            int[] source=planeSource(2);source[index(1,5,1)]=birch;source[index(5,5,1)]=birch;
            int[] biomes=new int[64];biomes[biomeIndex(5,5,1)]=1; // Desert adjacent to a temperate hedge.
            byte[] raw=sectionWire(source,biomes);Object original=chunk(raw,0,0);channel.writeOutbound(original);
            Object painted=channel.readOutbound();int[] client=decode(painted);
            check(client[index(1,5,1)]!=birch,"Temperate decorative birch colors through full pipeline");
            check(client[index(5,5,1)]==birch,"Desert birch excluded using original biome IDs before aliases");
            int shown=0;for(int c=0;c<256;c++){int expected=rank(c,0,0)<64?snow:air;check(client[index(c&15,2,c>>4)]==expected,"Initial chunk snow follows shared coverage");if(expected==snow)shown++;}
            check(shown==64,"First sent chunk has exact 25-percent winter snow presentation");
            var section=LeafWireCodec.read(buffer(call(painted,"chunkData")),id->true).getFirst();
            check(section.blocks()==66,"Leaf and snow rewrites compose corrected non-air count");
            ByteBuf biomeBytes=Unpooled.wrappedBuffer(section.biomes());try{
                int[] mapped=BiomeWireCodec.readBiomes(biomeBytes);
                for(int cell=0;cell<64;cell++)check(mapped[cell]==(biomes[cell]==1?1:12),"Biome aliases apply after leaf climate sampling");
            }finally{biomeBytes.release();}
            check(Arrays.equals(buffer(call(original,"chunkData")),raw),"Composite chunk processing never changes original bytes");

            Object newBiomes=biomesPacket(new int[64],0,0);channel.writeOutbound(newBiomes);Object aliased=channel.readOutbound();
            Object row=((List<?>)call(aliased,"chunkBiomeData")).getFirst();ByteBuf updateBytes=Unpooled.wrappedBuffer((byte[])call(row,"buffer"));
            try{for(int value:BiomeWireCodec.readBiomes(updateBytes))check(value==12,"Biome update forwards seasonal aliases");}finally{updateBytes.release();}
            pumpLeaves(handler,channel,leafContext,client,0,0,0);
            check(client[index(5,5,1)]!=birch,"Original biome-only update repaints already sent former desert leaves");
            var excludedPlains=new SeasonClimatePolicy(SeasonClimatePolicy.HOT,SeasonClimatePolicy.COLD,List.of("plains"));
            handler.setPresentation(false,Season.AUTUMN,excludedPlains);readAll(channel);
            pumpLeaves(handler,channel,leafContext,client,0,0,0);
            check(client[index(1,5,1)]==birch&&client[index(5,5,1)]==birch,"Changed biome policy restores cached leaves without new chunk");
            handler.setPresentation(false,Season.AUTUMN,POLICY);readAll(channel);pumpLeaves(handler,channel,leafContext,client,0,0,0);
            check(client[index(1,5,1)]!=birch&&client[index(5,5,1)]!=birch,"Restored eligibility repaints cached foliage");

            var complete=new PaperSnowPackets.Context(VERSION,256,0,16,owner);
            List<Object> first=pumpSnow(handler,channel,complete);check(updateCount(first)==128,"Handler snow pump caps each reveal at 128 blocks");apply(client,first,0,0,0);
            check((boolean)field(handler,"snowPending"),"Partial handler reveal remains pending for next tick");
            List<Object> second=pumpSnow(handler,channel,complete);check(updateCount(second)==64,"Next handler tick finishes remaining snow without repeats");apply(client,second,0,0,0);
            check(!(boolean)field(handler,"snowPending"),"Handler pending flag clears after bounded reveals finish");
            for(int c=0;c<256;c++)check(client[index(c&15,2,c>>4)]==snow,"Completed handler coverage reveals each original layer");
            check(pumpSnow(handler,channel,complete).isEmpty(),"Unchanged complete presentation emits no duplicate snow packets");

            // A bundle delivers newest original edits before a later reveal/restore.
            var hidden=new PaperSnowPackets.Context(VERSION,0,0,16,owner);List<Object> hideFirst=pumpSnow(handler,channel,hidden);apply(client,hideFirst,0,0,0);
            while((boolean)field(handler,"snowPending"))apply(client,pumpSnow(handler,channel,hidden),0,0,0);
            Object bundle=type("net.minecraft.network.protocol.game.ClientboundBundlePacket").getConstructor(Iterable.class)
                .newInstance(List.of(single(0,2,0,stone),batch(0,0,0,Map.of(index(1,2,0),air,index(1,5,1),stone))));
            channel.writeOutbound(bundle);Object outgoing=channel.readOutbound();List<Object> children=new ArrayList<>();
            for(Object child:(Iterable<?>)call(outgoing,"subPackets"))children.add(child);
            check(children.size()==2,"Bundled single and batch order preserved");
            check(id(call(children.getFirst(),"getBlockState"))==stone,"Bundled replacement remains exact stone");client[index(0,2,0)]=stone;apply(client,List.of(children.get(1)),0,0,0);
            check(client[index(1,2,0)]==air&&client[index(1,5,1)]==stone,"Bundled snow and decorative-leaf edits forwarded exactly");
            List<Object> restore=pumpSnow(handler,channel,null);apply(client,restore,0,0,0);
            while((boolean)field(handler,"snowPending"))apply(client,pumpSnow(handler,channel,null),0,0,0);
            check(client[index(0,2,0)]==stone&&client[index(1,2,0)]==air,"Null snow context never restores stale broken/replaced snow");
            check(client[index(2,2,0)]==snow,"Null snow context restores surviving real snow");
            check(((PaperSnowPackets.View)field(handler,"snow")).retainedColumns()==0,"Handler null restore releases snow candidates");

            pumpSnow(handler,channel,hidden);while((boolean)field(handler,"snowPending"))pumpSnow(handler,channel,hidden);
            // Full chunk refresh records actual surviving snow, then hides it again for retirement.
            source[index(0,2,0)]=stone;source[index(1,2,0)]=air;source[index(1,5,1)]=stone;
            channel.writeOutbound(chunk(sectionWire(source,new int[64]),0,0));client=decode(channel.readOutbound());
            invoke(handler,"retire");List<Object> retired=readAll(channel);apply(client,retired,0,0,0);
            check(Arrays.equals(client,source),"Retirement restores exact latest leaf and snow source state together");
            boolean restoredBiomes=false;for(Object packet:retired)if(packet.getClass().getSimpleName().equals("ClientboundChunksBiomesPacket")){
                restoredBiomes=true;Object entry=((List<?>)call(packet,"chunkBiomeData")).getFirst();ByteBuf bytes=Unpooled.wrappedBuffer((byte[])call(entry,"buffer"));
                try{for(int value:BiomeWireCodec.readBiomes(bytes))check(value==0,"Retirement restores latest original biome IDs");}finally{bytes.release();}
            }
            check(restoredBiomes,"Retirement includes exact original biomes");
            check(((PaperSnowPackets.View)field(handler,"snow")).retainedColumns()==0&&((PaperLeafPackets.View)field(handler,"leaves")).retainedStates()==0,"Retirement clears both presentation caches");
            Object after=single(2,2,0,snow);channel.writeOutbound(after);check(channel.readOutbound()==after,"Retired pipeline forwards real snow updates unchanged");
            check(channel.isActive(),"Successful composited pipeline never closes the player connection");
        }finally{channel.finishAndReleaseAll();}
    }
    private static void respawnAndDisconnect()throws Exception{
        var firstOwner=owners();var otherOwner=owners();publish(firstOwner,0,0,plane(2));publish(otherOwner,0,0,plane(-14));
        Map<String,PaperLeafPackets.Context> leafWorlds=Map.of("minecraft:overworld",new PaperLeafPackets.Context(42,0,16,true),"trueseasons:other",new PaperLeafPackets.Context(77,-16,0,true));
        Map<String,PaperSnowPackets.Context> snowWorlds=Map.of("minecraft:overworld",new PaperSnowPackets.Context(VERSION,256,0,16,firstOwner),"trueseasons:other",new PaperSnowPackets.Context(VERSION,0,-16,0,otherOwner));
        var handler=handler(resolver(),leafWorlds,snowWorlds);EmbeddedChannel channel=new EmbeddedChannel(handler);
        try{
            channel.writeOutbound(biomeRegistry());channel.readOutbound();handler.setPresentation(false,Season.AUTUMN,POLICY);
            channel.writeOutbound(login("minecraft:overworld"));channel.readOutbound();int[] first=planeSource(2);first[index(1,5,1)]=birch;
            channel.writeOutbound(chunk(sectionWire(first,new int[64]),0,0));check(decode(channel.readOutbound())[index(0,2,0)]==snow,"First world uses its completed winter presentation");
            Object spawn=spawn("trueseasons:other"),respawn=type("net.minecraft.network.protocol.game.ClientboundRespawnPacket").getConstructor(spawn.getClass(),byte.class).newInstance(spawn,(byte)0);
            channel.writeOutbound(respawn);check(channel.readOutbound()==respawn,"Respawn chooses next context before first destination chunk");
            int[] other=planeSource(2);other[index(1,5,1)]=birch;
            channel.writeOutbound(chunk(sectionWire(other,new int[64]),0,0));int[] client=decode(channel.readOutbound());
            check(client[index(0,2,0)]==air&&client[index(1,5,1)]!=birch,"First negative-height destination chunk uses its snow and foliage contexts");
            check(((PaperSnowPackets.View)field(handler,"snow")).retainedColumns()==256,"Respawn discards prior-world snow cache rather than merging coordinates");
            Object replacement=single(1,-14,0,stone);channel.writeOutbound(replacement);check(channel.readOutbound()==replacement,"Destination negative-height replacement remains unchanged");client[index(1,2,0)]=stone;
            List<Object> restored=pumpSnow(handler,channel,null);apply(client,restored,0,-1,0);
            while((boolean)field(handler,"snowPending"))apply(client,pumpSnow(handler,channel,null),0,-1,0);
            check(client[index(1,2,0)]==stone&&client[index(0,2,0)]==snow,"Negative-height restoration preserves newest destination edit");
            channel.close();channel.runPendingTasks();
            check(((PaperSnowPackets.View)field(handler,"snow")).retainedColumns()==0&&((PaperLeafPackets.View)field(handler,"leaves")).retainedStates()==0,"Disconnect clears all cached original snow and foliage");
            check(((Map<?,?>)field(handler,"originals")).isEmpty(),"Disconnect clears original biome cache");
        }finally{channel.finishAndReleaseAll();}
    }
    private static void alreadyVisibleWinterEntry()throws Exception{
        var owner=owners();var base=new PaperSnowPackets.Context(VERSION,256,0,16,owner);
        Map<String,PaperLeafPackets.Context> foliage=Map.of("minecraft:overworld",new PaperLeafPackets.Context(42,0,16,false));
        var handler=handler(resolver(),foliage,Map.of("minecraft:overworld",base));EmbeddedChannel channel=new EmbeddedChannel(handler);
        try{
            channel.writeOutbound(biomeRegistry());channel.readOutbound();channel.writeOutbound(login("minecraft:overworld"));channel.readOutbound();
            int[] source=new int[4096];Arrays.fill(source,air);channel.writeOutbound(chunk(sectionWire(source,new int[64]),0,0));
            check(Arrays.equals(decode(channel.readOutbound()),source),"Inactive snow context forwards already-visible bare chunk without modifications");
            var winter=new PaperSnowPackets.Context(VERSION,0,0,16,owner);check(pumpSnow(handler,channel,winter).isEmpty(),"Entering winter does not invent snow before physical placement");
            publish(owner,0,0,Map.of(0,5));Method dirty=handler.getClass().getDeclaredMethod("dirtySnow",int.class,int.class);dirty.setAccessible(true);dirty.invoke(handler,0,0);
            Object physical=single(0,5,0,snow);channel.writeOutbound(physical);Object masked=channel.readOutbound();
            check(id(call(masked,"getBlockState"))==air,"First regional snow update in previously-visible terrain is masked immediately");
            check(id(call(physical,"getBlockState"))==snow,"Already-visible winter entry keeps physical source update unchanged");
            check(pumpSnow(handler,channel,winter).isEmpty(),"Ownership dirty queue does not duplicate an already-hidden placement");
            List<Object> reveal=pumpSnow(handler,channel,base);check(updateCount(reveal)==1,"Winter completion reveals newly-owned snow in original visible chunk");
            int[] client=source.clone();apply(client,reveal,0,0,0);check(client[index(0,5,0)]==snow,"Retained sent marker supports complete reveal without full chunk resend");
        }finally{channel.finishAndReleaseAll();}
    }
    private static NativeSnowPresentation.ClimateHandler handler(SeasonVisualResolver resolver,Map<String,PaperLeafPackets.Context> foliage,Map<String,PaperSnowPackets.Context> snow)throws ReflectiveOperationException{
        return new NativeSnowPresentation.ClimateHandler(new PaperBiomePackets(Map.of()),Logger.getAnonymousLogger(),POLICY,()->resolver,leaves,()->foliage,snowPackets,()->snow);
    }
    private static void stormRevealBarrier()throws Exception{
        var owner=owners();Map<Integer,Integer> heights=new HashMap<>();for(int c=0;c<128;c++)heights.put(c,2);publish(owner,0,0,heights);
        var hidden=new PaperSnowPackets.Context(VERSION,0,0,16,owner);
        var finished=new PaperSnowPackets.Context(VERSION,256,0,16,owner);
        var handler=handler(resolver(),Map.of("minecraft:overworld",new PaperLeafPackets.Context(42,0,16,false)),Map.of("minecraft:overworld",hidden));
        EmbeddedChannel channel=new EmbeddedChannel(handler);
        try{
            channel.writeOutbound(biomeRegistry());channel.readOutbound();channel.writeOutbound(login("minecraft:overworld"));channel.readOutbound();
            Object snowy=type("net.minecraft.world.level.block.state.properties.BlockStateProperties").getField("SNOWY").get(null);
            int grass=defaultId("GRASS_BLOCK");grass=id(registry.states[grass].getClass().getMethod("setValue",type("net.minecraft.world.level.block.state.properties.Property"),Comparable.class).invoke(registry.states[grass],snowy,true));
            int[] source=new int[4096];Arrays.fill(source,air);for(int c=0;c<128;c++){source[index(c&15,1,c>>4)]=grass;source[index(c&15,2,c>>4)]=snow;}
            channel.writeOutbound(chunk(sectionWire(source,new int[64]),0,0));int[] client=decode(channel.readOutbound());
            check(waiting(handler,hidden,"minecraft:overworld"),"An applied partial target with hidden snow keeps the opening storm active");
            check(!waiting(handler,hidden,"minecraft:the_nether"),"Another dimension's viewer cannot hold this world's storm");
            check(waiting(handler,finished,"minecraft:overworld"),"Terrain completion waits for acknowledgment of final coverage before a pump is queued");
            // Reproduce a newer target coalesced while the old pump finishes: wanted
            // already changed, but applied still describes the previous completed pump.
            Field wanted=handler.getClass().getDeclaredField("snowWanted");wanted.setAccessible(true);wanted.set(handler,finished);
            check(!(boolean)field(handler,"snowPending"),"Coalesced target can arrive after the old target has no remaining queue");
            List<Object> first=pumpSnow(handler,channel,finished);apply(client,first,0,0,0);
            check(updateCount(first)==128&&((PaperSnowPackets.View)field(handler,"snow")).hiddenColumns()==0,"First final-coverage pump reveals 128 layers within its budget");
            check(waiting(handler,finished,"minecraft:overworld"),"Pending ground-texture restoration keeps the storm active after all layers reveal");
            List<Object> second=pumpSnow(handler,channel,finished);apply(client,second,0,0,0);
            check(updateCount(second)==128&&Arrays.equals(client,source),"Next pump restores the original snowy supports");
            check(!waiting(handler,finished,"minecraft:overworld"),"Weather can restore immediately after final layer and support updates drain");
            Method dirty=handler.getClass().getDeclaredMethod("dirtySnow",int.class,int.class);dirty.setAccessible(true);dirty.invoke(handler,0,0);
            check(!waiting(handler,finished,"minecraft:overworld"),"Metadata notifications without hidden visuals cannot extend the opening storm");
            check(pumpSnow(handler,channel,finished).isEmpty(),"Settled ownership notifications emit no extra visual updates");
            pumpSnow(handler,channel,hidden);check(waiting(handler,hidden,"minecraft:overworld"),"A later pending reveal is observed");
            channel.close();channel.runPendingTasks();check(!waiting(handler,hidden,"minecraft:overworld"),"Disconnected clients cannot strand a winter storm");
        }finally{channel.finishAndReleaseAll();}
    }
    private static boolean waiting(Object handler,PaperSnowPackets.Context desired,String world)throws Exception{
        Method method=handler.getClass().getDeclaredMethod("snowRevealPending",PaperSnowPackets.Context.class,String.class);method.setAccessible(true);return (boolean)method.invoke(handler,desired,world);
    }
    private static void previewDigging()throws Exception{
        var owner=owners();Map<Integer,SnowOwnershipSnapshots.PreviewColumn> candidates=new HashMap<>();
        for(int c=0;c<256;c++)candidates.put(c,new SnowOwnershipSnapshots.PreviewColumn(2,stone,air));
        Method publishPreview=owner.getClass().getDeclaredMethod("preview",int.class,int.class,SnowOwnershipSnapshots.Preview.class);publishPreview.setAccessible(true);
        publishPreview.invoke(owner,0,0,new SnowOwnershipSnapshots.Preview(VERSION,System.nanoTime(),candidates));
        var desired=new PaperSnowPackets.Context(VERSION,256,0,16,owner,true,60);
        java.util.concurrent.atomic.AtomicReference<NativeSnowPresentation.PreviewBreak> broken=new java.util.concurrent.atomic.AtomicReference<>();
        var palette=resolver();
        var handler=new NativeSnowPresentation.ClimateHandler(new PaperBiomePackets(Map.of()),Logger.getAnonymousLogger(),POLICY,()->palette,leaves,
            ()->Map.of("minecraft:overworld",new PaperLeafPackets.Context(42,0,16,false)),snowPackets,()->Map.of("minecraft:overworld",desired),broken::set);
        EmbeddedChannel channel=new EmbeddedChannel(handler);
        try{
            channel.writeOutbound(biomeRegistry());readAll(channel);channel.writeOutbound(login("minecraft:overworld"));readAll(channel);
            int[] source=new int[4096];Arrays.fill(source,air);for(int c=0;c<256;c++)source[index(c&15,1,c>>4)]=stone;
            channel.writeOutbound(chunk(sectionWire(source,new int[64]),0,0));Object shown=channel.readOutbound();
            var view=(PaperSnowPackets.View)field(handler,"snow");check(view.previewColumns()==256,"Whole connection pipeline shows preview snow without world ownership");
            Class<?> action=type("net.minecraft.network.protocol.game.ServerboundPlayerActionPacket$Action"),direction=type("net.minecraft.core.Direction");
            Object start=Arrays.stream(action.getEnumConstants()).filter(value->((Enum<?>)value).name().equals("START_DESTROY_BLOCK")).findFirst().orElseThrow();
            Object up=Arrays.stream(direction.getEnumConstants()).filter(value->((Enum<?>)value).name().equals("UP")).findFirst().orElseThrow();
            Object pos=type("net.minecraft.core.BlockPos").getConstructor(int.class,int.class,int.class).newInstance(0,2,0);
            Object dig=type("net.minecraft.network.protocol.game.ServerboundPlayerActionPacket").getConstructor(action,pos.getClass(),direction,int.class).newInstance(start,pos,up,7);
            channel.writeInbound(dig);check(channel.readInbound()==dig,"Digging remains available for normal server validation");
            Object correction=channel.readOutbound();check(id(call(correction,"getBlockState"))==air&&view.previewColumns()==255,"Preview digging sends the original air immediately through the native handler");
            check(broken.get()!=null&&broken.get().version().equals(VERSION)&&broken.get().dimension().equals("minecraft:overworld")&&broken.get().x()==0&&broken.get().y()==2,"Digging publishes bounded immutable coordinates for shared main-thread cooldown handling");
            publishPreview.invoke(owner,0,0,new SnowOwnershipSnapshots.Preview(VERSION,System.nanoTime(),candidates));
            Method dirty=handler.getClass().getDeclaredMethod("dirtySnow",int.class,int.class);dirty.setAccessible(true);dirty.invoke(handler,0,0);pumpSnow(handler,channel,desired);
            check(view.previewColumns()==255,"Replanning and connection pumps cannot bypass preview digging grace");
            channel.writeOutbound(login("trueseasons:other"));readAll(channel);check(view.previewColumns()==0,"Changing dimension drops the former world's previews");
        }finally{channel.finishAndReleaseAll();}
    }
    private static void meltDigging()throws Exception{
        int flower=defaultId("DANDELION");var owner=owners();
        Method publish=owner.getClass().getDeclaredMethod("preview",int.class,int.class,SnowOwnershipSnapshots.Preview.class);publish.setAccessible(true);
        publish.invoke(owner,0,0,new SnowOwnershipSnapshots.Preview(VERSION,System.nanoTime(),Map.of(0,new SnowOwnershipSnapshots.PreviewColumn(2,stone,flower)),true));
        var desired=new PaperSnowPackets.Context(VERSION,256,0,16,owner,true,60);var palette=resolver();
        java.util.concurrent.atomic.AtomicReference<NativeSnowPresentation.PreviewBreak> broken=new java.util.concurrent.atomic.AtomicReference<>();
        var handler=new NativeSnowPresentation.ClimateHandler(new PaperBiomePackets(Map.of()),Logger.getAnonymousLogger(),POLICY,()->palette,leaves,Map::of,snowPackets,()->Map.of("minecraft:overworld",desired),broken::set);
        EmbeddedChannel channel=new EmbeddedChannel(handler);
        try{
            channel.writeOutbound(biomeRegistry());readAll(channel);channel.writeOutbound(login("minecraft:overworld"));readAll(channel);
            int[] source=new int[4096];Arrays.fill(source,air);source[index(0,1,0)]=stone;source[index(0,2,0)]=flower;
            channel.writeOutbound(chunk(sectionWire(source,new int[64]),0,0));check(decode(channel.readOutbound())[index(0,2,0)]==snow,"A restored flower is covered by the remaining melt preview");
            Class<?> action=type("net.minecraft.network.protocol.game.ServerboundPlayerActionPacket$Action"),direction=type("net.minecraft.core.Direction");
            Object start=Arrays.stream(action.getEnumConstants()).filter(value->((Enum<?>)value).name().equals("START_DESTROY_BLOCK")).findFirst().orElseThrow();
            Object up=Arrays.stream(direction.getEnumConstants()).filter(value->((Enum<?>)value).name().equals("UP")).findFirst().orElseThrow();
            Object pos=type("net.minecraft.core.BlockPos").getConstructor(int.class,int.class,int.class).newInstance(0,2,0);
            Object dig=type("net.minecraft.network.protocol.game.ServerboundPlayerActionPacket").getConstructor(action,pos.getClass(),direction,int.class).newInstance(start,pos,up,42);
            channel.writeInbound(dig);check(channel.readInbound()==null,"Clearing cosmetic snow cannot send a destructive dig to the restored flower");
            List<Object> packets=readAll(channel);
            check(packets.size()==2&&id(call(packets.getFirst(),"getBlockState"))==flower,"Clearing a melt preview immediately reveals its real flower");
            check((int)call(packets.getLast(),"sequence")==42,"Consumed cosmetic digging acknowledges the client's sequence");
            check(broken.get()!=null,"Clearing melt snow requests durable column invalidation on the main thread");
        }finally{channel.finishAndReleaseAll();}
    }
    private static SeasonVisualResolver resolver(){YamlConfiguration config=new YamlConfiguration();for(Season season:Season.values())config.set("palettes.temperate."+season.key(),List.of("83B957","D98637"));return new SeasonVisualResolver(config,POLICY);}
    private static List<Object> pumpSnow(Object handler,EmbeddedChannel channel,PaperSnowPackets.Context next)throws Exception{invoke(handler,"requestSnow",PaperSnowPackets.Context.class,next);channel.runPendingTasks();return readAll(channel);}
    private static void sharedSnowBudget()throws Exception{
        var budget=new SnowPresentationBudget(2,100);var owner=owners();publish(owner,0,0,plane(2));
        var hidden=new PaperSnowPackets.Context(VERSION,0,0,16,owner);
        var finished=new PaperSnowPackets.Context(VERSION,256,0,16,owner);
        List<NativeSnowPresentation.ClimateHandler> handlers=new ArrayList<>();List<EmbeddedChannel> channels=new ArrayList<>();
        int[][] clients=new int[5][];int[] firstServed=new int[5];Arrays.fill(firstServed,-1);
        var visuals=resolver();Map<String,PaperSnowPackets.Context> worlds=Map.of("minecraft:overworld",hidden);
        try{
            for(int viewer=0;viewer<5;viewer++){
                var handler=new NativeSnowPresentation.ClimateHandler(new PaperBiomePackets(Map.of()),Logger.getLogger("shared-snow-tests"),POLICY,
                    ()->visuals,leaves,Map::of,snowPackets,()->worlds,ignored->{},budget);
                EmbeddedChannel channel=new EmbeddedChannel(handler);handlers.add(handler);channels.add(channel);
                channel.writeOutbound(biomeRegistry());readAll(channel);channel.writeOutbound(login("minecraft:overworld"));readAll(channel);
                channel.writeOutbound(chunk(sectionWire(planeSource(2),new int[64]),0,0));clients[viewer]=decode(channel.readOutbound());
                check(clients[viewer][index(0,2,0)]==air,"Initial full chunks follow coverage without using the extra refresh budget");
            }
            int start=0;
            for(int frame=1;frame<=30;frame++){
                budget.beginTick(frame);int following=start+1,admitted=0;
                for(int offset=0;offset<5;offset++){
                    int viewer=(start+offset)%5;
                    if((boolean)invoke(handlers.get(viewer),"requestSnow",PaperSnowPackets.Context.class,finished)){
                        admitted++;following=start+offset+1;if(firstServed[viewer]<0)firstServed[viewer]=frame;
                    }
                }
                int changes=0;
                for(int viewer=0;viewer<5;viewer++){
                    channels.get(viewer).runPendingTasks();List<Object> packets=readAll(channels.get(viewer));
                    changes+=updateCount(packets);apply(clients[viewer],packets,0,0,0);
                }
                check(admitted<=1&&changes<=100,"Connections share one block allowance, including queued work");
                start=following%5;
                boolean complete=true;for(var handler:handlers)complete&=!waiting(handler,finished,"minecraft:overworld");
                if(complete)break;
            }
            for(int viewer=0;viewer<5;viewer++){
                check(firstServed[viewer]>0&&firstServed[viewer]<=5,"Rotating after the last admitted viewer prevents starvation");
                check(!waiting(handlers.get(viewer),finished,"minecraft:overworld"),"Shared throttling eventually releases each storm reveal barrier");
                for(int c=0;c<256;c++)check(clients[viewer][index(c&15,2,c>>4)]==snow,"Every viewer converges to the physical snow");
            }
            var handler=handlers.getFirst();var channel=channels.getFirst();
            budget.beginTick(100);check((boolean)invoke(handler,"requestSnow",PaperSnowPackets.Context.class,hidden),"A fresh tick admits a queued reveal");
            budget.beginTick(101);channel.runPendingTasks();
            check(readAll(channel).isEmpty()&&waiting(handler,hidden,"minecraft:overworld"),"Expired work sends nothing and leaves the target pending");
            check((boolean)invoke(handler,"requestSnow",PaperSnowPackets.Context.class,hidden),"Expired work can retry within the new frame");
            channel.runPendingTasks();check(updateCount(readAll(channel))==100,"Retry respects the current frame allowance");

            var clean=handlers.get(1);var cleanChannel=channels.get(1);
            budget.beginTick(102);var held=budget.reserve(2,100);
            check(!(boolean)invoke(clean,"requestSnow",PaperSnowPackets.Context.class,hidden),"Denied admission keeps work queued without scheduling a packet task");
            held.close();check(!(boolean)invoke(clean,"requestSnow",PaperSnowPackets.Context.class,finished),"A newer satisfied target cancels an older denied request");
            cleanChannel.runPendingTasks();check(readAll(cleanChannel).isEmpty(),"Denied stale coverage never gets replayed later");

            for(int x=1;x<=2;x++){publish(owner,x,0,plane(2));cleanChannel.writeOutbound(chunk(sectionWire(planeSource(2),new int[64]),x,0));readAll(cleanChannel);}
            Method dirty=clean.getClass().getDeclaredMethod("dirtySnow",int.class,int.class);dirty.setAccessible(true);
            for(int x=0;x<=2;x++)dirty.invoke(clean,x,0);
            budget.beginTick(103);invoke(clean,"requestSnow",PaperSnowPackets.Context.class,finished);cleanChannel.runPendingTasks();
            var view=(PaperSnowPackets.View)field(clean,"snow");
            check(readAll(cleanChannel).isEmpty()&&view.lastRefreshChunks()==2&&view.lastRefreshBlocks()==0,"No-op scans consume the shared chunk allowance");
            check(!(boolean)invoke(clean,"requestSnow",PaperSnowPackets.Context.class,finished)&&waiting(clean,finished,"minecraft:overworld"),"Unused block capacity cannot bypass exhausted chunk capacity");
            budget.beginTick(104);invoke(clean,"requestSnow",PaperSnowPackets.Context.class,finished);cleanChannel.runPendingTasks();
            check(readAll(cleanChannel).isEmpty()&&view.lastRefreshChunks()==1&&!waiting(clean,finished,"minecraft:overworld"),"Routine rechecks resume and finish on the next tick");
            budget.limits(new dev.trueseasons.config.SnowPresentationLimits(1,1));
            budget.beginTick(105);invoke(clean,"requestSnow",PaperSnowPackets.Context.class,hidden);cleanChannel.runPendingTasks();
            check(updateCount(readAll(cleanChannel))==1&&view.lastRefreshChunks()==1,"Reloaded minimum limits constrain actual outgoing block updates");
            budget.limits(new dev.trueseasons.config.SnowPresentationLimits(2,100));
            for(int frame=106;frame<120&&view.pending();frame++){
                budget.beginTick(frame);invoke(clean,"requestSnow",PaperSnowPackets.Context.class,hidden);cleanChannel.runPendingTasks();
                check(updateCount(readAll(cleanChannel))<=100,"Raised limits preserve the shared outgoing block bound");
            }
            check(!view.pending()&&view.hiddenColumns()==768&&hidden.equals(field(clean,"snowApplied")),"Pending snow converges after limits change without resetting viewer state");
        }finally{for(var channel:channels)channel.finishAndReleaseAll();}
    }
    private static void snowmelt()throws Exception{
        var owner=owners();publish(owner,0,0,plane(2));
        var initial=new PaperSnowPackets.Context(VERSION,64,0,16,owner);
        Map<String,PaperSnowPackets.Context> worlds=new HashMap<>(Map.of("minecraft:overworld",initial));
        var handler=handler(resolver(),Map.of(),worlds);EmbeddedChannel channel=new EmbeddedChannel(handler);
        Method waiting=handler.getClass().getDeclaredMethod("snowRevealPending",PaperSnowPackets.Context.class,String.class,boolean.class);waiting.setAccessible(true);
        try{
            channel.writeOutbound(biomeRegistry());readAll(channel);handler.setPresentation(false,Season.SPRING,POLICY);
            channel.writeOutbound(login("minecraft:overworld"));readAll(channel);
            channel.writeOutbound(chunk(sectionWire(planeSource(2),new int[64]),0,0));int[] client=decode(channel.readOutbound());
            int shown=64;
            for(int coverage:new int[]{48,32,16,0}){
                var target=new PaperSnowPackets.Context(VERSION,coverage,0,16,owner);worlds.put("minecraft:overworld",target);
                int guard=0;
                do{check(guard++<20,"Snowmelt pumps finish within bounded batches");apply(client,pumpSnow(handler,channel,target),0,0,0);}
                while((boolean)waiting.invoke(handler,target,"minecraft:overworld",false));
                int now=0;for(int c=0;c<256;c++){
                    boolean snowing=client[index(c&15,2,c>>4)]==snow;
                    check(snowing==(rank(c,0,0)<coverage),"Melting preserves winter's exact scatter order without showing new columns");if(snowing)now++;
                }
                check(now<=shown&&now==coverage,"Client snow decreases monotonically to the shared target");shown=now;
            }
            var target=worlds.get("minecraft:overworld");
            check(((PaperSnowPackets.View)field(handler,"snow")).hiddenColumns()==256,"Physical winter snow is hidden before restoration starts");
            check(!(boolean)waiting.invoke(handler,target,"minecraft:overworld",false),"Hidden snow at zero coverage releases the melt barrier instead of deadlocking migration");
            channel.writeOutbound(chunk(sectionWire(planeSource(2),new int[64]),0,0));
            int[] resent=decode(channel.readOutbound());for(int c=0;c<256;c++)check(resent[index(c&15,2,c>>4)]==air,"A chunk resend during melt remains at the current coverage");
            channel.writeOutbound(batch(0,0,0,planeSourceUpdates(2,air)));readAll(channel);publish(owner,0,0,Map.of());
            check(pumpSnow(handler,channel,target).isEmpty(),"Physical cleanup does not resurrect already hidden snow");
            channel.writeOutbound(single(0,2,0,snow));Object natural=channel.readOutbound();
            check(id(call(natural,"getBlockState"))==snow,"Unowned natural snow keeps its actual appearance after melting");
        }finally{channel.finishAndReleaseAll();}
    }
    private static Map<Integer,Integer> planeSourceUpdates(int y,int state){Map<Integer,Integer> result=new HashMap<>();for(int c=0;c<256;c++)result.put(index(c&15,y,c>>4),state);return result;}
    private static void pumpLeaves(Object handler,EmbeddedChannel channel,PaperLeafPackets.Context next,int[] client,int x,int section,int z)throws Exception{
        int guard=0;do{check(guard++<100,"Leaf handler refresh makes bounded progress");invoke(handler,"requestLeaves",PaperLeafPackets.Context.class,next);channel.runPendingTasks();apply(client,readAll(channel),x,section,z);}while((boolean)field(handler,"leafPending"));
    }
    private static int updateCount(List<Object> packets)throws Exception{int result=0;for(Object packet:packets)if(packet.getClass().getSimpleName().equals("ClientboundSectionBlocksUpdatePacket"))result+=((short[])field(packet,"positions")).length;return result;}
    private static List<Object> readAll(EmbeddedChannel channel){List<Object> result=new ArrayList<>();Object packet;while((packet=channel.readOutbound())!=null)result.add(packet);return result;}
    private static void apply(int[] client,List<Object> packets,int x,int y,int z)throws Exception{
        for(Object packet:packets){if(!packet.getClass().getSimpleName().equals("ClientboundSectionBlocksUpdatePacket"))continue;Object section=field(packet,"sectionPos");if((int)call(section,"x")!=x||(int)call(section,"y")!=y||(int)call(section,"z")!=z)continue;
            short[] at=(short[])field(packet,"positions");Object[] states=(Object[])field(packet,"states");for(int i=0;i<at.length;i++){int p=at[i]&0xffff;client[index((p>>8)&15,p&15,(p>>4)&15)]=id(states[i]);}
        }
    }
    private static Object biomeRegistry()throws Exception{
        Class<?> compound=type("net.minecraft.nbt.CompoundTag"),identifier=type("net.minecraft.resources.Identifier"),key=type("net.minecraft.resources.ResourceKey");Object tag=compound.getConstructor().newInstance(),effects=compound.getConstructor().newInstance();
        compound.getMethod("putFloat",String.class,float.class).invoke(tag,"temperature",.7f);compound.getMethod("putFloat",String.class,float.class).invoke(tag,"downfall",.5f);compound.getMethod("putBoolean",String.class,boolean.class).invoke(tag,"has_precipitation",true);
        compound.getMethod("putInt",String.class,int.class).invoke(effects,"water_color",4159204);compound.getMethod("put",String.class,type("net.minecraft.nbt.Tag")).invoke(tag,"effects",effects);
        List<Object> entries=new ArrayList<>();for(String biome:List.of("plains","desert","forest"))entries.add(type("net.minecraft.core.RegistrySynchronization$PackedRegistryEntry").getConstructor(identifier,Optional.class).newInstance(identifier.getMethod("parse",String.class).invoke(null,"minecraft:"+biome),Optional.of(tag)));
        Object registry=key.getMethod("createRegistryKey",identifier).invoke(null,identifier.getMethod("parse",String.class).invoke(null,"minecraft:worldgen/biome"));return type("net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket").getConstructor(key,List.class).newInstance(registry,entries);
    }
    private static Object spawn(String dimension)throws Exception{
        Class<?> identifier=type("net.minecraft.resources.Identifier"),key=type("net.minecraft.resources.ResourceKey");Object registry=key.getMethod("createRegistryKey",identifier).invoke(null,identifier.getMethod("parse",String.class).invoke(null,"minecraft:dimension"));
        Object world=key.getMethod("create",key,identifier).invoke(null,registry,identifier.getMethod("parse",String.class).invoke(null,dimension));Class<?> gameType=type("net.minecraft.world.level.GameType");Object survival=Arrays.stream(gameType.getEnumConstants()).filter(value->((Enum<?>)value).name().equals("SURVIVAL")).findFirst().orElseThrow();
        return type("net.minecraft.network.protocol.game.CommonPlayerSpawnInfo").getConstructor(type("net.minecraft.core.Holder"),key,long.class,gameType,Optional.class,boolean.class,boolean.class,Optional.class,int.class,int.class).newInstance(null,world,999L,survival,Optional.empty(),false,false,Optional.empty(),0,63);
    }
    private static Object login(String dimension)throws Exception{Object spawn=spawn(dimension);return type("net.minecraft.network.protocol.game.ClientboundLoginPacket").getConstructor(int.class,boolean.class,Set.class,int.class,int.class,int.class,boolean.class,boolean.class,boolean.class,spawn.getClass(),boolean.class,boolean.class).newInstance(1,false,Set.of(call(spawn,"dimension")),10,8,8,false,true,false,spawn,false,false);}
    private static SnowOwnershipSnapshots.WorldView owners()throws Exception{Constructor<?> constructor=SnowOwnershipSnapshots.WorldView.class.getDeclaredConstructor();constructor.setAccessible(true);return (SnowOwnershipSnapshots.WorldView)constructor.newInstance();}
    private static void publish(SnowOwnershipSnapshots.WorldView view,int x,int z,Map<Integer,Integer> owned)throws Exception{Method method=view.getClass().getDeclaredMethod("publish",int.class,int.class,SnowOwnershipSnapshots.Snapshot.class);method.setAccessible(true);method.invoke(view,x,z,new SnowOwnershipSnapshots.Snapshot(owned));}
    private static Map<Integer,Integer> plane(int y){Map<Integer,Integer> result=new HashMap<>();for(int c=0;c<256;c++)result.put(c,y);return result;}
    private static int[] planeSource(int y){int[] result=new int[4096];Arrays.fill(result,air);for(int c=0;c<256;c++)result[index(c&15,y,c>>4)]=snow;return result;}
    private static int rank(int column,int x,int z){return new dev.trueseasons.snow.SnowColumnOrder(x,z,VERSION).rank(column);}
    private static int index(int x,int y,int z){return (y<<8)|(z<<4)|x;}
    private static int biomeIndex(int x,int y,int z){return ((y>>2)<<4)|((z>>2)<<2)|(x>>2);}
    private static byte[] biomeWire(int[] biomes){ByteBuf out=Unpooled.buffer();try{BiomeWireCodec.writeBiomes(out,biomes,3,false,3);return bytes(out);}finally{out.release();}}
    private static byte[] sectionWire(int[] states,int[] biomes){ByteBuf out=Unpooled.buffer();try{int count=0;for(int id:states)if(id!=air)count++;out.writeShort(count).writeShort(0);LeafWireCodec.writePalette(out,states,registry.states.length);out.writeBytes(biomeWire(biomes));return bytes(out);}finally{out.release();}}
    private static int[] decode(Object packet)throws Exception{return LeafWireCodec.read(buffer(call(packet,"chunkData")),id->true).getFirst().states();}
    private static Object chunk(byte[] bytes,int x,int z)throws Exception{Constructor<?> constructor=type("net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData").getDeclaredConstructor(Map.class,byte[].class,List.class);constructor.setAccessible(true);Object data=constructor.newInstance(Map.of(),bytes,List.of());return type("net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket").getConstructor(int.class,int.class,data.getClass(),type("net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData")).newInstance(x,z,data,null);}
    private static Object biomesPacket(int[] biomes,int x,int z)throws Exception{Object pos=type("net.minecraft.world.level.ChunkPos").getConstructor(int.class,int.class).newInstance(x,z);Object row=type("net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket$ChunkBiomeData").getConstructor(pos.getClass(),byte[].class).newInstance(pos,biomeWire(biomes));return type("net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket").getConstructor(List.class).newInstance(List.of(row));}
    private static Object single(int x,int y,int z,int state)throws Exception{Class<?> pos=type("net.minecraft.core.BlockPos");return type("net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket").getConstructor(pos,type("net.minecraft.world.level.block.state.BlockState")).newInstance(pos.getConstructor(int.class,int.class,int.class).newInstance(x,y,z),registry.states[state]);}
    private static Object batch(int x,int y,int z,Map<Integer,Integer> values)throws Exception{Short2ObjectOpenHashMap<Object> states=new Short2ObjectOpenHashMap<>();for(var row:values.entrySet()){int p=row.getKey();states.put((short)(((p&15)<<8)|(((p>>4)&15)<<4)|((p>>8)&15)),registry.states[row.getValue()]);}Class<?> section=type("net.minecraft.core.SectionPos");return type("net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket").getConstructor(section,Short2ObjectMap.class).newInstance(section.getMethod("of",int.class,int.class,int.class).invoke(null,x,y,z),states);}
    private static int defaultId(String name)throws Exception{return id(call(type("net.minecraft.world.level.block.Blocks").getField(name).get(null),"defaultBlockState"));}
    private static int id(Object state)throws Exception{return (int)nativeIds.getClass().getMethod("getId",Object.class).invoke(nativeIds,state);}
    private static byte[] bytes(ByteBuf out){byte[] result=new byte[out.readableBytes()];out.getBytes(0,result);return result;}
    private static byte[] buffer(Object data)throws Exception{return (byte[])field(data,"buffer");}
    private static Object field(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static Object invoke(Object object,String name)throws Exception{Method method=object.getClass().getDeclaredMethod(name);method.setAccessible(true);return method.invoke(object);}
    private static Object invoke(Object object,String name,Class<?> parameter,Object value)throws Exception{Method method=object.getClass().getDeclaredMethod(name,parameter);method.setAccessible(true);return method.invoke(object,value);}
    private static Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name);}
    private static Object call(Object object,String name)throws ReflectiveOperationException{return PaperBiomePackets.call(object,name);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks++;}
}
