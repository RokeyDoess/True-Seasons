package dev.trueseasons.api;

import org.bukkit.World;
import org.bukkit.event.*;

/** Common lifecycle event for migration and cleanup, shared by future adapters. */
public final class SeasonMigrationEvent extends Event {
    public enum Phase { START, COMPLETE }
    private static final HandlerList HANDLERS = new HandlerList();
    private final World world;
    private final boolean cleanup;
    private final Phase phase;
    public SeasonMigrationEvent(World world, boolean cleanup, Phase phase) {
        this.world = world; this.cleanup = cleanup; this.phase = phase;
    }
    public World getWorld() { return world; }
    public boolean isCleanup() { return cleanup; }
    public Phase getPhase() { return phase; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
