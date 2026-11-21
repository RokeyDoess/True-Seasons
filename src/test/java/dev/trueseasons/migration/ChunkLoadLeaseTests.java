package dev.trueseasons.migration;

import dev.trueseasons.TrueSeasonsPlugin;
import dev.trueseasons.config.Settings;
import dev.trueseasons.state.StateStore;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import org.bukkit.*;

/** Budget handoff must not duplicate requests, force chunk loads or retain work through pause/shutdown. */
public final class ChunkLoadLeaseTests {
    private static int checks,tick=37;
    private static World current;
    private static final Class<?> READY;
    static {try{READY=Class.forName("dev.trueseasons.migration.ChunkMigrationEngine$Ready");}catch(Exception error){throw new ExceptionInInitializerError(error);}}
    private static Object allocate(Class<?> type)throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field field=unsafe.getDeclaredField("theUnsafe");field.setAccessible(true);
        return unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),type);
    }
    private static void set(Object object,String name,Object value)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    private static Object get(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static Object identity(Object proxy,String name,Object[] args){return switch(name){case "equals"->proxy==args[0];case "hashCode"->System.identityHashCode(proxy);case "toString"->"ChunkLeaseFixture";default->throw new AssertionError("Unexpected API: "+name);};}
    @SuppressWarnings("unchecked") private static <T>T proxy(Class<T> type,InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
    private static final class Fixture {
        final UUID id=UUID.randomUUID();final ChunkPos pos=new ChunkPos(4,-3);final World world;final Chunk chunk;
        final ChunkMigrationEngine.Job job;final ChunkMigrationEngine engine;final StateStore state;
        boolean loaded=true;int unloads,loads;
        Fixture(boolean alreadyLoaded)throws Exception{
            world=proxy(World.class,(p,m,a)->switch(m.getName()){
                case "getUID"->id;case "isChunkLoaded"->loaded;case "unloadChunkRequest"->{unloads++;yield true;}
                case "getChunkAtAsync"->{check(a.length==4&&a[2].equals(false)&&a[3].equals(false),"Admission explicitly forbids terrain generation and urgent loads");loads++;yield CompletableFuture.completedFuture(suppliedChunk());}
                default->identity(p,m.getName(),a);
            });current=world;
            chunk=proxy(Chunk.class,(p,m,a)->{
                if(!loaded)throw new AssertionError("An unloaded wrapper must never be inspected: "+m.getName());
                return switch(m.getName()){
                    case "getPlayersSeeingChunk","getPluginChunkTickets"->List.of();case "isForceLoaded"->false;
                    default->throw new AssertionError("No terrain or ticket API is allowed in a budget handoff: "+m.getName());
                };
            });
            state=(StateStore)allocate(StateStore.class);Settings settings=(Settings)allocate(Settings.class);
            set(settings,"enabled",true);set(settings,"perTick",1);set(settings,"millis",3d);set(settings,"concurrency",4);
            TrueSeasonsPlugin plugin=(TrueSeasonsPlugin)allocate(TrueSeasonsPlugin.class);set(plugin,"settings",settings);set(plugin,"state",state);
            job=new ChunkMigrationEngine.Job(world);job.scanned=true;job.known.add(pos);job.flight.add(pos);
            engine=(ChunkMigrationEngine)allocate(ChunkMigrationEngine.class);set(engine,"plugin",plugin);set(engine,"jobs",Map.of(id,job));
            set(engine,"inFlight",1);set(engine,"ready",new ArrayDeque<>());set(engine,"applicationTick",tick);set(engine,"applications",1);
            var constructor=READY.getDeclaredConstructor(ChunkMigrationEngine.Job.class,ChunkPos.class,Chunk.class,boolean.class,int.class,Throwable.class);constructor.setAccessible(true);
            finish(constructor.newInstance(job,pos,chunk,alreadyLoaded,0,null));
        }
        @SuppressWarnings("unchecked") ArrayDeque<Object> pending()throws Exception{return (ArrayDeque<Object>)get(engine,"ready");}
        Chunk suppliedChunk(){return chunk;}
        void finish(Object value)throws Exception{Method method=ChunkMigrationEngine.class.getDeclaredMethod("finish",READY);method.setAccessible(true);method.invoke(engine,value);}
        void resume()throws Exception{finish(pending().removeFirst());}
    }
    public static void main(String[] args)throws Exception{
        Server server=proxy(Server.class,(p,m,a)->switch(m.getName()){
            case "getWorld"->current;case "getCurrentTick"->tick;case "isPrimaryThread"->true;case "getLogger"->Logger.getAnonymousLogger();
            case "getName","getVersion","getBukkitVersion"->"LeaseVerification";default->identity(p,m.getName(),a);
        });Field serverField=Bukkit.class.getDeclaredField("server");serverField.setAccessible(true);serverField.set(null,server);
        var deferred=new Fixture(false);
        check(deferred.pending().size()==1&&deferred.job.flight.contains(deferred.pos)&&(int)get(deferred.engine,"inFlight")==1,"A budget-full callback retains one bounded in-flight request");
        check(deferred.unloads==0&&deferred.job.queued.isEmpty()&&!deferred.job.regionProcessed(0,-1),"A deferred request neither releases its region nor asks for an unload");
        deferred.resume();check(deferred.pending().size()==1&&(int)get(deferred.engine,"inFlight")==1,"Another exhausted budget cannot duplicate the request or flight accounting");
        deferred.state.paused=true;deferred.resume();
        check(deferred.pending().isEmpty()&&deferred.job.flight.isEmpty()&&(int)get(deferred.engine,"inFlight")==0,"Pausing releases all retained flight accounting");
        check(deferred.unloads==1&&deferred.job.queued.contains(deferred.pos)&&!deferred.job.done.contains(deferred.pos),"Paused work requeues without claiming completion");
        var unloaded=new Fixture(false);unloaded.loaded=false;unloaded.resume();
        check(unloaded.pending().isEmpty()&&unloaded.unloads==0&&unloaded.job.queued.contains(unloaded.pos),"A naturally unloaded handoff requeues without touching or loading its wrapper");
        var obsolete=new Fixture(false);obsolete.job.generation++;obsolete.resume();
        check(obsolete.pending().isEmpty()&&obsolete.job.queued.contains(obsolete.pos)&&obsolete.unloads==1,"A season change retires the old handoff and requeues current work");
        var owned=new Fixture(true);owned.state.paused=true;owned.resume();check(owned.unloads==0,"Previously loaded chunks keep their original lifetime");
        var gone=new Fixture(false);current=null;gone.job.generation++;gone.resume();check(gone.unloads==0&&gone.job.flight.isEmpty(),"An unloaded world cannot receive an unload request from an obsolete callback");
        var closing=new Fixture(false);set(closing.engine,"scanner",Executors.newSingleThreadExecutor());
        var closingScan=new CompletableFuture<Void>();closing.job.scanTask=closingScan;closing.engine.close();
        check(closingScan.isCancelled(),"Shutdown cancels outstanding saved-world discovery");
        check(closing.pending().isEmpty()&&closing.job.flight.isEmpty()&&closing.unloads==1,"Shutdown releases retained requests without applying terrain edits");
        var unloading=new Fixture(false);var unloadedScan=new CompletableFuture<Void>();unloading.job.scanTask=unloadedScan;
        set(unloading.engine,"jobs",new LinkedHashMap<>((Map<?,?>)get(unloading.engine,"jobs")));
        unloading.engine.onWorldUnload(new org.bukkit.event.world.WorldUnloadEvent(unloading.world));
        check(unloadedScan.isCancelled(),"World unload cancels its saved-world scan without touching terrain");
        var inline=new Fixture(true);inline.pending().clear();inline.job.flight.clear();set(inline.engine,"inFlight",0);
        inline.job.queued.add(inline.pos);inline.job.queue.add(inline.pos);set(inline.engine,"scheduledWorlds",List.of(inline.job));
        Object plugin=get(inline.engine,"plugin");set(get(plugin,"settings"),"millis",1000d);
        Field enabled=org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("isEnabled");enabled.setAccessible(true);enabled.setBoolean(plugin,true);
        Method admit=ChunkMigrationEngine.class.getDeclaredMethod("admit");admit.setAccessible(true);admit.invoke(inline.engine);
        check(inline.loads==1&&inline.pending().size()==1&&(int)get(inline.engine,"inFlight")==1,"Inline completion preserves one bounded request without recursive scheduling or budget bypass");
        admit.invoke(inline.engine);check(inline.loads==1,"Repeated scheduling cannot duplicate a retained inline completion");
        inline.state.paused=true;inline.resume();check(inline.loads==1&&inline.pending().isEmpty(),"A paused callback cannot start another load from its completion path");
        System.out.println("Chunk load handoff: "+checks+" assertions passed");
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
