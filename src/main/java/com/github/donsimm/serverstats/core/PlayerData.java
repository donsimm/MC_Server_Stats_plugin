package com.github.donsimm.serverstats.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Alles, was die Seite ueber einen Spieler wissen soll. */
public final class PlayerData {
    public final UUID uuid;
    public String name;
    /** Epoch-Millisekunden, 0 = unbekannt. */
    public long first;
    public long last;
    /** Kategorie-ID -&gt; Wert. */
    public final Map<String, Double> values = new HashMap<>();
    /** mcMMO-Skills (Enum-Name -&gt; Level), null wenn nicht verfuegbar. */
    public Map<String, Integer> mcmmoSkills;
    public int mcmmoPower;
    /** Job-Name -&gt; {Level, XP}, null wenn keine Job-Daten vorliegen. */
    public Map<String, double[]> jobs;

    public PlayerData(UUID uuid) {
        this.uuid = uuid;
    }
}
