package dev.trueseasons.snow;

import dev.trueseasons.api.Season;
import dev.trueseasons.climate.SeasonClimatePolicy;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.block.Block;

/** One pass only: native biomes occupy 4x4x4 cells, including their vertical coordinate. */
final class WinterBiomeDecisions {
    private final SeasonClimatePolicy policy;
    private final java.util.function.Function<Block,String> lookup;
    private final Map<Integer, Boolean> cells = new HashMap<>();
    WinterBiomeDecisions(SeasonClimatePolicy policy) { this(policy,block->block.getBiome().getKey().toString()); }
    WinterBiomeDecisions(SeasonClimatePolicy policy,java.util.function.Function<Block,String> lookup) { this.policy = policy;this.lookup=lookup; }
    boolean allows(Block surface) {
        int key = ((surface.getY() >> 2) << 4) | ((surface.getZ() & 15) >> 2 << 2) | ((surface.getX() & 15) >> 2);
        return cells.computeIfAbsent(key, ignored -> policy.applies(Season.WINTER, lookup.apply(surface)));
    }
}
