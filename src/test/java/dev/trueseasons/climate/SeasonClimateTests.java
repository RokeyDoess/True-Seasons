package dev.trueseasons.climate;

import dev.trueseasons.api.Season;
import java.util.*;

public final class SeasonClimateTests {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        var policy = new SeasonClimatePolicy(SeasonClimatePolicy.HOT, SeasonClimatePolicy.COLD);
        for (String biome : List.of("desert","badlands","wooded_badlands","eroded_badlands","savanna","savanna_plateau","windswept_savanna","jungle","sparse_jungle","bamboo_jungle","warm_ocean")) {
            check(!policy.applies(Season.WINTER,"minecraft:"+biome), "Winter excludes hot " + biome);
            check(policy.applies(Season.SUMMER,"minecraft:"+biome), "Hot summer remains applicable");
        }
        for (String biome : List.of("snowy_plains","snowy_taiga","snowy_beach","frozen_river","frozen_ocean","deep_frozen_ocean","ice_spikes","grove","jagged_peaks","frozen_peaks","cold_ocean","deep_cold_ocean")) {
            check(!policy.applies(Season.SUMMER,"minecraft:"+biome), "Summer excludes cold " + biome);
            check(policy.applies(Season.WINTER,"minecraft:"+biome), "Cold winter remains applicable");
        }
        for (Season season : Season.values()) check(policy.applies(season,"minecraft:plains"), "Temperate seasons are unaffected");
        check(policy.paletteSeason(Season.SUMMER,"minecraft:frozen_peaks") == Season.WINTER, "Cold palette stays cold during summer");
        check(policy.paletteSeason(Season.WINTER,"minecraft:jungle") == Season.SUMMER, "Hot palette stays warm during winter");
        check(new SeasonClimatePolicy(List.of("custom:tropical"),List.of()).applies(Season.WINTER,"minecraft:desert"), "Explicit custom lists replace climate defaults");
        check(SeasonClimatePolicy.ALL.applies(Season.WINTER,"minecraft:desert"), "Empty list explicitly permits hot winter");
        check(new SeasonClimatePolicy(List.of("jungle","desert"),List.of()).sameWinter(new SeasonClimatePolicy(List.of("desert","jungle","jungle"),List.of("snowy"))), "Summer-only changes and reordered fragments do not reopen winter terrain");
        check(!policy.sameWinter(SeasonClimatePolicy.ALL), "Changed winter exclusions still require terrain reconciliation");
        for (String invalid : List.of("", "Desert", "*", " ", "foo bar")) {
            boolean rejected = false; try { new SeasonClimatePolicy(List.of(invalid),List.of()); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Invalid fragments fail settings validation");
        }
        System.out.println("Season climate tests passed: " + checks + " assertions");
    }
}
