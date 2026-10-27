package dev.sebi.sebstats;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import io.papermc.paper.advancement.AdvancementDisplay;
import io.papermc.paper.text.PaperComponents;
import net.kyori.adventure.text.Component;

/**
 * Publishes player statistics as a JSON snapshot.
 *
 * <p>The bulk of the work is off the main thread: {@link #rebuild()} parses the vanilla stats files
 * (only when their mtime changes) and builds the JSON. The main-thread {@link #tick()} only refreshes
 * names and overlays live values for online players, limited to keys already present in their file
 * and to a small time budget per tick, because Bukkit's statistic API is slow to scan exhaustively.
 */
final class StatsCollector {
    private static final long OVERLAY_BUDGET_NANOS = 2_000_000;
    private static final int NAMES_PER_TICK = 25;
    private static final long NAME_RETRY_MS = 60_000;

    private static final String[] UNTYPED_STATS = {
        "PLAY_ONE_MINUTE", "TOTAL_WORLD_TIME", "DEATHS", "MOB_KILLS", "PLAYER_KILLS", "DAMAGE_DEALT",
        "DAMAGE_TAKEN", "JUMP", "WALK_ONE_CM", "SPRINT_ONE_CM", "CROUCH_ONE_CM", "SWIM_ONE_CM",
        "AVIATE_ONE_CM", "BOAT_ONE_CM", "MINECART_ONE_CM", "HORSE_ONE_CM", "PIG_ONE_CM",
        "STRIDER_ONE_CM", "CLIMB_ONE_CM", "FALL_ONE_CM", "FISH_CAUGHT", "ANIMALS_BRED",
        "TRADED_WITH_VILLAGER", "TALKED_TO_VILLAGER", "SLEEP_IN_BED", "CHEST_OPENED", "ITEM_ENCHANTED",
        "CAKE_SLICES_EATEN", "RAID_WIN", "LEAVE_GAME", "TIME_SINCE_DEATH",
    };
    /** Vanilla stats-file keys that differ from the (Bukkit-derived) keys exposed by the API. */
    private static final Map<String, String> RENAMED = Map.of(
        "play_time", "play_one_minute", "open_chest", "chest_opened",
        "enchant_item", "item_enchanted", "eat_cake_slice", "cake_slices_eaten");
    private static final String[] TRAVEL_STATS = {
        "walk_one_cm", "sprint_one_cm", "crouch_one_cm", "swim_one_cm", "aviate_one_cm",
        "boat_one_cm", "minecart_one_cm", "horse_one_cm", "pig_one_cm", "strider_one_cm",
    };
    private static final List<String> COLLECTIONS =
            List.of("mined", "crafted", "picked_up", "used", "broken", "killed", "killed_by");
    private static final Map<String, Statistic> COLLECTION_STAT = Map.of(
        "mined", Statistic.MINE_BLOCK, "crafted", Statistic.CRAFT_ITEM, "picked_up", Statistic.PICKUP,
        "used", Statistic.USE_ITEM, "broken", Statistic.BREAK_ITEM, "killed", Statistic.KILL_ENTITY,
        "killed_by", Statistic.ENTITY_KILLED_BY);

    private static final List<Statistic> UNTYPED = new ArrayList<>();
    private static final Set<String> UNTYPED_KEYS = new HashSet<>();
    private static final Map<String, Material> BLOCKS = new HashMap<>();
    private static final Map<String, Material> ITEMS = new HashMap<>();
    private static final Map<String, EntityType> MOBS = new HashMap<>();

    static {
        for (String name : UNTYPED_STATS) {
            try {
                Statistic s = Statistic.valueOf(name);
                if (s.getType() == Statistic.Type.UNTYPED) {
                    UNTYPED.add(s);
                    UNTYPED_KEYS.add(name.toLowerCase());
                }
            } catch (IllegalArgumentException ignored) {
                // statistic doesn't exist in this version
            }
        }
        for (Material m : Material.values()) {
            if (m.isLegacy() || m.isAir()) continue;
            if (m.isBlock()) BLOCKS.put(m.getKey().getKey(), m);
            if (m.isItem()) ITEMS.put(m.getKey().getKey(), m);
        }
        for (EntityType t : EntityType.values()) {
            Class<?> c = t.getEntityClass();
            if (c != null && LivingEntity.class.isAssignableFrom(c)
                    && t != EntityType.PLAYER && t != EntityType.ARMOR_STAND) {
                MOBS.put(t.getKey().getKey(), t);
            }
        }
    }

    record PlayerData(UUID uuid, String name, boolean online, Map<String, Long> stats,
                              Map<String, Map<String, Long>> collections, Map<String, Long> advancements) {
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

    /** What a player's stats file said when it was last parsed. */
    private record FileData(long mtime, Map<String, Long> stats, Map<String, Map<String, Long>> collections) {
        static final FileData EMPTY = new FileData(0, Map.of(), Map.of());
    }

    /** Live values for an online player, covering the same keys as the file plus all untyped stats. */
    private record Overlay(Map<String, Long> stats, Map<String, Map<String, Long>> collections,
                           Map<String, Long> advancements) {}

    /** What a player's advancements file said when it was last parsed: id to epoch millis earned (0 = unknown). */
    private record AdvFile(long mtime, Map<String, Long> earned) {}

    /** A displayable advancement. Recipes and other display-less ones are not tracked. */
    private record AdvDef(Advancement advancement, String id, Map<String, Object> json) {}

    private static final DateTimeFormatter ADV_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");

    private final Gson gson = new Gson();
    private final Path statsDir;
    private final Path advancementsDir;
    private final Map<UUID, AdvFile> advFiles = new ConcurrentHashMap<>();
    private volatile List<AdvDef> advCatalog = List.of();
    private final Map<UUID, FileData> files = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nameRetryAt = new HashMap<>();
    private final Map<UUID, Overlay> overlays = new ConcurrentHashMap<>();
    private volatile Set<UUID> online = Set.of();
    private volatile Map<UUID, List<Boards.Placement>> placements = Map.of();
    private final AtomicLong changes = new AtomicLong(1);
    private long builtAt = 0;
    private int cursor = 0;
    private final AtomicReference<String> json = new AtomicReference<>(
            "{\"updated\":0,\"online\":0,\"advancements\":[],\"players\":[],\"facts\":[]}");

    /** Must be called on the main thread. */
    StatsCollector() {
        statsDir = findStatsDir();
        advancementsDir = statsDir.resolveSibling("advancements");
        Bukkit.getLogger().info("[SebStats] Reading player stats from " + statsDir);
    }

    String json() {
        return json.get();
    }

    /** The player's leaderboard placings, best first; empty if unranked. */
    List<Boards.Placement> placements(UUID id) {
        return placements.getOrDefault(id, List.of());
    }

    java.util.Optional<UUID> uuidByName(String name) {
        return names.entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(name))
                .map(Map.Entry::getKey).findFirst();
    }

    String nameOf(UUID id) {
        return names.getOrDefault(id, id.toString());
    }

    private static Path findStatsDir() {
        for (World w : Bukkit.getWorlds()) {
            for (Path dir = w.getWorldFolder().toPath().toAbsolutePath(), i = dir; i != null && dir.getNameCount() - i.getNameCount() < 4; i = i.getParent()) {
                for (String sub : new String[] {"players/stats", "stats"}) {
                    Path p = i.resolve(sub);
                    if (Files.isDirectory(p)) return p;
                }
            }
        }
        return Bukkit.getWorldContainer().toPath().toAbsolutePath().resolve("world/players/stats");
    }

    // ---- main thread -------------------------------------------------------------------------------

    /** Cheap main-thread work: names, the online set, and live overlays within a time budget. */
    void tick() {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        Set<UUID> now = players.stream().map(Player::getUniqueId).collect(Collectors.toSet());
        if (!now.equals(online)) {
            online = now;
            overlays.keySet().retainAll(now);
            changes.incrementAndGet();
        }
        for (Player p : players) {
            if (names.put(p.getUniqueId(), p.getName()) == null) changes.incrementAndGet();
        }

        if (advCatalog.isEmpty()) {
            advCatalog = buildCatalog();
            if (!advCatalog.isEmpty()) changes.incrementAndGet();
        }

        int budget = NAMES_PER_TICK;
        long wall = System.currentTimeMillis();
        for (UUID id : files.keySet()) {
            if (budget == 0) break;
            if (names.containsKey(id) || nameRetryAt.getOrDefault(id, 0L) > wall) continue;
            budget--;
            String name = Bukkit.getOfflinePlayer(id).getName();
            if (name != null) {
                names.put(id, name);
                nameRetryAt.remove(id);
                changes.incrementAndGet();
            } else {
                nameRetryAt.put(id, wall + NAME_RETRY_MS); // so unresolvable ids can't starve the rest
            }
        }

        long start = System.nanoTime();
        for (int i = 0; i < players.size(); i++) {
            if (i > 0 && System.nanoTime() - start > OVERLAY_BUDGET_NANOS) {
                cursor = (cursor + i) % players.size();
                return;
            }
            Player p = players.get((cursor + i) % players.size());
            try {
                overlays.put(p.getUniqueId(), readLive(p, files.getOrDefault(p.getUniqueId(), FileData.EMPTY), advCatalog));
                changes.incrementAndGet();
            } catch (RuntimeException e) {
                // fall back to the file data for this player rather than disturbing the tick
            }
        }
    }

    private static Overlay readLive(Player p, FileData base, List<AdvDef> catalog) {
        Map<String, Long> stats = new HashMap<>();
        for (Statistic s : UNTYPED) {
            long v = p.getStatistic(s);
            if (v != 0) stats.put(s.name().toLowerCase(), v);
        }
        Map<String, Map<String, Long>> colls = new HashMap<>();
        for (Map.Entry<String, Map<String, Long>> c : base.collections().entrySet()) {
            Statistic stat = COLLECTION_STAT.get(c.getKey());
            boolean mob = c.getKey().startsWith("killed");
            Map<String, Long> out = new LinkedHashMap<>();
            for (String key : c.getValue().keySet()) {
                try {
                    int v = mob ? p.getStatistic(stat, MOBS.get(key))
                            : p.getStatistic(stat, (c.getKey().equals("mined") ? BLOCKS : ITEMS).get(key));
                    if (v > 0) out.put(key, (long) v);
                } catch (IllegalArgumentException | NullPointerException ignored) {
                    out.put(key, c.getValue().get(key)); // keep the file value if the API rejects the key
                }
            }
            colls.put(c.getKey(), out);
        }
        Map<String, Long> adv = new HashMap<>();
        for (AdvDef def : catalog) {
            AdvancementProgress progress = p.getAdvancementProgress(def.advancement());
            if (!progress.isDone()) continue;
            long earned = 0;
            for (String criterion : progress.getAwardedCriteria()) {
                Date date = progress.getDateAwarded(criterion);
                if (date != null) earned = Math.max(earned, date.getTime());
            }
            adv.put(def.id(), earned);
        }
        return new Overlay(stats, colls, adv);
    }

    private static List<AdvDef> buildCatalog() {
        List<AdvDef> out = new ArrayList<>();
        for (Iterator<Advancement> it = Bukkit.advancementIterator(); it.hasNext();) {
            Advancement a = it.next();
            AdvancementDisplay d = a.getDisplay();
            if (d == null || a.getParent() == null) continue; // recipes, and the root of each tab
            String ns = a.getKey().getNamespace(), path = a.getKey().getKey();
            String id = ns.equals("minecraft") ? path : ns + ":" + path;
            String fallback = pretty(path.substring(path.lastIndexOf('/') + 1));
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", id);
            o.put("category", ns.equals("minecraft") ? path.substring(0, Math.max(0, path.indexOf('/'))) : ns);
            o.put("title", text(d.title(), fallback));
            o.put("description", text(d.description(), ""));
            o.put("icon", d.icon().getType().getKey().getKey());
            o.put("frame", d.frame().name().toLowerCase());
            o.put("hidden", d.isHidden());
            out.add(new AdvDef(a, id, o));
        }
        out.sort(Comparator.comparing((AdvDef d) -> (String) d.json().get("category")).thenComparing(AdvDef::id));
        return out;
    }

    /** Plain text of a component; untranslated keys (e.g. "advancements.x.title") fall back to {@code fallback}. */
    private static String text(Component c, String fallback) {
        String s = PaperComponents.plainTextSerializer().serialize(c);
        return s.isBlank() || s.startsWith("advancements.") ? fallback : s;
    }

    private static String pretty(String key) {
        return java.util.Arrays.stream(key.split("_"))
                .filter(w -> !w.isEmpty())
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1))
                .collect(Collectors.joining(" "));
    }

    // ---- async -------------------------------------------------------------------------------------

    /** Parses changed stats files and rebuilds the snapshot if anything changed. Runs off the main thread. */
    void rebuild() {
        scanFiles();
        long version = changes.get();
        if (version == builtAt) return;
        builtAt = version;

        Set<UUID> online = this.online;
        List<AdvDef> catalog = advCatalog;
        Set<UUID> ids = new HashSet<>(files.keySet());
        ids.addAll(online);

        List<PlayerData> players = new ArrayList<>();
        for (UUID id : ids) {
            String name = names.get(id);
            if (name == null) continue;
            FileData file = files.getOrDefault(id, FileData.EMPTY);
            Overlay live = overlays.get(id);
            Map<String, Long> stats = new LinkedHashMap<>(file.stats());
            Map<String, Map<String, Long>> colls = new LinkedHashMap<>();
            for (String c : COLLECTIONS) colls.put(c, file.collections().getOrDefault(c, Map.of()));
            if (live != null) {
                stats.keySet().removeIf(UNTYPED_KEYS::contains);
                stats.putAll(live.stats());
                live.collections().forEach(colls::put);
            }
            long travel = 0;
            for (String k : TRAVEL_STATS) travel += stats.getOrDefault(k, 0L);
            if (travel > 0) stats.put("travel_cm", travel);
            Map<String, Long> earned = live != null ? live.advancements()
                    : advFiles.getOrDefault(id, new AdvFile(0, Map.of())).earned();
            Map<String, Long> advancements = new LinkedHashMap<>();
            for (AdvDef def : catalog) {
                Long when = earned.get(def.id());
                if (when != null) advancements.put(def.id(), when);
            }
            players.add(new PlayerData(id, name, online.contains(id), stats, colls, advancements));
        }
        publish(players, catalog);
    }

    private void scanFiles() {
        scanAdvancements();
        if (!Files.isDirectory(statsDir)) return;
        Set<UUID> seen = new HashSet<>();
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(statsDir, "*.json")) {
            for (Path f : dir) {
                String fn = f.getFileName().toString();
                UUID id;
                try {
                    id = UUID.fromString(fn.substring(0, fn.length() - 5));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                seen.add(id);
                long mtime = Files.getLastModifiedTime(f).toMillis();
                FileData old = files.get(id);
                if (old != null && old.mtime() == mtime) continue;
                try {
                    files.put(id, parse(f, mtime));
                    changes.incrementAndGet();
                } catch (RuntimeException | IOException e) {
                    // file may be mid-write; try again next scan
                }
            }
        } catch (IOException ignored) {
            return;
        }
        if (files.keySet().retainAll(seen)) changes.incrementAndGet();
    }

    private void scanAdvancements() {
        if (!Files.isDirectory(advancementsDir)) return;
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(advancementsDir, "*.json")) {
            for (Path f : dir) {
                String fn = f.getFileName().toString();
                UUID id;
                try {
                    id = UUID.fromString(fn.substring(0, fn.length() - 5));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                long mtime = Files.getLastModifiedTime(f).toMillis();
                AdvFile old = advFiles.get(id);
                if (old != null && old.mtime() == mtime) continue;
                try {
                    advFiles.put(id, parseAdvancements(f, mtime));
                    changes.incrementAndGet();
                } catch (RuntimeException | IOException e) {
                    // file may be mid-write; try again next scan
                }
            }
        } catch (IOException ignored) {
            // directory unreadable; keep what we have
        }
    }

    private static AdvFile parseAdvancements(Path file, long mtime) throws IOException {
        JsonObject root;
        try (Reader r = Files.newBufferedReader(file)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        Map<String, Long> earned = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            if (!e.getValue().isJsonObject()) continue; // DataVersion
            JsonObject adv = e.getValue().getAsJsonObject();
            if (!adv.has("done") || !adv.get("done").getAsBoolean()) continue;
            long when = 0;
            JsonObject criteria = adv.getAsJsonObject("criteria");
            if (criteria != null) {
                for (JsonElement c : criteria.asMap().values()) {
                    try {
                        when = Math.max(when, OffsetDateTime.parse(c.getAsString(), ADV_DATE).toInstant().toEpochMilli());
                    } catch (RuntimeException ignored) {
                        // unknown date format: leave the date unknown
                    }
                }
            }
            earned.put(e.getKey().replace("minecraft:", ""), when);
        }
        return new AdvFile(mtime, earned);
    }

    private static FileData parse(Path file, long mtime) throws IOException {
        JsonObject stats;
        try (Reader r = Files.newBufferedReader(file)) {
            stats = JsonParser.parseReader(r).getAsJsonObject().getAsJsonObject("stats");
        }
        Map<String, Long> general = new LinkedHashMap<>();
        Map<String, Map<String, Long>> colls = new LinkedHashMap<>();
        if (stats != null) {
            for (Map.Entry<String, JsonElement> group : stats.entrySet()) {
                String g = group.getKey().replace("minecraft:", "");
                for (Map.Entry<String, JsonElement> e : group.getValue().getAsJsonObject().entrySet()) {
                    String key = e.getKey().replace("minecraft:", "");
                    long v = e.getValue().getAsLong();
                    if (v == 0) continue;
                    if (g.equals("custom")) {
                        key = RENAMED.getOrDefault(key, key);
                        if (UNTYPED_KEYS.contains(key)) general.put(key, v);
                    } else if (COLLECTIONS.contains(g)) {
                        colls.computeIfAbsent(g, k -> new LinkedHashMap<>()).put(key, v);
                    }
                }
            }
        }
        return new FileData(mtime, general, colls);
    }

    private void publish(List<PlayerData> players, List<AdvDef> catalog) {
        placements = Boards.compute(players);
        players.sort(Comparator.comparingLong((PlayerData p) -> p.stat("play_one_minute")).reversed());

        List<Map<String, Object>> playerJson = new ArrayList<>();
        for (PlayerData p : players) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("uuid", p.uuid().toString());
            o.put("name", p.name());
            o.put("online", p.online());
            o.put("stats", p.stats());
            o.put("collections", p.collections());
            o.put("advancements", p.advancements());
            playerJson.add(o);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("updated", System.currentTimeMillis());
        root.put("online", online.size());
        root.put("catalog", Map.of("mined", BLOCKS.size(), "crafted", ITEMS.size(), "killed", MOBS.size()));
        root.put("advancements", catalog.stream().map(AdvDef::json).toList());
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
    }
}
