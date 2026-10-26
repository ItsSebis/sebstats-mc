package dev.sebi.sebstats;

import java.io.IOException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class SebStatsPlugin extends JavaPlugin {
    private WebServer web;
    private BukkitTask task;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        StatsCollector collector = new StatsCollector();
        long period = Math.max(1, getConfig().getInt("refresh-seconds", 3)) * 20L;
        task = getServer().getScheduler().runTaskTimer(this, collector, 20L, period);

        try {
            web = new WebServer(getConfig().getString("bind-address", "0.0.0.0"),
                    getConfig().getInt("port", 8123), collector::json);
            getLogger().info("Stats website running on port " + web.port());
        } catch (IOException e) {
            getLogger().severe("Could not start stats website: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        if (web != null) web.stop();
    }
}
