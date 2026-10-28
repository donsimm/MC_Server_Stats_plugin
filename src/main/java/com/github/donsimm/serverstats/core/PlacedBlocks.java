package com.github.donsimm.serverstats.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Platzierte Bloecke fuers Museum-aehnliche Baumeister-Modul im Profil: je Block die Top-Spieler, je Spieler die meistgesetzten Bloecke.
 * Es werden nur die wichtigsten Eintraege behalten, damit die stats.json klein bleibt.
 */
public final class PlacedBlocks {
    private PlacedBlocks() {
    }

    public record Block(String id, long total, List<Map.Entry<UUID, Long>> top) {
    }

    public record Player(long total, List<Map.Entry<String, Long>> top) {
    }

    public record Result(List<Block> blocks, Map<UUID, Player> players) {
    }

    /**
     * @param perPlayer    Spieler -&gt; (Block-ID -&gt; Anzahl platziert)
     * @param topBlocks    so viele Bloecke (nach Gesamtzahl) werden immer behalten
     * @param topPlayers   Top-Spieler je Block
     * @param perPlayerTop so viele meistgesetzte Bloecke je Spieler kommen immer in die Blockliste; je Spieler werden danach
     *                     alle Werte der Bloecke aus der Liste mitgegeben (damit die Webseite keine Luecken zeigt)
     */
    public static Result aggregate(Map<UUID, Map<String, Long>> perPlayer, int topBlocks, int topPlayers, int perPlayerTop) {
        Map<String, Long> totals = new HashMap<>();
        perPlayer.values().forEach(m -> m.forEach((block, n) -> totals.merge(block, n, Long::sum)));

        Map<UUID, List<Map.Entry<String, Long>>> sortedPerPlayer = new LinkedHashMap<>();
        Set<String> wanted = new LinkedHashSet<>();
        Map<UUID, Long> totalPerPlayer = new HashMap<>();
        totals.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(topBlocks).forEach(e -> wanted.add(e.getKey()));
        for (Map.Entry<UUID, Map<String, Long>> e : perPlayer.entrySet()) {
            List<Map.Entry<String, Long>> sorted = new ArrayList<>(e.getValue().entrySet());
            sorted.removeIf(x -> x.getValue() <= 0);
            sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            if (sorted.isEmpty()) {
                continue;
            }
            long sum = sorted.stream().mapToLong(Map.Entry::getValue).sum();
            List<Map.Entry<String, Long>> top = new ArrayList<>(sorted.subList(0, Math.min(perPlayerTop, sorted.size())));
            top.forEach(x -> wanted.add(x.getKey()));
            sortedPerPlayer.put(e.getKey(), sorted);
            totalPerPlayer.put(e.getKey(), sum);
        }
        Map<UUID, Player> players = new LinkedHashMap<>();
        sortedPerPlayer.forEach((uuid, sorted) -> players.put(uuid,
                new Player(totalPerPlayer.get(uuid), sorted.stream().filter(x -> wanted.contains(x.getKey())).toList())));

        List<Block> blocks = new ArrayList<>();
        for (String id : wanted) {
            List<Map.Entry<UUID, Long>> who = new ArrayList<>();
            perPlayer.forEach((uuid, m) -> {
                Long n = m.get(id);
                if (n != null && n > 0) {
                    who.add(Map.entry(uuid, n));
                }
            });
            who.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            blocks.add(new Block(id, totals.getOrDefault(id, 0L), new ArrayList<>(who.subList(0, Math.min(topPlayers, who.size())))));
        }
        blocks.sort((a, b) -> Long.compare(b.total(), a.total()));
        return new Result(blocks, players);
    }
}
