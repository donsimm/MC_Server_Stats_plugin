package com.github.donsimm.serverstats;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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

    /** Die konfigurierte Datei, sonst eine .db-Datei in einem Ordner mit "cookie" im Namen (Ordnername kann je nach Version abweichen). */
    private Path locateSqlite() {
        Path configured = pluginsDir.resolve(settings.cookieSqliteFile).normalize();
        if (Files.isReadable(configured)) {
            return configured;
        }
        List<Path> found = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(pluginsDir)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir) || !dir.getFileName().toString().toLowerCase(Locale.ROOT).contains("cookie")) {
                    continue;
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
                    for (Path f : files) {
                        seen.add(dir.getFileName() + "/" + f.getFileName());
                        if (f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".db")) {
                            found.add(f);
                        }
                    }
                }
            }
        } catch (IOException e) {
            // Ordner nicht lesbar: Meldung unten
        }
        if (!found.isEmpty()) {
            log.info("CookieClicker: konfigurierte Datei fehlt (" + configured + "), verwende stattdessen " + found.get(0));
            return found.get(0);
        }
        log.warning("CookieClicker: keine SQLite-Datei gefunden (erwartet: " + configured + "). Inhalt der Ordner mit 'cookie' im Namen: "
                + (seen.isEmpty() ? "keine solchen Ordner" : String.join(", ", seen))
                + ". Nutzt CookieClicker MySQL? Dann sources.cookieclicker.type: mysql einstellen.");
        return null;
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
            Path file = locateSqlite();
            if (file == null) {
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
