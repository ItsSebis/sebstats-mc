package dev.sebi.sebstats;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

/** Answers server-list pings with a random fun fact, which {@link #rotate()} swaps for a new one. */
final class Motd implements Listener {
    private final StatsCollector collector;
    private volatile Component current;
    private volatile String currentText = "";

    Motd(StatsCollector collector) {
        this.collector = collector;
    }

    /** Picks a new fact, never the one already showing unless it is the only one. Keeps the old MOTD if there are none. */
    void rotate() {
        List<String> facts = collector.motdFacts();
        if (facts.isEmpty()) return;
        String pick;
        do {
            pick = facts.get(ThreadLocalRandom.current().nextInt(facts.size()));
        } while (facts.size() > 1 && pick.equals(currentText));
        currentText = pick;
        current = Component.text(pick, NamedTextColor.YELLOW);
    }

    @EventHandler
    public void onPing(ServerListPingEvent event) {
        if (current == null) rotate(); // first ping before the first scheduled rotation
        if (current != null) event.motd(current);
    }
}
