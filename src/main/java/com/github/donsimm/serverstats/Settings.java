package com.github.donsimm.serverstats;

import com.github.donsimm.serverstats.core.CategoryDef;
import com.github.donsimm.serverstats.core.StatsBuilder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/** Die config.yml als einfache Felder. */
final class Settings {
    final String title;
    final String subtitle;
    final int refreshSeconds;
    final int onlineSeconds;
    final Path outputDir;
    final boolean overwriteWebFiles;

    final boolean httpEnabled;
    final String httpBind;
    final int httpPort;
    final String publicUrl;

    final String statsDirOverride;
    final String advancementsDirOverride;
    final Set<UUID> excludeUuids = new HashSet<>();
    final Set<String> excludeNames = new HashSet<>();
    final int maxInactiveDays;
    final boolean hideVanished;

    final boolean headsEnabled;
    final int headsRefreshDays;

    final boolean mcmmoEnabled;
    final int mcmmoRadarMax;
    final boolean vaultEnabled;
    final Set<String> vaultExcludeNames = new HashSet<>();
    final boolean essentialsEnabled;

    final boolean cookieEnabled;
    final boolean cookieMysql;
    final String cookieSqliteFile;
    final String cookieMysqlHost;
    final int cookieMysqlPort;
    final String cookieMysqlDatabase;
    final String cookieMysqlUser;
    final String cookieMysqlPassword;
    final String cookieTable;

    final boolean skyblockEnabled;
    final String skyblockFolder;
    final boolean oneblockEnabled;
    final String oneblockFile;

    final boolean parkourEnabled;
    final String parkourFolder;

    final boolean autoStats;
    final String autoPage;
    final int autoMinPlayers;

    final boolean museumEnabled;
    final String museumTitle;
    final List<String> museumNamespaces;
    final List<String> museumContains;

    /** Weltname (klein geschrieben) -> Symbol fuer die Live-Anzeige. */
    final Map<String, String> worldSymbols = new java.util.HashMap<>();

    final String homepageLabel;
    final String homepageUrl;
    final String discordUrl;

    final boolean jobsEnabled;
    final String jobsFolder;

    final boolean ajlbEnabled;
    final int ajlbTop;
    final String ajlbNamePlaceholder;
    final String ajlbValuePlaceholder;
    final Map<String, String> ajlbPeriodNames;

    final List<CategoryDef> categories = new ArrayList<>();
    final List<StatsBuilder.Chip> chips = new ArrayList<>();

    Settings(FileConfiguration c, Path pluginDir, Logger log) {
        title = c.getString("title", "Stats-Tafel");
        subtitle = c.getString("subtitle", "");
        refreshSeconds = Math.max(10, c.getInt("refresh-seconds", 300));
        onlineSeconds = Math.max(5, c.getInt("online-refresh-seconds", 30));
        outputDir = pluginDir.resolve(c.getString("output-dir", "web")).normalize();
        overwriteWebFiles = c.getBoolean("overwrite-web-files", true);

        httpEnabled = c.getBoolean("http.enabled", true);
        httpBind = c.getString("http.bind", "0.0.0.0");
        httpPort = c.getInt("http.port", 8123);
        publicUrl = c.getString("http.public-url", "http://localhost:" + httpPort);

        statsDirOverride = c.getString("players.stats-dir", "");
        advancementsDirOverride = c.getString("players.advancements-dir", "");
        for (String s : c.getStringList("players.exclude-uuids")) {
            try {
                excludeUuids.add(UUID.fromString(s.trim()));
            } catch (IllegalArgumentException e) {
                log.warning("exclude-uuids: ungueltige UUID '" + s + "'");
            }
        }
        for (String s : c.getStringList("players.exclude-names")) {
            excludeNames.add(s.toLowerCase(Locale.ROOT));
        }
        maxInactiveDays = c.getInt("players.max-inactive-days", 0);
        hideVanished = c.getBoolean("players.hide-vanished", true);

        headsEnabled = c.getBoolean("heads.enabled", true);
        headsRefreshDays = Math.max(1, c.getInt("heads.refresh-days", 7));

        mcmmoEnabled = c.getBoolean("sources.mcmmo.enabled", true);
        mcmmoRadarMax = Math.max(1, c.getInt("sources.mcmmo.radar-max", 1000));
        vaultEnabled = c.getBoolean("sources.vault.enabled", true);
        for (String s : c.getStringList("sources.vault.exclude-names")) {
            vaultExcludeNames.add(s.toLowerCase(Locale.ROOT));
        }
        essentialsEnabled = c.getBoolean("sources.essentials.enabled", true);

        cookieEnabled = c.getBoolean("sources.cookieclicker.enabled", true);
        cookieMysql = "mysql".equalsIgnoreCase(c.getString("sources.cookieclicker.type", "sqlite"));
        cookieSqliteFile = c.getString("sources.cookieclicker.sqlite-file", "CookieClickerZ/userData.db");
        cookieMysqlHost = c.getString("sources.cookieclicker.mysql.host", "localhost");
        cookieMysqlPort = c.getInt("sources.cookieclicker.mysql.port", 3306);
        cookieMysqlDatabase = c.getString("sources.cookieclicker.mysql.database", "cookieclicker");
        cookieMysqlUser = c.getString("sources.cookieclicker.mysql.user", "root");
        cookieMysqlPassword = c.getString("sources.cookieclicker.mysql.password", "");
        cookieTable = c.getString("sources.cookieclicker.players-table", "players");

        skyblockEnabled = c.getBoolean("sources.skyblock.enabled", true);
        skyblockFolder = c.getString("sources.skyblock.folder", "uSkyBlock");
        oneblockEnabled = c.getBoolean("sources.oneblock.enabled", true);
        oneblockFile = c.getString("sources.oneblock.file", "Oneblock/PlData.json");

        parkourEnabled = c.getBoolean("sources.parkour.enabled", true);
        parkourFolder = c.getString("sources.parkour.folder", "IP");

        autoStats = c.getBoolean("auto-stats.enabled", true);
        autoPage = c.getString("auto-stats.page", "Alle Statistiken");
        autoMinPlayers = Math.max(1, c.getInt("auto-stats.min-players", 1));

        museumEnabled = c.getBoolean("museum.enabled", true);
        museumTitle = c.getString("museum.title", "Museum");
        museumNamespaces = lower(c.getStringList("museum.namespaces"));
        museumContains = lower(c.getStringList("museum.contains"));

        ConfigurationSection worlds = c.getConfigurationSection("worlds");
        if (worlds != null) {
            for (String name : worlds.getKeys(false)) {
                // Farbcodes (§x, &x) und Leerzeichen entfernen, nur das Symbol bleibt
                worldSymbols.put(name.toLowerCase(Locale.ROOT), String.valueOf(worlds.get(name)).replaceAll("[§&][0-9a-fk-orA-FK-OR]", "").trim());
            }
        }

        homepageLabel = c.getString("links.homepage-label", "Homepage");
        homepageUrl = c.getString("links.homepage-url", "");
        discordUrl = c.getString("links.discord-url", "");

        jobsEnabled = c.getBoolean("sources.jobs.enabled", true);
        jobsFolder = c.getString("sources.jobs.folder", "AethelJobs/playerdata");

        ajlbEnabled = c.getBoolean("sources.ajlb.enabled", true);
        ajlbTop = Math.max(1, Math.min(100, c.getInt("sources.ajlb.top", 30)));
        ajlbNamePlaceholder = c.getString("sources.ajlb.name-placeholder", "%ajlb_lb_{board}_{pos}_name{suffix}%");
        ajlbValuePlaceholder = c.getString("sources.ajlb.value-placeholder", "%ajlb_lb_{board}_{pos}_value{suffix}%");
        ajlbPeriodNames = Map.of(
                "day", c.getString("sources.ajlb.period-names.day", "daily"),
                "week", c.getString("sources.ajlb.period-names.week", "weekly"),
                "month", c.getString("sources.ajlb.period-names.month", "monthly"),
                "year", c.getString("sources.ajlb.period-names.year", "yearly"));

        ConfigurationSection cats = c.getConfigurationSection("categories");
        if (cats != null) {
            for (String id : cats.getKeys(false)) {
                ConfigurationSection s = cats.getConfigurationSection(id);
                if (s == null) {
                    continue;
                }
                categories.add(new CategoryDef(
                        id,
                        s.getString("page", s.getString("section", "Ranglisten")),
                        s.getBoolean("highlight", false),
                        s.getString("title", id),
                        s.getString("subtitle", ""),
                        s.getString("icon", "star"),
                        s.getString("color", "#8fd0ff"),
                        s.getBoolean("night", false),
                        s.getString("format", "int"),
                        s.getString("source", "vanilla").toLowerCase(Locale.ROOT),
                        list(s, "stat"),
                        list(s, "sum"),
                        s.getString("group-sum", ""),
                        s.getDouble("scale", 1.0),
                        s.getString("column", ""),
                        s.getString("ajlb-board", ""),
                        s.getDouble("ajlb-scale", 1.0),
                        s.getBoolean("include-zero", false)));
            }
        }
        for (Map<?, ?> m : c.getMapList("chips")) {
            Object category = m.get("category");
            Object label = m.get("label");
            if (category != null) {
                chips.add(new StatsBuilder.Chip(String.valueOf(category), label == null ? String.valueOf(category) : String.valueOf(label)));
            }
        }
    }

    private static List<String> lower(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) {
            if (!s.isBlank()) {
                out.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static List<String> list(ConfigurationSection s, String key) {
        if (s.isList(key)) {
            return s.getStringList(key);
        }
        String single = s.getString(key);
        return single == null || single.isEmpty() ? List.of() : List.of(single);
    }
}
