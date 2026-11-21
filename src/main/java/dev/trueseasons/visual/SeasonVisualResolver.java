package dev.trueseasons.visual;

import dev.trueseasons.api.*;
import dev.trueseasons.climate.SeasonClimatePolicy;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Stateless shared palette source; it never writes chunk biome data. */
public final class SeasonVisualResolver {
    // Bump when rendered content changes, so neither fingerprint format can reuse older pixels.
    private static final String CONTENT_VERSION="visual3";
    private final Map<String, List<String>> families = new LinkedHashMap<>();
    private final Map<String, Map<Season, SeasonVisualProfile>> palettes = new HashMap<>();
    private final SeasonClimatePolicy climate;
    private final boolean enabled;
    private final boolean autumnTrees;
    private final String fingerprint;
    private final String endpointFingerprint;
    private final Map<Season,String> seasonEndpoints;
    private final Map<Season,String> legacyScopedEndpoints;
    private final String registryFingerprint;
    private final Map<Season,Season> previous;
    public SeasonVisualResolver(YamlConfiguration config) {
        this(config, SeasonClimatePolicy.ALL);
    }
    public SeasonVisualResolver(YamlConfiguration config, SeasonClimatePolicy climate) {
        this(config,climate,season->Season.values()[Math.floorMod(season.ordinal()-1,Season.values().length)]);
    }
    public SeasonVisualResolver(YamlConfiguration config, SeasonClimatePolicy climate,java.util.function.Function<Season,Season> predecessor) {
        this.climate = Objects.requireNonNull(climate);
        var before=new EnumMap<Season,Season>(Season.class);
        for(Season season:Season.values())before.put(season,Objects.requireNonNull(predecessor.apply(season)));
        previous=Map.copyOf(before);
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
        endpointFingerprint=computeEndpointFingerprint();
        var endpoints=new EnumMap<Season,String>(Season.class);
        var legacyEndpoints=new EnumMap<Season,String>(Season.class);
        for(Season season:Season.values()){
            var profiles=new EnumMap<Season,Map<String,SeasonVisualProfile>>(Season.class);
            for(Season rendered:climate.paletteSeasons(season)){
                var familiesForSeason=new TreeMap<String,SeasonVisualProfile>();
                palettes.forEach((family,values)->familiesForSeason.put(family,values.get(rendered)));
                profiles.put(rendered,familiesForSeason);
            }
            legacyEndpoints.put(season,scopedFingerprint(season,profiles));
            // BlueMap delegates excluded biomes to its native calculator. Only the
            // active season's palette can contribute seasonal pixels to this map.
            profiles.keySet().removeIf(rendered->rendered!=season);
            endpoints.put(season,scopedFingerprint(season,profiles));
        }
        seasonEndpoints=Map.copyOf(endpoints);
        legacyScopedEndpoints=Map.copyOf(legacyEndpoints);
        registryFingerprint=endpointFingerprint;
        fingerprint=digest(registryFingerprint+"\n"+autumnTrees);
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
    public Season previous(Season season){return previous.get(season);}
    public SeasonVisualProfile resolve(SeasonColorFrame frame,String biome){
        return resolve(frame.to(),biome);
    }
    public boolean autumnTrees() { return autumnTrees; }
    public boolean applies(Season season, String biomeKey) { return enabled && climate.applies(season, biomeKey); }
    public String fingerprint() {
        return fingerprint;
    }
    public String endpointFingerprint(){return endpointFingerprint;}
    public String endpointFingerprint(Season season){return seasonEndpoints.get(Objects.requireNonNull(season));}
    /** Legacy palette format, for upgrading equivalent saved map targets only. */
    public String legacyScopedEndpointFingerprint(Season season){return legacyScopedEndpoints.get(Objects.requireNonNull(season));}
    private String scopedFingerprint(Season season,Map<Season,Map<String,SeasonVisualProfile>> profiles){
        return digest("season1/"+CONTENT_VERSION+"\n"+enabled+"\n"+families+"\n"+profiles+"\n"+climate.signature(season));
    }
    public String registryFingerprint(){return registryFingerprint;}
    private String computeEndpointFingerprint() {
        return digest(CONTENT_VERSION+"\n" + enabled + "\n" + families + "\n" + new TreeMap<>(palettes) + "\n" + climate.signature());
    }
    private static String digest(String value){
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
