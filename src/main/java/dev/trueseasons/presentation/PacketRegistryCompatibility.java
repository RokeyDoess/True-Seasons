package dev.trueseasons.presentation;

import io.netty.channel.Channel;
import java.util.logging.Logger;

/** Optional metadata only; no PacketEvents types may appear in this native-facing contract. */
interface PacketRegistryCompatibility extends AutoCloseable {
    void track(Channel channel);
    void original(Channel channel,Object packet,PaperBiomePackets nativePackets) throws ReflectiveOperationException;
    @Override void close();
    static PacketRegistryCompatibility create(Logger logger) throws ReflectiveOperationException {
        // Loading the implementation only when the plugin is present also avoids verifier linkage
        // to its listener superclass on servers without PacketEvents.
        return (PacketRegistryCompatibility)Class.forName("dev.trueseasons.presentation.PacketEventsRegistryCompatibility")
            .getDeclaredConstructor(Logger.class).newInstance(logger);
    }
}
