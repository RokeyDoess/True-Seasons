package dev.trueseasons.presentation;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.*;
import com.github.retrooper.packetevents.protocol.world.chunk.storage.BitStorage;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import io.netty.buffer.*;
import java.util.function.IntUnaryOperator;

/** Experimental maintained section wrappers with explicit per-client biome width and original capture. */
final class PacketEventsSectionCodec {
    private PacketEventsSectionCodec(){}
    static BiomeWireCodec.ChunkData chunk(byte[] source,int originalCount,int registrySize,IntUnaryOperator mapping){
        if(originalCount<1||registrySize<originalCount)throw new IllegalArgumentException("Invalid client registry size");
        ByteBuf input=Unpooled.wrappedBuffer(source),output=Unpooled.buffer(),originals=Unpooled.buffer();
        try{
            var reader=PacketWrapper.createUniversalPacketWrapper(input,ServerVersion.V_26_3);
            var writer=PacketWrapper.createUniversalPacketWrapper(output,ServerVersion.V_26_3);
            var capture=PacketWrapper.createUniversalPacketWrapper(originals,ServerVersion.V_26_3);
            while(input.isReadable()){
                var section=Chunk_v1_18.read(reader);var before=section.getBiomeData();
                PaletteType.write(capture,before);
                var after=PaletteType.BIOME.create();
                for(int y=0;y<4;y++)for(int z=0;z<4;z++)for(int x=0;x<4;x++){
                    int id=before.get(x,y,z);if(id<0||id>=originalCount)throw new IllegalArgumentException("Unknown original biome ID "+id);
                    int mapped=mapping.applyAsInt(id);if(mapped<0||mapped>=registrySize)throw new IllegalArgumentException("Mapped biome outside client registry");
                    after.set(x,y,z,mapped);
                }
                if(after.palette instanceof GlobalPalette){
                    int bits=Math.max(4,32-Integer.numberOfLeadingZeros(registrySize-1));
                    var resized=new BitStorage(bits,64);
                    for(int i=0;i<64;i++)resized.set(i,after.palette.idToState(after.storage.get(i)));
                    after.storage=resized;
                }
                Chunk_v1_18.write(writer,new Chunk_v1_18(ClientVersion.V_26_3,section.getBlockCount(),section.getFluidCount(),section.getChunkData(),after));
            }
            return new BiomeWireCodec.ChunkData(bytes(output),bytes(originals));
        }finally{input.release();output.release();originals.release();}
    }
    private static byte[] bytes(ByteBuf buffer){byte[] result=new byte[buffer.readableBytes()];buffer.getBytes(buffer.readerIndex(),result);return result;}
}
