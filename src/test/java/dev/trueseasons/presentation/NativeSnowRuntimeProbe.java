package dev.trueseasons.presentation;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.event.connection.configuration.PlayerConnectionInitialConfigureEvent;
import java.lang.reflect.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;

/** Called only inside the disposable real-server verification; creates no player or world/chunk. */
public final class NativeSnowRuntimeProbe {
    private NativeSnowRuntimeProbe() {}
    public static int verify(JavaPlugin probe) throws Exception {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Run native runtime checks on the server thread");
        Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
        Object flow = type("net.minecraft.network.protocol.PacketFlow").getField("SERVERBOUND").get(null);
        Object connection = type("net.minecraft.network.Connection").getConstructor(flow.getClass()).newInstance(flow);
        EmbeddedChannel channel = new EmbeddedChannel(); channel.pipeline().addLast("packet_handler", new ChannelDuplexHandler());
        connection.getClass().getField("channel").set(connection, channel);
        Object profile = type("com.mojang.authlib.GameProfile").getConstructor(UUID.class, String.class).newInstance(UUID.randomUUID(), "NativeSnowProbe");
        Object cookie = type("net.minecraft.server.network.CommonListenerCookie").getMethod("createInitial", profile.getClass(), boolean.class).invoke(null, profile, false);
        Object listener = type("net.minecraft.server.network.ServerConfigurationPacketListenerImpl").getConstructor(type("net.minecraft.server.MinecraftServer"), connection.getClass(), cookie.getClass()).newInstance(server, connection, cookie);
        PlayerConfigurationConnection api = (PlayerConfigurationConnection) listener.getClass().getField("paperConnection").get(listener);
        PaperBiomePackets packets = new PaperBiomePackets();
        if (packets.channel(api) != channel) throw new AssertionError("Actual Paper configuration connection channel lookup");
        NativeSnowPresentation service = new NativeSnowPresentation(probe, world -> true);
        try {
            service.configure(new PlayerConnectionInitialConfigureEvent(api));
            Object biomeKey = type("net.minecraft.resources.ResourceKey").getMethod("createRegistryKey", type("net.minecraft.resources.Identifier")).invoke(null, identifier("minecraft:worldgen/biome"));
            Object entry = type("net.minecraft.core.RegistrySynchronization$PackedRegistryEntry").getConstructor(type("net.minecraft.resources.Identifier"), Optional.class).newInstance(identifier("minecraft:desert"), Optional.empty());
            Object packet = type("net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket").getConstructor(biomeKey.getClass(), List.class).newInstance(biomeKey, List.of(entry));
            channel.writeOutbound(packet);
            Object rewritten = channel.readOutbound();
            List<?> entries = (List<?>) call(rewritten, "entries");
            if (entries.size() != 2) throw new AssertionError("Initial configuration handler emitted native alias");
            if (!((Optional<?>) call(entries.getFirst(), "data")).isEmpty()) throw new AssertionError("Original known-pack entry retained");
            Object data = ((Optional<?>) call(entries.get(1), "data")).orElseThrow();
            if ((float) data.getClass().getMethod("getFloatOr", String.class, float.class).invoke(data, "temperature", 999.0f) != -1.0f) throw new AssertionError("Authoritative missing known-pack payload materialized and cold");
            if (!(boolean) data.getClass().getMethod("getBooleanOr", String.class, boolean.class).invoke(data, "has_precipitation", false)) throw new AssertionError("Dry real desert has client native precipitation");
            PlayerGameConnection disconnected = (PlayerGameConnection) Proxy.newProxyInstance(PlayerGameConnection.class.getClassLoader(), new Class<?>[]{PlayerGameConnection.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("isConnected")) return false;
                throw new AssertionError("Disconnected connection should not be used: " + method.getName());
            });
            Player leaving = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("getConnection")) return disconnected;
                throw new AssertionError("Disconnecting player must not trigger reconnect warning/state lookup: " + method.getName());
            });
            service.join(new PlayerJoinEvent(leaving, (net.kyori.adventure.text.Component) null));
            probe.getLogger().info("PASS: Native snowfall actual Paper configuration channel and authoritative known-pack climate materialization");
            probe.getLogger().info("PASS: Disconnecting player is ignored before reconnect warning or seasonal state lookup");
            return 6;
        } finally { service.close(); channel.finishAndReleaseAll(); }
    }
    private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name); }
    private static Object identifier(String id) throws Exception { return type("net.minecraft.resources.Identifier").getMethod("parse", String.class).invoke(null, id); }
    private static Object call(Object target, String method) throws Exception { return target.getClass().getMethod(method).invoke(target); }
}
