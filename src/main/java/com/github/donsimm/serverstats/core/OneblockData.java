package com.github.donsimm.serverstats.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Liest die Daten des Plugins OneBlock (MrMarL), Datei PlData.json:
 * {"0": {"uuid": "...", "lvl": 5, "breaks": 12, "visit": true, "invited": ["..."]}, "1": {...}, "id": 2}
 */
public final class OneblockData {
    /** Eine Insel: Besitzer ist der Schluessel der Ergebnis-Map. */
    public record Island(int level, int breaks, int invited) {
    }

    private OneblockData() {
    }

    public static Map<UUID, Island> parse(String json) {
        Map<UUID, Island> result = new HashMap<>();
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) {
            return result;
        }
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
            if (entry.getKey().equals("id") || !entry.getValue().isJsonObject()) {
                continue; // Kopfzeile oder leerer Platz
            }
            JsonObject island = entry.getValue().getAsJsonObject();
            JsonElement uuid = island.get("uuid");
            if (uuid == null || !uuid.isJsonPrimitive()) {
                continue;
            }
            try {
                int invited = 0;
                JsonElement list = island.has("invited") ? island.get("invited") : island.get("invated");
                if (list != null && list.isJsonArray()) {
                    invited = list.getAsJsonArray().size();
                }
                result.put(UUID.fromString(uuid.getAsString()), new Island(
                        island.has("lvl") ? island.get("lvl").getAsInt() : 0,
                        island.has("breaks") ? island.get("breaks").getAsInt() : 0,
                        invited));
            } catch (RuntimeException ignored) {
                // ungueltige UUID oder Zahl: Zeile auslassen
            }
        }
        return result;
    }
}
