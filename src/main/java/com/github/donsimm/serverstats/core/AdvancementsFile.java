package com.github.donsimm.serverstats.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Zaehlt die abgeschlossenen Advancements in world/advancements/&lt;uuid&gt;.json (ohne Rezepte). */
public final class AdvancementsFile {
    private AdvancementsFile() {
    }

    public static int countDone(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return countDone(JsonParser.parseReader(reader));
        }
    }

    public static int countDone(String json) {
        return countDone(JsonParser.parseString(json));
    }

    private static int countDone(JsonElement root) {
        if (root == null || !root.isJsonObject()) {
            return 0;
        }
        int done = 0;
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            if (key.equals("DataVersion") || key.startsWith("minecraft:recipes/") || !entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject object = entry.getValue().getAsJsonObject();
            JsonElement flag = object.get("done");
            if (flag != null && flag.isJsonPrimitive() && flag.getAsBoolean()) {
                done++;
            }
        }
        return done;
    }

    /** IDs aller abgeschlossenen Advancements (z. B. "namespace:pfad/name"), ohne Rezepte. */
    public static Set<String> doneIds(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return doneIds(JsonParser.parseReader(reader));
        }
    }

    public static Set<String> doneIds(String json) {
        return doneIds(JsonParser.parseString(json));
    }

    private static Set<String> doneIds(JsonElement root) {
        Set<String> ids = new HashSet<>();
        if (root == null || !root.isJsonObject()) {
            return ids;
        }
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
            if (entry.getKey().equals("DataVersion") || !entry.getValue().isJsonObject()) {
                continue;
            }
            JsonElement flag = entry.getValue().getAsJsonObject().get("done");
            if (flag != null && flag.isJsonPrimitive() && flag.getAsBoolean()) {
                ids.add(entry.getKey());
            }
        }
        return ids;
    }
}
