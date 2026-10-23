package com.github.donsimm.serverstats;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Spieler, die nicht auf der Seite erscheinen wollen (optout.txt, eine UUID pro Zeile). */
final class OptOutStore {
    private final Path file;
    private final Logger log;
    private final Set<UUID> ids = ConcurrentHashMap.newKeySet();

    OptOutStore(Path file, Logger log) {
        this.file = file;
        this.log = log;
        try {
            if (Files.isRegularFile(file)) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (String line : lines) {
                    try {
                        ids.add(UUID.fromString(line.trim()));
                    } catch (IllegalArgumentException ignored) {
                        // leere oder ungueltige Zeile
                    }
                }
            }
        } catch (IOException e) {
            log.warning("optout.txt konnte nicht gelesen werden: " + e.getMessage());
        }
    }

    boolean contains(UUID id) {
        return ids.contains(id);
    }

    /** @return true, wenn der Spieler jetzt ausgeblendet ist */
    boolean toggle(UUID id) {
        boolean nowHidden = ids.add(id);
        if (!nowHidden) {
            ids.remove(id);
        }
        save();
        return nowHidden;
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, ids.stream().map(UUID::toString).toList(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warning("optout.txt konnte nicht gespeichert werden: " + e.getMessage());
        }
    }
}
