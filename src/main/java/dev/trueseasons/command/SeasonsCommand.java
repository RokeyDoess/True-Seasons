package dev.trueseasons.command;

import dev.trueseasons.TrueSeasonsPlugin;
import dev.trueseasons.api.Season;
import dev.trueseasons.notification.MigrationProgressNotifications;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;

public final class SeasonsCommand implements CommandExecutor, TabCompleter, Listener {
    private final TrueSeasonsPlugin plugin;
    private final Map<String, Long> confirmations = new HashMap<>();
    private final MigrationProgressNotifications notifications;
    public SeasonsCommand(TrueSeasonsPlugin plugin) {
        this.plugin = plugin;
        notifications=new MigrationProgressNotifications(Bukkit::getPlayer,()->new CommandOutput(plugin).progressUpdate(),
            ()->plugin.engine().worldEditsComplete()&&plugin.integrations().pending()==0);
    }
    public void tick(int tick){notifications.tick(tick);}
    @EventHandler(priority=EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event){notifications.remove(event.getPlayer().getUniqueId());}
    private static Component label(String text) { return Component.text(text, NamedTextColor.GRAY); }
    private static Component value(Object text, NamedTextColor color) { return Component.text(String.valueOf(text), color); }
    private static NamedTextColor seasonColor(Season season) {
        return switch (season) {
            case SPRING -> NamedTextColor.GREEN; case SUMMER -> NamedTextColor.YELLOW;
            case AUTUMN -> NamedTextColor.GOLD; case WINTER -> NamedTextColor.AQUA;
        };
    }
    private static void usage(CommandSender sender, String text) {
        sender.sendMessage(label("Usage: ").append(value(text, NamedTextColor.GOLD)));
    }
    private boolean permitted(CommandSender sender, String node) {
        if (sender.hasPermission("trueseasons.command." + node)) return true;
        sender.sendMessage(value("You lack permission: ", NamedTextColor.RED)
                .append(value("trueseasons.command." + node, NamedTextColor.YELLOW))); return false;
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        String node = switch (action) {
            case "info" -> "info";
            case "status", "migration" -> "migration";
            case "season" -> "season";
            case "cleanup", "uninstall" -> "cleanup";
            default -> "admin";
        };
        if (!permitted(sender, node)) return true;
        try {
            switch (action) {
                case "info" -> sender.sendMessage(value("True Seasons ", NamedTextColor.GOLD)
                        .append(value(plugin.getPluginMeta().getVersion(), NamedTextColor.AQUA)).append(label(" | "))
                        .append(value(plugin.currentSeason(), seasonColor(plugin.currentSeason()))).append(label(" | "))
                        .append(plugin.state().override == null ? label("Calendar: ").append(value(plugin.settings().calendar.zone(), NamedTextColor.AQUA))
                                : value("Manual override", NamedTextColor.YELLOW)));
                case "status" -> { if(args.length==1)new CommandOutput(plugin).overview(sender);else usage(sender,"/ts status"); }
                case "reload" -> { plugin.reloadSettings(); sender.sendMessage(value("Configuration validated and reloaded.", NamedTextColor.GREEN)); }
                case "season" -> {
                    if (args.length == 2 && args[1].equalsIgnoreCase("sync")) {
                        plugin.setSeason(null); sender.sendMessage(value("Season synchronized to calendar: ", NamedTextColor.GREEN)
                                .append(value(plugin.currentSeason(), seasonColor(plugin.currentSeason()))));
                    } else if (args.length == 3 && args[1].equalsIgnoreCase("set")) {
                        plugin.setSeason(Season.valueOf(args[2].toUpperCase(Locale.ROOT))); sender.sendMessage(value("Season set to ", NamedTextColor.GREEN)
                                .append(value(plugin.currentSeason(), seasonColor(plugin.currentSeason()))));
                    } else usage(sender, "/trueseasons season set <spring|summer|autumn|winter> or season sync");
                }
                case "migration" -> {
                    if(args.length==2&&args[1].equalsIgnoreCase("notify")){
                        if(!(sender instanceof Player player))sender.sendMessage(value("Live migration updates are available to players in game.",NamedTextColor.YELLOW));
                        else {
                            boolean enabled=notifications.toggle(player.getUniqueId(),Bukkit.getCurrentTick());
                            sender.sendMessage(value("Migration updates ",NamedTextColor.GOLD)
                                .append(value(enabled?"enabled":"disabled",enabled?NamedTextColor.GREEN:NamedTextColor.YELLOW))
                                .append(label(enabled?" · every 5 seconds; run /ts migration notify to stop.":".")));
                        }
                    } else if (args.length == 2 && List.of("pause", "resume").contains(args[1].toLowerCase(Locale.ROOT))) {
                        plugin.state().paused = args[1].equalsIgnoreCase("pause"); plugin.persist();
                        sender.sendMessage(value("Migration status", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
                                .append(label(" | ")).append(value(plugin.state().paused ? "paused" : "resumed",
                                plugin.state().paused ? NamedTextColor.YELLOW : NamedTextColor.GREEN)));
                    } else if(args.length==1||args.length==2&&List.of("status","details").contains(args[1].toLowerCase(Locale.ROOT)))
                        new CommandOutput(plugin).migration(sender,args.length==2&&args[1].equalsIgnoreCase("details"),false);
                    else usage(sender,"/ts migration [status|details|notify|pause|resume]");
                }
                case "cleanup", "uninstall" -> {
                    if (plugin.state().cleanup || args.length == 2 && args[1].equalsIgnoreCase("status")) { new CommandOutput(plugin).migration(sender,false,true); break; }
                    String key = sender instanceof org.bukkit.entity.Player player ? player.getUniqueId().toString() : sender.getName();
                    Long deadline = confirmations.remove(key);
                    if (args.length == 2 && args[1].equalsIgnoreCase("confirm") && deadline != null && deadline >= System.currentTimeMillis()) {
                        plugin.startCleanup(); sender.sendMessage(value("Full-world cleanup started; seasonal behavior is suspended.", NamedTextColor.YELLOW));
                    } else {
                        confirmations.put(key, System.currentTimeMillis() + 60_000);
                        sender.sendMessage(label("This restores all tracked seasonal edits and suspends normal seasonal behavior. Run ")
                                .append(value("/trueseasons cleanup confirm", NamedTextColor.GOLD))
                                .append(label(" within ")).append(value("60 seconds", NamedTextColor.YELLOW)).append(label(" to begin.")));
                    }
                }
                default -> usage(sender, "/trueseasons info|status|reload|season|migration|cleanup");
            }
        } catch (Exception failure) { sender.sendMessage(value("Command failed: ", NamedTextColor.RED)
                .append(value(Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()), NamedTextColor.RED))); }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> values = List.of();
        if (args.length == 1) values = List.of("info", "status", "reload", "season", "migration", "cleanup");
        else if (args.length == 2) values = switch (args[0].toLowerCase(Locale.ROOT)) {
            case "season" -> List.of("set", "sync"); case "migration" -> List.of("status", "details", "notify", "pause", "resume");
            case "cleanup", "uninstall" -> List.of("status", "confirm"); default -> List.of();
        };
        else if (args.length == 3 && args[0].equalsIgnoreCase("season") && args[1].equalsIgnoreCase("set"))
            values = List.of("spring", "summer", "autumn", "winter");
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(prefix)).toList();
    }
}
