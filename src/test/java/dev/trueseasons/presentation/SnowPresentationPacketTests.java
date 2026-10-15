package dev.trueseasons.presentation;

import dev.trueseasons.snow.SnowOwnershipSnapshots;
import io.netty.buffer.*;
import it.unimi.dsi.fastutil.shorts.*;
import java.lang.reflect.*;
import java.util.*;

/** Native snow packet palettes and incremental updates, without loading worlds. */
public final class SnowPresentationPacketTests {
    private static int checks;
    private static PaperSnowPackets bridge;
    private static NativeLeafRegistry registry;
    private static Object nativeIds;
    private static int air,stone,snow,thick;
    private static final String VERSION="packet-winter";
    public static void main(String[] args)throws Exception{
        type("net.minecraft.SharedConstants").getMethod("tryDetectVersion").invoke(null);
        type("net.minecraft.server.Bootstrap").getMethod("bootStrap").invoke(null);
        PaperLeafPackets leaves=new PaperLeafPackets();registry=leaves.registry;bridge=new PaperSnowPackets(leaves);
        nativeIds=type("net.minecraft.world.level.block.Block").getField("BLOCK_STATE_REGISTRY").get(null);
        air=defaultId("AIR");stone=defaultId("STONE");snow=defaultId("SNOW");
        Object layers=type("net.minecraft.world.level.block.state.properties.BlockStateProperties").getField("LAYERS").get(null);
        thick=id(registry.states[snow].getClass().getMethod("setValue",type("net.minecraft.world.level.block.state.properties.Property"),Comparable.class)
            .invoke(registry.states[snow],layers,2));
        check(bridge.snowId==snow&&bridge.airId==air,"Native single-layer snow and air IDs are exact");
        Object craft=type("org.bukkit.craftbukkit.block.data.CraftBlockData").getMethod("createData",type("net.minecraft.world.level.block.state.BlockState")).invoke(null,registry.states[stone]);
        check(bridge.stateId((org.bukkit.block.data.BlockData)craft)==stone,"Read-only planning converts actual Paper BlockData to the exact native source ID");
        paletteShapes();emptySection();coverageAndBudgets();ownershipChanges();updates();negativeHeights();payloadPreservation();lifecycle();chunkLimit();heightAndReplacement();groundTextures();previews();priorityRefresh();meltPlants();
        System.out.println("Snow presentation packet tests passed: "+checks+" assertions");
        type("org.apache.logging.log4j.LogManager").getMethod("shutdown").invoke(null);
    }
    private static void paletteShapes()throws Exception{
        for(int shape:new int[]{0,4,8,32}){
            int[] source=filled(snow);
            if(shape!=0){
                int distinct=shape==4?3:shape==8?200:300;
                List<Integer> filler=new ArrayList<>();
                int[] groundMappings=(int[])field(bridge,"plainGround");
                for(int id=0;filler.size()<distinct-1;id++)if(id!=snow&&groundMappings[id]<0&&!(boolean)call(registry.states[id],"isAir"))filler.add(id);
                for(int i=0;i<4096;i++)source[i]=i%distinct==0?snow:filler.get(i%distinct-1);
                for(int c=0;c<256;c++)source[index(c&15,5,c>>4)]=snow;
            }
            var owner=owners();publish(owner,2,-3,plane(5));
            var view=bridge.view();view.context(context(0,0,owner));
            byte[] original=wire(source,shape);Object data=data(original);Object packet=chunk(data,2,-3);
            Object mapped=view.chunk(packet);var section=sections(mapped).getFirst();int[] shown=decode(section.originalBlocks());
            for(int i=0;i<4096;i++)check(shown[i]==((i>>8)==5?air:source[i]),"Only owned snow hidden shape "+shape+" cell "+i);
            check(section.blocks()==nonAir(source)-256,"Snow-to-air adjusts non-air section count shape "+shape);
            check(section.fluids()==7&&Arrays.equals(section.biomes(),biomes()),"Fluid counts and opaque biome bytes preserved shape "+shape);
            check(Arrays.equals(buffer(data),original),"Source full chunk immutable shape "+shape);
            check(view.retainedColumns()==256,"Only owned columns retained shape "+shape);
            view.context(context(256,0,owner));drain(view,shown,2,0,-3);
            check(Arrays.equals(shown,source),"Full winter reveals exact originals shape "+shape);
        }
    }
    private static void emptySection()throws Exception{
        int[] source=planeSource(6);var owner=owners();publish(owner,0,0,plane(6));
        var view=bridge.view();view.context(context(0,0,owner));
        Object mapped=view.chunk(chunk(data(wire(source,4)),0,0));var section=sections(mapped).getFirst();
        check(section.blocks()==0,"Hiding the only snow plane produces an empty section count");
        check(Arrays.stream(decode(section.originalBlocks())).allMatch(id->id==air),"Snow-only section palette becomes native singleton air");
        check(section.originalBlocks().length<10,"Hidden snow-only section uses compact singleton palette");
        check(section.fluids()==7,"Snow presentation does not rewrite unrelated fluid count");
    }
    private static void coverageAndBudgets()throws Exception{
        int x=-4,z=7;int[] source=planeSource(6);var owner=owners();publish(owner,x,z,plane(6));
        var view=bridge.view();view.context(context(0,0,owner));
        int[] client=decode(sections(view.chunk(chunk(data(wire(source,4)),x,z))).getFirst().originalBlocks());
        drain(view,client,x,0,z);
        for(int coverage:new int[]{1,17,64,128,255,256}){
            view.context(context(coverage,0,owner));
            check(view.refresh(0,128).isEmpty()&&view.pending(),"Zero chunk budget preserves pending reveals");
            check(view.refresh(1,0).isEmpty()&&view.pending(),"Zero block budget preserves pending reveals");
            List<Object> first=view.refresh(1,1);
            check(updates(first)<=1,"Refresh obeys strict per-viewer changed-block budget");apply(client,first,x,0,z);
            drain(view,client,x,0,z);
            int visible=0;
            for(int c=0;c<256;c++){
                int expected=within(c,x,z,coverage)?snow:air;
                check(client[index(c&15,6,c>>4)]==expected,"Shared inverse column permutation exact at coverage "+coverage);
                if(client[index(c&15,6,c>>4)]==snow)visible++;
            }
            check(visible==coverage,"Coverage reveals exact column ratio "+coverage);
            check(view.refresh(10,256).isEmpty()&&!view.pending(),"Unchanged coverage emits no duplicate updates");
        }
    }
    private static void ownershipChanges()throws Exception{
        int[] source=planeSource(4);source[index(1,4,0)]=thick;
        source[index(2,7,0)]=snow; // Unowned natural snow above a different column.
        var owner=owners();publish(owner,0,0,plane(4));
        var view=bridge.view();view.context(context(0,0,owner));
        int[] client=decode(sections(view.chunk(chunk(data(wire(source,4)),0,0))).getFirst().originalBlocks());
        check(client[index(1,4,0)]==thick,"Owned column with multi-layer snow remains visible");
        check(client[index(2,7,0)]==snow,"Natural snow at another height remains visible");
        drain(view,client,0,0,0);
        Map<Integer,Integer> changed=new HashMap<>(plane(4));changed.remove(0);publish(owner,0,0,changed);view.dirty(0,0);
        drain(view,client,0,0,0);
        check(client[index(0,4,0)]==snow,"Relinquished ownership restores latest original snow appearance");
        changed.put(0,4);publish(owner,0,0,changed);view.dirty(0,0);
        Object newlyOwned=view.update(single(0,4,0,snow));client[index(0,4,0)]=id(call(newlyOwned,"getBlockState"));
        drain(view,client,0,0,0);
        check(client[index(0,4,0)]==air,"New owned placement can hide again without reloading chunk");
        publish(owner,0,0,Map.of());view.dirty(0,0);drain(view,client,0,0,0);
        check(Arrays.equals(client,source),"Removing all ownership restores every hidden original");
    }
    private static void updates()throws Exception{
        int[] source=filled(air);var owner=owners();publish(owner,0,0,Map.of(0,5,1,5,2,5,3,5));
        var view=bridge.view();view.context(context(0,0,owner));view.chunk(chunk(data(wire(source,0)),0,0));view.refresh(20,256);
        Object first=single(0,5,0,snow),hidden=view.update(first);
        check(id(call(hidden,"getBlockState"))==air,"New owned single snow update hides immediately");
        check(id(call(first,"getBlockState"))==snow,"Original single update not mutated");
        int[] client=filled(air);
        Object repeated=view.update(single(0,5,0,snow));check(id(call(repeated,"getBlockState"))==air,"Repeated same real snow update stays hidden");
        Map<Integer,Integer> batchValues=new LinkedHashMap<>();batchValues.put(index(1,5,0),snow);batchValues.put(index(2,5,0),thick);batchValues.put(index(4,5,0),snow);
        Object batch=batch(0,0,0,batchValues),mapped=view.update(batch);apply(client,List.of(mapped),0,0,0);
        check(client[index(1,5,0)]==air&&client[index(2,5,0)]==thick&&client[index(4,5,0)]==snow,
            "Batch hides only one-layer owned states and preserves natural/thick snow");
        int[] unchanged=filled(air);apply(unchanged,List.of(roundTrip(batch)),0,0,0);
        check(unchanged[index(1,5,0)]==snow,"Original section update is immutable");
        int[] actual=filled(air);apply(actual,List.of(roundTrip(mapped)),0,0,0);
        check(Arrays.equals(actual,client),"Native section packet codec accepts rewritten batch");
        Object replacement=single(0,5,0,stone);check(view.update(replacement)==replacement,"Player stone replacement forwarded unchanged");client[index(0,5,0)]=stone;
        Object broken=single(1,5,0,air);check(view.update(broken)==broken,"Player snow break forwards air");client[index(1,5,0)]=air;
        Object added=view.update(single(3,5,0,snow));check(id(call(added,"getBlockState"))==air,"Later local storm candidate added to known chunk");
        view.context(context(256,0,owner));drain(view,client,0,0,0);
        check(client[index(0,5,0)]==stone&&client[index(1,5,0)]==air,"Coverage reveal never resurrects broken/replaced snow");
        check(client[index(3,5,0)]==snow&&client[index(2,5,0)]==thick,"Reveal preserves latest legitimate single and batch originals");
        Object unseen=single(64,5,64,snow);check(view.update(unseen)==unseen,"Updates outside player-sent chunks never create phantom chunk cache");
    }
    private static void negativeHeights()throws Exception{
        int[] lower=filled(air),upper=filled(air);lower[index(3,8,2)]=snow;upper[index(5,1,4)]=snow;
        byte[] first=wire(lower,4),second=wire(upper,4),bytes=Arrays.copyOf(first,first.length+second.length);
        System.arraycopy(second,0,bytes,first.length,second.length);
        var owner=owners();publish(owner,-2,3,Map.of(3|(2<<4),-8,5|(4<<4),1));
        var view=bridge.view();view.context(context(0,-16,owner));
        Object input=chunk(data(bytes),-2,3),mapped=view.chunk(input);var sections=sections(mapped);
        int[] clientLower=decode(sections.get(0).originalBlocks()),clientUpper=decode(sections.get(1).originalBlocks());
        check(clientLower[index(3,8,2)]==air&&clientUpper[index(5,1,4)]==air,"Negative minY and cross-section ownership mapped exactly");
        check(sections.get(0).blocks()==0&&sections.get(1).blocks()==0,"Each affected vertical section gets its own corrected count");
        check(Arrays.equals(buffer(call(input,"chunkData")),bytes),"Multisection source payload remains unchanged");
        Object stoneUpdate=view.update(single((-2<<4)|3,-8,(3<<4)|2,stone));check(id(call(stoneUpdate,"getBlockState"))==stone,"Negative-height replacement forwarded exactly");clientLower[index(3,8,2)]=stone;
        view.context(null);
        int guard=0;while(view.pending()){
            check(guard++<10,"Negative section restore bounded");List<Object> packets=view.refresh(1,256);
            apply(clientLower,packets,-2,-1,3);apply(clientUpper,packets,-2,0,3);
        }
        check(clientLower[index(3,8,2)]==stone&&clientUpper[index(5,1,4)]==snow,"Null context restores only surviving originals at exact section heights");
    }
    private static void lifecycle()throws Exception{
        var owner=owners();publish(owner,0,0,plane(4));var view=bridge.view();view.context(context(0,0,owner));
        int[] source=planeSource(4);int[] client=decode(sections(view.chunk(chunk(data(wire(source,4)),0,0))).getFirst().originalBlocks());
        view.context(null);drain(view,client,0,0,0);
        check(Arrays.equals(client,source)&&view.retainedColumns()==0,"Retirement restores real snow and releases candidate cache");
        Object unmodified=chunk(data(wire(source,4)),0,0);check(view.chunk(unmodified)==unmodified,"Disabled presentation forwards full chunks untouched");
        view.context(context(0,0,owner));view.chunk(unmodified);check(view.retainedColumns()==256,"Reenabled view accepts owned source again");
        view.forget(0,0);check(view.retainedColumns()==0,"Forget immediately releases candidate columns");
        view.context(context(256,0,owner));check(view.refresh(10,1000).isEmpty(),"Forgotten terrain never receives reveal packets");
        view.dirty(100,100);check(!view.pending(),"Dirty unseen chunk does not grow pending work");
        publish(owner,0,0,Map.of());var natural=bridge.view();natural.context(context(0,0,owner));
        Object naturalPacket=chunk(data(wire(source,4)),0,0);Object naturalMapped=natural.chunk(naturalPacket);
        check(Arrays.equals(buffer(call(naturalMapped,"chunkData")),buffer(call(naturalPacket,"chunkData")))&&natural.retainedColumns()==0,
            "Entirely natural snow remains byte-equivalent without candidate retention");
    }
    private static void payloadPreservation()throws Exception{
        Object heightmap=Arrays.stream(type("net.minecraft.world.level.levelgen.Heightmap$Types").getEnumConstants())
            .filter(value->((Enum<?>)value).name().equals("WORLD_SURFACE")).findFirst().orElseThrow();
        Map<Object,long[]> maps=Map.of(heightmap,new long[]{123456L,7890L});
        Class<?> entity=type("net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData$BlockEntityInfo");
        Constructor<?> entityConstructor=entity.getDeclaredConstructor(byte.class,short.class,type("net.minecraft.world.level.block.entity.BlockEntityType"),Optional.class);entityConstructor.setAccessible(true);
        Object chestType=type("net.minecraft.world.level.block.entity.BlockEntityTypes").getField("CHEST").get(null);
        Object chest=entityConstructor.newInstance((byte)0x23,(short)5,chestType,Optional.empty());List<Object> entities=List.of(chest);
        byte[] original=wire(planeSource(6),4);Constructor<?> constructor=type("net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData").getDeclaredConstructor(Map.class,byte[].class,List.class);constructor.setAccessible(true);
        Object data=constructor.newInstance(maps,original,entities);List<Object> extras=List.of(single(2,5,3,stone));
        Field extrasField=data.getClass().getDeclaredField("extraPackets");extrasField.setAccessible(true);extrasField.set(data,extras);
        Class<?> lightType=type("net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData");BitSet sky=new BitSet();sky.set(1);
        Object light=lightType.getConstructor(BitSet.class,BitSet.class,BitSet.class,BitSet.class,List.class,List.class)
            .newInstance(sky,new BitSet(),new BitSet(),new BitSet(),List.of(new byte[2048]),List.of());
        Object packet=type("net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket").getConstructor(int.class,int.class,data.getClass(),lightType).newInstance(0,0,data,light);
        var owner=owners();publish(owner,0,0,plane(6));var view=bridge.view();view.context(context(0,0,owner));
        Object mapped=view.chunk(packet),mappedData=call(mapped,"chunkData");
        check(field(mappedData,"heightmaps")==maps,"Server heightmap payload retained unchanged");
        check(field(mappedData,"blockEntitiesData")==entities,"Chest and other block entities retained unchanged");
        check(field(mappedData,"extraPackets")==extras,"Antixray/block entity extra packets retained unchanged");
        check(call(mapped,"lightData")==light,"Light masks and arrays retained unchanged");
        check((boolean)call(mappedData,"isReady"),"Rewritten snow chunk remains ready to send");
        check(Arrays.equals(buffer(data),original),"Payload preservation never changes source buffer");
    }
    private static void chunkLimit()throws Exception{
        var owner=owners();var view=bridge.view();view.context(context(0,0,owner));Object empty=data(wire(filled(air),0));
        for(int x=0;x<=8192;x++)view.chunk(chunk(empty,x,0));
        publish(owner,8192,0,Map.of(0,5));Object outside=single(8192<<4,5,0,snow);
        check(view.update(outside)==outside&&view.retainedColumns()==0,"Sent-chunk cache limit fails open instead of retaining unbounded terrain");
        view.forget(0,0);view.chunk(chunk(empty,8192,0));
        Object admitted=view.update(single(8192<<4,5,0,snow));
        check(id(call(admitted,"getBlockState"))==air,"Freeing old sent chunk permits newly visible terrain to participate again");
    }
    private static void priorityRefresh()throws Exception{
        var owner=owners();var view=bridge.view();view.context(context(0,0,owner));
        int[] source=filled(air);source[index(0,2,0)]=snow;
        Object data=data(wire(source,4));
        for(int x=0;x<6;x++){publish(owner,x,0,Map.of(0,2));view.chunk(chunk(data,x,0));}
        view.context(context(256,0,owner));
        for(int x=1;x<6;x++){view.prioritize(x,0);view.prioritize(x,0);view.dirty(x,0);}
        for(int turn=0;turn<4;turn++){
            List<Object> packets=view.refresh(1,1);
            int expected=turn<3?turn+1:0;
            check(packets.size()==1&&(int)call(field(packets.getFirst(),"sectionPos"),"x")==expected,
                "Fresh chunks lead while routine work receives every fourth turn");
            check(view.lastRefreshChunks()==1&&view.lastRefreshBlocks()==1,"Refresh accounts for visited chunks and actual changed blocks");
        }
        view.forget(4,0);
        List<Object> last=view.refresh(8,256);
        check(last.size()==1&&(int)call(field(last.getFirst(),"sectionPos"),"x")==5,"Forgetting removes priority work and promotion creates no duplicate entries");
        check(!view.pending()&&view.hiddenColumns()==0,"Priority and ordinary queues converge together");
        view.prioritize(5,0);view.dirty(5,0);
        check(view.refresh(1,1).isEmpty()&&view.lastRefreshChunks()==1&&view.lastRefreshBlocks()==0,"A duplicate clean refresh consumes a chunk visit without block changes");
        view.context(null);view.refresh(8,256);
        check(view.lastRefreshChunks()==5&&view.lastRefreshBlocks()==0&&!view.pending(),"Final restoration keeps accounting even when its cache is cleared");
    }
    private static void meltPlants()throws Exception{
        int lower=defaultId("TALL_GRASS"),flower=defaultId("DANDELION");
        Object half=type("net.minecraft.world.level.block.state.properties.BlockStateProperties").getField("DOUBLE_BLOCK_HALF").get(null);
        Class<?> halfType=type("net.minecraft.world.level.block.state.properties.DoubleBlockHalf");
        Object upperValue=Arrays.stream(halfType.getEnumConstants()).filter(value->value.toString().equalsIgnoreCase("upper")).findFirst().orElseThrow();
        int upper=id(registry.states[lower].getClass().getMethod("setValue",type("net.minecraft.world.level.block.state.properties.Property"),Comparable.class).invoke(registry.states[lower],half,upperValue));
        var owner=owners();Map<Integer,SnowOwnershipSnapshots.PreviewColumn> candidates=Map.of(
            0,new SnowOwnershipSnapshots.PreviewColumn(2,stone,lower,upper),1,new SnowOwnershipSnapshots.PreviewColumn(2,stone,flower));
        Method publish=owner.getClass().getDeclaredMethod("preview",int.class,int.class,SnowOwnershipSnapshots.Preview.class);publish.setAccessible(true);
        publish.invoke(owner,0,0,new SnowOwnershipSnapshots.Preview(VERSION,System.nanoTime(),candidates,true));
        int[] source=filled(air);source[index(0,1,0)]=stone;source[index(1,1,0)]=stone;source[index(0,2,0)]=lower;source[index(0,3,0)]=upper;source[index(1,2,0)]=flower;
        var view=bridge.view();view.context(previewContext(256,owner,60));Object original=chunk(data(wire(source,4)),0,0);
        int[] client=sections(view.chunk(original)).getFirst().states();
        check(client[index(0,2,0)]==snow&&client[index(0,3,0)]==air&&client[index(1,2,0)]==snow,"Melting retains snow over restored flowers and hides complete tall plants");
        check(Arrays.equals(sections(original).getFirst().states(),source),"Melt previews never rewrite original restored plant data");
        int[] sheltered=source.clone();sheltered[index(0,5,0)]=stone;var roof=bridge.view();roof.context(previewContext(256,owner,60));
        int[] roofClient=sections(roof.chunk(chunk(data(wire(sheltered,4)),0,0))).getFirst().states();
        check(roofClient[index(0,2,0)]==lower&&roofClient[index(0,3,0)]==upper,"A new roof rejects remaining snow without concealing its real plant");
        view.context(previewContext(0,owner,60));drain(view,client,0,0,0);
        check(Arrays.equals(client,source),"Zero remaining coverage restores both halves and flower states exactly");
        view.context(previewContext(256,owner,60));client=sections(view.chunk(original)).getFirst().states();
        Object replacement=single(0,2,0,stone);Object painted=view.update(replacement);client[index(0,2,0)]=id(call(painted,"getBlockState"));drain(view,client,0,0,0);
        check(client[index(0,2,0)]==stone&&client[index(0,3,0)]==upper,"Player replacement invalidates the full column without resurrecting its old lower plant");
        // A receipt is published before a physical snow-to-plant update reaches Netty.
        var handoff=bridge.view();var actual=owners();publish(actual,0,0,Map.of(0,2));handoff.context(previewContext(256,actual,60));
        int[] winter=filled(air);winter[index(0,1,0)]=stone;winter[index(0,2,0)]=snow;handoff.chunk(chunk(data(wire(winter,4)),0,0));
        publish.invoke(actual,0,0,new SnowOwnershipSnapshots.Preview(VERSION,System.nanoTime(),Map.of(0,candidates.get(0)),true));
        publish(actual,0,0,Map.of());
        check(id(call(handoff.update(single(0,2,0,lower)),"getBlockState"))==snow,"Restored lower plants do not flash through remaining snow");
        check(id(call(handoff.update(single(0,3,0,upper)),"getBlockState"))==air,"Restored upper plant packets remain hidden during handoff");
        handoff.context(previewContext(0,actual,60));int[] handoffClient=winter.clone();drain(handoff,handoffClient,0,0,0);
        check(handoffClient[index(0,2,0)]==lower&&handoffClient[index(0,3,0)]==upper,"Physical handoff retains the latest real restored source for final reveal");
        view.context(null);drain(view,client,0,0,0);check(view.previewColumns()==0,"Season exit retires all melt overrides");
    }
    private static void heightAndReplacement()throws Exception{
        var owner=owners();var view=bridge.view();view.context(new PaperSnowPackets.Context(VERSION,0,0,16,owner));
        Object empty=chunk(data(wire(filled(air),0)),0,0);view.chunk(empty);
        publish(owner,0,0,Map.of(0,16));Object above=single(0,16,0,snow);
        check(view.update(above)==above&&view.retainedColumns()==0,"Owner-free sent marker rejects updates above actual world height");
        Object highBatch=batch(0,1,0,Map.of(index(0,0,0),snow));check(view.update(highBatch)==highBatch,"Out-of-world upper section update remains untouched");
        publish(owner,0,0,Map.of(0,-1));Object below=single(0,-1,0,snow);
        check(view.update(below)==below&&view.retainedColumns()==0,"Below-minimum snow update cannot create a candidate");
        Object lowBatch=batch(0,-1,0,Map.of(index(0,15,0),snow));check(view.update(lowBatch)==lowBatch,"Out-of-world lower section update remains untouched");
        publish(owner,0,0,Map.of(0,5));Object valid=view.update(single(0,5,0,snow));
        check(id(call(valid,"getBlockState"))==air&&view.retainedColumns()==1,"Same marker accepts a legitimate owned height after invalid updates");
        view.context(null);Object replacement=chunk(data(wire(filled(stone),0)),0,0);
        check(view.chunk(replacement)==replacement,"Full chunk replacement during restoration passes newest original payload through");
        check(view.refresh(100,1000).isEmpty()&&view.retainedColumns()==0,"Pending restoration never resurrects stale snow after full chunk replacement");
    }
    private static void preview(SnowOwnershipSnapshots.WorldView owner,int x,int z,String version,int y,int support)throws Exception{
        Map<Integer,SnowOwnershipSnapshots.PreviewColumn> columns=new HashMap<>();
        for(int column=0;column<256;column++)columns.put(column,new SnowOwnershipSnapshots.PreviewColumn(y,support,air));
        Method method=owner.getClass().getDeclaredMethod("preview",int.class,int.class,SnowOwnershipSnapshots.Preview.class);method.setAccessible(true);
        method.invoke(owner,x,z,new SnowOwnershipSnapshots.Preview(version,System.nanoTime(),columns));
    }
    private static PaperSnowPackets.Context previewContext(int coverage,SnowOwnershipSnapshots.WorldView owner,int delay){return new PaperSnowPackets.Context(VERSION,coverage,0,16,owner,true,delay);}
    private static void previews()throws Exception{
        int[] source=filled(air);for(int c=0;c<256;c++)source[index(c&15,4,c>>4)]=stone;
        var owner=owners();preview(owner,0,0,VERSION,5,stone);
        var view=bridge.view();view.context(previewContext(64,owner,60));
        Object original=chunk(data(wire(source,4)),0,0),painted=view.chunk(original);
        int[] client=decode(sections(painted).getFirst().originalBlocks());
        check(view.previewColumns()==64&&view.retainedColumns()==0,"Unfinished chunks show the exact target coverage without physical snow ownership");
        for(int c=0;c<256;c++)check(client[index(c&15,5,c>>4)]==(within(c,0,0,64)?snow:air),"Preview coverage follows the same monotonic shuffle as physical reveal");
        check(sections(painted).getFirst().blocks()==256+64,"Adding preview snow updates the native non-air section count");
        check(Arrays.equals(decode(sections(original).getFirst().originalBlocks()),source),"Preview snow never mutates the original packet terrain");
        view.context(previewContext(200,owner,60));drain(view,client,0,0,0);
        check(view.previewColumns()==200,"Coverage advances directly without additional terrain planning or physical placements");
        view.context(null);drain(view,client,0,0,0);
        check(Arrays.equals(client,source)&&view.previewColumns()==0,"Retirement removes every preview and restores original air");

        var late=bridge.view();var lateOwner=owners();late.context(previewContext(256,lateOwner,60));
        client=source.clone();check(late.chunk(original)==original,"Chunks can arrive before preview planning without inventing snow");
        preview(lateOwner,0,0,VERSION,5,stone);late.dirty(0,0);drain(late,client,0,0,0);
        check(late.previewColumns()==256,"Already-visible unfinished chunks catch up from newly published read-only candidates");
        Object replaced=late.update(single(0,5,0,stone));client[index(0,5,0)]=id(call(replaced,"getBlockState"));
        drain(late,client,0,0,0);
        check(client[index(0,5,0)]==stone&&late.previewColumns()==255,"Player replacements pass through and stale candidates cannot cover them again");
        Object roof=late.update(single(1,9,0,stone));client[index(1,9,0)]=id(call(roof,"getBlockState"));drain(late,client,0,0,0);
        check(client[index(1,5,0)]==air&&client[index(1,9,0)]==stone,"A new roof removes the preview below it without touching the roof");
        late.forget(0,0);check(!late.pending()&&late.previewColumns()==0,"Chunk forgetting releases every preview");

        var grace=bridge.view();var graceOwner=owners();preview(graceOwner,0,0,VERSION,5,stone);grace.context(previewContext(256,graceOwner,60));
        client=decode(sections(grace.chunk(original)).getFirst().originalBlocks());
        Class<?> action=type("net.minecraft.network.protocol.game.ServerboundPlayerActionPacket$Action");
        Object start=Arrays.stream(action.getEnumConstants()).filter(value->((Enum<?>)value).name().equals("START_DESTROY_BLOCK")).findFirst().orElseThrow();
        Class<?> direction=type("net.minecraft.core.Direction");Object up=Arrays.stream(direction.getEnumConstants()).filter(value->((Enum<?>)value).name().equals("UP")).findFirst().orElseThrow();
        Object position=type("net.minecraft.core.BlockPos").getConstructor(int.class,int.class,int.class).newInstance(0,5,0);
        Object digging=type("net.minecraft.network.protocol.game.ServerboundPlayerActionPacket").getConstructor(action,position.getClass(),direction).newInstance(start,position,up);
        Object correction=grace.dig(digging);client[index(0,5,0)]=id(call(correction,"getBlockState"));
        check(client[index(0,5,0)]==air&&grace.dig(digging)==null,"Native digging immediately corrects a preview once without waiting for a physical block break");
        preview(graceOwner,0,0,VERSION,5,stone);grace.dirty(0,0);drain(grace,client,0,0,0);
        check(client[index(0,5,0)]==air&&grace.previewColumns()==255,"Breaking preview snow keeps it absent even after a fresh valid terrain scan");
        grace.forget(0,0);client=decode(sections(grace.chunk(original)).getFirst().originalBlocks());
        check(client[index(0,5,0)]==air&&grace.previewColumns()==255,"Preview digging grace survives chunk forgetting and a full resend");
        grace.context(previewContext(256,graceOwner,0));drain(grace,client,0,0,0);
        check(client[index(0,5,0)]==snow,"Disabling the configured break delay releases preview-only grace too");
        publish(graceOwner,0,0,Map.of(0,5));Object real=grace.update(single(0,5,0,snow));client[index(0,5,0)]=id(call(real,"getBlockState"));
        check(client[index(0,5,0)]==snow&&grace.previewColumns()==255&&grace.retainedColumns()==1,"Real owned snow seamlessly replaces preview snow");
        grace.context(null);drain(grace,client,0,0,0);
        check(client[index(0,5,0)]==snow&&client[index(1,5,0)]==air,"Retirement retains physical snow while removing remaining previews");

        var raceOwner=owners();preview(raceOwner,0,0,VERSION,5,stone);var race=bridge.view();race.context(previewContext(256,raceOwner,60));race.chunk(original);race.dig(digging);
        publish(raceOwner,0,0,Map.of(0,5));Object physical=race.update(single(0,5,0,snow));
        check(id(call(physical,"getBlockState"))==air,"A physical placement racing with preview digging cannot bypass the viewer's grace");
        @SuppressWarnings("unchecked") Map<Long,long[]> graceRows=(Map<Long,long[]>)field(race,"breakGrace");
        graceRows.values().iterator().next()[0]=System.nanoTime()-1;
        Field expiry=race.getClass().getDeclaredField("nextGraceExpiry");expiry.setAccessible(true);expiry.setLong(race,System.nanoTime()-1);
        check(race.timedRefreshDue(),"Grace expiry requests a pump even when coverage and ownership are unchanged");
        List<Object> expired=race.refresh(100,1000);boolean revealed=false;
        for(Object packet:expired){short[] positions=(short[])field(packet,"positions");Object[] values=(Object[])field(packet,"states");for(int i=0;i<positions.length;i++)if((positions[i]&65535)==5&&id(values[i])==snow)revealed=true;}
        check(revealed&&!race.timedRefreshDue(),"Expiry reveals the retained physical source and clears the timed wakeup");

        var stale=bridge.view();var staleOwner=owners();preview(staleOwner,0,0,"previous-winter",5,stone);stale.context(previewContext(256,staleOwner,60));
        check(stale.chunk(original)==original&&stale.previewColumns()==0,"Previous-season preview snapshots are ignored");
        preview(staleOwner,0,0,VERSION,5,stone);int[] roofed=source.clone();roofed[index(0,8,0)]=stone;
        stale.chunk(chunk(data(wire(roofed,4)),0,0));check(stale.previewColumns()==255,"Full packets revalidate sky exposure against newer roofs");
        int[] unsupported=source.clone();unsupported[index(0,4,0)]=air;
        stale.chunk(chunk(data(wire(unsupported,4)),0,0));check(stale.previewColumns()==255,"Full packets revalidate saved preview supports");

        var grassOwner=owners();int plain=ground("GRASS_BLOCK",false);preview(grassOwner,0,0,VERSION,5,plain);
        int[] grassy=filled(air);for(int c=0;c<256;c++)grassy[index(c&15,4,c>>4)]=plain;
        var grassView=bridge.view();grassView.context(previewContext(256,grassOwner,60));
        client=decode(sections(grassView.chunk(chunk(data(wire(grassy,4)),0,0))).getFirst().originalBlocks());
        check(client[index(0,4,0)]==ground("GRASS_BLOCK",true),"Preview snow displays snowy support sides without saving ground changes");
        grassView.context(null);drain(grassView,client,0,0,0);check(Arrays.equals(client,grassy),"Preview support sides restore exactly on retirement");
    }
    private static PaperSnowPackets.Context context(int coverage,int minY,SnowOwnershipSnapshots.WorldView ownership){return new PaperSnowPackets.Context(VERSION,coverage,minY,ownership);}
    private static int ground(String name,boolean snowy)throws Exception{
        Object property=type("net.minecraft.world.level.block.state.properties.BlockStateProperties").getField("SNOWY").get(null);
        Object state=registry.states[defaultId(name)];
        return id(state.getClass().getMethod("setValue",type("net.minecraft.world.level.block.state.properties.Property"),Comparable.class).invoke(state,property,snowy));
    }
    private static void groundTextures()throws Exception{
        int[] source=filled(air);String[] names={"GRASS_BLOCK","PODZOL","MYCELIUM"};
        for(int x=0;x<3;x++){source[index(x,4,0)]=ground(names[x],true);source[index(x,5,0)]=snow;}
        source[index(3,4,0)]=ground("GRASS_BLOCK",true);source[index(3,5,0)]=snow;
        source[index(4,4,0)]=ground("PODZOL",true);source[index(4,5,0)]=thick;
        var owner=owners();publish(owner,0,0,Map.of(0,5,1,5,2,5,4,5));
        var view=bridge.view();view.context(context(0,0,owner));Object original=chunk(data(wire(source,4)),0,0);
        Object rewritten=view.chunk(original);int[] client=decode(sections(rewritten).getFirst().originalBlocks());
        for(int x=0;x<3;x++){
            check(client[index(x,5,0)]==air,"Owned snow layer hidden above "+names[x]);
            check(client[index(x,4,0)]==ground(names[x],false),"Hidden layer uses normal ground sides for "+names[x]);
        }
        check(client[index(3,4,0)]==source[index(3,4,0)]&&client[index(4,4,0)]==source[index(4,4,0)],"Natural and thick snow keep their original ground appearance");
        check(sections(rewritten).getFirst().blocks()==nonAir(source)-3,"Ground property changes never change section block count");
        check(Arrays.equals(decode(sections(original).getFirst().originalBlocks()),source),"Original ground states are immutable");
        check(view.hiddenColumns()==3&&view.retainedGroundStates()==5,"Hidden-layer and ground caches track source independently");
        view.context(context(256,0,owner));int guard=0;
        while(view.pending()){
            check(guard++<20,"Snow and support reveal make progress on a one-block budget");
            List<Object> packets=view.refresh(1,1);check(updates(packets)<=1,"Support updates share the one-block reveal budget");apply(client,packets,0,0,0);
        }
        check(Arrays.equals(client,source)&&view.hiddenColumns()==0,"Completion restores exact original snow and snowy ground properties");
        view.context(context(0,0,owner));drain(view,client,0,0,0);
        Object changed=view.update(single(0,4,0,stone));check(id(call(changed,"getBlockState"))==stone,"Player ground replacement passes through");client[index(0,4,0)]=stone;
        view.context(null);drain(view,client,0,0,0);source[index(0,4,0)]=stone;
        check(Arrays.equals(client,source)&&view.retainedGroundStates()==0,"Retirement cannot resurrect replaced ground and releases its cache");

        // A previously sent support is known before the migration claims its first layer.
        var fresh=bridge.view();var freshOwner=owners();fresh.context(context(0,0,freshOwner));
        int[] bare=filled(air);bare[index(0,4,0)]=ground("GRASS_BLOCK",true);
        fresh.chunk(chunk(data(wire(bare,4)),0,0));publish(freshOwner,0,0,Map.of(0,5));
        Object placed=fresh.update(single(0,5,0,snow));check(id(call(placed,"getBlockState"))==air,"First physical layer in an already-sent chunk remains hidden");
        drain(fresh,bare,0,0,0);check(bare[index(0,4,0)]==ground("GRASS_BLOCK",false),"First hidden placement also clears previously sent snowy ground sides");
        LinkedHashMap<Integer,Integer> batch=new LinkedHashMap<>();batch.put(index(0,4,0),ground("GRASS_BLOCK",true));batch.put(index(0,5,0),snow);
        Object mapped=fresh.update(batch(0,0,0,batch));apply(bare,List.of(mapped),0,0,0);
        check(bare[index(0,4,0)]==ground("GRASS_BLOCK",false)&&bare[index(0,5,0)]==air,"A support-first batch uses the final snow state");
        fresh.update(single(0,4,0,ground("GRASS_BLOCK",false)));fresh.context(null);
        List<Object> restored=fresh.refresh(100,1000);apply(bare,restored,0,0,0);
        check(bare[index(0,4,0)]==ground("GRASS_BLOCK",false),"Latest plain ground source is never restored to an old snowy state");

        // Section boundary and negative height keep support coordinates aligned.
        var vertical=bridge.view();var verticalOwner=owners();publish(verticalOwner,-2,3,Map.of(0,0));vertical.context(new PaperSnowPackets.Context(VERSION,0,-16,16,verticalOwner));
        int[] lower=filled(air),upper=filled(air);lower[index(0,15,0)]=ground("MYCELIUM",true);upper[index(0,0,0)]=snow;
        byte[] low=wire(lower,4),high=wire(upper,4),combined=new byte[low.length+high.length];System.arraycopy(low,0,combined,0,low.length);System.arraycopy(high,0,combined,low.length,high.length);
        Object across=vertical.chunk(chunk(data(combined),-2,3));List<LeafWireCodec.Section> rows=sections(across);
        check(decode(rows.getFirst().originalBlocks())[index(0,15,0)]==ground("MYCELIUM",false)&&decode(rows.getLast().originalBlocks())[0]==air,"Ground mask spans section zero at negative world heights");
        vertical.forget(-2,3);check(vertical.retainedGroundStates()==0&&vertical.hiddenColumns()==0,"Forget releases ground and hidden-layer caches together");
        var dense=bridge.view();var denseOwner=owners();dense.context(context(0,0,denseOwner));Object solid=chunk(data(wire(filled(ground("GRASS_BLOCK",true)),0)),0,0);
        check(dense.chunk(solid)==solid&&dense.retainedGroundStates()==512,"Dense custom terrain keeps natural textures and a bounded support cache");
        dense.context(context(256,0,denseOwner));check(!dense.pending(),"Unmodified sent terrain creates no coverage refresh backlog");
        dense.context(null);check(dense.refresh(1,1).isEmpty()&&dense.retainedGroundStates()==0,"Unmodified dense supports need no restoration traffic and release their cache");
    }
    private static SnowOwnershipSnapshots.WorldView owners()throws Exception{
        Constructor<?> constructor=SnowOwnershipSnapshots.WorldView.class.getDeclaredConstructor();constructor.setAccessible(true);
        return (SnowOwnershipSnapshots.WorldView)constructor.newInstance();
    }
    private static void publish(SnowOwnershipSnapshots.WorldView view,int x,int z,Map<Integer,Integer> owned)throws Exception{
        Method publish=view.getClass().getDeclaredMethod("publish",int.class,int.class,SnowOwnershipSnapshots.Snapshot.class);publish.setAccessible(true);
        publish.invoke(view,x,z,new SnowOwnershipSnapshots.Snapshot(owned));
    }
    private static Map<Integer,Integer> plane(int y){Map<Integer,Integer> result=new HashMap<>();for(int c=0;c<256;c++)result.put(c,y);return result;}
    private static int[] planeSource(int y){int[] result=filled(air);for(int c=0;c<256;c++)result[index(c&15,y,c>>4)]=snow;return result;}
    private static boolean within(int c,int x,int z,int coverage){return new dev.trueseasons.snow.SnowColumnOrder(x,z,VERSION).rank(c)<coverage;}
    private static void drain(PaperSnowPackets.View view,int[] client,int x,int section,int z)throws Exception{
        int guard=0;while(view.pending()){check(guard++<2000,"Pending reveals make bounded progress");List<Object> packets=view.refresh(1,13);check(updates(packets)<=13,"Every refresh obeys block budget");apply(client,packets,x,section,z);}
    }
    private static int updates(List<Object> packets)throws Exception{int count=0;for(Object packet:packets)count+=((short[])field(packet,"positions")).length;return count;}
    private static void apply(int[] client,List<Object> packets,int x,int y,int z)throws Exception{
        for(Object packet:packets){Object pos=field(packet,"sectionPos");if((int)call(pos,"x")!=x||(int)call(pos,"y")!=y||(int)call(pos,"z")!=z)continue;
            short[] at=(short[])field(packet,"positions");Object[] states=(Object[])field(packet,"states");
            for(int i=0;i<at.length;i++){int p=at[i]&0xffff;client[index((p>>8)&15,p&15,(p>>4)&15)]=id(states[i]);}
        }
    }
    private static List<LeafWireCodec.Section> sections(Object packet)throws Exception{return LeafWireCodec.read(buffer(call(packet,"chunkData")),id->true);}
    private static int[] filled(int value){int[] result=new int[4096];Arrays.fill(result,value);return result;}
    private static int index(int x,int y,int z){return (y<<8)|(z<<4)|x;}
    private static int nonAir(int[] states)throws Exception{int result=0;for(int state:states)if(!(boolean)call(registry.states[state],"isAir"))result++;return result;}
    private static byte[] biomes(){ByteBuf out=Unpooled.buffer();try{int[] ids=new int[64];for(int i=0;i<ids.length;i++)ids[i]=i%3;BiomeWireCodec.writeBiomes(out,ids,4,false,4);return bytes(out);}finally{out.release();}}
    private static byte[] wire(int[] states,int shape)throws Exception{
        ByteBuf out=Unpooled.buffer();try{
            out.writeShort(nonAir(states)).writeShort(7);
            if(shape==0){out.writeByte(0);varInt(out,states[0]);}
            else{
                int bits=shape==32?32-Integer.numberOfLeadingZeros(registry.states.length-1):shape;
                boolean direct=shape==32;Map<Integer,Integer> palette=new LinkedHashMap<>();for(int state:states)palette.computeIfAbsent(state,key->palette.size());
                out.writeByte(bits);if(!direct){varInt(out,palette.size());for(int state:palette.keySet())varInt(out,state);}
                int per=64/bits;for(int base=0;base<4096;base+=per){long packed=0;for(int i=0;i<per&&base+i<4096;i++)packed|=(long)(direct?states[base+i]:palette.get(states[base+i]))<<(i*bits);out.writeLong(packed);}
            }
            out.writeBytes(biomes());return bytes(out);
        }finally{out.release();}
    }
    private static int[] decode(byte[] wire)throws Exception{
        Object strategy=type("net.minecraft.world.level.chunk.Strategy").getMethod("createForBlockStates",type("net.minecraft.core.IdMap")).invoke(null,nativeIds);
        Object container=type("net.minecraft.world.level.chunk.PalettedContainer").getConstructor(Object.class,type("net.minecraft.world.level.chunk.Strategy")).newInstance(registry.states[air],strategy);
        ByteBuf bytes=Unpooled.wrappedBuffer(wire);try{
            Object friendly=type("net.minecraft.network.FriendlyByteBuf").getConstructor(ByteBuf.class).newInstance(bytes);
            container.getClass().getMethod("read",friendly.getClass()).invoke(container,friendly);
            check(!bytes.isReadable(),"Native palette decoder consumes exact rewritten payload");
            int[] result=new int[4096];for(int i=0;i<4096;i++)result[i]=id(container.getClass().getMethod("get",int.class).invoke(container,i));return result;
        }finally{bytes.release();}
    }
    private static Object roundTrip(Object packet)throws Exception{
        Object codec=packet.getClass().getField("STREAM_CODEC").get(null);ByteBuf bytes=Unpooled.buffer();
        try{Object friendly=type("net.minecraft.network.FriendlyByteBuf").getConstructor(ByteBuf.class).newInstance(bytes);
            Class<?> stream=type("net.minecraft.network.codec.StreamCodec");
            stream.getMethod("encode",Object.class,Object.class).invoke(codec,friendly,packet);
            return stream.getMethod("decode",Object.class).invoke(codec,friendly);
        }finally{bytes.release();}
    }
    private static Object batch(int x,int y,int z,Map<Integer,Integer> values)throws Exception{
        Short2ObjectOpenHashMap<Object> states=new Short2ObjectOpenHashMap<>();for(var row:values.entrySet()){int p=row.getKey();states.put((short)(((p&15)<<8)|(((p>>4)&15)<<4)|((p>>8)&15)),registry.states[row.getValue()]);}
        Class<?> section=type("net.minecraft.core.SectionPos");Object pos=section.getMethod("of",int.class,int.class,int.class).invoke(null,x,y,z);
        return type("net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket").getConstructor(section,Short2ObjectMap.class).newInstance(pos,states);
    }
    private static Object single(int x,int y,int z,int state)throws Exception{Class<?> pos=type("net.minecraft.core.BlockPos");return type("net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket").getConstructor(pos,type("net.minecraft.world.level.block.state.BlockState")).newInstance(pos.getConstructor(int.class,int.class,int.class).newInstance(x,y,z),registry.states[state]);}
    private static Object data(byte[] bytes)throws Exception{Constructor<?> constructor=type("net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData").getDeclaredConstructor(Map.class,byte[].class,List.class);constructor.setAccessible(true);return constructor.newInstance(Map.of(),bytes,List.of());}
    private static Object chunk(Object data,int x,int z)throws Exception{return type("net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket").getConstructor(int.class,int.class,data.getClass(),type("net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData")).newInstance(x,z,data,null);}
    private static int defaultId(String key)throws Exception{return id(call(type("net.minecraft.world.level.block.Blocks").getField(key).get(null),"defaultBlockState"));}
    private static int id(Object state)throws Exception{return (int)nativeIds.getClass().getMethod("getId",Object.class).invoke(nativeIds,state);}
    private static byte[] buffer(Object data)throws Exception{return (byte[])field(data,"buffer");}
    private static byte[] bytes(ByteBuf out){byte[] result=new byte[out.readableBytes()];out.getBytes(out.readerIndex(),result);return result;}
    private static void varInt(ByteBuf out,int value){do{int b=value&127;value>>>=7;out.writeByte(value==0?b:b|128);}while(value!=0);}
    private static Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name);}
    private static Object call(Object object,String method)throws ReflectiveOperationException{return PaperBiomePackets.call(object,method);}
    private static Object field(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks++;}
}
