package dev.trueseasons.climate;

import dev.trueseasons.api.Season;
import java.util.*;

/** Immutable biome-key policy shared by physical snow and client precipitation. */
public final class SeasonClimatePolicy {
    public static final List<String> HOT = List.of("desert", "badlands", "savanna", "jungle", "warm_ocean");
    public static final List<String> COLD = List.of("snowy", "frozen", "ice_spikes", "minecraft:grove", "jagged_peaks", "cold_ocean");
    public static final SeasonClimatePolicy ALL = new SeasonClimatePolicy(List.of(), List.of());
    private final List<String> winter, summer, autumn;
    public SeasonClimatePolicy(List<String> winter, List<String> summer) {
        this(winter, summer, winter);
    }
    public SeasonClimatePolicy(List<String> winter, List<String> summer, List<String> autumn) {
        this.winter = validated(winter); this.summer = validated(summer); this.autumn = validated(autumn);
    }
    private static List<String> validated(List<String> values) {
        if (values.size() > 128) throw new IllegalArgumentException("At most 128 biome exclusion fragments");
        Set<String> result = new TreeSet<>();
        for (String value : values) {
            if (value == null || !value.matches("[a-z0-9_./:-]{1,128}"))
                throw new IllegalArgumentException("Invalid biome exclusion fragment: " + value);
            result.add(value);
        }
        return List.copyOf(result);
    }
    public boolean applies(Season season, String biomeKey) {
        Objects.requireNonNull(season); Objects.requireNonNull(biomeKey);
        List<String> excluded = season == Season.WINTER ? winter : season == Season.SUMMER ? summer : season == Season.AUTUMN ? autumn : List.of();
        for (String fragment : excluded) if (biomeKey.contains(fragment)) return false;
        return true;
    }
    /** Palette fallback only; restoration of previously owned world edits is always allowed. */
    public Season paletteSeason(Season season, String biomeKey) {
        if (applies(season, biomeKey)) return season;
        return season == Season.WINTER || season == Season.AUTUMN ? Season.SUMMER : Season.WINTER;
    }
    public boolean sameWinter(SeasonClimatePolicy other) { return other != null && winter.equals(other.winter); }
    public String signature() { return winter + "/" + summer + "/" + autumn; }
    @Override public boolean equals(Object other) {
        return other instanceof SeasonClimatePolicy policy && winter.equals(policy.winter) && summer.equals(policy.summer) && autumn.equals(policy.autumn);
    }
    @Override public int hashCode() { return Objects.hash(winter, summer, autumn); }
}
