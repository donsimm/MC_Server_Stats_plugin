package com.github.donsimm.serverstats;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Holt die Skins ueber die Mojang-Server und speichert den Kopf (mit Hut-Ebene) als 64x64-PNG unter heads/&lt;uuid&gt;.png.
 * Gearbeitet wird in einem eigenen Thread, hoechstens eine Anfrage alle 1,5 Sekunden.
 * Fehlt ein Kopf (z. B. Offline-Modus), zeigt die Webseite ein erzeugtes Pixel-Gesicht.
 */
final class HeadService {
    private static final long MISS_RETRY_MS = 6L * 60 * 60 * 1000;

    private final Path dir;
    private final long maxAgeMs;
    private final Logger log;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ServerStats-Heads");
        t.setDaemon(true);
        return t;
    });
    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> missUntil = new ConcurrentHashMap<>();
    private volatile long pauseUntil;
    private volatile boolean disabled;
    private volatile String lastFailure = "";
    private final Set<String> loggedFailures = ConcurrentHashMap.newKeySet();

    HeadService(Path webDir, int refreshDays, Logger log) {
        this.dir = webDir.resolve("heads");
        this.maxAgeMs = refreshDays * 24L * 60 * 60 * 1000;
        this.log = log;
        System.setProperty("java.awt.headless", "true");
    }

    void enqueue(Collection<UUID> ids) {
        if (disabled) {
            return;
        }
        for (UUID id : ids) {
            if (isFresh(id) || missUntil.getOrDefault(id, 0L) > System.currentTimeMillis()) {
                continue;
            }
            if (queued.add(id)) {
                worker.execute(() -> fetch(id));
            }
        }
    }

    /** Fuer /serverstats status. */
    String summary() {
        long files = 0;
        try (java.util.stream.Stream<Path> stream = Files.isDirectory(dir) ? Files.list(dir) : java.util.stream.Stream.<Path>empty()) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".png")).count();
        } catch (IOException ignored) {
            // nur Anzeige
        }
        return files + " Koepfe gespeichert, " + queued.size() + " in Warteschlange, " + missUntil.size() + " ohne Skin"
                + (lastFailure.isEmpty() ? "" : ", letzter Grund: " + lastFailure) + (disabled ? " (DEAKTIVIERT)" : "");
    }

    private void fail(UUID id, String reason) {
        missUntil.put(id, System.currentTimeMillis() + MISS_RETRY_MS);
        lastFailure = reason;
        if (loggedFailures.add(reason)) {
            log.warning("Kopf fuer " + id + " nicht geladen: " + reason + " (weitere gleiche Meldungen werden nicht wiederholt)");
        }
    }

    private boolean isFresh(UUID id) {
        Path file = dir.resolve(id + ".png");
        try {
            return Files.isRegularFile(file) && System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() < maxAgeMs;
        } catch (IOException e) {
            return false;
        }
    }

    private void fetch(UUID id) {
        try {
            long wait = pauseUntil - System.currentTimeMillis();
            if (wait > 0) {
                Thread.sleep(wait);
            }
            Files.createDirectories(dir);
            String skinUrl = skinUrl(id);
            if (skinUrl == null) {
                fail(id, lastFailure.startsWith("Mojang") ? lastFailure : "Mojang kennt keinen Skin fuer diese UUID (Offline-Modus-Server?)");
                return;
            }
            HttpResponse<byte[]> png = http.send(HttpRequest.newBuilder(URI.create(skinUrl)).timeout(Duration.ofSeconds(15)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (png.statusCode() != 200) {
                fail(id, "Skin-Download HTTP " + png.statusCode());
                return;
            }
            BufferedImage skin = ImageIO.read(new ByteArrayInputStream(png.body()));
            if (skin == null || skin.getWidth() < 64 || skin.getHeight() < 16) {
                fail(id, "Skin-Bild nicht lesbar oder zu klein");
                return;
            }
            BufferedImage head = cropHead(skin);
            Path tmp = dir.resolve(id + ".png.tmp");
            ImageIO.write(head, "png", tmp.toFile());
            Files.move(tmp, dir.resolve(id + ".png"), StandardCopyOption.REPLACE_EXISTING);
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            fail(id, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (NoClassDefFoundError | UnsatisfiedLinkError e) {
            disabled = true;
            log.warning("Spielerkoepfe deaktiviert: Java-Bildbibliothek (java.desktop) fehlt auf diesem Server (" + e + ").");
        } finally {
            queued.remove(id);
        }
    }

    /** Gesicht (8x8 plus Hut-Ebene) auf 64x64 vergroessert. */
    private static BufferedImage cropHead(BufferedImage skin) {
        BufferedImage head = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int base = skin.getRGB(8 + x, 8 + y);
                int hat = skin.getRGB(40 + x, 8 + y);
                int color = (hat >>> 24) > 127 ? hat : base;
                for (int dy = 0; dy < 8; dy++) {
                    for (int dx = 0; dx < 8; dx++) {
                        head.setRGB(x * 8 + dx, y * 8 + dy, color | 0xFF000000);
                    }
                }
            }
        }
        return head;
    }

    private final ExecutorService skinWorker = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "ServerStats-MuseumHeads");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> queuedSkins = ConcurrentHashMap.newKeySet();

    /** Museum: Kopfbild aus einer Textur-Adresse (nur textures.minecraft.net) als heads/&lt;name&gt;.png speichern. */
    void enqueueSkin(String name, String skinUrl) {
        if (disabled || !skinUrl.startsWith("http://textures.minecraft.net/") && !skinUrl.startsWith("https://textures.minecraft.net/")) {
            return;
        }
        Path file = dir.resolve(name + ".png");
        try {
            if (Files.isRegularFile(file) && System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() < maxAgeMs) {
                return;
            }
        } catch (IOException ignored) {
            // dann neu laden
        }
        if (queuedSkins.add(name)) {
            skinWorker.execute(() -> {
                try {
                    Files.createDirectories(dir);
                    HttpResponse<byte[]> png = http.send(HttpRequest.newBuilder(URI.create(skinUrl)).timeout(Duration.ofSeconds(15)).build(),
                            HttpResponse.BodyHandlers.ofByteArray());
                    BufferedImage skin = png.statusCode() == 200 ? ImageIO.read(new ByteArrayInputStream(png.body())) : null;
                    if (skin == null || skin.getWidth() < 64 || skin.getHeight() < 16) {
                        lastFailure = "Museum-Textur " + name + ": HTTP " + png.statusCode() + " oder Bild nicht lesbar";
                        return;
                    }
                    Path tmp = dir.resolve(name + ".png.tmp");
                    ImageIO.write(cropHead(skin), "png", tmp.toFile());
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException | RuntimeException e) {
                    lastFailure = "Museum-Textur " + name + ": " + e.getClass().getSimpleName();
                } catch (NoClassDefFoundError | UnsatisfiedLinkError e) {
                    disabled = true;
                } finally {
                    queuedSkins.remove(name);
                }
            });
        }
    }

    /** Skin-URL ueber den Mojang-Sitzungsserver, oder null wenn das Profil nicht existiert. */
    private String skinUrl(UUID id) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + id.toString().replace("-", "")))
                        .timeout(Duration.ofSeconds(15)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) {
            pauseUntil = System.currentTimeMillis() + 10 * 60 * 1000;
            lastFailure = "Mojang: zu viele Anfragen (HTTP 429), Pause 10 Minuten";
            return null;
        }
        if (response.statusCode() != 200) {
            lastFailure = response.statusCode() == 204 || response.statusCode() == 404 ? "" : "Mojang: HTTP " + response.statusCode();
            return null;
        }
        JsonArray properties = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("properties");
        if (properties == null) {
            return null;
        }
        for (JsonElement element : properties) {
            JsonObject property = element.getAsJsonObject();
            if ("textures".equals(property.get("name").getAsString())) {
                String decoded = new String(Base64.getDecoder().decode(property.get("value").getAsString()), StandardCharsets.UTF_8);
                JsonObject textures = JsonParser.parseString(decoded).getAsJsonObject().getAsJsonObject("textures");
                if (textures != null && textures.has("SKIN")) {
                    return textures.getAsJsonObject("SKIN").get("url").getAsString();
                }
            }
        }
        return null;
    }

    void shutdown() {
        worker.shutdownNow();
        skinWorker.shutdownNow();
    }
}
