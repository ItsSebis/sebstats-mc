# SebStats API

A read-only JSON API served by the plugin on the same port as the website (default `8123`, set in `config.yml`).

```
http://<server-ip>:8123/api
```

`GET /api` returns the list of endpoints. All responses are JSON with `Access-Control-Allow-Origin: *`. Add `?pretty=1` to any endpoint for indented output. There is no authentication.

## Freshness

Data comes from the server's own stats files (`players/stats/*.json`), parsed off the main thread every `refresh-seconds` (default 3). A file is only re-read when it changes.

- **Online players** also get live values: every refresh the plugin re-reads, within a small per-tick time budget, the items already present in their file plus the general stats. An item a player has never collected before appears when the server next saves their stats (autosave, about every 5 minutes, or when they quit).
- **Offline players** reflect their last saved stats file.
- Players only appear once their name is known, so someone who has never joined is not listed.

## Endpoints

| Endpoint | Returns |
|---|---|
| `GET /api/stats` | Everything: `updated`, `online`, `catalog`, `advancements`, `players`, `facts` |
| `GET /api/online` | Array of online player names |
| `GET /api/players` | Array of `{uuid, name, online}` |
| `GET /api/players/{player}` | One player: `{uuid, name, online, stats, collections, advancements}` |
| `GET /api/players/{player}/stats` | Only the player's `stats` object |
| `GET /api/players/{player}/collections` | Only the player's `collections` object |
| `GET /api/players/{player}/collections/{collection}` | One collection as `{item: count}` |
| `GET /api/players/{player}/advancements` | Advancements the player has earned: `{id: epoch_millis}` |
| `GET /api/advancements` | The advancement catalog with rarity: `earned` (players) and `percent` |
| `GET /api/leaderboard/{stat}` | Ranking by a stat key or collection total |
| `GET /api/leaderboard/{collection}/{item}` | Ranking by a single item |
| `GET /api/facts` | Array of `{icon, text}` |

`{player}` is a username (case-insensitive) or a UUID.

### Leaderboards

`/api/leaderboard/{stat}` resolves `{stat}` like this:

1. If it is a collection name (`mined`, `crafted`, ...), the value is the **sum of that collection** (see below).
2. Otherwise it is looked up in each player's `stats` (e.g. `deaths`, `jump`, `travel_cm`).

`/api/leaderboard/{collection}/{item}` ranks by one entry, e.g. `/api/leaderboard/mined/diamond_ore` or `/api/leaderboard/killed/creeper`.

Players with a value of 0 are left out. Results are sorted descending. Query parameter `limit` (default `10`) caps the number of rows.

```json
[
  {"rank": 1, "uuid": "069a79f4-...", "name": "Notch", "online": false, "value": 1209},
  {"rank": 2, "uuid": "5f27cf8f-...", "name": "SebisGHG", "online": true, "value": 412}
]
```

### Errors

Errors are `{"error": "message"}` with status `404` (unknown player, collection or endpoint) or `400` (`limit` is not a number).

## Data model

### `stats`

A flat `{key: number}` object of Minecraft's general statistics. Keys are the lowercase Bukkit `Statistic` names. **Stats that are 0 are omitted**, so treat a missing key as 0.

| Key | Unit | Meaning |
|---|---|---|
| `play_one_minute` | ticks (20 = 1 s) | Time played (vanilla `play_time`) |
| `total_world_time` | ticks | Time spent in worlds |
| `time_since_death` | ticks | Time since the last death |
| `deaths` | count | Times died |
| `mob_kills` / `player_kills` | count | Mobs / players killed |
| `damage_dealt` / `damage_taken` | tenths of a half-heart | Divide by 20 for hearts |
| `jump` | count | Jumps |
| `*_one_cm` | centimetres | Distance by mode, see below |
| `fish_caught` | count | Things caught fishing |
| `animals_bred` | count | Animals bred |
| `traded_with_villager` / `talked_to_villager` | count | Villager trades / conversations |
| `sleep_in_bed` | count | Times slept in a bed |
| `chest_opened` | count | Chests opened |
| `item_enchanted` | count | Items enchanted |
| `cake_slices_eaten` | count | Cake slices eaten |
| `raid_win` | count | Raids won |
| `leave_game` | count | Times left the game |

Distance modes: `walk_one_cm`, `sprint_one_cm`, `crouch_one_cm`, `swim_one_cm`, `aviate_one_cm` (elytra), `boat_one_cm`, `minecart_one_cm`, `horse_one_cm`, `pig_one_cm`, `strider_one_cm`, `climb_one_cm`, `fall_one_cm`.

### Combined (derived) values

These are computed by the plugin, not by Minecraft.

**`travel_cm`** (stats): total distance travelled, in centimetres. It is the sum of:

`walk_one_cm` + `sprint_one_cm` + `crouch_one_cm` + `swim_one_cm` + `aviate_one_cm` + `boat_one_cm` + `minecart_one_cm` + `horse_one_cm` + `pig_one_cm` + `strider_one_cm`

It deliberately **excludes `climb_one_cm` and `fall_one_cm`**, which measure vertical movement rather than getting somewhere. Divide by 100 for metres, by 100,000 for kilometres. It is absent if the player has travelled 0.

**Collection totals** (leaderboards): when a collection name is used as `{stat}`, the value is the sum of every count in that collection:

| `/api/leaderboard/...` | Meaning |
|---|---|
| `mined` | Total blocks mined, across all block types |
| `crafted` | Total items crafted, across all item types |
| `picked_up` | Total items picked up |
| `used` | Total item uses (placing blocks, using tools, eating, ...) |
| `broken` | Total tools/items worn out and broken |
| `killed` | Total mobs killed, across all mob types (`mob_kills` is Minecraft's own counter and may differ slightly) |
| `killed_by` | Total times killed by mobs, across all mob types |

These totals are not stored in `stats`; they exist only through the leaderboard endpoint. The website's "Blocks Mined" and "Items Crafted" boards use the same sums.

### `collections`

Seven objects of `{item: count}`: `mined`, `crafted`, `picked_up`, `used`, `broken`, `killed`, `killed_by`.

- Keys are Minecraft IDs **without** the `minecraft:` prefix, e.g. `diamond_ore`, `creeper`.
- Entries with a count of 0 are omitted.
- `mined` uses block IDs. `crafted`, `picked_up`, `used` and `broken` use item IDs. `killed` and `killed_by` use living-entity IDs.

### `advancements`

The catalog (top level of `/api/stats`) lists every advancement that has a display, as `{id, category, title, description, icon, frame, hidden}`. The root advancement of each tab and recipe unlocks are left out. `id` is the vanilla id without `minecraft:` (e.g. `story/mine_stone`), `category` is its first path segment (`story`, `nether`, `end`, `adventure`, `husbandry`, or the namespace for datapack advancements), `icon` is an item id, and `frame` is `task`, `goal` or `challenge`.

Each player has an `advancements` object of `{id: epoch_millis}` for what they have completed. The value is the time of the last criterion, or `0` if unknown. Online players are read live from the server; offline players come from their advancements file, which the server writes on autosave and when they quit.

### `catalog`

Totals the plugin knows about, used for "x / y discovered" progress: `{"mined": <block types>, "crafted": <item types>, "killed": <mob types>}`. Compare against the number of keys in a player's matching collection.

### `facts`

Generated each refresh from the current stats: records such as most deaths per hour or most jumps, server-wide totals (blocks mined, distance travelled, time played), the most-killed mob and most-mined block, one rotating piece of Minecraft trivia, and a sample of player comparisons, rates and rivalries that changes hourly. Fun-fact conversions used:

- Chests: blocks mined / 1,728 (27 stacks of 64)
- Marathons: distance / 42,195 m
- Mount Everests: jumps x 1.25 m / 8,849 m
- Around the Earth: distance / 40,075 km

## Examples

```sh
curl localhost:8123/api/online
curl 'localhost:8123/api/players/Notch/stats?pretty=1'
curl localhost:8123/api/players/Notch/collections/mined
curl 'localhost:8123/api/leaderboard/travel_cm?limit=3'
curl localhost:8123/api/leaderboard/mined/diamond_ore
```

```js
const r = await fetch('http://10.5.5.199:8123/api/leaderboard/deaths?limit=5');
const top = await r.json();
```
