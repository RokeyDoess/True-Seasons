package dev.trueseasons.visual;

import dev.trueseasons.api.*;
import dev.trueseasons.climate.SeasonClimatePolicy;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Stateless shared palette source; it never writes chunk biome data. */
public final class SeasonVisualResolver {
    private final Map<String, List<String>> families = new LinkedHashMap<>();
    private final Map<String, Map<Season, SeasonVisualProfile>> palettes = new HashMap<>();
    private final SeasonClimatePolicy climate;
    private final boolean enabled;
    private final boolean autumnTrees;
    private final String fingerprint;
    public SeasonVisualResolver(YamlConfiguration config) {
        this(config, SeasonClimatePolicy.ALL);
    }
    public SeasonVisualResolver(YamlConfiguration config, SeasonClimatePolicy climate) {
        this.climate = Objects.requireNonNull(climate);
        enabled = config.getBoolean("enabled", true);
        autumnTrees = config.getBoolean("autumn-trees.enabled", true);
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
        fingerprint = computeFingerprint();
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
        return palettes.get(family).get(climate.paletteSeason(season, biomeKey));
    }
    public boolean enabled() { return enabled; }
    public boolean autumnTrees() { return autumnTrees; }
    public boolean applies(Season season, String biomeKey) { return enabled && climate.applies(season, biomeKey); }
    public String fingerprint() {
        return fingerprint;
    }
    private String computeFingerprint() {
        String value = "visual2\n" + enabled + "/" + autumnTrees + "\n" + families + "\n" + new TreeMap<>(palettes) + "\n" + climate.signature();
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @Override public boolean equals(Object other) {
        return other instanceof SeasonVisualResolver resolver && fingerprint.equals(resolver.fingerprint);
    }
    @Override public int hashCode() { return fingerprint.hashCode(); }
}
