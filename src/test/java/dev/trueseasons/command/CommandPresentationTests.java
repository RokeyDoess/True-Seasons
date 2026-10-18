package dev.trueseasons.command;

import dev.trueseasons.TrueSeasonsPlugin;
import dev.trueseasons.api.Season;
import dev.trueseasons.config.Settings;
import dev.trueseasons.integration.IntegrationAdapter;
import dev.trueseasons.integration.IntegrationManager;
import dev.trueseasons.migration.ChunkMigrationEngine;
import dev.trueseasons.migration.ChunkPos;
import dev.trueseasons.migration.TpsPoller;
import dev.trueseasons.state.StateStore;
import dev.trueseasons.weather.WeatherService;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

/** Captures the actual native command messages; no running server or legacy color codes. */
public final class CommandPresentationTests {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type));
    }
    private static void set(Object object, String name, Object value) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static boolean colored(Component component, String text, NamedTextColor color) {
        if (component instanceof TextComponent leaf && leaf.content().contains(text) && color.equals(leaf.color())) return true;
        return component.children().stream().anyMatch(child -> colored(child, text, color));
    }
    private static String plain(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }

    public static void main(String[] args) throws Exception {
        UUID id = UUID.randomUUID();
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, values) -> switch (method.getName()) {
            case "getUID" -> id; case "getName" -> "fixture-world";
            default -> throw new AssertionError("Command presentation must not inspect terrain: " + method.getName());
        });
        Constructor<ChunkMigrationEngine.Job> constructor = ChunkMigrationEngine.Job.class.getDeclaredConstructor(World.class);
        constructor.setAccessible(true);
        ChunkMigrationEngine.Job job = constructor.newInstance(world);
        ChunkPos done = new ChunkPos(0,0), pending = new ChunkPos(1,0);
        job.known.add(done); job.known.add(pending);
        set(job, "done", new HashSet<>(Set.of(done))); set(job, "scanned", true);
        set(job, "nextStep", new HashMap<>(Map.of(pending, 20))); set(job, "failures", new HashMap<>(Map.of(pending, 30L)));

        TrueSeasonsPlugin plugin = allocate(TrueSeasonsPlugin.class);
        StateStore state = allocate(StateStore.class);
        set(state, "managedWorlds", new LinkedHashMap<>()); set(state, "winterVersion", "fixture-winter"); set(state, "winterColumns", 128);
        Settings settings = allocate(Settings.class); set(settings, "playerRadius", 4); set(settings, "minTps", 18.5); set(settings,"enabled",true);
        WeatherService weather = allocate(WeatherService.class); set(weather, "storms", new HashMap<>());
        int[] displayRegions={3};
        IntegrationAdapter maps = (IntegrationAdapter) Proxy.newProxyInstance(IntegrationAdapter.class.getClassLoader(), new Class<?>[]{IntegrationAdapter.class},
                (proxy, method, values) -> switch (method.getName()) {
                    case "summary" -> "updating; waiting for saves"; case "status" -> "connected; 2 render batches; 99 metadata purges; region renders scheduled this run: 15; palette regions pending: 8; waiting for final saves: 3"; case "pending" -> 3;case "pendingRegions"->displayRegions[0]; case "cleanupPending" -> 2;
                    default -> throw new AssertionError("Status must not mutate map requests: " + method.getName());
                });
        IntegrationManager integrations = allocate(IntegrationManager.class); set(integrations, "blueMap", maps);
        ChunkMigrationEngine engine = allocate(ChunkMigrationEngine.class); set(engine, "plugin", plugin); set(engine, "jobs", Map.of(id, job));
        set(engine, "tps", new TpsPoller(() -> 20));
        var headroom=new dev.trueseasons.migration.RecentTickHeadroom();set(engine,"headroom",headroom);
        set(plugin, "state", state); set(plugin, "settings", settings); set(plugin, "weather", weather);
        set(plugin, "integrations", integrations); set(plugin, "engine", engine); set(plugin, "season", Season.WINTER); set(plugin, "version", "fixture-winter");

        List<Component> messages = new ArrayList<>(); boolean[] permitted = {true};
        CommandSender sender = (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(), new Class<?>[]{CommandSender.class},
                (proxy, method, values) -> switch (method.getName()) {
                    case "hasPermission" -> permitted[0]; case "getName" -> "ConsoleFixture";
                    case "sendMessage" -> {
                        if (values.length != 1 || !(values[0] instanceof Component component))
                            throw new AssertionError("All command messages must use Adventure components");
                        messages.add(component); yield null;
                    }
                    default -> throw new AssertionError("Unexpected sender access: " + method.getName());
                });
        SeasonsCommand command = new SeasonsCommand(plugin);
        for(String[] arguments:List.of(new String[]{"migration"},new String[]{"migration","status"})){
            messages.clear();command.onCommand(sender,null,"trueseasons",arguments);
            check(plain(messages.getFirst()).startsWith("Migration status"),"Migration routes retain a clear separator");
            check(messages.size()==5,"Normal migration output stays within five short rows for one world");
            check(messages.stream().anyMatch(message->colored(message,"1 / 2 chunks (50.0%)",NamedTextColor.AQUA)),"Chunk ratios and percentages retain an aqua highlight");
            check(messages.stream().anyMatch(message->colored(message,"1 deferred",NamedTextColor.RED)),"Deferred work is highlighted without printing successful zero counters");
            check(messages.stream().filter(message->plain(message).startsWith("BlueMap:")).count()==1,"BlueMap has one summary row");
            check(messages.stream().noneMatch(message->plain(message).contains("metadata purges")||plain(message).contains("render batches")||plain(message).contains("Plugin removal")),"Default migration avoids map internals and unrelated removal text");
        }
        var mapRow=messages.stream().filter(message->plain(message).startsWith("BlueMap:")).findFirst().orElseThrow();
        check(colored(mapRow,"3 regions pending",NamedTextColor.GOLD)&&mapRow.hoverEvent()!=null,"Pending map counts are amber and full diagnostics remain available on hover");
        Component live=new CommandOutput(plugin).progressUpdate();
        check(plain(live).startsWith("Migration status")&&!plain(live).contains("\n")&&colored(live,"1 / 2 chunks (50.0%)",NamedTextColor.AQUA),"Live notifications use one compact line with highlighted progress");
        check(plain(live).contains("snow: 50.0%")&&plain(live).contains("BlueMap: 3 regions pending"),"Live notifications retain shared coverage and compact map progress");
        headroom.observe(90,3);messages.clear();command.onCommand(sender,null,"trueseasons",new String[]{"migration"});
        check(plain(messages.getFirst()).endsWith("WAITING FOR TICK TIME"),"Recent tick pressure has an explicit command phase even at twenty historical TPS");
        set(settings,"minTps",0.0);check(!engine.waitingForTickTime(),"Disabling the TPS guard also disables recent tick throttling");set(settings,"minTps",18.5);
        headroom.observe(10,3);headroom.observe(10,3);check(!engine.waitingForTickTime(),"Healthy ticks resume without changing saved pause state");
        displayRegions[0]=-1;
        check(plain(new CommandOutput(plugin).progressUpdate()).contains("BlueMap: updates pending"),"Unknown region discovery never invents a physical region count");displayRegions[0]=3;
        check(command.onTabComplete(sender,null,"trueseasons",new String[]{"migration","no"}).equals(List.of("notify")),"Live notifications are discoverable in command completion");
        messages.clear();command.onCommand(sender,null,"trueseasons",new String[]{"migration","notify"});
        check(messages.size()==1&&plain(messages.getFirst()).contains("available to players in game"),"Console cannot accidentally create a persistent progress subscription");
        messages.clear();command.onCommand(sender,null,"trueseasons",new String[]{"status"});
        check(plain(messages.getFirst()).startsWith("Season status")&&messages.size()==5,"Status is a compact overview including a single actionable error row");
        check(messages.stream().noneMatch(message->plain(message).contains("fixture-world:")||plain(message).contains("region none")),"Status summarizes all worlds rather than duplicating migration rows");
        check(messages.stream().anyMatch(message->plain(message).startsWith("Winter coverage:")&&colored(message,"50.0%",NamedTextColor.AQUA)),"The shared winter coverage stays easy to find");
        messages.clear();command.onCommand(sender,null,"trueseasons",new String[]{"migration","details"});
        check(messages.stream().anyMatch(message->plain(message).contains("blocks +"))&&messages.stream().anyMatch(message->plain(message).startsWith("Map cleanup:")),"Detailed counters require an explicit request");
        check(command.onTabComplete(sender,null,"trueseasons",new String[]{"migration","de"}).equals(List.of("details")),"Detailed diagnostics are discoverable");
        for(String action:List.of("cleanup","uninstall")){
            messages.clear();command.onCommand(sender,null,"trueseasons",new String[]{action,"status"});
            check(plain(messages.getFirst()).startsWith("Cleanup status")&&messages.stream().anyMatch(message->plain(message).contains("Plugin removal: NOT READY")),"Cleanup has a dedicated header and removal readiness");
        }
        check(!state.cleanup,"Reading cleanup status cannot start restoration");
        set(job,"scanned",false);messages.clear();command.onCommand(sender,null,"trueseasons",new String[]{"status"});
        check(plain(messages.getFirst()).endsWith("READING SAVED PROGRESS"),"Initial saved indexing is identified clearly");
        check(messages.stream().noneMatch(message->plain(message).contains("%")),"Unknown saved progress never shows a misleading percentage");
        check(!plain(new CommandOutput(plugin).progressUpdate()).contains("%"),"Live notifications also suppress unknown percentages while indexing");
        state.cleanup=true;messages.clear();command.onCommand(sender,null,"trueseasons",new String[]{"cleanup","status"});
        check(plain(messages.getFirst()).endsWith("CLEANUP"),"Cleanup takes precedence while indexing");
        state.cleanup=false;state.clean=true;messages.clear();command.onCommand(sender,null,"trueseasons",new String[]{"status"});
        check(plain(messages.getFirst()).endsWith("RESTORED"),"Restored mode takes precedence while indexing");
        state.clean=false;set(job,"scanned",true);
        set(job,"done",new HashSet<>(Set.of(done,pending)));set(job,"nextStep",new HashMap<>());set(job,"failures",new HashMap<>());
        set(job,"completed",true);set(job,"scanning",true);
        messages.clear();command.onCommand(sender,null,"trueseasons",new String[]{"migration"});
        check(plain(messages.getFirst()).endsWith("TERRAIN COMPLETE — MAP PENDING"),"A periodic rescan does not make completed terrain appear to restart");
        check(messages.stream().anyMatch(message->colored(message,"2 / 2 chunks (100.0%)",NamedTextColor.AQUA)),"Completed world rows retain verified progress during periodic rescans");
        check(!engine.allFinished(),"Command summaries do not loosen internal completion checks");
        set(job,"done",new HashSet<>(Set.of(done)));set(job,"nextStep",new HashMap<>(Map.of(pending,20)));
        set(job,"failures",new HashMap<>(Map.of(pending,30L)));set(job,"completed",false);set(job,"scanning",false);
        set(settings,"snowmelt",true);set(plugin,"season",Season.SPRING);set(state,"meltVersion","fixture-winter");state.meltColumns=64;
        messages.clear();command.onCommand(sender,null,"trueseasons",new String[]{"status"});
        check(plain(messages.getFirst()).endsWith("SNOWMELT")&&messages.stream().anyMatch(message->colored(message,"25.0% remaining",NamedTextColor.AQUA)),"Snowmelt separates visible coverage from unchanged physical chunk progress");
        set(settings,"snowmelt",false);set(plugin,"season",Season.WINTER);
        Path confirmationDirectory=Files.createTempDirectory(Path.of("").toAbsolutePath(),"trueseasons-command-state-");
        try {
            set(state,"file",confirmationDirectory.resolve("jobs.yml"));
            // A manual target keeps this command-only fixture independent of the wall clock and Bukkit worlds.
            set(settings,"enabled",false);state.override=Season.WINTER;
            set(plugin,"version","manual-WINTER-r0-snow4");
            set(plugin,"engineVersion","manual-WINTER-r0-snow4");
            set(plugin,"clock",java.time.Clock.systemUTC());
            set(settings,"calendar",new dev.trueseasons.calendar.SeasonCalendar(java.time.ZoneOffset.UTC,Map.of(Season.SPRING,List.of(3,4,5),Season.SUMMER,List.of(6,7,8),Season.AUTUMN,List.of(9,10,11),Season.WINTER,List.of(12,1,2))));
            var palettes=new org.bukkit.configuration.file.YamlConfiguration();
            for(Season season:Season.values())palettes.set("palettes.temperate."+season.key(),List.of("83B957","D98637"));
            set(plugin,"visuals",new dev.trueseasons.visual.SeasonVisualResolver(palettes));
            for(String action : List.of("pause","resume")) {
                messages.clear(); command.onCommand(sender,null,"trueseasons",new String[]{"migration",action});
                String feedback=action.equals("pause")?"paused":"resumed";
                NamedTextColor color=action.equals("pause")?NamedTextColor.YELLOW:NamedTextColor.GREEN;
                check(messages.size()==1 && plain(messages.getFirst()).startsWith("Migration status")
                        && plain(messages.getFirst()).endsWith(feedback) && colored(messages.getFirst(),feedback,color),
                        "Pause and resume acknowledgments have a Migration status separator and clear feedback: "
                                +messages.stream().map(CommandPresentationTests::plain).toList());
                check(state.paused==action.equals("pause") && Files.readString(confirmationDirectory.resolve("jobs.yml")).contains("paused: "+state.paused),
                        "Pause and resume feedback corresponds to the persisted migration state");
                check(state.pauseReason.equals(state.paused?"Paused manually.":""),"Manual pause and resume update the reason with the saved intent");
            }
            plugin.pauseTransitions(true);set(plugin,"engineVersion","previous-target");set(engine,"jobs",Map.of());
            plugin.pauseTransitions(false);
            Field engineVersion=TrueSeasonsPlugin.class.getDeclaredField("engineVersion");engineVersion.setAccessible(true);
            check(engineVersion.get(plugin).equals(plugin.version())&&!state.paused&&state.winterColumns==128,"Resume recovers a target change interrupted before its jobs restarted, preserving saved coverage");
            set(engine,"jobs",Map.of(id,job));
            state.paused=true;state.pauseReason="Automatic pause during BlueMap update: disk full";state.save();
            check(new StateStore(confirmationDirectory).pauseReason.equals(state.pauseReason),"Automatic pause reasons survive restart");
            messages.clear();command.onCommand(sender,null,"trueseasons",new String[]{"migration"});
            check(messages.stream().anyMatch(message->plain(message).contains("disk full")&&plain(message).contains("/ts migration resume")),"Migration output explains the pause and its resume command");
            check(plain(new CommandOutput(plugin).progressUpdate()).contains("disk full"),"Private progress updates also explain a pause");
            state.pauseReason="";messages.clear();command.onCommand(sender,null,"trueseasons",new String[]{"status"});
            check(messages.stream().anyMatch(message->plain(message).contains("reason unavailable")),"Legacy pauses are clearly distinguished from known failures");state.paused=false;
        } finally {
            Files.deleteIfExists(confirmationDirectory.resolve("jobs.yml.tmp"));
            Files.deleteIfExists(confirmationDirectory.resolve("jobs.yml"));
            Files.deleteIfExists(confirmationDirectory);
        }

        messages.clear(); permitted[0] = false; command.onCommand(sender,null,"trueseasons",new String[]{"migration"});
        check(messages.size() == 1 && colored(messages.getFirst(),"You lack permission",NamedTextColor.RED), "Permission errors are clearly red");
        permitted[0] = true; messages.clear(); command.onCommand(sender,null,"trueseasons",new String[]{"unknown"});
        check(messages.size() == 1 && colored(messages.getFirst(),"Usage: ",NamedTextColor.GRAY)
                && colored(messages.getFirst(),"/trueseasons",NamedTextColor.GOLD), "Usage highlights the command without an all-white message");
        messages.clear(); command.onCommand(sender,null,"trueseasons",new String[]{"season","set","unknown"});
        check(messages.size() == 1 && colored(messages.getFirst(),"Command failed: ",NamedTextColor.RED), "Command errors use a red highlight");
        for (double tps : new double[]{20, 18.75, 17.5}) {
            set(engine, "tps", new TpsPoller(() -> tps)); messages.clear();
            command.onCommand(sender,null,"trueseasons",new String[]{"status"});
            NamedTextColor expected = tps == 20 ? NamedTextColor.GREEN : tps == 18.75 ? NamedTextColor.YELLOW : NamedTextColor.RED;
            check(messages.stream().anyMatch(message -> plain(message).contains("TPS:")
                    && colored(message,String.format(Locale.ROOT,"%.2f",tps),expected)), "TPS color conveys healthy, marginal or paused performance");
        }
        set(settings,"minTps",20.0); set(engine,"tps",new TpsPoller(() -> 19.5)); messages.clear();
        command.onCommand(sender,null,"trueseasons",new String[]{"status"});
        check(messages.stream().anyMatch(message -> plain(message).contains("TPS:") && colored(message,"19.50",NamedTextColor.RED)),
                "TPS below a customized migration threshold cannot appear healthy");
        int[] samples = {0}; double[] reported = {20};
        TpsPoller poller = new TpsPoller(() -> { samples[0]++; return reported[0]; });
        check(poller.read(1000) == 20 && samples[0] == 1, "The first TPS request samples its source");
        reported[0] = 17;
        check(poller.read(1001) == 20 && poller.read(1500) == 20 && poller.read(1999) == 20 && samples[0] == 1,
                "Repeated ticks and commands reuse one TPS sample within a second");
        check(poller.read(2000) == 17 && samples[0] == 2, "TPS refreshes at the next sampling interval");
        check(poller.read(1500) == 17 && samples[0] == 3, "A backward clock adjustment refreshes the TPS cache");
        System.out.println("Command presentation tests passed: " + assertions + " assertions");
    }
}
