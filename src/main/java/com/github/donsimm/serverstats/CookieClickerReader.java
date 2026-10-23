package com.github.donsimm.serverstats;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
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
 * Liest die Spielerdaten des Plugins CookieClickerZ (KartoffelChipss) rein lesend aus dessen Datenbank.
 * Tabelle players: uuid, name, totalCookies (Text, Riesenzahl), totalClicks, lastLogoutTime, cookiesPerClick (Text),
 * offlineCookies (Text), prestige. SQLite-Datei: plugins/CookieClickerZ/userData.db.
 */
final class CookieClickerReader {
    record Row(String name, double cookies, double perClick, long clicks, int prestige) {
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
            log.info("CookieClickerZ: konfigurierte Datei fehlt (" + configured + "), verwende stattdessen " + found.get(0));
            return found.get(0);
        }
        log.warning("CookieClickerZ: keine SQLite-Datei gefunden (erwartet: " + configured + "). Inhalt der Ordner mit 'cookie' im Namen: "
                + (seen.isEmpty() ? "keine solchen Ordner" : String.join(", ", seen))
                + ". Nutzt CookieClickerZ MySQL? Dann sources.cookieclicker.type: mysql einstellen.");
        return null;
    }

    private static double big(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        try {
            double value = new BigDecimal(text.trim()).doubleValue();
            return Double.isInfinite(value) ? Double.MAX_VALUE : value;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Tabellen und Spalten der Datenbank als Text, fuer die Fehlersuche. */
    private static String describeSchema(Connection connection) {
        List<String> tables = new ArrayList<>();
        try {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String table = rs.getString("TABLE_NAME");
                    List<String> columns = new ArrayList<>();
                    try (ResultSet cols = meta.getColumns(null, null, table, "%")) {
                        while (cols.next()) {
                            columns.add(cols.getString("COLUMN_NAME"));
                        }
                    }
                    tables.add(table + "(" + String.join(", ", columns) + ")");
                }
            }
        } catch (SQLException e) {
            return "Schema nicht lesbar: " + e.getMessage();
        }
        return tables.isEmpty() ? "keine Tabellen" : String.join("; ", tables);
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
            log.warning("CookieClickerZ: JDBC-Treiber " + driver + " ist auf diesem Server nicht verfuegbar.");
            return null;
        }
        String table = settings.cookieTable.replaceAll("[^A-Za-z0-9_]", "");
        Map<UUID, Row> result = new HashMap<>();
        try (Connection connection = DriverManager.getConnection(url, props)) {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT uuid, name, totalCookies, totalClicks, cookiesPerClick, prestige FROM " + table)) {
                while (rs.next()) {
                    try {
                        result.put(UUID.fromString(rs.getString("uuid")),
                                new Row(rs.getString("name"), big(rs.getString("totalCookies")), big(rs.getString("cookiesPerClick")),
                                        rs.getLong("totalClicks"), rs.getInt("prestige")));
                    } catch (IllegalArgumentException ignored) {
                        // ungueltige UUID in der Tabelle
                    }
                }
            } catch (SQLException e) {
                log.warning("CookieClickerZ: Abfrage der Tabelle '" + table + "' fehlgeschlagen: " + e.getMessage()
                        + ". Vorhandene Tabellen: " + describeSchema(connection));
                return null;
            }
        } catch (SQLException e) {
            log.warning("CookieClickerZ: Datenbank konnte nicht geoeffnet werden: " + e.getMessage());
            return null;
        }
        return result;
    }
}
