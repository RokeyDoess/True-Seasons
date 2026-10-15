package dev.trueseasons.config;

import dev.trueseasons.api.Season;
import dev.trueseasons.calendar.SeasonCalendar;
import dev.trueseasons.climate.SeasonClimatePolicy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class Settings {
    public final SeasonCalendar calendar;
    public final Set<String> worlds;
    public final int concurrency, perTick, rescanSeconds, blueMapSeconds, blueMapRegions;
    public final int playerRadius, snowStepColumns, snowStepTicks, snowPlayerBreakCooldownSeconds;
    public final double minTps, millis;
    public final boolean enabled, prioritize, snow, protectSnow, weather, blueMap;
    public final boolean allExposed, snowTransition, winterStorm, winterSnowfall, groundCover;
    public final SeasonClimatePolicy climate;
    public final Set<Material> surfaces;
    public final List<String> excludedBiomes;
    public final YamlConfiguration weatherConfig, visuals;

    public Settings(JavaPlugin plugin) throws Exception {
        Map<String, YamlConfiguration> files = new HashMap<>();
        for (String name : List.of("config", "calendar", "snow", "weather", "visuals", "integrations")) {
            File file = new File(plugin.getDataFolder(), name + ".yml");
            if (!file.exists()) plugin.saveResource(name + ".yml", false);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file); // Malformed files must fail instead of silently falling back.
            files.put(name, yaml);
        }
        var main = files.get("config"); var cal = files.get("calendar"); var sn = files.get("snow");
        var integrations = files.get("integrations");
        Map<Season, List<Integer>> months = new EnumMap<>(Season.class);
        for (Season season : Season.values()) months.put(season, cal.getIntegerList("months." + season.key()));
        calendar = new SeasonCalendar(ZoneId.of(cal.getString("timezone", "UTC")), months);
        worlds = Set.copyOf(main.getStringList("worlds"));
        concurrency = bounded(main.getInt("migration.max-concurrent-chunks", 2), 1, 16);
        perTick = bounded(main.getInt("migration.max-chunks-per-tick", 1), 1, 16);
        rescanSeconds = bounded(main.getInt("migration.rescan-seconds", 300), 30, 86400);
        millis = main.getDouble("migration.max-millis-per-tick", 3);
        minTps = main.getDouble("migration.pause-below-tps", 18.5);
        if (!Double.isFinite(millis) || millis <= 0 || millis > 50 || !Double.isFinite(minTps) || minTps < 0 || minTps > 20)
            throw new IllegalArgumentException("Invalid migration TPS/time budget");
        if (main.getBoolean("migration.generate-missing-chunks", false))
            throw new IllegalArgumentException("Generating missing chunks is forbidden");
        enabled = main.getBoolean("migration.enabled", true);
        prioritize = main.getBoolean("migration.prioritize-player-chunks", true);
        playerRadius = bounded(main.getInt("migration.player-radius-chunks", 4), 0, 16);
        snow = sn.getBoolean("enabled", true);
        groundCover = sn.getBoolean("ground-cover.enabled", true);
        String surfaceMode = sn.getString("surface-mode", "all-exposed");
        if (!List.of("all-exposed", "whitelist").contains(surfaceMode)) throw new IllegalArgumentException("Invalid snow surface-mode");
        allExposed = surfaceMode.equals("all-exposed");
        snowTransition = sn.getBoolean("transition.enabled", true);
        snowStepColumns = bounded(sn.getInt("transition.columns-per-step", 16), 1, 256);
        snowStepTicks = bounded(sn.getInt("transition.step-ticks", 5), 1, 200);
        snowPlayerBreakCooldownSeconds = bounded(sn.getInt("replenishment.player-break-cooldown-seconds", 60), 0, 86400);
        protectSnow = sn.getBoolean("protect-owned-layers", true);
        surfaces = EnumSet.noneOf(Material.class);
        for (String name : sn.getStringList("surface-materials")) {
            Material material = Material.matchMaterial(name);
            if (material == null || !material.isSolid()) throw new IllegalArgumentException("Invalid snow surface " + name);
            surfaces.add(material);
        }
        excludedBiomes = sn.getStringList("excluded-biome-fragments");
        boolean legacySnowMigrated = false;
        // Migrate only the old shipped exclusions, leaving customized exclusions intact.
        if (!sn.contains("surface-mode") && excludedBiomes.equals(List.of("desert", "badlands", "savanna", "jungle", "ocean", "river", "beach", "mushroom", "frozen", "snowy", "ice_spikes"))) {
            excludedBiomes.clear(); sn.set("excluded-biome-fragments", List.of());
            sn.set("surface-mode", "all-exposed");
            legacySnowMigrated = true;
        }
        List<String> winterExcluded = new ArrayList<>(main.contains("climate.winter-excluded-biome-fragments")
            ? main.getStringList("climate.winter-excluded-biome-fragments") : SeasonClimatePolicy.HOT);
        winterExcluded.addAll(excludedBiomes);
        climate = new SeasonClimatePolicy(winterExcluded, main.contains("climate.summer-excluded-biome-fragments")
            ? main.getStringList("climate.summer-excluded-biome-fragments") : SeasonClimatePolicy.COLD,
            main.contains("climate.autumn-excluded-biome-fragments")
            ? main.getStringList("climate.autumn-excluded-biome-fragments") : SeasonClimatePolicy.HOT);
        weatherConfig = files.get("weather"); weather = weatherConfig.getBoolean("enabled", true);
        winterStorm = weatherConfig.getBoolean("winter-storm.start-on-winter", true);
        boolean legacyStormTimer=weatherConfig.contains("winter-storm.duration-seconds");
        weatherConfig.set("winter-storm.duration-seconds",null);
        winterSnowfall = weatherConfig.getBoolean("snowfall.enabled", true);
        boolean legacyParticles = false;
        for (String key : List.of("snowfall.interval-ticks", "snowfall.particles-per-player", "snowfall.radius-blocks")) {
            if (weatherConfig.contains(key)) { weatherConfig.set(key, null); legacyParticles = true; }
        }
        for (Season season : Season.values()) for (String kind : List.of("rain", "thunder")) {
            double chance = weatherConfig.getDouble(kind + "-acceptance." + season.key(), 1);
            if (!Double.isFinite(chance) || chance < 0 || chance > 1)
                throw new IllegalArgumentException("Invalid " + kind + " probability");
        }
        visuals = files.get("visuals");
        blueMap = integrations.getBoolean("bluemap.enabled", true);
        blueMapSeconds = bounded(integrations.getInt("bluemap.flush-seconds", 30), 5, 86400);
        blueMapRegions = bounded(integrations.getInt("bluemap.max-regions-per-flush", 1), 1, 32);
        // Add new commented options without replacing the administrator's existing values.
        for (var entry : files.entrySet()) {
            var yaml = entry.getValue();
            var defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(plugin.getResource(entry.getKey() + ".yml")), StandardCharsets.UTF_8));
            boolean changed = entry.getKey().equals("snow") && legacySnowMigrated || entry.getKey().equals("weather") && (legacyParticles||legacyStormTimer);
            if(entry.getKey().equals("weather")&&legacyStormTimer)yaml.setComments("winter-storm.start-on-winter",defaults.getComments("winter-storm.start-on-winter"));
            if (entry.getKey().equals("weather") && legacyParticles) yaml.setComments("snowfall.enabled", defaults.getComments("snowfall.enabled"));
            if (changed) {
                yaml.setComments("surface-mode", defaults.getComments("surface-mode"));
                yaml.setComments("surface-materials", defaults.getComments("surface-materials"));
            }
            for (String key : defaults.getKeys(true)) if (!yaml.contains(key)) {
                if (defaults.isConfigurationSection(key)) yaml.createSection(key);
                else yaml.set(key, defaults.get(key));
                yaml.setComments(key, defaults.getComments(key)); changed = true;
            }
            if (changed) yaml.save(new File(plugin.getDataFolder(), entry.getKey() + ".yml"));
        }
    }
    private static int bounded(int value, int min, int max) {
        if (value < min || value > max) throw new IllegalArgumentException("Config value outside " + min + ".." + max);
        return value;
    }
}
