package com.github.donsimm.serverstats;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Merkt sich die Beitrittszeit und erzeugt den Inhalt von online.json. */
final class OnlineTracker implements Listener {
    private final Map<UUID, Long> joined = new ConcurrentHashMap<>();

    void seedExisting() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            joined.putIfAbsent(player.getUniqueId(), now);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        joined.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        joined.remove(event.getPlayer().getUniqueId());
    }

    /** Muss im Hauptthread aufgerufen werden. */
    JsonObject snapshot(Hooks hooks, Settings settings, OptOutStore optOut, boolean essentialsAfk) {
        long now = System.currentTimeMillis();
        JsonArray players = new JsonArray();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (optOut.contains(id) || settings.excludeUuids.contains(id)) {
                continue;
            }
            if (settings.hideVanished && hooks.vanished(player)) {
                continue;
            }
            long since = joined.computeIfAbsent(id, k -> now);
            JsonObject o = new JsonObject();
            o.addProperty("uuid", id.toString());
            o.addProperty("name", player.getName());
            o.addProperty("dim", dimension(player, settings));
            o.addProperty("sessionMin", Math.max(0, (now - since) / 60000));
            o.addProperty("ping", player.getPing());
            o.addProperty("afk", essentialsAfk && hooks.afk(player));
            players.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("generated", now / 1000);
        root.add("players", players);
        return root;
    }

    /** Weltname mit Symbol aus der config.yml (worlds:), sonst der Weltname selbst. */
    private static String dimension(Player player, Settings settings) {
        String name = player.getWorld().getName();
        String symbol = settings.worldSymbols.get(name.toLowerCase(java.util.Locale.ROOT));
        return symbol == null || symbol.isEmpty() ? name : symbol + " " + name;
    }
}
