package dev.trueseasons.presentation;

import com.github.retrooper.packetevents.*;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.*;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.*;
import com.github.retrooper.packetevents.protocol.world.chunk.storage.BitStorage;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import io.github.retrooper.packetevents.netty.NettyManagerImpl;
import io.netty.buffer.*;
import java.lang.management.ManagementFactory;
import java.util.*;

/** Isolated section-codec experiment. This does not measure whole-server TPS or a completed wrapper port. */
public final class PacketEventsPaletteExperiment {
    private static final int ORIGINAL=67,EXPANDED=ORIGINAL*PaperBiomePackets.COLOR_BANKS;
    private static volatile long sink;
    private static int map(int id){return id+ORIGINAL*4;}
    private static void setup(){
        PacketEvents.setAPI(new PacketEventsAPI<Object>(){
            final NettyManager netty=new NettyManagerImpl();
            public boolean isLoaded(){return true;}public void init(){}public boolean isInitialized(){return true;}public boolean isTerminated(){return false;}
            public Object getPlugin(){return null;}public ServerManager getServerManager(){return ()->ServerVersion.V_26_3;}
            public ProtocolManager getProtocolManager(){return null;}public PlayerManager getPlayerManager(){return null;}public NettyManager getNettyManager(){return netty;}public ChannelInjector getInjector(){return null;}
        });
    }
    private static byte[] fixture(boolean borders){
        ByteBuf bytes=Unpooled.buffer();try{
            for(int section=0;section<24;section++){
                int[] blocks=new int[4096];
                if(section>=5&&section<12)for(int i=0;i<blocks.length;i++)blocks[i]=section>=10?1+i%300:1+i%16;
                bytes.writeShort(section>=5&&section<12?4096:0).writeShort(0);LeafWireCodec.writePalette(bytes,blocks,32768);
                int[] biomes=new int[64];for(int i=0;i<64;i++)biomes[i]=borders&&section>=5&&section<12?i%9:0;
                BiomeWireCodec.writeBiomes(bytes,biomes,ORIGINAL,false,ORIGINAL);
            }
            byte[] result=new byte[bytes.readableBytes()];bytes.readBytes(result);return result;
        }finally{bytes.release();}
    }
    private static byte[] wrappers(byte[] source,boolean correct){
        ByteBuf input=Unpooled.wrappedBuffer(source),output=Unpooled.buffer();
        try{
            var reader=PacketWrapper.createUniversalPacketWrapper(input,ServerVersion.V_26_3);
            var writer=PacketWrapper.createUniversalPacketWrapper(output,ServerVersion.V_26_3);
            while(input.isReadable()){
                var section=Chunk_v1_18.read(reader);var before=section.getBiomeData();
                DataPalette after=correct?PaletteType.BIOME.create():before;
                for(int y=0;y<4;y++)for(int z=0;z<4;z++)for(int x=0;x<4;x++)after.set(x,y,z,map(before.get(x,y,z)));
                if(correct&&after.palette instanceof GlobalPalette){
                    BitStorage resized=new BitStorage(32-Integer.numberOfLeadingZeros(EXPANDED-1),64);
                    for(int i=0;i<64;i++)resized.set(i,after.palette.idToState(after.storage.get(i)));after.storage=resized;
                }
                var mapped=new Chunk_v1_18(ClientVersion.V_26_3,section.getBlockCount(),section.getFluidCount(),section.getChunkData(),after);
                Chunk_v1_18.write(writer,mapped);
            }
            byte[] result=new byte[output.readableBytes()];output.readBytes(result);return result;
        }finally{input.release();output.release();}
    }
    private static void verify(byte[] source,byte[] mapped){
        var before=LeafWireCodec.read(source,id->true);var after=LeafWireCodec.read(mapped,id->true);
        if(before.size()!=after.size())throw new AssertionError("Section count changed");
        for(int section=0;section<before.size();section++){
            var one=before.get(section);var two=after.get(section);
            if(one.blocks()!=two.blocks()||one.fluids()!=two.fluids()||!Arrays.equals(one.states(),two.states()))throw new AssertionError("Blocks/fluid counts changed");
            ByteBuf original=Unpooled.wrappedBuffer(one.biomes()),changed=Unpooled.wrappedBuffer(two.biomes());
            try{int[] a=BiomeWireCodec.readBiomes(original),b=BiomeWireCodec.readBiomes(changed);for(int i=0;i<64;i++)if(b[i]!=map(a[i]))throw new AssertionError("Expanded registry ID lost at section "+section+" cell "+i+": expected="+map(a[i])+" actual="+b[i]);}
            finally{original.release();changed.release();}
        }
    }
    public static void main(String[] args){
        setup();byte[] plain=fixture(false),border=fixture(true);
        boolean reproduced=false;try{verify(border,wrappers(border,false));}catch(AssertionError|IllegalStateException failure){reproduced=true;System.out.println("EXPECTED: an existing vanilla-width direct palette cannot accept our expanded IDs without resizing: "+failure.getMessage());}
        if(!reproduced)throw new AssertionError("Expected custom-registry width case not reproduced");
        for(byte[] fixture:new byte[][]{plain,border}){
            verify(fixture,BiomeWireCodec.chunk(fixture,ORIGINAL,EXPANDED,PacketEventsPaletteExperiment::map).sections());verify(fixture,wrappers(fixture,true));
            var nativeResult=BiomeWireCodec.chunk(fixture,ORIGINAL,EXPANDED,PacketEventsPaletteExperiment::map);
            var wrapperResult=PacketEventsSectionCodec.chunk(fixture,ORIGINAL,EXPANDED,PacketEventsPaletteExperiment::map);
            verify(fixture,wrapperResult.sections());if(!Arrays.equals(nativeResult.biomes(),wrapperResult.biomes()))throw new AssertionError("Original biome capture differs");
        }
        System.out.println("PASS: registry-aware wrapper adapter preserves all block/fluid data and all 1536 biome IDs for both fixtures.");
        if(args.length>0&&args[0].equals("--verify"))return;
        int loops=args.length==0?1000:Integer.parseInt(args[0]);
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);long thread=Thread.currentThread().threadId();
        for(int i=0;i<500;i++){sink+=BiomeWireCodec.chunk(border,ORIGINAL,EXPANDED,PacketEventsPaletteExperiment::map).sections().length;sink+=PacketEventsSectionCodec.chunk(border,ORIGINAL,EXPANDED,PacketEventsPaletteExperiment::map).sections().length;}
        for(int round=0;round<4;round++)for(int order=0;order<2;order++){
            boolean useWrapper=(round%2==0?order:1-order)==1;
            long allocation=bean.getThreadAllocatedBytes(thread),cpu=bean.getCurrentThreadCpuTime(),wall=System.nanoTime();
            for(int i=0;i<loops;i++){var encoded=useWrapper?PacketEventsSectionCodec.chunk(border,ORIGINAL,EXPANDED,PacketEventsPaletteExperiment::map):BiomeWireCodec.chunk(border,ORIGINAL,EXPANDED,PacketEventsPaletteExperiment::map);sink+=encoded.sections().length+encoded.biomes().length;}
            System.out.printf(Locale.ROOT,"PALETTE round=%d wrappers=%s loops=%d cpuMs=%.3f wallMs=%.3f allocatedMiB=%.3f sourceBytes=%d%n",round,useWrapper,loops,(bean.getCurrentThreadCpuTime()-cpu)/1e6,(System.nanoTime()-wall)/1e6,(bean.getThreadAllocatedBytes(thread)-allocation)/1048576.,border.length);
        }
        System.out.println("PASS checksum="+sink+"; codec-only costs, no networking, packet headers, lighting or block entities included.");
    }
}
