package com.github.donsimm.serverstats.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Erzeugt aus allen Zählern der Gruppe minecraft:custom automatisch Ranglisten, damit keine Statistik fehlt.
 * Zähler, für die es in der config.yml schon eine eigene Rangliste gibt, werden ausgelassen.
 */
public final class AutoStats {
    /** Ergebnis: neue Kategorien und deren Werte (Kategorie-ID, dann UUID). */
    public record Result(List<CategoryDef> categories, Map<String, Map<UUID, Double>> values) {
    }

    private static final String[] PALETTE = {"#8fd0ff", "#4de3d5", "#ffd23f", "#6fdc4f", "#f0b3e6", "#ffb36b", "#b9a4ff", "#e9dcb8",
            "#c6f26b", "#c7d0dc", "#ff8f8f", "#a8f0ff", "#ffe08a", "#9be8d0", "#ff9fc0"};

    private static final Set<String> TIME_KEYS = Set.of("total_world_time", "time_since_death", "time_since_rest", "sneak_time",
            "play_time", "play_one_minute");

    private static final Map<String, String> TITLES = Map.ofEntries(
            Map.entry("leave_game", "Spiel verlassen"),
            Map.entry("total_world_time", "Zeit in der Welt"),
            Map.entry("time_since_death", "Zeit seit dem letzten Tod"),
            Map.entry("time_since_rest", "Zeit seit dem letzten Schlaf"),
            Map.entry("crouch_one_cm", "Geschlichen (Strecke)"),
            Map.entry("fall_one_cm", "Gefallen (Strecke)"),
            Map.entry("fly_one_cm", "Geflogen (Strecke)"),
            Map.entry("walk_on_water_one_cm", "Über Wasser gelaufen"),
            Map.entry("walk_under_water_one_cm", "Unter Wasser gelaufen"),
            Map.entry("happy_ghast_one_cm", "Auf dem Happy Ghast"),
            Map.entry("drop", "Gegenstände fallengelassen"),
            Map.entry("damage_dealt", "Schaden ausgeteilt"),
            Map.entry("damage_dealt_absorbed", "Schaden ausgeteilt (absorbiert)"),
            Map.entry("damage_dealt_resisted", "Schaden ausgeteilt (widerstanden)"),
            Map.entry("damage_blocked_by_shield", "Schaden mit dem Schild geblockt"),
            Map.entry("damage_absorbed", "Schaden absorbiert"),
            Map.entry("damage_resisted", "Schaden widerstanden"),
            Map.entry("fish_caught", "Fische gefangen"),
            Map.entry("talked_to_villager", "Mit Dorfbewohnern gesprochen"),
            Map.entry("traded_with_villager", "Mit Dorfbewohnern gehandelt"),
            Map.entry("eat_cake_slice", "Kuchenstücke gegessen"),
            Map.entry("fill_cauldron", "Kessel gefüllt"),
            Map.entry("use_cauldron", "Kessel benutzt"),
            Map.entry("clean_armor", "Rüstung gewaschen"),
            Map.entry("clean_banner", "Banner gewaschen"),
            Map.entry("clean_shulker_box", "Shulkerkisten gewaschen"),
            Map.entry("interact_with_brewingstand", "Braustand benutzt"),
            Map.entry("interact_with_beacon", "Leuchtfeuer benutzt"),
            Map.entry("inspect_dropper", "Spender untersucht"),
            Map.entry("inspect_hopper", "Trichter untersucht"),
            Map.entry("inspect_dispenser", "Werfer untersucht"),
            Map.entry("play_noteblock", "Notenblöcke gespielt"),
            Map.entry("tune_noteblock", "Notenblöcke gestimmt"),
            Map.entry("pot_flower", "Blumen eingetopft"),
            Map.entry("trigger_trapped_chest", "Redstone-Truhen ausgelöst"),
            Map.entry("open_enderchest", "Endertruhe geöffnet"),
            Map.entry("enchant_item", "Gegenstände verzaubert"),
            Map.entry("play_record", "Schallplatten gespielt"),
            Map.entry("interact_with_furnace", "Ofen benutzt"),
            Map.entry("interact_with_crafting_table", "Werkbank benutzt"),
            Map.entry("open_chest", "Truhen geöffnet"),
            Map.entry("sleep_in_bed", "In Betten geschlafen"),
            Map.entry("open_shulker_box", "Shulkerkisten geöffnet"),
            Map.entry("open_barrel", "Fässer geöffnet"),
            Map.entry("interact_with_blast_furnace", "Schmelzofen benutzt"),
            Map.entry("interact_with_smoker", "Räucherofen benutzt"),
            Map.entry("interact_with_lectern", "Lesepult benutzt"),
            Map.entry("interact_with_campfire", "Lagerfeuer benutzt"),
            Map.entry("interact_with_cartography_table", "Kartentisch benutzt"),
            Map.entry("interact_with_loom", "Webstuhl benutzt"),
            Map.entry("interact_with_stonecutter", "Steinsäge benutzt"),
            Map.entry("bell_ring", "Glocke geläutet"),
            Map.entry("raid_trigger", "Raids ausgelöst"),
            Map.entry("raid_win", "Raids gewonnen"),
            Map.entry("interact_with_anvil", "Amboss benutzt"),
            Map.entry("interact_with_grindstone", "Schleifstein benutzt"),
            Map.entry("target_hit", "Zielblöcke getroffen"),
            Map.entry("interact_with_smithing_table", "Schmiedetisch benutzt"));

    private AutoStats() {
    }

    /** Lesbarer Titel: deutsche Bezeichnung, sonst der Schlüssel mit Leerzeichen ("some_new_stat" -> "Some new stat"). */
    public static String title(String key) {
        String known = TITLES.get(key);
        if (known != null) {
            return known;
        }
        String text = key.replace("_one_cm", " (Strecke)").replace('_', ' ').trim();
        return text.isEmpty() ? key : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    public static Result build(Map<String, Map<UUID, Long>> perKey, Set<String> excludedKeys, String page, int minPlayers) {
        List<String> keys = new ArrayList<>(perKey.keySet());
        keys.removeIf(excludedKeys::contains);
        keys.sort(Comparator.comparing((String k) -> title(k).toLowerCase(Locale.ROOT)));

        List<CategoryDef> categories = new ArrayList<>();
        Map<String, Map<UUID, Double>> values = new HashMap<>();
        int color = 0;
        for (String key : keys) {
            String format;
            double scale;
            String subtitle;
            if (key.endsWith("_one_cm")) {
                format = "km";
                scale = 0.00001;
                subtitle = "Strecke in Kilometern";
            } else if (TIME_KEYS.contains(key)) {
                format = "dur";
                scale = 1.0 / 72000;
                subtitle = "Dauer";
            } else if (key.startsWith("damage_")) {
                format = "int";
                scale = 0.1;
                subtitle = "in Lebenspunkten";
            } else {
                format = "int";
                scale = 1.0;
                subtitle = "Anzahl";
            }
            Map<UUID, Double> scaled = new HashMap<>();
            for (Map.Entry<UUID, Long> e : perKey.get(key).entrySet()) {
                if (e.getValue() > 0) {
                    scaled.put(e.getKey(), e.getValue() * scale);
                }
            }
            if (scaled.size() < Math.max(1, minPlayers)) {
                continue;
            }
            String id = "stat-" + key.replace('_', '-');
            values.put(id, scaled);
            categories.add(new CategoryDef(id, page, false, title(key), subtitle, "star", PALETTE[color++ % PALETTE.length],
                    false, format, "vanilla", List.of(), List.of(), "", 1.0, "", "", 1.0, false));
        }
        return new Result(categories, values);
    }
}
