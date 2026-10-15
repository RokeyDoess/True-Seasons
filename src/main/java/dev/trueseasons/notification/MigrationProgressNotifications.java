package dev.trueseasons.notification;

import java.util.*;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** Session-only subscriptions; one shared read-only summary for each due batch. */
public final class MigrationProgressNotifications {
    private static final int INTERVAL=100;
    private final Map<UUID,Integer> due=new LinkedHashMap<>();
    private final Function<UUID,Player> players;
    private final Supplier<Component> summary;
    private final BooleanSupplier complete;
    private int nextTick;
    public MigrationProgressNotifications(Function<UUID,Player> players,Supplier<Component> summary,BooleanSupplier complete){
        this.players=players;this.summary=summary;this.complete=complete;
    }
    public boolean toggle(UUID player,int tick){
        if(due.remove(player)!=null)return false;
        if(due.isEmpty())nextTick=tick+INTERVAL;
        due.put(player,tick+INTERVAL);return true;
    }
    public void remove(UUID player){due.remove(player);}
    public void tick(int tick){
        if(due.isEmpty()||tick-nextTick<0)return;
        Component message=null;boolean finished=false;int nearest=INTERVAL;
        for(var iterator=due.entrySet().iterator();iterator.hasNext();){
            var entry=iterator.next();int remaining=entry.getValue()-tick;
            if(remaining<=0){
                Player player=players.apply(entry.getKey());
                if(player==null||!player.isOnline()||!player.isOp()&&!player.hasPermission("trueseasons.command.migration")){iterator.remove();continue;}
                if(message==null){message=Objects.requireNonNull(summary.get());finished=complete.getAsBoolean();}
                player.sendMessage(message);
                if(finished){iterator.remove();continue;}
                entry.setValue(tick+INTERVAL);remaining=INTERVAL;
            }
            nearest=Math.min(nearest,remaining);
        }
        nextTick=tick+nearest;
    }
}
