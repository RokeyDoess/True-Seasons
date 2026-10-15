package dev.trueseasons.presentation;

import dev.trueseasons.snow.SnowOwnershipSnapshots;
import dev.trueseasons.snow.SnowColumnOrder;
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
    private final int[] plainGround, snowyGround;
    private final boolean[] airStates;
    private final Method nativeState;
    private final Field buffer,heightmaps,entities,extras,sectionPos,positions,updateStates;
    private final Constructor<?> dataConstructor,chunkConstructor,blockConstructor,sectionConstructor;
    private final Class<?> blockPacket,sectionPacket,actionPacket;
    private final Method sectionOf;
    private final Map<Object,Integer> ids=new IdentityHashMap<>();
    public PaperSnowPackets(PaperLeafPackets leaves)throws ReflectiveOperationException {
        registry=leaves.registry;
        Class<?> blocks=type("net.minecraft.world.level.block.Blocks");
        Object snow=blocks.getField("SNOW").get(null),air=blocks.getField("AIR").get(null);
        Object snowState=snow.getClass().getMethod("defaultBlockState").invoke(snow),airState=air.getClass().getMethod("defaultBlockState").invoke(air);
        for(int i=0;i<registry.states.length;i++)ids.put(registry.states[i],i);
        snowId=id(snowState);airId=id(airState);
        nativeState=type("org.bukkit.craftbukkit.block.data.CraftBlockData").getMethod("getState");
        airStates=new boolean[registry.states.length];
        for(String name:List.of("AIR","CAVE_AIR","VOID_AIR"))airStates[id(call(blocks.getField(name).get(null),"defaultBlockState"))]=true;
        plainGround=new int[registry.states.length];Arrays.fill(plainGround,-1);
        snowyGround=new int[registry.states.length];Arrays.fill(snowyGround,-1);
        Class<?> property=type("net.minecraft.world.level.block.state.properties.Property");
        Object snowy=type("net.minecraft.world.level.block.state.properties.BlockStateProperties").getField("SNOWY").get(null);
        for(String name:List.of("GRASS_BLOCK","PODZOL","MYCELIUM")){
            Object state=call(blocks.getField(name).get(null),"defaultBlockState");
            Method set=state.getClass().getMethod("setValue",property,Comparable.class);
            plainGround[id(set.invoke(state,snowy,true))]=id(set.invoke(state,snowy,false));
            snowyGround[id(set.invoke(state,snowy,false))]=id(set.invoke(state,snowy,true));
        }
        Class<?> data=type("net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData"),chunk=type("net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket");
        buffer=field(data,"buffer");heightmaps=field(data,"heightmaps");entities=field(data,"blockEntitiesData");extras=field(data,"extraPackets");
        dataConstructor=data.getDeclaredConstructor(Map.class,byte[].class,List.class);dataConstructor.setAccessible(true);
        chunkConstructor=chunk.getConstructor(int.class,int.class,data,type("net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData"));
        blockPacket=type("net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket");
        actionPacket=type("net.minecraft.network.protocol.game.ServerboundPlayerActionPacket");
        blockConstructor=blockPacket.getConstructor(type("net.minecraft.core.BlockPos"),type("net.minecraft.world.level.block.state.BlockState"));
        sectionPacket=type("net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket");
        sectionPos=field(sectionPacket,"sectionPos");positions=field(sectionPacket,"positions");updateStates=field(sectionPacket,"states");
        Class<?> section=type("net.minecraft.core.SectionPos");sectionOf=section.getMethod("of",int.class,int.class,int.class);
        sectionConstructor=sectionPacket.getConstructor(section,Short2ObjectMap.class);
    }
    public record Context(String version,int coverage,int minY,int maxY,SnowOwnershipSnapshots.WorldView ownership,boolean previews,int breakDelaySeconds) {
        public Context(String version,int coverage,int minY,int maxY,SnowOwnershipSnapshots.WorldView ownership){this(version,coverage,minY,maxY,ownership,false,60);}
        public Context(String version,int coverage,int minY,SnowOwnershipSnapshots.WorldView ownership){this(version,coverage,minY,Integer.MAX_VALUE,ownership);}
        public Context { Objects.requireNonNull(version);Objects.requireNonNull(ownership);if(coverage<0||coverage>256||maxY<=minY||breakDelaySeconds<0||breakDelaySeconds>86400)throw new IllegalArgumentException("Invalid snow coverage, height or grace"); }
    }
    private static final class Chunk {
        final int x,z,minY,maxY;
        final IntSet originals=new IntOpenHashSet(), hidden=new IntOpenHashSet();
        final Int2IntOpenHashMap ground=new Int2IntOpenHashMap();
        final IntSet groundHidden=new IntOpenHashSet();
        final IntSet previewShown=new IntOpenHashSet(), previewWanted=new IntOpenHashSet(), groundPreview=new IntOpenHashSet();
        final Int2IntOpenHashMap previewAir=new Int2IntOpenHashMap(),previewPaint=new Int2IntOpenHashMap();
        long[] changedAt,brokenUntil;
        String orderVersion;
        SnowColumnOrder order;
        Chunk(int x,int z,int minY,int maxY){this.x=x;this.z=z;this.minY=minY;this.maxY=maxY;ground.defaultReturnValue(-1);previewAir.defaultReturnValue(-1);}
        int local(int x,int y,int z){return ((y-minY)<<8)|((z&15)<<4)|(x&15);}
        int column(int local){return local&255;}
        int y(int local){return minY+(local>>8);}
    }
    public final class View {
        private final Map<Long,Chunk> chunks=new HashMap<>();
        private final LinkedHashSet<Long> pending=new LinkedHashSet<>(),fresh=new LinkedHashSet<>();
        private int priorityTurns,lastRefreshChunks,lastRefreshBlocks;
        // Keep preview-only digging grace through chunk forgetting/resends, with a fixed cap.
        private final Map<Long,long[]> breakGrace=new LinkedHashMap<>();
        private volatile long nextGraceExpiry;
        private Context context;
        private boolean consumeDig;
        private Object extraDigCorrection;
        public Object extraDigCorrection(){return extraDigCorrection;}
        public boolean consumedDig(){return consumeDig;}
        public Object acknowledgeDig(Object packet)throws ReflectiveOperationException{return type("net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket").getConstructor(int.class).newInstance(call(packet,"getSequence"));}
        private int retained,retainedGrounds,hiddenSnow,retainedPreviews,shownPreviews;
        public void context(Context next){
            if(Objects.equals(context,next))return;
            if(context!=null&&next!=null&&(context.ownership!=next.ownership||context.minY!=next.minY||context.maxY!=next.maxY))clear();
            if(next!=null&&(next.breakDelaySeconds==0||context!=null&&!next.version.equals(context.version))){for(long[] times:breakGrace.values())Arrays.fill(times,0);breakGrace.clear();nextGraceExpiry=0;}
            context=next;chunks.keySet().forEach(this::queue);
        }
        public void clear(){chunks.clear();pending.clear();fresh.clear();breakGrace.clear();nextGraceExpiry=0;retained=0;retainedGrounds=0;hiddenSnow=0;retainedPreviews=0;shownPreviews=0;}
        private void queue(long key){
            queue(key,false);
        }
        private void queue(long key,boolean priority){
            Chunk chunk=chunks.get(key);
            if(chunk!=null&&(!chunk.originals.isEmpty()||!chunk.groundHidden.isEmpty()||!chunk.previewAir.isEmpty()||!chunk.groundPreview.isEmpty()
                    ||context!=null&&context.previews&&context.ownership.preview(chunk.x,chunk.z)!=null)){
                if(priority){pending.remove(key);fresh.add(key);}else if(!fresh.contains(key))pending.add(key);
            }
        }
        public void dirty(int x,int z){queue(PaperBiomePackets.key(x,z));}
        public void prioritize(int x,int z){queue(PaperBiomePackets.key(x,z),true);}
        private SnowOwnershipSnapshots.Snapshot owners(Chunk chunk){return context==null?SnowOwnershipSnapshots.Snapshot.EMPTY:context.ownership.snapshot(chunk.x,chunk.z);}
        private boolean owns(Chunk chunk,int local,SnowOwnershipSnapshots.Snapshot ownership){
            Integer height=ownership.heights().get(COLUMN_KEYS[chunk.column(local)]);
            return height!=null&&height.intValue()==chunk.y(local);
        }
        private boolean hide(Chunk chunk,int local,SnowOwnershipSnapshots.Snapshot ownership){
            if(context==null||!owns(chunk,local,ownership))return false;
            if(context.breakDelaySeconds>0&&chunk.brokenUntil!=null&&chunk.brokenUntil[local&255]-System.nanoTime()>0)return true;
            if(context.coverage>=256)return false;
            return rank(chunk,local)>=context.coverage;
        }
        private int rank(Chunk chunk,int local){
            if(!Objects.equals(chunk.orderVersion,context.version)){chunk.orderVersion=context.version;chunk.order=new SnowColumnOrder(chunk.x,chunk.z,context.version);}
            return chunk.order.rank(chunk.column(local));
        }
        private void breakPreview(Chunk chunk,int column,long now){
            if(context==null||context.breakDelaySeconds<=0)return;
            long key=PaperBiomePackets.key(chunk.x,chunk.z);
            if(chunk.brokenUntil==null)chunk.brokenUntil=new long[256];
            if(!breakGrace.containsKey(key)){
                if(breakGrace.size()>=256){long oldest=breakGrace.keySet().iterator().next();Arrays.fill(breakGrace.remove(oldest),0);queue(oldest);}
                breakGrace.put(key,chunk.brokenUntil);
            }
            long expires=now+context.breakDelaySeconds*1_000_000_000L;chunk.brokenUntil[column]=expires;
            if(nextGraceExpiry==0||expires-nextGraceExpiry<0)nextGraceExpiry=expires;
        }
        /** Only this volatile deadline is read by the main thread; caches stay on the event loop. */
        public boolean timedRefreshDue(){long expiry=nextGraceExpiry;return expiry!=0&&expiry-System.nanoTime()<=0;}
        private void expireGrace(){
            if(!timedRefreshDue())return;
            long now=System.nanoTime(),next=0;
            for(var iterator=breakGrace.entrySet().iterator();iterator.hasNext();){
                var row=iterator.next();boolean expired=false,remaining=false;
                for(int c=0;c<256;c++)if(row.getValue()[c]!=0){
                    long until=row.getValue()[c];if(until-now<=0){row.getValue()[c]=0;expired=true;}
                    else{remaining=true;if(next==0||until-next<0)next=until;}
                }
                if(expired)queue(row.getKey());if(!remaining)iterator.remove();
            }
            nextGraceExpiry=next;
        }
        /** Let the server validate the action; correct only this viewer's preview immediately. */
        public Object dig(Object packet)throws ReflectiveOperationException{
            consumeDig=false;extraDigCorrection=null;if(context==null||!actionPacket.isInstance(packet))return null;
            String action=((Enum<?>)call(packet,"getAction")).name();
            if(!action.equals("START_DESTROY_BLOCK")&&!action.equals("STOP_DESTROY_BLOCK"))return null;
            Object pos=call(packet,"getPos");int x=(int)call(pos,"getX"),y=(int)call(pos,"getY"),z=(int)call(pos,"getZ");
            Chunk chunk=chunks.get(PaperBiomePackets.key(x>>4,z>>4));if(chunk==null||y<chunk.minY||y>=chunk.maxY)return null;
            int local=chunk.local(x,y,z);if(!chunk.previewShown.remove(local))return null;
            shownPreviews--;
            int original=chunk.previewAir.remove(local);chunk.previewPaint.remove(local);retainedPreviews--;
            consumeDig=!airStates[original];
            int above=local+256;
            if(chunk.previewShown.contains(above)&&chunk.previewPaint.get(above)==airId){
                chunk.previewShown.remove(above);shownPreviews--;int restored=chunk.previewAir.remove(above);retainedPreviews--;chunk.previewPaint.remove(above);
                Object top=pos.getClass().getConstructor(int.class,int.class,int.class).newInstance(x,y+1,z);
                extraDigCorrection=blockConstructor.newInstance(top,registry.states[restored]);
            }
            long now=System.nanoTime();breakPreview(chunk,local&255,now);
            if(chunk.changedAt==null)chunk.changedAt=new long[256];chunk.changedAt[local&255]=now;
            chunk.previewWanted.removeIf(value->(value&255)==(local&255));queue(PaperBiomePackets.key(chunk.x,chunk.z));
            return blockConstructor.newInstance(pos,registry.states[original]);
        }
        private SnowOwnershipSnapshots.Preview preview(Chunk chunk){
            if(context==null||!context.previews)return null;
            var next=context.ownership.preview(chunk.x,chunk.z);
            return next!=null&&next.version().equals(context.version)?next:null;
        }
        private int source(List<LeafWireCodec.Section> sections,Chunk chunk,int local){
            if(local<0||local>=(chunk.maxY-chunk.minY)*256)return -1;
            int[] states=sections.get(local>>12).states();return states==null?-1:states[local&4095];
        }
        private boolean supportState(int actual,int expected){return actual==expected||actual>=0&&expected>=0&&(plainGround[actual]==expected||plainGround[expected]==actual);}
        private boolean fresh(Chunk chunk,int column,SnowOwnershipSnapshots.Preview snapshot){
            return (chunk.changedAt==null||chunk.changedAt[column]==0||chunk.changedAt[column]-snapshot.checkedAt()<0)
                &&(context.breakDelaySeconds==0||chunk.brokenUntil==null||chunk.brokenUntil[column]-System.nanoTime()<=0);
        }
        private void putPreview(Chunk chunk,int local,int source,int paint){
            if(chunk.previewAir.put(local,source)<0)retainedPreviews++;chunk.previewPaint.put(local,paint);
            if(rank(chunk,local)<context.coverage&&!chunk.originals.contains(local))chunk.previewWanted.add(local);
        }
        private void syncPreviews(Chunk chunk,List<LeafWireCodec.Section> sections){
            chunk.previewWanted.clear();var snapshot=preview(chunk);if(snapshot==null)return;
            for(var row:snapshot.columns().entrySet()){
                int column=row.getKey();var candidate=row.getValue();int y=candidate.y(),upper=candidate.upperState();
                if(column<0||column>255||y<=chunk.minY||y>=chunk.maxY||upper>=0&&y+1>=chunk.maxY
                    ||candidate.airState()<0||candidate.airState()>=registry.states.length||upper>=registry.states.length
                    ||!snapshot.melting()&&!airStates[candidate.airState()]||!fresh(chunk,column,snapshot))continue;
                int local=((y-chunk.minY)<<8)|column;
                if(sections!=null){
                    int original=source(sections,chunk,local);
                    boolean valid=original>=0&&(snapshot.melting()?original==candidate.airState():airStates[original])
                        &&supportState(source(sections,chunk,local-256),candidate.supportState())
                        &&(upper<0||source(sections,chunk,local+256)==upper);
                    for(int above=local+(upper<0?256:512);valid&&above<(chunk.maxY-chunk.minY)*256;above+=256){int state=source(sections,chunk,above);valid=state>=0&&airStates[state];}
                    if(!valid){if(chunk.changedAt==null)chunk.changedAt=new long[256];chunk.changedAt[column]=System.nanoTime();continue;}
                }
                int needed=(chunk.previewAir.containsKey(local)?0:1)+(upper>=0&&!chunk.previewAir.containsKey(local+256)?1:0);
                if(retainedPreviews+needed>MAX_COLUMNS)continue;
                putPreview(chunk,local,sections==null?candidate.airState():source(sections,chunk,local),snowId);
                if(upper>=0)putPreview(chunk,local+256,upper,airId);
            }
        }
        private int painted(Chunk chunk,int local,int original,SnowOwnershipSnapshots.Snapshot ownership){
            return chunk.previewShown.contains(local)?chunk.previewPaint.get(local):chunk.hidden.contains(local)?airId:paintGround(chunk,local,original,ownership);
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
            if(plainGround[state]>=0||snowyGround[state]>=0){
                if(chunk.ground.containsKey(local)||(retainedGrounds<MAX_GROUND_STATES&&chunk.ground.size()<MAX_GROUND_PER_CHUNK)){
                    if(chunk.ground.put(local,state)<0)retainedGrounds++;
                }
            }else if(chunk.ground.remove(local)>=0)retainedGrounds--;
            chunk.groundHidden.remove(local);
            chunk.groundPreview.remove(local);
        }
        private int desiredGround(Chunk chunk,int local,int original,SnowOwnershipSnapshots.Snapshot ownership){
            if(plainGround[original]>=0&&hideGround(chunk,local,ownership))return plainGround[original];
            if(snowyGround[original]>=0&&chunk.previewShown.contains(local+256))return snowyGround[original];
            return original;
        }
        private int paintGround(Chunk chunk,int local,int state,SnowOwnershipSnapshots.Snapshot ownership){
            int painted=chunk.ground.containsKey(local)?desiredGround(chunk,local,state,ownership):state;
            if(painted!=state&&painted==plainGround[state])chunk.groundHidden.add(local);else chunk.groundHidden.remove(local);
            if(painted!=state&&painted==snowyGround[state])chunk.groundPreview.add(local);else chunk.groundPreview.remove(local);
            return painted;
        }
        private void removed(Chunk chunk){
            if(chunk!=null){retained-=chunk.originals.size();retainedGrounds-=chunk.ground.size();hiddenSnow-=chunk.hidden.size();retainedPreviews-=chunk.previewAir.size();shownPreviews-=chunk.previewShown.size();}
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
            boolean preview=context.previews&&context.ownership.preview(x,z)!=null;
            var sections=LeafWireCodec.read((byte[])buffer.get(old),id->preview||id==snowId||plainGround[id]>=0||snowyGround[id]>=0);
            Chunk chunk=new Chunk(x,z,context.minY,context.minY+sections.size()*16);
            chunk.brokenUntil=breakGrace.get(key);
            for(int section=0;section<sections.size();section++){
                int[] states=sections.get(section).states();if(states==null)continue;
                for(int index=0;index<states.length&&chunk.ground.size()<MAX_GROUND_PER_CHUNK&&retainedGrounds<MAX_GROUND_STATES;index++)
                    if(plainGround[states[index]]>=0||snowyGround[states[index]]>=0)sourceGround(chunk,(section<<12)|index,states[index]);
            }
            for(var entry:ownership.heights().entrySet()){
                int y=entry.getValue();if(y<chunk.minY||y>=chunk.maxY)continue;
                int[] states=sections.get((y-chunk.minY)>>4).states();if(states==null)continue;
                int local=((y-chunk.minY)<<8)|entry.getKey();
                if(states[local&4095]==snowId)chunk.originals.add(local);
            }
            if(retained+chunk.originals.size()>MAX_COLUMNS){retainedGrounds-=chunk.ground.size();return packet;}
            chunks.put(key,chunk);retained+=chunk.originals.size();
            syncPreviews(chunk,sections);chunk.previewShown.addAll(chunk.previewWanted);
            shownPreviews+=chunk.previewShown.size();
            boolean any=false;
            for(IntIterator positions=chunk.originals.iterator();positions.hasNext();){
                int local=positions.nextInt();if(hide(chunk,local,ownership)){hidden(chunk,local,true);any=true;}
            }
            for(var entry:chunk.ground.int2IntEntrySet())if(plainGround[entry.getIntValue()]>=0&&hideGround(chunk,entry.getIntKey(),ownership)){chunk.groundHidden.add(entry.getIntKey());any=true;}
            for(var entry:chunk.ground.int2IntEntrySet())if(snowyGround[entry.getIntValue()]>=0&&chunk.previewShown.contains(entry.getIntKey()+256)){chunk.groundPreview.add(entry.getIntKey());any=true;}
            any|=!chunk.previewShown.isEmpty();
            if(!any)return packet;
            byte[] mapped=LeafWireCodec.write(sections,registry.states.length,(section,index,original)->{
                int local=(section<<12)|index;
                return chunk.previewShown.contains(local)?chunk.previewPaint.get(local):chunk.hidden.contains(local)?airId:chunk.groundHidden.contains(local)?plainGround[original]:chunk.groundPreview.contains(local)?snowyGround[original]:original;
            },id->airStates[id]);
            return copyChunk(packet,old,mapped);
        }
        private int update(Chunk chunk,int x,int y,int z,int original,SnowOwnershipSnapshots.Snapshot ownership){
            int local=chunk.local(x,y,z),column=local&255;long now=System.nanoTime();
            var snapshot=preview(chunk);var candidate=snapshot!=null&&snapshot.melting()?snapshot.columns().get(column):null;
            boolean eligible=candidate!=null&&fresh(chunk,column,snapshot);
            boolean lower=eligible&&y==candidate.y(),upper=eligible&&y==candidate.y()+1&&candidate.upperState()>=0;
            boolean planned=eligible&&(lower&&(original==candidate.airState()||airStates[original])
                ||upper&&(original==candidate.upperState()||airStates[original])||y==candidate.y()-1&&supportState(original,candidate.supportState()));
            if(!planned){if(chunk.changedAt==null)chunk.changedAt=new long[256];chunk.changedAt[column]=now;}
            if(chunk.previewShown.remove(local)){shownPreviews--;if(airStates[original]&&!planned&&context!=null&&context.breakDelaySeconds>0)breakPreview(chunk,column,now);}
            if(chunk.previewAir.remove(local)>=0)retainedPreviews--;chunk.previewPaint.remove(local);chunk.previewWanted.remove(local);
            if(!planned)chunk.previewWanted.removeIf(value->(value&255)==column);
            boolean wasHidden=chunk.hidden.contains(local);if(chunk.originals.remove(local))retained--;hidden(chunk,local,false);
            if(original==snowId&&owns(chunk,local,ownership)&&retained<MAX_COLUMNS){chunk.originals.add(local);retained++;hidden(chunk,local,hide(chunk,local,ownership));}
            if(planned&&(lower||upper)&&retainedPreviews<MAX_COLUMNS){
                putPreview(chunk,local,original,lower?snowId:airId);
                if(chunk.previewWanted.contains(local)){chunk.previewShown.add(local);shownPreviews++;}
            }
            if(wasHidden!=chunk.hidden.contains(local)&&chunk.ground.containsKey(local-256)||!chunk.previewAir.isEmpty()||!chunk.groundPreview.isEmpty())queue(PaperBiomePackets.key(chunk.x,chunk.z));
            sourceGround(chunk,local,original);return painted(chunk,local,original,ownership);
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
                    int painted=painted(chunk,local,original,ownership);
                differs|=original!=painted;mapped.put(at[i],registry.states[painted]);
            }
            return differs?sectionConstructor.newInstance(section,mapped):packet;
        }
        public List<Object> refresh(int maxChunks,int maxBlocks)throws ReflectiveOperationException {
            expireGrace();
            List<Object> packets=new ArrayList<>();int changed=0,visited=0;
            for(int count=0;count<maxChunks&&pending()&&changed<maxBlocks;count++){
                var next=!fresh.isEmpty()&&(pending.isEmpty()||(priorityTurns++&3)!=3)?fresh:pending;
                var iterator=next.iterator();long key=iterator.next();iterator.remove();Chunk chunk=chunks.get(key);if(chunk==null)continue;
                visited++;
                var ownership=owners(chunk);Map<Integer,Short2ObjectOpenHashMap<Object>> updates=new LinkedHashMap<>();
                syncPreviews(chunk,null);
                for(IntIterator positions=chunk.originals.iterator();positions.hasNext();){
                    int local=positions.nextInt();
                    boolean hidden=hide(chunk,local,ownership);if(hidden==chunk.hidden.contains(local))continue;
                    if(changed>=maxBlocks){queue(key);break;}
                    int y=chunk.y(local);short at=(short)(((local&15)<<8)|(((local>>4)&15)<<4)|(y&15));
                    updates.computeIfAbsent(y>>4,ignored->new Short2ObjectOpenHashMap<>()).put(at,registry.states[hidden?airId:snowId]);
                    hidden(chunk,local,hidden);changed++;
                }
                for(IntIterator positions=chunk.previewAir.keySet().iterator();positions.hasNext();){
                    int local=positions.nextInt();boolean show=chunk.previewWanted.contains(local);
                    if(show!=chunk.previewShown.contains(local)){
                        if(changed>=maxBlocks){queue(key);break;}
                        int y=chunk.y(local);short at=(short)(((local&15)<<8)|(((local>>4)&15)<<4)|(y&15));
                        updates.computeIfAbsent(y>>4,ignored->new Short2ObjectOpenHashMap<>()).put(at,registry.states[show?chunk.previewPaint.get(local):chunk.previewAir.get(local)]);
                        if(show){chunk.previewShown.add(local);shownPreviews++;}else{chunk.previewShown.remove(local);shownPreviews--;}changed++;
                    }
                    if(!show){positions.remove();chunk.previewPaint.remove(local);retainedPreviews--;}
                }
                for(var entry:chunk.ground.int2IntEntrySet()){
                    int local=entry.getIntKey(),original=entry.getIntValue();
                    int shown=chunk.groundHidden.contains(local)?plainGround[original]:chunk.groundPreview.contains(local)?snowyGround[original]:original;
                    int desired=desiredGround(chunk,local,original,ownership);
                    if(shown==desired)continue;
                    if(changed>=maxBlocks){queue(key);break;}
                    int y=chunk.y(local);short at=(short)(((local&15)<<8)|(((local>>4)&15)<<4)|(y&15));
                    updates.computeIfAbsent(y>>4,ignored->new Short2ObjectOpenHashMap<>()).put(at,registry.states[paintGround(chunk,local,original,ownership)]);changed++;
                }
                for(var row:updates.entrySet())packets.add(sectionConstructor.newInstance(sectionOf.invoke(null,chunk.x,row.getKey(),chunk.z),row.getValue()));
            }
            if(context==null&&!pending())clear();
            lastRefreshChunks=visited;lastRefreshBlocks=changed;
            return packets;
        }
        public void forget(int x,int z){long key=PaperBiomePackets.key(x,z);removed(chunks.remove(key));fresh.remove(key);pending.remove(key);}
        public boolean pending(){return !pending.isEmpty()||!fresh.isEmpty();}
        public int lastRefreshChunks(){return lastRefreshChunks;}
        public int lastRefreshBlocks(){return lastRefreshBlocks;}
        public int retainedColumns(){return retained;}
        public int hiddenColumns(){return hiddenSnow;}
        public int retainedGroundStates(){return retainedGrounds;}
        public int previewColumns(){return shownPreviews;}
    }
    public View view(){return new View();}
    public int stateId(org.bukkit.block.data.BlockData state){
        try{return id(nativeState.invoke(state));}
        catch(ReflectiveOperationException failure){throw new IllegalStateException("Cannot read native preview state",failure);}
    }
    private int id(Object state){Integer result=ids.get(state);if(result==null)throw new IllegalArgumentException("Unknown native block state");return result;}
    private Object copyChunk(Object packet,Object old,byte[] bytes)throws ReflectiveOperationException {
        Object data=dataConstructor.newInstance(heightmaps.get(old),bytes,entities.get(old));extras.set(data,extras.get(old));data.getClass().getMethod("setReady",boolean.class).invoke(data,true);
        return chunkConstructor.newInstance(call(packet,"x"),call(packet,"z"),data,call(packet,"lightData"));
    }
    private static Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name);}
    private static Object call(Object object,String name)throws ReflectiveOperationException{return PaperBiomePackets.call(object,name);}
    private static Field field(Class<?> type,String name)throws ReflectiveOperationException{Field result=type.getDeclaredField(name);result.setAccessible(true);return result;}
}
