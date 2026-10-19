package dev.trueseasons.presentation;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.*;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.biome.Biomes;
import com.github.retrooper.packetevents.util.mappings.*;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerRegistryData;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerRegistryData.RegistryElement;
import io.netty.buffer.*;
import io.netty.channel.Channel;
import java.lang.invoke.*;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.*;

/** Observes configuration metadata only. Never edits, cancels, sends or routes a packet. */
final class PacketEventsRegistryCompatibility extends PacketListenerAbstract implements PacketRegistryCompatibility {
    private final Set<Channel> channels=ConcurrentHashMap.newKeySet();
    private final Logger logger;
    private final Object nativeCodec;
    private final Constructor<?> friendly;
    private final MethodHandle encode;
    private volatile boolean retiring;
    private List<RegistryElement> aliases;
    private IRegistry<?> projection;
    private WrapperConfigServerRegistryData canonical;
    private Map<com.github.retrooper.packetevents.resources.ResourceLocation,RegistryElement> canonicalElements;

    PacketEventsRegistryCompatibility(Logger logger) throws ReflectiveOperationException {
        super(PacketListenerPriority.LOWEST);this.logger=logger;
        nativeCodec=Class.forName("net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket").getField("STREAM_CODEC").get(null);
        friendly=Class.forName("net.minecraft.network.FriendlyByteBuf").getConstructor(ByteBuf.class);
        encode=MethodHandles.publicLookup().unreflect(Class.forName("net.minecraft.network.codec.StreamCodec").getMethod("encode",Object.class,Object.class));
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }
    @Override public void track(Channel channel){
        if(retiring)throw new IllegalStateException("Registry compatibility is retiring");
        if(channels.add(channel))channel.closeFuture().addListener(ignored->{channels.remove(channel);unregisterIfEmpty();});
    }
    @Override public void original(Channel channel,Object packet,PaperBiomePackets nativePackets) throws ReflectiveOperationException {
        if(!channels.contains(channel))return;
        var user=PacketEvents.getAPI().getProtocolManager().getUser(channel);
        if(user==null)throw new IllegalStateException("PacketEvents configuration user unavailable");
        Object key=PacketEvents.getAPI().getServerManager().getRegistryCacheKey(user,ClientVersion.V_26_3);
        if(key==null)return;
        var entry=SynchronizedRegistriesHandler.getRegistryEntry(Biomes.getRegistry().getRegistryKey());
        WrapperConfigServerRegistryData source;
        synchronized(this){
            if(canonical==null){
                canonical=readNative(nativePackets.materializedRegistry(packet));
                Map<com.github.retrooper.packetevents.resources.ResourceLocation,RegistryElement> base=new HashMap<>();
                for(RegistryElement element:canonical.getElements())base.put(element.getId(),element);
                canonicalElements=Map.copyOf(base);
            }
            source=canonical;
        }
        if(entry.getSyncedRegistry(key)!=null)return;
        // Populate an empty shared cache with real server biomes before the native handler's
        // expanded client registry reaches PacketEvents' internal configuration listener.
        // Existing shared entries owned by the library or another plugin are never replaced.
        entry.computeSyncedRegistry(key,()->entry.createFromElements(source.getElements(),source));
    }
    private WrapperConfigServerRegistryData readNative(Object packet) throws ReflectiveOperationException {
        ByteBuf bytes=Unpooled.buffer();
        try{
            try{encode.invoke(nativeCodec,friendly.newInstance(bytes),packet);}
            catch(Throwable error){if(error instanceof ReflectiveOperationException reflection)throw reflection;if(error instanceof RuntimeException runtime)throw runtime;if(error instanceof Error fatal)throw fatal;throw new ReflectiveOperationException(error);}
            var wrapper=new WrapperConfigServerRegistryData(Biomes.getRegistry().getRegistryKey(),List.of());
            wrapper.setServerVersion(ServerVersion.V_26_3);wrapper.setBuffer(bytes);
            try{wrapper.read();}finally{wrapper.setBuffer(null);}
            return wrapper;
        }finally{bytes.release();}
    }
    @Override public void onPacketSend(PacketSendEvent event){
        if(event.isCancelled()||event.getPacketType()!=PacketType.Configuration.Server.REGISTRY_DATA||!channels.contains(event.getChannel()))return;
        var wrapper=event.getLastUsedWrapper() instanceof WrapperConfigServerRegistryData previous?previous:new WrapperConfigServerRegistryData(event);
        if(!Biomes.getRegistry().getRegistryKey().equals(wrapper.getRegistryKey())||wrapper.getElements()==null)return;
        List<RegistryElement> elements=wrapper.getElements();int firstAlias=0;
        while(firstAlias<elements.size()&&!elements.get(firstAlias).getId().toString().startsWith("trueseasons:winter/"))firstAlias++;
        if(firstAlias==elements.size())return;
        try{
            IRegistry<?> current;
            synchronized(this){
                // Alias payloads contain the source biome definitions and palette. They are
                // complete even when the player's known-pack negotiation omits base payloads.
                // One immutable projection is shared across equal joins, and replaced on edits.
                List<RegistryElement> next=elements.subList(firstAlias,elements.size());
                if(aliases==null||!aliases.equals(next)){
                    var entry=SynchronizedRegistriesHandler.getRegistryEntry(wrapper.getRegistryKey());
                    List<RegistryElement> materialized=new ArrayList<>(elements);
                    for(int id=0;id<firstAlias;id++){
                        RegistryElement element=materialized.get(id);
                        if(element.getData()==null&&canonicalElements!=null&&canonicalElements.containsKey(element.getId()))materialized.set(id,canonicalElements.get(element.getId()));
                    }
                    projection=entry.createFromElements(materialized,wrapper);aliases=List.copyOf(next);
                }
                current=projection;
            }
            event.getUser().putRegistry(current);
        }catch(RuntimeException failure){
            logger.log(Level.SEVERE,"PacketEvents seasonal biome metadata could not synchronize",failure);
            ((Channel)event.getChannel()).close();
        }
    }
    @Override public void close(){retiring=true;unregisterIfEmpty();}
    private void unregisterIfEmpty(){
        if(retiring&&channels.isEmpty()){
            PacketEvents.getAPI().getEventManager().unregisterListener(this);
            synchronized(this){aliases=null;projection=null;canonical=null;canonicalElements=null;}
        }
    }
}
