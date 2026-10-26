package dev.sebi.sebstats;

import dev.sebi.sebstats.StatsCollector.PlayerData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The leaderboards, ranked server-side so /stats can show a player's best placings.
 * Keep in step with the BOARDS list in web/index.html.
 */
final class Boards {
    /** One player's position on one board. */
    record Placement(String board, int rank, int total, String value) {}

    private record Board(String title, Function<PlayerData, Double> metric, Function<Double, String> format,
                         boolean ascending, boolean keepZero) {}

    private static final long MIN_RATE_TICKS = 7200; // 6 minutes played before a per-hour rate is meaningful

    private static final List<Board> BOARDS = List.of(
        stat("Time Played", "play_one_minute", Boards::time),
        stat("Deaths", "deaths", Boards::num),
        new Board("Deaths / Hour (fewest first)",
            p -> p.stat("play_one_minute") >= MIN_RATE_TICKS
                    ? p.stat("deaths") / (p.stat("play_one_minute") / 72000.0) : null,
            v -> String.format("%.2f", v), true, true),
        new Board("Blocks Mined", p -> (double) p.sum("mined"), Boards::num, false, false),
        new Board("Items Crafted", p -> (double) p.sum("crafted"), Boards::num, false, false),
        stat("Mob Kills", "mob_kills", Boards::num),
        stat("Player Kills", "player_kills", Boards::num),
        stat("Damage Dealt", "damage_dealt", Boards::hearts),
        stat("Damage Taken", "damage_taken", Boards::hearts),
        stat("Distance Travelled", "travel_cm", Boards::dist),
        stat("Distance Walked", "walk_one_cm", Boards::dist),
        stat("Elytra Flight", "aviate_one_cm", Boards::dist),
        stat("Jumps", "jump", Boards::num),
        stat("Fish Caught", "fish_caught", Boards::num),
        stat("Animals Bred", "animals_bred", Boards::num),
        stat("Villager Trades", "traded_with_villager", Boards::num),
        stat("Nights Slept", "sleep_in_bed", Boards::num),
        stat("Raids Won", "raid_win", Boards::num),
        new Board("Totems Used", p -> (double) p.entry("used", "totem_of_undying"), Boards::num, false, false));

    private static Board stat(String title, String key, Function<Double, String> format) {
        return new Board(title, p -> (double) p.stat(key), format, false, false);
    }

    /** Every player's placements, best first (lowest rank, then highest percentile). */
    static Map<UUID, List<Placement>> compute(List<PlayerData> players) {
        Map<UUID, List<Placement>> out = new HashMap<>();
        for (Board b : BOARDS) {
            record Row(PlayerData p, double v) {}
            List<Row> rows = new ArrayList<>();
            for (PlayerData p : players) {
                Double v = b.metric().apply(p);
                if (v != null && (b.keepZero() || v > 0)) rows.add(new Row(p, v));
            }
            rows.sort(b.ascending() ? Comparator.comparingDouble(Row::v) : Comparator.comparingDouble(Row::v).reversed());

            int rank = 0;
            for (int i = 0; i < rows.size(); i++) {
                if (i == 0 || rows.get(i).v() != rows.get(i - 1).v()) rank = i + 1; // ties share a rank
                out.computeIfAbsent(rows.get(i).p().uuid(), k -> new ArrayList<>())
                        .add(new Placement(b.title(), rank, rows.size(), b.format().apply(rows.get(i).v())));
            }
        }
        out.values().forEach(l -> l.sort(Comparator.comparingInt(Placement::rank)
                .thenComparingDouble(pl -> pl.rank() / (double) pl.total())));
        return out;
    }

    private static String num(double v) {
        return String.format("%,d", (long) v);
    }

    private static String time(double ticks) {
        long s = (long) ticks / 20, h = s / 3600, m = s % 3600 / 60;
        return h > 0 ? h + "h " + m + "m" : m + "m";
    }

    private static String dist(double cm) {
        double m = cm / 100;
        return m >= 1000 ? String.format("%.1f km", m / 1000) : Math.round(m) + " m";
    }

    private static String hearts(double v) {
        return "\u2764 " + num(Math.round(v / 20));
    }
}
