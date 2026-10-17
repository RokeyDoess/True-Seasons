package dev.trueseasons.notification;

import dev.trueseasons.api.Season;
import dev.trueseasons.config.Settings;
import dev.trueseasons.state.StateStore;
import java.util.Collection;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/** Main-thread, server-wide notices; saved markers suppress restart and rescan repeats. */
public final class SeasonAnnouncements {
    private final StateStore state;
    private final Supplier<Settings> settings;
    private final Supplier<? extends Collection<? extends Player>> players;
    private final Runnable persist;
    public SeasonAnnouncements(StateStore state, Supplier<Settings> settings,
            Supplier<? extends Collection<? extends Player>> players, Runnable persist) {
        this.state=state;this.settings=settings;this.players=players;this.persist=persist;
    }
    /** An upgrade/startup is not a fresh transition. Preserve an already pending notice. */
    public void resume(String version) {
        if(version.equals(state.announcementVersion))return;
        state.announcementVersion=version;state.migrationAnnouncedComplete=true;persist.run();
    }
    public void start(Season previous, Season next, String version) {
        if(version.equals(state.announcementVersion))return;
        state.announcementVersion=version;
        state.migrationAnnouncedComplete=!settings.get().enabled||state.cleanup||state.clean;
        persist.run();
        if(state.migrationAnnouncedComplete)return;
        Component season=Component.text(name(next)+" has begun.",color(next));
        Component migration=prefix().append(Component.text(name(next),color(next)))
            .append(Component.text(" migration started. Check ",NamedTextColor.GRAY))
            .append(Component.text("/ts migration",NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand("/ts migration")))
            .append(Component.text(" for more details.",NamedTextColor.GRAY));
        for(Player player:players.get()){
            if(previous!=next&&settings.get().seasonAnnouncements)player.sendMessage(season);
            if(settings.get().migrationAnnouncements&&admin(player))player.sendMessage(migration);
        }
    }
    public boolean pending(String version) {
        return version.equals(state.announcementVersion)&&!state.migrationAnnouncedComplete&&!state.cleanup&&!state.clean;
    }
    public void complete(String version, boolean terrainComplete, int pendingRegions) {
        if(!terrainComplete||pendingRegions!=0||!pending(version))return;
        state.migrationAnnouncedComplete=true;persist.run();
        if(settings.get().migrationAnnouncements){
            Component message=prefix().append(Component.text("Migration complete.",NamedTextColor.GREEN));
            for(Player player:players.get())if(admin(player))player.sendMessage(message);
        }
    }
    public void cancel() {
        if(state.migrationAnnouncedComplete)return;
        state.migrationAnnouncedComplete=true;persist.run();
    }
    public void paused(String reason){
        if(!settings.get().migrationAnnouncements)return;
        Component message=prefix().append(Component.text("Migration paused: "+reason,NamedTextColor.RED))
            .append(Component.text(" Check /ts migration.",NamedTextColor.GRAY).clickEvent(ClickEvent.runCommand("/ts migration")));
        for(Player player:players.get())if(admin(player))player.sendMessage(message);
    }
    private static boolean admin(Player player){return player.isOp()||player.hasPermission("trueseasons.command.admin");}
    private static Component prefix(){return Component.text("[True Seasons] ",NamedTextColor.GOLD);}
    private static String name(Season season){String key=season.key();return Character.toUpperCase(key.charAt(0))+key.substring(1);}
    private static NamedTextColor color(Season season){return switch(season){
        case SPRING->NamedTextColor.GREEN;case SUMMER->NamedTextColor.YELLOW;
        case AUTUMN->NamedTextColor.GOLD;case WINTER->NamedTextColor.AQUA;
    };}
}
