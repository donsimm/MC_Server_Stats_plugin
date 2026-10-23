package com.github.donsimm.serverstats.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Liest die Ranglisten-Dateien von Infinite Parkour Reborn (leaderboards/&lt;modus&gt;.json).
 * Block-Parkour: {"serialized": {"uuid": "name,mm:ss:SSS,schwierigkeit,punkte"}}.
 * Elytra-Parkour: {"uuid": {"name":..., "score":..., "time":Millisekunden, "seed":...}}.
 */
public final class ParkourData {
    /** Fuer Zeitranglisten: Wert = TIME_BASE - Millisekunden, damit die schnellste Zeit oben steht. */
    public static final double TIME_BASE = 1_000_000_000d;

    private ParkourData() {
    }

    public record Entry(double score, long timeMs) {
    }

    public static Map<UUID, Entry> parse(String json) {
        Map<UUID, Entry> result = new HashMap<>();
        JsonElement root = JsonParser.parseString(json);
        if (root == null || !root.isJsonObject()) {
            return result;
        }
        JsonObject object = root.getAsJsonObject();
        if (object.has("serialized") && object.get("serialized").isJsonObject()) {
            object = object.getAsJsonObject("serialized");
        }
        for (Map.Entry<String, JsonElement> e : object.entrySet()) {
            try {
                UUID uuid = UUID.fromString(e.getKey());
                Entry entry = e.getValue().isJsonObject() ? fromObject(e.getValue().getAsJsonObject()) : fromText(e.getValue().getAsString());
                if (entry != null) {
                    result.put(uuid, entry);
                }
            } catch (RuntimeException ignored) {
                // ungueltiger Eintrag
            }
        }
        return result;
    }

    private static Entry fromObject(JsonObject o) {
        if (!o.has("score")) {
            return null;
        }
        return new Entry(o.get("score").getAsDouble(), o.has("time") ? o.get("time").getAsLong() : 0);
    }

    /** "name,mm:ss:SSS,schwierigkeit,punkte" (oder ein als Text gespeichertes JSON-Objekt). */
    private static Entry fromText(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) {
            return fromObject(JsonParser.parseString(trimmed).getAsJsonObject());
        }
        String[] parts = trimmed.split(",");
        if (parts.length < 4) {
            return null;
        }
        String[] t = parts[1].split(":");
        long ms = t.length == 3 ? Long.parseLong(t[0]) * 60_000 + Long.parseLong(t[1]) * 1000 + Long.parseLong(t[2]) : 0;
        return new Entry(Double.parseDouble(parts[parts.length - 1]), ms);
    }
}
