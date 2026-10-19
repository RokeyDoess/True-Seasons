package dev.trueseasons.presentation;

import dev.trueseasons.api.Season;
import dev.trueseasons.climate.SeasonClimatePolicy;
import dev.trueseasons.config.SnowPresentationLimits;
import dev.trueseasons.config.LeafPresentationLimits;
import dev.trueseasons.snow.SeasonalSnowService;
import dev.trueseasons.snow.SnowOwnershipSnapshots;

import io.netty.channel.*;
import io.papermc.paper.event.connection.configuration.PlayerConnectionInitialConfigureEvent;
import io.papermc.paper.event.connection.configuration.PlayerConnectionReconfigureEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.IntPredicate;
import dev.trueseasons.visual.SeasonVisualResolver;
import dev.trueseasons.visual.SeasonColorFrame;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Seasonal client colors and native snowfall using private biome aliases; saved world data stays untouched. */
public final class NativeSnowPresentation implements Listener, AutoCloseable {
    private static final String HANDLER = "trueseasons_native_biomes";
    private final JavaPlugin plugin;
    private final Predicate<World> winter;
    private final Supplier<SeasonClimatePolicy> climate;
    private final Function<World, Season> colors;
    private final Supplier<SeasonVisualResolver> visuals;
    private Supplier<SeasonColorFrame> colorFrames=()->null;
    public void colorFrameSource(Supplier<SeasonColorFrame> frames){colorFrames=Objects.requireNonNull(frames);}
    private final PaperBiomePackets packets;
    private final PaperLeafPackets leafPackets;
    private final PaperSnowPackets snowPackets;
    private final PacketEventsBridge packetEvents;
    private final SnowOwnershipSnapshots snowOwnership;
    private final IntSupplier coverage;
    private final Supplier<String> version;
    private final Predicate<World> snowTransition;
    private final IntSupplier breakDelay;
    private volatile Map<String, PaperLeafPackets.Context> leafWorlds = Map.of();
    private volatile Map<String, PaperSnowPackets.Context> snowWorlds = Map.of();
    private final Map<UUID, Viewer> viewers = new ConcurrentHashMap<>();
    private final SnowPresentationBudget snowBudget=new SnowPresentationBudget();
    private final PresentationWorkBudget leafBudget=new PresentationWorkBudget(LeafPresentationLimits.DEFAULT.chunks(),LeafPresentationLimits.DEFAULT.states());
    public void snowUpdateLimits(SnowPresentationLimits limits){snowBudget.limits(limits);}
    public void leafUpdateLimits(LeafPresentationLimits limits){leafBudget.limits(limits.chunks(),limits.states());}
    private int nextViewer;
    private int nextLeafViewer;
    private final Predicate<World> snowMelting;
    private final Set<UUID> warned = new HashSet<>();
    private volatile boolean closed;
    public record PreviewBreak(String dimension,String version,int x,int y,int z) {}
    public record SnowDiagnostics(int viewers,int catchingUp,long previewColumns,long hiddenColumns,int recentChunks,int recentBlocks){}
    public SnowDiagnostics diagnostics(){
        int connected=0,pending=0;long previews=0,hidden=0;
        for(Viewer viewer:viewers.values())if(viewer.channel.isActive()&&!viewer.handler.retired){
            ClimateHandler handler=viewer.handler;connected++;
            if(handler.snowPending||handler.snowPumpQueued.get()||!handler.snowDirty.isEmpty()||!handler.snowFresh.isEmpty()
                    ||!Objects.equals(handler.snowWanted,handler.snowApplied))pending++;
            previews+=handler.snowPreviewColumns;hidden+=handler.snowHiddenColumns;
        }
        var usage=snowBudget.usage();return new SnowDiagnostics(connected,pending,previews,hidden,usage.chunks(),usage.units());
    }
    private java.util.function.Consumer<PreviewBreak> previewBreak=ignored->{};
    public void onPreviewBreak(java.util.function.Consumer<PreviewBreak> listener){previewBreak=Objects.requireNonNull(listener);}
    private void previewBroken(UUID viewer,PreviewBreak notice){
        if(closed)return;
        Bukkit.getScheduler().runTask(plugin,()->{
            if(closed||!version.get().equals(notice.version))return;
            Player player=Bukkit.getPlayer(viewer);
            if(player==null||!player.getWorld().getKey().asString().equals(notice.dimension)||!snowTransition.test(player.getWorld())&&!snowMelting.test(player.getWorld()))return;
            var eye=player.getEyeLocation();double dx=eye.getX()-notice.x-.5,dy=eye.getY()-notice.y-.5,dz=eye.getZ()-notice.z-.5;
            if(dx*dx+dy*dy+dz*dz<=64)previewBreak.accept(notice);
        });
    }

    /** The predicate must describe the whole winter season, independently of current weather or snow layers. */
    public NativeSnowPresentation(JavaPlugin plugin, Predicate<World> winter) throws ReflectiveOperationException {
        this(plugin, winter, () -> SeasonClimatePolicy.ALL);
    }
    public NativeSnowPresentation(JavaPlugin plugin, Predicate<World> winter, Supplier<SeasonClimatePolicy> climate) throws ReflectiveOperationException {
        this(plugin, winter, climate, world -> null, () -> null);
    }
    public NativeSnowPresentation(JavaPlugin plugin, Predicate<World> winter, Supplier<SeasonClimatePolicy> climate,
            Function<World, Season> colors, Supplier<SeasonVisualResolver> visuals) throws ReflectiveOperationException {
        this(plugin,winter,climate,colors,visuals,null,()->256,()->"",world->false,()->60);
    }
    public NativeSnowPresentation(JavaPlugin plugin, Predicate<World> winter, Supplier<SeasonClimatePolicy> climate,
            Function<World, Season> colors, Supplier<SeasonVisualResolver> visuals, SeasonalSnowService snow,
            IntSupplier coverage,Supplier<String> version,Predicate<World> snowTransition,IntSupplier breakDelay) throws ReflectiveOperationException {
        this(plugin,winter,climate,colors,visuals,snow,coverage,version,snowTransition,breakDelay,world->false);
    }
    public NativeSnowPresentation(JavaPlugin plugin, Predicate<World> winter, Supplier<SeasonClimatePolicy> climate,
            Function<World, Season> colors, Supplier<SeasonVisualResolver> visuals, SeasonalSnowService snow,
            IntSupplier coverage,Supplier<String> version,Predicate<World> snowTransition,IntSupplier breakDelay,Predicate<World> snowMelting) throws ReflectiveOperationException {
        this.plugin = Objects.requireNonNull(plugin); this.winter = Objects.requireNonNull(winter);
        this.climate = Objects.requireNonNull(climate);
        this.snowMelting=Objects.requireNonNull(snowMelting);
        this.colors = Objects.requireNonNull(colors); this.visuals = Objects.requireNonNull(visuals);
        packets = new PaperBiomePackets();
        this.coverage=Objects.requireNonNull(coverage);this.version=Objects.requireNonNull(version);this.snowTransition=Objects.requireNonNull(snowTransition);
        this.breakDelay=Objects.requireNonNull(breakDelay);
        leafPackets = visuals.get() == null && snow==null ? null : new PaperLeafPackets();
        snowPackets = snow==null ? null : new PaperSnowPackets(leafPackets);
        packetEvents = System.getProperty("trueseasons.packet-backend", "packetevents").equals("native") ? null : new PacketEventsBridge(plugin.getLogger());
        snowOwnership = snow==null ? null : new SnowOwnershipSnapshots(plugin,snow,this::snowChanged);
        if(snow!=null)snow.setMeltObserver(chunk->{
            if(closed||!snowMelting.test(chunk.getWorld()))return;
            if(chunk.getPlayersSeeingChunk().isEmpty()){snowOwnership.preview(chunk.getWorld().getUID(),chunk.getX(),chunk.getZ(),null);return;}
            try{previewSnow(chunk.getWorld().getUID(),chunk.getX(),chunk.getZ(),snow.meltPreview(chunk,version.get(),this::previewStateId,false));}
            catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
        });
        refreshLeafWorlds();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) warnReconnect(player);
    }
    private record Viewer(Channel channel, ClimateHandler handler) {}

    @EventHandler(priority = EventPriority.LOWEST)
    public void configure(PlayerConnectionInitialConfigureEvent event) {
        if (closed) return;
        try {
            Channel channel = packets.channel(event.getConnection());
            ClimateHandler existing=packetEvents==null?(channel.pipeline().get(HANDLER) instanceof ClimateHandler found?found:null):packetEvents.handler(channel);
            if (existing!=null) {
                UUID id = event.getConnection().getProfile().getId();
                viewers.put(id, new Viewer(channel, existing));
                if (closed) { viewers.remove(id); channel.eventLoop().execute(() -> existing.retire()); }
                return;
            }
            UUID id = event.getConnection().getProfile().getId();
            ClimateHandler handler = new ClimateHandler(packets, plugin.getLogger(), climate.get(), visuals, leafPackets, () -> leafWorlds,snowPackets,()->snowWorlds,notice->previewBroken(id,notice),snowBudget,leafBudget);
            if(packetEvents==null)channel.pipeline().addBefore("packet_handler", HANDLER, handler);
            else packetEvents.bind(channel,handler);
            viewers.put(id, new Viewer(channel, handler));
            if (closed) { viewers.remove(id); channel.eventLoop().execute(() -> handler.retire()); }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            plugin.getLogger().log(Level.SEVERE, "Native snowfall could not attach to the client connection; no particle fallback is used", failure);
            throw new IllegalStateException("Native snowfall connection setup failed", failure);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) { beginPresentationTick();update(event.getPlayer());updateLeaves(event.getPlayer()); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void reconfigure(PlayerConnectionReconfigureEvent event) {
        Viewer viewer = viewers.get(event.getConnection().getProfile().getId());
        // Other plugins can own configuration sessions. Only resume a refresh we requested.
        if (closed || viewer == null || !viewer.channel.isActive()
                || !viewer.handler.reconfigurationRequested || viewer.handler.reconfigurationResumed) return;
        viewer.handler.reconfigurationResumed = true;
        // The acknowledgment can arrive on Netty, but native setup fires synchronous Bukkit events.
        if (event.isAsynchronous()) Bukkit.getScheduler().runTask(plugin, () -> refreshConfiguration(event, viewer));
        else refreshConfiguration(event, viewer);
    }
    private void refreshConfiguration(PlayerConnectionReconfigureEvent event, Viewer viewer) {
        if (!event.getConnection().isConnected()) return;
        try {
            packets.refreshConfiguration(event.getConnection());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            viewer.handler.failedVisuals = visuals.get();
            viewer.handler.reconfigurationRequested = false;
            plugin.getLogger().log(Level.WARNING, "Changed seasonal colors could not refresh for "
                + event.getConnection().getProfile().getName() + "; reconnect once to load them", failure);
            // If native setup could not start, leave Paper's plugin-owned waiting phase.
            // Paper also guards this call when a configuration task is already running.
            event.getConnection().completeReconfiguration();
        }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void world(PlayerChangedWorldEvent event) { beginPresentationTick();update(event.getPlayer());updateLeaves(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        // Paper also fires quit while another plugin reconfigures a still-live connection.
        // Retain that viewer until its channel actually closes, so later joins keep seasonal control.
        warned.remove(event.getPlayer().getUniqueId());
    }

    public void tick() {
        if (closed) return;
        beginPresentationTick();
        refreshLeafWorlds();
        viewers.entrySet().removeIf(entry -> !entry.getValue().channel.isActive());
        var players=new ArrayList<>(Bukkit.getOnlinePlayers());
        int start=players.isEmpty()?0:Math.floorMod(nextViewer,players.size()),following=start+1;
        for(int offset=0;offset<players.size();offset++)if(update(players.get((start+offset)%players.size())))following=start+offset+1;
        nextViewer=following; // Resume after the last admitted viewer rather than favoring the same few.
        start=players.isEmpty()?0:Math.floorMod(nextLeafViewer,players.size());following=start+1;
        for(int offset=0;offset<players.size();offset++)if(updateLeaves(players.get((start+offset)%players.size())))following=start+offset+1;
        nextLeafViewer=following; // Foliage admission rotates independently from snow work.
    }
    private void beginPresentationTick(){int tick=Bukkit.getCurrentTick();snowBudget.beginTick(tick);leafBudget.beginTick(tick);}
    private boolean updateLeaves(Player player){
        if(!player.getConnection().isConnected())return false;
        Viewer viewer=viewers.get(player.getUniqueId());
        return viewer!=null&&viewer.handler.requestLeaves(leafContext(player.getWorld()));
    }
    private PaperLeafPackets.Context leafContext(World world) {
        SeasonVisualResolver resolver = visuals.get();
        Season season = colors.apply(world);
        SeasonColorFrame frame=colorFrames.get();
        int autumn=season==null?0:frame!=null&&frame.to()==season?frame.autumnCoverage():season==Season.AUTUMN?256:0;
        int spring=season==null?0:frame!=null&&frame.to()==season?frame.springCoverage():season==Season.SPRING?256:0;
        return new PaperLeafPackets.Context(world.getSeed(), world.getMinHeight(), world.getMaxHeight(),
            resolver != null && resolver.enabled() && resolver.autumnTrees() && autumn>0,autumn,
            resolver!=null&&resolver.enabled()&&resolver.springBlossoms()?spring:0,season==Season.SPRING,
            resolver==null?dev.trueseasons.visual.SpringBlossoms.DEFAULT:resolver.blossoms());
    }
    private void refreshLeafWorlds() {
        if (leafPackets == null) return;
        Map<String, PaperLeafPackets.Context> next = new HashMap<>();
        for (World world : Bukkit.getWorlds()) {
            PaperLeafPackets.Context info = leafContext(world);
            if (info != null) next.put(world.getKey().asString(), info);
        }
        if (!next.equals(leafWorlds)) leafWorlds = Map.copyOf(next);
        if(snowOwnership!=null){
            Map<String,PaperSnowPackets.Context> nextSnow=new HashMap<>();
            int shared=coverage.getAsInt();
            // Keep cheap chunk markers outside winter too: a transition can begin while
            // those chunks are already on-screen, without another full chunk packet.
            for(World world:Bukkit.getWorlds())
                nextSnow.put(world.getKey().asString(),snowContext(world,shared));
            if(!nextSnow.equals(snowWorlds))snowWorlds=Map.copyOf(nextSnow);
        }
    }
    private PaperSnowPackets.Context snowContext(World world,int shared){
        boolean melting=snowMelting.test(world),transition=snowTransition.test(world)||melting;
        return new PaperSnowPackets.Context(version.get(),transition?shared:256,world.getMinHeight(),world.getMaxHeight(),
            snowOwnership.world(world.getUID()),transition&&(melting||world.hasStorm()),breakDelay.getAsInt());
    }
    public int previewStateId(org.bukkit.block.data.BlockData data){return snowPackets.stateId(data);}
    public boolean previewMissing(Chunk chunk){return snowOwnership.world(chunk.getWorld().getUID()).preview(chunk.getX(),chunk.getZ())==null;}
    public void previewSnow(UUID worldId,int x,int z,SnowOwnershipSnapshots.Preview preview){
        if(closed||snowOwnership==null)return;
        boolean arriving=preview!=null&&snowOwnership.world(worldId).preview(x,z)==null;
        snowOwnership.preview(worldId,x,z,preview);
        World world=Bukkit.getWorld(worldId);if(world==null)return;
        long key=org.bukkit.Chunk.getChunkKey(x,z);
        for(Player player:world.getPlayers())if(PlayerChunkVisibility.sent(player,key)){
            Viewer viewer=viewers.get(player.getUniqueId());if(viewer!=null)viewer.handler.dirtySnow(x,z,arriving);
        }
    }
    private void snowChanged(Chunk chunk){
        for(Player player:chunk.getPlayersSeeingChunk()){
            Viewer viewer=viewers.get(player.getUniqueId());
            if(viewer!=null)viewer.handler.dirtySnow(chunk.getX(),chunk.getZ());
        }
    }
    /** Main-thread weather barrier; viewer state is published by its packet event loop. */
    public boolean snowRevealPending(World world){
        if(closed||snowOwnership==null)return false;
        PaperSnowPackets.Context desired=snowContext(world,coverage.getAsInt());
        String dimension=world.getKey().asString();
        for(Player player:world.getPlayers()){
            Viewer viewer=viewers.get(player.getUniqueId());
            if(viewer!=null&&viewer.channel.isActive()&&viewer.handler.snowRevealPending(desired,dimension,!snowMelting.test(world)))return true;
        }
        return false;
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void loaded(org.bukkit.event.world.WorldLoadEvent event) { refreshLeafWorlds(); }
    private boolean update(Player player) {
        // Netty can close a channel before Paper removes its player from the online list.
        // That short disconnect window does not mean the client missed configuration.
        if (!player.getConnection().isConnected()) return false;
        Viewer viewer = viewers.get(player.getUniqueId());
        if (viewer == null) viewer = recoverViewer(player);
        if (viewer == null) { warnReconnect(player); return false; }
        if (!viewer.channel.isActive()) return false;
        boolean desired = !closed && winter.test(player.getWorld());
        Season desiredColors = closed ? null : colors.apply(player.getWorld());
        SeasonColorFrame targetFrame=colorFrames.get();
        SeasonColorFrame desiredFrame=desiredColors==null?null:targetFrame!=null&&targetFrame.to()==desiredColors?targetFrame:SeasonColorFrame.settled(desiredColors);
        if(desiredFrame!=null)desiredFrame=desiredFrame.palette();
        SeasonClimatePolicy policy = climate.get();
        SeasonVisualResolver nextVisuals = visuals.get();
        boolean scheduled=viewer.handler.requestSnow(snowWorlds.get(player.getWorld().getKey().asString()));
        // Biome effects are immutable after configuration. A palette edit uses
        // Paper's normal reconfiguration handshake rather than asking for reconnects.
        if (viewer.handler.reconfigurationRequested && !viewer.handler.reconfigurationStarted
                && System.currentTimeMillis() - viewer.handler.reconfigurationRequestedAt >= 5000) {
            viewer.handler.reconfigurationRequested = false;
            viewer.handler.failedVisuals = nextVisuals;
            plugin.getLogger().warning("Changed seasonal colors require " + player.getName()
                + " to reconnect: this connection could not reenter configuration");
            player.sendMessage("TrueSeasons: please reconnect once to load the changed seasonal colors.");
        }
        if (viewer.handler.originalCount > 0 && nextVisuals != null
                && (viewer.handler.deliveredVisuals==null||!nextVisuals.registryFingerprint().equals(viewer.handler.deliveredVisuals.registryFingerprint())) && !viewer.handler.reconfigurationRequested) {
            if (!nextVisuals.equals(viewer.handler.failedVisuals)) {
                viewer.handler.reconfigurationRequested = true;
                viewer.handler.reconfigurationStarted = false;
                viewer.handler.reconfigurationResumed = false;
                viewer.handler.reconfigurationRequestedAt = System.currentTimeMillis();
                player.getConnection().reenterConfiguration();
                return scheduled;
            }
        }
        if (viewer.handler.requested == desired && viewer.handler.requestedColors == desiredColors
                && Objects.equals(viewer.handler.requestedColorFrame,desiredFrame)
                && policy.equals(viewer.handler.requestedPolicy)) return scheduled;
        viewer.handler.requested = desired;
        viewer.handler.requestedColors = desiredColors;
        viewer.handler.requestedColorFrame=desiredFrame;
        viewer.handler.requestedPolicy = policy;
        Viewer current = viewer;
        SeasonColorFrame paletteFrame=desiredFrame;
        current.channel.eventLoop().execute(() -> current.handler.setPresentation(desired, desiredColors, policy,paletteFrame));
        return scheduled;
    }
    private Viewer recoverViewer(Player player) {
        try {
            Channel channel = packets.channel(player.getConnection());
            ClimateHandler handler=packetEvents==null?(channel.pipeline().get(HANDLER) instanceof ClimateHandler found?found:null):packetEvents.handler(channel);
            if (channel.isActive() && handler!=null && handler.canBind()) {
                Viewer recovered = new Viewer(channel, handler);
                viewers.put(player.getUniqueId(), recovered);
                return recovered;
            }
        } catch (ReflectiveOperationException failure) {
            plugin.getLogger().log(Level.SEVERE, "Native snowfall could not inspect the live client connection", failure);
        }
        return null;
    }
    private void warnReconnect(Player player) {
        if (!player.getConnection().isConnected()) return;
        try { if (!packets.channel(player.getConnection()).isActive()) return; }
        catch (ReflectiveOperationException failure) { plugin.getLogger().log(Level.WARNING, "Could not inspect seasonal connection", failure); return; }
        if (!warned.add(player.getUniqueId())) return;
        plugin.getLogger().warning("Seasonal colors and snowfall require " + player.getName() + " to reconnect: their biome registry predates this plugin instance");
        player.sendMessage("TrueSeasons: please reconnect once to enable seasonal colors and native winter snowfall.");
    }
    public int activeViewers() { return (int) viewers.values().stream().filter(viewer -> viewer.handler.requested).count(); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        HandlerList.unregisterAll(this);
        for (Viewer viewer : viewers.values()) {
            viewer.handler.requested = false;
            if (!viewer.channel.isActive()) continue;
            // Client-only alias entries live until disconnect. Keep the inert palette-width adapter
            // so future vanilla global palettes still match that client's enlarged registry.
            Runnable restore = viewer.handler::retire;
            if (viewer.channel.eventLoop().inEventLoop()) restore.run();
            else viewer.channel.eventLoop().submit(restore).syncUninterruptibly();
        }
        viewers.clear(); warned.clear();
        if(packetEvents!=null)packetEvents.retire();
        if(snowOwnership!=null)snowOwnership.close();
    }

    /** All cached data came from actual outgoing packets, so restoration never loads/generates a chunk. */
    public static final class ClimateHandler extends ChannelDuplexHandler {
        private final PaperBiomePackets packets;
        private final Logger logger;
        private final Map<Long, byte[]> originals = new HashMap<>();
        private final PaperLeafPackets.View leaves;
        private final Supplier<Map<String, PaperLeafPackets.Context>> leafWorlds;
        private final PaperSnowPackets.View snow;
        private final Supplier<Map<String,PaperSnowPackets.Context>> snowWorlds;
        private final java.util.function.Consumer<PreviewBreak> previewBreak;
        private final SnowPresentationBudget snowBudget;
        private final PresentationWorkBudget leafBudget;
        private volatile PaperSnowPackets.Context snowWanted;
        private volatile boolean snowPending;
        private volatile PaperSnowPackets.Context snowApplied;
        private volatile boolean snowHidden;
        private volatile boolean snowPreviewed;
        private volatile int snowPreviewColumns,snowHiddenColumns;
        private volatile String dimension;
        private final Set<Long> snowDirty=ConcurrentHashMap.newKeySet();
        private final Set<Long> snowFresh=ConcurrentHashMap.newKeySet();
        private final java.util.concurrent.atomic.AtomicBoolean snowPumpQueued=new java.util.concurrent.atomic.AtomicBoolean();
        private IntPredicate autumnAllowed=id->true;
        private PaperLeafPackets.Context leafBase,leafFiltered;
        private IntPredicate leafFilter;
        private volatile PaperLeafPackets.Context leafWanted;
        private volatile PaperLeafPackets.Context leafApplied;
        private volatile boolean leafPending;
        private final java.util.concurrent.atomic.AtomicBoolean leafPumpQueued = new java.util.concurrent.atomic.AtomicBoolean();
        private ChannelHandlerContext context;
        private volatile int originalCount;
        private int registrySize;
        private volatile boolean retired;
        private boolean winter, configuring;
        private volatile boolean requested;
        private volatile SeasonClimatePolicy requestedPolicy;
        private volatile Season requestedColors;
        private volatile SeasonColorFrame requestedColorFrame;
        private volatile SeasonVisualResolver deliveredVisuals;
        private volatile boolean reconfigurationRequested;
        private volatile boolean reconfigurationStarted;
        private volatile boolean reconfigurationResumed;
        private long reconfigurationRequestedAt;
        private volatile SeasonVisualResolver failedVisuals;
        private final Supplier<SeasonVisualResolver> visuals;
        private Season colors;
        private SeasonColorFrame colorFrame;
        private SeasonClimatePolicy policy;
        private List<String> biomeKeys = List.of();
        private boolean[] allowed = new boolean[0];
        private int[] mapping = new int[0];
        public ClimateHandler(PaperBiomePackets packets, Logger logger) { this(packets, logger, SeasonClimatePolicy.ALL); }
        public ClimateHandler(PaperBiomePackets packets, Logger logger, SeasonClimatePolicy policy) {
            this(packets, logger, policy, () -> null);
        }
        public ClimateHandler(PaperBiomePackets packets, Logger logger, SeasonClimatePolicy policy, Supplier<SeasonVisualResolver> visuals) {
            this(packets, logger, policy, visuals, null, Map::of);
        }
        public ClimateHandler(PaperBiomePackets packets, Logger logger, SeasonClimatePolicy policy, Supplier<SeasonVisualResolver> visuals,
                PaperLeafPackets leafPackets, Supplier<Map<String, PaperLeafPackets.Context>> leafWorlds) {
            this(packets,logger,policy,visuals,leafPackets,leafWorlds,null,Map::of);
        }
        public ClimateHandler(PaperBiomePackets packets, Logger logger, SeasonClimatePolicy policy, Supplier<SeasonVisualResolver> visuals,
                PaperLeafPackets leafPackets, Supplier<Map<String, PaperLeafPackets.Context>> leafWorlds,
                PaperSnowPackets snowPackets,Supplier<Map<String,PaperSnowPackets.Context>> snowWorlds) {
            this(packets,logger,policy,visuals,leafPackets,leafWorlds,snowPackets,snowWorlds,ignored->{});
        }
        public ClimateHandler(PaperBiomePackets packets, Logger logger, SeasonClimatePolicy policy, Supplier<SeasonVisualResolver> visuals,
                PaperLeafPackets leafPackets, Supplier<Map<String, PaperLeafPackets.Context>> leafWorlds,
                PaperSnowPackets snowPackets,Supplier<Map<String,PaperSnowPackets.Context>> snowWorlds,java.util.function.Consumer<PreviewBreak> previewBreak) {
            this(packets,logger,policy,visuals,leafPackets,leafWorlds,snowPackets,snowWorlds,previewBreak,null);
        }
        ClimateHandler(PaperBiomePackets packets, Logger logger, SeasonClimatePolicy policy, Supplier<SeasonVisualResolver> visuals,
                PaperLeafPackets leafPackets, Supplier<Map<String, PaperLeafPackets.Context>> leafWorlds,
                PaperSnowPackets snowPackets,Supplier<Map<String,PaperSnowPackets.Context>> snowWorlds,java.util.function.Consumer<PreviewBreak> previewBreak,SnowPresentationBudget snowBudget) {
            this(packets,logger,policy,visuals,leafPackets,leafWorlds,snowPackets,snowWorlds,previewBreak,snowBudget,null);
        }
        ClimateHandler(PaperBiomePackets packets, Logger logger, SeasonClimatePolicy policy, Supplier<SeasonVisualResolver> visuals,
                PaperLeafPackets leafPackets, Supplier<Map<String, PaperLeafPackets.Context>> leafWorlds,
                PaperSnowPackets snowPackets,Supplier<Map<String,PaperSnowPackets.Context>> snowWorlds,java.util.function.Consumer<PreviewBreak> previewBreak,SnowPresentationBudget snowBudget,PresentationWorkBudget leafBudget) {
            this.packets = packets; this.logger = logger; this.policy = Objects.requireNonNull(policy);
            this.visuals = Objects.requireNonNull(visuals);
            this.leaves = leafPackets == null ? null : leafPackets.view(); this.leafWorlds = leafWorlds;
            this.snow=snowPackets==null?null:snowPackets.view();this.snowWorlds=snowWorlds;
            this.previewBreak=previewBreak;
            this.snowBudget=snowBudget;
            this.leafBudget=leafBudget;
        }
        private PaperLeafPackets.Context filtered(PaperLeafPackets.Context base){
            if(base==null)return null;
            if(!base.equals(leafBase)||leafFilter!=autumnAllowed){
                leafBase=base;leafFilter=autumnAllowed;
                IntPredicate nativeFilter=autumnAllowed;
                leafFiltered=new PaperLeafPackets.Context(base.seed(),base.minY(),base.maxY(),base.autumn(),id->nativeFilter.test(id)&&base.autumnBiomeAllowed().test(id),base.autumnCoverage(),base.springCoverage(),base.springFirst(),base.blossoms());
            }
            return leafFiltered;
        }
        private void dirtySnow(int x,int z){dirtySnow(x,z,false);}
        private void dirtySnow(int x,int z,boolean arriving){if(snow!=null&&(snowWanted!=null||snowPending))(arriving?snowFresh:snowDirty).add(PaperBiomePackets.key(x,z));}
        private boolean snowRevealPending(PaperSnowPackets.Context desired,String world){
            return snowRevealPending(desired,world,true);
        }
        private boolean snowRevealPending(PaperSnowPackets.Context desired,String world,boolean revealAll){
            if(snow==null||retired||!Objects.equals(dimension,world))return false;
            return !Objects.equals(snowApplied,desired)||snowPending||snowPreviewed&&(!snowDirty.isEmpty()||!snowFresh.isEmpty())
                ||revealAll&&desired.coverage()<256&&snowHidden;
        }
        private void publishSnow(){
            snowPending=snow.pending();snowHidden=snow.hiddenColumns()>0;snowPreviewed=snow.previewColumns()>0;
            snowPreviewColumns=snow.previewColumns();snowHiddenColumns=snow.hiddenColumns();
        }
        private boolean requestSnow(PaperSnowPackets.Context next){
            if(snow==null||context==null||!context.channel().isActive()||retired)return false;
            // A newer request may arrive while the previous pump is running. Retry
            // until that target was actually applied, even when it is already wanted.
            boolean changed=!Objects.equals(snowApplied,next);snowWanted=next;
            if((!changed&&!snowPending&&snowDirty.isEmpty()&&snowFresh.isEmpty()&&!snow.timedRefreshDue())||!snowPumpQueued.compareAndSet(false,true))return false;
            SnowPresentationBudget.Grant grant=snowBudget==null?null:snowBudget.reserve(8,next!=null&&next.previews()?1024:128);
            if(snowBudget!=null&&grant==null){snowPumpQueued.set(false);return false;}
            try{context.executor().execute(()->{
                try{
                    if(retired||!context.channel().isActive())return;
                    if(grant!=null&&!grant.begin())return;
                    PaperSnowPackets.Context applied=snowWanted;
                    snow.context(applied);
                    for(long key:snowFresh)if(snowFresh.remove(key))snow.prioritize((int)key,(int)(key>>32));
                    for(long key:snowDirty)if(snowDirty.remove(key))snow.dirty((int)key,(int)(key>>32));
                    int blocks=applied!=null&&applied.previews()?1024:128;
                    List<Object> updates=snow.refresh(grant==null?8:grant.chunks,grant==null?blocks:Math.min(blocks,grant.units));
                    for(Object update:updates)context.write(update);
                    if(!updates.isEmpty())context.flush();
                    publishSnow();snowApplied=applied;
                    if(grant!=null)grant.complete(snow.lastRefreshChunks(),snow.lastRefreshBlocks());
                }catch(ReflectiveOperationException|RuntimeException failure){fail(failure);}
                finally{if(grant!=null)grant.close();snowPumpQueued.set(false);}
            });}catch(RuntimeException failure){if(grant!=null)grant.close();snowPumpQueued.set(false);throw failure;}
            return true;
        }
        private boolean requestLeaves(PaperLeafPackets.Context next) {
            if (leaves == null || context == null || !context.channel().isActive() || retired) return false;
            boolean changed = !Objects.equals(leafApplied, next); leafWanted = next;
            if (!changed && !leafPending || !leafPumpQueued.compareAndSet(false,true)) return false;
            PresentationWorkBudget.Grant grant=leafBudget==null?null:leafBudget.reserve(8,1024);
            if(leafBudget!=null&&grant==null){leafPumpQueued.set(false);return false;}
            try{context.executor().execute(() -> {
                try {
                    if (retired || !context.channel().isActive()) return;
                    if(grant!=null&&!grant.begin())return;
                    PaperLeafPackets.Context applied=leafWanted;
                    leaves.context(filtered(applied));
                    List<Object> updates=grant==null?leaves.refresh(1):leaves.refresh(grant.chunks,grant.units);
                    for (Object update : updates) context.write(update);
                    if(!updates.isEmpty())context.flush();leafPending = leaves.pending();leafApplied=applied;
                    if(grant!=null)grant.complete(leaves.lastRefreshChunks(),leaves.lastRefreshStates());
                } catch (ReflectiveOperationException | RuntimeException failure) { fail(failure); }
                finally { if(grant!=null)grant.close();leafPumpQueued.set(false); }
            });}catch(RuntimeException failure){if(grant!=null)grant.close();leafPumpQueued.set(false);throw failure;}
            return true;
        }
        private void rebuildClimate() {
            allowed = new boolean[biomeKeys.size()];
            for (int id = 0; id < allowed.length; id++) allowed[id] = policy.applies(Season.WINTER, biomeKeys.get(id));
            boolean[] autumn=new boolean[biomeKeys.size()];
            for(int id=0;id<autumn.length;id++)autumn[id]=policy.applies(Season.AUTUMN,biomeKeys.get(id));
            autumnAllowed=id->id>=0&&id<autumn.length&&autumn[id];
            if(leaves!=null){leaves.context(filtered(leafWanted));leafPending=leaves.pending();}
            rebuildMapping();
        }
        private boolean winterBiome(int id) { return allowed[id]; }
        private int mappedBiome(int id) {
            return mapping[id];
        }
        private void rebuildMapping() {
            mapping = new int[biomeKeys.size()];
            for (int id = 0; id < mapping.length; id++) {
                boolean snowy = winter && winterBiome(id);
                boolean colored = colors != null && deliveredVisuals != null && deliveredVisuals.enabled()
                    && policy.applies(colors, biomeKeys.get(id));
                SeasonColorFrame frame=colorFrame==null&&colors!=null?SeasonColorFrame.settled(colors):colorFrame;
                if(colored&&!deliveredVisuals.gradualColors())frame=SeasonColorFrame.settled(colors);
                if(colored&&frame.paletteStep()==0&&!policy.applies(frame.from(),biomeKeys.get(id)))colored=false;
                mapping[id] = id + originalCount * (colored ? PaperBiomePackets.colorBank(frame,snowy) : snowy ? 1 : 0);
            }
        }
        @Override public void handlerAdded(ChannelHandlerContext context) { this.context = context; }
        private void retire() {
            setPresentation(false, null, policy);
            if (leaves != null) try {
                leaves.context(null);
                for (Object update : leaves.refresh(Integer.MAX_VALUE)) context.write(update);
                context.flush(); leaves.clear();
            } catch (ReflectiveOperationException | RuntimeException failure) { fail(failure); }
            if(snow!=null)try{
                snow.context(null);
                for(Object update:snow.refresh(Integer.MAX_VALUE,Integer.MAX_VALUE))context.write(update);
                context.flush();snow.clear();snowDirty.clear();snowFresh.clear();snowPending=false;snowHidden=false;snowPreviewed=false;snowHiddenColumns=0;snowPreviewColumns=0;snowApplied=null;
            }catch(ReflectiveOperationException|RuntimeException failure){fail(failure);}
            retired = true; originals.clear();
        }
        private boolean canBind() { return originalCount > 0 && !retired; }
        public void setWinter(boolean desired) {
            setClimate(desired, policy);
        }
        public void setClimate(boolean desired, SeasonClimatePolicy next) {
            setPresentation(desired, colors, next);
        }
        public void setPresentation(boolean desired, Season colorSeason, SeasonClimatePolicy next) {
            setPresentation(desired,colorSeason,next,colorSeason==null?null:SeasonColorFrame.settled(colorSeason));
        }
        public void setPresentation(boolean desired,Season colorSeason,SeasonClimatePolicy next,SeasonColorFrame frame){
            if(colorSeason!=null&&(frame==null||frame.to()!=colorSeason))throw new IllegalArgumentException("Color frame must match the season");
            boolean changed = !policy.equals(next);
            if (winter == desired && colors == colorSeason && Objects.equals(colorFrame,frame) && !changed) return;
            if (changed) { policy = next; rebuildClimate(); }
            winter = desired;
            colors = colorSeason;
            colorFrame=frame;
            rebuildMapping();
            if (configuring || originalCount == 0 || originals.isEmpty() || context == null || !context.channel().isActive()) return;
            try {
                // Bound individual packets; no world access and no cache mutation.
                Map<Long, byte[]> batch = new HashMap<>();
                for (var entry : originals.entrySet()) {
                    batch.put(entry.getKey(), entry.getValue());
                    if (batch.size() == 64) { context.write(packets.refresh(batch, originalCount, registrySize, this::mappedBiome)); batch.clear(); }
                }
                if (!batch.isEmpty()) context.write(packets.refresh(batch, originalCount, registrySize, this::mappedBiome));
                context.flush();
            } catch (ReflectiveOperationException | RuntimeException failure) { fail(failure); }
        }
        @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            try { context.write(rewrite(message), promise); }
            catch (ReflectiveOperationException | RuntimeException failure) { promise.setFailure(failure); fail(failure); }
        }
        @Override public void channelRead(ChannelHandlerContext context,Object message)throws Exception{
            try{
                if(snow!=null&&!retired){
                    Object correction=snow.dig(message);
                    if(correction!=null){
                        Object pos=PaperBiomePackets.call(correction,"getPos");
                        if(snowWanted!=null)previewBreak.accept(new PreviewBreak(dimension,snowWanted.version(),(int)PaperBiomePackets.call(pos,"getX"),(int)PaperBiomePackets.call(pos,"getY"),(int)PaperBiomePackets.call(pos,"getZ")));
                        if(snow.extraDigCorrection()!=null)context.write(snow.extraDigCorrection());
                        context.writeAndFlush(correction);publishSnow();
                        if(snow.consumedDig()){context.writeAndFlush(snow.acknowledgeDig(message));return;}
                    }
                }
                super.channelRead(context,message);
            }catch(ReflectiveOperationException|RuntimeException failure){fail(failure);}
        }
        private Object rewrite(Object message) throws ReflectiveOperationException {
            if (retired && originalCount == 0) return message;
            SeasonVisualResolver palette = visuals.get();
            PaperBiomePackets.RegistryResult registry = packets.registry(message, palette);
            if (registry != null) {
                deliveredVisuals = palette; originalCount = registry.originalCount(); registrySize = registry.registrySize();
                biomeKeys = registry.biomeKeys(); rebuildClimate(); originals.clear(); reconfigurationRequested = false;
                return registry.packet();
            }
            String simple = message.getClass().getSimpleName();
            if (simple.equals("ClientboundStartConfigurationPacket")) { configuring = true; reconfigurationStarted = true; originals.clear(); if(leaves!=null){leaves.clear();leafApplied=null;}if(snow!=null){snow.clear();snowDirty.clear();snowFresh.clear();snowPending=false;snowHidden=false;snowPreviewed=false;snowHiddenColumns=0;snowPreviewColumns=0;snowApplied=null;dimension=null;} }
            if (simple.equals("ClientboundLoginPacket")) configuring = false;
            if (simple.equals("ClientboundLoginPacket") || simple.equals("ClientboundRespawnPacket")) {
                originals.clear();
                if(leaves!=null||snow!=null){
                    Object spawn = PaperBiomePackets.call(message,"commonPlayerSpawnInfo");
                    dimension = PaperBiomePackets.call(PaperBiomePackets.call(spawn,"dimension"),"identifier").toString();
                    if(leaves!=null){leaves.clear();leafApplied=null;leafWanted = leafWorlds.get().get(dimension); leaves.context(filtered(leafWanted)); leafPending = false;}
                    if(snow!=null){snow.clear();snowDirty.clear();snowFresh.clear();snowWanted=snowWorlds.get().get(dimension);snow.context(snowWanted);snowPending=false;snowHidden=false;snowPreviewed=false;snowHiddenColumns=0;snowPreviewColumns=0;snowApplied=snowWanted;}
                }
            }
            if (simple.equals("ClientboundBundlePacket")) {
                List<Object> children = new ArrayList<>();
                for (Object child : (Iterable<?>) PaperBiomePackets.call(message, "subPackets")) children.add(rewrite(child));
                return message.getClass().getConstructor(Iterable.class).newInstance(children);
            }
            if (originalCount == 0) return message;
            // New source packets must use the latest requested season even when its
            // bounded repaint task is still waiting behind a joining chunk burst.
            if(leaves!=null&&!retired)leaves.context(filtered(leafWanted));
            if (packets.isChunk(message)) {
                Object painted=message;
                if(leaves!=null&&!retired){painted=leaves.chunk(painted);leafPending=leaves.pending();}
                if(snow!=null&&!retired){snow.context(snowWanted);painted=snow.chunk(painted);publishSnow();}
                var mapped = packets.chunk(painted, originalCount, registrySize, this::mappedBiome);
                if (!retired) originals.put(packets.chunkKey(message), mapped.originalBiomes());
                return mapped.packet();
            }
            if (packets.isBiomes(message)) {
                if(leaves!=null&&!retired){leaves.biomes(message);leafPending=leaves.pending();}
                var mapped = packets.biomes(message, originalCount, registrySize, this::mappedBiome);
                if (!retired) for (var entry : mapped.originals().entrySet()) if (originals.containsKey(entry.getKey())) originals.put(entry.getKey(), entry.getValue());
                return mapped.packet();
            }
            if (packets.isForget(message)) {
                long key=packets.forgottenKey(message);originals.remove(key);
                if(leaves!=null){leaves.forget((int)key,(int)(key>>32));leafPending=leaves.pending();}
                if(snow!=null){snow.forget((int)key,(int)(key>>32));snowDirty.remove(key);snowFresh.remove(key);publishSnow();}
            }
            Object painted=message;
            if (leaves != null && !retired) { painted=leaves.update(painted);leafPending=leaves.pending(); }
            if(snow!=null&&!retired){snow.context(snowWanted);painted=snow.update(painted);publishSnow();}
            return painted;
        }
        @Override public void channelInactive(ChannelHandlerContext context) throws Exception { originals.clear(); if(leaves!=null){leaves.clear();leafApplied=null;}if(snow!=null){snow.clear();snowDirty.clear();snowFresh.clear();snowPending=false;snowHidden=false;snowPreviewed=false;snowHiddenColumns=0;snowPreviewColumns=0;snowApplied=null;dimension=null;}super.channelInactive(context); }
        private void fail(Throwable failure) {
            logger.log(Level.SEVERE, "Native snowfall packet compatibility failed; closing affected connection to avoid invalid biome data", failure);
            if (context != null) context.close();
        }
    }
}
