package dev.trueseasons.snow;

import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.event.*;
import org.bukkit.event.world.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Main-thread ownership publication with read-only packet-thread views. */
public final class SnowOwnershipSnapshots implements Listener, AutoCloseable {
    private static final Integer[] COLUMN_KEYS=new Integer[256];
    static{for(int column=0;column<256;column++)COLUMN_KEYS[column]=column;}
    public record PreviewColumn(int y, int supportState, int airState,int upperState) {
        public PreviewColumn(int y,int supportState,int airState){this(y,supportState,airState,-1);}
    }
    public record Preview(String version, long checkedAt, Map<Integer, PreviewColumn> columns,boolean melting) {
        public Preview(String version,long checkedAt,Map<Integer,PreviewColumn> columns){this(version,checkedAt,columns,false);}
        public Preview { Objects.requireNonNull(version); columns = columns instanceof FrozenPreviews ? columns : Map.copyOf(columns); }
        public PreviewColumn column(int column){
            if(column<0||column>=256)return null;
            return columns instanceof FrozenPreviews frozen?frozen.values[column]:columns.get(COLUMN_KEYS[column]);
        }
        /** Avoid entry objects and boxed keys on repeated packet-thread refreshes. */
        public int nextColumn(int from){
            if(from<0)throw new IllegalArgumentException("Negative column");
            for(int column=from;column<256;column++)if(column(column)!=null)return column;
            return 256;
        }
    }
    /** A builder transfers its private array exactly once; packet readers receive only the frozen map. */
    static final class PreviewBuilder {
        private PreviewColumn[] values;
        private int size;
        private boolean built;
        void put(int column,PreviewColumn value){
            if(built)throw new IllegalStateException("Preview already published");
            if(column<0||column>=256)throw new IllegalArgumentException("Invalid preview column");
            Objects.requireNonNull(value);if(values==null)values=new PreviewColumn[256];if(values[column]==null)size++;values[column]=value;
        }
        Map<Integer,PreviewColumn> build(){
            if(built)throw new IllegalStateException("Preview already published");
            var result=size==0?Map.<Integer,PreviewColumn>of():size<=16?Map.copyOf(sparse()):new FrozenPreviews(values,size);values=null;built=true;return result;
        }
        private Map<Integer,PreviewColumn> sparse(){
            Map<Integer,PreviewColumn> result=new HashMap<>(size*2);
            for(int column=0;column<256;column++)if(values[column]!=null)result.put(column,values[column]);return result;
        }
    }
    private static final class FrozenPreviews extends AbstractMap<Integer,PreviewColumn> {
        private final PreviewColumn[] values;
        private final int size;
        FrozenPreviews(PreviewColumn[] values,int size){this.values=values;this.size=size;}
        @Override public int size(){return size;}
        @Override public PreviewColumn get(Object key){return key instanceof Integer column&&column>=0&&column<256?values[column]:null;}
        @Override public Set<Entry<Integer,PreviewColumn>> entrySet(){
            return new AbstractSet<>(){
                @Override public int size(){return size;}
                @Override public Iterator<Entry<Integer,PreviewColumn>> iterator(){
                    return new Iterator<>(){
                        int next=find(0);
                        private int find(int from){while(from<256&&values[from]==null)from++;return from;}
                        @Override public boolean hasNext(){return next<256;}
                        @Override public Entry<Integer,PreviewColumn> next(){
                            if(!hasNext())throw new NoSuchElementException();
                            int column=next;next=find(column+1);return new SimpleImmutableEntry<>(column,values[column]);
                        }
                    };
                }
            };
        }
    }
    public record Snapshot(Map<Integer, Integer> heights) {
        public static final Snapshot EMPTY = new Snapshot(Map.of());
        public Snapshot { heights = heights instanceof FrozenColumns ? heights : Map.copyOf(heights); }
        static Snapshot copyOf(SnowColumns columns){return columns.isEmpty()?EMPTY:new Snapshot(new FrozenColumns(columns));}
        /** Absent is outside the signed-short range used by saved ownership. */
        public int height(int column){
            if(heights.isEmpty())return Integer.MIN_VALUE;
            if(heights instanceof FrozenColumns frozen)return frozen.columns.contains(column)?frozen.columns.heightAt(column):Integer.MIN_VALUE;
            Integer value=heights.get(column);return value==null?Integer.MIN_VALUE:value;
        }
        public int nextColumn(int from){
            if(from<0)throw new IllegalArgumentException("Negative column");
            if(heights.isEmpty())return 256;
            if(heights instanceof FrozenColumns frozen)return frozen.columns.nextColumn(from);
            for(int column=from;column<256;column++)if(heights.containsKey(column))return column;
            return 256;
        }
    }
    /** Only this private type may bypass Map.copyOf: its mutable source is never retained. */
    private static final class FrozenColumns extends AbstractMap<Integer,Integer>{
        private final SnowColumns columns;
        FrozenColumns(SnowColumns columns){this.columns=columns.copy();}
        @Override public Integer get(Object key){return key instanceof Integer column?columns.get(column):null;}
        @Override public Set<Entry<Integer,Integer>> entrySet(){return columns.entries().entrySet();}
        @Override public int size(){return columns.entries().size();}
    }
    public static final class WorldView {
        private final PreviewCapacity capacity;
        public WorldView(){this(new PreviewCapacity());}
        private WorldView(PreviewCapacity capacity){this.capacity=capacity;}
        private final ConcurrentHashMap<Long, Snapshot> chunks = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, Preview> previews = new ConcurrentHashMap<>();
        public Preview preview(int x, int z) { return previews.get(key(x,z)); }
        private void preview(int x, int z, Preview preview) {
            long key=key(x,z);
            if (preview == null || preview.columns().isEmpty()){if(previews.remove(key)!=null)capacity.used--;}
            else if(previews.containsKey(key))previews.put(key,preview);
            else if(capacity.used<65_536){previews.put(key,preview);capacity.used++;}
        }
        /** Packet-thread safe; no Bukkit objects or terrain reads are retained. */
        public Snapshot snapshot(int x, int z) { return chunks.getOrDefault(key(x, z), Snapshot.EMPTY); }
        private boolean observed(int x, int z) { return chunks.containsKey(key(x, z)); }
        private void publish(int x, int z, Snapshot snapshot) { chunks.put(key(x, z), snapshot); }
        private void forget(int x, int z) { chunks.remove(key(x, z)); preview(x,z,null); }
        private void clear() { chunks.clear(); capacity.used-=previews.size();previews.clear(); }
    }
    private static final class PreviewCapacity{int used;}
    private static final WorldView EMPTY_WORLD = new WorldView();
    private final ConcurrentHashMap<UUID, WorldView> worlds = new ConcurrentHashMap<>();
    private final PreviewCapacity previewCapacity=new PreviewCapacity();
    private final SeasonalSnowService snow;
    private final Consumer<Chunk> onChanged;
    private final Logger logger;
    private final BiConsumer<Chunk, SnowColumns> observer = this::publish;
    // Bounded by loaded chunks, with removal on successful reads and unload.
    private final Map<UUID, Set<Long>> warned = new HashMap<>();
    private volatile boolean closed;

    public SnowOwnershipSnapshots(JavaPlugin plugin, SeasonalSnowService snow, Consumer<Chunk> onChanged) {
        this(snow, onChanged, plugin.getLogger());
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (World world : Bukkit.getWorlds()) for (Chunk chunk : world.getLoadedChunks()) capture(chunk);
    }
    SnowOwnershipSnapshots(SeasonalSnowService snow, Consumer<Chunk> onChanged, Logger logger) {
        this.snow = Objects.requireNonNull(snow);
        this.onChanged = Objects.requireNonNull(onChanged);
        this.logger = Objects.requireNonNull(logger);
        snow.setOwnershipObserver(observer);
    }
    /** Stable view for this loaded world; retaining it never retains the Bukkit world. */
    public WorldView world(UUID id) {
        Objects.requireNonNull(id);
        return closed ? EMPTY_WORLD : worlds.computeIfAbsent(id, ignored -> new WorldView(previewCapacity));
    }
    /** Main-thread transient publication; never writes chunk ownership or world data. */
    public void preview(UUID world, int x, int z, Preview preview) {
        if (!closed) world(world).preview(x,z,preview);
    }
    private static long key(int x, int z) {
        // Mix the bijective packed coordinate before Long.hashCode: nearby grid
        // diagonals otherwise share a hash and force reflective tree-bin lookups.
        long value=((long)x<<32)|(z&0xffffffffL);
        value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;
        value=(value^(value>>>27))*0x94d049bb133111ebL;
        return value^(value>>>31);
    }
    private void publish(Chunk chunk, SnowColumns columns) {
        if (closed) return;
        UUID id = chunk.getWorld().getUID();
        Snapshot snapshot = Snapshot.copyOf(columns);
        world(id).publish(chunk.getX(), chunk.getZ(), snapshot);
        Set<Long> failures = warned.get(id);
        if (failures != null) {
            failures.remove(key(chunk.getX(), chunk.getZ()));
            if (failures.isEmpty()) warned.remove(id);
        }
        notifyChanged(chunk);
    }
    private void notifyChanged(Chunk chunk) {
        try { onChanged.accept(chunk); }
        catch (RuntimeException failure) {
            logger.warning("Could not refresh snow presentation for chunk " + chunk.getX() + "," + chunk.getZ()
                + ": " + failure.getMessage());
        }
    }
    private void capture(Chunk chunk) {
        if (closed) return;
        try { snow.pruneMelt(chunk);publish(chunk, snow.observeOwnership(chunk)); }
        catch (IOException failure) {
            UUID id = chunk.getWorld().getUID();
            world(id).publish(chunk.getX(), chunk.getZ(), Snapshot.EMPTY);
            if (warned.computeIfAbsent(id, ignored -> new HashSet<>()).add(key(chunk.getX(), chunk.getZ())))
                logger.warning("Snow presentation skipped unreadable ownership in " + chunk.getWorld().getName()
                    + " chunk " + chunk.getX() + "," + chunk.getZ() + ": " + failure.getMessage());
            notifyChanged(chunk);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(ChunkLoadEvent event) { capture(event.getChunk()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerLoad(PlayerChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        if (!world(chunk.getWorld().getUID()).observed(chunk.getX(), chunk.getZ())) capture(chunk);
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void onPlayerUnload(PlayerChunkUnloadEvent event){
        Chunk chunk=event.getChunk();
        UUID leaving=event.getPlayer().getUniqueId();
        for(var player:chunk.getPlayersSeeingChunk())if(!player.getUniqueId().equals(leaving))return;
        WorldView view=worlds.get(chunk.getWorld().getUID());if(view!=null)view.preview(chunk.getX(),chunk.getZ(),null);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk(); UUID id = chunk.getWorld().getUID();
        WorldView view = worlds.get(id);
        if (view != null) view.forget(chunk.getX(), chunk.getZ());
        Set<Long> failures = warned.get(id);
        if (failures != null) {
            failures.remove(key(chunk.getX(), chunk.getZ()));
            if (failures.isEmpty()) warned.remove(id);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        UUID id = event.getWorld().getUID(); WorldView old = worlds.remove(id);
        if (old != null) old.clear();
        warned.remove(id);
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        snow.removeOwnershipObserver(observer);
        HandlerList.unregisterAll(this);
        worlds.values().forEach(WorldView::clear);
        worlds.clear(); warned.clear();
    }
}
