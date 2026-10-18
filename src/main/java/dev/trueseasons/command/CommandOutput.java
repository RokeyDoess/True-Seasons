package dev.trueseasons.command;

import dev.trueseasons.TrueSeasonsPlugin;
import dev.trueseasons.api.Season;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import org.bukkit.command.CommandSender;

/** Read-only summaries. Detailed work counters are opt-in and never inspect terrain. */
final class CommandOutput {
    private final TrueSeasonsPlugin plugin;
    CommandOutput(TrueSeasonsPlugin plugin){this.plugin=plugin;}
    private static Component label(String text){return Component.text(text,NamedTextColor.GRAY);}
    private static Component value(Object text,NamedTextColor color){return Component.text(String.valueOf(text),color);}
    private static NamedTextColor seasonColor(Season season){return switch(season){
        case SPRING->NamedTextColor.GREEN;case SUMMER->NamedTextColor.YELLOW;case AUTUMN->NamedTextColor.GOLD;case WINTER->NamedTextColor.AQUA;
    };}
    private boolean indexing(){return plugin.engine().jobs().stream().anyMatch(job->job.indexingSavedProgress());}
    private String phase(){
        if(plugin.state().cleanup)return "CLEANUP";
        if(plugin.state().clean)return "RESTORED";
        if(plugin.state().paused)return "PAUSED";
        if(indexing())return "READING SAVED PROGRESS";
        if(plugin.engine().worldEditsComplete())return plugin.integrations().pending()>0?"TERRAIN COMPLETE — MAP PENDING":"COMPLETE";
        if(!plugin.settings().enabled)return "DISABLED";
        if(plugin.engine().currentTps()<plugin.settings().minTps)return "WAITING FOR TPS";
        if(plugin.engine().waitingForTickTime())return "WAITING FOR TICK TIME";
        if(plugin.melting()&&plugin.state().meltColumns>0)return "SNOWMELT";
        if(plugin.melting())return "RESTORING TERRAIN";
        return "MIGRATING";
    }
    private Component header(String title){
        String phase=phase();NamedTextColor color=phase.equals("COMPLETE")||phase.equals("RESTORED")?NamedTextColor.GREEN
            :phase.equals("PAUSED")||phase.contains("WAITING")||phase.contains("READING")?NamedTextColor.YELLOW:NamedTextColor.AQUA;
        return value(title,NamedTextColor.GOLD).decorate(TextDecoration.BOLD).append(label(" | "))
            .append(value(plugin.currentSeason(),seasonColor(plugin.currentSeason()))).append(label(" | ")).append(value(phase,color));
    }
    private Component tps(){
        double tps=plugin.engine().currentTps();NamedTextColor color=tps<plugin.settings().minTps?NamedTextColor.RED:tps>=19?NamedTextColor.GREEN:NamedTextColor.YELLOW;
        return label("TPS: ").append(value(String.format(Locale.ROOT,"%.2f",tps),color));
    }
    private Component ratio(long done,long total,double percent){
        return value(done+" / "+total+" chunks ("+String.format(Locale.ROOT,"%.1f",percent)+"%)",NamedTextColor.AQUA);
    }
    private void coverage(CommandSender sender){
        if(indexing()||plugin.state().cleanup||plugin.state().clean)return;
        if(plugin.melting()){
            if(plugin.state().meltColumns>0||!plugin.engine().worldEditsComplete())sender.sendMessage(label("Snowmelt: ").append(value(String.format(Locale.ROOT,"%.1f%% remaining",plugin.state().meltColumns*100.0/256),NamedTextColor.AQUA))
                .append(label(" · follows terrain restoration")));
        }
        else if(plugin.currentSeason()==Season.WINTER)sender.sendMessage(label("Winter coverage: ")
            .append(value(String.format(Locale.ROOT,"%.1f%%",plugin.engine().globalCoverageColumns()*100.0/256),NamedTextColor.AQUA)));
    }
    private void map(CommandSender sender){
        int pending=plugin.integrations().pending();
        Component row=label("BlueMap: ").append(value(plugin.integrations().summary(),pending>0?NamedTextColor.GOLD:NamedTextColor.GREEN));
        if(pending>0)row=row.append(label(" · ")).append(value(mapPending(),NamedTextColor.GOLD));
        sender.sendMessage(row.hoverEvent(Component.text(plugin.integrations().status(),NamedTextColor.GRAY)));
    }
    Component progressUpdate(){
        Component row=header("Migration status").append(label(" · "));
        if(plugin.state().paused)row=row.append(value(pauseReason(),NamedTextColor.YELLOW)).append(label(" · "));
        if(indexing())row=row.append(label("reading saved progress"));
        else {
            long done=0,total=0;double partial=0;
            for(var job:plugin.engine().jobs()){done+=job.completed();total+=job.total();partial+=job.total()*job.percent();}
            row=row.append(ratio(done,total,total==0?100:partial/total));
            if(!plugin.state().cleanup&&!plugin.state().clean){
                if(plugin.melting())row=row.append(label(" · snow remaining: ")).append(value(String.format(Locale.ROOT,"%.1f%%",plugin.state().meltColumns*100.0/256),NamedTextColor.AQUA));
                else if(plugin.currentSeason()==Season.WINTER)row=row.append(label(" · snow: ")).append(value(String.format(Locale.ROOT,"%.1f%%",plugin.engine().globalCoverageColumns()*100.0/256),NamedTextColor.AQUA));
            }
        }
        int pending=plugin.integrations().pending();
        return row.append(label(" · BlueMap: ")).append(value(pending>0?mapPending():plugin.integrations().summary(),pending>0?NamedTextColor.GOLD:NamedTextColor.GREEN));
    }
    private String mapPending(){int regions=plugin.integrations().pendingRegions();return regions>0?regions+" regions pending":"updates pending";}
    private String pauseReason(){return plugin.state().pauseReason==null||plugin.state().pauseReason.isBlank()
        ?"Saved pause; reason unavailable in the older build.":plugin.state().pauseReason;}
    private void pauseDetails(CommandSender sender){
        if(plugin.state().paused)sender.sendMessage(label("Pause: ").append(value(pauseReason(),NamedTextColor.YELLOW))
            .append(label(" · ")).append(value("/ts migration resume",NamedTextColor.AQUA)));
    }
    void overview(CommandSender sender){
        sender.sendMessage(header("Season status"));
        pauseDetails(sender);
        var visual=plugin.visualState();
        if(visual!=null&&!visual.eligibleWorlds().isEmpty()&&visual.colors().progress()<256)
            sender.sendMessage(label("Seasonal colors: ").append(value(String.format(Locale.ROOT,"%.1f%%",visual.colors().progress()*100.0/256),NamedTextColor.AQUA)).append(label(" · calendar transition")));
        long done=0,total=0,errors=0;double partial=0;
        for(var job:plugin.engine().jobs()){done+=job.completed();total+=job.total();errors+=job.errors();partial+=job.total()*job.percent();}
        Component progress=indexing()?label("reading saved progress"):ratio(done,total,total==0?100:partial/total);
        sender.sendMessage(label("Terrain: ").append(progress).append(label(" | ")).append(tps()));
        coverage(sender);map(sender);
        if(errors>0)sender.sendMessage(value(errors+" deferred chunks",NamedTextColor.RED).append(label(" · /ts migration details")));
        unavailable(sender);
        if(plugin.state().clean||plugin.state().cleanup)removal(sender);
    }
    void migration(CommandSender sender,boolean details,boolean cleanup){
        sender.sendMessage(header(cleanup?"Cleanup status":"Migration status"));
        pauseDetails(sender);
        sender.sendMessage(tps().append(label(" | ")).append(value("center-outward region order",NamedTextColor.GRAY)));
        for(var job:plugin.engine().jobs()){
            Component row=value(job.world.getName(),NamedTextColor.GOLD).append(label(": "));
            if(job.indexingSavedProgress())row=row.append(label("reading saved progress"));
            else {
                row=row.append(ratio(job.completed(),job.total(),job.percent()));
                if(!job.reportedFinished())row=row.append(label(" · region ")).append(value(job.activeRegion(),NamedTextColor.AQUA));
                if(job.errors()>0)row=row.append(label(" · ")).append(value(job.errors()+" deferred",NamedTextColor.RED));
                if(details)row=row.append(label(" · blocks +")).append(value(job.placed(),NamedTextColor.AQUA)).append(label(" / −")).append(value(job.removed(),NamedTextColor.AQUA));
            }
            sender.sendMessage(row);
            if(details&&job.error()!=null)sender.sendMessage(value("Index error: "+job.error(),NamedTextColor.RED));
        }
        coverage(sender);map(sender);unavailable(sender);
        if(details){
            var local=plugin.localSnow();var presentation=plugin.presentation();
            if(local!=null)sender.sendMessage(label("Preview planning: ").append(value(local.pendingPreviews()+" chunks queued",NamedTextColor.AQUA))
                .append(label(" · ")).append(value(local.checkedPreviews()+" visible chunks checked",NamedTextColor.GRAY)));
            if(presentation!=null){
                var stats=presentation.diagnostics();
                sender.sendMessage(label("Player catch-up: ").append(value(stats.catchingUp()+" / "+stats.viewers()+" viewers",NamedTextColor.AQUA))
                    .append(label(" · preview blocks: ")).append(value(stats.previewColumns(),NamedTextColor.AQUA))
                    .append(label(" · hidden: ")).append(value(stats.hiddenColumns(),NamedTextColor.AQUA)));
                sender.sendMessage(label("Snow refreshes last tick: ").append(value(stats.recentChunks()+" chunk visits",NamedTextColor.AQUA))
                    .append(label(" · ")).append(value(stats.recentBlocks()+" block updates",NamedTextColor.AQUA)));
            }
            int metadata=plugin.integrations().cleanupPending();
            if(metadata>0)sender.sendMessage(label("Map cleanup: ").append(value(metadata+" metadata records pending",NamedTextColor.GOLD)));
        }
        if(cleanup||plugin.state().cleanup||plugin.state().clean)removal(sender);
    }
    private void unavailable(CommandSender sender){
        for(var entry:plugin.state().managedWorlds.entrySet())if(org.bukkit.Bukkit.getWorld(UUID.fromString(entry.getKey()))==null)
            sender.sendMessage(value("World unavailable: ",NamedTextColor.YELLOW).append(value(entry.getValue(),NamedTextColor.GOLD)).append(label(" · load it to finish restoration.")));
    }
    private void removal(CommandSender sender){
        boolean safe=plugin.state().clean&&!plugin.state().cleanup&&plugin.engine().allFinished()&&plugin.weather().activeStorms()==0&&plugin.integrations().cleanupPending()==0;
        sender.sendMessage(label("Plugin removal: ").append(value(safe?"SAFE — stop the server before removing the jar":"NOT READY",safe?NamedTextColor.GREEN:NamedTextColor.YELLOW)));
        if(plugin.state().clean)sender.sendMessage(label("Use /ts season sync or /ts season set to re-enable seasons."));
    }
}
