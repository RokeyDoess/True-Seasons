package dev.trueseasons.integration;

import de.bluecolored.bluemap.api.*;
import de.bluecolored.bluemap.core.map.hires.HiresModelManager;
import de.bluecolored.bluemap.core.storage.GridStorage;
import de.bluecolored.bluemap.core.storage.compression.CompressedInputStream;
import de.bluecolored.bluemap.core.world.*;
import dev.trueseasons.visual.SeasonalVisualState;
import java.lang.reflect.Field;
import java.util.*;

/** Reads the real map's persisted hires colours, after the production queue has drained. */
public final class BlueMapVisibleColorProbe {
    private BlueMapVisibleColorProbe() {}
    public static String verify(BlueMapMap map,SeasonalVisualState state) throws Exception {
        Object nativeMap=map.getClass().getMethod("map").invoke(map);
        World source=(World)nativeMap.getClass().getMethod("getWorld").invoke(nativeMap);
        HiresModelManager manager=(HiresModelManager)nativeMap.getClass().getMethod("getHiresModelManager").invoke(nativeMap);
        Field storageField=HiresModelManager.class.getDeclaredField("storage");storageField.setAccessible(true);
        GridStorage storage=(GridStorage)storageField.get(manager);
        Map<Long,byte[]> tiles=new HashMap<>();int chunks=0,grasses=0;
        for(var region:source.listRegions())for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++) {
            if(++chunks>16384)throw new AssertionError("No seasonal grass RGB found in saved map tiles; grasses sampled: "+grasses);
            Chunk chunk=source.getChunk(region.getX()*32+cx,region.getY()*32+cz);
            if(!chunk.isGenerated())continue;
            for(int sx=0;sx<16;sx+=4)for(int sz=0;sz<16;sz+=4) {
                int x=(region.getX()*32+cx)*16+sx,z=(region.getY()*32+cz)*16+sz;
                for(int y=chunk.getMaxY(x,z);y>=chunk.getMinY(x,z);y--) {
                    if(!chunk.getBlockState(x,y,z).getId().toString().equals("minecraft:grass_block"))continue;
                    grasses++;String biome=chunk.getBiome(x,y,z).getKey().toString();
                    if(!state.resolver().applies(state.season(),biome))break;
                    int rgb=state.resolver().resolve(state.season(),biome).grassRgb();
                    int tx=manager.getTileGrid().getCellX(x),tz=manager.getTileGrid().getCellY(z);
                    long key=((long)tx<<32)^(tz&0xffffffffL);byte[] bytes=tiles.get(key);
                    if(bytes==null) {
                        if(!storage.exists(tx,tz))break;
                        try(CompressedInputStream input=storage.read(tx,tz)) { bytes=input.decompress().readAllBytes(); }
                        tiles.put(key,bytes);
                    }
                    if(BlueMapSavedTileTests.hasRgb(bytes,rgb))
                        return "PASS: Actual saved BlueMap tile "+tx+","+tz+" contains seasonal grass RGB "+String.format("%06X",rgb)
                            +" from original "+biome+" grass at "+x+","+y+","+z+"; "+grasses+" source columns checked.";
                    break;
                }
            }
        }
        throw new AssertionError("Real map's completed saved tiles have no expected seasonal grass RGB; grass sources: "+grasses+", tiles: "+tiles.size());
    }
}
