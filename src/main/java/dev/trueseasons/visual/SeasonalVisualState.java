package dev.trueseasons.visual;

import dev.trueseasons.api.Season;
import java.util.*;

/** Immutable main-thread snapshot; render workers never access Bukkit world state. */
public record SeasonalVisualState(SeasonVisualResolver resolver, Season season, Set<UUID> eligibleWorlds, Map<UUID, Long> worldSeeds,SeasonColorFrame colors) {
    public SeasonalVisualState {
        Objects.requireNonNull(resolver); Objects.requireNonNull(season);
        eligibleWorlds = Set.copyOf(eligibleWorlds);
        worldSeeds = Map.copyOf(worldSeeds);
        Objects.requireNonNull(colors);
        if(colors.to()!=season)throw new IllegalArgumentException("Color target must match the season");
    }
    public SeasonalVisualState(SeasonVisualResolver resolver,Season season,Set<UUID> worlds,Map<UUID,Long> seeds){this(resolver,season,worlds,seeds,SeasonColorFrame.settled(season));}
    public SeasonalVisualState(SeasonVisualResolver resolver, Season season, Set<UUID> eligibleWorlds) {
        this(resolver, season, eligibleWorlds, Map.of());
    }
    public long seed(UUID world) { return worldSeeds.getOrDefault(world, 0L); }
    public SeasonalVisualState endpoint(){return colors.equals(SeasonColorFrame.settled(season))?this:new SeasonalVisualState(resolver,season,eligibleWorlds,worldSeeds);}
    public boolean active(UUID world) { return resolver.enabled() && eligibleWorlds.contains(world); }
    public String fingerprint(UUID world) {
        return fingerprint(world,resolver.endpointFingerprint(season));
    }
    /** Exact previous-format target, used only when upgrading equivalent saved map work. */
    public String legacyFingerprint(UUID world){return fingerprint(world,resolver.endpointFingerprint());}
    /** Published saved formats whose pixels match the current endpoint exactly. */
    public List<String> fingerprintAliases(UUID world){
        return List.of(legacyFingerprint(world),fingerprint(world,resolver.legacyScopedEndpointFingerprint(season)));
    }
    private String fingerprint(UUID world,String endpoint){
        if(!active(world))return "vanilla";
        return season.key()+"/"+endpoint
            +(season==Season.AUTUMN?"/trees="+resolver.autumnTrees()+(resolver.autumnTrees()?"/"+Long.toUnsignedString(seed(world)):""):"")
            +(season==Season.SPRING?"/blossoms="+resolver.springBlossoms()+(resolver.springBlossoms()?"/"+resolver.blossoms().signature()+"/"+Long.toUnsignedString(seed(world)):""):"");
    }
}
