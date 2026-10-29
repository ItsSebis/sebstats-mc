# SebStats

A small Paper plugin (Minecraft 26.3) that hosts a live stats website on its own port: leaderboards, per-player collections, an advancements comparison and fun facts, plus a JSON API.

- Website: `http://<server-ip>:8123`
- API: see [docs/API.md](docs/API.md)
- Config: `plugins/SebStats/config.yml` (`bind-address`, `port`, `refresh-seconds`, `website-url`)
- Celebrations: when a player passes 100, 250, 500 or 1000 hours of play time (then every 1000), the server announces it and sets off fireworks around them. Disable with `celebrations: false` in the config.
- MOTD: every 15 minutes the server-list MOTD changes to a random fun fact (player comparisons like "Sebi has died 2.5× as often as Eliya", records, rates, rivalries; general trivia only when there is no data yet). Disable with `motd: false`, change the interval with `motd-minutes`.
- Command: `/stats [player]` shows a player's three best leaderboard placings and a clickable link to the website. Set `website-url` to your public address (e.g. a Cloudflare tunnel hostname); leave it empty to hide the link.

## Build

Requires JDK 25 and Maven.

```sh
mvn package   # produces target/SebStats.jar; drop it into the server's plugins/ folder
```
