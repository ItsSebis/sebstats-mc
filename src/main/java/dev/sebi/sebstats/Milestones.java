package dev.sebi.sebstats;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Celebrates play-time milestones (100h, 250h, 500h, 1000h, then every 1000h) with a chat announcement,
 * a title and fireworks around the player.
 *
 * <p>The last milestone celebrated per player is kept in {@code milestones.yml}. A player seen for the
 * first time is recorded at the milestone they already passed, so installing the plugin on an existing
 * server doesn't celebrate everyone at once.
 */
final class Milestones implements Listener {
    private static final long TICKS_PER_HOUR = 72_000;
    private static final int WAVES = 10;
    private static final long WAVE_INTERVAL_TICKS = 10;
    private static final double RING_RADIUS = 7;
    private static final Color[] COLORS = {
        Color.RED, Color.ORANGE, Color.YELLOW, Color.LIME, Color.AQUA, Color.FUCHSIA, Color.WHITE,
    };

    private final Plugin plugin;
    private final File file;
    private final YamlConfiguration state;
    private final NamespacedKey tag;

    Milestones(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "milestones.yml");
        this.state = YamlConfiguration.loadConfiguration(file);
        this.tag = new NamespacedKey(plugin, "celebration");
    }

    /** Highest milestone in hours reached by a player with this many hours played; 0 if none. */
    static int milestoneFor(long hours) {
        if (hours >= 1000) return (int) (hours / 1000 * 1000);
        for (int m : new int[] {500, 250, 100}) {
            if (hours >= m) return m;
        }
        return 0;
    }

    /** Main thread. Celebrates any online player who has passed a new milestone. */
    void check() {
        boolean changed = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            String key = p.getUniqueId().toString();
            int reached = milestoneFor(p.getStatistic(Statistic.PLAY_ONE_MINUTE) / TICKS_PER_HOUR);
            if (!state.contains(key)) {
                state.set(key, reached); // already-passed milestones are not celebrated retroactively
                changed = true;
            } else if (reached > state.getInt(key)) {
                state.set(key, reached);
                changed = true;
                celebrate(p, reached);
            }
        }
        if (changed) save();
    }

    private void save() {
        try {
            state.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save milestones.yml: " + e.getMessage());
        }
    }

    private void celebrate(Player p, int hours) {
        plugin.getLogger().info(p.getName() + " reached " + hours + " hours of play time.");
        Component label = Component.text(String.format("%,d hours", hours), NamedTextColor.GOLD, TextDecoration.BOLD);
        Bukkit.broadcast(Component.text("🎉 ", NamedTextColor.YELLOW)
                .append(Component.text(p.getName(), NamedTextColor.GREEN))
                .append(Component.text(" just reached ", NamedTextColor.YELLOW))
                .append(label)
                .append(Component.text(" of play time!", NamedTextColor.YELLOW)));
        p.showTitle(Title.title(label, Component.text("of play time. Thanks for playing!", NamedTextColor.YELLOW),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1))));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);

        int perWave = hours >= 1000 ? 5 : 3;
        UUID id = p.getUniqueId();
        int[] wave = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            Player online = Bukkit.getPlayer(id);
            if (online == null || ++wave[0] > WAVES) {
                task.cancel();
                return;
            }
            for (int i = 0; i < perWave; i++) launch(online.getLocation());
        }, 10L, WAVE_INTERVAL_TICKS);
    }

    /** Detonates a firework on a ring around {@code center}, far enough away not to hurt anyone in the middle. */
    private void launch(Location center) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double angle = r.nextDouble(Math.PI * 2);
        Location at = center.clone().add(Math.cos(angle) * RING_RADIUS, 2 + r.nextDouble(4), Math.sin(angle) * RING_RADIUS);
        Firework fw = center.getWorld().spawn(at, Firework.class, f -> {
            f.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            FireworkMeta meta = f.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .with(r.nextBoolean() ? FireworkEffect.Type.BALL_LARGE : FireworkEffect.Type.STAR)
                    .withColor(COLORS[r.nextInt(COLORS.length)], COLORS[r.nextInt(COLORS.length)])
                    .withFade(COLORS[r.nextInt(COLORS.length)])
                    .flicker(r.nextBoolean()).trail(r.nextBoolean()).build());
            f.setFireworkMeta(meta);
        });
        fw.detonate();
    }

    /** Belt and braces: a celebration firework must never damage anyone. */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Entity damager = e.getDamager();
        if (damager instanceof Firework f && f.getPersistentDataContainer().has(tag, PersistentDataType.BYTE)) {
            e.setCancelled(true);
        }
    }
}
