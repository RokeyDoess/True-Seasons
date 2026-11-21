package dev.trueseasons.integration;

import com.flowpowered.math.vector.Vector2i;
import de.bluecolored.bluemap.api.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

/** BlueMap 5 bridge: the public API exposes acceptance, but no completion receipt. */
final class BlueMapRenderBridge {
    private static final ClassValue<Map<String,Field>> FIELDS=new ClassValue<>(){
        @Override protected Map<String,Field> computeValue(Class<?> type){return new ConcurrentHashMap<>();}
    };
    private static final ClassValue<Map<String,Method>> GETTERS=new ClassValue<>(){
        @Override protected Map<String,Method> computeValue(Class<?> type){return new ConcurrentHashMap<>();}
    };
    private static final Object[] NO_ARGUMENTS=new Object[0];
    private static Object get(Object target,String name)throws ReflectiveOperationException{
        Map<String,Method> methods=GETTERS.get(target.getClass());Method method=methods.get(name);
        if(method==null){method=target.getClass().getMethod(name);methods.putIfAbsent(name,method);}
        return method.invoke(target,NO_ARGUMENTS);
    }
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
        return submit(map,regions,List.of());
    }
    /** Sparse maintenance still uses native tile preconditions, storage and the same final-save barrier. */
    Ticket submit(BlueMapMap map,Collection<Vector2i> regions,Collection<Vector2i> changedChunks)throws ReflectiveOperationException{
        Object nativeMap = nativeMap(map);
        Object strategy = strategyType.getMethod("fixed", boolean.class).invoke(null, true);
        Object delegate = Class.forName("de.bluecolored.bluemap.common.rendermanager.MapUpdateTask", true, loader)
                .getConstructor(mapType, Collection.class, strategyType).newInstance(nativeMap, regions, strategy);
        Object regionDelegate=delegate;
        Sparse sparse=changedChunks.isEmpty()?null:new Sparse(nativeMap,changedChunks,strategy);
        if(sparse!=null){
            Object full=delegate;
            delegate=Proxy.newProxyInstance(loader,new Class<?>[]{taskType},(proxy,method,args)->{
                return switch(method.getName()){
                    case "doWork" -> {if(!regions.isEmpty()&&(boolean)hasMoreWork.invoke(full))doWork.invoke(full);else sparse.work();yield null;}
                    case "hasMoreWork" -> !regions.isEmpty()&&(boolean)hasMoreWork.invoke(full)||sparse.more();
                    case "cancel" -> {cancelTask.invoke(full);sparse.cancel();yield null;}
                    case "contains","equals" -> proxy==args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "getDescription","toString" -> "updating seasonal map tiles";
                    case "estimateProgress" -> 0d;
                    default -> null;
                };
            });
        }
        Object fullDelegate=regionDelegate;
        // Sparse workers have their own native region adapters, without completing unrelated region hashes.
        List<?> children = fullDelegate==null?List.of():(List<?>) fullDelegate.getClass().getMethod("getTasks").invoke(fullDelegate);
        List<Field> cancelled = new ArrayList<>(); List<Object> regionTasks = new ArrayList<>();
        for (Object child : children) if (child.getClass().getSimpleName().equals("WorldRegionUpdateTask")) {
            Field field = child.getClass().getDeclaredField("cancelled"); field.setAccessible(true);
            cancelled.add(field); regionTasks.add(child);
        }
        if(sparse!=null){regionTasks.addAll(sparse.tasks.values());for(Object task:sparse.tasks.values())cancelled.add(access(task.getClass(),"cancelled"));}
        Object world = nativeMap.getClass().getMethod("getWorld").invoke(nativeMap);
        Method invalidate = world.getClass().getMethod("invalidateChunkCache", int.class, int.class);
        Class<?> tileState = Class.forName("de.bluecolored.bluemap.core.map.renderstate.TileState", true, loader);
        Object renderError = tileState.getField("RENDER_ERROR").get(null), chunkError = tileState.getField("CHUNK_ERROR").get(null);
        Ticket ticket = new Ticket(delegate, world, invalidate, List.copyOf(regions), regionTasks, cancelled,
                () -> {
                    if(sparse!=null)sparse.verify(renderError,chunkError);
                    int fullCount=regionTasks.size()-(sparse==null?0:sparse.tasks.size());
                    finishMap(nativeMap,regionTasks.subList(0,fullCount),cancelled.subList(0,fullCount),renderError,chunkError);
                });
        ticket.proxy = Proxy.newProxyInstance(loader, new Class<?>[]{taskType}, ticket);
        boolean accepted = (boolean) scheduleTask.invoke(manager, ticket.proxy);
        return accepted ? ticket : null;
    }
    private static Field access(Class<?> type,String name)throws NoSuchFieldException{
        Map<String,Field> fields=FIELDS.get(type);Field cached=fields.get(name);if(cached!=null)return cached;
        for(Class<?> owner=type;owner!=null;owner=owner.getSuperclass())try{
            Field field=owner.getDeclaredField(name);field.setAccessible(true);fields.putIfAbsent(name,field);return field;
        }catch(NoSuchFieldException missing){/* Check inherited BlueMap state storage. */}
        throw new NoSuchFieldException(name);
    }
    /** Native adapters deliberately retain unrelated saved chunk/region hashes. */
    private final class Sparse {
        private final Object map;
        private final LinkedHashMap<Vector2i,Object> tasks=new LinkedHashMap<>();
        private final List<Vector2i> tiles;
        private final Set<Vector2i> coveredChunks=new LinkedHashSet<>();
        private final Map<Vector2i,Object> owners=new HashMap<>();
        private final Method init,process;
        private int cursor;
        private boolean initialized,cancelled;
        Sparse(Object map,Collection<Vector2i> changed,Object strategy)throws ReflectiveOperationException{
            this.map=map;
            Object world=map.getClass().getMethod("getWorld").invoke(map);
            Object chunkGrid=world.getClass().getMethod("getChunkGrid").invoke(world);
            Object regionGrid=world.getClass().getMethod("getRegionGrid").invoke(world);
            Object hires=map.getClass().getMethod("getHiresModelManager").invoke(map);
            Object grid=hires.getClass().getMethod("getTileGrid").invoke(hires);
            Class<?> gridType=grid.getClass();
            Method intersect=chunkGrid.getClass().getMethod("getIntersecting",Vector2i.class,gridType);
            Set<Vector2i> selected=new LinkedHashSet<>();
            for(Vector2i chunk:changed)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
                Vector2i position=chunk.add(dx,dz);coveredChunks.add(position);
                @SuppressWarnings("unchecked") Collection<Vector2i> cells=(Collection<Vector2i>)intersect.invoke(chunkGrid,position,grid);selected.addAll(cells);
            }
            tiles=List.copyOf(selected);
            Class<?> regionType=Class.forName("de.bluecolored.bluemap.common.rendermanager.WorldRegionUpdateTask",true,loader);
            init=regionType.getDeclaredMethod("init");init.setAccessible(true);
            process=regionType.getDeclaredMethod("processTile",int.class,int.class);process.setAccessible(true);
            Method cellMin=gridType.getMethod("getCellMin",Vector2i.class),regionCell=regionGrid.getClass().getMethod("getCell",Vector2i.class);
            Constructor<?> makeRegion=regionType.getConstructor(mapType,Vector2i.class,strategyType);
            for(Vector2i tile:tiles){
                Vector2i block=(Vector2i)cellMin.invoke(grid,tile);
                Vector2i region=(Vector2i)regionCell.invoke(regionGrid,block);
                Object task=tasks.get(region);
                if(task==null){task=makeRegion.newInstance(map,region,strategy);tasks.put(region,task);}
                owners.put(tile,task);
            }
        }
        synchronized boolean more(){return !cancelled&&(!initialized||cursor<tiles.size());}
        synchronized void cancel()throws ReflectiveOperationException{cancelled=true;for(Object task:tasks.values())cancelTask.invoke(task);}
        void work()throws ReflectiveOperationException{
            Vector2i tile;Object task;
            synchronized(this){
                if(cancelled)return;
                if(!initialized){for(Object adapter:tasks.values())init.invoke(adapter);initialized=true;}
                if(cursor>=tiles.size())return;tile=tiles.get(cursor++);task=owners.get(tile);
            }
            if(access(task.getClass(),"cancelled").getBoolean(task))throw new ReflectiveOperationException("BlueMap tile preparation was cancelled");
            Vector2i min=(Vector2i)field(task,"tileMin");process.invoke(task,tile.getX()-min.getX(),tile.getY()-min.getY());
        }
        void verify(Object renderError,Object chunkError)throws ReflectiveOperationException{
            if(cancelled||!initialized||cursor!=tiles.size())throw new ReflectiveOperationException("BlueMap sparse render is incomplete");
            Object states=map.getClass().getMethod("getMapTileState").invoke(map);Method get=states.getClass().getMethod("get",int.class,int.class);
            for(Vector2i tile:tiles){Object info=get.invoke(states,tile.getX(),tile.getY());Object state=get(info,"getState");
                if(state==renderError||state==chunkError)throw new ReflectiveOperationException("BlueMap tile rendering failed");}
            Object hashes=map.getClass().getMethod("getMapChunkState").invoke(map);Method set=hashes.getClass().getMethod("set",int.class,int.class,int.class);
            for(Vector2i chunk:coveredChunks)for(Object task:tasks.values()){
                Vector2i min=(Vector2i)field(task,"chunkMin"),max=(Vector2i)field(task,"chunkMax");
                if(chunk.getX()<min.getX()||chunk.getX()>max.getX()||chunk.getY()<min.getY()||chunk.getY()>max.getY())continue;
                Method index=task.getClass().getDeclaredMethod("chunkIndex",int.class,int.class);index.setAccessible(true);
                int at=(int)index.invoke(task,chunk.getX()-min.getX(),chunk.getY()-min.getY());
                set.invoke(hashes,chunk.getX(),chunk.getY(),((int[])field(task,"chunkHashes"))[at]);break;
            }
        }
    }
    static Object nativeMap(BlueMapMap map) throws ReflectiveOperationException { return map.getClass().getMethod("map").invoke(map); }
    boolean scheduled(Ticket ticket) throws ReflectiveOperationException {
        return ((List<?>) scheduledTasks.invoke(manager)).contains(ticket.proxy);
    }
    void cancel(Ticket ticket) {
        try { removeTask.invoke(manager, ticket.proxy); }
        catch (ReflectiveOperationException ignored) { ticket.cancel(); }
    }
    private static Object field(Object object, String name) throws ReflectiveOperationException {
        return access(object.getClass(),name).get(object);
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
                Object state = get(info,"getState");
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
                    if ((boolean) get(cell,"isModified"))
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
