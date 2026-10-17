package dev.trueseasons.snow;

import dev.trueseasons.config.Settings;
import dev.trueseasons.presentation.PlayerChunkVisibility;
import java.io.IOException;
import java.util.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Bounded read-only planning for visible unfinished terrain. Physical edits belong to the region pass. */
public final class LocalWinterSnowService implements Listener, AutoCloseable {
    @FunctionalInterface public interface PreviewSink {
        void publish(UUID world,int x,int z,SnowOwnershipSnapshots.Preview preview);
    }
    private record Position(UUID world,int x,int z) {}
    private static final int CHUNKS_PER_PLAYER=4096, MAX_SERVER_CHUNKS=65_536, INSPECTIONS_PER_TICK=64;
    private static final long BUDGET_NANOS=1_000_000, REFRESH_NANOS=5_000_000_000L;
    private static final int[] ALL_COLUMNS=new int[256];
    static { for(int i=0;i<256;i++)ALL_COLUMNS[i]=i; }
    private final SeasonalSnowService snow;
    private final Supplier<Settings> settings;
    private final Supplier<String> version;
    private final Predicate<World> winterEnabled,active;
    private final SnowReplenishmentService grace;
    private final PreviewSink previews;
    private final ToIntFunction<BlockData> stateId;
    private final Map<Position,Long> checked=new HashMap<>();
    private final PreviewPriorityQueue<Position> fresh=new PreviewPriorityQueue<>(MAX_SERVER_CHUNKS);
    private List<Chunk> loaded=List.of();
    private int cursor,ticks;
    private int priorityTurns;
    private int maximumChunks=CHUNKS_PER_PLAYER;
    private String previousVersion;
    private boolean closed;
    private Predicate<World> melting=world->false;
    private Supplier<String> meltOrder=()->"";
    private Predicate<Chunk> previewMissing=chunk->false;
    public void previewMissing(Predicate<Chunk> missing){previewMissing=missing;}
    public void melting(Predicate<World> enabled,Supplier<String> order){melting=enabled;meltOrder=order;}
    public void invalidate(){clear();previousVersion=version.get();}
    public int pendingPreviews(){return fresh.size();}
    public int checkedPreviews(){return checked.size();}

    public LocalWinterSnowService(JavaPlugin plugin,SeasonalSnowService snow,Supplier<Settings> settings,
            Supplier<String> version,Predicate<World> winterEnabled,Predicate<World> active,IntSupplier coverage,
            SnowReplenishmentService grace,PreviewSink previews,ToIntFunction<BlockData> stateId) {
        this(snow,settings,version,winterEnabled,active,coverage,grace,previews,stateId);
        Bukkit.getPluginManager().registerEvents(this,plugin);
    }
    LocalWinterSnowService(SeasonalSnowService snow,Supplier<Settings> settings,Supplier<String> version,
            Predicate<World> winterEnabled,Predicate<World> active,IntSupplier coverage,SnowReplenishmentService grace,
            PreviewSink previews,ToIntFunction<BlockData> stateId) {
        this.snow=snow;this.settings=settings;this.version=version;this.winterEnabled=winterEnabled;
        this.active=active;this.grace=grace;this.previews=previews;this.stateId=stateId;
    }
    LocalWinterSnowService(SeasonalSnowService snow,Supplier<Settings> settings,Supplier<String> version,
            Predicate<World> winterEnabled,Predicate<World> active,IntSupplier coverage,SnowReplenishmentService grace,
            Consumer<Chunk> beforeWrite,Consumer<Chunk> afterWrite) {
        this(snow,settings,version,winterEnabled,active,coverage,grace,(world,x,z,preview)->{},data->0);
    }
    static boolean withinCoverage(int column,int x,int z,String version,int coverage) {
        if(coverage<0||coverage>256)throw new IllegalArgumentException("Invalid local snow coverage");
        return new SnowColumnOrder(x,z,version).rank(column)<coverage;
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void capNaturalSnow(BlockFormEvent event) {
        if(closed||event.getNewState().getType()!=Material.SNOW)return;
        var block=event.getBlock();World world=block.getWorld();
        if(!winterEnabled.test(world)||!world.isChunkLoaded(block.getX()>>4,block.getZ()>>4))return;
        if(!snow.isComplete(block.getChunk(),version.get()))event.setCancelled(true);
    }
    boolean ready(Chunk chunk) {
        return !closed&&chunk.isLoaded()&&active.test(chunk.getWorld())
            &&(melting.test(chunk.getWorld())||chunk.getWorld().hasStorm()&&!snow.isComplete(chunk,version.get()))&&!chunk.getPlayersSeeingChunk().isEmpty();
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void visible(PlayerChunkLoadEvent event){
        Chunk chunk=event.getChunk();
        if(closed||!winterEnabled.test(chunk.getWorld())&&!melting.test(chunk.getWorld()))return;
        var player=event.getPlayer();
        resetVersion();queueFresh(player.getUniqueId(),chunk,true,distance(player,chunk));
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event){fresh.removeViewer(event.getPlayer().getUniqueId());}
    private void resetVersion(){String current=version.get();if(!Objects.equals(current,previousVersion)){clear();previousVersion=current;}}
    private static int distance(org.bukkit.entity.Player player,Chunk chunk){
        int x=org.bukkit.Location.locToBlock(player.getX())>>4,z=org.bukkit.Location.locToBlock(player.getZ())>>4;
        return (int)Math.min(Integer.MAX_VALUE,Math.max(Math.abs((long)chunk.getX()-x),Math.abs((long)chunk.getZ()-z)));
    }
    private void queueFresh(UUID viewer,Chunk chunk,boolean arriving,int distance){
        Position pos=position(chunk);
        if(arriving&&previewMissing.test(chunk))checked.remove(pos);
        Long last=checked.get(pos);
        if((last==null||arriving&&System.nanoTime()-last>=REFRESH_NANOS)&&active.test(chunk.getWorld())
                &&(melting.test(chunk.getWorld())||chunk.getWorld().hasStorm()&&!snow.isComplete(chunk,version.get())))fresh.offer(viewer,pos,distance);
    }
    public void tick() throws IOException {
        if(closed)return;
        ticks++;resetVersion();String current=version.get();
        if(ticks==1||ticks%20==0) {
            var players=new ArrayList<>(Bukkit.getOnlinePlayers());
            maximumChunks=(int)Math.min(MAX_SERVER_CHUNKS,Math.max(CHUNKS_PER_PLAYER,(long)players.size()*CHUNKS_PER_PLAYER));
            // Share the emergency cap fairly instead of always favoring the first connected players.
            if(!players.isEmpty())Collections.rotate(players,(ticks/20)%players.size());
            Set<Chunk> visible=new LinkedHashSet<>();
            for(var player:players)if(winterEnabled.test(player.getWorld())||melting.test(player.getWorld()))
                collectVisible(player,visible,maximumChunks,chunk->queueFresh(player.getUniqueId(),chunk,false,distance(player,chunk)));
            loaded=List.copyOf(visible);
            Set<Position> keep=new HashSet<>();
            for(Chunk chunk:loaded)if(melting.test(chunk.getWorld())||!snow.isComplete(chunk,current))keep.add(position(chunk));
            fresh.retain(keep::contains);
            for(var it=checked.keySet().iterator();it.hasNext();) {
                Position old=it.next();if(!keep.contains(old)) { previews.publish(old.world,old.x,old.z,null);it.remove(); }
            }
        }
        long deadline=System.nanoTime()+Math.min(BUDGET_NANOS,(long)(settings.get().millis*1_000_000));
        for(int count=0;(!loaded.isEmpty()||!fresh.isEmpty())&&count<INSPECTIONS_PER_TICK&&System.nanoTime()<deadline;count++) {
            Chunk chunk;
            if(!fresh.isEmpty()&&(loaded.isEmpty()||(priorityTurns++&3)!=3)){
                Position pos=fresh.poll();World world=Bukkit.getWorld(pos.world);
                if(world==null||!world.isChunkLoaded(pos.x,pos.z))continue;
                chunk=world.getChunkAt(pos.x,pos.z,false);
            }else chunk=loaded.get(Math.floorMod(cursor++,loaded.size()));
            if(!ready(chunk))continue;
            Position pos=position(chunk);long now=System.nanoTime();
            if(now-checked.getOrDefault(pos,now-REFRESH_NANOS)<REFRESH_NANOS)continue;
            refresh(chunk,ALL_COLUMNS);
        }
    }
    static void collectVisible(org.bukkit.entity.Player player,Set<Chunk> visible,int maximum){
        collectVisible(player,visible,maximum,ignored->{});
    }
    static void collectVisible(org.bukkit.entity.Player player,Set<Chunk> visible,int maximum,Consumer<Chunk> observed){
        World world=player.getWorld();
        for(long key:PlayerChunkVisibility.sent(player)){
            if(visible.size()>=maximum)break;
            int x=(int)key,z=(int)(key>>32);
            // getSentChunks itself resolves wrappers through getChunkAt. Inspect the
            // sent keys first, so an unloading chunk cannot cause even a disk reload.
            if(world.isChunkLoaded(x,z)){Chunk chunk=world.getChunkAt(x,z,false);visible.add(chunk);observed.accept(chunk);}
        }
    }
    public void refresh(Chunk chunk,int[] columns) {
        if(!ready(chunk))return;
        Position pos=position(chunk);if(!checked.containsKey(pos)&&checked.size()>=maximumChunks)return;
        Integer height=chunk.getWorld().getGameRuleValue(GameRules.MAX_SNOW_ACCUMULATION_HEIGHT);
        long start=System.nanoTime();String current=version.get();
        if(melting.test(chunk.getWorld())){
            try{previews.publish(chunk.getWorld().getUID(),chunk.getX(),chunk.getZ(),snow.meltPreview(chunk,meltOrder.get(),stateId,true));checked.put(pos,start);}
            catch(IOException failure){previews.publish(chunk.getWorld().getUID(),chunk.getX(),chunk.getZ(),null);checked.put(pos,start);}
            return;
        }
        int[] available=grace==null?columns:grace.availableColumns(chunk,columns);
        var candidates=height!=null&&height<=0?Map.<Integer,SnowOwnershipSnapshots.PreviewColumn>of():snow.previewColumns(chunk,available,stateId);
        if(ready(chunk)&&current.equals(version.get())){
            previews.publish(chunk.getWorld().getUID(),chunk.getX(),chunk.getZ(),new SnowOwnershipSnapshots.Preview(current,start,candidates));
            checked.put(pos,start);
        }
    }
    /** Older live probes can invoke this method; local planning no longer places blocks. */
    public SeasonalSnowService.Result accumulate(Chunk chunk,int[] columns) {
        refresh(chunk,columns);return new SeasonalSnowService.Result(0,0,0,false);
    }
    private Position position(Chunk chunk){return new Position(chunk.getWorld().getUID(),chunk.getX(),chunk.getZ());}
    private void clear(){for(Position pos:checked.keySet())previews.publish(pos.world,pos.x,pos.z,null);checked.clear();fresh.clear();loaded=List.of();}
    @Override public void close(){closed=true;clear();HandlerList.unregisterAll(this);}
}
