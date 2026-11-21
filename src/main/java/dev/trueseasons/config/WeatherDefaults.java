package dev.trueseasons.config;

import org.bukkit.configuration.file.YamlConfiguration;

/** Upgrade shipped probabilities without replacing customized values. */
public final class WeatherDefaults {
    private WeatherDefaults() {}
    public static boolean upgrade(YamlConfiguration weather) {
        boolean changed = replace(weather, "rain-acceptance.summer", .65, .15);
        changed |= replace(weather, "thunder-acceptance.summer", .65, .5);
        changed |= replace(weather, "rain-acceptance.winter", .8, 1.0);
        changed |= replace(weather, "thunder-acceptance.winter", .2, .1);
        return changed;
    }
    private static boolean replace(YamlConfiguration weather, String key, double previous, double next) {
        if (!(weather.get(key) instanceof Number value) || Double.compare(value.doubleValue(), previous) != 0) return false;
        weather.set(key, next);
        return true;
    }
}
