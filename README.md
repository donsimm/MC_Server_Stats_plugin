# ServerStats

Paper-Plugin, das Spielerstatistiken und Daten anderer Plugins einsammelt und daraus eine Webseite mit Ranglisten,
Spielerprofilen, Skill-Radar und Online-Liste erstellt (dunkles Neo-Brutalism-Design mit Pixel-Schrift).

**Stand: erster Entwurf, noch nicht auf einem echten Server getestet.** Siehe «Testen» unten.

## Installation

1. JAR aus dem Reiter *Releases* dieses Repos laden (Eintrag `build-<branch>`) und in `plugins/` legen.
2. Server starten. Das Plugin legt `plugins/ServerStats/config.yml` und den Ordner `plugins/ServerStats/web/` an.
3. Im Log nach `[ServerStats]` suchen. Nach etwa 5 Sekunden steht dort `stats.json geschrieben: …`.
4. Seite öffnen: `http://<Server-IP>:8123` (Port in der `config.yml` unter `http.port`, muss in der Firewall offen sein).
   Hinter nginx/Apache: `http.enabled: false` setzen und den Ordner `plugins/ServerStats/web/` ausliefern.
5. `http.public-url` in der `config.yml` auf die öffentliche Adresse stellen (wird bei `/serverstats link` angezeigt).

Voraussetzung: Paper mit Java 21 oder neuer. Optional: Vault, mcMMO, PlaceholderAPI + AJLeaderboards, EssentialsX, CookieClicker.

## Seitenaufbau und Logo

Menü: **Übersicht** (alle allgemeinen Ranglisten), **mcMMO** (Skill-Meister und eine Rangliste je Skill), **Cookie Clicker**, **Jobs**, **Spieler**.
Welche Rangliste auf welcher Seite steht, bestimmt `page` in der `config.yml` (`"Übersicht"` = Startseite, andere Namen ergeben neue Reiter).

Eigenes Logo: eine Datei `logo.png` in `plugins/ServerStats/web/` ersetzt im Seitenkopf Grasblock, Titel und Untertitel. Das Plugin überschreibt diese Datei nie.

## Befehle

| Befehl | Wer | Zweck |
|---|---|---|
| `/serverstats` oder `/serverstats link` | alle | Link zur Seite |
| `/serverstats optout` | alle | sich selbst auf der Seite aus- oder wieder einblenden |
| `/serverstats status` | `serverstats.admin` | gefundene Ordner, aktive Quellen, letzter Durchlauf, Hinweise |
| `/serverstats refresh` | `serverstats.admin` | sofort neu berechnen |
| `/serverstats ajlb <board> [day\|week\|month]` | `serverstats.admin` | AJLeaderboards-Platzhalter prüfen |
| `/serverstats reload` | `serverstats.admin` | `config.yml` neu laden |

## Datenquellen

| Kategorie | Quelle |
|---|---|
| Spielzeit, Tode, Blöcke, Mobs, Strecke, Zucht, Schaden, PvP | Vanilla-Statistikdateien (`world/stats/<uuid>.json`, bei neueren Versionen `world/players/stats/`) |
| Erfolge | Vanilla-Advancements (`world/advancements/<uuid>.json`) |
| Kontostand | Vault (EssentialsX Economy o. ä.) |
| Skills, Power-Level, Radar | mcMMO (`ExperienceAPI`, Flatfile oder SQL egal) |
| Zeitfenster Monat/Woche/Heute, Quest-Punkte | AJLeaderboards über PlaceholderAPI |
| Jobs (Level je Beruf, Job-Meister) | Spielerdateien von AethelJobs (`plugins/AethelJobs/playerdata/<uuid>.yml`), nur lesend; MySQL wird nicht unterstützt |
| Kekse, Klicks, Kekse pro Klick, Prestige | Datenbank des Plugins CookieClickerZ (`plugins/CookieClickerZ/userData.db`, Tabelle `players`), nur lesend |
| Online-Liste | Paper-API, Sitzungsdauer und Dimension vom Plugin selbst; AFK über EssentialsX |
| Köpfe | Mojang-Sitzungsserver, als PNG zwischengespeichert; ohne Kopf zeigt die Seite ein erzeugtes Pixel-Gesicht |

Alle Ranglisten stehen in der `config.yml` unter `categories` und lassen sich dort ändern, ergänzen oder entfernen.
Eine Kategorie erscheint nur, wenn ihre Quelle verfügbar ist. Fehlt eine, steht der Grund im Log und bei `/serverstats status`.

## Testen

Bitte der Reihe nach prüfen und mir Log-Auszüge (`[ServerStats]`, `ERROR`, `WARN`) schicken:

1. **Plugin startet:** Zeile `[ServerStats] … Webserver laeuft auf 0.0.0.0:8123`. Sonst Fehler im Log.
2. **`/serverstats status`:** Sind «Statistik-Ordner» und «Advancement-Ordner» gefunden? Welche Quellen sind «aktiv»?
3. **Seite öffnen:** Erscheinen die Karten? Stimmen die Zahlen mit dem Spiel überein (z. B. `/stats` oder Statistik-Bildschirm)?
4. **Online-Liste:** Erscheint, wer online ist, und verschwindet er nach dem Ausloggen (bis zu 30 Sekunden Verzögerung)?
5. **Kontostand** (Vault), **Skills** (mcMMO), **Cookie-Karten** (CookieClicker): erscheinen sie?
6. **Zeitfenster** (Monat/Woche/Heute): erscheint der Schalter? Wenn nicht: `/serverstats ajlb statistic_deaths week` und die Ausgabe schicken.
7. **Köpfe:** Erscheinen echte Skin-Köpfe nach ein bis zwei Minuten (pro Spieler etwa 1,5 Sekunden)?

### Ehrlich offen (nicht verifiziert)

- Läuft das Plugin gegen Paper 26.2? Gebaut wird gegen die Paper-API 1.21.11, die genutzten Methoden sind alt und stabil, geprüft ist es nicht.
- Ordner der Statistikdateien in Paper 26.x: ab dem Weltordner werden bis zu 6 Ebenen nach oben `stats` und `players/stats` gesucht (auf einem Testserver lag der Weltordner unter `DoNs_World/dimensions/minecraft/overworld`).
- Schreibweise der AJLeaderboards-Platzhalter (`sources.ajlb` in der `config.yml`): aus dem Gedächtnis, mit `/serverstats ajlb` prüfen.
- mcMMO-Anbindung per Reflection (`ExperienceAPI.getLevelOffline`, `getPowerLevelOffline`) für Version 2.2.x.
- JDBC-Treiber `org.sqlite.JDBC` für die CookieClickerZ-Datenbank: CookieClickerZ selbst nutzt ihn ohne eigene Kopie, er sollte also auf dem Server vorhanden sein.

## Entwickeln

```
mvn -B verify          # baut gegen die Paper-API und führt die Tests aus
```

Der Bau läuft automatisch in GitHub Actions (`.github/workflows/build.yml`) und legt das JAR unter *Releases* ab.
Die Logik ohne Bukkit-Abhängigkeit steckt im Paket `core` und ist mit JUnit getestet.

## Quellen und Lizenzen

- Pixelify Sans (Schrift, lokal eingebunden): SIL Open Font License 1.1, https://fonts.google.com/specimen/Pixelify+Sans
- Atkinson Hyperlegible Next (Schrift für Text und Zahlen, lokal eingebunden): SIL Open Font License 1.1, https://fonts.google.com/specimen/Atkinson+Hyperlegible+Next
- CookieClickerZ von KartoffelChipss (nur Datenbank lesen, kein Code übernommen): https://github.com/KartoffelChipss/CookieClickerZ (GPL-3.0)
- Paper-API: https://docs.papermc.io
- Vanilla-Statistiken: https://minecraft.wiki/w/Statistics
- AJLeaderboards: https://wiki.ajg0702.us/ajleaderboards
- mcMMO: https://github.com/mcMMO-Dev/mcMMO
