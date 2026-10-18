package dev.trueseasons.presentation;

import dev.trueseasons.visual.AutumnTreeColors;
import dev.trueseasons.visual.SpringBlossoms;
import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.shorts.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.IntPredicate;
import io.netty.buffer.*;

/** Viewer-local originals and appearances. All terrain comes from outgoing packets. */
public final class PaperLeafPackets {
    private static final int MAX_TREE_STATES=2_000_000;
    private static final int MAX_MISSING_CHUNKS=64;
    final NativeLeafRegistry registry;
    private final Field buffer, heightmaps, entities, extras, sectionPos, positions, updateStates;
    private final Constructor<?> dataConstructor, chunkConstructor, blockConstructor, sectionConstructor;
    private final Class<?> blockPacket, sectionPacket;
    private final Method sectionOf;
    public PaperLeafPackets() throws ReflectiveOperationException {
        registry=new NativeLeafRegistry();
        Class<?> data=type("net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData"), chunk=type("net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket");
        buffer=field(data,"buffer");heightmaps=field(data,"heightmaps");entities=field(data,"blockEntitiesData");extras=field(data,"extraPackets");
        dataConstructor=data.getDeclaredConstructor(Map.class,byte[].class,List.class);dataConstructor.setAccessible(true);
        chunkConstructor=chunk.getConstructor(int.class,int.class,data,type("net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData"));
        blockPacket=type("net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket");
        blockConstructor=blockPacket.getConstructor(type("net.minecraft.core.BlockPos"),type("net.minecraft.world.level.block.state.BlockState"));
        sectionPacket=type("net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket");
        sectionPos=field(sectionPacket,"sectionPos");positions=field(sectionPacket,"positions");updateStates=field(sectionPacket,"states");
        Class<?> section=type("net.minecraft.core.SectionPos");sectionOf=section.getMethod("of",int.class,int.class,int.class);
        sectionConstructor=sectionPacket.getConstructor(section,Short2ObjectMap.class);
        for(int i=0;i<registry.states.length;i++)stateIds.put(registry.states[i],i);
    }
    private static final IntPredicate ALL_BIOMES=id->true;
    public record Context(long seed,int minY,int maxY,boolean autumn,IntPredicate autumnBiomeAllowed,int autumnCoverage,int springCoverage,boolean springFirst,SpringBlossoms blossoms) {
        public Context(long seed,int minY,int maxY,boolean autumn,IntPredicate allowed,int coverage,int spring,boolean first){this(seed,minY,maxY,autumn,allowed,coverage,spring,first,SpringBlossoms.DEFAULT);}
        public Context(long seed,int minY,int maxY,boolean autumn,int coverage,int spring,boolean first,SpringBlossoms blossoms){this(seed,minY,maxY,autumn,ALL_BIOMES,coverage,spring,first,blossoms);}
        public Context(long seed,int minY,int maxY,boolean autumn,IntPredicate allowed,int coverage){this(seed,minY,maxY,autumn,allowed,coverage,0,false);}
        public Context(long seed,int minY,int maxY,boolean autumn,IntPredicate allowed){this(seed,minY,maxY,autumn,allowed,256);}
        public Context(long seed,int minY,int maxY,boolean autumn,int coverage){this(seed,minY,maxY,autumn,ALL_BIOMES,coverage);}
        public Context(long seed,int minY,int maxY,boolean autumn,int coverage,int spring,boolean first){this(seed,minY,maxY,autumn,ALL_BIOMES,coverage,spring,first);}
        public Context(long seed,int minY,int maxY,boolean autumn){this(seed,minY,maxY,autumn,ALL_BIOMES);}
        public Context { Objects.requireNonNull(autumnBiomeAllowed);Objects.requireNonNull(blossoms);if(autumnCoverage<0||autumnCoverage>256||springCoverage<0||springCoverage>256)throw new IllegalArgumentException("Invalid tree coverage"); }
    }
    private static final class Chunk {
        final int x,z,minY,maxY;
        final Int2IntOpenHashMap original=new Int2IntOpenHashMap(), shown=new Int2IntOpenHashMap();
        final Set<Long> missing=new HashSet<>();
        boolean complete=true;
        int[][] biomes;
        byte[][] rawBiomes;
        Chunk(int x,int z,int minY,int maxY){this.x=x;this.z=z;this.minY=minY;this.maxY=maxY;original.defaultReturnValue(-1);shown.defaultReturnValue(-1);}
        int key(int x,int y,int z){return ((y-minY)<<8)|((z&15)<<4)|(x&15);}
    }
    public final class View {
        private final Map<Long,Chunk> chunks=new HashMap<>();
        private final Set<Long> pending=new LinkedHashSet<>(), repairs=new LinkedHashSet<>();
        private final Map<Long,Set<Long>> waiting=new HashMap<>();
        private final Set<Long> broadWaiters=new HashSet<>();
        private Chunk resolving;
        private Context context;
        private AutumnTreeColors.Session session;
        private boolean clearAfterRestore;
        private int storedStates;
        private Chunk refreshing;
        private IntIterator refreshStates;
        private int lastRefreshChunks,lastRefreshStates;
        public void context(Context next){
            if(next==null){
                if(context==null||clearAfterRestore)return;
                if(chunks.isEmpty()){context=null;return;}
                clearAfterRestore=true;
                context=new Context(context.seed,context.minY,context.maxY,false,context.autumnBiomeAllowed);invalidate();chunks.keySet().forEach(this::queue);
                return;
            }
            clearAfterRestore=false;
            if(Objects.equals(context,next))return;
            cancelRefresh();
            if(context!=null&&(context.seed!=next.seed||context.minY!=next.minY||context.maxY!=next.maxY))clear();
            if(context!=null&&!context.blossoms.equals(next.blossoms))invalidate();
            // A color-stage change does not change any original tree samples or anchors.
            context=next;chunks.keySet().forEach(this::queue);
        }
        public void clear(){refreshing=null;refreshStates=null;chunks.clear();pending.clear();repairs.clear();waiting.clear();broadWaiters.clear();storedStates=0;session=null;clearAfterRestore=false;}
        private void invalidate(){session=null;}
        private void cancelRefresh(){
            if(refreshing!=null){pending.add(PaperBiomePackets.key(refreshing.x,refreshing.z));refreshing=null;refreshStates=null;}
        }
        private void cancelRefresh(long key){if(refreshing!=null&&PaperBiomePackets.key(refreshing.x,refreshing.z)==key)cancelRefresh();}
        private void queue(long key){if(chunks.containsKey(key)&&!repairs.contains(key)){cancelRefresh(key);pending.add(key);}}
        private void repair(long key){if(chunks.containsKey(key)){cancelRefresh(key);pending.remove(key);repairs.add(key);}}
        private void missing(long key){
            if(resolving==null)return;
            if(resolving.missing.contains(key))return;
            long owner=PaperBiomePackets.key(resolving.x,resolving.z);
            if(resolving.missing.size()>=MAX_MISSING_CHUNKS){broadWaiters.add(owner);return;}
            if(resolving.missing.add(key))waiting.computeIfAbsent(key,k->new HashSet<>()).add(owner);
        }
        private void clearMissing(Chunk chunk){
            long owner=PaperBiomePackets.key(chunk.x,chunk.z);
            for(long key:chunk.missing){Set<Long> owners=waiting.get(key);if(owners!=null){owners.remove(owner);if(owners.isEmpty())waiting.remove(key);}}
            chunk.missing.clear();broadWaiters.remove(owner);
        }
        private void arrived(long key){
            Set<Long> owners=waiting.get(key);if(owners!=null)owners.forEach(this::repair);
            // Unusually large connected builds retain bounded dependency metadata.
            // Only those exceptional unresolved chunks retry on other arrivals.
            broadWaiters.forEach(this::repair);
        }
        private void nearby(int x,int z){for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)queue(PaperBiomePackets.key(x+dx,z+dz));}
        private AutumnTreeColors.Kind sample(int x,int y,int z){
            if(context==null)return AutumnTreeColors.Kind.UNKNOWN;
            if(y<context.minY||y>=context.maxY)return AutumnTreeColors.Kind.OTHER;
            Chunk chunk=chunks.get(PaperBiomePackets.key(x>>4,z>>4));
            if(chunk==null||!chunk.complete){missing(PaperBiomePackets.key(x>>4,z>>4));return AutumnTreeColors.Kind.UNKNOWN;}
            int state=chunk.original.get(chunk.key(x,y,z));return state<0?AutumnTreeColors.Kind.OTHER:registry.kind(state);
        }
        private boolean persistent(int x,int y,int z){
            Chunk chunk=chunks.get(PaperBiomePackets.key(x>>4,z>>4));
            if(chunk==null||y<chunk.minY||y>=chunk.maxY)return false;
            int state=chunk.original.get(chunk.key(x,y,z));return state>=0&&registry.persistent[state];
        }
        private int appearance(Chunk chunk,int key,int original){
            if(context==null||!context.autumn&&context.springCoverage==0||!chunk.complete||!registry.kind(original).leaves())return original;
            int section=key>>12,cell=((key>>6)&48)|((key>>4)&12)|((key>>2)&3);
            if(chunk.biomes==null||section>=chunk.biomes.length)return original;
            if(chunk.biomes[section]==null){ByteBuf biome=Unpooled.wrappedBuffer(chunk.rawBiomes[section]);
                try{chunk.biomes[section]=BiomeWireCodec.readBiomes(biome);}finally{biome.release();}}
            boolean autumn=context.autumn&&context.autumnBiomeAllowed.test(chunk.biomes[section][cell]);
            if(!autumn&&context.springCoverage==0)return original;
            // Decorative foliage has a fixed autumn appearance and never blossoms.
            // Its original state already tells us everything, without a tree session.
            if(registry.persistent[original]){
                AutumnTreeColors.Appearance color=!autumn||context.autumnCoverage<128?AutumnTreeColors.Appearance.ORIGINAL
                        :registry.kind(original).species()==AutumnTreeColors.Species.CHERRY?AutumnTreeColors.Appearance.RED:AutumnTreeColors.Appearance.ORANGE;
                return registry.painted(original,color);
            }
            if(session==null)session=new AutumnTreeColors.Session(context.seed,new AutumnTreeColors.Sampler(){
                public AutumnTreeColors.Kind kindAt(int x,int y,int z){return sample(x,y,z);}
                public boolean persistentAt(int x,int y,int z){return persistent(x,y,z);}
            },context.blossoms);
            AutumnTreeColors.Appearance color;
            resolving=chunk;
            try{
                color=AutumnTreeColors.Appearance.ORIGINAL;
                if(context.springFirst&&context.springCoverage>0)color=seasonAppearance(chunk,key,true);
                if(color==AutumnTreeColors.Appearance.ORIGINAL&&autumn)color=seasonAppearance(chunk,key,false);
                if(color==AutumnTreeColors.Appearance.ORIGINAL&&!context.springFirst&&context.springCoverage>0)color=seasonAppearance(chunk,key,true);
            }
            finally{resolving=null;}
            return registry.painted(original,color);
        }
        private AutumnTreeColors.Appearance seasonAppearance(Chunk chunk,int key,boolean spring){
            int x=(chunk.x<<4)|(key&15),y=chunk.minY+(key>>8),z=(chunk.z<<4)|((key>>4)&15);
            AutumnTreeColors.Appearance color=spring?session.springAppearanceAt(x,y,z,context.springCoverage):session.appearanceAt(x,y,z,context.autumnCoverage);
            if(session.incomplete()){
                AutumnTreeColors.Appearance known=registry.appearance(chunk.shown.get(key));
                if(spring?known==AutumnTreeColors.Appearance.BLOSSOM:known!=AutumnTreeColors.Appearance.ORIGINAL&&known!=AutumnTreeColors.Appearance.BLOSSOM)color=known;
            }
            return color;
        }
        private void shown(Chunk chunk,int key,int original,int painted){if(original==painted)chunk.shown.remove(key);else chunk.shown.put(key,painted);}
        public Object chunk(Object packet) throws ReflectiveOperationException {
            if(context==null)return packet;
            int x=(int)call(packet,"x"),z=(int)call(packet,"z");long key=PaperBiomePackets.key(x,z);
            Object old=call(packet,"chunkData");byte[] bytes=(byte[])buffer.get(old);
            List<LeafWireCodec.Section> sections=LeafWireCodec.read(bytes,registry::relevant);
            Chunk source=new Chunk(x,z,context.minY,context.minY+sections.size()*16);
            source.biomes=new int[sections.size()][];
            source.rawBiomes=new byte[sections.size()][];
            for(int section=0;section<sections.size();section++){
                source.rawBiomes[section]=sections.get(section).biomes();
                int[] states=sections.get(section).states();if(states==null)continue;
                for(int i=0;i<4096;i++)if(registry.relevant(states[i]))source.original.put((section<<12)|i,states[i]);
            }
            cancelRefresh(key);
            Chunk previous=chunks.remove(key);if(previous!=null){storedStates-=previous.original.size();clearMissing(previous);}
            if(storedStates+source.original.size()>MAX_TREE_STATES){invalidate();nearby(x,z);return packet;}
            chunks.put(key,source);storedStates+=source.original.size();
            // A new chunk only supplies previously UNKNOWN samples, which sessions
            // never cache. Replacements can change known originals and must reset.
            if(previous!=null)invalidate();
            arrived(key);
            if(previous!=null)nearby(x,z);
            if((context.autumn||context.springCoverage>0)&&previous!=null)for(var row:previous.shown.int2IntEntrySet()){
                int local=row.getIntKey(),before=previous.original.get(local),after=source.original.get(local);
                if(before>=0&&after>=0&&previous.minY==source.minY&&registry.kind(before).leaves()
                        &&registry.kind(before).species()==registry.kind(after).species())source.shown.put(local,row.getIntValue());
            }
            if(!context.autumn&&context.springCoverage==0)return packet;
            byte[] mapped=LeafWireCodec.write(sections,registry.states.length,(section,index,original)->{
                int local=(section<<12)|index,painted=appearance(source,local,original);shown(source,local,original,painted);return painted;
            });
            return copyChunk(packet,old,mapped);
        }
        /** Consume original biome updates before any client-only alias rewriting. */
        public void biomes(Object packet)throws ReflectiveOperationException{
            if(context==null)return;
            for(Object row:(List<?>)call(packet,"chunkBiomeData")){
                Object pos=call(row,"pos");int x=(int)call(pos,"x"),z=(int)call(pos,"z");
                Chunk chunk=chunks.get(PaperBiomePackets.key(x,z));if(chunk==null)continue;
                ByteBuf bytes=Unpooled.wrappedBuffer((byte[])call(row,"buffer"));
                try{List<byte[]> sections=new ArrayList<>();while(bytes.isReadable()){
                        int start=bytes.readerIndex();BiomeWireCodec.readBiomes(bytes);byte[] section=new byte[bytes.readerIndex()-start];bytes.getBytes(start,section);sections.add(section);}
                    if(sections.size()==chunk.biomes.length){chunk.rawBiomes=sections.toArray(byte[][]::new);chunk.biomes=new int[sections.size()][];queue(PaperBiomePackets.key(x,z));}
                }finally{bytes.release();}
            }
        }
        public Object update(Object packet) throws ReflectiveOperationException {
            if(context==null||(!blockPacket.isInstance(packet)&&!sectionPacket.isInstance(packet)))return packet;
            if(blockPacket.isInstance(packet)){
                Object pos=call(packet,"getPos"),state=call(packet,"getBlockState");
                int x=(int)call(pos,"getX"),y=(int)call(pos,"getY"),z=(int)call(pos,"getZ"),id=id(state);
                Chunk chunk=changed(x,y,z,id);if(chunk==null)return packet;
                int key=chunk.key(x,y,z),painted=appearance(chunk,key,id);shown(chunk,key,id,painted);
                return painted==id?packet:blockConstructor.newInstance(pos,registry.states[painted]);
            }
            Object section=sectionPos.get(packet);int sx=(int)call(section,"x"),sy=(int)call(section,"y"),sz=(int)call(section,"z");
            short[] at=(short[])positions.get(packet);Object[] states=(Object[])updateStates.get(packet);
            // Apply the entire original batch before resolving any canopy against its logs.
            for(int i=0;i<at.length;i++)changed((sx<<4)|((at[i]>>8)&15),(sy<<4)|(at[i]&15),(sz<<4)|((at[i]>>4)&15),id(states[i]));
            Chunk chunk=chunks.get(PaperBiomePackets.key(sx,sz));if(chunk==null||(sy<<4)<chunk.minY||(sy<<4)>=chunk.maxY)return packet;
            Short2ObjectOpenHashMap<Object> mapped=new Short2ObjectOpenHashMap<>();boolean differs=false;
            for(int i=0;i<at.length;i++){
                int x=(sx<<4)|((at[i]>>8)&15),y=(sy<<4)|(at[i]&15),z=(sz<<4)|((at[i]>>4)&15),original=id(states[i]);
                int key=chunk.key(x,y,z),painted=appearance(chunk,key,original);shown(chunk,key,original,painted);differs|=original!=painted;
                mapped.put(at[i],registry.states[painted]);
            }
            return differs?sectionConstructor.newInstance(section,mapped):packet;
        }
        private Chunk changed(int x,int y,int z,int state){
            Chunk chunk=chunks.get(PaperBiomePackets.key(x>>4,z>>4));if(chunk==null||y<chunk.minY||y>=chunk.maxY)return null;
            int key=chunk.key(x,y,z),old=chunk.original.get(key);
            boolean relevant=registry.relevant(state);
            if(old==state||old<0&&!relevant)return chunk;
            if(old>=0){chunk.original.remove(key);storedStates--;}
            if(relevant){if(storedStates<MAX_TREE_STATES){chunk.original.put(key,state);storedStates++;}else chunk.complete=false;}
            if(old<0||!registry.kind(old).leaves()||registry.kind(old).species()!=registry.kind(state).species())chunk.shown.remove(key);
            invalidate();nearby(chunk.x,chunk.z);return chunk;
        }
        public void forget(int x,int z){long key=PaperBiomePackets.key(x,z);cancelRefresh(key);Chunk old=chunks.remove(key);pending.remove(key);repairs.remove(key);if(old!=null){storedStates-=old.original.size();clearMissing(old);invalidate();nearby(x,z);}}
        /** Explicit unlimited-state drains remain available for shutdown and compatibility. */
        public List<Object> refresh(int budget) throws ReflectiveOperationException {
            return refresh(budget,Integer.MAX_VALUE);
        }
        /** Both visits and inspected original states are bounded, including unchanged blocks. */
        public List<Object> refresh(int budget,int stateBudget) throws ReflectiveOperationException {
            if(budget<0||stateBudget<0)throw new IllegalArgumentException("Nonnegative foliage budget required");
            lastRefreshChunks=0;lastRefreshStates=0;
            List<Object> result=new ArrayList<>();
            while(lastRefreshChunks<budget&&lastRefreshStates<stateBudget&&pending()){
                if(refreshing==null){
                    Set<Long> next=repairs.isEmpty()?pending:repairs;Iterator<Long> iterator=next.iterator();long key=iterator.next();iterator.remove();
                    refreshing=chunks.get(key);if(refreshing==null)continue;
                    clearMissing(refreshing);
                    // Shown states are always a subset of originals; edits invalidate this cursor.
                    refreshStates=refreshing.original.keySet().iterator();
                }
                Chunk chunk=refreshing;lastRefreshChunks++;
                Map<Integer,Short2ObjectOpenHashMap<Object>> updates=new LinkedHashMap<>();
                while(lastRefreshStates<stateBudget&&refreshStates.hasNext()){
                    int local=refreshStates.nextInt();lastRefreshStates++;
                    int original=chunk.original.get(local);if(original<0)continue;
                    int painted=appearance(chunk,local,original),previous=chunk.shown.getOrDefault(local,original);
                    if(previous==painted)continue;
                    int y=chunk.minY+(local>>8),section=y>>4;
                    short at=(short)(((local&15)<<8)|(((local>>4)&15)<<4)|(y&15));
                    updates.computeIfAbsent(section,k->new Short2ObjectOpenHashMap<>()).put(at,registry.states[painted]);shown(chunk,local,original,painted);
                }
                for(var row:updates.entrySet())result.add(sectionConstructor.newInstance(sectionOf.invoke(null,chunk.x,row.getKey(),chunk.z),row.getValue()));
                if(!refreshStates.hasNext()){refreshing=null;refreshStates=null;}
            }
            if(!pending()&&clearAfterRestore){clear();context=null;}
            return result;
        }
        public int lastRefreshChunks(){return lastRefreshChunks;}
        public int lastRefreshStates(){return lastRefreshStates;}
        public boolean pending(){return refreshing!=null||!pending.isEmpty()||!repairs.isEmpty();}
        public int retainedStates(){return storedStates;}
    }
    public View view(){return new View();}
    private final Map<Object,Integer> stateIds=new IdentityHashMap<>();
    private int id(Object state){
        // Read-only after construction; native BlockState objects are immutable registry singletons.
        Integer id=stateIds.get(state);if(id==null)throw new IllegalArgumentException("Unknown block update state");return id;
    }
    private Object copyChunk(Object packet,Object old,byte[] bytes) throws ReflectiveOperationException {
        Object copy=dataConstructor.newInstance(heightmaps.get(old),bytes,entities.get(old));extras.set(copy,extras.get(old));
        copy.getClass().getMethod("setReady",boolean.class).invoke(copy,true);
        return chunkConstructor.newInstance(call(packet,"x"),call(packet,"z"),copy,call(packet,"lightData"));
    }
    private static Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name);}
    private static Object call(Object object,String method)throws ReflectiveOperationException{return PaperBiomePackets.call(object,method);}
    private static Field field(Class<?> type,String name)throws ReflectiveOperationException{Field result=type.getDeclaredField(name);result.setAccessible(true);return result;}
}
