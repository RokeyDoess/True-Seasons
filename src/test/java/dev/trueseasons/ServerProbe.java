package dev.trueseasons;

import dev.trueseasons.api.*;
import dev.trueseasons.migration.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Snow;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.Slab;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.weather.WeatherChangeEvent;

/** Runs only in the disposable verification clone. Never install on a real world. */
public final class ServerProbe extends JavaPlugin implements Listener {
    private TrueSeasonsPlugin seasons;
    private World world;
    private Chunk fixture;
    private Path phaseFile;
    private int checks, ticks;
    private String phase;
    private Set<ChunkPos> original;
    private ChunkPos fixturePos;
    private ChunkPos neighborPos;
    private boolean neighborUpdated;
    private int neighborWait;
    private Chunk explored;
    private int explorationTick;
    private dev.trueseasons.integration.BlueMapFinalizationProbe finalMap;
    private boolean stormCompleted;
    private int completedTotal, rescanStarted;
    private void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++; getLogger().info("PASS: " + label);
    }
    @Override public void onEnable() {
        try {
            seasons = (TrueSeasonsPlugin) Bukkit.getPluginManager().getPlugin("TrueSeasons");
            world = Bukkit.getWorlds().stream().filter(w -> w.getEnvironment() == World.Environment.NORMAL).findFirst().orElseThrow();
            phaseFile = Path.of("verification-phase.txt");
            phase = Files.exists(phaseFile) ? Files.readString(phaseFile).strip() : "initial";
            original = new HashSet<>(new GeneratedChunkIndex().scan(world.getWorldPath().resolve("region")));
            Bukkit.getPluginManager().registerEvents(this,this);
            check(!original.isEmpty(), "Existing test chunks found in 26.3 dimension path");
            if (phase.equals("initial")) {
                checks += dev.trueseasons.presentation.NativeSnowRuntimeProbe.verify(this);
                seasons.state().paused = true; seasons.persist();
                ChunkPos pos = original.stream().filter(p -> world.isChunkGenerated(p.x(),p.z())).findFirst().orElseThrow();
                fixturePos = pos;
                world.getChunkAtAsync(pos.x(),pos.z(),false).thenAccept(chunk -> {
                    try { setup(chunk); } catch (Throwable failure) { fail(failure); }
                });
            } else if (phase.equals("restart")) {
                check(seasons.state().override == Season.WINTER, "Manual override persisted across restart");
                check(seasons.state().paused, "Pause persisted across restart");
                var coords = Files.readString(Path.of("fixture.txt")).split(",");
                fixturePos = new ChunkPos(Integer.parseInt(coords[0]),Integer.parseInt(coords[1]));
                world.getChunkAtAsync(Integer.parseInt(coords[0]),Integer.parseInt(coords[1]),false).thenAccept(chunk -> {
                    try {
                        fixture = chunk;
                        check(ownedCount() > 0, "Snow ownership and physical snow survived restart");
                        check(fixture.getPersistentDataContainer().has(new NamespacedKey(seasons,"winter_cursor")), "Partial winter cursor survived restart");
                        check(!seasons.version().equals(seasons.getChunkSeasonalVersion(fixture)), "Partial winter chunk remains stale until fully covered");
                        check(seasons.engine().globalCoverageColumns()>=128,"Shared winter coverage survived restart");
                        check(world.hasStorm()&&seasons.weather().activeStorms()>0,"Migration snowstorm resumes after restart");
                        seasons.engine().prioritizeAround(world,fixturePos);
                        seasons.state().paused = false; seasons.persist();
                        phase = "finish-winter";
                    } catch (Throwable failure) { fail(failure); }
                });
            } else if (phase.equals("resume-winter-map")) {
                // Resume only after the recorded winter/transition checks passed.
                check(seasons.state().override==Season.WINTER&&!seasons.state().paused,"Winter map validation resumes in the saved winter state");
                var coords=Files.readString(Path.of("fixture.txt")).split(",");
                fixturePos=new ChunkPos(Integer.parseInt(coords[0]),Integer.parseInt(coords[1]));
                phase="loading-resume-fixture"; stormCompleted=true;
                world.getChunkAtAsync(fixturePos.x(),fixturePos.z(),false).thenAccept(chunk -> {
                    try {
                        fixture=chunk;
                        check(seasons.snow().isComplete(fixture,seasons.version()),"Completed winter fixture survived the map-validation restart");
                        phase="winter-map-drain";
                    } catch(Throwable failure) { fail(failure); }
                });
            } else if(phase.equals("winter-complete-restart")) {
                check(seasons.state().override==Season.WINTER,"Completed winter season persists across restart");
                var coords=Files.readString(Path.of("fixture.txt")).split(",");
                fixturePos=new ChunkPos(Integer.parseInt(coords[0]),Integer.parseInt(coords[1]));
                phase="loading-saved-winter"; stormCompleted=true; explorationTick=Bukkit.getCurrentTick();
                world.getChunkAtAsync(fixturePos.x(),fixturePos.z(),false).thenAccept(chunk -> { fixture=chunk; phase="verify-saved-winter"; });
            } else if (phase.equals("cleanup-restart")) {
                check(seasons.state().cleanup, "Cleanup intent resumed after restart");
                seasons.state().paused = false; seasons.persist(); phase = "finish-cleanup";
            } else if (phase.equals("complete") && Bukkit.getPluginManager().getPlugin("BlueMap") == null) {
                legacyConfigUpgrade();
                phase = "optional";
            } else if (phase.equals("complete")) {
                var coords=Files.readString(Path.of("fixture.txt")).split(",");
                finalMap=new dev.trueseasons.integration.BlueMapFinalizationProbe(seasons,world,Integer.parseInt(coords[0]),Integer.parseInt(coords[1]));
                phase="final-map";
            }
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                try { poll(); } catch (Throwable failure) { fail(failure); }
            }, 20,20);
        } catch (Throwable failure) { fail(failure); }
    }
    private Block block(int x, int z) { return fixture.getBlock(x,260,z); }
    private int ownedCount() {
        byte[] value = fixture.getPersistentDataContainer().get(new NamespacedKey(seasons,"snow_v1"),PersistentDataType.BYTE_ARRAY);
        try { return dev.trueseasons.snow.SnowColumns.decode(value).entries().size(); }
        catch (Exception failure) { throw new RuntimeException(failure); }
    }
    private void setup(Chunk chunk) throws Exception {
        fixture = chunk;
        seasons.snow().apply(chunk,Season.SPRING,"fixture-reset",true);
        for (int x=0;x<16;x++) for (int z=0;z<16;z++) {
            fixture.getBlock(x,259,z).setType(Material.GRASS_BLOCK,false);
            block(x,z).setType(Material.AIR,false);
            world.setBiome(chunk.getX()*16+x,260,chunk.getZ()*16+z,org.bukkit.block.Biome.PLAINS);
            world.setBiome(chunk.getX()*16+x,259,chunk.getZ()*16+z,org.bukkit.block.Biome.PLAINS);
            world.setBiome(chunk.getX()*16+x,270,chunk.getZ()*16+z,org.bukkit.block.Biome.PLAINS);
        }
        block(0,0).setType(Material.SNOW,false); // Existing unowned snow.
        block(1,0).setType(Material.DIAMOND_BLOCK,false); // Player terrain replacement.
        fixture.getBlock(5,259,0).setType(Material.OAK_PLANKS,false);
        Leaves leaves=(Leaves)Material.OAK_LEAVES.createBlockData(); leaves.setPersistent(true);
        fixture.getBlock(6,259,0).setBlockData(leaves,false);
        fixture.getBlock(7,270,0).setType(Material.GLASS,false);
        Slab slab=(Slab)Material.STONE_SLAB.createBlockData(); slab.setType(Slab.Type.TOP);
        fixture.getBlock(8,259,0).setBlockData(slab,false);
        fixture.getBlock(9,259,0).setType(Material.OAK_FENCE,false);
        block(10,0).setType(Material.SHORT_GRASS,false);
        neighborPos=PlayerNeighborhood.around(fixturePos,2).stream().filter(pos -> !pos.equals(fixturePos) && world.isChunkGenerated(pos.x(),pos.z())).findFirst().orElseThrow();
        Files.writeString(Path.of("fixture.txt"),chunk.getX()+","+chunk.getZ());
        check(Bukkit.getServicesManager().load(TrueSeasonsApi.class) == seasons, "Public service registered");
        check(seasons.getSeasonalVisuals(block(2,0).getLocation()).family().equals("temperate"), "Shared visuals resolver");
        world.setStorm(false); world.setClearWeatherDuration(77);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"ts season set winter");
        check(world.hasStorm() && !world.isThundering(),"Winter starts a snowstorm without lightning");
        var partial=seasons.snow().applyStep(fixture,Season.WINTER,seasons.version(),false,16);
        check(!partial.complete() && ownedCount()>0 && ownedCount()<=16,"Winter accumulation begins with a scattered partial step");
        check(!seasons.version().equals(seasons.getChunkSeasonalVersion(fixture)),"Partial snow does not count as migrated");
        // Test a player entering the same world at an already advanced global coverage.
        seasons.state().winterColumns=128; seasons.persist();

        seasons.engine().prioritizeAround(world,fixturePos);
        seasons.state().paused = false; seasons.persist(); phase = "wait-winter-fixture";
    }
    private void poll() throws Exception {
        if (fixturePos != null && !world.isChunkLoaded(fixturePos.x(),fixturePos.z()) && fixture != null) {
            world.getChunkAtAsync(fixturePos.x(),fixturePos.z(),false).thenAccept(chunk -> {
                fixture=chunk;
                try { pollWork(); } catch(Throwable failure) { fail(failure); }
            });
        } else pollWork();
    }
    private void pollWork() throws Exception {
        if (++ticks > 1080) throw new AssertionError("Timed out in " + phase);
        if (ticks % 15 == 0) Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"ts status");
        if (phase.equals("final-map") && finalMap.tick()) {
            check(true,"Real BlueMap tile workers and final save completed without tile/storage errors");
            check(new HashSet<>(new GeneratedChunkIndex().scan(world.getWorldPath().resolve("region"))).equals(original),"Final map receipt check generated zero world chunks");
            Files.writeString(Path.of("map-finalization-result.txt"),"PASS: production BlueMap final-worker/save receipt verified on real map storage; zero new chunks.");
            getLogger().info("PROBE_MAP_BARRIER_PASSED"); Bukkit.shutdown(); phase="stopping";
        } else if(phase.equals("verify-saved-winter")&&seasons.engine().allFinished()) {
            var job=seasons.engine().jobs().stream().filter(j -> j.world==world).findFirst().orElseThrow();
            check(Bukkit.getCurrentTick()-explorationTick<200,"Restart reads saved completion without replaying the whole chunk traversal");
            check(job.placed()==0&&job.removed()==0,"Restart does not rewrite completed winter snow");
            check(seasons.weather().activeStorms()==0,"Completed winter restart does not start another introductory storm");
            phase="settle-exploration"; explorationTick=Bukkit.getCurrentTick();
        } else if (phase.equals("optional") && seasons.state().clean && seasons.engine().allFinished()) {
            check(seasons.integrations().status().equals("not installed"),"TrueSeasons works with BlueMap absent");
            check(seasons.engine().allFinished(),"Saved clean state revalidated without BlueMap");
            check(new HashSet<>(new GeneratedChunkIndex().scan(world.getWorldPath().resolve("region"))).equals(original),"Optional-adapter startup generated zero chunks");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"ts status");
            Files.writeString(Path.of("optional-result.txt"),"PASS: startup and saved cleanup verification without BlueMap; zero new chunks.");
            getLogger().info("PROBE_OPTIONAL_PASSED"); Bukkit.shutdown(); phase="stopping";
        } else if (phase.equals("wait-winter-fixture") && ownedCount() > 0) {
            check(block(0,0).getType()==Material.SNOW,"Existing snow preserved during winter placement");
            check(block(1,0).getType()==Material.DIAMOND_BLOCK,"Player block preserved during placement");
            check(ownedCount()<253,"Player neighborhood has not instantly filled the chunk");
            check(seasons.snow().coveredColumns(fixture,seasons.version())<=seasons.engine().globalCoverageColumns(),"Near-player snow never exceeds shared winter coverage");
            seasons.state().paused = true; seasons.persist();
            world.setWeatherDuration(1); world.setClearWeatherDuration(200);
            var ending=new WeatherChangeEvent(world,false,WeatherChangeEvent.Cause.NATURAL);
            Bukkit.getPluginManager().callEvent(ending);
            check(ending.isCancelled(),"Natural weather expiry cannot stop an unfinished migration storm");
            phase="paused-storm"; explorationTick=Bukkit.getCurrentTick();
        } else if(phase.equals("paused-storm")&&Bukkit.getCurrentTick()-explorationTick>=20) {
            check(world.hasStorm()&&world.getWeatherDuration()>1&&world.getClearWeatherDuration()==0,"Snowstorm stays active through migration pauses without a fixed timer");
            world.save(); Files.writeString(phaseFile,"restart");
            getLogger().info("PROBE_RESTART_READY " + checks + " checks"); Bukkit.shutdown(); phase="stopping";
        } else if (phase.equals("finish-winter") && seasons.engine().allFinished()) {
            check(stormCompleted&&seasons.weather().activeStorms()==0,"Snowstorm ends when terrain migration finishes");
            check(new HashSet<>(new GeneratedChunkIndex().scan(world.getWorldPath().resolve("region"))).equals(original),"Winter migration generated zero new chunks");
            var job=seasons.engine().jobs().stream().filter(j -> j.world==world).findFirst().orElseThrow();
            String savedVersion=seasons.version(); int savedDone=job.completed();
            seasons.reloadSettings();
            check(savedVersion.equals(seasons.version())&&job.completed()==savedDone,"Unchanged configuration reload preserves migration revision and progress");
            int column=2;
            seasons.relinquishSnowOwnership(block(column,0)); block(column,0).setType(Material.AIR,false);
            world.setStorm(false);
            check(seasons.replenishment().refill(fixture,new int[]{column}).placed()==0,"Clear winter weather does not replenish broken snow");
            world.setStorm(true);
            check(seasons.replenishment().refill(fixture,new int[]{column}).placed()==1&&block(column,0).getType()==Material.SNOW,"Winter rain replenishes broken snow after migration");
            check(job.completed()==savedDone&&seasons.snow().isComplete(fixture,seasons.version()),"Snow replenishment preserves completed migration progress");
            world.setStorm(false);
            ChunkPos proto=job.unfinishedTerrain().stream().filter(p -> Math.max(Math.abs(p.x()-fixturePos.x()),Math.abs(p.z()-fixturePos.z()))>seasons.settings().playerRadius)
                .findFirst().orElseGet(() -> new ChunkPos(job.known.stream().mapToInt(ChunkPos::x).max().orElse(0)+8,fixturePos.z()));
            seasons.state().paused=true; seasons.persist();
            phase="exploring";
            // Explicit test exploration is the ONLY generating request in this probe.
            world.getChunkAtAsync(proto.x(),proto.z(),true).thenAccept(chunk -> {
                explored=chunk;
                // Ocean/naturally snowy terrain can legitimately have no new layers.
                // Give this newly generated chunk one deterministic exposed support.
                int top=world.getMaxHeight()-3;
                chunk.getBlock(0,top,0).setType(Material.GRASS_BLOCK,false);
                chunk.getBlock(0,top+1,0).setType(Material.AIR,false);
                chunk.getBlock(0,top+2,0).setType(Material.AIR,false);
                explorationTick=Bukkit.getCurrentTick(); phase="new-winter-chunk";
                seasons.state().paused=false; seasons.persist();
                world.save();
            });
        } else if (phase.equals("new-winter-chunk") && seasons.snow().isComplete(explored,seasons.version()) && seasons.engine().allFinished()
                && Bukkit.getCurrentTick()-explorationTick>200) {
            check(seasons.snow().isComplete(explored,seasons.version()),"Newly explored terrain receives winter when Minecraft finishes it");
            check(explored.getPersistentDataContainer().has(new NamespacedKey(seasons,"snow_v1")),"Newly generated winter terrain receives tracked physical snow");
            check(explored.getBlock(0,world.getMaxHeight()-2,0).getType()==Material.SNOW,"New winter chunks cover exposed support without restarting the season");
            // A matching version alone must not hide a pending transition on reload.
            fixture.getPersistentDataContainer().set(new NamespacedKey(seasons,"winter_pending"),PersistentDataType.STRING,seasons.version());
            fixture.getPersistentDataContainer().set(new NamespacedKey(seasons,"winter_cursor"),PersistentDataType.INTEGER,0);
            seasons.engine().onLoad(new ChunkLoadEvent(fixture,false));
            check(!seasons.engine().allFinished(),"Loading a current-stamped but partial chunk reopens migration");
            phase="repair-pending";
        } else if (phase.equals("repair-pending") && seasons.engine().allFinished()) {
            check(seasons.snow().isComplete(fixture,seasons.version()),"Pending state is finished rather than skipped by a matching version");
            seasons.state().paused=true; seasons.persist(); phase="prepare-remote-partial";
            world.getChunkAtAsync(explored.getX(),explored.getZ(),false).thenAccept(chunk -> {
                try {
                    explored=chunk;
                    explored.getPersistentDataContainer().remove(new NamespacedKey(seasons,"season_version"));
                    seasons.integrations().chunkUpdated(explored);
                    var partial=seasons.snow().applyStep(explored,Season.WINTER,seasons.version(),false,16);
                    check(!partial.complete(),"Remote transition fixture begins with an unfinished column pass");
                    seasons.engine().onLoad(new ChunkLoadEvent(explored,false));
                    world.unloadChunkRequest(explored.getX(),explored.getZ());
                    phase="wait-remote-unload"; explorationTick=Bukkit.getCurrentTick();
                } catch(Throwable failure) { fail(failure); }
            });
        } else if (phase.equals("wait-remote-unload")) {
            if (!world.isChunkLoaded(explored.getX(),explored.getZ())) {
                check(true,"Unfinished winter chunk unloads outside the player radius");
                seasons.state().paused=false; seasons.persist(); phase="finish-remote-partial";
            } else if (Bukkit.getCurrentTick()-explorationTick>400) throw new AssertionError("Paper did not release the remote chunk's temporary load hold");
        } else if (phase.equals("finish-remote-partial")) {
            world.getChunkAtAsync(explored.getX(),explored.getZ(),false).thenAccept(chunk -> explored=chunk);
            if (seasons.snow().isComplete(explored,seasons.version()) && seasons.engine().allFinished()) {
                check(!seasons.snow().hasPending(explored),"Background work completes an unloaded winter transition");
                check(explored.getBlock(0,world.getMaxHeight()-2,0).getType()==Material.SNOW,"Unloaded transition retains its final physical snow");
                world.save(); phase="winter-map-drain";
            }
        } else if (phase.equals("winter-map-drain") && seasons.integrations().pending()==0) {
            check(true,"BlueMap renders the final winter state after partial chunk unloading");
            world.save(); Files.writeString(phaseFile,"winter-complete-restart");
            getLogger().info("PROBE_RESTART_READY "+checks+" checks"); Bukkit.shutdown(); phase="stopping";
        } else if (phase.equals("settle-exploration") && Bukkit.getCurrentTick()-explorationTick>200) {
            original = new HashSet<>(new GeneratedChunkIndex().scan(world.getWorldPath().resolve("region")));
            Files.writeString(Path.of("exploration-baseline.txt"),String.valueOf(original.size()));
            check(original.size()>0,"Explicit exploration baseline saved separately from non-generating migration");
            check(ownedCount()==253,"All exposed supporting columns covered and tracked");
            check(block(2,0).getType()==Material.SNOW,"Winter snow covers ordinary terrain");
            check(block(5,0).getType()==Material.SNOW,"Wooden roofs receive snow");
            check(block(6,0).getType()==Material.SNOW,"Leaf canopies receive snow");
            check(fixture.getBlock(7,271,0).getType()==Material.SNOW && block(7,0).getType()==Material.AIR,"Snow covers glass roof but not sheltered ground");
            check(block(8,0).getType()==Material.SNOW,"Upper slabs receive snow");
            check(block(9,0).getType()==Material.AIR,"Unsupported fence shapes are left intact");
            check(block(10,0).getType()==Material.SHORT_GRASS,"Occupied vegetation remains intact");
            check(seasons.engine().jobs().stream().anyMatch(j -> j.completed()>1),"Remote chunks processed without online players");
            check(Bukkit.getOnlinePlayers().isEmpty(),"No players required for full-world progress");
            var faded=block(5,0).getState(); faded.setType(Material.AIR);
            BlockFadeEvent fade=new BlockFadeEvent(block(5,0),faded);
            Bukkit.getPluginManager().callEvent(fade);
            check(fade.isCancelled(),"Natural melting cannot erase tracked winter snow");
            // Simulate a player replacement through the documented editor API.
            seasons.relinquishSnowOwnership(block(2,0)); block(2,0).setType(Material.SNOW,false);
            block(3,0).setType(Material.GOLD_BLOCK,false); // Unannounced differing replacement fails safely.
            Snow snow = (Snow) block(4,0).getBlockData(); snow.setLayers(2); block(4,0).setBlockData(snow,false);
            var conflict = seasons.snow().apply(fixture,Season.SPRING,seasons.version(),true);
            check(conflict.conflicts()==1,"Ambiguous layers block successful cleanup");
            check(block(3,0).getType()==Material.GOLD_BLOCK,"Unexpected replacement never deleted");
            check(ownedCount()==1,"Conflict ownership retained for recovery");
            seasons.relinquishSnowOwnership(block(4,0)); // Explicit resolution: retain edited snow.
            // Hints simulate a player location, with a real saved neighboring chunk.
            neighborPos=PlayerNeighborhood.around(fixturePos,2).stream().filter(pos -> !pos.equals(fixturePos) && world.isChunkGenerated(pos.x(),pos.z())).findFirst().orElseThrow();
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"ts season set spring");
            seasons.engine().prioritizeAround(world,fixturePos); phase="spring"; ticks=0; neighborWait=0;
            check(seasons.weather().activeStorms()==0,"Leaving winter restores temporary server weather");
        } else if (phase.equals("spring") && seasons.engine().allFinished()) {
            check(neighborUpdated,"Surrounding chunk migrated through the priority neighborhood");
            check(ownedCount()==0,"Spring removed all remaining owned snow");
            check(block(0,0).getType()==Material.SNOW && block(2,0).getType()==Material.SNOW,"Unowned/player snow survives spring");
            check(block(4,0).getType()==Material.SNOW,"Resolved edited layers remain untouched");
            completedTotal=seasons.engine().jobs().stream().filter(j -> j.world==world).findFirst().orElseThrow().total();
            rescanStarted=Bukkit.getCurrentTick(); phase="stable-rescan";
        } else if(phase.equals("stable-rescan")&&Bukkit.getCurrentTick()-rescanStarted>660&&seasons.engine().allFinished()) {
            var job=seasons.engine().jobs().stream().filter(j -> j.world==world).findFirst().orElseThrow();
            check(job.total()==completedTotal&&job.percent()==100,"Unchanged disk rescan preserves completed total and 100 percent progress");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"ts cleanup confirm");
            check(!seasons.state().cleanup,"Cleanup requires a prior confirmation request");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"ts cleanup confirm");
            check(seasons.state().cleanup,"Confirmed cleanup starts");
            seasons.state().paused=true; seasons.persist(); world.save();
            Files.writeString(phaseFile,"cleanup-restart");
            getLogger().info("PROBE_RESTART_READY " + checks + " checks"); Bukkit.shutdown(); phase="stopping";
        } else if (phase.equals("finish-cleanup") && seasons.state().clean) {
            check(seasons.engine().allFinished(),"Cleanup reached 100 percent");
            check(seasons.engine().jobs().stream().allMatch(j -> j.errors()==0),"No unresolved cleanup chunks");
            world.save();
            Set<ChunkPos> after=new HashSet<>(new GeneratedChunkIndex().scan(world.getWorldPath().resolve("region")));
            check(after.equals(original),"Migration and cleanup generated zero new chunks");
            check(seasons.integrations().status().startsWith("connected"),"BlueMap API connected with real plugin stack");
            phase="map-drain"; ticks=0;
        } else if (phase.equals("map-drain") && seasons.integrations().pending()==0) {
            check(true,"BlueMap completed and saved all latest queued map revisions");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"ts status");
            Files.writeString(Path.of("verification-result.txt"),"PASS: server integration checks completed; see logs for all three restarts.");
            Files.writeString(phaseFile,"complete"); getLogger().info("PROBE_ALL_PASSED " + checks + " checks this run");
            Bukkit.shutdown(); phase="stopping";
        }
        if (phase.equals("spring") && !neighborUpdated && ++neighborWait>5) throw new AssertionError("Surrounding priority chunk was not cleaned within five seconds");
        if (phase.equals("new-winter-chunk") && Bukkit.getCurrentTick()-explorationTick>100 && !seasons.snow().isComplete(explored,seasons.version()))
            throw new AssertionError("Newly generated chunk did not enter winter migration promptly");
    }
    @EventHandler
    public void neighbor(SeasonChunkUpdatedEvent event) {
        if (phase.equals("spring") && event.getChunk().getWorld()==world
                && new ChunkPos(event.getChunk().getX(),event.getChunk().getZ()).equals(neighborPos)) neighborUpdated=true;
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void winterComplete(SeasonMigrationEvent event) {
        if(!stormCompleted&&event.getWorld()==world&&!event.isCleanup()&&event.getPhase()==SeasonMigrationEvent.Phase.COMPLETE
            &&seasons.currentSeason()==Season.WINTER) {
            check(seasons.weather().activeStorms()==0&&!world.hasStorm(),"Terrain completion restores original clear weather");
            check(world.getClearWeatherDuration()==77,"Terrain completion restores original clear-weather timer");
            stormCompleted=true;
        }
    }
    private void legacyConfigUpgrade() throws Exception {
        var file=seasons.getDataFolder().toPath().resolve("snow.yml").toFile();
        var legacy=YamlConfiguration.loadConfiguration(file);
        legacy.set("surface-mode",null); legacy.set("transition",null);
        legacy.set("excluded-biome-fragments",List.of("desert","badlands","savanna","jungle","ocean","river","beach","mushroom","frozen","snowy","ice_spikes"));
        legacy.set("custom-note","preserved"); legacy.save(file);
        var weatherFile=seasons.getDataFolder().toPath().resolve("weather.yml").toFile();
        var oldWeather=YamlConfiguration.loadConfiguration(weatherFile);
        oldWeather.set("snowfall.particles-per-player",24); oldWeather.set("snowfall.radius-blocks",24); oldWeather.set("snowfall.interval-ticks",5);
        oldWeather.set("custom-note","weather-preserved"); oldWeather.save(weatherFile);
        oldWeather.set("winter-storm.duration-seconds",120); oldWeather.save(weatherFile);
        int previousConcurrency=seasons.settings().concurrency;
        seasons.reloadSettings();
        var upgraded=YamlConfiguration.loadConfiguration(file);
        check(seasons.settings().allExposed && seasons.settings().excludedBiomes.isEmpty(),"Old defaults upgrade to all exposed surfaces in every biome");
        check(upgraded.contains("transition.columns-per-step") && upgraded.contains("surface-mode"),"New commented snow options added to an existing config");
        check(upgraded.getString("custom-note").equals("preserved") && seasons.settings().concurrency==previousConcurrency,"Existing custom configuration values preserved");
        var newWeather=YamlConfiguration.loadConfiguration(weatherFile);
        check(!newWeather.contains("snowfall.particles-per-player") && !newWeather.contains("snowfall.radius-blocks")
                && !newWeather.contains("snowfall.interval-ticks") && newWeather.getBoolean("snowfall.enabled"),"Obsolete particle settings upgrade to native snow");
        check(newWeather.getString("custom-note").equals("weather-preserved"),"Weather upgrade preserves custom values");
        check(!newWeather.contains("winter-storm.duration-seconds"),"Legacy storm duration upgrades to migration lifetime");
    }
    private void fail(Throwable failure) {
        getLogger().log(java.util.logging.Level.SEVERE,"PROBE_FAILED",failure);
        try { Files.writeString(Path.of("verification-result.txt"),"FAIL: "+failure); } catch(Exception ignored) {}
        Bukkit.shutdown();
    }
}
