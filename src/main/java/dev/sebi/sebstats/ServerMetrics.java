package dev.sebi.sebstats;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Server health and activity history for the website's "Server" tab.
 *
 * <p>A main-thread sampler runs every 5 seconds using only cheap calls (TPS, MSPT, memory, CPU load, chunk and
 * entity counts) and keeps the latest values in memory for the live gauges. Samples are averaged into one-minute
 * rows, kept for 48 hours, and complete hours are rolled up into hourly rows kept forever. Rows are appended to
 * CSV files by an async task, so the main thread never touches the disk.
 */
final class ServerMetrics implements Listener {
    private static final long SAMPLE_TICKS = 100;
    private static final int MINUTE_KEEP = 48 * 60;
    private static final long CHART_DAYS = 30;
    private static final long GAP_SECONDS = 150;
    private static final long HOUR_GAP_SECONDS = 7200;
    private static final double LAG_TPS = 18.0;
    /** TPS right after startup is not representative. */
    private static final long WARMUP_MS = 120_000;
    private static final double MB = 1024.0 * 1024.0;

    /**
     * One minute (or, once rolled up, one hour) of measurements. Negative cpu/sys mean "unavailable".
     * {@code msptMax} is the single slowest tick, {@code msptP95} the 95th percentile tick. The entity columns
     * come from a once-a-minute census; {@code hotspot} is the most entities found in one chunk.
     */
    record Row(long ts, double tps, double tpsMin, double mspt, double msptMax, double online, double onlineMax,
               double ram, double cpu, double sys, double gc, long chunks, double loaded, double entities, int n,
               double msptP95, double animals, double monsters, double villagers, double items, double hotspot) {
        private static final int LEGACY_FIELDS = 15;
        private static final int FIELDS = 21;

        String csv() {
            return String.format(Locale.ROOT,
                    "%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.0f,%.1f,%.1f,%.2f,%d,%.0f,%.0f,%d,%.2f,%.0f,%.0f,%.0f,%.0f,%.0f",
                    ts, tps, tpsMin, mspt, msptMax, online, onlineMax, ram, cpu, sys, gc, chunks, loaded, entities, n,
                    msptP95, animals, monsters, villagers, items, hotspot);
        }

        /** Reads both the current format and the 15-field one written by 1.5.0 before the entity census existed. */
        static Row parse(String line) {
            String[] f = line.trim().split(",");
            if (f.length != FIELDS && f.length != LEGACY_FIELDS) return null;
            try {
                boolean full = f.length == FIELDS;
                return new Row(Long.parseLong(f[0]), Double.parseDouble(f[1]), Double.parseDouble(f[2]),
                        Double.parseDouble(f[3]), Double.parseDouble(f[4]), Double.parseDouble(f[5]),
                        Double.parseDouble(f[6]), Double.parseDouble(f[7]), Double.parseDouble(f[8]),
                        Double.parseDouble(f[9]), Double.parseDouble(f[10]), Long.parseLong(f[11]),
                        Double.parseDouble(f[12]), Double.parseDouble(f[13]), Integer.parseInt(f[14]),
                        full ? Double.parseDouble(f[15]) : 0, full ? Double.parseDouble(f[16]) : 0,
                        full ? Double.parseDouble(f[17]) : 0, full ? Double.parseDouble(f[18]) : 0,
                        full ? Double.parseDouble(f[19]) : 0, full ? Double.parseDouble(f[20]) : 0);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** Entities by kind, counted once a minute. {@code other} is everything not in the named groups. */
    private record Census(int animals, int monsters, int villagers, int items, int other, int hotspot,
                          String hotspotWorld) {}

    private record WorldLive(String name, String env, int chunks, int entities, long day, boolean storm,
                             boolean thunder) {}

    /** The most recent sample, for the live gauges. */
    private record Live(long at, double tps, double mspt, double tickP50, double tickP95, double tickWorst,
                        int online, int maxPlayers, double ramUsed, double ramTotal,
                        double ramMax, double cpu, double sys, double gc, int chunks, int entities,
                        List<WorldLive> worlds) {}

    /** Records and counters that survive restarts. */
    static final class State {
        long since;
        double peakOnline;
        long peakOnlineAt;
        double lowTps = 20;
        long lowTpsAt;
        long longestUptimeMs;
        long longestUptimeAt;
        Map<String, Long> chunks = new HashMap<>();
    }

    private final JavaPlugin plugin;
    private final Gson gson = new Gson();
    private final Path minuteFile;
    private final Path hourlyFile;
    private final Path stateFile;
    private final com.sun.management.OperatingSystemMXBean os;
    private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

    private final Object lock = new Object();
    private final Object io = new Object();
    private final List<Row> minutes = new ArrayList<>();
    private final List<Row> hours = new ArrayList<>();
    private final List<String> pendingMinutes = new ArrayList<>();
    private final List<String> pendingHours = new ArrayList<>();
    private State state = new State();
    private int minuteLinesOnDisk;

    private final Map<String, LongAdder> generated = new ConcurrentHashMap<>();
    private final LongAdder generatedThisMinute = new LongAdder();
    private volatile Live live;
    private volatile Census census = new Census(0, 0, 0, 0, 0, 0, "");
    private long lastCensusMs;

    // Main-thread accumulators for the minute in progress.
    private long curMinute = -1;
    private int accN, accCpuN, accSysN;
    private double accTps, accTpsMin = 20, accMspt, accMsptMax, accOnline, accOnlineMax, accRam, accCpu, accSys, accGc,
            accLoaded, accEntities, accP95;
    private long lastSampleMs;
    private long lastGcMs = -1;

    private BukkitTask sampler;
    private BukkitTask writer;

    ServerMetrics(JavaPlugin plugin) {
        this.plugin = plugin;
        Path dir = plugin.getDataFolder().toPath();
        minuteFile = dir.resolve("metrics-minute.csv");
        hourlyFile = dir.resolve("metrics-hourly.csv");
        stateFile = dir.resolve("metrics-state.json");
        os = ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean b ? b : null;
        load();
    }

    void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        sampler = Bukkit.getScheduler().runTaskTimer(plugin, this::sample, 40L, SAMPLE_TICKS);
        writer = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flush, 1200L, 1200L);
    }

    /** Main thread: stop sampling and write out what is left. */
    void stop() {
        if (sampler != null) sampler.cancel();
        if (writer != null) writer.cancel();
        closeMinute();
        flush();
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!e.isNewChunk()) return;
        generatedThisMinute.increment();
        generated.computeIfAbsent(e.getWorld().getName(), k -> new LongAdder()).increment();
    }

    // ---- sampling (main thread) -----------------------------------------------------------------------

    void sample() {
        long nowMs = System.currentTimeMillis();
        long minute = nowMs / 60_000;
        if (curMinute >= 0 && minute != curMinute) closeMinute();
        curMinute = minute;

        double tps = Math.min(20.0, Math.max(0, Bukkit.getTPS()[0]));
        double mspt = Math.max(0, Bukkit.getAverageTickTime());
        // The server keeps the last 100 tick durations (nanoseconds), which is exactly one sampling interval.
        long[] ticks = Bukkit.getTickTimes().clone();
        Arrays.sort(ticks);
        double p50 = 0, p95 = 0, worst = 0;
        if (ticks.length > 0) {
            p50 = ticks[ticks.length / 2] / 1e6;
            p95 = ticks[Math.min(ticks.length - 1, (int) (ticks.length * 0.95))] / 1e6;
            worst = ticks[ticks.length - 1] / 1e6;
        }
        boolean warm = ManagementFactory.getRuntimeMXBean().getUptime() > WARMUP_MS;
        if (!warm) { // loading the world makes a few huge ticks that say nothing about how the server runs
            p95 = Math.min(p95, mspt);
            worst = Math.min(worst, mspt);
        }
        if (nowMs - lastCensusMs >= 60_000) {
            census = takeCensus();
            lastCensusMs = nowMs;
        }
        int online = Bukkit.getOnlinePlayers().size();
        Runtime rt = Runtime.getRuntime();
        double used = (rt.totalMemory() - rt.freeMemory()) / MB;
        double cpu = -1, sys = -1;
        if (os != null) {
            double c = os.getProcessCpuLoad(), s = os.getCpuLoad();
            if (c >= 0) cpu = c * 100;
            if (s >= 0) sys = s * 100;
        }
        long gcMs = 0;
        for (GarbageCollectorMXBean b : gcBeans) gcMs += Math.max(0, b.getCollectionTime());
        double gc = 0;
        if (lastGcMs >= 0 && lastSampleMs > 0 && nowMs > lastSampleMs) {
            gc = Math.min(100, Math.max(0, (gcMs - lastGcMs) * 100.0 / (nowMs - lastSampleMs)));
        }
        lastGcMs = gcMs;
        lastSampleMs = nowMs;

        int chunks = 0, entities = 0;
        List<WorldLive> worlds = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            int c = w.getChunkCount(), en = w.getEntityCount();
            chunks += c;
            entities += en;
            worlds.add(new WorldLive(w.getName(), w.getEnvironment().name().toLowerCase(Locale.ROOT), c, en,
                    w.getFullTime() / 24000, w.hasStorm(), w.isThundering()));
        }
        live = new Live(nowMs, tps, mspt, p50, p95, worst, online, Bukkit.getMaxPlayers(), used, rt.totalMemory() / MB,
                rt.maxMemory() / MB, cpu, sys, gc, chunks, entities, worlds);

        accN++;
        accTps += tps;
        accTpsMin = Math.min(accTpsMin, warm ? tps : 20);
        accMspt += mspt;
        accMsptMax = Math.max(accMsptMax, worst);
        accP95 += p95;
        accOnline += online;
        accOnlineMax = Math.max(accOnlineMax, online);
        accRam += used;
        if (cpu >= 0) { accCpu += cpu; accCpuN++; }
        if (sys >= 0) { accSys += sys; accSysN++; }
        accGc += gc;
        accLoaded += chunks;
        accEntities += entities;
    }

    private void closeMinute() {
        if (accN == 0 || curMinute < 0) return;
        Census c = census;
        Row row = new Row(curMinute * 60, accTps / accN, accTpsMin, accMspt / accN, accMsptMax, accOnline / accN,
                accOnlineMax, accRam / accN, accCpuN > 0 ? accCpu / accCpuN : -1, accSysN > 0 ? accSys / accSysN : -1,
                accGc / accN, generatedThisMinute.sumThenReset(), accLoaded / accN, accEntities / accN, accN,
                accP95 / accN, c.animals(), c.monsters(), c.villagers(), c.items(), c.hotspot());
        accN = accCpuN = accSysN = 0;
        accTps = accMspt = accMsptMax = accOnline = accOnlineMax = accRam = accCpu = accSys = accGc = accP95 = 0;
        accLoaded = accEntities = 0;
        accTpsMin = 20;

        long now = System.currentTimeMillis();
        synchronized (lock) {
            minutes.add(row);
            pendingMinutes.add(row.csv());
            long cutoff = row.ts() - MINUTE_KEEP * 60L;
            while (!minutes.isEmpty() && minutes.get(0).ts() < cutoff) minutes.remove(0);
            finalizeHours(row.ts());
            updateRecords(row, now);
        }
    }

    private void updateRecords(Row row, long nowMs) {
        if (state.since == 0) state.since = nowMs;
        if (row.onlineMax() > state.peakOnline) {
            state.peakOnline = row.onlineMax();
            state.peakOnlineAt = row.ts();
        }
        if (row.tpsMin() < state.lowTps && ManagementFactory.getRuntimeMXBean().getUptime() > WARMUP_MS) {
            state.lowTps = row.tpsMin();
            state.lowTpsAt = row.ts();
        }
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        if (uptime > state.longestUptimeMs) {
            state.longestUptimeMs = uptime;
            state.longestUptimeAt = row.ts();
        }
    }

    /** Walks every loaded entity once. Main thread only; cheap enough to do once a minute. */
    private Census takeCensus() {
        int animals = 0, monsters = 0, villagers = 0, items = 0, total = 0, hot = 0;
        String hotWorld = "";
        for (World w : Bukkit.getWorlds()) {
            Map<Long, Integer> perChunk = new HashMap<>();
            for (Entity e : w.getEntities()) {
                total++;
                EntityType type = e.getType();
                if (type == EntityType.ITEM) items++;
                else if (type == EntityType.VILLAGER || type == EntityType.WANDERING_TRADER) villagers++;
                else switch (e.getSpawnCategory()) {
                    case ANIMAL -> animals++;
                    case MONSTER -> monsters++;
                    default -> { }
                }
                long cx = (long) Math.floor(e.getX()) >> 4, cz = (long) Math.floor(e.getZ()) >> 4;
                perChunk.merge(cx << 32 ^ (cz & 0xffffffffL), 1, Integer::sum);
            }
            for (int v : perChunk.values()) {
                if (v > hot) { hot = v; hotWorld = w.getName(); }
            }
        }
        return new Census(animals, monsters, villagers, items, Math.max(0, total - animals - monsters - villagers - items),
                hot, hotWorld);
    }

    // ---- rollups (call with {@link #lock} held) --------------------------------------------------------

    /** Turns every finished hour that is not yet in {@link #hours} into an hourly row. */
    private void finalizeHours(long nowSec) {
        long currentHour = nowSec / 3600 * 3600;
        long done = hours.isEmpty() ? -1 : hours.get(hours.size() - 1).ts();
        for (Row h : groupHours(done)) {
            if (h.ts() >= currentHour) break;
            hours.add(h);
            pendingHours.add(h.csv());
        }
    }

    /** Aggregates the minute rows of every hour after {@code after}, including the hour in progress. */
    private List<Row> groupHours(long after) {
        TreeMap<Long, List<Row>> groups = new TreeMap<>();
        for (Row r : minutes) {
            long h = r.ts() / 3600 * 3600;
            if (h > after) groups.computeIfAbsent(h, k -> new ArrayList<>()).add(r);
        }
        List<Row> out = new ArrayList<>();
        groups.forEach((h, rs) -> out.add(aggregate(h, rs)));
        return out;
    }

    private static Row aggregate(long ts, List<Row> rs) {
        double w = 0, tps = 0, mspt = 0, online = 0, ram = 0, gc = 0, loaded = 0, ent = 0;
        double p95 = 0, animals = 0, monsters = 0, villagers = 0, items = 0, hotspot = 0;
        double cpu = 0, cpuW = 0, sys = 0, sysW = 0, tpsMin = 20, msptMax = 0, onlineMax = 0;
        long chunks = 0;
        int n = 0;
        for (Row r : rs) {
            double k = r.n();
            w += k;
            n += r.n();
            tps += r.tps() * k;
            mspt += r.mspt() * k;
            online += r.online() * k;
            ram += r.ram() * k;
            gc += r.gc() * k;
            loaded += r.loaded() * k;
            ent += r.entities() * k;
            p95 += r.msptP95() * k;
            animals += r.animals() * k;
            monsters += r.monsters() * k;
            villagers += r.villagers() * k;
            items += r.items() * k;
            hotspot = Math.max(hotspot, r.hotspot());
            if (r.cpu() >= 0) { cpu += r.cpu() * k; cpuW += k; }
            if (r.sys() >= 0) { sys += r.sys() * k; sysW += k; }
            tpsMin = Math.min(tpsMin, r.tpsMin());
            msptMax = Math.max(msptMax, r.msptMax());
            onlineMax = Math.max(onlineMax, r.onlineMax());
            chunks += r.chunks();
        }
        if (w == 0) w = 1;
        return new Row(ts, tps / w, tpsMin, mspt / w, msptMax, online / w, onlineMax, ram / w,
                cpuW > 0 ? cpu / cpuW : -1, sysW > 0 ? sys / sysW : -1, gc / w, chunks, loaded / w, ent / w, n,
                p95 / w, animals / w, monsters / w, villagers / w, items / w, hotspot);
    }

    // ---- persistence -----------------------------------------------------------------------------------

    private void load() {
        try {
            Files.createDirectories(minuteFile.getParent());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not create data folder: " + e.getMessage());
        }
        long cutoff = System.currentTimeMillis() / 1000 - MINUTE_KEEP * 60L;
        for (String line : readLines(minuteFile)) {
            minuteLinesOnDisk++;
            Row r = Row.parse(line);
            if (r != null && r.ts() >= cutoff) minutes.add(r);
        }
        for (String line : readLines(hourlyFile)) {
            Row r = Row.parse(line);
            if (r != null && (hours.isEmpty() || r.ts() > hours.get(hours.size() - 1).ts())) hours.add(r);
        }
        try {
            if (Files.exists(stateFile)) {
                State s = gson.fromJson(Files.readString(stateFile, StandardCharsets.UTF_8), State.class);
                if (s != null) state = s;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ignoring unreadable " + stateFile.getFileName() + ": " + e.getMessage());
        }
        if (state.chunks == null) state.chunks = new HashMap<>();
        state.chunks.forEach((k, v) -> generated.computeIfAbsent(k, x -> new LongAdder()).add(v));
        synchronized (lock) {
            // Hours that finished while the server was down are still in the minute rows.
            finalizeHours(System.currentTimeMillis() / 1000);
        }
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** Appends new rows and saves the state. Runs async every minute and once on shutdown. */
    void flush() {
        List<String> newMinutes, newHours;
        List<Row> rewrite = null;
        String stateJson;
        synchronized (lock) {
            newMinutes = new ArrayList<>(pendingMinutes);
            newHours = new ArrayList<>(pendingHours);
            pendingMinutes.clear();
            pendingHours.clear();
            if (minuteLinesOnDisk + newMinutes.size() > 2 * MINUTE_KEEP) rewrite = new ArrayList<>(minutes);
            state.chunks = new HashMap<>();
            generated.forEach((k, v) -> state.chunks.put(k, v.sum()));
            stateJson = gson.toJson(state);
        }
        synchronized (io) {
            try {
                if (rewrite != null) {
                    List<String> lines = new ArrayList<>();
                    for (Row r : rewrite) lines.add(r.csv());
                    Files.write(minuteFile, lines, StandardCharsets.UTF_8);
                    minuteLinesOnDisk = lines.size();
                } else if (!newMinutes.isEmpty()) {
                    append(minuteFile, newMinutes);
                    minuteLinesOnDisk += newMinutes.size();
                }
                if (!newHours.isEmpty()) append(hourlyFile, newHours);
                Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
                Files.writeString(tmp, stateJson, StandardCharsets.UTF_8);
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save server metrics: " + e.getMessage());
            }
        }
    }

    private static void append(Path file, List<String> lines) throws IOException {
        Files.write(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // ---- API view --------------------------------------------------------------------------------------

    /** Everything the Server tab shows. {@code numbers} is the whole-server totals from the player snapshot. */
    JsonObject toJson(JsonElement numbers) {
        long nowSec = System.currentTimeMillis() / 1000;
        List<Row> recent, hourly;
        State st;
        synchronized (lock) {
            recent = new ArrayList<>(minutes);
            hourly = new ArrayList<>(hours);
            hourly.addAll(groupHours(hours.isEmpty() ? -1 : hours.get(hours.size() - 1).ts()));
            st = gson.fromJson(gson.toJson(state), State.class);
        }

        JsonObject o = new JsonObject();
        o.addProperty("updated", nowSec * 1000);
        o.add("now", now(nowSec));
        o.add("recent", columns(recent));
        o.add("hourly", columns(hourly.stream().filter(r -> r.ts() >= nowSec - CHART_DAYS * 86400).toList()));
        o.add("heatmap", heatmap(hourly));
        o.add("chunks", chunks(hourly, nowSec));
        o.add("records", records(st, hourly, recent, nowSec));
        o.add("restarts", restarts(recent, hourly));
        if (numbers != null && !numbers.isJsonNull()) o.add("numbers", numbers);
        return o;
    }

    private JsonObject now(long nowSec) {
        JsonObject o = new JsonObject();
        Live l = live;
        o.addProperty("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
        o.addProperty("minecraft", Bukkit.getMinecraftVersion());
        o.addProperty("software", Bukkit.getName());
        o.addProperty("since", state.since);
        if (l == null) return o;
        o.addProperty("sampledAt", l.at());
        o.addProperty("tps", round(l.tps()));
        o.addProperty("mspt", round(l.mspt()));
        o.addProperty("tickP50", round(l.tickP50()));
        o.addProperty("tickP95", round(l.tickP95()));
        o.addProperty("tickWorst", round(l.tickWorst()));
        o.addProperty("online", l.online());
        o.addProperty("maxPlayers", l.maxPlayers());
        o.addProperty("ramUsed", Math.round(l.ramUsed()));
        o.addProperty("ramTotal", Math.round(l.ramTotal()));
        o.addProperty("ramMax", Math.round(l.ramMax()));
        o.addProperty("cpu", l.cpu() < 0 ? null : Double.valueOf(round(l.cpu())));
        o.addProperty("sys", l.sys() < 0 ? null : Double.valueOf(round(l.sys())));
        o.addProperty("gc", round(l.gc()));
        o.addProperty("loadedChunks", l.chunks());
        o.addProperty("entities", l.entities());
        Census c = census;
        JsonObject eb = new JsonObject();
        eb.addProperty("animals", c.animals());
        eb.addProperty("monsters", c.monsters());
        eb.addProperty("villagers", c.villagers());
        eb.addProperty("items", c.items());
        eb.addProperty("other", c.other());
        eb.addProperty("hotspot", c.hotspot());
        eb.addProperty("hotspotWorld", c.hotspotWorld());
        o.add("entityBreakdown", eb);
        JsonArray ws = new JsonArray();
        for (WorldLive w : l.worlds()) {
            JsonObject wo = new JsonObject();
            wo.addProperty("name", w.name());
            wo.addProperty("env", w.env());
            wo.addProperty("chunks", w.chunks());
            wo.addProperty("entities", w.entities());
            wo.addProperty("day", w.day() + 1);
            wo.addProperty("storm", w.storm());
            wo.addProperty("thunder", w.thunder());
            ws.add(wo);
        }
        o.add("worlds", ws);
        return o;
    }

    private static JsonObject columns(List<Row> rows) {
        JsonObject o = new JsonObject();
        longCol(o, "t", rows, Row::ts);
        col(o, "tps", rows, Row::tps);
        col(o, "tpsMin", rows, Row::tpsMin);
        col(o, "mspt", rows, Row::mspt);
        col(o, "msptMax", rows, Row::msptMax);
        col(o, "online", rows, Row::online);
        col(o, "onlineMax", rows, Row::onlineMax);
        col(o, "ram", rows, Row::ram);
        col(o, "cpu", rows, Row::cpu);
        col(o, "sys", rows, Row::sys);
        col(o, "gc", rows, Row::gc);
        longCol(o, "chunks", rows, Row::chunks);
        col(o, "loaded", rows, Row::loaded);
        col(o, "entities", rows, Row::entities);
        col(o, "msptP95", rows, Row::msptP95);
        col(o, "animals", rows, Row::animals);
        col(o, "monsters", rows, Row::monsters);
        col(o, "villagers", rows, Row::villagers);
        col(o, "items", rows, Row::items);
        col(o, "hotspot", rows, Row::hotspot);
        return o;
    }

    private static void col(JsonObject o, String key, List<Row> rows, ToDoubleFunction<Row> f) {
        JsonArray a = new JsonArray();
        for (Row r : rows) a.add(round(f.applyAsDouble(r)));
        o.add(key, a);
    }

    private static void longCol(JsonObject o, String key, List<Row> rows, ToLongFunction<Row> f) {
        JsonArray a = new JsonArray();
        for (Row r : rows) a.add(f.applyAsLong(r));
        o.add(key, a);
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /** Hour-of-week (Monday 00:00 UTC = 0) of an epoch second. The client shifts it into the viewer's timezone. */
    static int hourOfWeek(long epochSec) {
        long h = epochSec / 3600;
        return (int) (((h / 24 + 3) % 7) * 24 + h % 24);
    }

    private static JsonObject heatmap(List<Row> hourly) {
        double[] sum = new double[168];
        int[] n = new int[168];
        for (Row r : hourly) {
            int i = hourOfWeek(r.ts());
            sum[i] += r.online();
            n[i]++;
        }
        JsonArray avg = new JsonArray(), counts = new JsonArray();
        for (int i = 0; i < 168; i++) {
            avg.add(n[i] == 0 ? 0 : round(sum[i] / n[i]));
            counts.add(n[i]);
        }
        JsonObject o = new JsonObject();
        o.add("avg", avg);
        o.add("n", counts);
        return o;
    }

    private JsonObject chunks(List<Row> hourly, long nowSec) {
        long today = nowSec / 86400;
        long[] perDay = new long[(int) CHART_DAYS];
        for (Row r : hourly) {
            long idx = r.ts() / 86400 - (today - CHART_DAYS + 1);
            if (idx >= 0 && idx < CHART_DAYS) perDay[(int) idx] += r.chunks();
        }
        JsonObject o = new JsonObject();
        long total = 0;
        JsonObject perWorld = new JsonObject();
        for (Map.Entry<String, LongAdder> e : generated.entrySet()) {
            perWorld.addProperty(e.getKey(), e.getValue().sum());
            total += e.getValue().sum();
        }
        o.addProperty("total", total);
        o.add("perWorld", perWorld);
        o.addProperty("since", state.since);
        JsonObject days = new JsonObject();
        days.addProperty("start", (today - CHART_DAYS + 1) * 86400);
        JsonArray values = new JsonArray();
        for (long v : perDay) values.add(v);
        days.add("values", values);
        o.add("perDay", days);
        return o;
    }

    private static JsonObject records(State st, List<Row> hourly, List<Row> recent, long nowSec) {
        JsonObject o = new JsonObject();
        o.add("peakOnline", record(st.peakOnline, st.peakOnlineAt));
        o.add("lowestTps", st.lowTpsAt == 0 ? null : record(st.lowTps, st.lowTpsAt));
        o.add("longestUptime", record(st.longestUptimeMs, st.longestUptimeAt));

        Map<Long, Double> perDay = new HashMap<>();
        for (Row r : hourly) perDay.merge(r.ts() / 86400, r.online(), Double::sum);
        perDay.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(e -> {
            JsonObject d = new JsonObject();
            d.addProperty("t", e.getKey() * 86400);
            d.addProperty("playerHours", Math.round(e.getValue()));
            o.add("busiestDay", d);
        });

        long lagMinutes = recent.stream().filter(r -> r.ts() >= nowSec - 86400 && r.tpsMin() < LAG_TPS).count();
        o.addProperty("lagMinutes24h", lagMinutes);
        return o;
    }

    private static JsonObject record(double value, long at) {
        JsonObject o = new JsonObject();
        o.addProperty("value", round(value));
        o.addProperty("t", at);
        return o;
    }

    /** Timestamps where the data resumes after a gap, taken as server restarts. */
    private static JsonArray restarts(List<Row> recent, List<Row> hourly) {
        JsonArray out = new JsonArray();
        long firstMinute = recent.isEmpty() ? Long.MAX_VALUE : recent.get(0).ts();
        for (int i = 1; i < hourly.size(); i++) {
            Row r = hourly.get(i);
            if (r.ts() < firstMinute && r.ts() - hourly.get(i - 1).ts() > HOUR_GAP_SECONDS) out.add(r.ts());
        }
        for (int i = 1; i < recent.size(); i++) {
            if (recent.get(i).ts() - recent.get(i - 1).ts() > GAP_SECONDS) out.add(recent.get(i).ts());
        }
        return out;
    }
}
