package com.github.donsimm.serverstats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Liest die Streaks des Plugins DailyRewards2 rein lesend aus plugins/DailyRewards2/dailyrewards.db (SQLite).
 * Tabelle player_data: uuid, username, currentStreak, lastClaimTime (ms), totalClaims, longestStreak, lastKnownIp, temporaryGrace.
 * Die IP-Adresse wird nicht gelesen.
 */
final class DailyRewardsReader {
    record Row(int currentStreak, int longestStreak, int totalClaims, long lastClaimMs) {
    }

    private final Settings settings;
    private final Path pluginsDir;
    private final Logger log;

    DailyRewardsReader(Settings settings, Path pluginsDir, Logger log) {
        this.settings = settings;
        this.pluginsDir = pluginsDir;
        this.log = log;
    }

    Path file() {
        Path file = pluginsDir.resolve(settings.dailyFile).normalize();
        return Files.isReadable(file) ? file : null;
    }

    /** @return Zeilen pro UUID, oder null wenn die Datenbank nicht gelesen werden konnte (Grund steht im Log). */
    Map<UUID, Row> read() {
        Path file = file();
        if (file == null) {
            return null;
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            log.warning("DailyRewards: SQLite-Treiber ist auf diesem Server nicht verfuegbar.");
            return null;
        }
        Properties props = new Properties();
        props.setProperty("open_mode", "1"); // SQLITE_OPEN_READONLY
        String table = settings.dailyTable.replaceAll("[^A-Za-z0-9_]", "");
        Map<UUID, Row> result = new HashMap<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file, props);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT uuid, currentStreak, longestStreak, totalClaims, lastClaimTime FROM " + table)) {
            while (rs.next()) {
                try {
                    result.put(UUID.fromString(rs.getString("uuid")),
                            new Row(rs.getInt("currentStreak"), rs.getInt("longestStreak"), rs.getInt("totalClaims"), rs.getLong("lastClaimTime")));
                } catch (IllegalArgumentException ignored) {
                    // ungueltige UUID
                }
            }
        } catch (SQLException e) {
            log.warning("DailyRewards: Abfrage der Tabelle '" + table + "' fehlgeschlagen: " + e.getMessage()
                    + ". Erwartet werden die Spalten uuid, currentStreak, longestStreak, totalClaims, lastClaimTime.");
            return null;
        }
        return result;
    }
}
