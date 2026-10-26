package com.github.donsimm.serverstats;

import com.github.donsimm.serverstats.core.AjlbEntry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /serverstats [link|optout|refresh|status|ajlb|reload] */
final class StatsCommand implements CommandExecutor, TabCompleter {
    private static final String PREFIX = "§6[ServerStats]§r ";
    private final ServerStatsPlugin plugin;

    StatsCommand(ServerStatsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "link" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "link" -> {
                if (!sender.hasPermission("serverstats.use")) {
                    return noPermission(sender);
                }
                sender.sendMessage(PREFIX + "Die Stats-Seite: §b" + plugin.settings().publicUrl);
            }
            case "optout" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(PREFIX + "Nur für Spieler.");
                    return true;
                }
                if (!sender.hasPermission("serverstats.use")) {
                    return noPermission(sender);
                }
                boolean hidden = plugin.optOut().toggle(player.getUniqueId());
                sender.sendMessage(PREFIX + (hidden
                        ? "Du erscheinst jetzt nicht mehr auf der Stats-Seite (spätestens nach der nächsten Aktualisierung). Mit demselben Befehl wieder einblenden."
                        : "Du erscheinst wieder auf der Stats-Seite."));
                plugin.collector().refresh();
            }
            case "refresh" -> {
                if (!sender.hasPermission("serverstats.admin")) {
                    return noPermission(sender);
                }
                plugin.collector().refresh();
                sender.sendMessage(PREFIX + "Aktualisierung gestartet. Ergebnis mit /serverstats status.");
            }
            case "status" -> {
                if (!sender.hasPermission("serverstats.admin")) {
                    return noPermission(sender);
                }
                sender.sendMessage(PREFIX + "Status");
                for (Map.Entry<String, String> e : plugin.collector().diagnostics().entrySet()) {
                    sender.sendMessage("§7- " + e.getKey() + ": §f" + e.getValue());
                }
                sender.sendMessage("§7- Webserver: §f" + plugin.webStatus());
                sender.sendMessage("§7- Ausgabeordner: §f" + plugin.settings().outputDir);
                sender.sendMessage("§7- Koepfe: §f" + plugin.headStatus());
                sender.sendMessage("§7- Block-Texturen: §f" + plugin.blockStatus());
                for (String line : plugin.collector().statusLines()) {
                    sender.sendMessage("§7- §f" + line);
                }
            }
            case "ajlb" -> {
                if (!sender.hasPermission("serverstats.admin")) {
                    return noPermission(sender);
                }
                ajlbDiagnose(sender, args);
            }
            case "reload" -> {
                if (!sender.hasPermission("serverstats.admin")) {
                    return noPermission(sender);
                }
                plugin.reloadAll();
                sender.sendMessage(PREFIX + "Konfiguration neu geladen.");
            }
            default -> sender.sendMessage(PREFIX + "/serverstats [link|optout" + (sender.hasPermission("serverstats.admin")
                    ? "|refresh|status|ajlb <board> [day|week|month]|reload" : "") + "]");
        }
        return true;
    }

    private void ajlbDiagnose(CommandSender sender, String[] args) {
        Settings s = plugin.settings();
        if (args.length < 2) {
            sender.sendMessage(PREFIX + "/serverstats ajlb <board> [day|week|month]");
            return;
        }
        String board = args[1];
        String period = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "all";
        String suffix = period.equals("all") ? "" : "_" + s.ajlbPeriodNames.getOrDefault(period, period);
        Hooks hooks = plugin.hooks();
        if (!hooks.papi()) {
            sender.sendMessage(PREFIX + "§cPlaceholderAPI ist nicht installiert oder nicht ansprechbar.");
            return;
        }
        String nameTemplate = Collector.fill(s.ajlbNamePlaceholder, board, 1, suffix);
        String valueTemplate = Collector.fill(s.ajlbValuePlaceholder, board, 1, suffix);
        sender.sendMessage(PREFIX + "Platz 1 roh: " + nameTemplate + " -> '§f" + hooks.parsePlaceholders(nameTemplate) + "§r'");
        sender.sendMessage(PREFIX + "Wert roh: " + valueTemplate + " -> '§f" + hooks.parsePlaceholders(valueTemplate) + "§r'");
        List<AjlbEntry> entries = plugin.collector().queryAjlb(s, board, suffix);
        sender.sendMessage(PREFIX + entries.size() + " Eintraege erkannt" + (entries.isEmpty()
                ? ". Stimmt die Schreibweise der Platzhalter? Siehe sources.ajlb in der config.yml." : ":"));
        for (int i = 0; i < Math.min(5, entries.size()); i++) {
            sender.sendMessage("§7" + (i + 1) + ". §f" + entries.get(i).name() + " §7= §f" + entries.get(i).value());
        }
    }

    private boolean noPermission(CommandSender sender) {
        sender.sendMessage(PREFIX + "§cDazu fehlt dir die Berechtigung.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("link", "optout"));
            if (sender.hasPermission("serverstats.admin")) {
                subs.addAll(List.of("refresh", "status", "ajlb", "reload"));
            }
            for (String s : subs) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("ajlb")) {
            out.addAll(List.of("day", "week", "month"));
        }
        return out;
    }
}
