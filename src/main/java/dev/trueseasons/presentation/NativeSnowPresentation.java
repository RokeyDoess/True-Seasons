package dev.trueseasons.presentation;

import dev.trueseasons.api.Season;
import dev.trueseasons.climate.SeasonClimatePolicy;

import io.netty.channel.*;
import io.papermc.paper.event.connection.configuration.PlayerConnectionInitialConfigureEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Native client snowfall using private climate aliases, with no block, weather or server-biome writes. */
public final class NativeSnowPresentation implements Listener, AutoCloseable {
    private static final String HANDLER = "trueseasons_native_biomes";
    private final JavaPlugin plugin;
    private final Predicate<World> winter;
    private final Supplier<SeasonClimatePolicy> climate;
    private final PaperBiomePackets packets;
    private final Map<UUID, Viewer> viewers = new ConcurrentHashMap<>();
    private final Set<UUID> warned = new HashSet<>();
    private volatile boolean closed;

    /** The predicate must describe the whole winter season, independently of current weather or snow layers. */
    public NativeSnowPresentation(JavaPlugin plugin, Predicate<World> winter) throws ReflectiveOperationException {
        this(plugin, winter, () -> SeasonClimatePolicy.ALL);
    }
    public NativeSnowPresentation(JavaPlugin plugin, Predicate<World> winter, Supplier<SeasonClimatePolicy> climate) throws ReflectiveOperationException {
        this.plugin = Objects.requireNonNull(plugin); this.winter = Objects.requireNonNull(winter);
        this.climate = Objects.requireNonNull(climate);
        packets = new PaperBiomePackets();
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
            ClimateHandler handler = new ClimateHandler(packets, plugin.getLogger(), climate.get());
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
        viewers.entrySet().removeIf(entry -> !entry.getValue().channel.isActive());
        for (Player player : Bukkit.getOnlinePlayers()) update(player);
    }
    private void update(Player player) {
        // Netty can close a channel before Paper removes its player from the online list.
        // That short disconnect window does not mean the client missed configuration.
        if (!player.getConnection().isConnected()) return;
        Viewer viewer = viewers.get(player.getUniqueId());
        if (viewer == null) viewer = recoverViewer(player);
        if (viewer == null) { warnReconnect(player); return; }
        if (!viewer.channel.isActive()) return;
        boolean desired = !closed && winter.test(player.getWorld());
        SeasonClimatePolicy policy = climate.get();
        if (viewer.handler.requested == desired && policy.sameWinter(viewer.handler.requestedPolicy)) return;
        viewer.handler.requested = desired;
        viewer.handler.requestedPolicy = policy;
        Viewer current = viewer;
        current.channel.eventLoop().execute(() -> current.handler.setClimate(desired, policy));
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
        if (!warned.add(player.getUniqueId())) return;
        plugin.getLogger().warning("Native snowfall requires " + player.getName() + " to reconnect: their biome registry predates this plugin instance");
        player.sendMessage("TrueSeasons: please reconnect once to enable native winter snowfall.");
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
        private ChannelHandlerContext context;
        private volatile int originalCount;
        private volatile boolean retired;
        private boolean winter, configuring;
        private volatile boolean requested;
        private volatile SeasonClimatePolicy requestedPolicy;
        private SeasonClimatePolicy policy;
        private List<String> biomeKeys = List.of();
        private boolean[] allowed = new boolean[0];
        public ClimateHandler(PaperBiomePackets packets, Logger logger) { this(packets, logger, SeasonClimatePolicy.ALL); }
        public ClimateHandler(PaperBiomePackets packets, Logger logger, SeasonClimatePolicy policy) {
            this.packets = packets; this.logger = logger; this.policy = Objects.requireNonNull(policy);
        }
        private void rebuildClimate() {
            allowed = new boolean[biomeKeys.size()];
            for (int id = 0; id < allowed.length; id++) allowed[id] = policy.applies(Season.WINTER, biomeKeys.get(id));
        }
        private boolean winterBiome(int id) { return allowed[id]; }
        @Override public void handlerAdded(ChannelHandlerContext context) { this.context = context; }
        private void retire() { setWinter(false); retired = true; originals.clear(); }
        private boolean canBind() { return originalCount > 0 && !retired; }
        public void setWinter(boolean desired) {
            setClimate(desired, policy);
        }
        public void setClimate(boolean desired, SeasonClimatePolicy next) {
            boolean changed = !policy.sameWinter(next);
            if (winter == desired && !changed) return;
            if (changed) { policy = next; rebuildClimate(); }
            winter = desired;
            if (configuring || originalCount == 0 || originals.isEmpty() || context == null || !context.channel().isActive()) return;
            try {
                // Bound individual packets; no world access and no cache mutation.
                Map<Long, byte[]> batch = new HashMap<>();
                for (var entry : originals.entrySet()) {
                    batch.put(entry.getKey(), entry.getValue());
                    if (batch.size() == 64) { context.write(packets.refresh(batch, originalCount, winter, this::winterBiome)); batch.clear(); }
                }
                if (!batch.isEmpty()) context.write(packets.refresh(batch, originalCount, winter, this::winterBiome));
                context.flush();
            } catch (ReflectiveOperationException | RuntimeException failure) { fail(failure); }
        }
        @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            try { context.write(rewrite(message), promise); }
            catch (ReflectiveOperationException | RuntimeException failure) { promise.setFailure(failure); fail(failure); }
        }
        private Object rewrite(Object message) throws ReflectiveOperationException {
            if (retired && originalCount == 0) return message;
            PaperBiomePackets.RegistryResult registry = packets.registry(message);
            if (registry != null) { originalCount = registry.originalCount(); biomeKeys = registry.biomeKeys(); rebuildClimate(); originals.clear(); return registry.packet(); }
            String simple = message.getClass().getSimpleName();
            if (simple.equals("ClientboundStartConfigurationPacket")) { configuring = true; originals.clear(); }
            if (simple.equals("ClientboundLoginPacket")) configuring = false;
            if (simple.equals("ClientboundLoginPacket") || simple.equals("ClientboundRespawnPacket")) originals.clear();
            if (simple.equals("ClientboundBundlePacket")) {
                List<Object> children = new ArrayList<>();
                for (Object child : (Iterable<?>) PaperBiomePackets.call(message, "subPackets")) children.add(rewrite(child));
                return message.getClass().getConstructor(Iterable.class).newInstance(children);
            }
            if (originalCount == 0) return message;
            if (packets.isChunk(message)) {
                var mapped = packets.chunk(message, originalCount, winter, this::winterBiome);
                if (!retired) originals.put(packets.chunkKey(message), mapped.originalBiomes());
                return mapped.packet();
            }
            if (packets.isBiomes(message)) {
                var mapped = packets.biomes(message, originalCount, winter, this::winterBiome);
                if (!retired) for (var entry : mapped.originals().entrySet()) if (originals.containsKey(entry.getKey())) originals.put(entry.getKey(), entry.getValue());
                return mapped.packet();
            }
            if (packets.isForget(message)) originals.remove(packets.forgottenKey(message));
            return message;
        }
        @Override public void channelInactive(ChannelHandlerContext context) throws Exception { originals.clear(); super.channelInactive(context); }
        private void fail(Throwable failure) {
            logger.log(Level.SEVERE, "Native snowfall packet compatibility failed; closing affected connection to avoid invalid biome data", failure);
            if (context != null) context.close();
        }
    }
}
