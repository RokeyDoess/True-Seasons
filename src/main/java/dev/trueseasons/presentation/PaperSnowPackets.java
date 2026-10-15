package dev.trueseasons.presentation;

import dev.trueseasons.snow.SnowOwnershipSnapshots;
import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.shorts.*;
import java.lang.reflect.*;
import java.util.*;

/** Presentation only: retain original owned single layers from packets, never world blocks. */
public final class PaperSnowPackets {
    private static final int MAX_COLUMNS=1_048_576, MAX_CHUNKS=8192;
    private static final int MAX_GROUND_STATES=1_048_576, MAX_GROUND_PER_CHUNK=512;
    private static final Integer[] COLUMN_KEYS=new Integer[256];
    static {for(int column=0;column<256;column++)COLUMN_KEYS[column]=column;}
    private final NativeLeafRegistry registry;
    final int snowId, airId;
    private final int[] plainGround;
    private final Field buffer,heightmaps,entities,extras,sectionPos,positions,updateStates;
    private final Constructor<?> dataConstructor,chunkConstructor,blockConstructor,sectionConstructor;
    private final Class<?> blockPacket,sectionPacket;
    private final Method sectionOf;
    private final Map<Object,Integer> ids=new IdentityHashMap<>();
    public PaperSnowPackets(PaperLeafPackets leaves)throws ReflectiveOperationException {
        registry=leaves.registry;
        Class<?> blocks=type("net.minecraft.world.level.block.Blocks");
        Object snow=blocks.getField("SNOW").get(null),air=blocks.getField("AIR").get(null);
        Object snowState=snow.getClass().getMethod("defaultBlockState").invoke(snow),airState=air.getClass().getMethod("defaultBlockState").invoke(air);
        for(int i=0;i<registry.states.length;i++)ids.put(registry.states[i],i);
        snowId=id(snowState);airId=id(airState);
        plainGround=new int[registry.states.length];Arrays.fill(plainGround,-1);
        Class<?> property=type("net.minecraft.world.level.block.state.properties.Property");
        Object snowy=type("net.minecraft.world.level.block.state.properties.BlockStateProperties").getField("SNOWY").get(null);
        for(String name:List.of("GRASS_BLOCK","PODZOL","MYCELIUM")){
            Object state=call(blocks.getField(name).get(null),"defaultBlockState");
            Method set=state.getClass().getMethod("setValue",property,Comparable.class);
            plainGround[id(set.invoke(state,snowy,true))]=id(set.invoke(state,snowy,false));
        }
        Class<?> data=type("net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData"),chunk=type("net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket");
        buffer=field(data,"buffer");heightmaps=field(data,"heightmaps");entities=field(data,"blockEntitiesData");extras=field(data,"extraPackets");
        dataConstructor=data.getDeclaredConstructor(Map.class,byte[].class,List.class);dataConstructor.setAccessible(true);
        chunkConstructor=chunk.getConstructor(int.class,int.class,data,type("net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData"));
        blockPacket=type("net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket");
        blockConstructor=blockPacket.getConstructor(type("net.minecraft.core.BlockPos"),type("net.minecraft.world.level.block.state.BlockState"));
        sectionPacket=type("net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket");
        sectionPos=field(sectionPacket,"sectionPos");positions=field(sectionPacket,"positions");updateStates=field(sectionPacket,"states");
        Class<?> section=type("net.minecraft.core.SectionPos");sectionOf=section.getMethod("of",int.class,int.class,int.class);
        sectionConstructor=sectionPacket.getConstructor(section,Short2ObjectMap.class);
    }
    public record Context(String version,int coverage,int minY,int maxY,SnowOwnershipSnapshots.WorldView ownership) {
        public Context(String version,int coverage,int minY,SnowOwnershipSnapshots.WorldView ownership){this(version,coverage,minY,Integer.MAX_VALUE,ownership);}
        public Context { Objects.requireNonNull(version);Objects.requireNonNull(ownership);if(coverage<0||coverage>256||maxY<=minY)throw new IllegalArgumentException("Invalid snow coverage or height"); }
    }
    private static final class Chunk {
        final int x,z,minY,maxY;
        final IntSet originals=new IntOpenHashSet(), hidden=new IntOpenHashSet();
        final Int2IntOpenHashMap ground=new Int2IntOpenHashMap();
        final IntSet groundHidden=new IntOpenHashSet();
        Chunk(int x,int z,int minY,int maxY){this.x=x;this.z=z;this.minY=minY;this.maxY=maxY;ground.defaultReturnValue(-1);}
        int local(int x,int y,int z){return ((y-minY)<<8)|((z&15)<<4)|(x&15);}
        int column(int local){return local&255;}
        int y(int local){return minY+(local>>8);}
    }
    public final class View {
        private final Map<Long,Chunk> chunks=new HashMap<>();
        private final ArrayDeque<Long> pending=new ArrayDeque<>();
        private final Set<Long> queued=new HashSet<>();
        private Context context;
        private int retained,retainedGrounds,hiddenSnow;
        public void context(Context next){
            if(Objects.equals(context,next))return;
            if(context!=null&&next!=null&&(context.ownership!=next.ownership||context.minY!=next.minY||context.maxY!=next.maxY))clear();
            context=next;chunks.keySet().forEach(this::queue);
        }
        public void clear(){chunks.clear();pending.clear();queued.clear();retained=0;retainedGrounds=0;hiddenSnow=0;}
        private void queue(long key){
            Chunk chunk=chunks.get(key);
            if(chunk!=null&&(!chunk.originals.isEmpty()||!chunk.groundHidden.isEmpty())&&queued.add(key))pending.addLast(key);
        }
        public void dirty(int x,int z){queue(PaperBiomePackets.key(x,z));}
        private SnowOwnershipSnapshots.Snapshot owners(Chunk chunk){return context==null?SnowOwnershipSnapshots.Snapshot.EMPTY:context.ownership.snapshot(chunk.x,chunk.z);}
        private boolean owns(Chunk chunk,int local,SnowOwnershipSnapshots.Snapshot ownership){
            Integer height=ownership.heights().get(COLUMN_KEYS[chunk.column(local)]);
            return height!=null&&height.intValue()==chunk.y(local);
        }
        private boolean hide(Chunk chunk,int local,SnowOwnershipSnapshots.Snapshot ownership){
            if(context==null||context.coverage>=256||!owns(chunk,local,ownership))return false;
            int salt=31*(31*(31+chunk.x)+chunk.z)+context.version.hashCode();
            int rank=((chunk.column(local)-salt)*189)&255;
            return rank>=context.coverage;
        }
        private void hidden(Chunk chunk,int local,boolean value){
            if(value){if(chunk.hidden.add(local))hiddenSnow++;}
            else if(chunk.hidden.remove(local))hiddenSnow--;
        }
        private boolean hideGround(Chunk chunk,int local,SnowOwnershipSnapshots.Snapshot ownership){
            int above=local+256;
            return chunk.hidden.contains(above)&&owns(chunk,above,ownership);
        }
        private void sourceGround(Chunk chunk,int local,int state){
            if(plainGround[state]>=0){
                if(chunk.ground.containsKey(local)||(retainedGrounds<MAX_GROUND_STATES&&chunk.ground.size()<MAX_GROUND_PER_CHUNK)){
                    if(chunk.ground.put(local,state)<0)retainedGrounds++;
                }
            }else if(chunk.ground.remove(local)>=0)retainedGrounds--;
            chunk.groundHidden.remove(local);
        }
        private int paintGround(Chunk chunk,int local,int state,SnowOwnershipSnapshots.Snapshot ownership){
            if(plainGround[state]>=0&&chunk.ground.containsKey(local)&&hideGround(chunk,local,ownership)){
                chunk.groundHidden.add(local);return plainGround[state];
            }
            chunk.groundHidden.remove(local);
            return state;
        }
        private void removed(Chunk chunk){
            if(chunk!=null){retained-=chunk.originals.size();retainedGrounds-=chunk.ground.size();hiddenSnow-=chunk.hidden.size();}
        }
        public Object chunk(Object packet)throws ReflectiveOperationException {
            int x=(int)call(packet,"x"),z=(int)call(packet,"z");long key=PaperBiomePackets.key(x,z);
            if(context==null){forget(x,z);return packet;}
            removed(chunks.remove(key));
            if(chunks.size()>=MAX_CHUNKS)return packet;
            Object old=call(packet,"chunkData");
            var ownership=context.ownership.snapshot(x,z);
            // Snowy support flags must also be known before winter starts in a sent chunk.
            // Retain only these three native states; no terrain reads or world writes.
            var sections=LeafWireCodec.read((byte[])buffer.get(old),id->id==snowId||plainGround[id]>=0);
            Chunk chunk=new Chunk(x,z,context.minY,context.minY+sections.size()*16);
            for(int section=0;section<sections.size();section++){
                int[] states=sections.get(section).states();if(states==null)continue;
                for(int index=0;index<states.length&&chunk.ground.size()<MAX_GROUND_PER_CHUNK&&retainedGrounds<MAX_GROUND_STATES;index++)
                    if(plainGround[states[index]]>=0)sourceGround(chunk,(section<<12)|index,states[index]);
            }
            for(var entry:ownership.heights().entrySet()){
                int y=entry.getValue();if(y<chunk.minY||y>=chunk.maxY)continue;
                int[] states=sections.get((y-chunk.minY)>>4).states();if(states==null)continue;
                int local=((y-chunk.minY)<<8)|entry.getKey();
                if(states[local&4095]==snowId)chunk.originals.add(local);
            }
            if(retained+chunk.originals.size()>MAX_COLUMNS){retainedGrounds-=chunk.ground.size();return packet;}
            chunks.put(key,chunk);retained+=chunk.originals.size();
            boolean any=false;
            for(IntIterator positions=chunk.originals.iterator();positions.hasNext();){
                int local=positions.nextInt();if(hide(chunk,local,ownership)){hidden(chunk,local,true);any=true;}
            }
            for(var entry:chunk.ground.int2IntEntrySet())if(hideGround(chunk,entry.getIntKey(),ownership)){chunk.groundHidden.add(entry.getIntKey());any=true;}
            if(!any)return packet;
            byte[] mapped=LeafWireCodec.write(sections,registry.states.length,(section,index,original)->{
                int local=(section<<12)|index;
                return chunk.hidden.contains(local)?airId:chunk.groundHidden.contains(local)?plainGround[original]:original;
            },id->id==airId);
            return copyChunk(packet,old,mapped);
        }
        private int update(Chunk chunk,int x,int y,int z,int original,SnowOwnershipSnapshots.Snapshot ownership){
            int local=chunk.local(x,y,z);
            boolean wasHidden=chunk.hidden.contains(local);
            if(chunk.originals.remove(local))retained--;
            hidden(chunk,local,false);
            if(original==snowId&&owns(chunk,local,ownership)&&retained<MAX_COLUMNS){
                chunk.originals.add(local);retained++;
                hidden(chunk,local,hide(chunk,local,ownership));
            }
            if(wasHidden!=chunk.hidden.contains(local)&&chunk.ground.containsKey(local-256))queue(PaperBiomePackets.key(chunk.x,chunk.z));
            sourceGround(chunk,local,original);
            return chunk.hidden.contains(local)?airId:paintGround(chunk,local,original,ownership);
        }
        public Object update(Object packet)throws ReflectiveOperationException {
            if(context==null&&chunks.isEmpty())return packet;
            if(blockPacket.isInstance(packet)){
                Object pos=call(packet,"getPos");int x=(int)call(pos,"getX"),y=(int)call(pos,"getY"),z=(int)call(pos,"getZ");
                Chunk chunk=chunks.get(PaperBiomePackets.key(x>>4,z>>4));if(chunk==null||y<chunk.minY||y>=chunk.maxY)return packet;
                int original=id(call(packet,"getBlockState")),painted=update(chunk,x,y,z,original,owners(chunk));
                return original==painted?packet:blockConstructor.newInstance(pos,registry.states[painted]);
            }
            if(!sectionPacket.isInstance(packet))return packet;
            Object section=sectionPos.get(packet);int sx=(int)call(section,"x"),sy=(int)call(section,"y"),sz=(int)call(section,"z");
            Chunk chunk=chunks.get(PaperBiomePackets.key(sx,sz));if(chunk==null||(sy<<4)<chunk.minY||(sy<<4)>=chunk.maxY)return packet;
            short[] at=(short[])positions.get(packet);Object[] states=(Object[])updateStates.get(packet);
            var ownership=owners(chunk);boolean differs=false;Short2ObjectOpenHashMap<Object> mapped=new Short2ObjectOpenHashMap<>();
            // Record the complete source batch before choosing support textures.
            for(int i=0;i<at.length;i++){
                update(chunk,(sx<<4)|((at[i]>>8)&15),(sy<<4)|(at[i]&15),(sz<<4)|((at[i]>>4)&15),id(states[i]),ownership);
            }
            for(int i=0;i<at.length;i++){
                int original=id(states[i]),local=chunk.local((sx<<4)|((at[i]>>8)&15),(sy<<4)|(at[i]&15),(sz<<4)|((at[i]>>4)&15));
                int painted=chunk.hidden.contains(local)?airId:paintGround(chunk,local,original,ownership);
                differs|=original!=painted;mapped.put(at[i],registry.states[painted]);
            }
            return differs?sectionConstructor.newInstance(section,mapped):packet;
        }
        public List<Object> refresh(int maxChunks,int maxBlocks)throws ReflectiveOperationException {
            List<Object> packets=new ArrayList<>();int changed=0;
            for(int count=0;count<maxChunks&&!pending.isEmpty()&&changed<maxBlocks;count++){
                long key=pending.removeFirst();queued.remove(key);Chunk chunk=chunks.get(key);if(chunk==null)continue;
                var ownership=owners(chunk);Map<Integer,Short2ObjectOpenHashMap<Object>> updates=new LinkedHashMap<>();
                for(IntIterator positions=chunk.originals.iterator();positions.hasNext();){
                    int local=positions.nextInt();
                    boolean hidden=hide(chunk,local,ownership);if(hidden==chunk.hidden.contains(local))continue;
                    if(changed>=maxBlocks){queue(key);break;}
                    int y=chunk.y(local);short at=(short)(((local&15)<<8)|(((local>>4)&15)<<4)|(y&15));
                    updates.computeIfAbsent(y>>4,ignored->new Short2ObjectOpenHashMap<>()).put(at,registry.states[hidden?airId:snowId]);
                    hidden(chunk,local,hidden);changed++;
                }
                for(var entry:chunk.ground.int2IntEntrySet()){
                    int local=entry.getIntKey();boolean hidden=hideGround(chunk,local,ownership);
                    if(hidden==chunk.groundHidden.contains(local))continue;
                    if(changed>=maxBlocks){queue(key);break;}
                    int y=chunk.y(local);short at=(short)(((local&15)<<8)|(((local>>4)&15)<<4)|(y&15));
                    int original=entry.getIntValue();updates.computeIfAbsent(y>>4,ignored->new Short2ObjectOpenHashMap<>()).put(at,registry.states[hidden?plainGround[original]:original]);
                    if(hidden)chunk.groundHidden.add(local);else chunk.groundHidden.remove(local);changed++;
                }
                for(var row:updates.entrySet())packets.add(sectionConstructor.newInstance(sectionOf.invoke(null,chunk.x,row.getKey(),chunk.z),row.getValue()));
            }
            if(context==null&&pending.isEmpty())clear();
            return packets;
        }
        public void forget(int x,int z){long key=PaperBiomePackets.key(x,z);removed(chunks.remove(key));queued.remove(key);pending.remove(key);}
        public boolean pending(){return !pending.isEmpty();}
        public int retainedColumns(){return retained;}
        public int hiddenColumns(){return hiddenSnow;}
        public int retainedGroundStates(){return retainedGrounds;}
    }
    public View view(){return new View();}
    private int id(Object state){Integer result=ids.get(state);if(result==null)throw new IllegalArgumentException("Unknown native block state");return result;}
    private Object copyChunk(Object packet,Object old,byte[] bytes)throws ReflectiveOperationException {
        Object data=dataConstructor.newInstance(heightmaps.get(old),bytes,entities.get(old));extras.set(data,extras.get(old));data.getClass().getMethod("setReady",boolean.class).invoke(data,true);
        return chunkConstructor.newInstance(call(packet,"x"),call(packet,"z"),data,call(packet,"lightData"));
    }
    private static Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name);}
    private static Object call(Object object,String name)throws ReflectiveOperationException{return PaperBiomePackets.call(object,name);}
    private static Field field(Class<?> type,String name)throws ReflectiveOperationException{Field result=type.getDeclaredField(name);result.setAccessible(true);return result;}
}
