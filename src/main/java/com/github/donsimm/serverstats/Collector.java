package com.github.donsimm.serverstats;

import com.github.donsimm.serverstats.core.AdvancementsFile;
import com.github.donsimm.serverstats.core.AjlbEntry;
import com.github.donsimm.serverstats.core.AutoStats;
import com.github.donsimm.serverstats.core.HistoryStore;
import com.github.donsimm.serverstats.core.CategoryDef;
import com.github.donsimm.serverstats.core.NumberParse;
import com.github.donsimm.serverstats.core.OneblockData;
import com.github.donsimm.serverstats.core.PlacedBlocks;
import com.github.donsimm.serverstats.core.ParkourData;
import com.github.donsimm.serverstats.core.PlayerData;
import com.github.donsimm.serverstats.core.StatsBuilder;
import com.github.donsimm.serverstats.core.VanillaStats;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Sammelt alle Werte und schreibt stats.json.
 * Teil 1 (Hauptthread): Spielerliste, Vault, mcMMO, AJLeaderboards. Teil 2 (eigener Thread): Dateien lesen, JSON schreiben.
 */
final class Collector {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Ein Museumsstueck: Advancement mit Anzeigename, Kategorie (Reiter im Advancement-Menue) und Kopf-Textur. */
    private record MuseumItem(String name, String category, String sub, int depth, String skinUrl, boolean gold) {
    }

    private static final class Snapshot {
        final List<PlayerData> players = new ArrayList<>();
        final List<CategoryDef> categories = new ArrayList<>();
        final Map<String, Map<String, List<AjlbEntry>>> ajlbPeriods = new HashMap<>();
        final Map<String, List<AjlbEntry>> ajlbAll = new HashMap<>();
        final Map<String, Integer> categoryMax = new HashMap<>();
        /** Advancements, die im Spiel im Menue sichtbar sind (mit Anzeige, ohne Rezepte und versteckte Hilfs-Advancements). */
        final Set<String> shownAdvancements = new HashSet<>();
        final List<String> notes = new ArrayList<>();
        Path statsDir;
        Path advDir;
        Path jobsDir;
        Path skyDir;
        Path oneFile;
        Path parkourDir;
        /** Museum: Advancement-ID -> Anzeigename (leer = Museum aus). */
        final Map<String, MuseumItem> museum = new java.util.LinkedHashMap<>();
        final Map<UUID, Set<String>> museumDone = new HashMap<>();
        /** Goldene Koepfe: Eintrags-Schluessel -> Advancement der Kategorie, deren Abschluss sie belohnt. */
        final Map<String, String> museumGolden = new java.util.LinkedHashMap<>();
        /** Easter-Egg-Koepfe: {Name, Bildname, Textur-Adresse}. */
        final List<String[]> museumEggs = new ArrayList<>();
    }

    private final ServerStatsPlugin plugin;
    private final Hooks hooks;
    private final OptOutStore optOut;
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile Settings settings;
    private volatile HeadService heads;
    private volatile boolean verbose = true;

    private volatile long lastFinishedMs;
    private volatile long lastDurationMs;
    private volatile int lastPlayers;
    private volatile List<String> lastNotes = List.of();
    private final Set<String> loggedNotes = new LinkedHashSet<>();
    private final List<String> lastCategories = new CopyOnWriteArrayList<>();

    Collector(ServerStatsPlugin plugin, Hooks hooks, OptOutStore optOut) {
        this.plugin = plugin;
        this.hooks = hooks;
        this.optOut = optOut;
    }

    private volatile BlockTextureService blockTextures;
    private static final Map<String, Boolean> BLOCK_ITEMS = new java.util.concurrent.ConcurrentHashMap<>();

    String blockTextureSummary() {
        BlockTextureService service = blockTextures;
        return service == null ? "aus" : service.summary();
    }

    /** Ist der Gegenstand ein platzierbarer Block (nicht Luft)? */
    private static boolean isBlockItem(String key) {
        return BLOCK_ITEMS.computeIfAbsent(key, k -> {
            try {
                org.bukkit.Material m = org.bukkit.Material.matchMaterial("minecraft:" + k);
                return m != null && m.isBlock() && !m.isAir();
            } catch (RuntimeException e) {
                return false;
            }
        });
    }

    void configure(Settings settings, HeadService heads) {
        this.settings = settings;
        this.heads = heads;
        this.blockTextures = settings.blocksEnabled
                ? new BlockTextureService(settings.outputDir.resolve("blocks"), Bukkit.getMinecraftVersion(), plugin.getLogger()) : null;
        loggedNotes.clear();
        verbose = true;
    }

    int lastPlayers() {
        return lastPlayers;
    }

    List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        if (lastFinishedMs == 0) {
            lines.add("Noch kein Durchlauf abgeschlossen.");
        } else {
            lines.add("Letzter Durchlauf: vor " + (System.currentTimeMillis() - lastFinishedMs) / 1000 + " s, Dauer " + lastDurationMs
                    + " ms, " + lastPlayers + " Spieler");
            lines.add("Kategorien mit Daten: " + (lastCategories.isEmpty() ? "keine" : String.join(", ", lastCategories)));
        }
        for (String note : lastNotes) {
            lines.add("Hinweis: " + note);
        }
        return lines;
    }

    // ------------------------------------------------------------------ Teil 1: Hauptthread

    /** Im Hauptthread aufrufen. */
    void refresh() {
        Settings s = settings;
        if (s == null || !plugin.isEnabled() || !running.compareAndSet(false, true)) {
            return;
        }
        long started = System.currentTimeMillis();
        Snapshot snapshot;
        try {
            snapshot = gather(s);
        } catch (RuntimeException e) {
            running.set(false);
            plugin.getLogger().log(Level.SEVERE, "Statistiken konnten nicht gesammelt werden", e);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                finish(s, snapshot, started);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Statistiken konnten nicht geschrieben werden", t);
            } finally {
                running.set(false);
            }
        });
    }

    /** Der Weltordner der ersten Welt und seine uebergeordneten Ordner (ab Paper 26.x liegt er unter dimensions/...). */
    private static List<Path> worldRoots() {
        List<Path> roots = new ArrayList<>();
        Path path = Bukkit.getWorlds().get(0).getWorldFolder().toPath().toAbsolutePath().normalize();
        for (int i = 0; i < 6 && path != null; i++) {
            roots.add(path);
            path = path.getParent();
        }
        return roots;
    }

    private Path findDir(String override, String... candidates) {
        if (override != null && !override.isBlank()) {
            Path path = plugin.getDataFolder().toPath().toAbsolutePath().getParent().getParent().resolve(override).normalize();
            return Files.isDirectory(path) ? path : null;
        }
        for (Path root : worldRoots()) {
            for (String candidate : candidates) {
                Path path = root.resolve(candidate);
                if (Files.isDirectory(path)) {
                    return path;
                }
            }
        }
        return null;
    }

    private static String describeCandidates(String... candidates) {
        List<String> out = new ArrayList<>();
        for (Path root : worldRoots()) {
            for (String c : candidates) {
                out.add(root.resolve(c).toString());
            }
        }
        return String.join(" | ", out);
    }

    private Map<UUID, String> readUserCache() {
        Map<UUID, String> result = new HashMap<>();
        Path file = Bukkit.getWorldContainer().toPath().resolve("usercache.json");
        if (!Files.isRegularFile(file)) {
            return result;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (root.isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray()) {
                    JsonObject o = element.getAsJsonObject();
                    if (o.has("uuid") && o.has("name")) {
                        result.put(UUID.fromString(o.get("uuid").getAsString()), o.get("name").getAsString());
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Namen kommen dann nur aus der Bukkit-API
        }
        return result;
    }

    /** Museum: alle geladenen Advancements der gewaehlten Namensraeume (Datapack "All Mob Heads"). */
    private void gatherMuseum(Settings s, Snapshot snap) {
        Map<String, Integer> perNamespace = new java.util.TreeMap<>();
        Map<String, List<String>> idsByNamespace = new HashMap<>();
        for (Iterator<Advancement> it = Bukkit.advancementIterator(); it.hasNext(); ) {
            org.bukkit.NamespacedKey key = it.next().getKey();
            String ns = key.getNamespace().toLowerCase(Locale.ROOT);
            if (ns.equals("minecraft") || ns.equals("bukkit")) {
                continue;
            }
            perNamespace.merge(ns, 1, Integer::sum);
            idsByNamespace.computeIfAbsent(ns, k -> new ArrayList<>()).add(key.getNamespace() + ":" + key.getKey());
        }
        List<String> chosen = new ArrayList<>();
        if (!s.museumNamespaces.isEmpty()) {
            chosen.addAll(s.museumNamespaces);
        } else {
            for (String ns : perNamespace.keySet()) {
                if (ns.contains("head") || ns.contains("mob") || ns.contains("skull")) {
                    chosen.add(ns);
                }
            }
        }
        Map<String, Advancement> byId = new HashMap<>();
        for (Iterator<Advancement> it = Bukkit.advancementIterator(); it.hasNext(); ) {
            Advancement adv = it.next();
            byId.put(adv.getKey().getNamespace() + ":" + adv.getKey().getKey(), adv);
        }
        // Koepfe = angezeigte Advancements, die keine Kategorie sind (keine anderen Advancements darunter im Pfad).
        // Kategorie und Unterkategorie ergeben sich aus den Ordnern im Pfad, ihr Name aus dem Titel der Kategorie-Advancements.
        for (String ns : chosen) {
            List<String> ids = idsByNamespace.getOrDefault(ns, List.of());
            java.util.Set<String> paths = new java.util.HashSet<>();
            for (String id : ids) {
                paths.add(id.substring(id.indexOf(':') + 1));
            }
            for (String id : ids) {
                String path = id.substring(id.indexOf(':') + 1);
                String lowerPath = path.toLowerCase(Locale.ROOT);
                if (lowerPath.equals("root") || lowerPath.endsWith("/root") || lowerPath.startsWith("recipes/")) {
                    continue;
                }
                if (!s.museumContains.isEmpty() && s.museumContains.stream().noneMatch(lowerPath::contains)) {
                    continue;
                }
                Advancement adv = byId.get(id);
                if (adv == null || adv.getDisplay() == null) {
                    continue; // versteckte Hilfs-Advancements (z. B. "End line", "Kill baby")
                }
                String prefix = path.startsWith("collection/") ? "collection/" : "";
                String rest = path.substring(prefix.length());
                if (paths.stream().anyMatch(q -> q.startsWith(path + "/"))) {
                    continue; // Kategorie, kein Kopf
                }
                String[] dirs = rest.contains("/") ? rest.substring(0, rest.lastIndexOf('/')).split("/") : new String[0];
                String category = dirs.length > 0 ? dirTitle(byId, ns, prefix + dirs[0], dirs[0]) : "Sonstige";
                String sub = dirs.length > 1 ? dirTitle(byId, ns, prefix + dirs[0] + "/" + dirs[1], dirs[1]) : "";
                String title = displayTitle(adv);
                snap.museum.put(id, new MuseumItem(title != null ? title : prettyName(path), category, sub, depth(adv), skinUrl(adv), false));
            }
        }
        if (!snap.museum.isEmpty()) {
            loadMuseumExtras(snap, byId);
        }
        long noSkin = snap.museum.values().stream().filter(m -> m.skinUrl().isEmpty()).count();
        if (noSkin > 0) {
            snap.notes.add("Museum: " + noSkin + " von " + snap.museum.size() + " Advancements ohne Kopf-Textur im Symbol (kein Bild).");
        }
        if (snap.museum.isEmpty()) {
            snap.notes.add("Museum: keine passenden Advancements gefunden. Namensraeume mit Advancements ausser minecraft: "
                    + (perNamespace.isEmpty() ? "keine" : perNamespace)
                    + ". Passenden Namensraum unter museum.namespaces in der config.yml eintragen.");
        }
    }

    /** Goldene Koepfe und Easter Eggs aus museum-extra.json (aus dem Datapack V12.2 uebernommen). */
    private void loadMuseumExtras(Snapshot snap, Map<String, Advancement> byId) {
        try (java.io.InputStream in = plugin.getResource("museum-extra.json")) {
            if (in == null) {
                return;
            }
            JsonObject root = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            int n = 0;
            for (JsonElement e : root.getAsJsonArray("golden")) {
                JsonObject g = e.getAsJsonObject();
                String adv = g.get("advancement").getAsString();
                if (!byId.containsKey(adv)) {
                    continue; // Kategorie gibt es in dieser Datapack-Version nicht
                }
                String key = "golden:" + adv + "#" + n++;
                snap.museumGolden.put(key, adv);
                snap.museum.put(key, new MuseumItem(g.get("name").getAsString(), "Golden Heads", "", n,
                        "http://textures.minecraft.net/texture/" + g.get("texture").getAsString(), true));
            }
            for (JsonElement e : root.getAsJsonArray("eggs")) {
                JsonObject g = e.getAsJsonObject();
                String name = g.get("name").getAsString();
                snap.museumEggs.add(new String[]{name, imageName("egg:" + name), "http://textures.minecraft.net/texture/" + g.get("texture").getAsString()});
            }
        } catch (IOException | RuntimeException ex) {
            snap.notes.add("Museum: museum-extra.json nicht lesbar (" + ex.getMessage() + ").");
        }
    }

    private static String dirTitle(Map<String, Advancement> byId, String ns, String path, String fallback) {
        String title = displayTitle(byId.get(ns + ":" + path));
        return title != null ? title : prettyName(fallback);
    }

    /** Tiefe im Advancement-Baum (Reihenfolge wie im Spiel: jeder Kopf haengt am vorherigen). */
    private static int depth(Advancement adv) {
        int depth = 0;
        try {
            java.lang.reflect.Method parent = Advancement.class.getMethod("getParent");
            Object current = adv;
            while (depth < 200) {
                current = parent.invoke(current);
                if (current == null) {
                    break;
                }
                depth++;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
        return depth;
    }

    /** Titel aus der Advancement-Anzeige, oder null (kein Titel oder nur ein Uebersetzungsschluessel). */
    private static String displayTitle(Advancement adv) {
        try {
            if (adv == null || adv.getDisplay() == null) {
                return null;
            }
            String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(adv.getDisplay().title()).trim();
            return text.isEmpty() || !text.contains(" ") && text.contains(".") ? null : text;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Textur-Adresse des Kopfes, der als Symbol des Advancements dient, oder leer. */
    private static String skinUrl(Advancement adv) {
        try {
            if (adv == null || adv.getDisplay() == null) {
                return "";
            }
            org.bukkit.inventory.meta.ItemMeta meta = adv.getDisplay().icon().getItemMeta();
            if (meta instanceof org.bukkit.inventory.meta.SkullMeta skull && skull.getOwnerProfile() != null) {
                java.net.URL url = skull.getOwnerProfile().getTextures().getSkin();
                return url == null ? "" : url.toString();
            }
        } catch (RuntimeException | LinkageError e) {
            // Symbol ohne Kopf-Textur
        }
        return "";
    }

    private static String imageName(String id) {
        return "mob_" + id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    private static String prettyName(String path) {
        String last = path.substring(path.lastIndexOf('/') + 1).replace('_', ' ').replace('-', ' ').trim();
        return last.isEmpty() ? path : Character.toUpperCase(last.charAt(0)) + last.substring(1);
    }

    /**
     * Ranglisten-Datei von Infinite Parkour Reborn. stat = "block:<modus>" (IP/leaderboards) oder "elytra:<modus>"
     * (IP/elytra/leaderboards, sonst altes IEP/leaderboards), oder null wenn es die Datei (noch) nicht gibt.
     */
    private Path parkourFile(Path ipDir, CategoryDef c) {
        if (ipDir == null || c.stat().isEmpty() || !c.stat().get(0).contains(":")) {
            return null;
        }
        String[] key = c.stat().get(0).split(":", 2);
        String name = key[1].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 _-]", "") + ".json";
        List<Path> candidates = key[0].equals("elytra")
                ? List.of(ipDir.resolve("elytra/leaderboards").resolve(name), ipDir.resolveSibling("IEP").resolve("leaderboards").resolve(name))
                : List.of(ipDir.resolve("leaderboards").resolve(name));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private Snapshot gather(Settings s) {
        Snapshot snap = new Snapshot();
        long now = System.currentTimeMillis();

        snap.statsDir = findDir(s.statsDirOverride, "stats", "players/stats");
        snap.advDir = findDir(s.advancementsDirOverride, "advancements", "players/advancements");
        if (s.jobsEnabled) {
            snap.jobsDir = JobsReader.locate(s, plugin.getDataFolder().toPath().toAbsolutePath().getParent());
            if (snap.jobsDir == null) {
                snap.notes.add("AethelJobs: Ordner nicht gefunden (erwartet: plugins/" + s.jobsFolder + "), Job-Ranglisten fehlen.");
            }
        }
        if (snap.statsDir == null) {
            snap.notes.add("Kein Statistik-Ordner gefunden (players.stats-dir in der config.yml setzen). Gesucht: "
                    + describeCandidates("stats", "players/stats"));
        }

        Path pluginsDir = plugin.getDataFolder().toPath().toAbsolutePath().getParent();
        if (s.skyblockEnabled && Files.isDirectory(pluginsDir.resolve(s.skyblockFolder).resolve("islands"))) {
            snap.skyDir = pluginsDir.resolve(s.skyblockFolder);
        }
        if (s.parkourEnabled && Files.isDirectory(pluginsDir.resolve(s.parkourFolder))) {
            snap.parkourDir = pluginsDir.resolve(s.parkourFolder);
        }
        if (s.oneblockEnabled && Files.isRegularFile(pluginsDir.resolve(s.oneblockFile))) {
            snap.oneFile = pluginsDir.resolve(s.oneblockFile);
        }

        // Welche Kategorien haben eine verfuegbare Quelle?
        boolean vault = s.vaultEnabled && hooks.vault();
        boolean mcmmo = s.mcmmoEnabled && hooks.mcmmo();
        boolean ajlb = s.ajlbEnabled && hooks.papi() && hooks.ajlbInstalled();
        for (CategoryDef c : s.categories) {
            boolean ok = switch (c.source()) {
                case "vanilla" -> snap.statsDir != null;
                case "advancements" -> snap.advDir != null;
                case "vault" -> vault;
                case "mcmmo-power" -> mcmmo;
                case "cookieclicker" -> s.cookieEnabled;
                case "jobs" -> snap.jobsDir != null;
                case "skyblock" -> snap.skyDir != null;
                case "oneblock" -> snap.oneFile != null;
                case "survival", "placed" -> snap.statsDir != null;
                case "dailyrewards" -> s.dailyEnabled && new DailyRewardsReader(s, pluginsDir, plugin.getLogger()).file() != null;
                case "parkour" -> parkourFile(snap.parkourDir, c) != null;
                case "ajlb" -> ajlb && !c.ajlbBoard().isBlank();
                default -> false;
            };
            if (ok) {
                snap.categories.add(c);
            } else if ((!c.source().equals("cookieclicker") || s.cookieEnabled) && !c.source().equals("parkour") && !c.source().equals("dailyrewards") && !c.source().equals("survival")) {
                snap.notes.add("Kategorie '" + c.id() + "' uebersprungen: Quelle '" + c.source() + "' nicht verfuegbar.");
            }
        }
        for (CategoryDef c : snap.categories) {
            if (c.source().equals("advancements")) {
                if (snap.shownAdvancements.isEmpty()) {
                    for (Iterator<Advancement> it = Bukkit.advancementIterator(); it.hasNext(); ) {
                        Advancement adv = it.next();
                        if (!adv.getKey().getKey().startsWith("recipes/") && adv.getDisplay() != null) {
                            snap.shownAdvancements.add(adv.getKey().getNamespace() + ":" + adv.getKey().getKey());
                        }
                    }
                }
                snap.categoryMax.put(c.id(), snap.shownAdvancements.size());
            }
        }

        if (s.museumEnabled && snap.advDir != null) {
            gatherMuseum(s, snap);
        }

        // Spielerliste: alle Statistik-Dateien plus aktuell Online
        Set<UUID> ids = new LinkedHashSet<>();
        if (snap.statsDir != null) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(snap.statsDir, "*.json")) {
                for (Path file : stream) {
                    String name = file.getFileName().toString();
                    try {
                        ids.add(UUID.fromString(name.substring(0, name.length() - 5)));
                    } catch (IllegalArgumentException ignored) {
                        // keine Spieler-Datei
                    }
                }
            } catch (IOException e) {
                snap.notes.add("Statistik-Ordner nicht lesbar: " + e.getMessage());
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            ids.add(player.getUniqueId());
        }

        Map<UUID, String> userCache = readUserCache();
        int unnamed = 0;
        int mcmmoMissing = 0;
        for (UUID id : ids) {
            if (s.excludeUuids.contains(id) || optOut.contains(id)) {
                continue;
            }
            OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
            String name = offline.getName();
            if (name == null) {
                name = userCache.get(id);
            }
            if (name == null) {
                unnamed++;
                continue;
            }
            if (s.excludeNames.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            boolean online = offline.isOnline();
            long last = online ? now : offline.getLastPlayed();
            if (s.maxInactiveDays > 0 && !online && last > 0 && now - last > s.maxInactiveDays * 86_400_000L) {
                continue;
            }
            PlayerData p = new PlayerData(id);
            p.name = name;
            p.first = offline.getFirstPlayed();
            p.last = last;

            if (vault) {
                for (CategoryDef c : snap.categories) {
                    if (c.source().equals("vault") && !s.vaultExcludeNames.contains(name.toLowerCase(Locale.ROOT))) {
                        double balance = hooks.balance(offline);
                        if (!Double.isNaN(balance)) {
                            p.values.put(c.id(), balance);
                        }
                    }
                }
            }
            if (mcmmo) {
                Map<String, Integer> levels = hooks.mcmmoLevels(id);
                if (levels == null) {
                    mcmmoMissing++;
                } else {
                    p.mcmmoSkills = levels;
                    int power = hooks.mcmmoPower(id);
                    if (power <= 0) {
                        power = levels.values().stream().mapToInt(Integer::intValue).sum();
                    }
                    p.mcmmoPower = power;
                    for (CategoryDef c : snap.categories) {
                        if (c.source().equals("mcmmo-power")) {
                            p.values.put(c.id(), (double) power);
                        }
                    }
                }
            }
            snap.players.add(p);
        }
        if (mcmmoMissing > 0) {
            snap.notes.add(mcmmoMissing + " von " + snap.players.size() + " Spielern haben (noch) kein mcMMO-Profil. Das ist normal, wenn sie mcMMO nie benutzt haben.");
        }
        if (unnamed > 0) {
            snap.notes.add(unnamed + " Spieler ohne bekannten Namen uebersprungen (nie auf diesem Server gewesen?).");
        }

        if (ajlb) {
            for (CategoryDef c : snap.categories) {
                if (c.ajlbBoard().isBlank()) {
                    continue;
                }
                if (c.source().equals("ajlb")) {
                    List<AjlbEntry> all = queryAjlb(s, c.ajlbBoard(), "");
                    if (all.isEmpty()) {
                        snap.notes.add("AJLB-Board '" + c.ajlbBoard() + "' liefert keine Eintraege (mit /serverstats ajlb " + c.ajlbBoard() + " pruefen).");
                    }
                    snap.ajlbAll.put(c.id(), all);
                }
                Map<String, List<AjlbEntry>> periods = new HashMap<>();
                for (String period : StatsBuilder.PERIODS) {
                    List<AjlbEntry> entries = queryAjlb(s, c.ajlbBoard(), "_" + s.ajlbPeriodNames.get(period));
                    if (!entries.isEmpty()) {
                        periods.put(period, entries);
                    }
                }
                if (!periods.isEmpty()) {
                    snap.ajlbPeriods.put(c.id(), periods);
                }
            }
        }
        return snap;
    }

    /** Fragt die Top-N eines AJLB-Boards ueber PlaceholderAPI ab. Hauptthread. */
    List<AjlbEntry> queryAjlb(Settings s, String board, String suffix) {
        List<AjlbEntry> out = new ArrayList<>();
        for (int pos = 1; pos <= s.ajlbTop; pos++) {
            String name = clean(hooks.parsePlaceholders(fill(s.ajlbNamePlaceholder, board, pos, suffix)));
            if (name.isEmpty() || name.contains("%") || name.equals("---") || name.equalsIgnoreCase("N/A")) {
                break;
            }
            double value = NumberParse.parse(hooks.parsePlaceholders(fill(s.ajlbValuePlaceholder, board, pos, suffix)));
            if (!Double.isNaN(value)) {
                out.add(new AjlbEntry(name, value));
            }
        }
        return out;
    }

    static String fill(String template, String board, int pos, String suffix) {
        return template.replace("{board}", board).replace("{pos}", String.valueOf(pos)).replace("{suffix}", suffix);
    }

    private static String clean(String text) {
        return text == null ? "" : text.replaceAll("§.", "").replaceAll("&[0-9a-fk-orA-FK-OR]", "").trim();
    }

    // ------------------------------------------------------------------ Teil 2: eigener Thread

    private void finish(Settings s, Snapshot snap, long started) throws IOException {
        List<String> notes = new ArrayList<>(snap.notes);
        int readErrors = 0;

        // DailyRewards2 (Streaks)
        Map<UUID, DailyRewardsReader.Row> daily = null;
        if (snap.categories.stream().anyMatch(c -> c.source().equals("dailyrewards"))) {
            daily = new DailyRewardsReader(s, plugin.getDataFolder().toPath().getParent(), plugin.getLogger()).read();
            if (daily == null) {
                notes.add("DailyRewards-Datenbank nicht lesbar, Streak-Ranglisten fehlen (Details im Log).");
                snap.categories.removeIf(c -> c.source().equals("dailyrewards"));
            } else {
                notes.add("DailyRewards: " + daily.size() + " Spieler gelesen.");
            }
        }
        // CookieClicker-Datenbank
        Map<UUID, CookieClickerReader.Row> cookies = null;
        boolean cookieNeeded = snap.categories.stream().anyMatch(c -> c.source().equals("cookieclicker"));
        if (cookieNeeded) {
            cookies = new CookieClickerReader(s, plugin.getDataFolder().toPath().getParent(), plugin.getLogger()).read();
            if (cookies == null) {
                notes.add("CookieClickerZ-Datenbank nicht lesbar, Cookie-Ranglisten fehlen (Details im Log).");
                snap.categories.removeIf(c -> c.source().equals("cookieclicker"));
            }
        }

        Map<UUID, Map<String, double[]>> jobsData = snap.jobsDir == null ? null : JobsReader.read(snap.jobsDir);
        Map<String, Map<UUID, Long>> customStats = new HashMap<>();
        Map<UUID, Map<String, Long>> placedPerPlayer = new HashMap<>();
        SkyblockReader.Result skyResult = snap.skyDir == null ? null : SkyblockReader.read(snap.skyDir, plugin.getLogger());
        Map<UUID, SkyblockReader.Info> skyData = skyResult == null ? null : skyResult.players();
        if (skyResult != null) {
            notes.add("uSkyBlock: " + skyResult.islandFiles() + " Insel-Dateien, " + skyResult.playerFiles() + " Spielerdateien, "
                    + skyResult.islands() + " Inseln, " + skyData.size() + " Spieler mit Insel, Challenge-Daten fuer "
                    + skyResult.challengeIslands() + " Inseln.");
        }
        Map<String, Map<UUID, ParkourData.Entry>> parkourData = new HashMap<>();
        int parkourRecords = 0;
        for (CategoryDef c : snap.categories) {
            if (c.source().equals("parkour")) {
                Path file = parkourFile(snap.parkourDir, c);
                try {
                    Map<UUID, ParkourData.Entry> data = ParkourData.parse(Files.readString(file, StandardCharsets.UTF_8));
                    parkourData.put(c.id(), data);
                    parkourRecords += data.size();
                } catch (IOException | RuntimeException e) {
                    notes.add("Parkour: " + file.getFileName() + " nicht lesbar (" + e.getMessage() + "). Nutzt Infinite Parkour MySQL? Das wird nicht unterstuetzt.");
                }
            }
        }
        if (snap.parkourDir != null) {
            notes.add("Parkour: " + parkourData.size() + " Ranglisten-Dateien gelesen, " + parkourRecords + " Eintraege.");
        }
        Map<UUID, OneblockData.Island> oneData = null;
        if (snap.oneFile != null) {
            try {
                oneData = OneblockData.parse(Files.readString(snap.oneFile, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                notes.add("OneBlock: PlData.json nicht lesbar (" + e.getMessage() + "). Nutzt OneBlock H2 oder MySQL statt JSON? Das wird nicht unterstuetzt.");
            }
        }

        for (PlayerData p : snap.players) {
            p.jobs = jobsData == null ? null : jobsData.get(p.uuid);
            VanillaStats stats = null;
            if (snap.statsDir != null) {
                Path file = snap.statsDir.resolve(p.uuid + ".json");
                if (Files.isRegularFile(file)) {
                    try {
                        stats = VanillaStats.read(file);
                    } catch (IOException | RuntimeException e) {
                        readErrors++;
                    }
                }
            }
            if (s.blocksEnabled && stats != null) {
                // Platzierte Bloecke: Statistik "benutzt" nur fuer Gegenstaende, die Bloecke sind
                Map<String, Long> blocks = new HashMap<>();
                for (Map.Entry<String, Long> e : stats.group("used").entrySet()) {
                    String key = e.getKey().startsWith("minecraft:") ? e.getKey().substring("minecraft:".length()) : e.getKey();
                    if (e.getValue() > 0 && isBlockItem(key)) {
                        blocks.put(key, e.getValue());
                    }
                }
                if (!blocks.isEmpty()) {
                    placedPerPlayer.put(p.uuid, blocks);
                }
            }
            if (s.autoStats && stats != null) {
                for (Map.Entry<String, Long> e : stats.group("custom").entrySet()) {
                    String key = e.getKey().startsWith("minecraft:") ? e.getKey().substring("minecraft:".length()) : e.getKey();
                    customStats.computeIfAbsent(key, k -> new HashMap<>()).put(p.uuid, e.getValue());
                }
            }
            if (!snap.museum.isEmpty() && snap.advDir != null) {
                Path file = snap.advDir.resolve(p.uuid + ".json");
                if (Files.isRegularFile(file)) {
                    try {
                        Set<String> all = AdvancementsFile.doneIds(file);
                        Set<String> done = new java.util.HashSet<>(all);
                        done.retainAll(snap.museum.keySet());
                        snap.museumGolden.forEach((key, adv) -> {
                            if (all.contains(adv)) {
                                done.add(key);
                            }
                        });
                        snap.museumDone.put(p.uuid, done);
                    } catch (IOException | RuntimeException e) {
                        readErrors++;
                    }
                }
            }
            for (CategoryDef c : snap.categories) {
                switch (c.source()) {
                    case "vanilla" -> {
                        if (stats != null) {
                            double v = stats.value(c);
                            if (v > 0) {
                                p.values.put(c.id(), v);
                            }
                        }
                    }
                    case "advancements" -> {
                        if (snap.advDir != null) {
                            Path file = snap.advDir.resolve(p.uuid + ".json");
                            if (Files.isRegularFile(file)) {
                                try {
                                    Set<String> done = AdvancementsFile.doneIds(file);
                                    done.retainAll(snap.shownAdvancements);
                                    p.values.put(c.id(), (double) done.size());
                                } catch (IOException | RuntimeException e) {
                                    readErrors++;
                                }
                            }
                        }
                    }
                    case "jobs" -> {
                        if (p.jobs != null) {
                            double value = 0;
                            if (c.column().equals("*")) {
                                double xp = 0;
                                for (double[] job : p.jobs.values()) {
                                    value += job[0];
                                    xp += job[1];
                                }
                                value += Math.min(xp, 999_999) / 1_000_000;
                            } else {
                                for (Map.Entry<String, double[]> job : p.jobs.entrySet()) {
                                    if (job.getKey().equalsIgnoreCase(c.column())) {
                                        value = job.getValue()[0] + Math.min(job.getValue()[1], 999_999) / 1_000_000; // XP entscheidet bei gleichem Level
                                    }
                                }
                            }
                            if (value > 0) {
                                p.values.put(c.id(), value);
                            }
                        }
                    }
                    case "skyblock" -> {
                        SkyblockReader.Info info = skyData == null ? null : skyData.get(p.uuid);
                        if (info != null) {
                            double value = switch (c.column().toUpperCase(Locale.ROOT)) {
                                case "LEVEL" -> info.level();
                                case "CHALLENGES" -> info.challenges();
                                case "COMPLETIONS" -> info.completions();
                                case "PARTY" -> info.partySize() > 1 ? info.partySize() : 0;
                                default -> 0;
                            };
                            // Der Level darf 0 sein (frische Insel): so sieht man, wer schon eine Insel hat
                            if (value > 0 || c.column().equalsIgnoreCase("LEVEL")) {
                                p.values.put(c.id(), value);
                            }
                        }
                    }
                    case "parkour" -> {
                        ParkourData.Entry entry = parkourData.getOrDefault(c.id(), Map.of()).get(p.uuid);
                        if (entry != null) {
                            double value = c.column().equalsIgnoreCase("TIME") ? (entry.timeMs() > 0 ? ParkourData.TIME_BASE - entry.timeMs() : 0) : entry.score();
                            if (value > 0) {
                                p.values.put(c.id(), value);
                            }
                        }
                    }
                    case "placed" -> {
                        if (stats != null) {
                            long total = 0;
                            for (Map.Entry<String, Long> e : stats.group("used").entrySet()) {
                                String key = e.getKey().startsWith("minecraft:") ? e.getKey().substring("minecraft:".length()) : e.getKey();
                                if (e.getValue() > 0 && isBlockItem(key)) {
                                    total += e.getValue();
                                }
                            }
                            if (total > 0) {
                                p.values.put(c.id(), (double) total);
                            }
                        }
                    }
                    case "survival" -> {
                        if (stats != null) {
                            double play = Math.max(stats.get("custom:play_time"), stats.get("custom:play_one_minute")) / 72000.0; // Stunden
                            if (play >= s.survivalMinHours) {
                                double value = 0;
                                if (c.column().equalsIgnoreCase("TIME")) {
                                    long ticks = stats.get("custom:time_since_death");
                                    value = ticks > 0 ? ticks / 72000.0 : 0;
                                } else {
                                    // wenigste Tode zuerst, bei Gleichstand die laengere Spielzeit: (1000 - Tode) * 1e6 + Spielstunden
                                    long deaths = Math.min(999, Math.max(0, stats.get("custom:deaths")));
                                    value = (1000 - deaths) * 1_000_000d + Math.min(999_999, Math.round(play));
                                }
                                if (value > 0) {
                                    p.values.put(c.id(), value);
                                }
                            }
                        }
                    }
                    case "dailyrewards" -> {
                        DailyRewardsReader.Row row = daily == null ? null : daily.get(p.uuid);
                        if (row != null) {
                            double value = switch (c.column().toUpperCase(Locale.ROOT)) {
                                // Der Streak bleibt in der Datenbank stehen, bis der Spieler wieder abholt: zu alt = verfallen
                                case "CURRENT" -> System.currentTimeMillis() - row.lastClaimMs() > s.dailyStreakValidHours * 3_600_000d ? 0 : row.currentStreak();
                                case "LONGEST" -> row.longestStreak();
                                case "CLAIMS" -> row.totalClaims();
                                default -> 0;
                            };
                            if (value > 0) {
                                p.values.put(c.id(), value);
                            }
                        }
                    }
                    case "oneblock" -> {
                        OneblockData.Island island = oneData == null ? null : oneData.get(p.uuid);
                        if (island != null) {
                            double value = switch (c.column().toUpperCase(Locale.ROOT)) {
                                case "LEVEL" -> island.level() + Math.min(island.breaks(), 999_999) / 1_000_000.0; // Blöcke entscheiden bei gleichem Level
                                case "INVITED" -> island.invited();
                                default -> 0;
                            };
                            if (value > 0) {
                                p.values.put(c.id(), value);
                            }
                        }
                    }
                    case "cookieclicker" -> {
                        CookieClickerReader.Row row = cookies == null ? null : cookies.get(p.uuid);
                        if (row != null) {
                            switch (c.column().toUpperCase(Locale.ROOT)) {
                                case "COOKIES" -> p.values.put(c.id(), row.cookies());
                                case "PER_CLICK" -> p.values.put(c.id(), row.perClick());
                                case "CLICKS", "CLICKER_CLICKS" -> p.values.put(c.id(), (double) row.clicks());
                                case "PRESTIGE" -> p.values.put(c.id(), (double) row.prestige());
                                default -> {
                                }
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        if (readErrors > 0) {
            notes.add(readErrors + " Statistik-/Advancement-Dateien waren nicht lesbar.");
        }

        // AJLB-Gesamtwerte (nur Kategorien mit source: ajlb)
        Map<String, UUID> byName = new HashMap<>();
        for (PlayerData p : snap.players) {
            byName.put(p.name.toLowerCase(Locale.ROOT), p.uuid);
        }
        Map<UUID, PlayerData> byUuid = new HashMap<>();
        for (PlayerData p : snap.players) {
            byUuid.put(p.uuid, p);
        }
        for (Map.Entry<String, List<AjlbEntry>> e : snap.ajlbAll.entrySet()) {
            CategoryDef category = snap.categories.stream().filter(c -> c.id().equals(e.getKey())).findFirst().orElse(null);
            double factor = category == null ? 1.0 : category.ajlbScale();
            for (AjlbEntry entry : e.getValue()) {
                UUID uuid = byName.get(entry.name().toLowerCase(Locale.ROOT));
                if (uuid != null) {
                    byUuid.get(uuid).values.put(e.getKey(), entry.value() * factor);
                }
            }
        }

        if (s.autoStats && !customStats.isEmpty()) {
            // Zaehler, die schon eine eigene Rangliste haben, nicht doppelt anlegen
            java.util.Set<String> used = new java.util.HashSet<>();
            for (CategoryDef c : s.categories) {
                List<String> specs = new ArrayList<>(c.stat());
                specs.addAll(c.sum());
                for (String spec : specs) {
                    String key = spec.replace("minecraft:", "");
                    if (key.startsWith("custom:")) {
                        used.add(key.substring("custom:".length()));
                    }
                }
            }
            AutoStats.Result auto = AutoStats.build(customStats, used, s.autoPage, s.autoMinPlayers);
            snap.categories.addAll(auto.categories());
            for (PlayerData p : snap.players) {
                for (Map.Entry<String, Map<UUID, Double>> e : auto.values().entrySet()) {
                    Double v = e.getValue().get(p.uuid);
                    if (v != null) {
                        p.values.put(e.getKey(), v);
                    }
                }
            }
        }

        if (s.luckpermsEnabled) {
            int withRank = 0;
            for (PlayerData p : snap.players) {
                p.rank = hooks.primaryGroup(p.uuid);
                if (p.rank != null) {
                    withRank++;
                }
            }
            notes.add("LuckPerms: " + withRank + " von " + snap.players.size() + " Spielern mit Gruppe.");
        }
        JsonObject json = StatsBuilder.build(s.title, s.subtitle, System.currentTimeMillis() / 1000, snap.categories,
                snap.players, snap.ajlbPeriods, s.chips, snap.categoryMax, s.mcmmoRadarMax);
        // Ranglisten der Sammelseite fuer die Webseite kennzeichnen (erscheinen nicht als Kacheln im Profil)
        for (JsonElement element : json.getAsJsonArray("categories")) {
            JsonObject category = element.getAsJsonObject();
            if (s.autoPage.equals(category.get("page").getAsString())) {
                category.addProperty("auto", true);
            }
        }
        JsonObject links = new JsonObject();
        links.addProperty("homepageLabel", s.homepageLabel);
        links.addProperty("homepageUrl", safeUrl(s.homepageUrl));
        links.addProperty("discordUrl", safeUrl(s.discordUrl));
        json.add("links", links);
        applyHistoryWindows(snap, json, notes);
        for (JsonElement element : json.getAsJsonArray("categories")) {
            JsonObject category = element.getAsJsonObject();
            List<String> tags = s.categoryTags.get(category.get("id").getAsString());
            if (tags != null) {
                JsonArray array = new JsonArray();
                tags.forEach(array::add);
                category.add("tags", array);
            }
        }
        addPlacedBlocks(s, json, placedPerPlayer);
        if (s.luckpermsEnabled && !s.ranks.isEmpty()) {
            JsonObject ranks = new JsonObject();
            for (Map.Entry<String, String[]> e : s.ranks.entrySet()) {
                JsonObject r = new JsonObject();
                r.addProperty("label", e.getValue()[0]);
                r.addProperty("bg", e.getValue()[1]);
                r.addProperty("fg", e.getValue()[2]);
                ranks.add(e.getKey(), r);
            }
            json.add("ranks", ranks);
        }
        if (!snap.museum.isEmpty()) {
            Set<UUID> listed = new java.util.HashSet<>();
            for (PlayerData p : snap.players) {
                listed.add(p.uuid);
            }
            JsonObject museum = new JsonObject();
            museum.addProperty("title", s.museumTitle);
            JsonArray items = new JsonArray();
            HeadService museumHeads = heads;
            for (Map.Entry<String, MuseumItem> e : snap.museum.entrySet()) {
                JsonObject item = new JsonObject();
                item.addProperty("id", e.getKey());
                item.addProperty("name", e.getValue().name());
                item.addProperty("cat", e.getValue().category());
                item.addProperty("sub", e.getValue().sub());
                item.addProperty("ord", e.getValue().depth());
                if (e.getValue().gold()) {
                    item.addProperty("gold", true);
                }
                if (!e.getValue().skinUrl().isEmpty() && museumHeads != null) {
                    item.addProperty("img", imageName(e.getKey()));
                    museumHeads.enqueueSkin(imageName(e.getKey()), e.getValue().skinUrl());
                }
                JsonArray by = new JsonArray();
                for (Map.Entry<UUID, Set<String>> d : snap.museumDone.entrySet()) {
                    if (listed.contains(d.getKey()) && d.getValue().contains(e.getKey())) {
                        by.add(d.getKey().toString());
                    }
                }
                item.add("by", by);
                items.add(item);
            }
            museum.add("items", items);
            JsonArray eggs = new JsonArray();
            for (String[] egg : snap.museumEggs) {
                JsonObject o = new JsonObject();
                o.addProperty("name", egg[0]);
                if (museumHeads != null) {
                    o.addProperty("img", egg[1]);
                    museumHeads.enqueueSkin(egg[1], egg[2]);
                }
                eggs.add(o);
            }
            if (eggs.size() > 0) {
                museum.add("eggs", eggs);
            }
            json.add("museum", museum);
            // Kompakte Uebersicht fuer die Fehlersuche: je Kategorie Anzahl, Beispiel-IDs und -Namen
            JsonObject overview = new JsonObject();
            Map<String, List<Map.Entry<String, MuseumItem>>> byCategory = new java.util.TreeMap<>();
            for (Map.Entry<String, MuseumItem> e : snap.museum.entrySet()) {
                byCategory.computeIfAbsent(e.getValue().category(), k -> new ArrayList<>()).add(e);
            }
            for (Map.Entry<String, List<Map.Entry<String, MuseumItem>>> c : byCategory.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("anzahl", c.getValue().size());
                JsonArray sample = new JsonArray();
                for (Map.Entry<String, MuseumItem> e : c.getValue().subList(0, Math.min(6, c.getValue().size()))) {
                    sample.add(e.getValue().name() + "  [" + e.getKey() + "]");
                }
                o.add("beispiele", sample);
                overview.add(c.getKey(), o);
            }
            Files.createDirectories(s.outputDir);
            writeJson(s.outputDir.resolve("museum-overview.json"), overview);
        }
        Files.createDirectories(s.outputDir);
        writeJson(s.outputDir.resolve("stats.json"), json);

        HeadService headService = heads;
        if (headService != null) {
            List<UUID> listed = new ArrayList<>();
            for (String key : json.getAsJsonObject("players").keySet()) {
                listed.add(UUID.fromString(key));
            }
            headService.enqueue(listed);
        }

        List<String> categoryNames = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("categories")) {
            JsonObject category = element.getAsJsonObject();
            int entries = category.getAsJsonObject("boards").getAsJsonArray("all").size();
            JsonArray periods = category.getAsJsonArray("periods");
            categoryNames.add(category.get("id").getAsString() + "(" + entries + (periods.size() > 1 ? ", " + (periods.size() - 1) + " Zeitfenster" : "") + ")");
        }
        lastCategories.clear();
        lastCategories.addAll(categoryNames);
        lastPlayers = json.getAsJsonObject("players").size();
        lastNotes = notes;
        lastDurationMs = System.currentTimeMillis() - started;
        lastFinishedMs = System.currentTimeMillis();

        for (String note : notes) {
            if (loggedNotes.add(note)) {
                plugin.getLogger().warning(note);
            }
        }
        if (verbose) {
            plugin.getLogger().info("stats.json geschrieben: " + lastPlayers + " Spieler, Kategorien: " + String.join(", ", categoryNames)
                    + " (" + lastDurationMs + " ms)");
            verbose = false;
        }
    }

    /** Nur http(s)-Adressen werden an die Webseite weitergegeben. */
    private static String safeUrl(String url) {
        return url != null && (url.startsWith("https://") || url.startsWith("http://")) ? url.trim() : "";
    }

    /** Sucht ein eigenes Logo im Web-Ordner und, falls es dort fehlt, im Plugin-Ordner (falsch abgelegt). */
    private String logoStatus(Settings s) {
        String[] names = {"logo.png", "logo.jpg", "logo.jpeg", "logo.webp", "logo.gif"};
        for (String name : names) {
            Path file = s.outputDir.resolve(name);
            if (Files.isRegularFile(file)) {
                try {
                    return name + " gefunden (" + Files.size(file) / 1024 + " KB), wird im Seitenkopf angezeigt";
                } catch (IOException e) {
                    return name + " gefunden";
                }
            }
        }
        for (String name : names) {
            if (Files.isRegularFile(plugin.getDataFolder().toPath().resolve(name))) {
                return name + " liegt im falschen Ordner. Bitte nach " + s.outputDir + " verschieben";
            }
        }
        return "keine Datei gefunden. Erwartet: " + s.outputDir.resolve("logo.png");
    }

    static void writeJson(Path file, JsonObject json) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(json), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Baumeister-Modul: je Block die Top-Spieler, je Spieler die meistgesetzten Bloecke (klein gehalten). */
    private void addPlacedBlocks(Settings s, JsonObject json, Map<UUID, Map<String, Long>> placedPerPlayer) {
        if (!s.blocksEnabled || placedPerPlayer.isEmpty()) {
            return;
        }
        PlacedBlocks.Result result = PlacedBlocks.aggregate(placedPerPlayer, 40, 5, 20);
        JsonObject players = json.getAsJsonObject("players");
        JsonArray blocks = new JsonArray();
        List<String> ids = new ArrayList<>();
        for (PlacedBlocks.Block block : result.blocks()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", block.id());
            o.addProperty("total", block.total());
            JsonArray top = new JsonArray();
            for (Map.Entry<UUID, Long> e : block.top()) {
                if (players.has(e.getKey().toString())) {
                    JsonArray pair = new JsonArray();
                    pair.add(e.getKey().toString());
                    pair.add(e.getValue());
                    top.add(pair);
                }
            }
            o.add("top", top);
            blocks.add(o);
            ids.add(block.id());
        }
        JsonObject blockRoot = new JsonObject();
        blockRoot.add("list", blocks);
        json.add("blocks", blockRoot);
        for (Map.Entry<UUID, PlacedBlocks.Player> e : result.players().entrySet()) {
            JsonObject target = players.has(e.getKey().toString()) ? players.getAsJsonObject(e.getKey().toString()) : null;
            if (target == null) {
                continue;
            }
            JsonObject placed = new JsonObject();
            placed.addProperty("total", e.getValue().total());
            JsonArray top = new JsonArray();
            for (Map.Entry<String, Long> b : e.getValue().top()) {
                JsonArray pair = new JsonArray();
                pair.add(b.getKey());
                pair.add(b.getValue());
                top.add(pair);
            }
            placed.add("top", top);
            target.add("placed", placed);
        }
        BlockTextureService textures = blockTextures;
        if (textures != null) {
            textures.ensure(ids);
        }
    }

    private static final Map<String, Long> WINDOW_DAYS = new LinkedHashMap<>();

    static {
        WINDOW_DAYS.put("week", 7L);
        WINDOW_DAYS.put("month", 30L);
        WINDOW_DAYS.put("year", 365L);
    }

    /**
     * Zeitfenster aus dem eigenen Verlauf (history.json): Zuwachs seit vor 7 Tagen, einem Monat (30 Tage) und einem Jahr (365 Tage).
     * Gilt fuer Werte, die mit der Zeit wachsen. AJLeaderboards-Fenster (falls vorhanden) bleiben unveraendert.
     * Laeuft die Aufzeichnung noch nicht so lange, zaehlt der Zuwachs seit Beginn (windows.<zeitraum> = Startzeit).
     */
    private void applyHistoryWindows(Snapshot snap, JsonObject json, List<String> notes) {
        Path file = plugin.getDataFolder().toPath().resolve("history.json");
        HistoryStore store = new HistoryStore();
        try {
            if (Files.isRegularFile(file)) {
                store = HistoryStore.parse(Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            notes.add("Verlauf (history.json) nicht lesbar, wird neu begonnen: " + e.getMessage());
        }
        Set<String> skip = Set.of("skyblock", "oneblock", "parkour", "ajlb", "survival");
        Map<String, Map<String, Double>> current = new LinkedHashMap<>();
        Map<String, CategoryDef> eligible = new HashMap<>();
        for (CategoryDef c : snap.categories) {
            if (skip.contains(c.source()) || c.format().equals("timerev") || c.includeZero()
                    || (c.source().equals("dailyrewards") && !c.column().equalsIgnoreCase("CLAIMS"))) {
                continue;
            }
            Map<String, Double> values = new LinkedHashMap<>();
            for (PlayerData p : snap.players) {
                Double v = p.values.get(c.id());
                if (v != null && v > 0 && !v.isNaN()) {
                    values.put(p.uuid.toString(), v);
                }
            }
            current.put(c.id(), values);
            eligible.put(c.id(), c);
        }
        long now = System.currentTimeMillis() / 1000;
        // anfangs oefter aufnehmen, damit die Zeitfenster schnell erscheinen, spaeter alle 2 Stunden
        if (store.addIfDue(now, current, store.snapshots().size() < 4 ? 1800L : 2 * 3600L)) {
            store.prune(now);
            try {
                Files.writeString(file, store.toJson(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                notes.add("Verlauf (history.json) nicht schreibbar: " + e.getMessage());
            }
        }
        if (!store.snapshots().isEmpty()) {
            json.addProperty("historySince", store.snapshots().get(0).t());
        }
        for (JsonElement element : json.getAsJsonArray("categories")) {
            JsonObject category = element.getAsJsonObject();
            String id = category.get("id").getAsString();
            Map<String, Double> values = current.get(id);
            if (values == null) {
                continue;
            }
            JsonObject boards = category.getAsJsonObject("boards");
            JsonArray periods = category.getAsJsonArray("periods");
            JsonObject windows = new JsonObject();
            category.addProperty("windowable", true);
            for (Map.Entry<String, Long> w : WINDOW_DAYS.entrySet()) {
                if (boards.has(w.getKey())) {
                    continue; // AJLB hat dieses Fenster
                }
                HistoryStore.Snapshot base = store.baseline(now, w.getValue() * 86_400L);
                if (base == null || base.t() >= now - 600) {
                    continue; // noch keine Vergleichsaufnahme
                }
                List<Map.Entry<String, Double>> gain = HistoryStore.delta(values, base, id);
                if (gain.isEmpty()) {
                    continue;
                }
                JsonArray rows = new JsonArray();
                for (Map.Entry<String, Double> e : gain) {
                    JsonArray row = new JsonArray();
                    row.add(e.getKey());
                    row.add(StatsBuilder.number(e.getValue()));
                    rows.add(row);
                }
                boards.add(w.getKey(), rows);
                periods.add(w.getKey());
                if (base.t() > now - w.getValue() * 86_400L + 86_400L) {
                    windows.addProperty(w.getKey(), base.t()); // Aufzeichnung kuerzer als das Fenster
                }
            }
            if (windows.size() > 0) {
                category.add("windows", windows);
            }
        }
    }

    /** Fuer den Befehl /serverstats status: Wie viele Spieler und welche Ordner wurden gefunden? */
    Map<String, String> diagnostics() {
        Settings s = settings;
        Map<String, String> out = new LinkedHashMap<>();
        if (s == null) {
            return out;
        }
        Path stats = findDir(s.statsDirOverride, "stats", "players/stats");
        Path adv = findDir(s.advancementsDirOverride, "advancements", "players/advancements");
        out.put("Statistik-Ordner", stats == null ? "NICHT GEFUNDEN (" + describeCandidates("stats", "players/stats") + ")" : stats.toString());
        out.put("Advancement-Ordner", adv == null ? "NICHT GEFUNDEN (" + describeCandidates("advancements", "players/advancements") + ")" : adv.toString());
        out.put("Logo", logoStatus(s));
        out.put("Vault", s.vaultEnabled ? (hooks.vault() ? "aktiv" : "nicht verfuegbar") : "aus");
        out.put("mcMMO", s.mcmmoEnabled ? (hooks.mcmmo() ? "aktiv" : "nicht verfuegbar") : "aus");
        out.put("PlaceholderAPI/AJLB", s.ajlbEnabled ? (hooks.papi() && hooks.ajlbInstalled() ? "aktiv" : "nicht verfuegbar") : "aus");
        out.put("CookieClicker", s.cookieEnabled ? (s.cookieMysql ? "MySQL" : "SQLite " + s.cookieSqliteFile) : "aus");
        return out;
    }
}
