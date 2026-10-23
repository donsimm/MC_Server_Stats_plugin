package com.github.donsimm.serverstats.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Liest eine Vanilla-Statistikdatei (world/stats/&lt;uuid&gt;.json).
 * Aufbau: {"stats": {"minecraft:custom": {"minecraft:deaths": 5}, "minecraft:mined": {...}}, "DataVersion": ...}
 */
public final class VanillaStats {
    private final Map<String, Map<String, Long>> groups = new HashMap<>();

    public static VanillaStats read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(JsonParser.parseReader(reader));
        }
    }

    public static VanillaStats parse(String json) {
        return parse(JsonParser.parseString(json));
    }

    private static VanillaStats parse(JsonElement root) {
        VanillaStats result = new VanillaStats();
        if (root == null || !root.isJsonObject()) {
            return result;
        }
        JsonElement stats = root.getAsJsonObject().get("stats");
        if (stats == null || !stats.isJsonObject()) {
            return result;
        }
        for (Map.Entry<String, JsonElement> group : stats.getAsJsonObject().entrySet()) {
            if (!group.getValue().isJsonObject()) {
                continue;
            }
            Map<String, Long> entries = new HashMap<>();
            JsonObject object = group.getValue().getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                try {
                    entries.put(entry.getKey(), entry.getValue().getAsLong());
                } catch (RuntimeException ignored) {
                    // kein Zahlenwert, ueberspringen
                }
            }
            result.groups.put(group.getKey(), entries);
        }
        return result;
    }

    private static String ns(String name) {
        return name.contains(":") ? name : "minecraft:" + name;
    }

    /** Einzelwert nach "gruppe:schluessel", z. B. "custom:deaths". Fehlt er, ist das Ergebnis -1. */
    public long get(String spec) {
        int split = spec.indexOf(':');
        if (split <= 0) {
            return -1;
        }
        // "minecraft:custom:deaths" und "custom:deaths" sind beide erlaubt
        String group;
        String key;
        if (spec.startsWith("minecraft:")) {
            String rest = spec.substring("minecraft:".length());
            int second = rest.indexOf(':');
            if (second <= 0) {
                return -1;
            }
            group = "minecraft:" + rest.substring(0, second);
            key = rest.substring(second + 1);
        } else {
            group = spec.substring(0, split);
            key = spec.substring(split + 1);
        }
        Map<String, Long> entries = groups.get(ns(group));
        if (entries == null) {
            return -1;
        }
        Long value = entries.get(ns(key));
        return value == null ? -1 : value;
    }

    /** Alle Eintraege einer Gruppe (Schluessel mit "minecraft:"-Vorsatz), z. B. group("custom"). */
    public Map<String, Long> group(String group) {
        Map<String, Long> entries = groups.get(ns(group));
        return entries == null ? Map.of() : entries;
    }

    /** Summe aller Eintraege einer Gruppe, z. B. "mined". */
    public long groupSum(String group) {
        Map<String, Long> entries = groups.get(ns(group));
        if (entries == null) {
            return 0;
        }
        long sum = 0;
        for (long value : entries.values()) {
            sum += value;
        }
        return sum;
    }

    /** Wert einer Kategorie, bereits mit dem Faktor "scale" multipliziert. */
    public double value(CategoryDef category) {
        double raw = 0;
        if (!category.stat().isEmpty()) {
            for (String spec : category.stat()) {
                long value = get(spec);
                if (value >= 0) {
                    raw = value;
                    break;
                }
            }
        } else if (!category.sum().isEmpty()) {
            for (String spec : category.sum()) {
                long value = get(spec);
                if (value > 0) {
                    raw += value;
                }
            }
        } else if (category.groupSum() != null && !category.groupSum().isEmpty()) {
            raw = groupSum(category.groupSum());
        }
        return raw * category.scale();
    }

    /** Zugriff auf die Gruppen fuer Diagnosezwecke. */
    public List<String> groupNames() {
        return List.copyOf(groups.keySet());
    }
}
