package dev.trueseasons.presentation;

import dev.trueseasons.api.Season;
import dev.trueseasons.climate.SeasonClimatePolicy;

import io.netty.channel.*;
import io.papermc.paper.event.connection.configuration.PlayerConnectionInitialConfigureEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.Function;
import dev.trueseasons.visual.SeasonVisualResolver;
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
    private final PaperBiomePackets packets;
    private final PaperLeafPackets leafPackets;
    private volatile Map<String, PaperLeafPackets.Context> leafWorlds = Map.of();
    private final Map<UUID, Viewer> viewers = new ConcurrentHashMap<>();
    private final Set<UUID> warned = new HashSet<>();
    private volatile boolean closed;

    /** The predicate must describe the whole winter season, independently of current weather or snow layers. */
    public NativeSnowPresentation(JavaPlugin plugin, Predicate<World> winter) throws ReflectiveOperationException {
        this(plugin, winter, () -> SeasonClimatePolicy.ALL);
    }
    public NativeSnowPresentation(JavaPlugin plugin, Predicate<World> winter, Supplier<SeasonClimatePolicy> climate) throws ReflectiveOperationException {
        this(plugin, winter, climate, world -> null, () -> null);
    }
    public NativeSnowPresentation(JavaPlugin plugin, Predicate<World> winter, Supplier<SeasonClimatePolicy> climate,
            Function<World, Season> colors, Supplier<SeasonVisualResolver> visuals) throws ReflectiveOperationException {
        this.plugin = Objects.requireNonNull(plugin); this.winter = Objects.requireNonNull(winter);
        this.climate = Objects.requireNonNull(climate);
        this.colors = Objects.requireNonNull(colors); this.visuals = Objects.requireNonNull(visuals);
        packets = new PaperBiomePackets();
        leafPackets = visuals.get() == null ? null : new PaperLeafPackets();
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
            if (channel.pipeline().get(HANDLER) instanceof ClimateHandler existing) {
                UUID id = event.getConnection().getProfile().getId();
                viewers.put(id, new Viewer(channel, existing));
                if (closed) { viewers.remove(id); channel.eventLoop().execute(() -> existing.retire()); }
                return;
            }
            ClimateHandler handler = new ClimateHandler(packets, plugin.getLogger(), climate.get(), visuals, leafPackets, () -> leafWorlds);
            channel.pipeline().addBefore("packet_handler", HANDLER, handler);
            UUID id = event.getConnection().getProfile().getId();
            viewers.put(id, new Viewer(channel, handler));
            if (closed) { viewers.remove(id); channel.eventLoop().execute(() -> handler.retire()); }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            plugin.getLogger().log(Level.SEVERE, "Native snowfall could not attach to the client connection; no particle fallback is used", failure);
            throw new IllegalStateException("Native snowfall connection setup failed", failure);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) { update(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void world(PlayerChangedWorldEvent event) { update(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        // Paper also fires quit while another plugin reconfigures a still-live connection.
        // Retain that viewer until its channel actually closes, so later joins keep seasonal control.
        warned.remove(event.getPlayer().getUniqueId());
    }

    public void tick() {
        if (closed) return;
        refreshLeafWorlds();
        viewers.entrySet().removeIf(entry -> !entry.getValue().channel.isActive());
        for (Player player : Bukkit.getOnlinePlayers()) update(player);
    }
    private PaperLeafPackets.Context leafContext(World world) {
        SeasonVisualResolver resolver = visuals.get();
        Season season = colors.apply(world);
        return new PaperLeafPackets.Context(world.getSeed(), world.getMinHeight(), world.getMaxHeight(),
            resolver != null && resolver.enabled() && resolver.autumnTrees() && season == Season.AUTUMN);
    }
    private void refreshLeafWorlds() {
        if (leafPackets == null) return;
        Map<String, PaperLeafPackets.Context> next = new HashMap<>();
        for (World world : Bukkit.getWorlds()) {
            PaperLeafPackets.Context info = leafContext(world);
            if (info != null) next.put(world.getKey().asString(), info);
        }
        if (!next.equals(leafWorlds)) leafWorlds = Map.copyOf(next);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void loaded(org.bukkit.event.world.WorldLoadEvent event) { refreshLeafWorlds(); }
    private void update(Player player) {
        // Netty can close a channel before Paper removes its player from the online list.
        // That short disconnect window does not mean the client missed configuration.
        if (!player.getConnection().isConnected()) return;
        Viewer viewer = viewers.get(player.getUniqueId());
        if (viewer == null) viewer = recoverViewer(player);
        if (viewer == null) { warnReconnect(player); return; }
        if (!viewer.channel.isActive()) return;
        boolean desired = !closed && winter.test(player.getWorld());
        Season desiredColors = closed ? null : colors.apply(player.getWorld());
        SeasonClimatePolicy policy = climate.get();
        SeasonVisualResolver nextVisuals = visuals.get();
        viewer.handler.requestLeaves(leafContext(player.getWorld()));
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
                && !nextVisuals.equals(viewer.handler.deliveredVisuals) && !viewer.handler.reconfigurationRequested) {
            if (!nextVisuals.equals(viewer.handler.failedVisuals)) {
                viewer.handler.reconfigurationRequested = true;
                viewer.handler.reconfigurationStarted = false;
                viewer.handler.reconfigurationRequestedAt = System.currentTimeMillis();
                player.getConnection().reenterConfiguration();
                return;
            }
        }
        if (viewer.handler.requested == desired && viewer.handler.requestedColors == desiredColors
                && policy.equals(viewer.handler.requestedPolicy)) return;
        viewer.handler.requested = desired;
        viewer.handler.requestedColors = desiredColors;
        viewer.handler.requestedPolicy = policy;
        Viewer current = viewer;
        current.channel.eventLoop().execute(() -> current.handler.setPresentation(desired, desiredColors, policy));
    }
    private Viewer recoverViewer(Player player) {
        try {
            Channel channel = packets.channel(player.getConnection());
            if (channel.isActive() && channel.pipeline().get(HANDLER) instanceof ClimateHandler handler && handler.canBind()) {
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
    }

    /** All cached data came from actual outgoing packets, so restoration never loads/generates a chunk. */
    public static final class ClimateHandler extends ChannelDuplexHandler {
        private final PaperBiomePackets packets;
        private final Logger logger;
        private final Map<Long, byte[]> originals = new HashMap<>();
        private final PaperLeafPackets.View leaves;
        private final Supplier<Map<String, PaperLeafPackets.Context>> leafWorlds;
        private volatile PaperLeafPackets.Context leafWanted;
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
        private volatile SeasonVisualResolver deliveredVisuals;
        private volatile boolean reconfigurationRequested;
        private volatile boolean reconfigurationStarted;
        private long reconfigurationRequestedAt;
        private SeasonVisualResolver failedVisuals;
        private final Supplier<SeasonVisualResolver> visuals;
        private Season colors;
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
            this.packets = packets; this.logger = logger; this.policy = Objects.requireNonNull(policy);
            this.visuals = Objects.requireNonNull(visuals);
            this.leaves = leafPackets == null ? null : leafPackets.view(); this.leafWorlds = leafWorlds;
        }
        private void requestLeaves(PaperLeafPackets.Context next) {
            if (leaves == null || context == null || !context.channel().isActive() || retired) return;
            boolean changed = !Objects.equals(leafWanted, next); leafWanted = next;
            if (!changed && !leafPending || !leafPumpQueued.compareAndSet(false,true)) return;
            context.executor().execute(() -> {
                try {
                    if (retired || !context.channel().isActive()) return;
                    leaves.context(leafWanted);
                    for (Object update : leaves.refresh(1)) context.write(update);
                    context.flush(); leafPending = leaves.pending();
                } catch (ReflectiveOperationException | RuntimeException failure) { fail(failure); }
                finally { leafPumpQueued.set(false); }
            });
        }
        private void rebuildClimate() {
            allowed = new boolean[biomeKeys.size()];
            for (int id = 0; id < allowed.length; id++) allowed[id] = policy.applies(Season.WINTER, biomeKeys.get(id));
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
                mapping[id] = id + originalCount * (colored ? snowy && colors == Season.WINTER ? 6 : 2 + colors.ordinal() : snowy ? 1 : 0);
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
            boolean changed = !policy.equals(next);
            if (winter == desired && colors == colorSeason && !changed) return;
            if (changed) { policy = next; rebuildClimate(); }
            winter = desired;
            colors = colorSeason;
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
            if (simple.equals("ClientboundStartConfigurationPacket")) { configuring = true; reconfigurationStarted = true; originals.clear(); if(leaves!=null)leaves.clear(); }
            if (simple.equals("ClientboundLoginPacket")) configuring = false;
            if (simple.equals("ClientboundLoginPacket") || simple.equals("ClientboundRespawnPacket")) {
                originals.clear();
                if(leaves!=null){
                    leaves.clear();
                    Object spawn = PaperBiomePackets.call(message,"commonPlayerSpawnInfo");
                    String dimension = PaperBiomePackets.call(PaperBiomePackets.call(spawn,"dimension"),"identifier").toString();
                    leafWanted = leafWorlds.get().get(dimension); leaves.context(leafWanted); leafPending = false;
                }
            }
            if (simple.equals("ClientboundBundlePacket")) {
                List<Object> children = new ArrayList<>();
                for (Object child : (Iterable<?>) PaperBiomePackets.call(message, "subPackets")) children.add(rewrite(child));
                return message.getClass().getConstructor(Iterable.class).newInstance(children);
            }
            if (originalCount == 0) return message;
            if (packets.isChunk(message)) {
                var mapped = packets.chunk(message, originalCount, registrySize, this::mappedBiome);
                if (!retired) originals.put(packets.chunkKey(message), mapped.originalBiomes());
                if (leaves != null && !retired) { Object painted=leaves.chunk(mapped.packet());leafPending=leaves.pending();return painted; }
                return mapped.packet();
            }
            if (packets.isBiomes(message)) {
                var mapped = packets.biomes(message, originalCount, registrySize, this::mappedBiome);
                if (!retired) for (var entry : mapped.originals().entrySet()) if (originals.containsKey(entry.getKey())) originals.put(entry.getKey(), entry.getValue());
                return mapped.packet();
            }
            if (packets.isForget(message)) {
                long key=packets.forgottenKey(message);originals.remove(key);
                if(leaves!=null){leaves.forget((int)key,(int)(key>>32));leafPending=leaves.pending();}
            }
            if (leaves != null && !retired) { Object painted=leaves.update(message);leafPending=leaves.pending();return painted; }
            return message;
        }
        @Override public void channelInactive(ChannelHandlerContext context) throws Exception { originals.clear(); if(leaves!=null)leaves.clear();super.channelInactive(context); }
        private void fail(Throwable failure) {
            logger.log(Level.SEVERE, "Native snowfall packet compatibility failed; closing affected connection to avoid invalid biome data", failure);
            if (context != null) context.close();
        }
    }
}
