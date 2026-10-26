# SebStats

A small Paper plugin (Minecraft 26.3) that hosts a live stats website on its own port: leaderboards, per-player collections and fun facts, plus a JSON API.

- Website: `http://<server-ip>:8123`
- API: see [docs/API.md](docs/API.md)
- Config: `plugins/SebStats/config.yml` (`bind-address`, `port`, `refresh-seconds`, `website-url`)
- Command: `/stats [player]` shows a player's three best leaderboard placings and a clickable link to the website. Set `website-url` to your public address (e.g. a Cloudflare tunnel hostname); leave it empty to hide the link.

## Build

Requires JDK 25 and Maven.

```sh
mvn package   # produces target/SebStats.jar; drop it into the server's plugins/ folder
```
