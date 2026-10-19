package dev.trueseasons.presentation;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.*;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerRegistryData;
import com.github.retrooper.packetevents.util.mappings.SynchronizedRegistriesHandler;
import io.netty.buffer.*;
import io.netty.channel.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.*;
import org.bukkit.Bukkit;

/** Experimental transport only. Native seasonal algorithms are deliberately retained for comparison. */
final class PacketEventsBridge extends PacketListenerAbstract {
    private static final Set<PacketTypeCommon> OUTBOUND=Set.of(
        PacketType.Configuration.Server.REGISTRY_DATA,PacketType.Play.Server.JOIN_GAME,PacketType.Play.Server.RESPAWN,
        PacketType.Play.Server.CHUNK_DATA,PacketType.Play.Server.CHUNK_BIOMES,PacketType.Play.Server.UNLOAD_CHUNK,
        PacketType.Play.Server.BLOCK_CHANGE,PacketType.Play.Server.MULTI_BLOCK_CHANGE,PacketType.Play.Server.CONFIGURATION_START);
    private final Map<Channel,Binding> bindings=new ConcurrentHashMap<>();
    private final ThreadLocal<Frame> frames=new ThreadLocal<>();
    private final Map<ByteBuf,Binding> generated=new IdentityHashMap<>();
    private final Codec codec;
    private final Logger logger;
    private volatile boolean retiring;
    private static final class Frame {
        final Binding binding;final boolean outbound;Object output;boolean forwarded;
        Frame(Binding binding,boolean outbound){this.binding=binding;this.outbound=outbound;}
    }
    private final class Binding {
        final Channel channel;final NativeSnowPresentation.ClimateHandler handler;final ChannelHandlerContext context;
        Binding(Channel channel,NativeSnowPresentation.ClimateHandler handler){
            this.channel=channel;this.handler=handler;
            context=(ChannelHandlerContext)Proxy.newProxyInstance(PacketEventsBridge.class.getClassLoader(),new Class<?>[]{ChannelHandlerContext.class},(proxy,method,args)->{
                return switch(method.getName()){
                    case "channel" -> channel;
                    case "executor" -> channel.eventLoop();
                    case "name" -> "trueseasons_packetevents_bridge";
                    case "handler" -> handler;
                    case "isRemoved" -> !channel.isActive();
                    case "write","writeAndFlush" -> {
                        Frame current=frames.get();
                        if(current!=null&&current.binding==this&&current.outbound)current.output=args[0];
                        else send(this,args[0]);
                        ChannelPromise promise=args.length>1&&args[1] instanceof ChannelPromise p?p:channel.newPromise();promise.trySuccess();yield promise;
                    }
                    case "flush" -> {channel.flush();yield proxy;}
                    case "fireChannelRead" -> {Frame current=frames.get();if(current!=null)current.forwarded=true;yield proxy;}
                    case "fireChannelInactive" -> proxy;
                    case "close" -> channel.close();
                    case "toString" -> "TrueSeasons PacketEvents experimental context";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy==args[0];
                    default -> throw new UnsupportedOperationException("Experimental context: "+method.getName());
                };
            });
        }
    }
    PacketEventsBridge(Logger logger)throws ReflectiveOperationException{
        super(PacketListenerPriority.NORMAL);this.logger=logger;codec=new Codec();
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }
    void bind(Channel channel,NativeSnowPresentation.ClimateHandler handler){
        if(retiring)throw new IllegalStateException("Packet transport is retiring");
        Binding binding=new Binding(channel,handler);
        if(bindings.putIfAbsent(channel,binding)!=null)throw new IllegalStateException("Channel already has seasonal packet transport");
        channel.eventLoop().execute(()->handler.handlerAdded(binding.context));
        channel.closeFuture().addListener(ignored->{
            Binding old=bindings.remove(channel);if(old!=null)old.handler.channelInactive(old.context);
            synchronized(generated){generated.values().removeIf(value->value.channel==channel);}
            unregisterIfEmpty();
        });
    }
    NativeSnowPresentation.ClimateHandler handler(Channel channel){
        Binding binding=bindings.get(channel);return binding==null?null:binding.handler;
    }
    private void unregisterIfEmpty(){if(retiring&&bindings.isEmpty())PacketEvents.getAPI().getEventManager().unregisterListener(this);}
    void retire(){retiring=true;unregisterIfEmpty();}
    @Override public void onPacketSend(PacketSendEvent event){
        Binding binding=bindings.get((Channel)event.getChannel());if(binding==null)return;
        ByteBuf original=(ByteBuf)event.getByteBuf();synchronized(generated){if(generated.remove(original)!=null)return;}
        if(event.isCancelled()||!OUTBOUND.contains(event.getPacketType()))return;
        Frame prior=frames.get(),frame=new Frame(binding,true);frames.set(frame);
        try(ByteBufScope input=new ByteBufScope(full(event))){
            Object source=codec.decode(event.getConnectionState(),false,input.bytes);
            binding.handler.write(binding.context,source,binding.channel.newPromise());
            if(frame.output==null){event.setCancelled(true);return;}
            if(frame.output!=source)try(ByteBufScope output=new ByteBufScope(codec.encode(event.getConnectionState(),false,frame.output))){
                replace(event,output.bytes);
                if(event.getPacketType()==PacketType.Configuration.Server.REGISTRY_DATA){
                    // PacketEvents' LOWEST internal listener already observed the original registry.
                    // Refresh this user's typed view without poisoning the shared vanilla registry cache.
                    var registry=new WrapperConfigServerRegistryData(event);
                    if(registry.getElements()==null)throw new IllegalStateException("Expected modern registry elements");
                    SynchronizedRegistriesHandler.handleRegistry(event.getUser(),registry,registry.getRegistryKey(),registry.getElements(),null);
                    event.setLastUsedWrapper(null);
                }
            }
        }catch(Throwable error){failure(binding,event,error);}
        finally{if(prior==null)frames.remove();else frames.set(prior);}
    }
    @Override public void onPacketReceive(PacketReceiveEvent event){
        if(event.isCancelled()||event.getPacketType()!=PacketType.Play.Client.PLAYER_DIGGING)return;
        Binding binding=bindings.get((Channel)event.getChannel());if(binding==null)return;
        Frame prior=frames.get(),frame=new Frame(binding,false);frames.set(frame);
        try(ByteBufScope input=new ByteBufScope(full(event))){
            binding.handler.channelRead(binding.context,codec.decode(event.getConnectionState(),true,input.bytes));
            if(!frame.forwarded)event.setCancelled(true);
        }catch(Throwable error){failure(binding,event,error);}
        finally{if(prior==null)frames.remove();else frames.set(prior);}
    }
    private ByteBuf full(ProtocolPacketEvent event){
        ByteBuf full=Unpooled.buffer();PacketWrapper<?> wrapper=event.getLastUsedWrapper();
        try{
            if(wrapper!=null){Object old=wrapper.getBuffer();try{wrapper.setBuffer(full);wrapper.writeVarInt(event.getPacketId());wrapper.write();}finally{wrapper.setBuffer(old);}}
            else {writeVarInt(full,event.getPacketId());ByteBuf body=(ByteBuf)event.getByteBuf();full.writeBytes(body,body.readerIndex(),body.readableBytes());}
            return full;
        }catch(Throwable error){full.release();throw error;}
    }
    private static void replace(ProtocolPacketEvent event,ByteBuf encoded){
        int start=encoded.readerIndex(),id=readVarInt(encoded);if(id!=event.getPacketId())throw new IllegalStateException("Packet rewrite changed its type");
        int body=encoded.readerIndex()-start;encoded.readerIndex(start);ByteBuf target=(ByteBuf)event.getByteBuf();target.clear().writeBytes(encoded);target.readerIndex(body);
        // Later listeners must parse the new buffer, never reuse a stale pre-rewrite wrapper.
        event.setLastUsedWrapper(null);
    }
    private void send(Binding binding,Object packet)throws Exception{
        ByteBuf encoded=codec.encode(ConnectionState.PLAY,false,packet);
        if(!binding.channel.isActive()){encoded.release();return;}
        synchronized(generated){if(generated.size()>=65_536){encoded.release();throw new IllegalStateException("Experimental transport queue exceeded its limit");}generated.put(encoded,binding);}
        try{PacketEvents.getAPI().getProtocolManager().writePacket(binding.channel,encoded);}
        catch(Throwable error){synchronized(generated){generated.remove(encoded);}if(encoded.refCnt()>0)encoded.release();throw error;}
        // PacketEvents can hold writes during configuration. Keep identity until the actual
        // listener sees the message, or channel closure releases the bounded pending set.
    }
    private void failure(Binding binding,ProtocolPacketEvent event,Throwable error){
        event.setCancelled(true);logger.log(Level.SEVERE,"Experimental PacketEvents transport failed; closing affected connection",error);binding.channel.close();
    }
    private static void writeVarInt(ByteBuf out,int value){do{int next=value&127;value>>>=7;out.writeByte(next|(value==0?0:128));}while(value!=0);}
    private static int readVarInt(ByteBuf in){int value=0;for(int n=0;n<5;n++){int next=in.readUnsignedByte();value|=(next&127)<<(n*7);if((next&128)==0)return value;}throw new IllegalArgumentException("Oversized packet ID");}
    private static final class ByteBufScope implements AutoCloseable{
        final ByteBuf bytes;ByteBufScope(ByteBuf bytes){this.bytes=bytes;}public void close(){bytes.release();}
    }
    /** Codec access is native in this first transport experiment, not a claim of a completed wrapper port. */
    private static final class Codec {
        final Object configOut,gameOut,gameIn;
        final MethodHandle decode,encode;
        Codec()throws ReflectiveOperationException{
            Class<?> info=Class.forName("net.minecraft.network.ProtocolInfo"),stream=Class.forName("net.minecraft.network.codec.StreamCodec");
            Method getCodec=info.getMethod("codec");
            configOut=getCodec.invoke(Class.forName("net.minecraft.network.protocol.configuration.ConfigurationProtocols").getField("CLIENTBOUND").get(null));
            Object server=Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());Object access=server.getClass().getMethod("registryAccess").invoke(server);
            Object decorator=Class.forName("net.minecraft.network.RegistryFriendlyByteBuf").getMethod("decorator",Class.forName("net.minecraft.core.RegistryAccess")).invoke(null,access);
            Class<?> protocols=Class.forName("net.minecraft.network.protocol.game.GameProtocols");
            Object outTemplate=protocols.getField("CLIENTBOUND_TEMPLATE").get(null);
            Method bind=Class.forName("net.minecraft.network.protocol.SimpleUnboundProtocol").getMethod("bind",java.util.function.Function.class);
            gameOut=getCodec.invoke(bind.invoke(outTemplate,decorator));
            Class<?> context=Class.forName("net.minecraft.network.protocol.game.GameProtocols$Context");
            Object capabilities=Proxy.newProxyInstance(context.getClassLoader(),new Class<?>[]{context},(proxy,method,args)->switch(method.getName()){
                case "toString" -> "TrueSeasons native serverbound codec context";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy==args[0];
                default -> {
                    if(method.getReturnType()!=boolean.class)throw new UnsupportedOperationException("Unknown codec capability "+method.getName());
                    yield false;
                }
            });
            Object inTemplate=protocols.getField("SERVERBOUND_TEMPLATE").get(null);
            Method bindIn=Class.forName("net.minecraft.network.protocol.UnboundProtocol").getMethod("bind",java.util.function.Function.class,Object.class);
            gameIn=getCodec.invoke(bindIn.invoke(inTemplate,decorator,capabilities));
            decode=MethodHandles.publicLookup().unreflect(stream.getMethod("decode",Object.class));encode=MethodHandles.publicLookup().unreflect(stream.getMethod("encode",Object.class,Object.class));
        }
        Object protocol(ConnectionState state,boolean inbound){if(state==ConnectionState.CONFIGURATION&&!inbound)return configOut;if(state==ConnectionState.PLAY)return inbound?gameIn:gameOut;throw new IllegalArgumentException("Unsupported experimental state "+state);}
        Object decode(ConnectionState state,boolean inbound,ByteBuf bytes)throws Exception{try{return decode.invoke(protocol(state,inbound),bytes);}catch(Throwable error){if(error instanceof Exception exception)throw exception;throw new IllegalStateException(error);}}
        ByteBuf encode(ConnectionState state,boolean inbound,Object packet)throws Exception{ByteBuf bytes=Unpooled.buffer();try{encode.invoke(protocol(state,inbound),bytes,packet);return bytes;}catch(Throwable error){bytes.release();if(error instanceof Exception exception)throw exception;throw new IllegalStateException(error);}}
    }
}
