package dev.trueseasons.config;

import dev.trueseasons.climate.SeasonClimatePolicy;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

/** Repair the shipped grove fragment without replacing customized biome lists. */
public final class BiomeDefaults {
    private BiomeDefaults(){}
    public static boolean upgradeSummer(YamlConfiguration main){
        String key="climate.summer-excluded-biome-fragments";
        if(!main.getStringList(key).equals(List.of("snowy","frozen","ice_spikes","grove","jagged_peaks","cold_ocean")))return false;
        main.set(key,SeasonClimatePolicy.COLD);return true;
    }
    public static boolean upgradeFamilies(YamlConfiguration visuals){
        String key="families.evergreen";
        if(!visuals.getStringList(key).equals(List.of("taiga","grove")))return false;
        visuals.set(key,List.of("taiga","minecraft:grove"));return true;
    }
}
