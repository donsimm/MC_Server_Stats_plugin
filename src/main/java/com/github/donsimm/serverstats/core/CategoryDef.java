package com.github.donsimm.serverstats.core;

import java.util.List;

/**
 * Eine Rangliste, wie sie in der config.yml unter "categories" steht.
 *
 * @param page       Name des Reiters auf der Webseite (z. B. "Spielwelt", "Unterwegs")
 * @param highlight  true: erscheint zusaetzlich auf der Uebersichtsseite
 * @param source     vanilla, advancements, vault, mcmmo-power, cookieclicker oder ajlb
 * @param stat       Vanilla: Liste von "gruppe:schluessel", der erste vorhandene Eintrag zaehlt
 * @param sum        Vanilla: alle Eintraege werden addiert
 * @param groupSum   Vanilla: alle Eintraege einer Gruppe werden addiert (z. B. "mined")
 * @param scale      Faktor auf den Rohwert (z. B. Ticks in Stunden)
 * @param column     CookieClicker: Spalte COOKIES, PER_CLICK oder CLICKER_CLICKS
 * @param ajlbBoard  AJLeaderboards-Board fuer Zeitfenster (oder als Quelle bei source=ajlb)
 * @param ajlbScale  Faktor auf die AJLB-Werte
 */
public record CategoryDef(String id, String page, boolean highlight, String title, String subtitle, String icon, String color,
                          boolean night, String format, String source, List<String> stat, List<String> sum,
                          String groupSum, double scale, String column, String ajlbBoard, double ajlbScale) {
}
