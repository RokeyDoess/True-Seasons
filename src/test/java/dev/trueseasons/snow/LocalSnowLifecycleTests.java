package dev.trueseasons.snow;

import dev.trueseasons.config.Settings;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.bukkit.*;
import org.bukkit.entity.Player;

/** Inactive planning releases chunk wrappers while preserving paused presentation and waking on demand. */
public final class LocalSnowLifecycleTests {
    private static int checks;
    private static Object allocate(Class<?> type)throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field field=unsafe.getDeclaredField("theUnsafe");field.setAccessible(true);
        return unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),type);
    }
    private static Object get(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static void set(Object object,String name,Object value)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    @SuppressWarnings("unchecked") private static <T>T proxy(Class<T> type,InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
    private static Object identity(Object proxy,String name,Object[] args){return switch(name){case "equals"->proxy==args[0];case "hashCode"->System.identityHashCode(proxy);case "toString"->"LocalSnowLifecycle";default->throw new AssertionError("Unexpected API: "+name);};}
    @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception{
        UUID id=UUID.randomUUID();AtomicInteger playerQueries=new AtomicInteger(),settingsQueries=new AtomicInteger(),clears=new AtomicInteger();
        World world=proxy(World.class,(p,m,a)->m.getName().equals("getUID")?id:identity(p,m.getName(),a));
        Chunk chunk=proxy(Chunk.class,(p,m,a)->m.getName().equals("getWorld")?world:identity(p,m.getName(),a));
        Server server=proxy(Server.class,(p,m,a)->switch(m.getName()){
            case "getWorlds"->List.of(world);case "getOnlinePlayers"->{playerQueries.incrementAndGet();yield List.of();}
            default->identity(p,m.getName(),a);
        });Field serverField=Bukkit.class.getDeclaredField("server");serverField.setAccessible(true);serverField.set(null,server);
        Settings settings=(Settings)allocate(Settings.class);set(settings,"millis",3d);
        AtomicBoolean active=new AtomicBoolean(true),transition=new AtomicBoolean(true);
        var service=new LocalWinterSnowService(null,()->{settingsQueries.incrementAndGet();return settings;},()->"winter",
            value->true,value->active.get(),()->256,null,(w,x,z,preview)->{check(w.equals(id)&&x==2&&z==3&&preview==null,"Completion clears the exact retained preview");clears.incrementAndGet();},data->{throw new AssertionError("Idle planning must not inspect block data");});
        service.transitioning(value->transition.get());service.tick();
        Method track=LocalWinterSnowService.class.getDeclaredMethod("track",UUID.class,Chunk.class);track.setAccessible(true);track.invoke(service,new UUID(0,7),chunk);
        Class<?> position=Class.forName("dev.trueseasons.snow.LocalWinterSnowService$Position");var constructor=position.getDeclaredConstructor(UUID.class,int.class,int.class);constructor.setAccessible(true);
        Map<Object,Long> checked=(Map<Object,Long>)get(service,"checked");checked.put(constructor.newInstance(id,2,3),System.nanoTime());
        int playerBaseline=playerQueries.get(),settingsBaseline=settingsQueries.get();
        active.set(false);service.transitioning(value->transition.get());service.tick();
        check(((Map<?,?>)get(service,"references")).isEmpty()&&((Map<?,?>)get(service,"viewers")).isEmpty(),"Pausing releases retained chunk wrappers");
        check(checked.size()==1&&clears.get()==0,"A paused transition retains its current preview rather than revealing physical terrain");
        Player player=proxy(Player.class,(p,m,a)->{throw new AssertionError("Inactive visibility must stop before inspecting a player");});
        service.visible(new PlayerChunkLoadEvent(chunk,player));
        for(int i=0;i<60;i++)service.tick();
        check(playerQueries.get()==playerBaseline&&settingsQueries.get()==settingsBaseline,"Inactive ticks avoid player snapshots, visibility reconciliation and terrain budgets");
        check(checked.size()==1&&clears.get()==0,"Periodic activity checks preserve paused previews");
        transition.set(false);service.transitioning(value->transition.get());service.tick();
        check(checked.isEmpty()&&clears.get()==1,"A finished transition retires its previews once");
        active.set(true);transition.set(true);service.transitioning(value->transition.get());service.tick();
        check(playerQueries.get()>playerBaseline&&((Set<?>)get(service,"workingWorlds")).contains(id),"A later transition wakes visibility reconciliation without waiting for another chunk packet");
        var refill=(SnowReplenishmentService)allocate(SnowReplenishmentService.class);set(refill,"ticks",2);set(refill,"loaded",List.of());
        refill.tick();check((int)get(refill,"cursor")==0,"An empty replenishment snapshot returns before sampling or touching the world");
        AtomicInteger inspected=new AtomicInteger();
        Chunk unavailable=proxy(Chunk.class,(p,m,a)->switch(m.getName()){
            case "isLoaded"->{inspected.incrementAndGet();yield true;}case "getWorld"->world;default->identity(p,m.getName(),a);
        });set(refill,"loaded",List.of(unavailable));set(refill,"activeAndFinished",(java.util.function.Predicate<World>)value->false);
        refill.tick();check(inspected.get()==1&&(int)get(refill,"cursor")==1,"An inactive loaded chunk is inspected once instead of cycling around a small snapshot");
        AtomicInteger visibilityCopies=new AtomicInteger();
        Chunk visible=proxy(Chunk.class,(p,m,a)->switch(m.getName()){
            case "isLoaded"->true;case "getWorld"->world;
            case "getPlayersSeeingChunk"->{visibilityCopies.incrementAndGet();yield java.util.Collections.emptySet();}
            default->identity(p,m.getName(),a);
        });
        UUID firstViewer=new UUID(0,10),secondViewer=new UUID(0,11);service.melting(value->true,()->"melt");
        track.invoke(service,firstViewer,visible);track.invoke(service,secondViewer,visible);
        check(service.ready(visible)&&visibilityCopies.get()==0,"Tracked visibility avoids copying a chunk's viewer set during preview inspections");
        Player first=proxy(Player.class,(p,m,a)->m.getName().equals("getUniqueId")?firstViewer:identity(p,m.getName(),a));
        Player second=proxy(Player.class,(p,m,a)->m.getName().equals("getUniqueId")?secondViewer:identity(p,m.getName(),a));
        service.hidden(new io.papermc.paper.event.packet.PlayerChunkUnloadEvent(visible,first));
        check(service.ready(visible)&&visibilityCopies.get()==0,"One viewer leaving preserves another viewer's preview");
        service.hidden(new io.papermc.paper.event.packet.PlayerChunkUnloadEvent(visible,second));
        check(!service.ready(visible)&&visibilityCopies.get()==1,"The last viewer leaving retires tracked visibility and confirms an unseen chunk is not ready");
        System.out.println("Local snow lifecycle: "+checks+" assertions passed");
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
