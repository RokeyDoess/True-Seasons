package dev.trueseasons.integration;

import dev.trueseasons.integration.RenderLedger.ChunkKey;
import java.util.*;
import java.lang.reflect.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import dev.trueseasons.config.Settings;
import org.bukkit.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.*;

/** Canonical purge membership, independent proof rotation and bounded runnable selection. */
public final class BlueMapPurgeTests {
    private static int assertions;
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        mutationViews();independentQueues();callbackSafety();
        System.out.println("BlueMap purge tests passed: "+assertions+" assertions");
    }
    private static void mutationViews(){
        var queue=new PurgeWorkQueue<String>();Map<ChunkKey,String> expected=new HashMap<>();Set<ChunkKey> ready=new HashSet<>();
        UUID[] worlds={new UUID(0,1),new UUID(0,2),new UUID(0,3)};Random random=new Random(23);
        for(int step=0;step<2400;step++){
            int n=random.nextInt(120);var key=new ChunkKey(worlds[n%3],n-60,n/5);String value="revision-"+step;
            switch(step%14){
                case 0->{if(!expected.containsKey(key))ready.add(key);expected.put(key,value);queue.put(key,value);}
                case 1->{expected.remove(key);ready.remove(key);queue.remove(key);}
                case 2->{queue.awaiting(key);ready.remove(key);}
                case 3->{queue.retry(key);if(expected.containsKey(key))ready.add(key);}
                case 4->{if(!expected.containsKey(key))ready.add(key);expected.compute(key,(k,old)->value);queue.compute(key,(k,old)->value);}
                case 5->{if(!expected.containsKey(key))ready.add(key);expected.putIfAbsent(key,value);queue.putIfAbsent(key,value);}
                case 6->{expected.replace(key,value);queue.replace(key,value);}
                case 7->{if(expected.containsKey(key))ready.remove(key);else ready.add(key);expected.merge(key,value,(a,b)->null);queue.merge(key,value,(a,b)->null);}
                case 8->{if(!queue.isEmpty()){var iterator=queue.entrySet().iterator();var row=iterator.next();ChunkKey removed=row.getKey();iterator.hasNext();iterator.remove();expected.remove(removed);ready.remove(removed);}}
                case 9->{if(!queue.isEmpty()){var row=queue.entrySet().iterator().next();row.setValue(value);expected.put(row.getKey(),value);}}
                case 10->{if(!queue.isEmpty()){var row=queue.entrySet().iterator().next();String removed=row.getValue();ChunkKey removedKey=row.getKey();queue.values().remove(removed);expected.remove(removedKey);ready.remove(removedKey);}}
                case 11->{queue.keySet().removeIf(k->k.x()%5==0);expected.keySet().removeIf(k->k.x()%5==0);ready.removeIf(k->k.x()%5==0);}
                case 12->{if(step%47==0){queue.clear();expected.clear();ready.clear();}}
                default->{queue.replaceAll((k,old)->"replacement-"+k);expected.replaceAll((k,old)->"replacement-"+k);}
            }
            check(queue.size()==expected.size()&&queue.equals(expected)&&expected.equals(queue),"Direct, default and collection-view edits preserve exact canonical purge membership");
            for(UUID world:worlds){
                var next=queue.nextReady(world::equals);boolean runnable=ready.stream().anyMatch(k->k.world().equals(world));
                check((next!=null)==runnable&&(next==null||ready.contains(next.getKey())&&next.getValue().equals(expected.get(next.getKey()))),"Phase moves and removals preserve runnable work in each world");
            }
            var before=queue.nextReady(id->true);var probes=queue.probes(17,id->true);
            check(probes.size()<=17&&probes.stream().map(Map.Entry::getKey).distinct().count()==probes.size(),"Proof probes stay bounded without duplicate keys");
            for(var probe:probes)check(probe.getValue().equals(expected.get(probe.getKey())),"A proof captures the current immutable revision value");
            check(Objects.equals(before,queue.nextReady(id->true)),"Proof rotation cannot change the head of runnable chunk work");
        }
        queue.clear();check(queue.nextReady(id->true)==null&&queue.probes(128,id->true).isEmpty(),"Clearing removes both work phases");
    }
    private static void independentQueues(){
        UUID missing=new UUID(0,1),active=new UUID(0,2),other=new UUID(0,3);
        var queue=new PurgeWorkQueue<String>();
        for(int n=0;n<4096;n++)queue.put(new ChunkKey(missing,n,0),"retained-"+n);
        var first=new ChunkKey(active,0,0);queue.put(first,"active");int[] lookups={0};
        check(queue.nextReady(id->{lookups[0]++;return id.equals(active);}).getKey().equals(first)&&lookups[0]==2,"An unavailable world's large backlog costs one world check before finding runnable work");
        queue.awaiting(first);check(queue.nextReady(active::equals)==null,"An in-flight or awaiting-save key leaves runnable selection without leaving the canonical map");
        queue.put(first,"replacement");check(queue.nextReady(active::equals)==null&&queue.get(first).equals("replacement"),"Updating a pending revision preserves its phase and replaces its value");
        queue.retry(first);check(queue.nextReady(active::equals).getValue().equals("replacement"),"A failed or obsolete callback can retry the current target");
        queue.clear();
        for(int n=0;n<296;n++){var key=new ChunkKey(active,n,0);queue.put(key,"ready-"+n);if((n&1)==0)queue.awaiting(key);}
        Set<ChunkKey> proofSeen=new HashSet<>(),loaded=new HashSet<>();
        while(loaded.size()<148){
            var next=queue.nextReady(active::equals);check(next!=null&&loaded.add(next.getKey()),"Repeated small load budgets cannot starve runnable keys when proofs rotate independently");
            queue.awaiting(next.getKey());
            for(var probe:queue.probes(128,id->true))proofSeen.add(probe.getKey());
        }
        for(int n=0;n<6;n++)for(var probe:queue.probes(128,id->true))proofSeen.add(probe.getKey());
        check(proofSeen.size()==296&&queue.size()==296,"Every waiting revision receives proof checks without dropping metadata or duplicating canonical rows");
        check(queue.nextReady(id->true)==null,"A fully awaiting-save backlog has no per-chunk load scan");
        queue.clear();
        for(UUID id:List.of(active,other))for(int n=0;n<3;n++)queue.put(new ChunkKey(id,n,0),"world-"+n);
        var a=queue.nextReady(id->true);queue.awaiting(a.getKey());var b=queue.nextReady(id->true);
        check(!a.getKey().world().equals(b.getKey().world()),"Runnable worlds share admission instead of one large world monopolizing all requests");
        var iterator=queue.entrySet().iterator();queue.awaiting(b.getKey());
        try{iterator.hasNext();throw new AssertionError("Iterator ignored a phase change");}catch(ConcurrentModificationException expected){assertions++;}
    }
    private static Object allocate(Class<?> type)throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field field=unsafe.getDeclaredField("theUnsafe");field.setAccessible(true);
        return unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),type);
    }
    private static void set(Object target,String name,Object value)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static Object get(Object target,String name)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    @SuppressWarnings("unchecked") private static <T>T proxy(Class<T> type,InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
    private static final class CallbackFixture {
        final UUID id=new UUID(0,7);final ChunkKey key=new ChunkKey(id,4,-3);
        final BlueMapIntegration integration;final World world;final Chunk chunk;
        final Map<ChunkKey,Object> purges;final PurgeWorkQueue<Object> queue;
        final Set<ChunkKey> loaded=new HashSet<>(),loading=new HashSet<>();
        final Map<NamespacedKey,Object> metadata=new HashMap<>();
        final NamespacedKey revision=new NamespacedKey("trueseasons","bluemap_revision");
        CompletableFuture<Chunk> pending;boolean failRequest;int requests,removed,unloads;
        @SuppressWarnings("unchecked") CallbackFixture()throws Exception{
            integration=(BlueMapIntegration)allocate(BlueMapIntegration.class);set(integration,"ledger",new RenderLedger());
            Settings settings=(Settings)allocate(Settings.class);set(settings,"perTick",1);set(settings,"concurrency",2);
            set(integration,"settings",(Supplier<Settings>)()->settings);set(integration,"purgeLoaded",loaded);set(integration,"loading",loading);set(integration,"revisionKey",revision);
            Method factory=BlueMapIntegration.class.getDeclaredMethod("purgeIndex");factory.setAccessible(true);
            purges=(Map<ChunkKey,Object>)factory.invoke(integration);set(integration,"trackedPurges",purges);set(integration,"purges",purges);queue=(PurgeWorkQueue<Object>)get(integration,"purgeQueue");
            world=proxy(World.class,(p,m,a)->switch(m.getName()){
                case "getUID"->id;case "isChunkLoaded"->false;
                case "getChunkAtAsync"->{check(a.length==3&&a[2].equals(false),"Metadata admission explicitly forbids terrain generation");requests++;if(failRequest)throw new IllegalStateException("load unavailable");yield pending=new CompletableFuture<>();}
                case "unloadChunkRequest"->{unloads++;yield true;}
                default->throw new AssertionError("Unexpected world access: "+m.getName());
            });
            var pdc=proxy(PersistentDataContainer.class,(p,m,a)->switch(m.getName()){
                case "get"->metadata.get(a[0]);case "remove"->{removed++;metadata.remove(a[0]);yield null;}
                default->throw new AssertionError("Unexpected metadata access: "+m.getName());
            });
            chunk=proxy(Chunk.class,(p,m,a)->switch(m.getName()){
                case "getWorld"->world;case "getX"->key.x();case "getZ"->key.z();case "getPersistentDataContainer"->pdc;
                case "isForceLoaded"->false;case "getPluginChunkTickets","getPlayersSeeingChunk"->List.of();
                default->throw new AssertionError("No terrain edits or tickets are allowed: "+m.getName());
            });
        }
        Object target(String token)throws Exception{var type=Class.forName(BlueMapIntegration.class.getName()+"$Purge");var constructor=type.getDeclaredConstructor(String.class,boolean.class);constructor.setAccessible(true);return constructor.newInstance(token,true);}
        void tick()throws Exception{Method method=BlueMapIntegration.class.getDeclaredMethod("purgeChunks");method.setAccessible(true);method.invoke(integration);}
    }
    private static void callbackSafety()throws Exception{
        World[] current={null};Field field=Bukkit.class.getDeclaredField("server");field.setAccessible(true);Object previous=field.get(null);
        Server server=proxy(Server.class,(p,m,a)->switch(m.getName()){
            case "getWorld"->current[0];case "isPrimaryThread"->true;
            default->throw new AssertionError("Unexpected server access: "+m.getName());
        });field.set(null,server);
        try{
            var fixture=new CallbackFixture();current[0]=fixture.world;Object old=fixture.target("old"),next=fixture.target("new");
            fixture.purges.put(fixture.key,old);fixture.metadata.put(fixture.revision,"old");fixture.tick();fixture.tick();
            check(fixture.requests==1&&fixture.loading.size()==1&&fixture.queue.nextReady(id->true)==null,"An outstanding metadata request cannot be duplicated");
            fixture.purges.put(fixture.key,next);fixture.pending.complete(fixture.chunk);
            check(fixture.removed==0&&fixture.unloads==0&&fixture.loading.isEmpty()&&fixture.queue.nextReady(id->true).getValue().equals(next),"An obsolete callback preserves metadata and retries the current revision");
            fixture.tick();fixture.metadata.put(fixture.revision,"new");fixture.pending.complete(fixture.chunk);fixture.tick();
            check(fixture.requests==2&&fixture.removed==1&&fixture.unloads==1&&fixture.loaded.contains(fixture.key),"A current completion removes only its exact marker and releases its temporary chunk");
            check(fixture.purges.size()==1&&fixture.queue.nextReady(id->true)==null,"Successful chunk work retains cleanup intent until separate saved proof");
            fixture.purges.remove(fixture.key);fixture.loaded.clear();fixture.purges.put(fixture.key,next);fixture.tick();
            fixture.pending.completeExceptionally(new IllegalStateException("load unavailable"));
            check(fixture.loading.isEmpty()&&fixture.queue.nextReady(id->true)!=null&&fixture.removed==1,"Failed requests release admission and retain retryable intent");
            fixture.tick();fixture.pending.complete(null);
            check(fixture.queue.nextReady(id->true)!=null&&fixture.loading.isEmpty(),"A missing chunk remains retryable without generation");
            fixture.failRequest=true;
            try{fixture.tick();throw new AssertionError("Synchronous load failure was hidden");}catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalStateException,"Synchronous failure remains visible to automatic pause handling");}
            check(fixture.loading.isEmpty()&&fixture.queue.nextReady(id->true)!=null,"Synchronous failure cannot strand an admission or purge");fixture.failRequest=false;
            fixture.tick();current[0]=null;fixture.metadata.put(fixture.revision,"new");fixture.pending.complete(fixture.chunk);
            check(fixture.removed==1&&fixture.unloads==1&&fixture.queue.nextReady(id->true)!=null,"World unload makes a completion stale before metadata or chunk lifetime changes");
            int requests=fixture.requests;fixture.tick();check(fixture.requests==requests,"An unavailable world cannot admit metadata work");
            current[0]=fixture.world;fixture.integration.loaded(new ChunkLoadEvent(fixture.chunk,false));fixture.tick();
            check(fixture.loaded.contains(fixture.key)&&fixture.removed==2&&fixture.requests==requests&&fixture.queue.nextReady(id->true)==null,"A naturally loaded chunk moves directly to saved-proof work");
            fixture.purges.remove(fixture.key);fixture.loaded.clear();fixture.purges.put(fixture.key,next);fixture.tick();set(fixture.integration,"closed",true);fixture.pending.complete(fixture.chunk);
            check(fixture.purges.size()==1&&fixture.removed==2&&fixture.unloads==1,"Shutdown cannot apply late metadata work or discard its durable intent");
        }finally{field.set(null,previous);}
    }
}
