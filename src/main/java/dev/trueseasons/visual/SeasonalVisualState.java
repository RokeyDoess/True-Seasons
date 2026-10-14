package dev.trueseasons.visual;

import dev.trueseasons.api.Season;
import java.util.*;

/** Immutable main-thread snapshot; render workers never access Bukkit world state. */
public record SeasonalVisualState(SeasonVisualResolver resolver, Season season, Set<UUID> eligibleWorlds, Map<UUID, Long> worldSeeds) {
    public SeasonalVisualState {
        Objects.requireNonNull(resolver); Objects.requireNonNull(season);
        eligibleWorlds = Set.copyOf(eligibleWorlds);
        worldSeeds = Map.copyOf(worldSeeds);
    }
    public SeasonalVisualState(SeasonVisualResolver resolver, Season season, Set<UUID> eligibleWorlds) {
        this(resolver, season, eligibleWorlds, Map.of());
    }
    public long seed(UUID world) { return worldSeeds.getOrDefault(world, 0L); }
    public boolean active(UUID world) { return resolver.enabled() && eligibleWorlds.contains(world); }
    public String fingerprint(UUID world) {
        return active(world) ? season.key() + "/" + resolver.fingerprint()
            + (season == Season.AUTUMN && resolver.autumnTrees() ? "/" + Long.toUnsignedString(seed(world)) : "") : "vanilla";
    }
}
