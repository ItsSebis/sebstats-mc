package dev.sebi.sebstats;

import dev.sebi.sebstats.StatsCollector.PlayerData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.ToLongFunction;

/**
 * Fun facts built from the current snapshot.
 *
 * <p>A {@link Pool} has the {@code base} facts (records, server totals, one trivia line) that the website
 * always shows, and a larger {@code extra} set (comparisons between players, rates, rivalries) of which the
 * website shows a sample that changes hourly. The server-list MOTD draws from the stat-based ones.
 */
final class FunFacts {
    record Fact(String icon, String text) {}

    record Pool(List<Fact> base, List<Fact> extra) {
        List<Fact> all() {
            List<Fact> all = new ArrayList<>(base);
            all.addAll(extra);
            return all;
        }
    }

    private static final String TRIVIA_ICON = "📚";
    private static final int WEBSITE_EXTRAS = 6;
    /** Comparisons only look at the most active players, so the pairs stay manageable on big servers. */
    private static final int COMPARED_PLAYERS = 20;
    /** The server list shows two lines of about 45 characters. */
    private static final int MOTD_MAX_LENGTH = 90;
    private static final double MIN_RATIO = 1.5;

    private static final String[] TRIVIA = {
        "A full Minecraft day lasts 20 real-life minutes.",
        "Creepers were born from a bug while Notch was trying to make a pig: he mixed up the height and width.",
        "Endermen can't see you if you wear a carved pumpkin on your head.",
        "A single ender pearl thrown at the right angle can save you from a long fall. Or end you faster.",
        "Cats scare away creepers. Phantoms are scared of cats too.",
        "A bed explodes in the Nether and the End, so don't sleep there.",
        "Axolotls play dead when hurt, and it actually heals them.",
        "Iron golems give poppies to villager kids. Villagers are suspiciously fine with it.",
        "Lightning turns pigs into zombified piglins and villagers into witches.",
        "Phantoms only show up if you've gone three in-game days without sleeping.",
    };

    /** One metric that two players can be compared on; {@code phrase} takes (leader, ratio, other). */
    private record Comparison(String icon, ToLongFunction<PlayerData> value, long min, String phrase) {}

    private static final List<Comparison> COMPARISONS = List.of(
        new Comparison("💀", p -> p.stat("deaths"), 3, "%s has died %s× as often as %s."),
        new Comparison("⛏️", p -> p.sum("mined"), 100, "%s has mined %s× as many blocks as %s."),
        new Comparison("🛠️", p -> p.sum("crafted"), 100, "%s has crafted %s× as many items as %s."),
        new Comparison("🏃", p -> p.stat("travel_cm"), 100_000, "%s has travelled %s× as far as %s."),
        new Comparison("🦘", p -> p.stat("jump"), 100, "%s has jumped %s× as often as %s."),
        new Comparison("⏱️", p -> p.stat("play_one_minute"), 72_000, "%s has played %s× as long as %s."),
        new Comparison("🩸", p -> p.stat("damage_taken"), 200, "%s has taken %s× as much damage as %s."),
        new Comparison("🎣", p -> p.stat("fish_caught"), 5, "%s has caught %s× as many fish as %s."),
        new Comparison("🗡️", p -> p.stat("mob_kills"), 10, "%s has killed %s× as many mobs as %s."),
        new Comparison("🛏️", p -> p.stat("sleep_in_bed"), 3, "%s has slept %s× as often as %s."));

    /** Players are expected most active first. */
    static Pool pool(List<PlayerData> ps) {
        List<Fact> base = new ArrayList<>();
        List<Fact> extra = new ArrayList<>();
        if (ps.isEmpty()) {
            base.add(new Fact("👋", "Nobody has played yet. Join the server and be the first on the board!"));
        } else {
            records(ps, base);
            totals(ps, base);
            comparisons(ps, extra);
            perPlayer(ps, extra);
            rivalries(ps, extra);
            serverWide(ps, extra);
        }
        base.add(new Fact(TRIVIA_ICON, TRIVIA[(int) (System.currentTimeMillis() / 3_600_000 % TRIVIA.length)]));
        for (String t : TRIVIA) extra.add(new Fact(TRIVIA_ICON, t));
        return new Pool(base, extra);
    }

    /** The website cards: all base facts plus a sample of the extras that stays put for an hour. */
    static List<Map<String, String>> website(Pool pool) {
        List<Fact> extras = new ArrayList<>(pool.extra());
        Collections.shuffle(extras, new Random(System.currentTimeMillis() / 3_600_000));
        List<Fact> shown = new ArrayList<>(pool.base());
        shown.addAll(extras.subList(0, Math.min(WEBSITE_EXTRAS, extras.size())));
        return shown.stream().map(f -> Map.of("icon", f.icon(), "text", f.text())).toList();
    }

    /**
     * Texts short enough for the server list. Only facts about the players' stats; general Minecraft trivia
     * is a last resort for when there is nothing else to show.
     */
    static List<String> motd(Pool pool) {
        List<Fact> shortEnough = pool.all().stream().filter(f -> f.text().length() <= MOTD_MAX_LENGTH).toList();
        List<String> stats = shortEnough.stream().filter(f -> !f.icon().equals(TRIVIA_ICON)).map(Fact::text).toList();
        return stats.isEmpty() ? shortEnough.stream().map(Fact::text).toList() : stats;
    }

    // ---- base: the top player per stat, and server totals --------------------------------------------

    private static void records(List<PlayerData> ps, List<Fact> out) {
        add(out, "⏱️", ps, p -> p.stat("play_one_minute"),
                (p, v) -> p.name() + " has spent " + hours(v / 20.0) + " in the world, the server's most dedicated player.");
        add(out, "💀", ps, p -> p.stat("deaths"),
                (p, v) -> p.name() + " has died " + n(v) + " times, " + perHour(v, p.stat("play_one_minute"))
                        + " deaths per hour. Clumsiest around.");
        add(out, "⛏️", ps, p -> p.sum("mined"),
                (p, v) -> p.name() + " has mined " + n(v) + " blocks. That's " + n(v / 64) + " full stacks.");
        add(out, "💎", ps, p -> p.entry("mined", "diamond_ore", "deepslate_diamond_ore"),
                (p, v) -> p.name() + " has dug up " + n(v) + " diamond ore. Shiny.");
        add(out, "🏃", ps, p -> p.stat("travel_cm"),
                (p, v) -> p.name() + " has travelled " + km(v) + " km, about " + String.format("%.1f", v / 100.0 / 42195)
                        + " marathons.");
        add(out, "🦘", ps, p -> p.stat("jump"),
                (p, v) -> p.name() + " has jumped " + n(v) + " times. Stacked up, that's "
                        + String.format("%.2f", v * 1.25 / 8849) + "× the height of Mount Everest.");
        add(out, "🪂", ps, p -> p.stat("aviate_one_cm"),
                (p, v) -> p.name() + " has glided " + km(v) + " km on elytra.");
        add(out, "💥", ps, p -> p.entry("killed", "creeper"),
                (p, v) -> p.name() + " has defused " + n(v) + " creepers. The creepers did not ask for this.");
        add(out, "🧟", ps, p -> p.entry("killed", "zombie", "zombie_villager", "husk", "drowned"),
                (p, v) -> p.name() + " has put " + n(v) + " zombie-types back in the ground.");
        add(out, "🎣", ps, p -> p.stat("fish_caught"),
                (p, v) -> p.name() + " has caught " + n(v) + " things on a fishing rod. Some of it was even fish.");
        add(out, "🛠️", ps, p -> p.sum("crafted"),
                (p, v) -> p.name() + " has crafted " + n(v) + " items. Crafting table's best friend.");
        add(out, "🩸", ps, p -> p.stat("damage_taken"),
                (p, v) -> p.name() + " has absorbed " + n(v / 20) + " hearts' worth of damage. Human punching bag.");
        add(out, "🛏️", ps, p -> p.stat("sleep_in_bed"),
                (p, v) -> p.name() + " has slept in a bed " + n(v) + " times. Well rested.");
        add(out, "🐄", ps, p -> p.stat("animals_bred"),
                (p, v) -> p.name() + " has bred " + n(v) + " animals. The farm is thriving.");
        add(out, "🤝", ps, p -> p.stat("traded_with_villager"),
                (p, v) -> p.name() + " has traded with villagers " + n(v) + " times. Hrmm.");
    }

    private static void totals(List<PlayerData> ps, List<Fact> out) {
        long blocks = ps.stream().mapToLong(p -> p.sum("mined")).sum();
        long travel = ps.stream().mapToLong(p -> p.stat("travel_cm")).sum();
        long time = ps.stream().mapToLong(p -> p.stat("play_one_minute")).sum();
        if (blocks > 0) {
            out.add(new Fact("🌍", "Together you've mined " + n(blocks) + " blocks, enough to fill "
                    + n(Math.max(1, blocks / 1728)) + " chests, stack by stack."));
        }
        if (travel > 0) {
            out.add(new Fact("🧭", "Combined, the server has travelled " + km(travel) + " km, "
                    + String.format("%.3f", travel / 100.0 / 1000 / 40075 * 100) + "% of the way around the real Earth."));
        }
        if (time > 0) {
            out.add(new Fact("⌛", "Total time played across all players: " + hours(time / 20.0) + "."));
        }
        topEntry(ps, "killed").ifPresent(e -> out.add(new Fact("🗡️",
                "The most-killed mob on the server is the " + pretty(e.getKey()) + " (" + n(e.getValue()) + " dead).")));
        topEntry(ps, "mined").ifPresent(e -> out.add(new Fact("🪨",
                "The most-mined block is " + pretty(e.getKey()) + " (" + n(e.getValue()) + " broken).")));
    }

    // ---- extra: comparisons, rates, rivalries ----------------------------------------------------------

    /** "Sebi has died 2.5× as often as Eliya" for every pair and metric where the gap is clear. */
    private static void comparisons(List<PlayerData> ps, List<Fact> out) {
        List<PlayerData> top = ps.subList(0, Math.min(COMPARED_PLAYERS, ps.size()));
        for (Comparison c : COMPARISONS) {
            for (int i = 0; i < top.size(); i++) {
                for (int j = i + 1; j < top.size(); j++) {
                    PlayerData a = top.get(i), b = top.get(j);
                    long va = c.value().applyAsLong(a), vb = c.value().applyAsLong(b);
                    if (va < vb) {
                        PlayerData t = a; a = b; b = t;
                        long tv = va; va = vb; vb = tv;
                    }
                    if (vb < c.min() || va < vb * MIN_RATIO) continue;
                    out.add(new Fact(c.icon(), String.format(c.phrase(), a.name(), ratio(va, vb), b.name())));
                }
            }
        }
    }

    private static void perPlayer(List<PlayerData> ps, List<Fact> out) {
        for (PlayerData p : ps) {
            long ticks = p.stat("play_one_minute"), deaths = p.stat("deaths"), mined = p.sum("mined");
            if (deaths > 0 && ticks >= 72_000) {
                out.add(new Fact("💀", p.name() + " dies about every " + duration(ticks / deaths) + "."));
            }
            if (mined >= 100 && ticks >= 72_000) {
                out.add(new Fact("⛏️", p.name() + " mines about " + n(Math.round(mined / (ticks / 72_000.0)))
                        + " blocks per hour."));
            }
            long alive = p.stat("time_since_death");
            if (alive >= 72_000) {
                out.add(new Fact("🛡️", p.name() + " hasn't died in " + duration(alive) + "."));
            }
            long slept = p.stat("sleep_in_bed");
            if (slept > 0 && deaths > slept) {
                out.add(new Fact("🛏️", p.name() + " has died " + n(deaths) + " times but slept only " + n(slept) + "."));
            }
            top(p, "killed_by").ifPresent(e -> out.add(new Fact("☠️", p.name() + " has been killed by "
                    + pretty(e.getKey()) + " " + n(e.getValue()) + " times.")));
            long totems = p.entry("used", "totem_of_undying");
            if (totems > 0) {
                out.add(new Fact("🗿", p.name() + " has popped " + n(totems) + " totems of undying."));
            }
            long broken = p.sum("broken");
            if (broken > 0) {
                out.add(new Fact("🔨", p.name() + " has worn out " + n(broken) + " tools and armor pieces."));
            }
            long hours = ticks / 72_000;
            int next = Milestones.nextMilestone(hours);
            if (hours >= 50 && next - hours <= 25) {
                out.add(new Fact("🎆", p.name() + " is " + (next - hours) + "h away from " + n(next)
                        + " hours played. Fireworks incoming."));
            }
        }
    }

    private static void rivalries(List<PlayerData> ps, List<Fact> out) {
        if (ps.size() < 2) return;
        PlayerData first = ps.get(0), second = ps.get(1);
        long gap = first.stat("play_one_minute") - second.stat("play_one_minute");
        if (gap > 0) {
            out.add(new Fact("🏁", second.name() + " is " + duration(gap) + " behind " + first.name()
                    + " in play time."));
        }
        List<PlayerData> byDeaths = new ArrayList<>(ps);
        byDeaths.sort(Comparator.comparingLong((PlayerData p) -> p.stat("deaths")).reversed());
        long diff = byDeaths.get(0).stat("deaths") - byDeaths.get(1).stat("deaths");
        if (byDeaths.get(1).stat("deaths") > 0 && diff > 0 && diff <= 10) {
            out.add(new Fact("🥇", byDeaths.get(1).name() + " is " + n(diff) + " death" + (diff == 1 ? "" : "s")
                    + " from overtaking " + byDeaths.get(0).name() + " as the clumsiest."));
        }
        List<PlayerData> byAdv = new ArrayList<>(ps);
        byAdv.sort(Comparator.comparingInt((PlayerData p) -> p.advancements().size()).reversed());
        PlayerData a = byAdv.get(0), b = byAdv.get(1);
        if (b.advancements().size() > 0 && a.advancements().size() > b.advancements().size()) {
            out.add(new Fact("🏆", a.name() + " has " + a.advancements().size() + " advancements, "
                    + b.name() + " has " + b.advancements().size() + "."));
        }
    }

    private static void serverWide(List<PlayerData> ps, List<Fact> out) {
        if (ps.size() < 2) return;
        long blocks = ps.stream().mapToLong(p -> p.sum("mined")).sum();
        long time = ps.stream().mapToLong(p -> p.stat("play_one_minute")).sum();
        PlayerData miner = ps.stream().max(Comparator.comparingLong(p -> p.sum("mined"))).orElseThrow();
        if (blocks > 0 && miner.sum("mined") * 4 >= blocks) {
            out.add(new Fact("⛏️", miner.name() + " mined " + percent(miner.sum("mined"), blocks)
                    + " of all blocks on the server."));
        }
        if (time > 0 && ps.get(0).stat("play_one_minute") * 4 >= time) {
            out.add(new Fact("⏱️", ps.get(0).name() + " accounts for " + percent(ps.get(0).stat("play_one_minute"), time)
                    + " of all hours played."));
        }
        topEntry(ps, "killed_by").ifPresent(e -> out.add(new Fact("☠️", "The server's top killer is the "
                + pretty(e.getKey()) + " (" + n(e.getValue()) + " deaths).")));
        long deaths = ps.stream().mapToLong(p -> p.stat("deaths")).sum();
        if (deaths > 0) {
            out.add(new Fact("🪦", "The server has died " + n(deaths) + " times in total."));
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private interface Describe {
        String of(PlayerData p, long value);
    }

    private static void add(List<Fact> out, String icon, List<PlayerData> ps, ToLongFunction<PlayerData> metric,
                            Describe text) {
        Optional<PlayerData> best = ps.stream().filter(p -> metric.applyAsLong(p) > 0)
                .max(Comparator.comparingLong(metric));
        best.ifPresent(p -> out.add(new Fact(icon, text.of(p, metric.applyAsLong(p)))));
    }

    private static Optional<Map.Entry<String, Long>> topEntry(List<PlayerData> ps, String collection) {
        Map<String, Long> totals = new HashMap<>();
        for (PlayerData p : ps) p.collections().getOrDefault(collection, Map.of()).forEach((k, v) -> totals.merge(k, v, Long::sum));
        return totals.entrySet().stream().max(Map.Entry.comparingByValue());
    }

    private static Optional<Map.Entry<String, Long>> top(PlayerData p, String collection) {
        return p.collections().getOrDefault(collection, Map.of()).entrySet().stream()
                .max(Map.Entry.comparingByValue());
    }

    /** "diamond_ore" as "Diamond Ore". */
    static String pretty(String key) {
        return java.util.Arrays.stream(key.split("_"))
                .filter(w -> !w.isEmpty())
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1))
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private static String ratio(long a, long b) {
        double r = a / (double) b;
        return r >= 10 ? String.format("%.0f", r) : String.format("%.1f", r);
    }

    private static String percent(long part, long whole) {
        return Math.round(part * 100.0 / whole) + "%";
    }

    /** A tick count as "47 minutes", "3h 12m" or "5h". */
    private static String duration(long ticks) {
        long minutes = ticks / 1200;
        if (minutes < 60) return Math.max(1, minutes) + " minutes";
        long h = minutes / 60, m = minutes % 60;
        return m == 0 ? h + "h" : h + "h " + m + "m";
    }

    private static String n(long v) {
        return String.format("%,d", v);
    }

    private static String km(long cm) {
        return String.format("%,.1f", cm / 100000.0);
    }

    private static String hours(double seconds) {
        return String.format("%,.1f hours", seconds / 3600);
    }

    private static String perHour(long count, long ticks) {
        return ticks <= 0 ? "-" : String.format("%.2f", count / (ticks / 72000.0));
    }
}
