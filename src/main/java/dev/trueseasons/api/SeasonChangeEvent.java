package dev.trueseasons.api;

import org.bukkit.World;
import org.bukkit.event.*;

public final class SeasonChangeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final World world;
    private final Season previous, current;
    public SeasonChangeEvent(World world, Season previous, Season current) {
        this.world = world; this.previous = previous; this.current = current;
    }
    public World getWorld() { return world; }
    public Season getPreviousSeason() { return previous; }
    public Season getCurrentSeason() { return current; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
