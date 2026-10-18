package dev.trueseasons.climate;

import dev.trueseasons.api.Season;
import java.util.*;
import dev.trueseasons.config.BiomeDefaults;
import dev.trueseasons.visual.SeasonVisualResolver;
import org.bukkit.configuration.file.YamlConfiguration;

public final class SeasonClimateTests {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        var policy = new SeasonClimatePolicy(SeasonClimatePolicy.HOT, SeasonClimatePolicy.COLD);
        for (String biome : List.of("desert","badlands","wooded_badlands","eroded_badlands","savanna","savanna_plateau","windswept_savanna","jungle","sparse_jungle","bamboo_jungle","warm_ocean")) {
            check(!policy.applies(Season.WINTER,"minecraft:"+biome), "Winter excludes hot " + biome);
            check(!policy.applies(Season.AUTUMN,"minecraft:"+biome), "Autumn excludes hot " + biome);
            check(policy.applies(Season.SUMMER,"minecraft:"+biome), "Hot summer remains applicable");
        }
        for (String biome : List.of("snowy_plains","snowy_taiga","snowy_beach","frozen_river","frozen_ocean","deep_frozen_ocean","ice_spikes","grove","jagged_peaks","frozen_peaks","cold_ocean","deep_cold_ocean")) {
            check(!policy.applies(Season.SUMMER,"minecraft:"+biome), "Summer excludes cold " + biome);
            check(policy.applies(Season.WINTER,"minecraft:"+biome), "Cold winter remains applicable");
        }
        for (Season season : Season.values()) check(policy.applies(season,"minecraft:plains"), "Temperate seasons are unaffected");
        for(String biome:List.of("minecraft:cherry_grove","minecraft:mangrove_swamp")){
            check(policy.applies(Season.SUMMER,biome),"Summer effects apply outside the cold grove biome: "+biome);
            check(policy.paletteSeason(Season.SUMMER,biome)==Season.SUMMER,"Summer palettes stay warm in "+biome);
        }
        check(policy.paletteSeason(Season.SUMMER,"minecraft:frozen_peaks") == Season.WINTER, "Cold palette stays cold during summer");
        check(policy.paletteSeason(Season.WINTER,"minecraft:jungle") == Season.SUMMER, "Hot palette stays warm during winter");
        check(policy.paletteSeason(Season.AUTUMN,"minecraft:jungle") == Season.SUMMER, "Hot palette stays warm during autumn");
        var customAutumn = new SeasonClimatePolicy(SeasonClimatePolicy.HOT,SeasonClimatePolicy.COLD,List.of());
        check(customAutumn.applies(Season.AUTUMN,"minecraft:jungle"),"Autumn exclusions can be overridden independently");
        check(customAutumn.sameWinter(policy),"Autumn-only edits do not reopen snow migration");
        check(!customAutumn.equals(policy)&&!customAutumn.signature().equals(policy.signature()),"Autumn-only edits invalidate seasonal colors");
        check(new SeasonClimatePolicy(List.of("custom:tropical"),List.of()).applies(Season.WINTER,"minecraft:desert"), "Explicit custom lists replace climate defaults");
        check(SeasonClimatePolicy.ALL.applies(Season.WINTER,"minecraft:desert"), "Empty list explicitly permits hot winter");
        check(new SeasonClimatePolicy(List.of("jungle","desert"),List.of()).sameWinter(new SeasonClimatePolicy(List.of("desert","jungle","jungle"),List.of("snowy"))), "Summer-only changes and reordered fragments do not reopen winter terrain");
        check(!policy.sameWinter(SeasonClimatePolicy.ALL), "Changed winter exclusions still require terrain reconciliation");
        for (String invalid : List.of("", "Desert", "*", " ", "foo bar")) {
            boolean rejected = false; try { new SeasonClimatePolicy(List.of(invalid),List.of()); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Invalid fragments fail settings validation");
        }
        var main=new YamlConfiguration();var visuals=new YamlConfiguration();
        var legacy=List.of("snowy","frozen","ice_spikes","grove","jagged_peaks","cold_ocean");
        main.set("climate.summer-excluded-biome-fragments",legacy);main.set("announcements.season-start",false);
        visuals.set("families.evergreen",List.of("taiga","grove"));
        check(BiomeDefaults.upgradeSummer(main)&&main.getStringList("climate.summer-excluded-biome-fragments").equals(SeasonClimatePolicy.COLD),"Existing shipped summer defaults upgrade automatically");
        check(!main.getBoolean("announcements.season-start"),"Climate upgrade preserves unrelated settings");
        check(BiomeDefaults.upgradeFamilies(visuals)&&visuals.getStringList("families.evergreen").equals(List.of("taiga","minecraft:grove")),"Existing evergreen defaults exclude accidental cherry and mangrove matches");
        check(!BiomeDefaults.upgradeSummer(main)&&!BiomeDefaults.upgradeFamilies(visuals),"Default upgrades are idempotent");
        var custom=new ArrayList<>(legacy);custom.add("custom:grove");main.set("climate.summer-excluded-biome-fragments",custom);
        check(!BiomeDefaults.upgradeSummer(main)&&main.getStringList("climate.summer-excluded-biome-fragments").equals(custom),"Customized summer lists are preserved exactly");
        visuals.set("families.evergreen",List.of("grove"));
        check(!BiomeDefaults.upgradeFamilies(visuals)&&visuals.getStringList("families.evergreen").equals(List.of("grove")),"Customized palette families are preserved");
        main.set("climate.summer-excluded-biome-fragments",List.of());check(!BiomeDefaults.upgradeSummer(main),"An explicit empty exemption list stays empty");
        try(var input=Objects.requireNonNull(SeasonClimateTests.class.getResourceAsStream("/visuals.yml"))){
            var defaults=YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8));
            var resolver=new SeasonVisualResolver(defaults,policy);
            check(resolver.resolve(Season.SUMMER,"minecraft:cherry_grove").family().equals("temperate"),"Shipped cherry grove palette is deciduous");
            check(resolver.resolve(Season.SUMMER,"minecraft:mangrove_swamp").family().equals("lush"),"Shipped mangrove palette is tropical");
            check(resolver.resolve(Season.SUMMER,"minecraft:grove").family().equals("evergreen"),"The cold grove retains its evergreen palette");
        }catch(java.io.IOException failure){throw new AssertionError(failure);}
        System.out.println("Season climate tests passed: " + checks + " assertions");
    }
}
