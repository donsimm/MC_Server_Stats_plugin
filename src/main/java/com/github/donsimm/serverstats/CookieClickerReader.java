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
 * Liest die Spielerdaten des Plugins CookieClicker (ZilloLP) rein lesend aus dessen Datenbank.
 * Tabelle cookieclicker_players: UUID, NAME, COOKIES, PER_CLICK, CLICKER_CLICKS.
 */
final class CookieClickerReader {
    record Row(String name, long cookies, long perClick, long clicks) {
    }

    private final Settings settings;
    private final Path pluginsDir;
    private final Logger log;

    CookieClickerReader(Settings settings, Path pluginsDir, Logger log) {
        this.settings = settings;
        this.pluginsDir = pluginsDir;
        this.log = log;
    }

    /** @return Zeilen pro UUID, oder null wenn die Datenbank nicht gelesen werden konnte (Grund steht im Log). */
    Map<UUID, Row> read() {
        String url;
        Properties props = new Properties();
        String driver;
        if (settings.cookieMysql) {
            driver = "com.mysql.cj.jdbc.Driver";
            url = "jdbc:mysql://" + settings.cookieMysqlHost + ":" + settings.cookieMysqlPort + "/" + settings.cookieMysqlDatabase;
            props.setProperty("user", settings.cookieMysqlUser);
            props.setProperty("password", settings.cookieMysqlPassword);
        } else {
            driver = "org.sqlite.JDBC";
            Path file = pluginsDir.resolve(settings.cookieSqliteFile).normalize();
            if (!Files.isReadable(file)) {
                log.warning("CookieClicker: Datenbankdatei nicht gefunden: " + file
                        + " (sources.cookieclicker.sqlite-file pruefen, oder type: mysql einstellen)");
                return null;
            }
            url = "jdbc:sqlite:" + file;
            props.setProperty("open_mode", "1"); // SQLITE_OPEN_READONLY
        }
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            log.warning("CookieClicker: JDBC-Treiber " + driver + " ist auf diesem Server nicht verfuegbar.");
            return null;
        }
        String table = settings.cookieTable.replaceAll("[^A-Za-z0-9_]", "");
        Map<UUID, Row> result = new HashMap<>();
        try (Connection connection = DriverManager.getConnection(url, props);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT UUID, NAME, COOKIES, PER_CLICK, CLICKER_CLICKS FROM " + table)) {
            while (rs.next()) {
                try {
                    result.put(UUID.fromString(rs.getString("UUID")),
                            new Row(rs.getString("NAME"), rs.getLong("COOKIES"), rs.getLong("PER_CLICK"), rs.getLong("CLICKER_CLICKS")));
                } catch (IllegalArgumentException ignored) {
                    // ungueltige UUID in der Tabelle
                }
            }
        } catch (SQLException e) {
            log.warning("CookieClicker: Datenbank konnte nicht gelesen werden: " + e.getMessage());
            return null;
        }
        return result;
    }
}
