package dev.trueseasons.command;

import dev.trueseasons.TrueSeasonsPlugin;
import dev.trueseasons.api.Season;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.*;

public final class SeasonsCommand implements CommandExecutor, TabCompleter {
    private final TrueSeasonsPlugin plugin;
    private final Map<String, Long> confirmations = new HashMap<>();
    public SeasonsCommand(TrueSeasonsPlugin plugin) { this.plugin = plugin; }
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
                        .append(value(plugin.version(), NamedTextColor.GRAY)).append(label(" | "))
                        .append(plugin.state().override == null ? label("Calendar: ").append(value(plugin.settings().calendar.zone(), NamedTextColor.AQUA))
                                : value("Manual override", NamedTextColor.YELLOW)));
                case "status" -> status(sender);
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
                    if (args.length == 2 && List.of("pause", "resume").contains(args[1].toLowerCase(Locale.ROOT))) {
                        plugin.state().paused = args[1].equalsIgnoreCase("pause"); plugin.persist();
                        sender.sendMessage(value("Migration status", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
                                .append(label(" | ")).append(value(plugin.state().paused ? "paused" : "resumed",
                                plugin.state().paused ? NamedTextColor.YELLOW : NamedTextColor.GREEN)));
                    } else status(sender);
                }
                case "cleanup", "uninstall" -> {
                    if (plugin.state().cleanup || args.length == 2 && args[1].equalsIgnoreCase("status")) { status(sender); break; }
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
    private void status(CommandSender sender) {
        int pending = plugin.integrations().pending();
        boolean finished = plugin.engine().worldEditsComplete();
        boolean indexing = plugin.engine().jobs().stream().anyMatch(job -> job.indexingSavedProgress());
        String phase = plugin.state().cleanup ? "CLEANUP" : plugin.state().clean ? "RESTORED"
                : indexing ? "INDEXING SAVED PROGRESS" : finished ? (pending > 0 ? "WORLD COMPLETE — MAP UPDATES PENDING" : "COMPLETE") : "MIGRATION";
        NamedTextColor phaseColor = plugin.state().cleanup || pending > 0 && finished ? NamedTextColor.GOLD
                : plugin.state().clean || finished ? NamedTextColor.GREEN : indexing ? NamedTextColor.YELLOW : NamedTextColor.AQUA;
        sender.sendMessage(value("Migration status", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
                .append(label(" | ")).append(value(plugin.currentSeason(), seasonColor(plugin.currentSeason())))
                .append(label(" | ")).append(value(phase, phaseColor)));
        double tps = plugin.engine().currentTps();
        NamedTextColor tpsColor = tps < plugin.settings().minTps ? NamedTextColor.RED : tps >= 19 ? NamedTextColor.GREEN : NamedTextColor.YELLOW;
        sender.sendMessage(label("Migration paused: ").append(value(plugin.state().paused, plugin.state().paused ? NamedTextColor.YELLOW : NamedTextColor.GREEN))
                .append(label("; TPS: ")).append(value(String.format(Locale.ROOT, "%.2f", tps), tpsColor)));
        sender.sendMessage(label("Region-by-region migration; active winter storms: ").append(value(plugin.weather().activeStorms(), NamedTextColor.AQUA)));
        for (var job : plugin.engine().jobs()) {
            Component row = value(job.world.getName(), NamedTextColor.GOLD).append(label(": "));
            if (job.indexingSavedProgress()) row = row.append(label("reading saved progress"));
            else row = row.append(value(job.completed() + " / " + job.total() + " chunks (" + String.format(Locale.ROOT, "%.1f", job.percent()) + "%)", NamedTextColor.AQUA))
                    .append(job.scanning() && !job.reportedFinished() ? value(" indexing", NamedTextColor.YELLOW) : Component.empty())
                    .append(label("; deferred: ")).append(value(job.errors(), job.errors() > 0 ? NamedTextColor.RED : NamedTextColor.GREEN))
                    .append(label("; accumulating snow: ")).append(value(job.transitioning(), NamedTextColor.BLUE))
                    .append(label("; blocks removed this run: ")).append(value(job.removed(), NamedTextColor.AQUA))
                    .append(label("; active region: ")).append(value(job.activeRegion(), NamedTextColor.AQUA));
            sender.sendMessage(row);
            if (job.error() != null) sender.sendMessage(value("Index error: " + job.error(), NamedTextColor.RED));
        }
        if(plugin.currentSeason()==Season.WINTER&&!plugin.state().cleanup&&!plugin.state().clean)
            sender.sendMessage(label("Shared winter transition coverage: ")
                    .append(indexing ? label("reading saved progress")
                            : value(String.format(Locale.ROOT,"%.1f",plugin.engine().globalCoverageColumns()*100.0/256)+"%", NamedTextColor.AQUA)));
        for (var entry : plugin.state().managedWorlds.entrySet())
            if (org.bukkit.Bukkit.getWorld(UUID.fromString(entry.getKey())) == null)
                sender.sendMessage(value("Tracked world unavailable: ", NamedTextColor.YELLOW).append(value(entry.getValue(), NamedTextColor.GOLD))
                        .append(label("; load it to complete cleanup.")));
        int cleanupPending = plugin.integrations().cleanupPending();
        sender.sendMessage(label("BlueMap: ").append(value(plugin.integrations().status(), NamedTextColor.AQUA))
                .append(label("; pending regions: ")).append(value(pending, pending > 0 ? NamedTextColor.GOLD : NamedTextColor.GREEN))
                .append(label("; cleanup metadata pending: ")).append(value(cleanupPending, cleanupPending > 0 ? NamedTextColor.GOLD : NamedTextColor.GREEN)));
        if(finished&&pending>0)
            sender.sendMessage(value("World edits are complete", NamedTextColor.GREEN)
                    .append(label("; BlueMap is still updating saved map tiles. This does not restart world migration.")));
        boolean safe = plugin.state().clean && !plugin.state().cleanup && plugin.engine().allFinished() && plugin.weather().activeStorms() == 0
            && plugin.integrations().cleanupPending() == 0;
        sender.sendMessage(label("Plugin removal safe: ").append(value(safe ? "YES — stop the server normally before removing the jar" : "NO",
                safe ? NamedTextColor.GREEN : NamedTextColor.RED)));
        if (plugin.state().clean) sender.sendMessage(label("Use ").append(value("season sync", NamedTextColor.GOLD)).append(label(" or "))
                .append(value("season set", NamedTextColor.GOLD)).append(label(" to re-enable seasonal behavior.")));
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> values = List.of();
        if (args.length == 1) values = List.of("info", "status", "reload", "season", "migration", "cleanup");
        else if (args.length == 2) values = switch (args[0].toLowerCase(Locale.ROOT)) {
            case "season" -> List.of("set", "sync"); case "migration" -> List.of("status", "pause", "resume");
            case "cleanup", "uninstall" -> List.of("status", "confirm"); default -> List.of();
        };
        else if (args.length == 3 && args[0].equalsIgnoreCase("season") && args[1].equalsIgnoreCase("set"))
            values = List.of("spring", "summer", "autumn", "winter");
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(prefix)).toList();
    }
}
