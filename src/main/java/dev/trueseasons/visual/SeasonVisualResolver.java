package dev.trueseasons.visual;

import dev.trueseasons.api.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Stateless shared palette source; it never writes chunk biome data. */
public final class SeasonVisualResolver {
    private final Map<String, List<String>> families = new LinkedHashMap<>();
    private final Map<String, Map<Season, SeasonVisualProfile>> palettes = new HashMap<>();
    public SeasonVisualResolver(YamlConfiguration config) {
        var section = config.getConfigurationSection("families");
        if (section != null) for (String family : section.getKeys(false)) families.put(family, section.getStringList(family));
        Set<String> names = new LinkedHashSet<>(families.keySet()); names.add("temperate");
        for (String name : names) {
            Map<Season, SeasonVisualProfile> seasons = new EnumMap<>(Season.class);
            for (Season season : Season.values()) {
                List<String> colors = config.getStringList("palettes." + name + "." + season.key());
                if (colors.size() != 2) throw new IllegalArgumentException("Two colors required for " + name + "/" + season);
                seasons.put(season, new SeasonVisualProfile(season, name, color(colors.get(0)), color(colors.get(1))));
            }
            palettes.put(name, seasons);
        }
    }
    private static int color(String value) {
        if (!value.matches("[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Expected six-digit RGB: " + value);
        return Integer.parseInt(value, 16);
    }
    public SeasonVisualProfile resolve(Season season, String biomeKey) {
        String family = "temperate";
        for (var entry : families.entrySet()) if (entry.getValue().stream().anyMatch(biomeKey::contains)) {
            family = entry.getKey(); break;
        }
        return palettes.get(family).get(season);
    }
}
