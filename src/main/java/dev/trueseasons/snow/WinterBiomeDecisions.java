package dev.trueseasons.snow;

import dev.trueseasons.api.Season;
import dev.trueseasons.climate.SeasonClimatePolicy;
import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;
import java.io.IOException;
import org.bukkit.block.Block;

/** One pass only: native biomes occupy 4x4x4 cells, including their vertical coordinate. */
final class WinterBiomeDecisions {
    private final SeasonClimatePolicy policy;
    private final java.util.function.Function<Block,String> lookup;
    private final Int2ByteOpenHashMap cells = new Int2ByteOpenHashMap(32);
    WinterBiomeDecisions(SeasonClimatePolicy policy) { this(policy,block->block.getBiome().getKey().toString()); }
    WinterBiomeDecisions(SeasonClimatePolicy policy,java.util.function.Function<Block,String> lookup) { this.policy = policy;this.lookup=lookup; }
    boolean allows(Block surface) {
        int key = ((surface.getY() >> 2) << 4) | ((surface.getZ() & 15) >> 2 << 2) | ((surface.getX() & 15) >> 2);
        byte cached=cells.get(key);if(cached!=0)return cached==1;
        boolean allowed=policy.applies(Season.WINTER,lookup.apply(surface));cells.put(key,(byte)(allowed?1:2));return allowed;
    }
    boolean allows(ColumnReader reader,int column,int y)throws IOException{
        int key=((y>>2)<<4)|((column>>6)<<2)|((column&15)>>2);
        byte cached=cells.get(key);if(cached!=0)return cached==1;
        boolean allowed=policy.applies(Season.WINTER,reader.biome(column,y));cells.put(key,(byte)(allowed?1:2));return allowed;
    }
}
