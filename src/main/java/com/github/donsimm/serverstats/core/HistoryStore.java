package com.github.donsimm.serverstats.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verlauf der Rangliste-Werte fuer Zeitfenster (letzte 7 Tage, Monat, Jahr). Es werden regelmaessig Momentaufnahmen aller Werte
 * gespeichert; ein Zeitfenster ist der Unterschied zwischen heute und der Aufnahme vor 7, 30 oder 365 Tagen.
 * Aufbewahrt: die letzten 3 Tage alle, bis 40 Tage eine pro Tag, bis 400 Tage eine pro Woche.
 */
public final class HistoryStore {
    private static final Gson GSON = new Gson();
    private static final long DAY = 86_400L;

    /** @param t Unix-Sekunden, @param v Kategorie-ID -&gt; (Spieler-UUID -&gt; Wert) */
    public record Snapshot(long t, Map<String, Map<String, Double>> v) {
    }

    private final List<Snapshot> snapshots = new ArrayList<>();

    public List<Snapshot> snapshots() {
        return snapshots;
    }

    public static HistoryStore parse(String json) {
        HistoryStore store = new HistoryStore();
        JsonElement root = JsonParser.parseString(json);
        if (root == null || !root.isJsonObject() || !root.getAsJsonObject().has("snapshots")) {
            return store;
        }
        for (JsonElement e : root.getAsJsonObject().getAsJsonArray("snapshots")) {
            JsonObject o = e.getAsJsonObject();
            Map<String, Map<String, Double>> v = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> cat : o.getAsJsonObject("v").entrySet()) {
                Map<String, Double> values = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> p : cat.getValue().getAsJsonObject().entrySet()) {
                    values.put(p.getKey(), p.getValue().getAsDouble());
                }
                v.put(cat.getKey(), values);
            }
            store.snapshots.add(new Snapshot(o.get("t").getAsLong(), v));
        }
        store.snapshots.sort((a, b) -> Long.compare(a.t(), b.t()));
        return store;
    }

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("snapshots", snapshots);
        return GSON.toJson(root);
    }

    /** Neue Aufnahme, wenn die letzte aelter als minGapSeconds ist. Leere Werte werden weggelassen. */
    public boolean addIfDue(long nowSeconds, Map<String, Map<String, Double>> values, long minGapSeconds) {
        if (!snapshots.isEmpty() && nowSeconds - snapshots.get(snapshots.size() - 1).t() < minGapSeconds) {
            return false;
        }
        Map<String, Map<String, Double>> copy = new LinkedHashMap<>();
        values.forEach((cat, map) -> {
            Map<String, Double> m = new LinkedHashMap<>();
            map.forEach((uuid, value) -> {
                if (value != null && value > 0 && !value.isNaN()) {
                    m.put(uuid, Math.round(value * 1000.0) / 1000.0);
                }
            });
            if (!m.isEmpty()) {
                copy.put(cat, m);
            }
        });
        snapshots.add(new Snapshot(nowSeconds, copy));
        return true;
    }

    /** Behaelt die letzten 3 Tage ganz, bis 40 Tage eine Aufnahme pro Tag, bis 400 Tage eine pro Woche. */
    public void prune(long nowSeconds) {
        Set<String> taken = new HashSet<>();
        List<Snapshot> keep = new ArrayList<>();
        for (Snapshot s : snapshots) {
            long age = nowSeconds - s.t();
            String bucket;
            if (age <= 3 * DAY) {
                bucket = "s" + s.t();
            } else if (age <= 40 * DAY) {
                bucket = "d" + s.t() / DAY;
            } else if (age <= 400 * DAY) {
                bucket = "w" + s.t() / (7 * DAY);
            } else {
                continue;
            }
            if (taken.add(bucket)) {
                keep.add(s);
            }
        }
        snapshots.clear();
        snapshots.addAll(keep);
    }

    /** Aufnahme vor windowSeconds (die juengste, die nicht juenger ist), sonst die aelteste (Fenster nur teilweise aufgezeichnet). */
    public Snapshot baseline(long nowSeconds, long windowSeconds) {
        Snapshot best = null;
        for (Snapshot s : snapshots) {
            if (s.t() <= nowSeconds - windowSeconds) {
                best = s;
            }
        }
        if (best == null && !snapshots.isEmpty()) {
            best = snapshots.get(0);
        }
        return best;
    }

    /** Zuwachs je Spieler seit der Aufnahme (nur positive Werte), absteigend sortiert. */
    public static List<Map.Entry<String, Double>> delta(Map<String, Double> now, Snapshot base, String category) {
        Map<String, Double> before = base == null ? Map.of() : base.v().getOrDefault(category, Map.of());
        List<Map.Entry<String, Double>> out = new ArrayList<>();
        now.forEach((uuid, value) -> {
            double diff = value - before.getOrDefault(uuid, 0.0);
            if (diff > 1e-9) {
                out.add(Map.entry(uuid, diff));
            }
        });
        out.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return out;
    }
}
