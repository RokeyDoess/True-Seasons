package dev.trueseasons.visual;

import dev.trueseasons.api.Season;
import dev.trueseasons.calendar.SeasonCalendar;
import dev.trueseasons.state.StateStore;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

public final class SeasonColorTransitionTests {
    private static int checks;
    private static void check(boolean value,String text){checks++;if(!value)throw new AssertionError(text);}
    public static void main(String[] args)throws Exception{
        endpointScope();
        var months=Map.of(Season.SPRING,List.of(3,4,5),Season.SUMMER,List.of(6,7,8),Season.AUTUMN,List.of(9,10,11),Season.WINTER,List.of(12,1,2));
        var calendar=new SeasonCalendar(ZoneId.of("America/Los_Angeles"),months);
        Instant start=Instant.parse("2026-09-01T07:00:00Z");
        check(calendar.colorsAt(start,7).equals(new SeasonColorFrame(Season.SUMMER,Season.AUTUMN,0)),"Calendar colors begin at the previous season");
        for(int i=0;i<=256;i++){
            var frame=calendar.colorsAt(start.plusMillis(604800000L*i/256),7);
            check(frame.progress()==i,"Calendar progress derives directly from real elapsed time");
            check(frame.autumnCoverage()==i,"Autumn tree coverage follows the opening window");
        }
        check(calendar.colorsAt(start.plusSeconds(20*86400),7).equals(SeasonColorFrame.settled(Season.AUTUMN)),"A late startup settles immediately without replaying missed stages");
        check(calendar.colorsAt(start,0).equals(SeasonColorFrame.settled(Season.AUTUMN)),"Zero-day window changes immediately");
        Instant spring=Instant.parse("2026-03-01T08:00:00Z");
        check(calendar.colorsAt(spring.plusSeconds(14*86400L-1),14).progress()==255,"DST does not shorten a real-day opening window");
        check(calendar.colorsAt(spring.plusSeconds(14*86400L),14).progress()==256,"DST window completes after exact real elapsed days");
        var reordered=new SeasonCalendar(ZoneOffset.UTC,Map.of(Season.SPRING,List.of(12,1,2),Season.WINTER,List.of(3,4,5),Season.AUTUMN,List.of(6,7,8),Season.SUMMER,List.of(9,10,11)));
        check(reordered.colorsAt(Instant.parse("2027-01-01T00:00:00Z"),7).to()==Season.SPRING,"Custom wrapping calendar uses the correct target");
        check(reordered.colorsAt(Instant.parse("2026-06-01T00:00:00Z"),7).from()==Season.WINTER,"Custom calendar uses its actual preceding season");
        var config=new YamlConfiguration();
        for(Season season:Season.values())config.set("palettes.temperate."+season.key(),List.of(season==Season.SUMMER?"000000":"FFFFFF",season==Season.SUMMER?"FFFFFF":"000000"));
        var resolver=new SeasonVisualResolver(config);
        var halfway=new SeasonColorFrame(Season.SUMMER,Season.AUTUMN,128);
        check(resolver.resolve(halfway,"plains").grassRgb()==0x808080,"Intermediate grass colors blend RGB channels");
        check(resolver.resolve(halfway,"plains").foliageRgb()==0x808080,"Intermediate foliage colors blend RGB channels");
        config.set("transition.opening-days",0);var immediate=new SeasonVisualResolver(config);
        check(!immediate.fingerprint().equals(resolver.fingerprint())&&immediate.endpointFingerprint().equals(resolver.endpointFingerprint()),"Turning the client opening window off reconfigures aliases without requesting a redundant map pass");
        var customOrder=new SeasonVisualResolver(config,dev.trueseasons.climate.SeasonClimatePolicy.ALL,reordered::previous);
        check(customOrder.endpointFingerprint().equals(immediate.endpointFingerprint()),"Predecessor mapping does not invalidate unchanged map endpoint colors");
        UUID treeWorld=UUID.randomUUID();
        var springState=new SeasonalVisualState(resolver,Season.SPRING,Set.of(treeWorld),Map.of(treeWorld,1L));
        check(!springState.fingerprint(treeWorld).equals(new SeasonalVisualState(resolver,Season.SPRING,Set.of(treeWorld),Map.of(treeWorld,2L)).fingerprint(treeWorld)),"Spring tree selection is part of the map endpoint");
        config.set("transition.opening-days",7);config.set("spring-blossoms.enabled",false);
        var noBlossoms=new SeasonVisualResolver(config);
        check(resolver.registryFingerprint().equals(noBlossoms.registryFingerprint()),"Tree toggles do not rebuild or resend the biome registry");
        check(!springState.fingerprint(treeWorld).equals(new SeasonalVisualState(noBlossoms,Season.SPRING,Set.of(treeWorld),Map.of(treeWorld,1L)).fingerprint(treeWorld)),"Disabling blossoms invalidates the spring map endpoint");
        check(new SeasonalVisualState(resolver,Season.SUMMER,Set.of(treeWorld)).fingerprint(treeWorld).equals(new SeasonalVisualState(noBlossoms,Season.SUMMER,Set.of(treeWorld)).fingerprint(treeWorld)),"Disabling blossoms does not request summer map work");
        config.set("spring-blossoms.enabled",true);config.set("spring-blossoms.leaves-percent",15);
        var scattered=new SeasonVisualResolver(config);
        check(resolver.registryFingerprint().equals(scattered.registryFingerprint()),"Blossom density does not resend biome registries");
        check(!springState.fingerprint(treeWorld).equals(new SeasonalVisualState(scattered,Season.SPRING,Set.of(treeWorld),Map.of(treeWorld,1L)).fingerprint(treeWorld)),"Canopy density changes invalidate the spring map endpoint");
        check(new SeasonalVisualState(resolver,Season.AUTUMN,Set.of(treeWorld)).fingerprint(treeWorld).equals(new SeasonalVisualState(scattered,Season.AUTUMN,Set.of(treeWorld)).fingerprint(treeWorld)),"Spring density changes do not request autumn rendering");
        config.set("spring-blossoms.leaves-percent",25);config.set("spring-blossoms.enabled",false);
        for(String key:List.of("trees-percent","leaves-percent"))for(Object invalid:List.of(-1,101,1.5,"20",true,Long.MAX_VALUE)){
            var malformed=new YamlConfiguration();malformed.set("spring-blossoms."+key,invalid);
            try{new SeasonVisualResolver(malformed);throw new AssertionError("Invalid spring percentage accepted: "+invalid);}
            catch(IllegalArgumentException expected){check(expected.getMessage().contains(key),"Invalid spring percentage identifies its setting");}
        }
        config.set("autumn-trees.enabled",false);var noTrees=new SeasonVisualResolver(config);
        check(resolver.registryFingerprint().equals(noTrees.registryFingerprint()),"Both tree settings are independent from immutable biome aliases");
        check(!new SeasonalVisualState(resolver,Season.AUTUMN,Set.of(treeWorld)).fingerprint(treeWorld).equals(new SeasonalVisualState(noTrees,Season.AUTUMN,Set.of(treeWorld)).fingerprint(treeWorld)),"Autumn tree toggle still invalidates the autumn map endpoint");
        UUID world=UUID.randomUUID();String fingerprint=null;SeasonalVisualState endpoint=null;
        for(int i=0;i<=256;i++){
            var state=new SeasonalVisualState(resolver,Season.AUTUMN,Set.of(world),Map.of(world,1L),new SeasonColorFrame(Season.SUMMER,Season.AUTUMN,i));
            if(fingerprint==null){fingerprint=state.fingerprint(world);endpoint=state.endpoint();}
            check(state.fingerprint(world).equals(fingerprint)&&state.endpoint().equals(endpoint),"Intermediate client stages never change the map endpoint or render fingerprint");
        }
        Path directory=Files.createTempDirectory("trueseasons-color-state-");
        try{
            StateStore state=new StateStore(directory);state.lastSeason=Season.AUTUMN;state.colorFrame=halfway;
            state.paused=true;state.pausedCalendarProgress=.125;state.currentVersion="2026-AUTUMN-r4-snow4";state.winterColumns=110;state.meltColumns=27;state.save();
            StateStore restored=new StateStore(directory);
            check(restored.paused&&restored.colorFrame.equals(halfway)&&restored.pausedCalendarProgress==.125,"Paused visual target and calendar progress survive restart");
            check(restored.currentVersion.equals(state.currentVersion)&&restored.winterColumns==110&&restored.meltColumns==27,"Saving colors preserves the physical migration target and progress");
            Files.writeString(directory.resolve("jobs.yml"),"schema: 1\nlast-season: WINTER\npaused: true\n");
            check(new StateStore(directory).colorFrame==null,"Legacy paused jobs remain readable without new fields");
        }finally{
            Path root=Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            if(!directory.toRealPath().startsWith(root)||directory.toRealPath().equals(root))throw new IllegalStateException("Unsafe temporary cleanup");
            try(var files=Files.walk(directory)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
        }
        System.out.println("Seasonal color transitions: "+checks+" assertions passed");
    }
    private static void endpointScope(){
        var yaml=new YamlConfiguration();for(Season season:Season.values())yaml.set("palettes.temperate."+season.key(),List.of("83B957","68AE48"));
        // Captured from the published 0.3.2 jar, not calculated with this implementation.
        String legacyRoot="29915aeb152ed70ac91b2de0ca76515417c98ecda059b28552bfc9711f032daf";
        var legacyResolver=new SeasonVisualResolver(yaml);UUID legacyWorld=new UUID(0,1);
        publishedScopedAliases(yaml,legacyWorld);
        for(Season season:Season.values()){
            String suffix=season==Season.AUTUMN?"/trees=true/1":season==Season.SPRING?"/blossoms=true/scattered1/20/25/1":"";
            check(new SeasonalVisualState(legacyResolver,season,Set.of(legacyWorld),Map.of(legacyWorld,1L)).legacyFingerprint(legacyWorld).equals(season.key()+"/"+legacyRoot+suffix),"Equivalent upgrade recognizes the exact published 0.3.2 target for "+season);
        }
        UUID world=UUID.randomUUID();var climate=new dev.trueseasons.climate.SeasonClimatePolicy(List.of("desert"),List.of("snowy"),List.of("jungle"));
        var before=new SeasonVisualResolver(yaml,climate);
        yaml.set("palettes.temperate.spring",List.of("123456","ABCDEF"));var after=new SeasonVisualResolver(yaml,climate);
        for(Season season:Season.values()){
            var first=new SeasonalVisualState(before,season,Set.of(world),Map.of(world,1L));var next=new SeasonalVisualState(after,season,Set.of(world),Map.of(world,1L));
            check(first.fingerprint(world).equals(next.fingerprint(world))==(season!=Season.SPRING),"Only the edited season's endpoint requests new map pixels");
            check(!first.legacyFingerprint(world).equals(next.legacyFingerprint(world)),"Legacy signatures still describe their original complete configuration");
        }
        var summerOnly=new SeasonVisualResolver(yaml,new dev.trueseasons.climate.SeasonClimatePolicy(List.of("desert"),List.of("snowy","minecraft:grove"),List.of("jungle")));
        for(Season season:Season.values())check(after.endpointFingerprint(season).equals(summerOnly.endpointFingerprint(season))==(season!=Season.SUMMER),"Climate edits invalidate only their affected map season");
        check(!before.registryFingerprint().equals(after.registryFingerprint()),"Future biome registry entries still receive edited colors");
        yaml.set("palettes.temperate.autumn",List.of("FFFFFF","000000"));var autumn=new SeasonVisualResolver(yaml,climate);
        check(!after.endpointFingerprint(Season.AUTUMN).equals(autumn.endpointFingerprint(Season.AUTUMN)),"Active palette edits still redraw the map");
        yaml.set("palettes.temperate.summer",List.of("112233","445566"));var summer=new SeasonVisualResolver(yaml,climate);
        for(Season season:Season.values())check(autumn.endpointFingerprint(season).equals(summer.endpointFingerprint(season))==(season!=Season.SUMMER),"Native hot-biome colors never depend on an unused summer fallback palette in autumn or winter");
        yaml.set("palettes.temperate.winter",List.of("778899","AABBCC"));var winter=new SeasonVisualResolver(yaml,climate);
        for(Season season:Season.values())check(summer.endpointFingerprint(season).equals(winter.endpointFingerprint(season))==(season!=Season.WINTER),"Native cold-biome colors never depend on an unused winter fallback palette in summer");
        for(Season season:Season.values()){
            check(autumn.legacyScopedEndpointFingerprint(season).equals(summer.legacyScopedEndpointFingerprint(season))==(season==Season.SPRING),"Published 0.3.3 aliases retain their original summer fallback dependency for upgrade recognition");
            check(summer.legacyScopedEndpointFingerprint(season).equals(winter.legacyScopedEndpointFingerprint(season))==(season==Season.SPRING||season==Season.AUTUMN),"Published 0.3.3 aliases retain their original winter fallback dependency for upgrade recognition");
        }
        var allBefore=new SeasonVisualResolver(yaml,dev.trueseasons.climate.SeasonClimatePolicy.ALL);
        yaml.set("palettes.temperate.summer",List.of("000000","FFFFFF"));var allAfter=new SeasonVisualResolver(yaml,dev.trueseasons.climate.SeasonClimatePolicy.ALL);
        for(Season season:Season.values())check(allBefore.endpointFingerprint(season).equals(allAfter.endpointFingerprint(season))==(season!=Season.SUMMER),"Without climate fallbacks only the edited summer map changes");
        yaml.set("enabled",false);var disabled=new SeasonalVisualState(new SeasonVisualResolver(yaml,climate),Season.AUTUMN,Set.of(world));
        check(disabled.fingerprint(world).equals("vanilla")&&disabled.legacyFingerprint(world).equals("vanilla"),"Inactive worlds keep the same native restoration target");
    }
    private static void publishedScopedAliases(YamlConfiguration yaml,UUID world){
        // Captured by running the published 0.3.3 jar with these exact palettes and policies.
        var all=Map.of(
            Season.SPRING,"e1c6f16414842692308c4e1080a47a275708549c728d706193e4bab829050a6d",
            Season.SUMMER,"9e21c612d18ce2d57d822e554bc658ad8eac8284498a7c19104c8efdbac17869",
            Season.AUTUMN,"4ab05c9a6a77bbd8bf77478c1b930e31c2f081c6a88986927fd5e543ab0d1377",
            Season.WINTER,"531a257a8c4c57e5d1ef10f2aa57ab66e2b1995eaca2e20b4710aaaa9f6ac17f");
        var excluded=Map.of(
            Season.SPRING,all.get(Season.SPRING),
            Season.SUMMER,"6812c9ad27c0359e76a679e97f85e9f7ef2ac0a0c9e7de8c5483b1389aceb5db",
            Season.AUTUMN,"6937f77d1239bced9cb8c7aa2b532ee0caba0dcea0a8c530fc58417e61ca86cf",
            Season.WINTER,"245c9fc8fad9a7123b4d959856d2a580c2e583783af9a8e0eabd381518a05eaa");
        for(boolean exemptions:List.of(false,true)){
            var climate=exemptions?new dev.trueseasons.climate.SeasonClimatePolicy(List.of("desert"),List.of("snowy"),List.of("jungle")):dev.trueseasons.climate.SeasonClimatePolicy.ALL;
            var resolver=new SeasonVisualResolver(yaml,climate);
            for(Season season:Season.values()){
                var state=new SeasonalVisualState(resolver,season,Set.of(world),Map.of(world,1L));
                String suffix=season==Season.AUTUMN?"/trees=true/1":season==Season.SPRING?"/blossoms=true/scattered1/20/25/1":"";
                String published=season.key()+"/"+(exemptions?excluded:all).get(season)+suffix;
                check(state.fingerprintAliases(world).contains(published),"Equivalent saved 0.3.3 target remains recognizable for "+season+" exclusions="+exemptions);
                if(!exemptions)check(state.fingerprint(world).equals(published),"No fingerprint rewrite is needed when the older palette already described only active map colors");
            }
        }
    }
}
