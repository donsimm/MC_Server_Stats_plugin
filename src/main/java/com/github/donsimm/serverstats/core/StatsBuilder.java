package com.github.donsimm.serverstats.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Baut aus Kategorien und Spielerdaten die stats.json fuer die Webseite. */
public final class StatsBuilder {
    public static final List<String> PERIODS = List.of("month", "week", "day");

    /** Eine Kennzahl oben auf der Startseite: Summe aller Werte einer Kategorie. */
    public record Chip(String category, String label) {
    }

    private StatsBuilder() {
    }

    public static JsonObject build(String title, String subtitle, long generatedSeconds, List<CategoryDef> categories,
                                   Collection<PlayerData> players,
                                   Map<String, Map<String, List<AjlbEntry>>> ajlb,
                                   List<Chip> chips, Map<String, Integer> categoryMax, int mcmmoRadarMax) {
        Map<String, UUID> byName = new HashMap<>();
        for (PlayerData p : players) {
            if (p.name != null) {
                byName.put(p.name.toLowerCase(Locale.ROOT), p.uuid);
            }
        }
        Map<UUID, PlayerData> byUuid = new HashMap<>();
        for (PlayerData p : players) {
            byUuid.put(p.uuid, p);
        }

        JsonObject root = new JsonObject();
        root.addProperty("generated", generatedSeconds);
        root.addProperty("title", title);
        root.addProperty("subtitle", subtitle);
        root.addProperty("mcmmoRadarMax", mcmmoRadarMax);

        Map<UUID, Boolean> listed = new LinkedHashMap<>();
        Map<String, Double> categorySums = new HashMap<>();
        JsonArray categoryArray = new JsonArray();
        for (CategoryDef category : categories) {
            List<Map.Entry<UUID, Double>> all = new ArrayList<>();
            for (PlayerData p : players) {
                Double value = p.values.get(category.id());
                if (value != null && !value.isNaN() && value > 0) {
                    all.add(Map.entry(p.uuid, value));
                }
            }
            all.sort(byValueDesc(byUuid));

            JsonObject boards = new JsonObject();
            boards.add("all", toArray(all));
            double sum = 0;
            for (Map.Entry<UUID, Double> e : all) {
                sum += e.getValue();
                listed.put(e.getKey(), Boolean.TRUE);
            }
            categorySums.put(category.id(), sum);

            JsonArray periods = new JsonArray();
            periods.add("all");
            Map<String, List<AjlbEntry>> perPeriod = ajlb.get(category.id());
            if (perPeriod != null) {
                for (String period : PERIODS) {
                    List<AjlbEntry> entries = perPeriod.get(period);
                    if (entries == null) {
                        continue;
                    }
                    List<Map.Entry<UUID, Double>> list = new ArrayList<>();
                    for (AjlbEntry entry : entries) {
                        UUID uuid = byName.get(entry.name().toLowerCase(Locale.ROOT));
                        double value = entry.value() * category.ajlbScale();
                        if (uuid != null && value > 0) {
                            list.add(Map.entry(uuid, value));
                            listed.put(uuid, Boolean.TRUE);
                        }
                    }
                    if (list.isEmpty()) {
                        continue;
                    }
                    list.sort(byValueDesc(byUuid));
                    boards.add(period, toArray(list));
                    periods.add(period);
                }
            }

            JsonObject object = new JsonObject();
            object.addProperty("id", category.id());
            object.addProperty("page", category.page());
            object.addProperty("highlight", category.highlight());
            object.addProperty("title", category.title());
            object.addProperty("subtitle", category.subtitle());
            object.addProperty("icon", category.icon());
            object.addProperty("color", category.color());
            object.addProperty("night", category.night());
            object.addProperty("format", category.format());
            object.addProperty("source", sourceLabel(category));
            Integer max = categoryMax.get(category.id());
            if (max != null) {
                object.addProperty("max", max);
            }
            object.add("periods", periods);
            object.add("boards", boards);
            categoryArray.add(object);
        }
        root.add("categories", categoryArray);

        JsonObject playerObject = new JsonObject();
        for (PlayerData p : players) {
            boolean hasSkills = p.mcmmoSkills != null && !p.mcmmoSkills.isEmpty();
            if (!listed.containsKey(p.uuid) && !hasSkills) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("name", p.name);
            o.addProperty("first", p.first);
            o.addProperty("last", p.last);
            if (hasSkills) {
                JsonObject skills = new JsonObject();
                for (Map.Entry<String, Integer> e : new java.util.TreeMap<>(p.mcmmoSkills).entrySet()) {
                    skills.addProperty(e.getKey(), e.getValue());
                }
                JsonObject mcmmo = new JsonObject();
                mcmmo.add("skills", skills);
                mcmmo.addProperty("power", p.mcmmoPower);
                o.add("mcmmo", mcmmo);
            }
            playerObject.add(p.uuid.toString(), o);
        }
        root.add("players", playerObject);

        JsonArray chipArray = new JsonArray();
        for (Chip chip : chips) {
            Double sum = categorySums.get(chip.category());
            if (sum == null) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("category", chip.category());
            o.addProperty("label", chip.label());
            o.add("value", number(sum));
            chipArray.add(o);
        }
        root.add("chips", chipArray);
        return root;
    }

    private static String sourceLabel(CategoryDef c) {
        return switch (c.source()) {
            case "vanilla" -> "Vanilla-Statistik";
            case "advancements" -> "Vanilla-Advancements";
            case "vault" -> "Vault (Kontostand)";
            case "mcmmo-power" -> "mcMMO";
            case "cookieclicker" -> "CookieClicker (ZilloLP)";
            case "ajlb" -> "AJLeaderboards";
            default -> c.source();
        };
    }

    private static Comparator<Map.Entry<UUID, Double>> byValueDesc(Map<UUID, PlayerData> byUuid) {
        return (a, b) -> {
            int c = Double.compare(b.getValue(), a.getValue());
            if (c != 0) {
                return c;
            }
            String na = byUuid.get(a.getKey()) == null ? "" : String.valueOf(byUuid.get(a.getKey()).name);
            String nb = byUuid.get(b.getKey()) == null ? "" : String.valueOf(byUuid.get(b.getKey()).name);
            return na.compareToIgnoreCase(nb);
        };
    }

    private static JsonArray toArray(List<Map.Entry<UUID, Double>> list) {
        JsonArray array = new JsonArray();
        for (Map.Entry<UUID, Double> e : list) {
            JsonArray pair = new JsonArray();
            pair.add(e.getKey().toString());
            pair.add(number(e.getValue()));
            array.add(pair);
        }
        return array;
    }

    /** Ganze Zahlen ohne Nachkommastellen, sonst auf drei Stellen gerundet. */
    static JsonPrimitive number(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 9.0e15) {
            return new JsonPrimitive((long) value);
        }
        return new JsonPrimitive(Math.round(value * 1000.0) / 1000.0);
    }
}
