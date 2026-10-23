package com.github.donsimm.serverstats;

import com.github.donsimm.serverstats.core.AdvancementsFile;
import com.github.donsimm.serverstats.core.AjlbEntry;
import com.github.donsimm.serverstats.core.AutoStats;
import com.github.donsimm.serverstats.core.CategoryDef;
import com.github.donsimm.serverstats.core.NumberParse;
import com.github.donsimm.serverstats.core.OneblockData;
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
    private record MuseumItem(String name, String category, String skinUrl) {
    }

    private static final class Snapshot {
        final List<PlayerData> players = new ArrayList<>();
        final List<CategoryDef> categories = new ArrayList<>();
        final Map<String, Map<String, List<AjlbEntry>>> ajlbPeriods = new HashMap<>();
        final Map<String, List<AjlbEntry>> ajlbAll = new HashMap<>();
        final Map<String, Integer> categoryMax = new HashMap<>();
        final List<String> notes = new ArrayList<>();
        Path statsDir;
        Path advDir;
        Path jobsDir;
        Path skyDir;
        Path oneFile;
        /** Museum: Advancement-ID -> Anzeigename (leer = Museum aus). */
        final Map<String, MuseumItem> museum = new java.util.LinkedHashMap<>();
        final Map<UUID, Set<String>> museumDone = new HashMap<>();
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

    void configure(Settings settings, HeadService heads) {
        this.settings = settings;
        this.heads = heads;
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
        Map<String, String> rootTitles = new HashMap<>();
        Map<String, Advancement> byId = new HashMap<>();
        for (Iterator<Advancement> it = Bukkit.advancementIterator(); it.hasNext(); ) {
            Advancement adv = it.next();
            byId.put(adv.getKey().getNamespace() + ":" + adv.getKey().getKey(), adv);
        }
        for (String ns : chosen) {
            for (String id : idsByNamespace.getOrDefault(ns, List.of())) {
                String path = id.substring(id.indexOf(':') + 1);
                if (path.equals("root") || path.endsWith("/root")) {
                    String title = displayTitle(byId.get(id));
                    if (title != null) {
                        rootTitles.put(id.substring(0, id.length() - "root".length()), title);
                    }
                }
            }
        }
        for (String ns : chosen) {
            for (String id : idsByNamespace.getOrDefault(ns, List.of())) {
                String path = id.substring(id.indexOf(':') + 1);
                String lowerPath = path.toLowerCase(Locale.ROOT);
                if (lowerPath.equals("root") || lowerPath.endsWith("/root") || lowerPath.startsWith("recipes/")) {
                    continue;
                }
                if (!s.museumContains.isEmpty() && s.museumContains.stream().noneMatch(lowerPath::contains)) {
                    continue;
                }
                int slash = path.lastIndexOf('/');
                String dirKey = id.substring(0, id.length() - (path.length() - (slash + 1)));
                String category = rootTitles.get(dirKey);
                if (category == null) {
                    category = slash < 0 ? "Weitere" : prettyName(path.substring(0, slash));
                }
                String title = displayTitle(byId.get(id));
                snap.museum.put(id, new MuseumItem(title != null ? title : prettyName(path), category, skinUrl(byId.get(id))));
            }
        }
        if (snap.museum.isEmpty()) {
            snap.notes.add("Museum: keine passenden Advancements gefunden. Namensraeume mit Advancements ausser minecraft: "
                    + (perNamespace.isEmpty() ? "keine" : perNamespace)
                    + ". Passenden Namensraum unter museum.namespaces in der config.yml eintragen.");
        }
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
                case "ajlb" -> ajlb && !c.ajlbBoard().isBlank();
                default -> false;
            };
            if (ok) {
                snap.categories.add(c);
            } else if (!c.source().equals("cookieclicker") || s.cookieEnabled) {
                snap.notes.add("Kategorie '" + c.id() + "' uebersprungen: Quelle '" + c.source() + "' nicht verfuegbar.");
            }
        }
        for (CategoryDef c : snap.categories) {
            if (c.source().equals("advancements")) {
                int count = 0;
                for (Iterator<Advancement> it = Bukkit.advancementIterator(); it.hasNext(); ) {
                    if (!it.next().getKey().getKey().startsWith("recipes/")) {
                        count++;
                    }
                }
                snap.categoryMax.put(c.id(), count);
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
        SkyblockReader.Result skyResult = snap.skyDir == null ? null : SkyblockReader.read(snap.skyDir, plugin.getLogger());
        Map<UUID, SkyblockReader.Info> skyData = skyResult == null ? null : skyResult.players();
        if (skyResult != null) {
            notes.add("uSkyBlock: " + skyResult.islandFiles() + " Insel-Dateien, " + skyResult.playerFiles() + " Spielerdateien, "
                    + skyResult.islands() + " Inseln, " + skyData.size() + " Spieler mit Insel, Challenge-Daten fuer "
                    + skyResult.challengeIslands() + " Inseln.");
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
                        Set<String> done = AdvancementsFile.doneIds(file);
                        done.retainAll(snap.museum.keySet());
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
                                    p.values.put(c.id(), (double) AdvancementsFile.countDone(file));
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
            json.add("museum", museum);
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
