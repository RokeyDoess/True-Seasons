package dev.trueseasons.migration;

import dev.trueseasons.TrueSeasonsPlugin;
import dev.trueseasons.api.*;
import dev.trueseasons.snow.WinterGroundCoverRuntimeProbe;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;

/** Two normal starts, real region sequencing, winter plant persistence and final BlueMap receipts. */
public final class RegionMigrationRuntimeProbe extends JavaPlugin implements Listener {
    private TrueSeasonsPlugin seasons;
    private World world;
    private String phase, previous, failure;
    private Chunk fixture;
    private int checks, ticks;
    private long began, winterRenders;
    private final Set<String> visited = new HashSet<>();
    private final Set<Object> seenTickets=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String,Set<String>> requestedRegions=new HashMap<>();
    private Field flightsField;
    private Object mapIntegration;
    private long springRenders;
    private boolean colorClockChecked;
    private Path phaseFile = Path.of("region-probe-phase.txt");
    private YamlConfiguration data;
    @Override public void onEnable() {
        try {
            if (!Files.exists(Path.of("region-verification-clone.txt"))) throw new IllegalStateException("Disposable region verification clone required");
            seasons = (TrueSeasonsPlugin) Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("TrueSeasons"));
            world = Bukkit.getWorlds().stream().filter(seasons::eligible).findFirst().orElseThrow();
            Field adapter=seasons.integrations().getClass().getDeclaredField("blueMap");adapter.setAccessible(true);
            mapIntegration=Objects.requireNonNull(adapter.get(seasons.integrations()));
            flightsField=mapIntegration.getClass().getDeclaredField("flights");flightsField.setAccessible(true);
            data = YamlConfiguration.loadConfiguration(Path.of("region-probe-data.yml").toFile());
            phase = Files.exists(phaseFile) ? Files.readString(phaseFile).strip() : "startup";
            if (phase.equals("startup") && !seasons.state().cleanup && seasons.currentSeason()!=Season.SPRING)
                seasons.setSeason(Season.SPRING); // Establish an original baseline when no older verification clone exists.
            if (phase.equals("restart-winter")) {
                previous = data.getString("previous-region"); visited.addAll(data.getStringList("visited"));
                check(seasons.currentSeason() == Season.WINTER, "Partial winter season survives restart");
                seasons.state().paused = false; seasons.persist(); phase = "winter";
            } else if (phase.equals("resume-spring")) {
                previous = data.getString("previous-region"); visited.addAll(data.getStringList("visited"));
                check(seasons.currentSeason()==Season.SPRING, "Completed spring season survives the map-drain restart");
                phase="spring";
            }
            Bukkit.getPluginManager().registerEvents(this,this); began = System.currentTimeMillis();
            Bukkit.getScheduler().runTaskTimer(this, () -> { try { poll(); } catch (Throwable error) { fail(error); } },20L,20L);
        } catch (Throwable error) { fail(error); }
    }
    private void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); getLogger().info("PASS: " + label); }
    private String region(ChunkPos pos) { return Math.floorDiv(pos.x(),32)+","+Math.floorDiv(pos.z(),32); }
    @EventHandler public void renderRequests(ServerTickEndEvent event){
        if(!(phase.equals("winter")||phase.equals("spring")||phase.equals("cleanup")))return;
        try{
            // Accepted flights remain in the main-thread map until the next tick,
            // even when an empty-region render finishes immediately on a worker.
            for(var row:((Map<?,?>)flightsField.get(mapIntegration)).entrySet()){
                Object flight=row.getValue();Method ticket=flight.getClass().getDeclaredMethod("ticket");ticket.setAccessible(true);
                if(!seenTickets.add(ticket.invoke(flight)))continue;
                Method regions=flight.getClass().getDeclaredMethod("regions");regions.setAccessible(true);
                Set<String> requests=requestedRegions.computeIfAbsent(phase,ignored->new HashSet<>());
                for(Object value:(List<?>)regions.invoke(flight)){
                    String key=row.getKey()+"/"+value;
                    if(!requests.add(key))failure="Duplicate accepted map-region render in "+phase+": "+key;
                }
            }
        }catch(Exception error){fail(error);}
    }
    private int requests(String target){return requestedRegions.getOrDefault(target,Set.of()).size();}
    @EventHandler public void migrated(SeasonChunkUpdatedEvent event) {
        if (!event.getChunk().getWorld().equals(world) || !(phase.equals("winter") || phase.equals("starting-winter") || phase.equals("spring"))) return;
        String current = region(new ChunkPos(event.getChunk().getX(),event.getChunk().getZ()));
        if (!current.equals(previous)) {
            if (previous != null) {
                String[] point = previous.split(",");
                if (!seasons.engine().regionReady(world.getUID(),Integer.parseInt(point[0]),Integer.parseInt(point[1]))) failure = "Region " + previous + " was unfinished when " + current + " started";
            }
            if (!visited.add(current)) failure = "Completed region revisited: " + current;
            getLogger().info("REGION_START phase="+phase+" region="+current+" previous="+previous);
            previous = current;
        }
    }
    private ChunkMigrationEngine.Job job() { return seasons.engine().jobs().stream().filter(j -> j.world == world).findFirst().orElseThrow(); }
    private void poll() throws Exception {
        if (phase.equals("stopping")) return;
        if (failure != null) throw new AssertionError(failure);
        if (System.currentTimeMillis()-began > 1_200_000) throw new AssertionError("Timed out in " + phase + ": " + seasons.integrations().status());
        if (++ticks % 5 == 0) world.save();
        if (ticks % 15 == 0) Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"ts status");
        if (phase.equals("startup")) {
            if (!seasons.engine().allFinished() || seasons.state().cleanup || seasons.integrations().pending()!=0) return;
            // Saved-world indexing can finish before BlueMap's asynchronous resource load.
            if(!seasons.integrations().status().startsWith("connected"))return;
            if(!colorClockChecked){
                checks+=dev.trueseasons.presentation.SeasonColorClockRuntimeProbe.verify(seasons);
                checks+=dev.trueseasons.presentation.NativeSnowRuntimeProbe.verify(this);
                colorClockChecked=true;return;
            }
            check(seasons.integrations().status().startsWith("connected"), "Real BlueMap connected");
            Set<ChunkPos> headers = new HashSet<>(new GeneratedChunkIndex().scan(world.getWorldPath().resolve("region")));
            data.set("headers",headers.stream().map(p -> p.x()+","+p.z()).sorted().toList());
            ChunkPos position = job().known.stream().filter(p -> !job().unfinishedTerrain().contains(p)).min(
                java.util.Comparator.comparingLong(RegionMigrationRuntimeProbe::fixtureRegionOrder)
                    .thenComparingInt(p -> Math.floorMod(p.z(),32)*32+Math.floorMod(p.x(),32))).orElseThrow();
            data.set("x",position.x()); data.set("z",position.z()); data.set("y",world.getMaxHeight()-25); saveData();
            phase = "loading";
            world.getChunkAtAsync(position.x(),position.z(),false,false).thenAccept(chunk -> {
                try {
                    fixture = Objects.requireNonNull(chunk); seasons.state().paused = true;
                    checks += WinterGroundCoverRuntimeProbe.verify(this,seasons::settings,fixture);
                    makeFixtures(); seasons.state().paused = false;
                    world.setStorm(false); phase = "starting-winter";
                    seasons.setSeason(Season.WINTER); phase = "winter";
                    // A far-player hint must have no effect on region scheduling.
                    seasons.engine().prioritizeAround(world,new ChunkPos(position.x()+32,position.z()+32));
                    check(seasons.weather().activeStorms()>0, "Introductory native snowstorm begins with winter");
                } catch (Throwable error) { fail(error); }
            }); return;
        }
        if (phase.equals("winter") && fixture == null) {
            phase = "reloading";
            world.getChunkAtAsync(data.getInt("x"),data.getInt("z"),false,false).thenAccept(chunk -> { fixture=chunk; phase="winter"; }); return;
        }
        if (phase.equals("winter")) {
            if (!data.getBoolean("interrupted") && visited.size()>=2 && job().completed()<job().total()) {
                loadFixture(() -> {
                    check(block(2,2).getType()==Material.SNOW, "Exposed leaf litter is replaced with real snow");
                    check(block(6,2).getType()==Material.SNOW && block(6,2).getRelative(BlockFace.UP).getType()==Material.AIR, "Tall grass halves are saved and replaced with one snow layer");
                    check(block(14,2).getType()==Material.POPPY && block(14,2).getRelative(BlockFace.UP).getType()==Material.AIR, "Desert flowers and precipitation surface stay unchanged");
                    check(block(14,6).getType()==Material.POPPY, "Jungle flowers stay through winter");
                    check(block(10,2).getType()==Material.OAK_SAPLING && block(10,6).getType()==Material.WHEAT, "Saplings and crops stay through winter");
                    data.set("interrupted",true); data.set("previous-region",previous); data.set("visited",List.copyOf(visited)); saveData();
                    seasons.state().paused=true; seasons.persist();
                    check(seasons.weather().activeStorms()>0, "Storm persists during partial migration pause");
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"save-all flush");
                    Files.writeString(phaseFile,"restart-winter"); stop("PROBE_REGION_RESTART_READY");
                }); return;
            }
            if (!seasons.engine().allFinished() || seasons.integrations().pending()!=0) return;
            check(visited.size()>=2 && failure==null, "Every observed region finished before the next started, including restart");
            check(seasons.weather().activeStorms()==0, "Introductory storm ends when world migration finishes");
            winterRenders = renders(); check(winterRenders>0 && winterRenders==requests("winter") && failure==null,
                "Every accepted winter map-region request was observed exactly once, including palette-only regions");
            loadFixture(() -> {
                check(fixture.getPersistentDataContainer().has(new NamespacedKey(seasons,"ground_cover_v1")), "Plant backups survive normal restart and final winter completion");
                Block replacement = block(2,10); var old = replacement.getState(); replacement.setType(Material.DIAMOND_BLOCK,false);
                seasons.snow().onPlace(new BlockPlaceEvent(replacement,old,replacement.getRelative(BlockFace.DOWN),new ItemStack(Material.DIAMOND_BLOCK),null,true,EquipmentSlot.HAND));
                previous=null; visited.clear(); phase="spring"; seasons.setSeason(Season.SPRING);
            }); return;
        }
        if (phase.equals("spring") && seasons.engine().allFinished() && seasons.integrations().pending()==0) {
            if (Files.readString(phaseFile).strip().equals("resume-spring")) check(job().placed()==0&&job().removed()==0, "Completed spring restart hydrates saved progress without world edits");
            check(failure==null && visited.size()>=2, "Spring restoration also completes regions methodically");
            loadFixture(() -> {
                check(block(2,2).getBlockData().getAsString().equals(data.getString("litter")), "Exact leaf-litter properties restore on leaving winter");
                check(block(6,2).getType()==Material.TALL_GRASS && block(6,2).getRelative(BlockFace.UP).getType()==Material.TALL_GRASS, "Both tall grass halves restore after interrupted winter");
                check(block(2,10).getType()==Material.DIAMOND_BLOCK, "Player replacement prevents plant resurrection");
                check(!fixture.getPersistentDataContainer().has(new NamespacedKey(seasons,"ground_cover_v1")), "Resolved plant backups retire after restoration");
                check(block(14,2).getType()==Material.POPPY && block(14,6).getType()==Material.POPPY, "Hot-biome plants were never claimed or removed");
                springRenders=renders()-winterRenders;
                check(springRenders==requests("spring")&&failure==null,"Spring also renders each requested map region once");
                phase="cleanup"; seasons.startCleanup();
            }); return;
        }
        if (phase.equals("cleanup") && seasons.state().clean && seasons.engine().allFinished() && seasons.integrations().pending()==0) {
            Set<String> after = new HashSet<>(); for (var p : new GeneratedChunkIndex().scan(world.getWorldPath().resolve("region"))) after.add(p.x()+","+p.z());
            check(after.equals(new HashSet<>(data.getStringList("headers"))), "Migration, restart and cleanup generate zero terrain");
            check(seasons.integrations().cleanupPending()==0, "Cleanup verifies map metadata removal");
            check(renders()-winterRenders-springRenders==requests("cleanup")&&failure==null,"Cleanup map requests also contain no duplicate regions");
            Files.writeString(phaseFile,"complete"); Files.writeString(Path.of("region-verification-result.txt"),"PASS: region order, restart, climate, exact plant restoration and final map receipts; "+checks+" checks this start.\n");
            stop("PROBE_REGION_ALL_PASSED");
        }
    }
    private static long fixtureRegionOrder(ChunkPos position){
        long x=Math.floorDiv(position.x(),32),z=Math.floorDiv(position.z(),32),ring=Math.max(Math.abs(x),Math.abs(z));
        if(ring==0)return 0;
        long offset=x==ring&&z>-ring?z+ring-1:z==ring?3*ring-1-x:x==-ring?5*ring-1-z:7*ring+x-1;
        return (2*ring-1)*(2*ring-1)+offset;
    }
    private long renders() throws Exception {
        var manager = seasons.integrations(); Field adapter=manager.getClass().getDeclaredField("blueMap"); adapter.setAccessible(true);
        Object integration=adapter.get(manager); Field meter=integration.getClass().getDeclaredField("regionRendersScheduled"); meter.setAccessible(true); return meter.getLong(integration);
    }
    private Block block(int x,int z) { return fixture.getBlock(x,data.getInt("y"),z); }
    private void makeFixtures() throws Exception {
        for (int[] p : new int[][]{{2,2},{6,2},{10,2},{10,6},{14,2},{14,6},{2,10}}) {
            Block target=block(p[0],p[1]); target.getRelative(BlockFace.DOWN).setType(p[1]==6&&p[0]==10?Material.FARMLAND:Material.GRASS_BLOCK,false);
            target.setType(Material.AIR,false); target.getRelative(BlockFace.UP).setType(Material.AIR,false);
            for(int y=data.getInt("y")-1;y<=data.getInt("y")+1;y++) world.setBiome(target.getX(),y,target.getZ(),p[0]==14?(p[1]==2?org.bukkit.block.Biome.DESERT:org.bukkit.block.Biome.JUNGLE):org.bukkit.block.Biome.PLAINS);
        }
        block(2,2).setType(Material.LEAF_LITTER,false); block(2,10).setType(Material.LEAF_LITTER,false);
        block(6,2).setBlockData(Bukkit.createBlockData("minecraft:tall_grass[half=lower]"),false); block(6,2).getRelative(BlockFace.UP).setBlockData(Bukkit.createBlockData("minecraft:tall_grass[half=upper]"),false);
        block(10,2).setType(Material.OAK_SAPLING,false); block(10,6).setBlockData(Bukkit.createBlockData("minecraft:wheat[age=7]"),false);
        block(14,2).setType(Material.POPPY,false); block(14,6).setType(Material.POPPY,false);
        data.set("litter",block(2,2).getBlockData().getAsString()); saveData();
    }
    private interface Checked { void run() throws Exception; }
    private void loadFixture(Checked action) {
        String resume=phase; phase="lease";
        world.getChunkAtAsync(data.getInt("x"),data.getInt("z"),false,false).thenAccept(chunk -> { try { fixture=Objects.requireNonNull(chunk); phase=resume; action.run(); } catch(Throwable error) { fail(error); } });
    }
    private void saveData() throws Exception { data.save(Path.of("region-probe-data.yml").toFile()); }
    private void stop(String marker) { getLogger().info(marker+" checks="+checks); phase="stopping"; Bukkit.shutdown(); }
    private void fail(Throwable error) { getLogger().log(java.util.logging.Level.SEVERE,"PROBE_REGION_FAILED",error); try { Files.writeString(Path.of("region-verification-result.txt"),"FAIL: "+error); } catch(Exception ignored) {} phase="stopping"; Bukkit.shutdown(); }
}
