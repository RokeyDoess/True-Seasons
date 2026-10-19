package dev.trueseasons.presentation;

import io.netty.buffer.ByteBuf;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;

/** Narrow native decoding for ordinary encoded block updates entering our existing handler. */
final class NativeRawBlockUpdates {
    @FunctionalInterface interface Rewrite {
        Object apply(Object packet) throws ReflectiveOperationException;
    }
    private record Codec(Object stream,boolean section) {}
    private final Codec[] codecs;
    private final Object registries;
    private final Constructor<?> buffer;
    private final MethodHandle decode,encode;
    private static final int MAX_UPDATE_BYTES=4096*10+32;

    static NativeRawBlockUpdates forServer() throws ReflectiveOperationException {
        Object server=Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
        return new NativeRawBlockUpdates(server.getClass().getMethod("registryAccess").invoke(server));
    }
    NativeRawBlockUpdates(Object registries) throws ReflectiveOperationException {
        this.registries=registries;
        Class<?> stream=type("net.minecraft.network.codec.StreamCodec");
        decode=MethodHandles.publicLookup().unreflect(stream.getMethod("decode",Object.class));
        encode=MethodHandles.publicLookup().unreflect(stream.getMethod("encode",Object.class,Object.class));
        buffer=type("net.minecraft.network.RegistryFriendlyByteBuf").getConstructor(ByteBuf.class,type("net.minecraft.core.RegistryAccess"));
        Map<Integer,Codec> found=new HashMap<>();
        Class<?> visitor=type("net.minecraft.network.ProtocolInfo$Details$PacketVisitor");
        Object listing=Proxy.newProxyInstance(visitor.getClassLoader(),new Class<?>[]{visitor},(proxy,method,args)->{
            if(method.getName().equals("accept")){
                String key=type("net.minecraft.network.protocol.PacketType").getMethod("id").invoke(args[0]).toString();
                String packet=switch(key){
                    case "minecraft:block_update" -> "ClientboundBlockUpdatePacket";
                    case "minecraft:section_blocks_update" -> "ClientboundSectionBlocksUpdatePacket";
                    default -> null;
                };
                if(packet!=null)found.put((int)args[1],new Codec(type("net.minecraft.network.protocol.game."+packet).getField("STREAM_CODEC").get(null),key.equals("minecraft:section_blocks_update")));
                return null;
            }
            return switch(method.getName()){
                case "toString" -> "TrueSeasons block-update protocol listing";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy==args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
        Object template=type("net.minecraft.network.protocol.game.GameProtocols").getField("CLIENTBOUND_TEMPLATE").get(null);
        Object details=type("net.minecraft.network.protocol.SimpleUnboundProtocol").getMethod("details").invoke(template);
        type("net.minecraft.network.ProtocolInfo$Details").getMethod("listPackets",visitor).invoke(details,listing);
        if(found.size()!=2)throw new IllegalStateException("Native block-update packet IDs unavailable");
        codecs=new Codec[found.keySet().stream().mapToInt(Integer::intValue).max().orElseThrow()+1];
        found.forEach((id,codec)->codecs[id]=codec);
    }

    /** Input ownership and indices stay with the caller; unchanged packets retain their original buffer. */
    ByteBuf rewrite(ByteBuf source,Rewrite rewrite) throws ReflectiveOperationException {
        if(!source.isReadable())return source;
        int index=source.readerIndex(),id=0,header=0;
        for(int n=0;n<5&&index+n<source.writerIndex();n++){
            int next=source.getUnsignedByte(index+n);id|=(next&127)<<(n*7);
            if((next&128)==0){header=n+1;break;}
        }
        Codec codec=header==0||id<0||id>=codecs.length?null:codecs[id];
        if(codec==null)return source;
        if(source.readableBytes()>MAX_UPDATE_BYTES)throw new IllegalArgumentException("Encoded block update exceeds native section bound");
        ByteBuf input=source.duplicate();input.skipBytes(header);
        if(codec.section){
            ByteBuf count=input.duplicate();count.skipBytes(8);
            int entries=readVarInt(count);
            if(entries<0||entries>count.readableBytes())throw new IllegalArgumentException("Invalid encoded section update count");
        }
        Object original=read(codec,buffer.newInstance(input,registries));
        if(input.isReadable())throw new IllegalArgumentException("Trailing data in encoded block update");
        Object painted=rewrite.apply(original);
        if(painted==original)return source;
        if(painted==null||painted.getClass()!=original.getClass())throw new IllegalStateException("Block update rewrite changed packet type");
        ByteBuf output=source.alloc().buffer(source.readableBytes());
        try{
            writeVarInt(output,id);write(codec,buffer.newInstance(output,registries),painted);
            return output;
        }catch(ReflectiveOperationException|RuntimeException failure){output.release();throw failure;}
    }
    private Object read(Codec codec,Object input) throws ReflectiveOperationException {
        try{return decode.invoke(codec.stream,input);}
        catch(Throwable failure){throw failure(failure);}
    }
    private void write(Codec codec,Object output,Object packet) throws ReflectiveOperationException {
        try{encode.invoke(codec.stream,output,packet);}
        catch(Throwable failure){throw failure(failure);}
    }
    private static ReflectiveOperationException failure(Throwable failure){
        if(failure instanceof RuntimeException runtime)throw runtime;
        if(failure instanceof Error error)throw error;
        return failure instanceof ReflectiveOperationException reflection?reflection:new ReflectiveOperationException(failure);
    }
    private static void writeVarInt(ByteBuf output,int value){
        do{int next=value&127;value>>>=7;output.writeByte(next|(value==0?0:128));}while(value!=0);
    }
    private static int readVarInt(ByteBuf input){
        int value=0;
        for(int n=0;n<5;n++){int next=input.readUnsignedByte();value|=(next&127)<<(n*7);if((next&128)==0)return value;}
        throw new IllegalArgumentException("Oversized encoded update count");
    }
    private static Class<?> type(String name) throws ClassNotFoundException{return Class.forName(name);}
}
