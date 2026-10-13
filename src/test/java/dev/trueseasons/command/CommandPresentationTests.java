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
        Settings settings = allocate(Settings.class); set(settings, "playerRadius", 4); set(settings, "minTps", 18.5);
        WeatherService weather = allocate(WeatherService.class); set(weather, "storms", new HashMap<>());
        IntegrationAdapter maps = (IntegrationAdapter) Proxy.newProxyInstance(IntegrationAdapter.class.getClassLoader(), new Class<?>[]{IntegrationAdapter.class},
                (proxy, method, values) -> switch (method.getName()) {
                    case "status" -> "connected"; case "pending" -> 3; case "cleanupPending" -> 2;
                    default -> throw new AssertionError("Status must not mutate map requests: " + method.getName());
                });
        IntegrationManager integrations = allocate(IntegrationManager.class); set(integrations, "blueMap", maps);
        ChunkMigrationEngine engine = allocate(ChunkMigrationEngine.class); set(engine, "plugin", plugin); set(engine, "jobs", Map.of(id, job));
        set(engine, "tps", new TpsPoller(() -> 20));
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
        for (String[] arguments : List.of(new String[]{"status"},new String[]{"migration"},new String[]{"migration","status"},new String[]{"cleanup","status"},new String[]{"uninstall","status"})) {
            messages.clear(); command.onCommand(sender, null, "trueseasons", arguments);
            check(!messages.isEmpty() && plain(messages.getFirst()).startsWith("Migration status"), "Every status route starts with Migration status");
            check(colored(messages.getFirst(), "Migration status", NamedTextColor.GOLD), "Status headers use a readable gold highlight");
        }
        check(messages.stream().anyMatch(message -> colored(message, "1 / 2 chunks (50.0%)", NamedTextColor.AQUA)), "Progress ratio and percentage are highlighted together in aqua");
        check(messages.stream().anyMatch(message -> plain(message).startsWith("Shared winter") && colored(message,"50.0%",NamedTextColor.AQUA)), "Shared winter coverage has its own aqua highlight");
        check(messages.stream().anyMatch(message -> colored(message,"; deferred: ",NamedTextColor.GRAY) && colored(message,"1",NamedTextColor.RED)), "Deferred work is red beside a gray label");
        check(messages.stream().anyMatch(message -> plain(message).startsWith("BlueMap:") && colored(message,"3",NamedTextColor.GOLD) && colored(message,"2",NamedTextColor.GOLD)), "Pending map counts are amber");
        check(!state.cleanup, "Viewing cleanup status cannot start a cleanup");
        check(command.onTabComplete(sender,null,"trueseasons",new String[]{"cleanup","st"}).equals(List.of("status")), "Cleanup status is discoverable through tab completion");

        set(job,"scanned",false); messages.clear(); command.onCommand(sender,null,"trueseasons",new String[]{"status"});
        check(plain(messages.getFirst()).startsWith("Migration status") && plain(messages.getFirst()).endsWith("INDEXING SAVED PROGRESS"),
                "Initial saved progress reading is identified instead of appearing as a restarted migration");
        Component initialWorld = messages.stream().filter(message -> plain(message).startsWith("fixture-world:")).findFirst().orElseThrow();
        check(plain(initialWorld).equals("fixture-world: reading saved progress") && colored(initialWorld,"reading saved progress",NamedTextColor.GRAY),
                "Initial indexing displays a gray reading notice instead of an unverified chunk ratio");
        check(messages.stream().noneMatch(message -> plain(message).contains("%")), "Initial saved indexing does not display misleading zero progress or premature coverage percentages");
        state.cleanup=true; messages.clear(); command.onCommand(sender,null,"trueseasons",new String[]{"cleanup","status"});
        check(plain(messages.getFirst()).endsWith("CLEANUP"), "Cleanup remains the primary phase while its saved progress is indexed");
        state.cleanup=false; state.clean=true; messages.clear(); command.onCommand(sender,null,"trueseasons",new String[]{"status"});
        check(plain(messages.getFirst()).endsWith("RESTORED"), "Restored mode remains the primary phase while its saved progress is indexed");
        state.clean=false; set(job,"scanned",true);

        set(job,"done",new HashSet<>(Set.of(done,pending))); set(job,"nextStep",new HashMap<>()); set(job,"failures",new HashMap<>());
        set(job,"completed",true); set(job,"scanning",true);
        messages.clear(); command.onCommand(sender,null,"trueseasons",new String[]{"status"});
        check(plain(messages.getFirst()).endsWith("WORLD COMPLETE — MAP UPDATES PENDING"), "A completed world's periodic rescan does not make its header look like a new migration");
        check(messages.stream().anyMatch(message -> plain(message).startsWith("World edits are complete")), "Map-pending feedback still explains completion during a periodic rescan");
        Component rescanningWorld=messages.stream().filter(message -> plain(message).startsWith("fixture-world:")).findFirst().orElseThrow();
        check(colored(rescanningWorld,"2 / 2 chunks (100.0%)",NamedTextColor.AQUA) && !plain(rescanningWorld).contains("indexing"),
                "An unchanged completed-world row keeps100 percent without a distracting indexing suffix");
        check(!engine.allFinished(), "The status presentation does not change strict internal completion during a scan");
        set(job,"done",new HashSet<>(Set.of(done))); set(job,"nextStep",new HashMap<>(Map.of(pending,20)));
        set(job,"failures",new HashMap<>(Map.of(pending,30L))); set(job,"completed",false); set(job,"scanning",false);

        Path confirmationDirectory=Files.createTempDirectory(Path.of("").toAbsolutePath(),"trueseasons-command-state-");
        try {
            set(state,"file",confirmationDirectory.resolve("jobs.yml"));
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
            }
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
