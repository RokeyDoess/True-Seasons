package dev.trueseasons.integration;

import com.flowpowered.math.vector.Vector2i;
import de.bluecolored.bluemap.api.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** BlueMap 5 bridge: the public API exposes acceptance, but no completion receipt. */
final class BlueMapRenderBridge {
    @FunctionalInterface interface Finalizer { void finish() throws ReflectiveOperationException; }
    private final Object manager;
    private final Class<?> taskType, mapType, strategyType;
    private final Method scheduleTask, scheduledTasks, removeTask, hasMoreWork, doWork, cancelTask, description;
    private final ClassLoader loader;
    BlueMapRenderBridge(RenderManager api) throws ReflectiveOperationException {
        loader = api.getClass().getClassLoader();
        Field field = api.getClass().getDeclaredField("renderManager"); field.setAccessible(true);
        manager = field.get(api);
        taskType = Class.forName("de.bluecolored.bluemap.common.rendermanager.RenderTask", true, loader);
        mapType = Class.forName("de.bluecolored.bluemap.core.map.BmMap", true, loader);
        strategyType = Class.forName("de.bluecolored.bluemap.common.rendermanager.TileUpdateStrategy", true, loader);
        scheduleTask = manager.getClass().getMethod("scheduleRenderTask", taskType);
        scheduledTasks = manager.getClass().getMethod("getScheduledRenderTasks");
        removeTask = manager.getClass().getMethod("removeRenderTask", taskType);
        hasMoreWork = taskType.getMethod("hasMoreWork"); doWork = taskType.getMethod("doWork");
        cancelTask = taskType.getMethod("cancel"); description = taskType.getMethod("getDescription");
    }
    Ticket submit(BlueMapMap map, Collection<Vector2i> regions) throws ReflectiveOperationException {
        Object nativeMap = map.getClass().getMethod("map").invoke(map);
        Object strategy = strategyType.getMethod("fixed", boolean.class).invoke(null, true);
        Object delegate = Class.forName("de.bluecolored.bluemap.common.rendermanager.MapUpdateTask", true, loader)
                .getConstructor(mapType, Collection.class, strategyType).newInstance(nativeMap, regions, strategy);
        List<?> children = (List<?>) delegate.getClass().getMethod("getTasks").invoke(delegate);
        List<Field> cancelled = new ArrayList<>(); List<Object> regionTasks = new ArrayList<>();
        for (Object child : children) if (child.getClass().getSimpleName().equals("WorldRegionUpdateTask")) {
            Field field = child.getClass().getDeclaredField("cancelled"); field.setAccessible(true);
            cancelled.add(field); regionTasks.add(child);
        }
        Object world = nativeMap.getClass().getMethod("getWorld").invoke(nativeMap);
        Method invalidate = world.getClass().getMethod("invalidateChunkCache", int.class, int.class);
        Class<?> tileState = Class.forName("de.bluecolored.bluemap.core.map.renderstate.TileState", true, loader);
        Object renderError = tileState.getField("RENDER_ERROR").get(null), chunkError = tileState.getField("CHUNK_ERROR").get(null);
        Ticket ticket = new Ticket(delegate, world, invalidate, List.copyOf(regions), regionTasks, cancelled,
                () -> finishMap(nativeMap, regionTasks, cancelled, renderError, chunkError));
        ticket.proxy = Proxy.newProxyInstance(loader, new Class<?>[]{taskType}, ticket);
        boolean accepted = (boolean) scheduleTask.invoke(manager, ticket.proxy);
        return accepted ? ticket : null;
    }
    boolean scheduled(Ticket ticket) throws ReflectiveOperationException {
        return ((List<?>) scheduledTasks.invoke(manager)).contains(ticket.proxy);
    }
    void cancel(Ticket ticket) {
        try { removeTask.invoke(manager, ticket.proxy); }
        catch (ReflectiveOperationException ignored) { ticket.cancel(); }
    }
    private static Object field(Object object, String name) throws ReflectiveOperationException {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object); }
            catch (NoSuchFieldException ignored) { /* Inherited BlueMap state storage. */ }
        }
        throw new NoSuchFieldException(name);
    }
    /** Runs on a render worker only after every native region worker has returned. */
    static void finishMap(Object map, List<Object> regions, List<Field> cancellations, Object renderError, Object chunkError)
            throws ReflectiveOperationException {
        Object tileState = map.getClass().getMethod("getMapTileState").invoke(map);
        Method getTile = tileState.getClass().getMethod("get", int.class, int.class);
        for (int n = 0; n < regions.size(); n++) {
            Object region = regions.get(n);
            if (cancellations.get(n).getBoolean(region)) throw new ReflectiveOperationException("BlueMap region was cancelled");
            Vector2i minimum = (Vector2i) field(region, "tileMin"), size = (Vector2i) field(region, "tileSize");
            if (minimum == null || size == null) throw new ReflectiveOperationException("BlueMap region did not finish initialization");
            for (int x = 0; x < size.getX(); x++) for (int z = 0; z < size.getY(); z++) {
                Object info = getTile.invoke(tileState, minimum.getX() + x, minimum.getY() + z);
                Object state = info.getClass().getMethod("getState").invoke(info);
                if (state == renderError || state == chunkError) throw new ReflectiveOperationException("BlueMap tile rendering failed");
            }
        }
        // CombinedRenderTask can advance to its own save while last tile workers are still active.
        // This second save is deliberately after those workers, including their final lowres/state writes.
        map.getClass().getMethod("save").invoke(map);
        Object lowres = map.getClass().getMethod("getLowresTileManager").invoke(map);
        for (Object layer : (Object[]) field(lowres, "layers")) synchronized (layer) {
            if (!((Map<?, ?>) field(layer, "pendingChanges")).isEmpty())
                throw new ReflectiveOperationException("BlueMap lowres storage still has unsaved tiles");
        }
        for (String getter : List.of("getMapTileState", "getMapChunkState", "getMapRegionState")) {
            Object storage = map.getClass().getMethod(getter).invoke(map);
            synchronized (storage) {
                for (Object cell : ((Map<?, ?>) field(storage, "cells")).values())
                    if ((boolean) cell.getClass().getMethod("isModified").invoke(cell))
                        throw new ReflectiveOperationException("BlueMap render state failed to save");
            }
        }
    }
    final class Ticket implements InvocationHandler {
        private final Object delegate, world;
        private final Method invalidate;
        private final List<Object> regions;
        private final List<Vector2i> coordinates;
        private final List<Field> cancelledFields;
        private final Finalizer finalizer;
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger nativeActive = new AtomicInteger();
        private volatile boolean cancelled, failed, finished;
        private volatile String failureDetail;
        private boolean initialized, saving;
        private volatile boolean saved;
        private Object proxy;
        Ticket(Object delegate, Object world, Method invalidate, List<Vector2i> coordinates, List<Object> regions, List<Field> fields, Finalizer finalizer) {
            this.delegate = delegate; this.world = world; this.invalidate = invalidate;
            this.coordinates = coordinates; this.regions = regions; this.cancelledFields = fields;
            this.finalizer = finalizer;
        }
        boolean finished() { return finished; }
        String failure() { return failureDetail != null ? failureDetail : "render was cancelled or incomplete"; }
        boolean successful() {
            if (!finished || failed || cancelled) return false;
            try {
                for (int n = 0; n < regions.size(); n++) if (cancelledFields.get(n).getBoolean(regions.get(n))) return false;
                return true;
            } catch (IllegalAccessException failure) { return false; }
        }
        void cancel() {
            cancelled = true;
            synchronized (this) { notifyAll(); }
            try { cancelTask.invoke(delegate); }
            catch (ReflectiveOperationException failure) { failed = true; }
        }
        @Override public Object invoke(Object object, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "equals" -> object == args[0];
                case "hashCode" -> System.identityHashCode(object);
                case "toString", "getDescription" -> "TrueSeasons: " + description.invoke(delegate);
                // Identity prevents older native tasks from swallowing a newer final revision.
                case "contains" -> object == args[0];
                case "cancel" -> { cancel(); yield null; }
                case "hasMoreWork" -> {
                    boolean more = !cancelled && !failed && (!saved || (boolean) hasMoreWork.invoke(delegate));
                    if (!more && active.get() == 0) finished = true;
                    yield more;
                }
                case "doWork" -> {
                    active.incrementAndGet();
                    try {
                        boolean nativeWork = false, finish = false;
                        synchronized (this) {
                            if (!initialized) {
                                // Evict only this bounded batch; otherwise BlueMap can reuse a partial chunk.
                                for (Vector2i region : coordinates) for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++)
                                    invalidate.invoke(world, region.getX() * 32 + x, region.getY() * 32 + z);
                                initialized = true;
                            }
                            if (!cancelled && !failed) {
                                nativeWork = (boolean) hasMoreWork.invoke(delegate);
                                // Reserve delegate work under the same lock as the final-save gate.
                                if (nativeWork) nativeActive.incrementAndGet();
                                else {
                                    while (!cancelled && !failed && (nativeActive.get() != 0 || (saving && !saved))) wait();
                                    finish = !cancelled && !failed && !saving && !saved;
                                    if (finish) saving = true;
                                }
                            }
                        }
                        if (nativeWork) {
                            try { doWork.invoke(delegate); }
                            finally { nativeActive.decrementAndGet(); synchronized (this) { notifyAll(); } }
                        } else if (finish) { finalizer.finish(); saved = true; }
                    } catch (ReflectiveOperationException | InterruptedException | RuntimeException failure) {
                        Throwable cause = failure instanceof InvocationTargetException wrapped ? wrapped.getCause() : failure;
                        failureDetail = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                        failed = true; cancel(); if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    }
                    finally { active.decrementAndGet(); synchronized (this) { notifyAll(); } }
                    yield null;
                }
                default -> {
                    try { yield method.invoke(delegate, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                }
            };
        }
    }
}
