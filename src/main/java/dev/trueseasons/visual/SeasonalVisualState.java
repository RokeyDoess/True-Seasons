package dev.trueseasons.visual;

import dev.trueseasons.api.Season;
import java.util.*;

/** Immutable main-thread snapshot; render workers never access Bukkit world state. */
public record SeasonalVisualState(SeasonVisualResolver resolver, Season season, Set<UUID> eligibleWorlds) {
    public SeasonalVisualState {
        Objects.requireNonNull(resolver); Objects.requireNonNull(season);
        eligibleWorlds = Set.copyOf(eligibleWorlds);
    }
    public boolean active(UUID world) { return resolver.enabled() && eligibleWorlds.contains(world); }
    public String fingerprint(UUID world) { return active(world) ? season.key() + "/" + resolver.fingerprint() : "vanilla"; }
}
