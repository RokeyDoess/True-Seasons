package dev.trueseasons.config;

import org.bukkit.configuration.ConfigurationSection;

/** Server-wide limits for extra foliage catch-up, independent of normal terrain packets. */
public record LeafPresentationLimits(int chunks,int states) {
    public static final LeafPresentationLimits DEFAULT=new LeafPresentationLimits(64,8192);
    public LeafPresentationLimits {
        validate("max-chunks-per-tick",chunks,1024);
        validate("max-states-per-tick",states,131072);
    }
    public static LeafPresentationLimits read(ConfigurationSection visuals){
        return new LeafPresentationLimits(read(visuals,"max-chunks-per-tick",DEFAULT.chunks),read(visuals,"max-states-per-tick",DEFAULT.states));
    }
    private static int read(ConfigurationSection visuals,String key,int fallback){
        String path="foliage-updates."+key;
        if(!visuals.contains(path))return fallback;
        if(!visuals.isInt(path))throw new IllegalArgumentException("visuals.yml: "+path+" must be a whole number");
        return visuals.getInt(path);
    }
    private static void validate(String key,int value,int maximum){
        if(value<1||value>maximum)throw new IllegalArgumentException("visuals.yml: foliage-updates."+key+" must be within 1.."+maximum);
    }
}
