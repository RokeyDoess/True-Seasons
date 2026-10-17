package dev.trueseasons.config;

import org.bukkit.configuration.file.YamlConfiguration;

public record FireflySettings(boolean enabled,int intervalTicks,int attempts,int particles,int radius,boolean sounds){
    public static FireflySettings read(YamlConfiguration yaml){
        return new FireflySettings(yaml.getBoolean("fireflies.enabled",true),bounded(yaml,"fireflies.interval-ticks",20,10,200),
            bounded(yaml,"fireflies.attempts-per-tick",16,1,64),bounded(yaml,"fireflies.max-particles-per-tick",8,1,64),
            bounded(yaml,"fireflies.radius-blocks",24,4,48),yaml.getBoolean("fireflies.sounds",false));
    }
    private static int bounded(YamlConfiguration yaml,String key,int fallback,int min,int max){
        Object raw=yaml.get(key,fallback);
        if(!(raw instanceof Integer||raw instanceof Long))throw new IllegalArgumentException(key+" must be a whole number in "+min+".."+max);
        long value=((Number)raw).longValue();
        if(value<min||value>max)throw new IllegalArgumentException(key+" must be "+min+".."+max);
        return (int)value;
    }
}
