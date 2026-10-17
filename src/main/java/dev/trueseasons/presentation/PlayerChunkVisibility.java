package dev.trueseasons.presentation;

import java.util.Set;
import org.bukkit.entity.Player;

/** Main-thread visibility queries can be unavailable while Paper detaches a player chunk loader. */
public final class PlayerChunkVisibility {
    private PlayerChunkVisibility() {}
    private static boolean active(Player player){return player.isOnline()&&player.isValid()&&!player.isDead();}
    public static Set<Long> sent(Player player){
        if(!active(player))return Set.of();
        try{return player.getSentChunkKeys();}
        catch(NullPointerException|IllegalStateException unavailable){return Set.of();}
    }
    public static boolean sent(Player player,long key){
        if(!active(player))return false;
        try{return player.isChunkSent(key);}
        catch(NullPointerException|IllegalStateException unavailable){return false;}
    }
}
