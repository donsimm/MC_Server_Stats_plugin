package com.github.donsimm.serverstats.core;

/** Ein Eintrag einer AJLeaderboards-Rangliste (Name und Wert, wie von den Platzhaltern geliefert). */
public record AjlbEntry(String name, double value) {
}
