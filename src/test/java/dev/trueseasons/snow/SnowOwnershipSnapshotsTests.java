package dev.trueseasons.snow;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import org.bukkit.*;
import org.bukkit.event.world.*;
import org.bukkit.persistence.PersistentDataType;

/** Read-only packet views must track durable ownership without terrain reads. */
public final class SnowOwnershipSnapshotsTests {
    private static int checks;
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
    public static void main(String[] args) throws Exception {
        coordinateViews();
        previewPublication();
        UUID id = UUID.randomUUID();
        var fixture = new SnowReplenishmentTests.Fixture();
        World world = proxy(World.class, (p,m,a) -> switch(m.getName()) {
            case "getUID" -> id; case "getName" -> "ownership-fixture";
            default -> invoke(m, fixture.world, a);
        });
        Chunk chunk = proxy(Chunk.class, (p,m,a) -> m.getName().equals("getWorld") ? world : invoke(m, fixture.chunk, a));
        Logger logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        List<LogRecord> logs = new ArrayList<>();
        logger.addHandler(new Handler() {
            public void publish(LogRecord record) { logs.add(record); }
            public void flush() {} public void close() {}
        });
        int[] notifications = {0};
        var snapshots = new SnowOwnershipSnapshots(fixture.snow, changed -> {
            notifications[0]++; fixture.order.add("snapshot");
        }, logger);
        var view = snapshots.world(id);
        check(view == snapshots.world(id), "World view identity is stable before first owned chunk");
        check(view.snapshot(4, -3) == SnowOwnershipSnapshots.Snapshot.EMPTY, "Unknown chunks safely expose no ownership");
        snapshots.onLoad(new ChunkLoadEvent(chunk, false));
        check(view.snapshot(4,-3) == SnowOwnershipSnapshots.Snapshot.EMPTY && notifications[0] == 1,
            "Loading an empty chunk publishes a known empty snapshot");
        fixture.order.clear();
        var result = fixture.snow.replenishColumns(chunk, "finished-winter", new int[]{0},
            new SeasonalSnowService.ReplenishmentPolicy(true, true, Set.of(), List.of(), 1),
            fixture.snowData, block -> true, ignored -> { fixture.before++; fixture.order.add("intent"); },
            ignored -> { fixture.after++; fixture.order.add("revision"); }, Long.MAX_VALUE);
        check(result.placed() == 1 && fixture.order.equals(List.of("intent", "ownership", "snapshot", "block", "revision")),
            "Snapshot publication follows durable ownership and precedes physical placement");
        var first = view.snapshot(4,-3);
        check(first.heights().equals(Map.of(0,65)), "Snapshot exposes exact owned column height");
        try { first.heights().put(1, 2); throw new AssertionError("Mutable snapshot"); }
        catch (UnsupportedOperationException expected) { check(true, "Published snapshot is immutable"); }
        Map<Integer,Integer> mutable = new HashMap<>(Map.of(4,-64));
        var copied = new SnowOwnershipSnapshots.Snapshot(mutable); mutable.put(4,70);
        check(copied.heights().equals(Map.of(4,-64)), "Snapshot copies caller maps and preserves signed heights");
        check(SnowOwnershipSnapshots.Snapshot.EMPTY.nextColumn(0)==256
            &&SnowOwnershipSnapshots.Snapshot.EMPTY.height(0)==Integer.MIN_VALUE,"Empty ownership has no columns or height");
        SnowColumns dense=new SnowColumns();for(int column=0;column<256;column++)dense.put(column,column-128);
        var frozen=SnowOwnershipSnapshots.Snapshot.copyOf(dense);
        for(int column=frozen.nextColumn(0);column<256;column=frozen.nextColumn(column+1)){
            dense.remove(column);check(frozen.height(column)==column-128,"Compact snapshot remains detached while its source is edited");
        }
        check(frozen.heights().size()==256&&frozen.nextColumn(256)==256&&frozen.height(256)==Integer.MIN_VALUE,
            "Compact ownership retains exact map and primitive bounds");
        check(CompletableFuture.supplyAsync(() -> view.snapshot(4,-3).heights().get(0)).get() == 65,
            "Packet thread reads only safely published immutable ownership");
        fixture.metadata.put(fixture.owned, new SnowReplenishmentTests.Value(PersistentDataType.BYTE_ARRAY,new byte[]{99}));
        snapshots.onLoad(new ChunkLoadEvent(chunk, false));
        snapshots.onLoad(new ChunkLoadEvent(chunk, false));
        check(view.snapshot(4,-3) == SnowOwnershipSnapshots.Snapshot.EMPTY && logs.size() == 1,
            "Malformed ownership fails open and warns once while chunk stays loaded");
        check(first.heights().equals(Map.of(0,65)), "Replacing cache entry cannot mutate retained reader snapshot");
        try { fixture.snow.observeOwnership(chunk); throw new AssertionError("Expected malformed metadata"); }
        catch (java.io.IOException expected) { check(true, "Presentation handling leaves migration metadata failures intact"); }
        fixture.metadata.remove(fixture.owned);
        snapshots.onLoad(new ChunkLoadEvent(chunk, false));
        snapshots.onUnload(new ChunkUnloadEvent(chunk));
        check(view.snapshot(4,-3) == SnowOwnershipSnapshots.Snapshot.EMPTY, "Chunk unload removes its ownership without loading terrain");
        snapshots.onWorldUnload(new WorldUnloadEvent(world));
        check(snapshots.world(id) != view && view.snapshot(4,-3) == SnowOwnershipSnapshots.Snapshot.EMPTY,
            "World unload clears retained views and permits a fresh world lifetime");
        // Callback failure must not abort already-owned physical snow writes.
        var failing = new SnowOwnershipSnapshots(fixture.snow, changed -> { throw new IllegalStateException("test callback"); }, logger);
        fixture.column(0).target = Material.AIR;
        fixture.before = fixture.after = 0;
        result = fixture.snow.replenishColumns(chunk, "finished-winter", new int[]{0},
            new SeasonalSnowService.ReplenishmentPolicy(true, true, Set.of(), List.of(), 1),
            fixture.snowData, block -> true, ignored -> fixture.before++, ignored -> fixture.after++, Long.MAX_VALUE);
        check(result.placed() == 1 && failing.world(id).snapshot(4,-3).heights().equals(Map.of(0,65)),
            "Notification failure preserves snapshot publication and physical placement");
        snapshots.close(); snapshots.close();
        fixture.column(0).target = Material.AIR; fixture.before = fixture.after = 0;
        result = fixture.snow.replenishColumns(chunk, "finished-winter", new int[]{1},
            new SeasonalSnowService.ReplenishmentPolicy(true, true, Set.of(), List.of(), 1),
            fixture.snowData, block -> true, ignored -> fixture.before++, ignored -> fixture.after++, Long.MAX_VALUE);
        var retained = failing.world(id);
        check(result.placed() == 1 && retained.snapshot(4,-3).heights().containsKey(1),
            "Closing an older owner cannot detach its replacement observer");
        failing.close();
        check(retained.snapshot(4,-3) == SnowOwnershipSnapshots.Snapshot.EMPTY
                && failing.world(id).snapshot(4,-3) == SnowOwnershipSnapshots.Snapshot.EMPTY,
            "Close clears retained world views and closed reads remain empty");
        var bounded=new SnowOwnershipSnapshots(fixture.snow,ignored->{},logger);UUID secondWorld=UUID.randomUUID();
        var preview=new SnowOwnershipSnapshots.Preview("winter",1,Map.of(0,new SnowOwnershipSnapshots.PreviewColumn(65,0,0)),true);
        for(int x=0;x<65_536;x++)bounded.preview(id,x,0,preview);
        bounded.preview(secondWorld,0,0,preview);
        check(bounded.world(secondWorld).preview(0,0)==null,"All worlds share the server preview cap");
        bounded.preview(id,0,0,null);bounded.preview(secondWorld,0,0,preview);
        check(bounded.world(secondWorld).preview(0,0)==preview,"Clearing a preview releases capacity for another world");
        bounded.onWorldUnload(new WorldUnloadEvent(world));bounded.preview(secondWorld,1,0,preview);
        check(bounded.world(secondWorld).preview(1,0)==preview,"World unloading returns all transient capacity");bounded.close();
        System.out.println("Snow ownership snapshot tests passed: " + checks + " assertions");
    }
    private static void coordinateViews()throws Exception{
        var view=new SnowOwnershipSnapshots.WorldView();
        Method publish=view.getClass().getDeclaredMethod("publish",int.class,int.class,SnowOwnershipSnapshots.Snapshot.class);publish.setAccessible(true);
        Method preview=view.getClass().getDeclaredMethod("preview",int.class,int.class,SnowOwnershipSnapshots.Preview.class);preview.setAccessible(true);
        Method forget=view.getClass().getDeclaredMethod("forget",int.class,int.class);forget.setAccessible(true);
        List<int[]> positions=new ArrayList<>();for(int x=-20;x<=20;x++)for(int z=-20;z<=20;z++)positions.add(new int[]{x,z});
        for(int x:new int[]{Integer.MIN_VALUE,Integer.MAX_VALUE})for(int z:new int[]{Integer.MIN_VALUE,Integer.MAX_VALUE})positions.add(new int[]{x,z});
        for(int i=0;i<positions.size();i++){
            int[] pos=positions.get(i);publish.invoke(view,pos[0],pos[1],new SnowOwnershipSnapshots.Snapshot(Map.of(0,i-64)));
            preview.invoke(view,pos[0],pos[1],new SnowOwnershipSnapshots.Preview("grid-"+i,0,Map.of(0,new SnowOwnershipSnapshots.PreviewColumn(i-64,1,0))));
        }
        for(int i=0;i<positions.size();i++){
            int[] pos=positions.get(i);
            check(view.snapshot(pos[0],pos[1]).height(0)==i-64&&view.preview(pos[0],pos[1]).version().equals("grid-"+i),"Grid coordinates retain independent ownership and previews");
            if((i&1)==0)forget.invoke(view,pos[0],pos[1]);
        }
        for(int i=0;i<positions.size();i++){
            int[] pos=positions.get(i);
            check((i&1)==0?view.snapshot(pos[0],pos[1])==SnowOwnershipSnapshots.Snapshot.EMPTY&&view.preview(pos[0],pos[1])==null
                :view.snapshot(pos[0],pos[1]).height(0)==i-64,"Forgetting a grid coordinate preserves its neighbors");
        }
    }
    private static void previewPublication()throws Exception{
        for(int count:new int[]{0,1,16,17,128,256}){
            var builder=new SnowOwnershipSnapshots.PreviewBuilder();Map<Integer,SnowOwnershipSnapshots.PreviewColumn> expected=new HashMap<>();
            for(int column=0;column<count;column++){
                var value=new SnowOwnershipSnapshots.PreviewColumn(column-64,column+1,0);
                builder.put(column,value);expected.put(column,value);builder.put(column,value);
            }
            var columns=builder.build();var preview=new SnowOwnershipSnapshots.Preview("winter",0,columns);
            check(columns.equals(expected)&&expected.equals(columns)&&columns.hashCode()==expected.hashCode(),"Frozen previews preserve sparse and dense map contents");
            check(columns.get(-1)==null&&columns.get(256)==null&&columns.get("0")==null,"Frozen preview lookup respects map key and column bounds");
            check(preview.columns().equals(expected),"Publication preserves every planned column");
            int visited=0;
            for(int column=preview.nextColumn(0);column<256;column=preview.nextColumn(column+1)){
                check(preview.column(column).equals(expected.get(column)),"Primitive preview traversal preserves exact source states");visited++;
            }
            check(visited==count&&preview.nextColumn(256)==256&&preview.column(-1)==null&&preview.column(256)==null,"Primitive traversal respects sparse, empty and boundary columns");
            try{builder.put(0,new SnowOwnershipSnapshots.PreviewColumn(0,1,0));throw new AssertionError("Builder reused");}
            catch(IllegalStateException expectedFailure){check(true,"Publishing consumes the only mutable builder");}
            try{builder.build();throw new AssertionError("Builder republished");}
            catch(IllegalStateException expectedFailure){check(true,"A consumed builder cannot transfer its array again");}
            try{columns.put(255,new SnowOwnershipSnapshots.PreviewColumn(0,1,0));throw new AssertionError("Mutable preview");}
            catch(UnsupportedOperationException expectedFailure){check(true,"Published preview map rejects writes");}
            if(!columns.isEmpty()){
                try{columns.entrySet().iterator().next().setValue(null);throw new AssertionError("Mutable entry");}
                catch(UnsupportedOperationException expectedFailure){check(true,"Preview entries cannot mutate packet readers");}
                var iterator=columns.entrySet().iterator();iterator.next();
                try{iterator.remove();throw new AssertionError("Mutable iterator");}
                catch(UnsupportedOperationException expectedFailure){check(true,"Preview iteration cannot remove published columns");}
            }
            check(CompletableFuture.supplyAsync(()->preview.columns().equals(expected)).get(),"Packet threads see the complete immutable preview");
        }
        var mutable=new HashMap<Integer,SnowOwnershipSnapshots.PreviewColumn>();mutable.put(0,new SnowOwnershipSnapshots.PreviewColumn(1,2,0));
        var preview=new SnowOwnershipSnapshots.Preview("winter",0,mutable);mutable.clear();
        check(preview.columns().size()==1,"Untrusted caller maps are still copied before publication");
    }
    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target,args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
