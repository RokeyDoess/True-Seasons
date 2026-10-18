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
    private final boolean springBlossoms;
    private final SpringBlossoms blossoms;
    private final boolean gradualColors;
    private final String fingerprint;
    private final String endpointFingerprint;
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
        springBlossoms=config.getBoolean("spring-blossoms.enabled",true);
        blossoms=new SpringBlossoms(percent(config,"spring-blossoms.trees-percent",20),percent(config,"spring-blossoms.leaves-percent",25));
        gradualColors=config.getDouble("transition.opening-days",7)>0;
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
        registryFingerprint=digest(endpointFingerprint+"\n"+new TreeMap<>(previous)+"\n"+gradualColors);
        fingerprint=digest(registryFingerprint+"\n"+autumnTrees+"/"+springBlossoms+"/"+blossoms.signature());
    }
    private static int percent(YamlConfiguration config,String key,int fallback){
        Object raw=config.get(key,fallback);
        if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<0||((Number)raw).longValue()>100)
            throw new IllegalArgumentException(key+" must be a whole number in 0..100");
        return ((Number)raw).intValue();
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
        return blend(resolve(frame.from(),biome),resolve(frame.to(),biome),frame.paletteStep());
    }
    private static SeasonVisualProfile blend(SeasonVisualProfile from,SeasonVisualProfile to,int step){
        return new SeasonVisualProfile(to.season(),to.family(),blendRgb(from.grassRgb(),to.grassRgb(),step),blendRgb(from.foliageRgb(),to.foliageRgb(),step));
    }
    private static int blendRgb(int from,int to,int step){
        int result=0;
        for(int shift:new int[]{16,8,0})result|=(((from>>shift&255)*(SeasonColorFrame.PALETTE_STEPS-step)+(to>>shift&255)*step+SeasonColorFrame.PALETTE_STEPS/2)/SeasonColorFrame.PALETTE_STEPS)<<shift;
        return result;
    }
    public boolean autumnTrees() { return autumnTrees; }
    public boolean springBlossoms(){return springBlossoms;}
    public SpringBlossoms blossoms(){return blossoms;}
    public boolean gradualColors(){return gradualColors;}
    public boolean applies(Season season, String biomeKey) { return enabled && climate.applies(season, biomeKey); }
    public String fingerprint() {
        return fingerprint;
    }
    public String endpointFingerprint(){return endpointFingerprint;}
    public String registryFingerprint(){return registryFingerprint;}
    private String computeEndpointFingerprint() {
        return digest("visual3\n" + enabled + "\n" + families + "\n" + new TreeMap<>(palettes) + "\n" + climate.signature());
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
