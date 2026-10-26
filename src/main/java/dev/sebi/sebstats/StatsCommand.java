package dev.sebi.sebstats;

import dev.sebi.sebstats.Boards.Placement;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** /stats [player]: the player's three best leaderboard placings plus a link to the website. */
final class StatsCommand implements TabExecutor {
    private static final int SHOWN = 3;

    private final Plugin plugin;
    private final StatsCollector collector;

    StatsCommand(Plugin plugin, StatsCollector collector) {
        this.plugin = plugin;
        this.collector = collector;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String target;
        if (args.length > 0) {
            target = args[0];
        } else if (sender instanceof Player p) {
            target = p.getName();
        } else {
            sender.sendMessage(Component.text("Usage: /" + label + " <player>", NamedTextColor.RED));
            return true;
        }

        var id = collector.uuidByName(target);
        if (id.isEmpty()) {
            sender.sendMessage(Component.text("No stats found for " + target + ".", NamedTextColor.RED));
            return true;
        }

        List<Placement> best = collector.placements(id.get()).stream().limit(SHOWN).toList();
        sender.sendMessage(Component.text("Top placements for ", NamedTextColor.GOLD)
                .append(Component.text(collector.nameOf(id.get()), NamedTextColor.YELLOW)));
        if (best.isEmpty()) {
            sender.sendMessage(Component.text("Nothing ranked yet, keep playing!", NamedTextColor.GRAY));
        }
        for (Placement pl : best) {
            sender.sendMessage(Component.text("#" + pl.rank(), rankColor(pl.rank()), TextDecoration.BOLD)
                    .append(Component.text(" " + pl.board(), NamedTextColor.WHITE))
                    .append(Component.text("  " + pl.value(), NamedTextColor.GREEN))
                    .append(Component.text("  (of " + pl.total() + ")", NamedTextColor.DARK_GRAY)));
        }

        String url = plugin.getConfig().getString("website-url", "").trim();
        if (!url.isEmpty()) {
            sender.sendMessage(Component.text("Full leaderboards: ", NamedTextColor.GRAY)
                    .append(Component.text(url, NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                            .clickEvent(ClickEvent.openUrl(url))));
        }
        return true;
    }

    private static NamedTextColor rankColor(int rank) {
        return switch (rank) {
            case 1 -> NamedTextColor.GOLD;
            case 2 -> NamedTextColor.GRAY;
            case 3 -> NamedTextColor.RED;
            default -> NamedTextColor.WHITE;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase();
        return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                .filter(n -> n.toLowerCase().startsWith(prefix)).toList();
    }
}
