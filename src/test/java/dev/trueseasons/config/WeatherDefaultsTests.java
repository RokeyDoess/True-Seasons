package dev.trueseasons.config;

import org.bukkit.configuration.file.YamlConfiguration;

public final class WeatherDefaultsTests {
    public static void main(String[] args) {
        var weather = new YamlConfiguration();
        weather.set("rain-acceptance.summer", .65);
        weather.set("thunder-acceptance.summer", .65);
        weather.set("rain-acceptance.winter", .8);
        weather.set("thunder-acceptance.winter", .2);
        weather.set("rain-acceptance.spring", 1.0);
        check(WeatherDefaults.upgrade(weather), "Old shipped probabilities upgrade");
        check(weather.getDouble("rain-acceptance.summer") == .15, "Summer accepts 15% of rain starts");
        check(weather.getDouble("thunder-acceptance.summer") == .5, "Summer accepts 50% of thunder starts");
        check(weather.getDouble("rain-acceptance.winter") == 1, "Winter accepts all rain starts");
        check(weather.getDouble("thunder-acceptance.winter") == .1, "Winter accepts 10% of thunder starts");
        check(weather.getDouble("rain-acceptance.spring") == 1, "Other seasons retain their settings");
        check(!WeatherDefaults.upgrade(weather), "Repeated reload does not rewrite upgraded values");
        for (String key : new String[]{"rain-acceptance.summer", "thunder-acceptance.summer", "rain-acceptance.winter", "thunder-acceptance.winter"}) {
            weather.set(key, .33);
        }
        check(!WeatherDefaults.upgrade(weather), "Customized probabilities remain intact");
        check(!WeatherDefaults.upgrade(new YamlConfiguration()), "Missing settings remain available for default merging");
        System.out.println("Weather defaults: 9 assertions passed");
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
