package dev.sebi.sebstats;

import java.io.IOException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class SebStatsPlugin extends JavaPlugin {
    private WebServer web;
    private BukkitTask task;
    private BukkitTask asyncTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        addMissingDefaults();
        StatsCollector collector = new StatsCollector();
        long period = Math.max(1, getConfig().getInt("refresh-seconds", 3)) * 20L;
        task = getServer().getScheduler().runTaskTimer(this, collector::tick, 20L, period);
        asyncTask = getServer().getScheduler().runTaskTimerAsynchronously(this, collector::rebuild, 30L, period);

        getCommand("stats").setExecutor(new StatsCommand(this, collector));

        try {
            web = new WebServer(getConfig().getString("bind-address", "0.0.0.0"),
                    getConfig().getInt("port", 8123), collector::json);
            getLogger().info("Stats website running on port " + web.port());
        } catch (IOException e) {
            getLogger().severe("Could not start stats website: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /** Adds keys (with their comments) introduced by newer versions to an existing config.yml. */
    private void addMissingDefaults() {
        var config = getConfig();
        var defaults = config.getDefaults();
        if (defaults == null) return;
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            if (config.contains(key, true)) continue;
            config.set(key, defaults.get(key));
            config.setComments(key, defaults.getComments(key));
            changed = true;
        }
        if (changed) saveConfig();
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        if (asyncTask != null) asyncTask.cancel();
        if (web != null) web.stop();
    }
}
