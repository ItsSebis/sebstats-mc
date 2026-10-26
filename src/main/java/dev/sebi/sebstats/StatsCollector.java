package dev.sebi.sebstats;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/** Reads player statistics on the main thread and publishes them as a JSON snapshot. */
final class StatsCollector implements Runnable {
    private static final long OFFLINE_REFRESH_MS = 60_000;
    private static final int OFFLINE_BUDGET_PER_RUN = 3;

    private static final String[] UNTYPED_STATS = {
        "PLAY_ONE_MINUTE", "TOTAL_WORLD_TIME", "DEATHS", "MOB_KILLS", "PLAYER_KILLS", "DAMAGE_DEALT",
        "DAMAGE_TAKEN", "JUMP", "WALK_ONE_CM", "SPRINT_ONE_CM", "CROUCH_ONE_CM", "SWIM_ONE_CM",
        "AVIATE_ONE_CM", "BOAT_ONE_CM", "MINECART_ONE_CM", "HORSE_ONE_CM", "PIG_ONE_CM",
        "STRIDER_ONE_CM", "CLIMB_ONE_CM", "FALL_ONE_CM", "FISH_CAUGHT", "ANIMALS_BRED",
        "TRADED_WITH_VILLAGER", "TALKED_TO_VILLAGER", "SLEEP_IN_BED", "CHEST_OPENED", "ITEM_ENCHANTED",
        "CAKE_SLICES_EATEN", "RAID_WIN", "LEAVE_GAME", "TIME_SINCE_DEATH",
    };
    private static final String[] TRAVEL_STATS = {
        "walk_one_cm", "sprint_one_cm", "crouch_one_cm", "swim_one_cm", "aviate_one_cm",
        "boat_one_cm", "minecart_one_cm", "horse_one_cm", "pig_one_cm", "strider_one_cm",
    };

    private static final List<Statistic> UNTYPED = new ArrayList<>();
    private static final List<Material> BLOCKS = new ArrayList<>();
    private static final List<Material> ITEMS = new ArrayList<>();
    private static final List<EntityType> MOBS = new ArrayList<>();

    static {
        for (String name : UNTYPED_STATS) {
            try {
                Statistic s = Statistic.valueOf(name);
                if (s.getType() == Statistic.Type.UNTYPED) UNTYPED.add(s);
            } catch (IllegalArgumentException ignored) {
                // statistic doesn't exist in this version
            }
        }
        for (Material m : Material.values()) {
            if (m.isLegacy() || m.isAir()) continue;
            if (m.isBlock()) BLOCKS.add(m);
            if (m.isItem()) ITEMS.add(m);
        }
        for (EntityType t : EntityType.values()) {
            Class<?> c = t.getEntityClass();
            if (c != null && LivingEntity.class.isAssignableFrom(c)
                    && t != EntityType.PLAYER && t != EntityType.ARMOR_STAND) {
                MOBS.add(t);
            }
        }
    }

    private record PlayerData(UUID uuid, String name, boolean onlineWhenRead, long readAt,
                              Map<String, Long> stats, Map<String, Map<String, Long>> collections) {
        long stat(String key) {
            return stats.getOrDefault(key, 0L);
        }

        long sum(String collection) {
            return collections.getOrDefault(collection, Map.of()).values().stream().mapToLong(Long::longValue).sum();
        }

        long entry(String collection, String... keys) {
            Map<String, Long> c = collections.getOrDefault(collection, Map.of());
            long total = 0;
            for (String k : keys) total += c.getOrDefault(k, 0L);
            return total;
        }
    }

    private final Gson gson = new Gson();
    private final Map<UUID, PlayerData> cache = new HashMap<>();
    private final AtomicReference<String> json = new AtomicReference<>(
            "{\"updated\":0,\"online\":0,\"players\":[],\"facts\":[]}");

    String json() {
        return json.get();
    }

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        Set<UUID> online = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).collect(Collectors.toSet());

        for (Player p : Bukkit.getOnlinePlayers()) cache.put(p.getUniqueId(), read(p, true, now));

        int budget = OFFLINE_BUDGET_PER_RUN;
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            UUID id = op.getUniqueId();
            if (online.contains(id) || op.getName() == null) continue;
            PlayerData old = cache.get(id);
            boolean due = old == null || old.onlineWhenRead() || now - old.readAt() > OFFLINE_REFRESH_MS;
            if (due && budget > 0) {
                cache.put(id, read(op, false, now));
                budget--;
            }
        }
        publish(online, now);
    }

    private PlayerData read(OfflinePlayer p, boolean online, long now) {
        Map<String, Long> stats = new LinkedHashMap<>();
        for (Statistic s : UNTYPED) {
            long v = p.getStatistic(s);
            if (v != 0) stats.put(s.name().toLowerCase(), v);
        }
        long travel = 0;
        for (String k : TRAVEL_STATS) travel += stats.getOrDefault(k, 0L);
        if (travel > 0) stats.put("travel_cm", travel);

        Map<String, Map<String, Long>> collections = new LinkedHashMap<>();
        collections.put("mined", collect(p, Statistic.MINE_BLOCK, BLOCKS));
        collections.put("crafted", collect(p, Statistic.CRAFT_ITEM, ITEMS));
        collections.put("picked_up", collect(p, Statistic.PICKUP, ITEMS));
        collections.put("used", collect(p, Statistic.USE_ITEM, ITEMS));
        collections.put("broken", collect(p, Statistic.BREAK_ITEM, ITEMS));
        collections.put("killed", collectMobs(p, Statistic.KILL_ENTITY));
        collections.put("killed_by", collectMobs(p, Statistic.ENTITY_KILLED_BY));
        return new PlayerData(p.getUniqueId(), p.getName(), online, now, stats, collections);
    }

    private static Map<String, Long> collect(OfflinePlayer p, Statistic stat, List<Material> materials) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Material m : materials) {
            try {
                int v = p.getStatistic(stat, m);
                if (v > 0) out.put(m.getKey().getKey(), (long) v);
            } catch (IllegalArgumentException ignored) {
                // material not applicable to this statistic
            }
        }
        return out;
    }

    private static Map<String, Long> collectMobs(OfflinePlayer p, Statistic stat) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (EntityType t : MOBS) {
            try {
                int v = p.getStatistic(stat, t);
                if (v > 0) out.put(t.getKey().getKey(), (long) v);
            } catch (IllegalArgumentException ignored) {
                // entity type not applicable to this statistic
            }
        }
        return out;
    }

    private void publish(Set<UUID> online, long now) {
        List<PlayerData> players = new ArrayList<>(cache.values());
        players.sort(Comparator.comparingLong((PlayerData p) -> p.stat("play_one_minute")).reversed());

        List<Map<String, Object>> playerJson = new ArrayList<>();
        for (PlayerData p : players) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("uuid", p.uuid().toString());
            o.put("name", p.name());
            o.put("online", online.contains(p.uuid()));
            o.put("stats", p.stats());
            o.put("collections", p.collections());
            playerJson.add(o);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("updated", now);
        root.put("online", online.size());
        root.put("catalog", Map.of("mined", BLOCKS.size(), "crafted", ITEMS.size(), "killed", MOBS.size()));
        root.put("players", playerJson);
        root.put("facts", FunFacts.generate(players));
        json.set(gson.toJson(root));
    }

    /** Builds the fun-fact cards from the current snapshot. */
    private static final class FunFacts {
        private static final String[] TRIVIA = {
            "A full Minecraft day lasts 20 real-life minutes.",
            "Creepers were born from a bug while Notch was trying to make a pig: he mixed up the height and width.",
            "Endermen can't see you if you wear a carved pumpkin on your head.",
            "A single ender pearl thrown at the right angle can save you from a long fall. Or end you faster.",
            "Cats scare away creepers. Phantoms are scared of cats too.",
        };

        static List<Map<String, String>> generate(List<PlayerData> ps) {
            List<Map<String, String>> facts = new ArrayList<>();
            if (ps.isEmpty()) {
                facts.add(fact("👋", "Nobody has played yet. Join the server and be the first on the board!"));
            } else {
                add(facts, "⏱️", ps, p -> p.stat("play_one_minute"),
                        (p, v) -> p.name() + " has spent " + hours(v / 20.0) + " in the world, the server's most dedicated player.");
                add(facts, "💀", ps, p -> p.stat("deaths"),
                        (p, v) -> p.name() + " has died " + n(v) + " times, " + perHour(v, p.stat("play_one_minute"))
                                + " deaths per hour. Clumsiest around.");
                add(facts, "⛏️", ps, p -> p.sum("mined"),
                        (p, v) -> p.name() + " has mined " + n(v) + " blocks. That's " + n(v / 64) + " full stacks.");
                add(facts, "💎", ps, p -> p.entry("mined", "diamond_ore", "deepslate_diamond_ore"),
                        (p, v) -> p.name() + " has dug up " + n(v) + " diamond ore. Shiny.");
                add(facts, "🏃", ps, p -> p.stat("travel_cm"),
                        (p, v) -> p.name() + " has travelled " + km(v) + " km, about " + String.format("%.1f", v / 100.0 / 42195)
                                + " marathons.");
                add(facts, "🦘", ps, p -> p.stat("jump"),
                        (p, v) -> p.name() + " has jumped " + n(v) + " times. Stacked up, that's "
                                + String.format("%.2f", v * 1.25 / 8849) + "× the height of Mount Everest.");
                add(facts, "🪂", ps, p -> p.stat("aviate_one_cm"),
                        (p, v) -> p.name() + " has glided " + km(v) + " km on elytra.");
                add(facts, "💥", ps, p -> p.entry("killed", "creeper"),
                        (p, v) -> p.name() + " has defused " + n(v) + " creepers. The creepers did not ask for this.");
                add(facts, "🧟", ps, p -> p.entry("killed", "zombie", "zombie_villager", "husk", "drowned"),
                        (p, v) -> p.name() + " has put " + n(v) + " zombie-types back in the ground.");
                add(facts, "🎣", ps, p -> p.stat("fish_caught"),
                        (p, v) -> p.name() + " has caught " + n(v) + " things on a fishing rod. Some of it was even fish.");
                add(facts, "🛠️", ps, p -> p.sum("crafted"),
                        (p, v) -> p.name() + " has crafted " + n(v) + " items. Crafting table's best friend.");
                add(facts, "🩸", ps, p -> p.stat("damage_taken"),
                        (p, v) -> p.name() + " has absorbed " + n(v / 20) + " hearts' worth of damage. Human punching bag.");
                add(facts, "🛏️", ps, p -> p.stat("sleep_in_bed"),
                        (p, v) -> p.name() + " has slept in a bed " + n(v) + " times. Well rested.");
                add(facts, "🐄", ps, p -> p.stat("animals_bred"),
                        (p, v) -> p.name() + " has bred " + n(v) + " animals. The farm is thriving.");
                add(facts, "🤝", ps, p -> p.stat("traded_with_villager"),
                        (p, v) -> p.name() + " has traded with villagers " + n(v) + " times. Hrmm.");

                long blocks = ps.stream().mapToLong(p -> p.sum("mined")).sum();
                long travel = ps.stream().mapToLong(p -> p.stat("travel_cm")).sum();
                long time = ps.stream().mapToLong(p -> p.stat("play_one_minute")).sum();
                if (blocks > 0) {
                    facts.add(fact("🌍", "Together you've mined " + n(blocks) + " blocks, enough to fill "
                            + n(Math.max(1, blocks / 1728)) + " chests, stack by stack."));
                }
                if (travel > 0) {
                    facts.add(fact("🧭", "Combined, the server has travelled " + km(travel) + " km, "
                            + String.format("%.3f", travel / 100.0 / 1000 / 40075 * 100) + "% of the way around the real Earth."));
                }
                if (time > 0) {
                    facts.add(fact("⌛", "Total time played across all players: " + hours(time / 20.0) + "."));
                }
                topEntry(ps, "killed").ifPresent(e -> facts.add(fact("🗡️",
                        "The most-killed mob on the server is the " + pretty(e.getKey()) + " (" + n(e.getValue()) + " dead).")));
                topEntry(ps, "mined").ifPresent(e -> facts.add(fact("🪨",
                        "The most-mined block is " + pretty(e.getKey()) + " (" + n(e.getValue()) + " broken).")));
            }
            facts.add(fact("📚", TRIVIA[(int) (System.currentTimeMillis() / 3_600_000 % TRIVIA.length)]));
            return facts;
        }

        private interface Describe {
            String of(PlayerData p, long value);
        }

        private static void add(List<Map<String, String>> out, String icon, List<PlayerData> ps,
                                ToLongFunction<PlayerData> metric, Describe text) {
            Optional<PlayerData> best = ps.stream().filter(p -> metric.applyAsLong(p) > 0)
                    .max(Comparator.comparingLong(metric));
            best.ifPresent(p -> out.add(fact(icon, text.of(p, metric.applyAsLong(p)))));
        }

        private static Optional<Map.Entry<String, Long>> topEntry(List<PlayerData> ps, String collection) {
            Map<String, Long> totals = new HashMap<>();
            for (PlayerData p : ps) p.collections().getOrDefault(collection, Map.of()).forEach((k, v) -> totals.merge(k, v, Long::sum));
            return totals.entrySet().stream().max(Map.Entry.comparingByValue());
        }

        private static Map<String, String> fact(String icon, String text) {
            return Map.of("icon", icon, "text", text);
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

        private static String pretty(String key) {
            return java.util.Arrays.stream(key.split("_"))
                    .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1))
                    .collect(Collectors.joining(" "));
        }
    }
}
