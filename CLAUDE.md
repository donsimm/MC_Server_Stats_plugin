# Projekt-Notizen für Claude

## Worum es geht
Paper-Plugin **ServerStats**: sammelt Vanilla-Statistiken und Daten von Vault, mcMMO, AJLeaderboards (via PlaceholderAPI),
CookieClickerZ (KartoffelChipss, DB `userData.db` lesen; Tabelle `players`, Zahlen als Text) und schreibt `stats.json` / `online.json` für eine mitgelieferte Webseite (`src/main/resources/web`).
Zielserver des Nutzers: Paper 26.2, 20 bis 30 Spieler, Antworten auf Deutsch, technisch und knapp, Quellen nennen.

## Aufbau
- `core/`: Logik ohne Bukkit (Vanilla-JSON lesen, `StatsBuilder` baut stats.json, `NumberParse`). Mit JUnit getestet.
- Hauptpaket: `Collector` (Hauptthread sammelt, eigener Thread schreibt), `Hooks` (Vault/mcMMO/PAPI/Essentials per Reflection),
  `CookieClickerReader`, `HeadService`, `WebServer`, `OnlineTracker`, `StatsCommand`, `Settings`.
- Ranglisten sind rein in `config.yml` definiert (`categories`), die Webseite zeichnet, was in `stats.json` steht.

## Bauen und Testen
- **Lokal ist das Paper-Repo gesperrt** (`repo.papermc.io`), `mvn package` scheitert hier beim Auflösen von `paper-api`. Maven Central geht.
- Gebaut wird in GitHub Actions (`.github/workflows/build.yml`); das JAR landet unter Releases (`build-<branch>`). Fehler dort mit den Actions-Tools des GitHub-MCP lesen.
- Tests der `core`-Klassen lokal: `javac --release 21 -cp gson.jar:junit-platform-console-standalone.jar -d /tmp/out $(find src/main/java/.../core src/test -name '*.java')`, dann `java -jar junit-platform-console-standalone.jar execute -cp /tmp/out:gson.jar --scan-classpath /tmp/out`.
- Webseite lokal prüfen: Ordner `src/main/resources/web` plus Beispiel-`stats.json`/`online.json` per `python3 -m http.server` ausliefern, mit Playwright (`/opt/pw-browsers/chromium`) ansehen.

## Hinweise
- Gebaut wird gegen Paper-API 1.21.11 (`pom.xml`, Property `paper.api.version`); Plugin-`api-version` ist `'1.21'`.
- Fremdplugins nie direkt importieren, nur per Reflection (`Hooks`), sonst braucht der Build deren Repos.
- Nicht verifiziert (Stand erster Entwurf): AJLB-Platzhalter-Schreibweise, Statistik-Ordner unter Paper 26.x, mcMMO-Reflection, SQLite-Treiber.
- CookieClicker-Lizenz: nicht kommerziell, Namensnennung (steht im Footer der Seite).
