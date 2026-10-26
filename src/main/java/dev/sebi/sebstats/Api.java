package dev.sebi.sebstats;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Routes /api/... requests to scoped views of the latest snapshot. */
final class Api {
    record Result(int status, JsonElement body) {}

    private static final String[] ENDPOINTS = {
        "/api/stats                                  everything (players, facts, catalog)",
        "/api/online                                 online player names",
        "/api/players                                player summaries (uuid, name, online)",
        "/api/players/{name|uuid}                    one player, full data",
        "/api/players/{name|uuid}/stats              one player's numeric stats",
        "/api/players/{name|uuid}/collections        one player's collections",
        "/api/players/{name|uuid}/collections/{c}    one collection (mined, crafted, picked_up, used, broken, killed, killed_by)",
        "/api/leaderboard/{stat}                     ranking by a stat key (e.g. deaths, jump, travel_cm) or collection total (e.g. mined)",
        "/api/leaderboard/{collection}/{item}        ranking by one item (e.g. mined/diamond_ore, killed/creeper)",
        "/api/facts                                  fun facts",
        "                                            query params: ?limit=N on leaderboard; ?pretty=1 on any endpoint",
    };

    static Result handle(String path, Map<String, String> query, String snapshot) {
        JsonObject root = parse(snapshot);
        String[] p = path.replaceAll("^/+|/+$", "").split("/");
        // p[0] == "api"
        if (p.length == 1) return ok(index());
        switch (p[1]) {
            case "stats": return p.length == 2 ? ok(root) : notFound("no such endpoint");
            case "facts": return ok(root.get("facts"));
            case "online": return ok(online(root));
            case "players": return players(root, p);
            case "leaderboard": return leaderboard(root, p, query);
            default: return notFound("no such endpoint");
        }
    }

    private static String lastSnapshot;
    private static JsonObject lastRoot;

    /** The snapshot string only changes when a rebuild happens, so parse it once per rebuild. */
    private static synchronized JsonObject parse(String snapshot) {
        if (snapshot != lastSnapshot) {
            lastRoot = JsonParser.parseString(snapshot).getAsJsonObject();
            lastSnapshot = snapshot;
        }
        return lastRoot;
    }

    private static JsonObject index() {
        JsonObject o = new JsonObject();
        JsonArray a = new JsonArray();
        for (String e : ENDPOINTS) a.add(e);
        o.add("endpoints", a);
        return o;
    }

    private static JsonArray online(JsonObject root) {
        JsonArray out = new JsonArray();
        for (JsonElement e : root.getAsJsonArray("players")) {
            JsonObject pl = e.getAsJsonObject();
            if (pl.get("online").getAsBoolean()) out.add(pl.get("name").getAsString());
        }
        return out;
    }

    private static Result players(JsonObject root, String[] p) {
        JsonArray all = root.getAsJsonArray("players");
        if (p.length == 2) {
            JsonArray out = new JsonArray();
            for (JsonElement e : all) {
                JsonObject src = e.getAsJsonObject(), o = new JsonObject();
                for (String k : new String[] {"uuid", "name", "online"}) o.add(k, src.get(k));
                out.add(o);
            }
            return ok(out);
        }
        JsonObject player = find(all, p[2]);
        if (player == null) return notFound("unknown player: " + p[2]);
        if (p.length == 3) return ok(player);
        switch (p[3]) {
            case "stats": return p.length == 4 ? ok(player.get("stats")) : notFound("no such endpoint");
            case "collections": {
                JsonObject colls = player.getAsJsonObject("collections");
                if (p.length == 4) return ok(colls);
                if (p.length == 5 && colls.has(p[4])) return ok(colls.get(p[4]));
                return notFound("unknown collection: " + p[p.length - 1]);
            }
            default: return notFound("no such endpoint");
        }
    }

    private static Result leaderboard(JsonObject root, String[] p, Map<String, String> query) {
        if (p.length < 3 || p.length > 4) return notFound("use /api/leaderboard/{stat} or /{collection}/{item}");
        int limit = 10;
        try {
            if (query.containsKey("limit")) limit = Math.max(1, Integer.parseInt(query.get("limit")));
        } catch (NumberFormatException e) {
            return new Result(400, error("limit must be a number"));
        }

        record Row(String uuid, String name, boolean online, long value) {}
        List<Row> rows = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("players")) {
            JsonObject pl = e.getAsJsonObject();
            JsonObject colls = pl.getAsJsonObject("collections");
            long v;
            if (p.length == 4) {
                JsonElement c = colls.get(p[2]);
                if (c == null) return notFound("unknown collection: " + p[2]);
                JsonElement item = c.getAsJsonObject().get(p[3]);
                v = item == null ? 0 : item.getAsLong();
            } else if (colls.has(p[2])) {
                v = colls.getAsJsonObject(p[2]).entrySet().stream().mapToLong(en -> en.getValue().getAsLong()).sum();
            } else {
                JsonElement s = pl.getAsJsonObject("stats").get(p[2]);
                v = s == null ? 0 : s.getAsLong();
            }
            if (v > 0) {
                rows.add(new Row(pl.get("uuid").getAsString(), pl.get("name").getAsString(),
                        pl.get("online").getAsBoolean(), v));
            }
        }
        rows.sort(Comparator.comparingLong(Row::value).reversed());

        JsonArray out = new JsonArray();
        for (int i = 0; i < Math.min(limit, rows.size()); i++) {
            Row r = rows.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("rank", i + 1);
            o.addProperty("uuid", r.uuid());
            o.addProperty("name", r.name());
            o.addProperty("online", r.online());
            o.addProperty("value", r.value());
            out.add(o);
        }
        return ok(out);
    }

    private static JsonObject find(JsonArray players, String id) {
        for (JsonElement e : players) {
            JsonObject pl = e.getAsJsonObject();
            if (pl.get("uuid").getAsString().equalsIgnoreCase(id) || pl.get("name").getAsString().equalsIgnoreCase(id)) {
                return pl;
            }
        }
        return null;
    }

    private static Result ok(JsonElement body) {
        return new Result(200, body);
    }

    private static Result notFound(String msg) {
        return new Result(404, error(msg));
    }

    private static JsonObject error(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg);
        return o;
    }
}
