package com.github.donsimm.serverstats;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/** Kleiner Datei-Webserver (nur GET/HEAD, nur Dateien unterhalb des Ausgabeordners). */
final class WebServer {
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "json", "application/json; charset=utf-8",
            "png", "image/png",
            "woff2", "font/woff2",
            "svg", "image/svg+xml",
            "ico", "image/x-icon");

    private final HttpServer server;
    private final ExecutorService executor;
    private final Path root;

    private WebServer(HttpServer server, ExecutorService executor, Path root) {
        this.server = server;
        this.executor = executor;
        this.root = root;
    }

    /** @return der laufende Server, oder null (mit Hinweis im Log), wenn er nicht starten kann */
    static WebServer start(String bind, int port, Path root, Logger log) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
            ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "ServerStats-Web");
                t.setDaemon(true);
                return t;
            });
            WebServer web = new WebServer(server, executor, root.toAbsolutePath().normalize());
            server.createContext("/", web::handle);
            server.setExecutor(executor);
            server.start();
            log.info("Webserver laeuft auf " + bind + ":" + port + " (Ordner " + web.root + ")");
            return web;
        } catch (IOException e) {
            log.severe("Webserver konnte nicht auf " + bind + ":" + port + " starten: " + e.getMessage()
                    + " (Port belegt? http.port in der config.yml aendern)");
        } catch (NoClassDefFoundError e) {
            log.severe("Dieses Java enthaelt kein Modul jdk.httpserver. Webserver abgeschaltet: http.enabled: false setzen "
                    + "und den Ordner " + root + " ueber nginx/Apache ausliefern.");
        }
        return null;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                send(exchange, 405, "text/plain; charset=utf-8", "Method Not Allowed".getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            String path = URLDecoder.decode(exchange.getRequestURI().getRawPath(), StandardCharsets.UTF_8);
            if (path.equals("/") || path.isEmpty()) {
                path = "/index.html";
            }
            Path file = root.resolve(path.substring(1)).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                send(exchange, 404, "text/plain; charset=utf-8", "Nicht gefunden".getBytes(StandardCharsets.UTF_8), method.equals("HEAD"));
                return;
            }
            String name = file.getFileName().toString();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
            String type = TYPES.getOrDefault(ext, "application/octet-stream");
            // Seite, Skripte, Stile und Daten nie zwischenspeichern, sonst mischen sich alte Dateien mit neuen Daten
            if (ext.equals("json") || ext.equals("html") || ext.equals("js") || ext.equals("css")) {
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
            } else {
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
            }
            send(exchange, 200, type, Files.readAllBytes(file), method.equals("HEAD"));
        } catch (RuntimeException e) {
            send(exchange, 500, "text/plain; charset=utf-8", "Fehler".getBytes(StandardCharsets.UTF_8), false);
        } finally {
            exchange.close();
        }
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body, boolean headOnly) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (headOnly) {
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(body.length));
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }
}
