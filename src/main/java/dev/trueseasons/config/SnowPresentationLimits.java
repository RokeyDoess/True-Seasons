package dev.trueseasons.config;

import org.bukkit.configuration.ConfigurationSection;

/** Server-wide limits for extra snow reveal, preview and melt refreshes. */
public record SnowPresentationLimits(int chunks, int blocks) {
    public static final SnowPresentationLimits DEFAULT = new SnowPresentationLimits(64, 8192);
    public SnowPresentationLimits {
        validate("max-chunks-per-tick", chunks, 1024);
        validate("max-block-updates-per-tick", blocks, 131072);
    }
    public static SnowPresentationLimits read(ConfigurationSection snow) {
        return new SnowPresentationLimits(read(snow, "max-chunks-per-tick", DEFAULT.chunks),
            read(snow, "max-block-updates-per-tick", DEFAULT.blocks));
    }
    private static int read(ConfigurationSection snow, String key, int fallback) {
        String path = "presentation." + key;
        if (!snow.contains(path)) return fallback;
        if (!snow.isInt(path)) throw new IllegalArgumentException("snow.yml: " + path + " must be a whole number");
        return snow.getInt(path);
    }
    private static void validate(String key, int value, int maximum) {
        if (value < 1 || value > maximum)
            throw new IllegalArgumentException("snow.yml: presentation." + key + " must be within 1.." + maximum);
    }
}
