package dev.trueseasons.api;

import org.bukkit.Chunk;
import org.bukkit.event.*;

public final class SeasonChunkUpdatedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Chunk chunk;
    private final String version;
    private final boolean cleanup, changed;
    public SeasonChunkUpdatedEvent(Chunk chunk, String version, boolean cleanup, boolean changed) {
        this.chunk = chunk; this.version = version; this.cleanup = cleanup; this.changed = changed;
    }
    public Chunk getChunk() { return chunk; }
    public String getVersion() { return version; }
    public boolean isCleanup() { return cleanup; }
    public boolean hasBlockChanges() { return changed; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
