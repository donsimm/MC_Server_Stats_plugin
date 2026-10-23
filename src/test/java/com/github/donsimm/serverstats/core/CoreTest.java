package com.github.donsimm.serverstats.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreTest {
    private static final String STATS = """
            {"stats":{
              "minecraft:custom":{"minecraft:deaths":5,"minecraft:play_time":144000,"minecraft:walk_one_cm":200000,"minecraft:sprint_one_cm":300000},
              "minecraft:mined":{"minecraft:stone":100,"minecraft:dirt":23},
              "minecraft:killed":{"minecraft:player":2}},
             "DataVersion":4440}""";

    private static CategoryDef cat(String id, List<String> stat, List<String> sum, String groupSum, double scale) {
        return new CategoryDef(id, "S", id, "", "clock", "#fff", false, "int", "vanilla", stat, sum, groupSum, scale,
                "", "", 1);
    }

    @Test
    void vanillaSingleValueWithScaleAndFallbackKey() {
        VanillaStats s = VanillaStats.parse(STATS);
        assertEquals(5, s.get("custom:deaths"));
        assertEquals(5, s.get("minecraft:custom:deaths"));
        assertEquals(-1, s.get("custom:missing"));
        // erster Treffer der Liste zaehlt (play_one_minute gibt es hier nicht)
        double hours = s.value(cat("h", List.of("custom:play_one_minute", "custom:play_time"), List.of(), "", 1.0 / 72000));
        assertEquals(2.0, hours, 1e-9);
    }

    @Test
    void vanillaSumAndGroupSum() {
        VanillaStats s = VanillaStats.parse(STATS);
        double km = s.value(cat("km", List.of(), List.of("custom:walk_one_cm", "custom:sprint_one_cm", "custom:swim_one_cm"), "", 1e-5));
        assertEquals(5.0, km, 1e-9);
        assertEquals(123, s.value(cat("mined", List.of(), List.of(), "mined", 1)), 1e-9);
        assertEquals(2, s.get("killed:player"));
    }

    @Test
    void advancementsCountsOnlyDoneNonRecipes() {
        String json = """
                {"minecraft:story/root":{"criteria":{},"done":true},
                 "minecraft:story/mine_stone":{"criteria":{},"done":false},
                 "minecraft:recipes/misc/stick":{"criteria":{},"done":true},
                 "minecraft:adventure/root":{"criteria":{},"done":true},
                 "DataVersion":4440}""";
        assertEquals(2, AdvancementsFile.countDone(json));
    }

    @Test
    void numberParse() {
        assertEquals(1234.0, NumberParse.parse("1,234"), 1e-9);
        assertEquals(1234.5, NumberParse.parse("1,234.5"), 1e-9);
        assertEquals(1234.5, NumberParse.parse("1.234,5"), 1e-9);
        assertEquals(1234567.0, NumberParse.parse("1,234,567"), 1e-9);
        assertEquals(42.0, NumberParse.parse("§a42 Kekse"), 1e-9);
        assertEquals(3.5, NumberParse.parse("3,5"), 1e-9);
        assertTrue(Double.isNaN(NumberParse.parse("---")));
        assertTrue(Double.isNaN(NumberParse.parse(null)));
    }

    @Test
    void builderSortsFiltersAndMapsAjlbNames() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        UUID c = UUID.fromString("00000000-0000-0000-0000-00000000000c");
        PlayerData pa = new PlayerData(a);
        pa.name = "Alpha";
        pa.values.put("deaths", 3.0);
        PlayerData pb = new PlayerData(b);
        pb.name = "Beta";
        pb.values.put("deaths", 9.0);
        PlayerData pc = new PlayerData(c);
        pc.name = "Gamma";
        pc.values.put("deaths", 0.0); // 0 wird nicht gelistet
        pc.mcmmoSkills = Map.of("MINING", 120);
        pc.mcmmoPower = 120;
        CategoryDef deaths = cat("deaths", List.of("custom:deaths"), List.of(), "", 1);
        Map<String, Map<String, List<AjlbEntry>>> ajlb = Map.of("deaths",
                Map.of("week", List.of(new AjlbEntry("beta", 2), new AjlbEntry("Unbekannt", 99))));

        JsonObject json = StatsBuilder.build("T", "S", 1000, List.of(deaths), List.of(pa, pb, pc), ajlb,
                List.of(new StatsBuilder.Chip("deaths", "Tode")), Map.of(), 1000);

        JsonObject category = json.getAsJsonArray("categories").get(0).getAsJsonObject();
        JsonArray all = category.getAsJsonObject("boards").getAsJsonArray("all");
        assertEquals(2, all.size());
        assertEquals(b.toString(), all.get(0).getAsJsonArray().get(0).getAsString());
        assertEquals(9, all.get(0).getAsJsonArray().get(1).getAsLong());
        JsonArray week = category.getAsJsonObject("boards").getAsJsonArray("week");
        assertEquals(1, week.size());
        assertEquals(2, week.get(0).getAsJsonArray().get(1).getAsLong());
        assertEquals(List.of("all", "week"), category.getAsJsonArray("periods").asList().stream().map(e -> e.getAsString()).toList());
        assertEquals(12, json.getAsJsonArray("chips").get(0).getAsJsonObject().get("value").getAsLong());
        assertTrue(json.getAsJsonObject("players").has(c.toString()), "Spieler mit mcMMO-Daten wird gelistet");
        assertEquals(120, json.getAsJsonObject("players").getAsJsonObject(c.toString())
                .getAsJsonObject("mcmmo").get("power").getAsInt());
    }
}
