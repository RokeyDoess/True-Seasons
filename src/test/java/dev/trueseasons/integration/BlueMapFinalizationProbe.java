package dev.trueseasons.integration;

import de.bluecolored.bluemap.api.*;
import dev.trueseasons.TrueSeasonsPlugin;
import java.lang.reflect.*;
import java.util.List;
import org.bukkit.World;

/** Exercises the production receipt wrapper against one real map, without world edits. */
public final class BlueMapFinalizationProbe {
    private final TrueSeasonsPlugin seasons;
    private final World world;
    private final int x, z;
    private Object ticket;
    private Method finished, successful;
    public BlueMapFinalizationProbe(TrueSeasonsPlugin seasons, World world, int x, int z) {
        this.seasons=seasons; this.world=world; this.x=x; this.z=z;
    }
    public boolean tick() throws Exception {
        if (ticket==null) {
            var api=BlueMapAPI.getInstance(); if(api.isEmpty()) return false;
            var mapped=api.get().getWorld(world); if(mapped.isEmpty()) return false;
            var map=mapped.get().getMaps().stream().filter(m -> !m.isFrozen()).findFirst(); if(map.isEmpty()) return false;
            // The test plugin and production plugin have different runtime packages.
            // Reflect into the production class rather than copying the implementation.
            Class<?> bridgeType=seasons.getClass().getClassLoader().loadClass("dev.trueseasons.integration.BlueMapRenderBridge");
            Constructor<?> constructor=bridgeType.getDeclaredConstructor(RenderManager.class); constructor.setAccessible(true);
            Object bridge=constructor.newInstance(api.get().getRenderManager());
            Method submit=bridgeType.getDeclaredMethod("submit",BlueMapMap.class,java.util.Collection.class); submit.setAccessible(true);
            ticket=submit.invoke(bridge,map.get(),List.of(new com.flowpowered.math.vector.Vector2i(Math.floorDiv(x,32),Math.floorDiv(z,32))));
            if(ticket==null) return false;
            finished=ticket.getClass().getDeclaredMethod("finished"); finished.setAccessible(true);
            successful=ticket.getClass().getDeclaredMethod("successful"); successful.setAccessible(true);
        }
        if(!(boolean)finished.invoke(ticket)) return false;
        if(!(boolean)successful.invoke(ticket)) throw new AssertionError("Real BlueMap final tile/save barrier rejected its render");
        return true;
    }
}
