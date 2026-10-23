package com.github.donsimm.serverstats;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.logging.Level;

public final class ServerStatsPlugin extends JavaPlugin {
    private static final List<String> WEB_FILES = List.of(
            "index.html", "app.js", "style.css",
            "fonts/pixelify-latin.woff2", "fonts/pixelify-latin-ext.woff2",
            "fonts/atkinson-next-latin.woff2", "fonts/atkinson-next-latin-ext.woff2");

    private Settings settings;
    private OptOutStore optOut;
    private Hooks hooks;
    private OnlineTracker online;
    private Collector collector;
    private HeadService heads;
    private WebServer web;
    private BukkitTask statsTask;
    private BukkitTask onlineTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        optOut = new OptOutStore(getDataFolder().toPath().resolve("optout.txt"), getLogger());
        hooks = new Hooks(getLogger());
        online = new OnlineTracker();
        collector = new Collector(this, hooks, optOut);
        getServer().getPluginManager().registerEvents(online, this);

        StatsCommand command = new StatsCommand(this);
        PluginCommand pluginCommand = getCommand("serverstats");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }
        start();
    }

    @Override
    public void onDisable() {
        stop();
    }

    private void start() {
        settings = new Settings(getConfig(), getDataFolder().toPath(), getLogger());
        online.seedExisting();
        try {
            Files.createDirectories(settings.outputDir);
            copyWebFiles();
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Ausgabeordner " + settings.outputDir + " nicht beschreibbar", e);
        }
        heads = settings.headsEnabled ? new HeadService(settings.outputDir, settings.headsRefreshDays, getLogger()) : null;
        collector.configure(settings, heads);
        if (settings.httpEnabled) {
            web = WebServer.start(settings.httpBind, settings.httpPort, settings.outputDir, getLogger());
        }
        statsTask = Bukkit.getScheduler().runTaskTimer(this, collector::refresh, 100L, settings.refreshSeconds * 20L);
        onlineTask = Bukkit.getScheduler().runTaskTimer(this, this::writeOnline, 40L, settings.onlineSeconds * 20L);
    }

    private void stop() {
        if (statsTask != null) {
            statsTask.cancel();
            statsTask = null;
        }
        if (onlineTask != null) {
            onlineTask.cancel();
            onlineTask = null;
        }
        if (web != null) {
            web.stop();
            web = null;
        }
        if (heads != null) {
            heads.shutdown();
            heads = null;
        }
    }

    void reloadAll() {
        stop();
        reloadConfig();
        start();
    }

    private void copyWebFiles() throws IOException {
        for (String name : WEB_FILES) {
            Path target = settings.outputDir.resolve(name);
            if (Files.exists(target) && !settings.overwriteWebFiles) {
                continue;
            }
            try (InputStream in = getResource("web/" + name)) {
                if (in == null) {
                    getLogger().warning("Webdatei fehlt im Plugin-JAR: web/" + name);
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Hauptthread: Online-Liste einsammeln, danach asynchron schreiben. */
    private void writeOnline() {
        JsonObject json = online.snapshot(hooks, settings, optOut, settings.essentialsEnabled);
        if (heads != null) {
            List<java.util.UUID> ids = new java.util.ArrayList<>();
            for (var element : json.getAsJsonArray("players")) {
                ids.add(java.util.UUID.fromString(element.getAsJsonObject().get("uuid").getAsString()));
            }
            heads.enqueue(ids);
        }
        Path file = settings.outputDir.resolve("online.json");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Files.createDirectories(file.getParent());
                Collector.writeJson(file, json);
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "online.json konnte nicht geschrieben werden", e);
            }
        });
    }

    String headStatus() {
        return heads == null ? "aus (heads.enabled: false)" : heads.summary();
    }

    String webStatus() {
        if (!settings.httpEnabled) {
            return "aus (http.enabled: false), Ordner extern ausliefern";
        }
        return web == null ? "FEHLER beim Start (siehe Log)" : "laeuft auf Port " + settings.httpPort;
    }

    Settings settings() {
        return settings;
    }

    OptOutStore optOut() {
        return optOut;
    }

    Hooks hooks() {
        return hooks;
    }

    Collector collector() {
        return collector;
    }
}
