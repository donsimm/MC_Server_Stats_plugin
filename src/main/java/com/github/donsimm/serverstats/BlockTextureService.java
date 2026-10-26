package com.github.donsimm.serverstats;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Holt die Block-Texturen fuers Baumeister-Modul. Sie werden nicht mitgeliefert (sie gehoeren Mojang), sondern bei Bedarf
 * einmalig aus dem offiziellen Minecraft-Client von den Mojang-Servern geladen (wie Dynmap) und als
 * blocks/&lt;block&gt;_top.png und blocks/&lt;block&gt;_side.png gespeichert. Der Client wird danach wieder geloescht.
 * Laeuft in einem eigenen Thread; fehlt eine Textur, zeigt die Webseite eine Ersatzfarbe.
 */
final class BlockTextureService {
    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final long RETRY_MS = 6L * 60 * 60 * 1000;

    private final Path dir;
    private final String version;
    private final Logger log;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ServerStats-BlockTextures");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private final Set<String> missing = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile long retryAt;
    private volatile String status = "noch nicht benoetigt";

    BlockTextureService(Path dir, String version, Logger log) {
        this.dir = dir;
        this.version = version;
        this.log = log;
        System.setProperty("java.awt.headless", "true");
        try {
            Path file = dir.resolve("_missing.txt");
            if (Files.isRegularFile(file)) {
                missing.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // wird neu aufgebaut
        }
    }

    String summary() {
        long files = 0;
        try (java.util.stream.Stream<Path> s = Files.isDirectory(dir) ? Files.list(dir) : java.util.stream.Stream.<Path>empty()) {
            files = s.filter(p -> p.getFileName().toString().endsWith("_top.png")).count();
        } catch (IOException ignored) {
            // nur Anzeige
        }
        return files + " Blocktexturen, " + missing.size() + " ohne Textur, " + status;
    }

    private boolean present(String id) {
        return Files.isRegularFile(dir.resolve(id + "_top.png")) || Files.isRegularFile(dir.resolve(id + "_side.png")) || missing.contains(id);
    }

    void ensure(Collection<String> blockIds) {
        List<String> todo = new ArrayList<>();
        for (String id : blockIds) {
            if (id.matches("[a-z0-9_]+") && !present(id)) {
                todo.add(id);
            }
        }
        if (todo.isEmpty() || System.currentTimeMillis() < retryAt || !running.compareAndSet(false, true)) {
            return;
        }
        worker.execute(() -> {
            try {
                run(todo);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                retryAt = System.currentTimeMillis() + RETRY_MS;
                status = "Fehler: " + e.getClass().getSimpleName() + " " + e.getMessage();
                log.warning("Block-Texturen konnten nicht geladen werden (" + e.getMessage() + "). Neuer Versuch in 6 Stunden, bis dahin zeigt die Seite Ersatzfarben.");
            } catch (NoClassDefFoundError | UnsatisfiedLinkError e) {
                retryAt = Long.MAX_VALUE;
                status = "deaktiviert";
            } finally {
                running.set(false);
            }
        });
    }

    private JsonObject getJson(String url) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IOException("HTTP " + r.statusCode() + " bei " + url);
        }
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private void run(List<String> todo) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        status = "laedt Minecraft-Client " + version;
        JsonObject manifest = getJson(MANIFEST);
        String url = null;
        String latest = manifest.getAsJsonObject("latest").get("release").getAsString();
        for (JsonElement e : manifest.getAsJsonArray("versions")) {
            JsonObject v = e.getAsJsonObject();
            if (v.get("id").getAsString().equals(version)) {
                url = v.get("url").getAsString();
            }
        }
        if (url == null) {
            for (JsonElement e : manifest.getAsJsonArray("versions")) {
                JsonObject v = e.getAsJsonObject();
                if (v.get("id").getAsString().equals(latest)) {
                    url = v.get("url").getAsString();
                }
            }
        }
        if (url == null) {
            throw new IOException("Minecraft-Version " + version + " nicht im Mojang-Manifest");
        }
        String clientUrl = getJson(url).getAsJsonObject("downloads").getAsJsonObject("client").get("url").getAsString();
        Path jar = dir.resolve("client.jar.tmp");
        HttpResponse<Path> r = http.send(HttpRequest.newBuilder(URI.create(clientUrl)).timeout(Duration.ofMinutes(5)).build(),
                HttpResponse.BodyHandlers.ofFile(jar));
        if (r.statusCode() != 200) {
            Files.deleteIfExists(jar);
            throw new IOException("Client-Download HTTP " + r.statusCode());
        }
        int found = 0;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (String id : todo) {
                if (extract(zip, id)) {
                    found++;
                } else {
                    missing.add(id);
                }
            }
        } finally {
            Files.deleteIfExists(jar);
        }
        Files.write(dir.resolve("_missing.txt"), new LinkedHashSet<>(missing), StandardCharsets.UTF_8);
        status = "zuletzt " + found + " von " + todo.size() + " Texturen aus dem Client " + version;
        log.info("Block-Texturen: " + found + " von " + todo.size() + " aus dem Minecraft-Client geholt.");
    }

    /** Suchreihenfolge: der Block selbst, Mehrzahl, ohne Form-Endung (Treppe, Stufe ...), dazu Planken und Block. */
    private static List<String> bases(String id) {
        String stripped = id.replaceAll("_(stairs|slab|wall|fence_gate|fence|button|pressure_plate|sign|hanging_sign|door|trapdoor|pane)$", "");
        List<String> out = new ArrayList<>();
        for (String b : new String[]{id, id + "s", stripped, stripped + "s", stripped + "_planks", stripped + "_block"}) {
            if (!out.contains(b)) {
                out.add(b);
            }
        }
        return out;
    }

    private static ZipEntry entry(ZipFile zip, String name) {
        return zip.getEntry("assets/minecraft/textures/block/" + name + ".png");
    }

    private boolean extract(ZipFile zip, String id) throws IOException {
        ZipEntry top = null;
        ZipEntry side = null;
        for (String b : bases(id)) {
            if (top == null) {
                top = firstOf(zip, b + "_top", b, b + "_front");
            }
            if (side == null) {
                side = firstOf(zip, b + "_side", b, b + "_front", b + "_top");
            }
            if (top != null && side != null) {
                break;
            }
        }
        if (top == null && side == null) {
            return false;
        }
        copy(zip, top != null ? top : side, dir.resolve(id + "_top.png"));
        copy(zip, side != null ? side : top, dir.resolve(id + "_side.png"));
        return true;
    }

    private static ZipEntry firstOf(ZipFile zip, String... names) {
        for (String n : names) {
            ZipEntry e = entry(zip, n);
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    private static void copy(ZipFile zip, ZipEntry e, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (java.io.InputStream in = zip.getInputStream(e)) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
