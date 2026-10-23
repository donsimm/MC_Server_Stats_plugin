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
 * Liest uSkyBlock rein lesend: islands/&lt;x,z&gt;.yml (general.level, party.leader-uuid, party.members.&lt;uuid&gt;)
 * und data/challenge-progress.db (Tabelle challenge_progress: island_key, challenge_id, times_completed).
 */
final class SkyblockReader {
    /** Werte einer Insel, jedem Mitglied zugeordnet. */
    record Info(double level, int challenges, int completions, int partySize) {
    }

    private SkyblockReader() {
    }

    static Map<UUID, Info> read(Path pluginDir, Logger log) {
        Map<String, int[]> challenges = readChallenges(pluginDir.resolve("data").resolve("challenge-progress.db"), log);
        Map<UUID, Info> result = new HashMap<>();
        Path islands = pluginDir.resolve("islands");
        try (DirectoryStream<Path> files = Files.newDirectoryStream(islands, "*.yml")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String key = name.substring(0, name.length() - 4);
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
                Set<UUID> members = new HashSet<>();
                String leader = yaml.getString("party.leader-uuid");
                if (leader != null) {
                    addUuid(members, leader);
                }
                ConfigurationSection section = yaml.getConfigurationSection("party.members");
                if (section != null) {
                    for (String member : section.getKeys(false)) {
                        addUuid(members, member);
                    }
                }
                if (members.isEmpty()) {
                    continue; // verlassene Insel
                }
                int[] c = challenges.getOrDefault(key, new int[]{0, 0});
                Info info = new Info(yaml.getDouble("general.level", 0), c[0], c[1], Math.max(members.size(), yaml.getInt("party.currentSize", 0)));
                for (UUID uuid : members) {
                    result.put(uuid, info);
                }
            }
        } catch (IOException e) {
            log.warning("uSkyBlock: Insel-Ordner nicht lesbar: " + e.getMessage());
        }
        return result;
    }

    private static void addUuid(Set<UUID> set, String text) {
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
