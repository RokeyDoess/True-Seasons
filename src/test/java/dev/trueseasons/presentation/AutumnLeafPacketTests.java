package dev.trueseasons.presentation;

import dev.trueseasons.visual.AutumnTreeColors;
import dev.trueseasons.visual.SeasonVisualResolver;
import dev.trueseasons.api.Season;
import dev.trueseasons.climate.SeasonClimatePolicy;
import io.netty.buffer.*;
import io.netty.channel.embedded.EmbeddedChannel;
import it.unimi.dsi.fastutil.shorts.*;
import org.bukkit.configuration.file.YamlConfiguration;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.logging.Logger;

/** Actual native registries, block palettes and packets; no server/world startup or disk terrain. */
public final class AutumnLeafPacketTests {
    private static int assertions;
    private static PaperLeafPackets bridge;
    private static NativeLeafRegistry registry;
    private static Object nativeIds;
    private static int air, stone, birch, birchLog, spruce, cherry, oakLog;
    public static void main(String[] args) throws Exception {
        type("net.minecraft.SharedConstants").getMethod("tryDetectVersion").invoke(null);
        type("net.minecraft.server.Bootstrap").getMethod("bootStrap").invoke(null);
        bridge=new PaperLeafPackets();registry=bridge.registry;
        nativeIds=type("net.minecraft.world.level.block.Block").getField("BLOCK_STATE_REGISTRY").get(null);
        air=defaultId("AIR");stone=defaultId("STONE");birch=defaultId("BIRCH_LEAVES");
        birchLog=defaultId("BIRCH_LOG");spruce=defaultId("SPRUCE_LEAVES");cherry=defaultId("CHERRY_LEAVES");oakLog=defaultId("OAK_LOG");
        nativeVariants(); paletteShapes(); viewerLifecycle(); crossChunk(); batches(); verticalAndMetadata(); compositeHandler();
        System.out.println("Autumn leaf packet tests passed: "+assertions+" assertions");
        type("org.apache.logging.log4j.LogManager").getMethod("shutdown").invoke(null);
    }
    private static void nativeVariants() throws Exception {
        for(int id=0;id<registry.states.length;id++) {
            if(!registry.kind(id).leaves()) continue;
            Map<String,String> original=properties(registry.states[id]);
            for(var color:List.of(AutumnTreeColors.Appearance.YELLOW,AutumnTreeColors.Appearance.ORANGE,AutumnTreeColors.Appearance.RED)) {
                int painted=registry.painted(id,color);
                check(painted>=0&&painted<registry.states.length,"native variant belongs to registry");
                check(properties(registry.states[painted]).equals(original),"distance/persistent/waterlogged preserved "+id);
                Object block=call(registry.states[painted],"getBlock");
                check(call(block,"getDescriptionId").equals("block.minecraft."+color.blockKey().substring(10)),"native poplar color correct");
            }
            check(registry.painted(id,AutumnTreeColors.Appearance.ORIGINAL)==id,"original variant remains exact");
        }
        for(var color:AutumnTreeColors.Appearance.values()) {
            check(registry.painted(spruce,color)==spruce,"spruce never substituted");
            check(registry.painted(stone,color)==stone,"non-leaf never substituted");
        }
    }
    private static void paletteShapes() throws Exception {
        for(int shape:new int[]{0,4,8,32}) {
            int[] source=filled(air);
            if(shape==0)Arrays.fill(source,cherry);
            else {
                int distinct=shape==4?3:shape==8?200:300;
                List<Integer> filler=new ArrayList<>();
                for(int id=0;filler.size()<distinct-1;id++)if(!registry.relevant(id)&&id!=cherry)filler.add(id);
                for(int i=0;i<4096;i++) source[i]=i%distinct==0?cherry:filler.get((i%distinct)-1);
            }
            byte[] bytes=sectionWire(source,shape),copy=bytes.clone();
            Object sourceData=chunkData(bytes),sourcePacket=chunk(sourceData,0,0);
            PaperLeafPackets.View view=bridge.view();view.context(new PaperLeafPackets.Context(20,0,16,true));
            Object painted=view.chunk(sourcePacket);
            check(Arrays.equals(buffer(sourceData),copy),"source packet immutable palette "+shape);
            LeafWireCodec.Section section=LeafWireCodec.read(buffer(call(painted,"chunkData")),id->true).getFirst();
            check(section.blocks()==123&&section.fluids()==7,"section block/fluid counts preserved "+shape);
            check(Arrays.equals(section.biomes(),biomeWire()),"biome bytes unchanged palette "+shape);
            int[] nativeDecoded=nativeDecode(section.originalBlocks());
            for(int i=0;i<4096;i++) {
                int expected=source[i]==cherry?registry.painted(cherry,AutumnTreeColors.Appearance.RED):source[i];
                check(nativeDecoded[i]==expected,"native palette decodes exact block palette "+shape+" cell "+i);
            }
            view.context(new PaperLeafPackets.Context(20,0,16,false));
            int[] client=nativeDecoded.clone();apply(client,view.refresh(1),0,0);
            check(Arrays.equals(client,source),"leaving autumn restores all original IDs palette "+shape);
        }
        int[] irrelevant=filled(stone);byte[] wire=sectionWire(irrelevant,0);
        var sections=LeafWireCodec.read(wire,registry::relevant);
        check(sections.getFirst().states()==null,"irrelevant sections avoid 4096 decoded states");
        check(Arrays.equals(LeafWireCodec.write(sections,registry.states.length,(s,i,id)->id),wire),"irrelevant bytes retained exactly");
    }
    private static void viewerLifecycle() throws Exception {
        int[] source=filled(air);int custom=leafVariant(birch,3,true,true);
        for(int y=0;y<=12;y++)source[index(8,y,8)]=birchLog;
        for(int x=6;x<=10;x++)for(int z=6;z<=10;z++)for(int y=10;y<=14;y++)
            if(source[index(x,y,z)]==air)source[index(x,y,z)]=custom;
        source[index(2,12,2)]=spruce;
        PaperLeafPackets.View view=bridge.view();view.context(new PaperLeafPackets.Context(42,0,16,false));
        Object original=chunk(chunkData(sectionWire(source,4)),0,0);
        check(view.chunk(original)==original,"outside autumn packet remains original");
        check(view.retainedStates()>100,"tree originals retained outside autumn for later entry");
        int[] client=source.clone();drain(view,client,0,0);
        view.context(new PaperLeafPackets.Context(42,0,16,true));
        check(view.refresh(0).isEmpty()&&view.pending(),"refresh zero budget preserves queued work");
        apply(client,view.refresh(1),0,0);
        int color=client[index(9,12,8)];
        check(color!=custom,"cached birch colors upon autumn entry");
        for(int i=0;i<4096;i++)if(source[i]==custom)check(client[i]==color,"entire birch canopy consistent");
        check(properties(registry.states[color]).equals(properties(registry.states[custom])),"packet preserves all leaf variant properties");
        check(client[index(2,12,2)]==spruce,"packet retains spruce green");
        int broken=index(9,12,8);
        Object replacement=single(9,12,8,stone);
        check(view.update(replacement)==replacement,"player replacement forwarded unchanged");client[broken]=stone;
        drain(view,client,0,0);
        view.context(new PaperLeafPackets.Context(42,0,16,false));drain(view,client,0,0);
        check(client[broken]==stone,"cleanup never resurrects replaced leaf");
        for(int i=0;i<4096;i++)if(i!=broken)check(client[i]==source[i],"exit restores current originals exactly");
        Object inserted=view.update(single(3,10,3,cherry));
        check(id(call(inserted,"getBlockState"))==cherry,"outside autumn newly placed cherry stays original");
        view.context(new PaperLeafPackets.Context(42,0,16,true));drain(view,client,0,0);
        check(client[index(3,10,3)]==registry.painted(cherry,AutumnTreeColors.Appearance.RED),"placed cherry participates in next autumn");
        view.forget(0,0);check(view.retainedStates()==0,"unload releases original tree cache");
        check(view.refresh(10).isEmpty(),"forgotten chunk never refreshed");
        view.clear();check(!view.pending()&&view.retainedStates()==0,"clear releases queues and originals");
    }
    private static void crossChunk() throws Exception {
        int[] west=filled(air),east=filled(air);
        for(int y=0;y<=6;y++)west[index(15,y,8)]=birchLog;
        west[index(14,6,8)]=birch;east[index(0,6,8)]=birch;
        var view=bridge.view();view.context(new PaperLeafPackets.Context(123,0,16,true));
        Object first=view.chunk(chunk(chunkData(sectionWire(east,4)),1,0));
        int[] client=nativeDecode(LeafWireCodec.read(buffer(call(first,"chunkData")),id->true).getFirst().originalBlocks());
        check(client[index(0,6,8)]==birch,"unavailable neighboring trunk retains original appearance");
        view.chunk(chunk(chunkData(sectionWire(west,4)),0,0));drain(view,client,1,0);
        int colored=client[index(0,6,8)];check(colored!=birch,"neighbor arrival repairs already sent border canopy");
        view.forget(0,0);drain(view,client,1,0);
        check(client[index(0,6,8)]==birch,"neighbor unload restores unresolved border leaves");
        view.chunk(chunk(chunkData(sectionWire(west,4)),0,0));drain(view,client,1,0);
        check(client[index(0,6,8)]==colored,"neighbor reload retains stable tree color");
        view.context(new PaperLeafPackets.Context(124,0,16,false));
        check(view.retainedStates()==0,"world seed change clears originals from prior world");
    }
    private static void batches() throws Exception {
        var view=bridge.view();view.context(new PaperLeafPackets.Context(7,0,16,true));
        view.chunk(chunk(chunkData(sectionWire(filled(air),0)),0,0));view.refresh(20);
        Map<Integer,Integer> originals=new LinkedHashMap<>();
        originals.put(index(9,6,8),birch);
        for(int y=0;y<=6;y++) originals.put(index(8,y,8),birchLog);
        originals.put(index(2,6,2),cherry);originals.put(index(3,6,3),spruce);
        Object packet=batch(0,0,0,originals),painted=view.update(packet);
        int[] client=filled(air);apply(client,List.of(painted),0,0);
        check(client[index(9,6,8)]!=birch,"batch applies all logs before choosing leaf color");
        check(client[index(2,6,2)]==registry.painted(cherry,AutumnTreeColors.Appearance.RED),"batch cherry red");
        check(client[index(3,6,3)]==spruce,"batch spruce unchanged");
        for(int y=0;y<=6;y++)check(client[index(8,y,8)]==birchLog,"batch logs unchanged");
        Object originalRoundTrip=roundTrip(packet);int[] raw=filled(air);apply(raw,List.of(originalRoundTrip),0,0);
        check(raw[index(9,6,8)]==birch,"source batch not mutated");
        Object wire=roundTrip(painted);int[] decoded=filled(air);apply(decoded,List.of(wire),0,0);
        check(Arrays.equals(client,decoded),"native section update stream codec accepts painted batch");
        Object block=view.update(single(10,6,8,cherry));
        check(id(call(block,"getBlockState"))==registry.painted(cherry,AutumnTreeColors.Appearance.RED),"single cherry update immediately painted");
        Object unseen=single(100,6,100,cherry);
        check(view.update(unseen)==unseen,"uncached updates forwarded unchanged without introducing cache");
        Map<Integer,Integer> removedLogs=new LinkedHashMap<>();for(int y=0;y<=6;y++)removedLogs.put(index(8,y,8),air);
        Object removed=view.update(batch(0,0,0,removedLogs));apply(client,List.of(removed),0,0);drain(view,client,0,0);
        check(client[index(9,6,8)]==birch,"cutting trunk restores unresolved canopy to original leaf appearance");
        view.context(new PaperLeafPackets.Context(7,0,16,false));drain(view,client,0,0);
        check(client[index(9,6,8)]==birch,"batch original birch restored");
    }
    private static void verticalAndMetadata() throws Exception {
        int[] lower=filled(air),upper=filled(air);
        lower[index(3,8,3)]=cherry;
        for(int y=-13;y<=1;y++)(y<0?lower:upper)[index(8,y&15,8)]=birchLog;
        upper[index(9,1,8)]=birch;
        byte[] first=sectionWire(lower,4),second=sectionWire(upper,4);
        byte[] bytes=Arrays.copyOf(first,first.length+second.length);System.arraycopy(second,0,bytes,first.length,second.length);
        Object heightmap=Arrays.stream(type("net.minecraft.world.level.levelgen.Heightmap$Types").getEnumConstants())
                .filter(value->((Enum<?>)value).name().equals("WORLD_SURFACE")).findFirst().orElseThrow();
        Map<Object,long[]> maps=Map.of(heightmap,new long[]{123456L,7890L});
        Class<?> entity=type("net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData$BlockEntityInfo");
        Constructor<?> entityConstructor=entity.getDeclaredConstructor(byte.class,short.class,type("net.minecraft.world.level.block.entity.BlockEntityType"),Optional.class);entityConstructor.setAccessible(true);
        Object chestType=type("net.minecraft.world.level.block.entity.BlockEntityTypes").getField("CHEST").get(null);
        Object chest=entityConstructor.newInstance((byte)0x23,(short)-10,chestType,Optional.empty());
        List<Object> entities=List.of(chest);
        Constructor<?> dataConstructor=type("net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData").getDeclaredConstructor(Map.class,byte[].class,List.class);dataConstructor.setAccessible(true);
        Object data=dataConstructor.newInstance(maps,bytes,entities);
        List<Object> extras=List.of(single(2,-10,3,stone));Field extraField=data.getClass().getDeclaredField("extraPackets");extraField.setAccessible(true);extraField.set(data,extras);
        Class<?> lightType=type("net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData");
        BitSet sky=new BitSet();sky.set(1);Object light=lightType.getConstructor(BitSet.class,BitSet.class,BitSet.class,BitSet.class,List.class,List.class)
                .newInstance(sky,new BitSet(),new BitSet(),new BitSet(),List.of(new byte[2048]),List.of());
        Object packet=type("net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket").getConstructor(int.class,int.class,data.getClass(),lightType).newInstance(0,0,data,light);
        var view=bridge.view();view.context(new PaperLeafPackets.Context(6,-16,16,true));Object painted=view.chunk(packet);
        Object paintedData=call(painted,"chunkData");
        check(field(paintedData,"heightmaps")==maps,"heightmap payload shared unchanged");
        check(field(paintedData,"blockEntitiesData")==entities,"block entities retained unchanged");
        check(field(paintedData,"extraPackets")==extras,"extra antixray/blockentity packets retained unchanged");
        check(call(painted,"lightData")==light,"light payload retained unchanged");
        check((boolean)call(paintedData,"isReady"),"rewritten chunk ready for send");
        check(Arrays.equals(buffer(data),bytes),"multisection source bytes unchanged");
        var sections=LeafWireCodec.read(buffer(paintedData),id->true);
        check(sections.size()==2,"all vertical sections preserved");
        int[] coldLower=nativeDecode(sections.get(0).originalBlocks()),coldUpper=nativeDecode(sections.get(1).originalBlocks());
        check(coldLower[index(3,8,3)]==registry.painted(cherry,AutumnTreeColors.Appearance.RED),"negative-height cherry painted");
        check(coldUpper[index(9,1,8)]!=birch,"tree crossing section zero resolves shared anchor");
        Object updated=view.update(single(3,-8,3,stone));check(id(call(updated,"getBlockState"))==stone,"negative-height player replacement forwarded");
        view.context(new PaperLeafPackets.Context(6,-16,16,false));
        Map<Integer,int[]> client=new HashMap<>();client.put(-1,coldLower);client.put(0,coldUpper);client.get(-1)[index(3,8,3)]=stone;
        for(Object update:view.refresh(1)) {
            int section=(int)call(field(update,"sectionPos"),"y");check(section==-1||section==0,"negative section refresh stays in source height");
            short[] positions=(short[])field(update,"positions");Object[] states=(Object[])field(update,"states");
            for(int i=0;i<positions.length;i++){int p=positions[i]&0xffff;client.get(section)[index((p>>8)&15,p&15,(p>>4)&15)]=id(states[i]);}
        }
        lower[index(3,8,3)]=stone;
        check(Arrays.equals(client.get(-1),lower),"negative section restores originals without player replacement resurrection");
        check(Arrays.equals(client.get(0),upper),"positive section original restoration exact");
    }
    private static void compositeHandler() throws Exception {
        YamlConfiguration config=new YamlConfiguration();
        for(Season season:Season.values())config.set("palettes.temperate."+season.key(),List.of("83B957","D98637"));
        SeasonVisualResolver resolver=new SeasonVisualResolver(config);
        Map<String,PaperLeafPackets.Context> worlds=new HashMap<>();
        worlds.put("minecraft:overworld",new PaperLeafPackets.Context(42,0,16,true));
        worlds.put("trueseasons:other_world",new PaperLeafPackets.Context(77,-16,0,true));
        var handler=new NativeSnowPresentation.ClimateHandler(new PaperBiomePackets(Map.of()),Logger.getAnonymousLogger(),
                SeasonClimatePolicy.ALL,()->resolver,bridge,()->Map.copyOf(worlds));
        EmbeddedChannel channel=new EmbeddedChannel(handler);
        try {
            Object registryPacket=biomeRegistry(4);channel.writeOutbound(registryPacket);
            Object expanded=channel.readOutbound();
            check(((List<?>)call(expanded,"entries")).size()==28,"composite handler supplies seasonal biome registry");
            check(((List<?>)call(registryPacket,"entries")).size()==4,"source biome registry unchanged");
            handler.setPresentation(false,Season.AUTUMN,SeasonClimatePolicy.ALL);
            Object login=login("minecraft:overworld");channel.writeOutbound(login);
            check(channel.readOutbound()==login,"login forwarded while selecting original world context");
            int[] source=filled(air);for(int y=0;y<=6;y++)source[index(8,y,8)]=birchLog;source[index(9,6,8)]=birch;source[index(2,6,2)]=spruce;
            byte[] original=sectionWire(source,4);Object chunk=chunk(chunkData(original),0,0);channel.writeOutbound(chunk);
            Object painted=channel.readOutbound();var section=LeafWireCodec.read(buffer(call(painted,"chunkData")),id->true).getFirst();
            int[] client=nativeDecode(section.originalBlocks());
            check(client[index(9,6,8)]!=birch,"login world context colors first chunk before main-thread viewer update");
            check(client[index(2,6,2)]==spruce,"composite handler leaves spruce unchanged");
            check(client[index(9,6,8)]==registry.painted(birch,new AutumnTreeColors.Session(42,(x,y,z)->{
                if(x<0||x>=16||z<0||z>=16)return AutumnTreeColors.Kind.UNKNOWN;
                if(y<0||y>=16)return AutumnTreeColors.Kind.OTHER;return registry.kind(source[index(x,y,z)]);
            }).appearanceAt(9,6,8)),"supplier real world seed drives tree selection rather than hashed spawn seed");
            ByteBuf biomes=Unpooled.wrappedBuffer(section.biomes());try{
                int[] ids=BiomeWireCodec.readBiomes(biomes);for(int i=0;i<64;i++)check(ids[i]==16+i%3,"leaf painting and biome aliases compose cell "+i);
            }finally{biomes.release();}
            check(Arrays.equals(buffer(call(chunk,"chunkData")),original),"composite original chunk immutable");
            Object added=single(3,6,3,cherry);channel.writeOutbound(added);Object rewritten=channel.readOutbound();
            check(id(call(rewritten,"getBlockState"))==registry.painted(cherry,AutumnTreeColors.Appearance.RED),"composite handler paints single updates after registry delivery");
            check(id(call(added,"getBlockState"))==cherry,"composite source single unchanged");client[index(3,6,3)]=registry.painted(cherry,AutumnTreeColors.Appearance.RED);
            Map<Integer,Integer> batchStates=new LinkedHashMap<>();batchStates.put(index(10,6,8),birch);batchStates.put(index(4,6,4),cherry);
            Object batch=batch(0,0,0,batchStates);Object bundle=type("net.minecraft.network.protocol.game.ClientboundBundlePacket").getConstructor(Iterable.class).newInstance(List.of(batch,single(5,6,5,cherry)));
            channel.writeOutbound(bundle);Object mappedBundle=channel.readOutbound();
            List<Object> children=new ArrayList<>();for(Object child:(Iterable<?>)call(mappedBundle,"subPackets"))children.add(child);
            check(children.size()==2,"bundle child order and count preserved");apply(client,List.of(children.getFirst()),0,0);
            check(client[index(10,6,8)]==client[index(9,6,8)],"bundled birch update retains whole-tree color");
            check(client[index(4,6,4)]==registry.painted(cherry,AutumnTreeColors.Appearance.RED),"bundled batch cherry red");
            check(id(call(children.get(1),"getBlockState"))==registry.painted(cherry,AutumnTreeColors.Appearance.RED),"bundled single cherry red");
            client[index(5,6,5)]=registry.painted(cherry,AutumnTreeColors.Appearance.RED);
            Method request=handler.getClass().getDeclaredMethod("requestLeaves",PaperLeafPackets.Context.class);request.setAccessible(true);
            request.invoke(handler,new PaperLeafPackets.Context(42,0,16,false));channel.runPendingTasks();
            List<Object> restored=readAll(channel);apply(client,restored,0,0);
            check(client[index(9,6,8)]==birch&&client[index(3,6,3)]==cherry,"cleanup season request restores cached original leaf variants");
            request.invoke(handler,new PaperLeafPackets.Context(42,0,16,true));channel.runPendingTasks();apply(client,readAll(channel),0,0);
            check(client[index(9,6,8)]!=birch,"reenabling autumn restores appearances without full chunk load");
            Method retire=handler.getClass().getDeclaredMethod("retire");retire.setAccessible(true);retire.invoke(handler);
            List<Object> retired=readAll(channel);List<Object> blockRestores=new ArrayList<>();boolean biomeRestored=false;
            for(Object update:retired){if(update.getClass().getSimpleName().equals("ClientboundSectionBlocksUpdatePacket"))blockRestores.add(update);
                if(update.getClass().getSimpleName().equals("ClientboundChunksBiomesPacket")){
                    biomeRestored=true;Object row=((List<?>)call(update,"chunkBiomeData")).getFirst();
                    ByteBuf restoredBiomes=Unpooled.wrappedBuffer((byte[])call(row,"buffer"));
                    try {int[] ids=BiomeWireCodec.readBiomes(restoredBiomes);for(int i=0;i<64;i++)check(ids[i]==i%3,"retire restores exact original biome ID "+i);}
                    finally {restoredBiomes.release();}
                }}
            apply(client,blockRestores,0,0);
            check(client[index(9,6,8)]==birch&&client[index(5,6,5)]==cherry,"retire drains all original leaf appearances");
            check(biomeRestored,"retire restores original biome aliases too");
            Object after=single(3,6,3,cherry);channel.writeOutbound(after);check(channel.readOutbound()==after,"retired handler stops leaf painting");
        }finally{channel.finishAndReleaseAll();}
        // A respawn selects a different height and seed before its first chunks; old-world caches disappear.
        var respawnHandler=new NativeSnowPresentation.ClimateHandler(new PaperBiomePackets(Map.of()),Logger.getAnonymousLogger(),
                SeasonClimatePolicy.ALL,()->resolver,bridge,()->Map.copyOf(worlds));
        EmbeddedChannel respawnChannel=new EmbeddedChannel(respawnHandler);
        try {
            respawnChannel.writeOutbound(biomeRegistry(4));respawnChannel.readOutbound();
            respawnChannel.writeOutbound(login("minecraft:overworld"));respawnChannel.readOutbound();
            int[] normal=filled(air);normal[index(2,6,2)]=cherry;respawnChannel.writeOutbound(chunk(chunkData(sectionWire(normal,4)),0,0));respawnChannel.readOutbound();
            Object spawn=spawn("trueseasons:other_world");Object respawn=type("net.minecraft.network.protocol.game.ClientboundRespawnPacket").getConstructor(spawn.getClass(),byte.class).newInstance(spawn,(byte)0);
            respawnChannel.writeOutbound(respawn);check(respawnChannel.readOutbound()==respawn,"respawn passed through with new dimension context");
            int[] other=filled(air);for(int y=0;y<=6;y++)other[index(8,y,8)]=birchLog;other[index(9,6,8)]=birch;
            respawnChannel.writeOutbound(chunk(chunkData(sectionWire(other,4)),0,0));Object mapped=respawnChannel.readOutbound();
            int[] actual=nativeDecode(LeafWireCodec.read(buffer(call(mapped,"chunkData")),id->true).getFirst().originalBlocks());
            check(actual[index(9,6,8)]!=birch,"respawn negative-height context colors first new-world chunk");
            var expectedSession=new AutumnTreeColors.Session(77,(x,y,z)->{
                if(y< -16||y>=0)return AutumnTreeColors.Kind.OTHER;
                if(x<0||x>=16||z<0||z>=16)return AutumnTreeColors.Kind.UNKNOWN;
                return registry.kind(other[index(x,y+16,z)]);
            });
            check(actual[index(9,6,8)]==registry.painted(birch,expectedSession.appearanceAt(9,-10,8)),"respawn switches to new supplier world seed and height");
            Method request=respawnHandler.getClass().getDeclaredMethod("requestLeaves",PaperLeafPackets.Context.class);request.setAccessible(true);
            request.invoke(respawnHandler,new PaperLeafPackets.Context(77,-16,0,false));respawnChannel.runPendingTasks();
            for(Object update:readAll(respawnChannel))if(update.getClass().getSimpleName().equals("ClientboundSectionBlocksUpdatePacket"))
                check((int)call(field(update,"sectionPos"),"y")==-1,"respawn world height applied to restoration packets");
        }finally{respawnChannel.finishAndReleaseAll();}
    }
    private static List<Object> readAll(EmbeddedChannel channel){List<Object> result=new ArrayList<>();Object next;while((next=channel.readOutbound())!=null)result.add(next);return result;}
    private static Object spawn(String dimension)throws Exception {
        Class<?> identifier=type("net.minecraft.resources.Identifier"),key=type("net.minecraft.resources.ResourceKey");
        Object registry=key.getMethod("createRegistryKey",identifier).invoke(null,identifier.getMethod("parse",String.class).invoke(null,"minecraft:dimension"));
        Object world=key.getMethod("create",key,identifier).invoke(null,registry,identifier.getMethod("parse",String.class).invoke(null,dimension));
        Class<?> gameType=type("net.minecraft.world.level.GameType");Object survival=Arrays.stream(gameType.getEnumConstants()).filter(value->((Enum<?>)value).name().equals("SURVIVAL")).findFirst().orElseThrow();
        return type("net.minecraft.network.protocol.game.CommonPlayerSpawnInfo").getConstructor(type("net.minecraft.core.Holder"),key,long.class,gameType,Optional.class,boolean.class,boolean.class,Optional.class,int.class,int.class)
                .newInstance(null,world,999L,survival,Optional.empty(),false,false,Optional.empty(),0,63);
    }
    private static Object login(String dimension)throws Exception {
        Object spawn=spawn(dimension);Object world=call(spawn,"dimension");
        return type("net.minecraft.network.protocol.game.ClientboundLoginPacket").getConstructor(int.class,boolean.class,Set.class,int.class,int.class,int.class,boolean.class,boolean.class,boolean.class,spawn.getClass(),boolean.class,boolean.class)
                .newInstance(1,false,Set.of(world),10,8,8,false,true,false,spawn,false,false);
    }
    private static Object biomeRegistry(int count)throws Exception {
        Class<?> compound=type("net.minecraft.nbt.CompoundTag"),identifier=type("net.minecraft.resources.Identifier"),key=type("net.minecraft.resources.ResourceKey");
        Object tag=compound.getConstructor().newInstance(),effects=compound.getConstructor().newInstance();
        compound.getMethod("putFloat",String.class,float.class).invoke(tag,"temperature",.7f);
        compound.getMethod("putFloat",String.class,float.class).invoke(tag,"downfall",.5f);
        compound.getMethod("putBoolean",String.class,boolean.class).invoke(tag,"has_precipitation",true);
        compound.getMethod("putInt",String.class,int.class).invoke(effects,"water_color",4159204);
        compound.getMethod("put",String.class,type("net.minecraft.nbt.Tag")).invoke(tag,"effects",effects);
        List<Object> entries=new ArrayList<>();for(int i=0;i<count;i++)entries.add(type("net.minecraft.core.RegistrySynchronization$PackedRegistryEntry").getConstructor(identifier,Optional.class)
                .newInstance(identifier.getMethod("parse",String.class).invoke(null,"minecraft:test_"+i),Optional.of(tag)));
        Object registry=key.getMethod("createRegistryKey",identifier).invoke(null,identifier.getMethod("parse",String.class).invoke(null,"minecraft:worldgen/biome"));
        return type("net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket").getConstructor(key,List.class).newInstance(registry,entries);
    }
    private static void drain(PaperLeafPackets.View view,int[] client,int x,int z) throws Exception {
        int guard=0;while(view.pending()){check(guard++<100,"refresh drain bounded");apply(client,view.refresh(1),x,z);}
    }
    private static int[] filled(int id){int[] states=new int[4096];Arrays.fill(states,id);return states;}
    private static int index(int x,int y,int z){return (y<<8)|(z<<4)|x;}
    private static byte[] biomeWire(){ByteBuf out=Unpooled.buffer();try{int[] biomes=new int[64];for(int i=0;i<64;i++)biomes[i]=i%3;BiomeWireCodec.writeBiomes(out,biomes,4,false,4);return bytes(out);}finally{out.release();}}
    private static byte[] sectionWire(int[] states,int requestedBits){
        ByteBuf out=Unpooled.buffer();try{
            out.writeShort(123).writeShort(7);
            if(requestedBits==0){out.writeByte(0);varInt(out,states[0]);}
            else {
                int bits=requestedBits==32?32-Integer.numberOfLeadingZeros(registry.states.length-1):requestedBits;
                boolean direct=requestedBits==32;
                Map<Integer,Integer> palette=new LinkedHashMap<>();for(int state:states)palette.computeIfAbsent(state,key->palette.size());
                out.writeByte(bits);if(!direct){varInt(out,palette.size());for(int id:palette.keySet())varInt(out,id);}
                int per=64/bits;for(int base=0;base<4096;base+=per){long packed=0;for(int i=0;i<per&&base+i<4096;i++)packed|=(long)(direct?states[base+i]:palette.get(states[base+i]))<<(i*bits);out.writeLong(packed);}
            }
            out.writeBytes(biomeWire());return bytes(out);
        }finally{out.release();}
    }
    private static int[] nativeDecode(byte[] wire)throws Exception {
        Object strategy=type("net.minecraft.world.level.chunk.Strategy").getMethod("createForBlockStates",type("net.minecraft.core.IdMap")).invoke(null,nativeIds);
        Object container=type("net.minecraft.world.level.chunk.PalettedContainer").getConstructor(Object.class,type("net.minecraft.world.level.chunk.Strategy")).newInstance(registry.states[air],strategy);
        ByteBuf bytes=Unpooled.wrappedBuffer(wire);try{
            Object friendly=type("net.minecraft.network.FriendlyByteBuf").getConstructor(ByteBuf.class).newInstance(bytes);
            container.getClass().getMethod("read",friendly.getClass()).invoke(container,friendly);
            check(!bytes.isReadable(),"native block decoder consumes exact palette bytes");
            int[] ids=new int[4096];for(int i=0;i<4096;i++)ids[i]=id(container.getClass().getMethod("get",int.class).invoke(container,i));return ids;
        }finally{bytes.release();}
    }
    private static void apply(int[] client,List<Object> packets,int x,int z)throws Exception {
        for(Object packet:packets){Object section=field(packet,"sectionPos");if((int)call(section,"x")!=x||(int)call(section,"z")!=z)continue;
            check((int)call(section,"y")==0,"test fixture section coordinate preserved");
            short[] positions=(short[])field(packet,"positions");Object[] states=(Object[])field(packet,"states");
            for(int i=0;i<positions.length;i++){int p=positions[i]&0xffff;client[index((p>>8)&15,p&15,(p>>4)&15)]=id(states[i]);}
        }
    }
    private static Object batch(int x,int y,int z,Map<Integer,Integer> entries)throws Exception {
        Short2ObjectOpenHashMap<Object> states=new Short2ObjectOpenHashMap<>();
        for(var entry:entries.entrySet()){int p=entry.getKey();short packed=(short)(((p&15)<<8)|(((p>>4)&15)<<4)|((p>>8)&15));states.put(packed,registry.states[entry.getValue()]);}
        Class<?> section=type("net.minecraft.core.SectionPos");Object pos=section.getMethod("of",int.class,int.class,int.class).invoke(null,x,y,z);
        return type("net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket").getConstructor(section,Short2ObjectMap.class).newInstance(pos,states);
    }
    private static Object single(int x,int y,int z,int state)throws Exception {
        Class<?> pos=type("net.minecraft.core.BlockPos");return type("net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket").getConstructor(pos,type("net.minecraft.world.level.block.state.BlockState")).newInstance(pos.getConstructor(int.class,int.class,int.class).newInstance(x,y,z),registry.states[state]);
    }
    private static int leafVariant(int original,int distance,boolean persistent,boolean waterlogged)throws Exception {
        Object state=registry.states[original];Class<?> properties=type("net.minecraft.world.level.block.state.properties.BlockStateProperties");
        Method set=state.getClass().getMethod("setValue",type("net.minecraft.world.level.block.state.properties.Property"),Comparable.class);
        state=set.invoke(state,properties.getField("DISTANCE").get(null),distance);
        state=set.invoke(state,properties.getField("PERSISTENT").get(null),persistent);
        state=set.invoke(state,properties.getField("WATERLOGGED").get(null),waterlogged);return id(state);
    }
    private static Map<String,String> properties(Object state)throws Exception {
        Map<String,String> result=new TreeMap<>();try(Stream<?> values=(Stream<?>)call(state,"getValues")){
            for(Object entry:values.toList()){Object property=call(entry,"property");result.put((String)call(property,"getName"),call(entry,"value").toString());}
        }return result;
    }
    private static int defaultId(String name)throws Exception {Object block=type("net.minecraft.world.level.block.Blocks").getField(name).get(null);return id(call(block,"defaultBlockState"));}
    private static int id(Object state)throws Exception{return (int)nativeIds.getClass().getMethod("getId",Object.class).invoke(nativeIds,state);}
    private static Object chunkData(byte[] bytes)throws Exception {Constructor<?> constructor=type("net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData").getDeclaredConstructor(Map.class,byte[].class,List.class);constructor.setAccessible(true);return constructor.newInstance(Map.of(),bytes,List.of());}
    private static Object chunk(Object data,int x,int z)throws Exception{return type("net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket").getConstructor(int.class,int.class,data.getClass(),type("net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData")).newInstance(x,z,data,null);}
    private static byte[] buffer(Object data)throws Exception{return (byte[])field(data,"buffer");}
    private static Object field(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static Object roundTrip(Object packet)throws Exception {
        Object codec=packet.getClass().getField("STREAM_CODEC").get(null);ByteBuf raw=Unpooled.buffer();try{
            Object friendly=type("net.minecraft.network.FriendlyByteBuf").getConstructor(ByteBuf.class).newInstance(raw);Class<?> stream=type("net.minecraft.network.codec.StreamCodec");
            stream.getMethod("encode",Object.class,Object.class).invoke(codec,friendly,packet);return stream.getMethod("decode",Object.class).invoke(codec,friendly);
        }finally{raw.release();}
    }
    private static byte[] bytes(ByteBuf out){byte[] bytes=new byte[out.readableBytes()];out.getBytes(0,bytes);return bytes;}
    private static void varInt(ByteBuf out,int value){do{int b=value&127;value>>>=7;out.writeByte(value==0?b:b|128);}while(value!=0);}
    private static Object call(Object object,String name)throws Exception{return PaperBiomePackets.call(object,name);}
    private static Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name);}
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
