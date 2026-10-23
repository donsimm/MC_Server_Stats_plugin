package com.github.donsimm.serverstats;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Liest uSkyBlock rein lesend. Wer eine Insel hat, ergibt sich aus zwei Quellen, die sich ergaenzen:
 * islands/&lt;x,z&gt;.yml (general.level, party.leader-uuid, party.members.&lt;uuid&gt;) und
 * players/&lt;uuid&gt;.yml (player.islandX/Z, 0 = keine Insel). Challenges kommen aus data/challenge-progress.db
 * (Tabelle challenge_progress: island_key, challenge_id, times_completed), der Schluessel ist "x,z".
 */
final class SkyblockReader {
    /** Werte einer Insel, jedem Mitglied zugeordnet. */
    record Info(double level, int challenges, int completions, int partySize) {
    }

    /** Ergebnis samt Zaehlern fuer die Fehlersuche. */
    record Result(Map<UUID, Info> players, int islandFiles, int playerFiles, int islands, int challengeIslands) {
    }

    private SkyblockReader() {
    }

    private static final class Island {
        double level;
        int partySize;
        final Set<UUID> members = new HashSet<>();
    }

    static Result read(Path pluginDir, Logger log) {
        Map<String, int[]> challenges = readChallenges(pluginDir.resolve("data").resolve("challenge-progress.db"), log);
        Map<String, Island> islands = new HashMap<>();

        int islandFiles = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(pluginDir.resolve("islands"), "*.yml")) {
            for (Path file : files) {
                islandFiles++;
                String name = file.getFileName().toString();
                String key = name.substring(0, name.length() - 4);
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
                Island island = islands.computeIfAbsent(key, k -> new Island());
                island.level = yaml.getDouble("general.level", 0);
                island.partySize = yaml.getInt("party.currentSize", 0);
                addUuid(island.members, yaml.getString("party.leader-uuid"));
                ConfigurationSection section = yaml.getConfigurationSection("party.members");
                if (section != null) {
                    for (String member : section.getKeys(false)) {
                        addUuid(island.members, member);
                    }
                }
            }
        } catch (IOException e) {
            log.warning("uSkyBlock: Insel-Ordner nicht lesbar: " + e.getMessage());
        }

        // Spielerdateien: islandX/islandZ ist der Inselschluessel, islandY 0 bedeutet "keine Insel"
        int playerFiles = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(pluginDir.resolve("players"), "*.yml")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                UUID uuid;
                try {
                    uuid = UUID.fromString(name.substring(0, name.length() - 4));
                } catch (IllegalArgumentException e) {
                    continue; // aeltere Dateien nach Spielername
                }
                playerFiles++;
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
                if (yaml.getInt("player.islandY", 0) == 0) {
                    continue;
                }
                String key = yaml.getInt("player.islandX") + "," + yaml.getInt("player.islandZ");
                islands.computeIfAbsent(key, k -> new Island()).members.add(uuid);
            }
        } catch (IOException e) {
            // Ordner fehlt: dann zaehlen nur die Insel-Dateien
        }

        Map<UUID, Info> result = new HashMap<>();
        int withChallenges = 0;
        for (Map.Entry<String, Island> entry : islands.entrySet()) {
            Island island = entry.getValue();
            if (island.members.isEmpty()) {
                continue; // verlassene Insel
            }
            int[] c = challenges.get(entry.getKey());
            if (c != null) {
                withChallenges++;
            }
            Info info = new Info(island.level, c == null ? 0 : c[0], c == null ? 0 : c[1], Math.max(island.members.size(), island.partySize));
            for (UUID uuid : island.members) {
                result.put(uuid, info);
            }
        }
        return new Result(result, islandFiles, playerFiles, islands.size(), withChallenges);
    }

    private static void addUuid(Set<UUID> set, String text) {
        if (text == null) {
            return;
        }
        try {
            set.add(UUID.fromString(text));
        } catch (IllegalArgumentException ignored) {
            // kein UUID-Eintrag
        }
    }

    /** Inselschluessel -&gt; {verschiedene Challenges, Abschluesse gesamt}. */
    private static Map<String, int[]> readChallenges(Path db, Logger log) {
        Map<String, int[]> result = new HashMap<>();
        if (!Files.isReadable(db)) {
            return result;
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            log.warning("uSkyBlock: SQLite-Treiber nicht verfuegbar, Challenges fehlen.");
            return result;
        }
        Properties props = new Properties();
        props.setProperty("open_mode", "1"); // nur lesen
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath(), props);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT island_key, "
                     + "SUM(CASE WHEN times_completed > 0 THEN 1 ELSE 0 END) AS verschieden, SUM(times_completed) AS gesamt "
                     + "FROM challenge_progress GROUP BY island_key")) {
            while (rs.next()) {
                result.put(rs.getString("island_key"), new int[]{rs.getInt("verschieden"), rs.getInt("gesamt")});
            }
        } catch (SQLException e) {
            log.warning("uSkyBlock: Challenge-Datenbank nicht lesbar: " + e.getMessage());
        }
        return result;
    }
}
