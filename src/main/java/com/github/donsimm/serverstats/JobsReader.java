package com.github.donsimm.serverstats;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Liest die Spielerdateien des Plugins AethelJobs (playerdata/&lt;uuid&gt;.yml) rein lesend.
 * Aufbau: jobs: &lt;Job&gt;: level: N, experience: X (Miner, Farmer, Hunter, Fisher, Woodcutter).
 */
final class JobsReader {
    private JobsReader() {
    }

    /** Die konfigurierte Ordner, sonst ein "playerdata"-Ordner in einem Plugin-Ordner mit "job" im Namen. */
    static Path locate(Settings settings, Path pluginsDir) {
        Path configured = pluginsDir.resolve(settings.jobsFolder).normalize();
        if (Files.isDirectory(configured)) {
            return configured;
        }
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(pluginsDir)) {
            for (Path dir : dirs) {
                if (dir.getFileName().toString().toLowerCase(Locale.ROOT).contains("job") && Files.isDirectory(dir.resolve("playerdata"))) {
                    return dir.resolve("playerdata");
                }
            }
        } catch (IOException ignored) {
            // kein Zugriff: null
        }
        return null;
    }

    /** UUID -&gt; (Job -&gt; {Level, XP}). Jobs ohne Fortschritt (Level 1, 0 XP) werden ausgelassen. */
    static Map<UUID, Map<String, double[]>> read(Path dir) {
        Map<UUID, Map<String, double[]>> result = new HashMap<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.yml")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                UUID uuid;
                try {
                    uuid = UUID.fromString(name.substring(0, name.length() - 4));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
                ConfigurationSection jobs = yaml.getConfigurationSection("jobs");
                if (jobs == null) {
                    continue;
                }
                Map<String, double[]> entries = new HashMap<>();
                for (String job : jobs.getKeys(false)) {
                    ConfigurationSection section = jobs.getConfigurationSection(job);
                    if (section == null) {
                        continue;
                    }
                    double level = section.getInt("level", 1);
                    double xp = section.getDouble("experience", 0);
                    if (level > 1 || xp > 0) {
                        entries.put(job, new double[]{level, xp});
                    }
                }
                if (!entries.isEmpty()) {
                    result.put(uuid, entries);
                }
            }
        } catch (IOException e) {
            return result;
        }
        return result;
    }
}
