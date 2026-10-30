package dev.rtpq;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class RtpqPlugin extends JavaPlugin {

    private RtpqManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Built-in preferences at the lowest priority, so a settings plugin can override them
        // by registering its own RtpqPreferences service at Normal priority or higher.
        getServer().getServicesManager().register(
                RtpqPreferences.class, new PdcPreferences(this), this, ServicePriority.Lowest);

        manager = new RtpqManager(this);

        PluginCommand command = getCommand("rtpq");
        if (command != null) {
            RtpqCommand handler = new RtpqCommand(this, manager);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        getServer().getPluginManager().registerEvents(new RtpqListener(this, manager), this);
        getLogger().info("RTPQ enabled.");
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.shutdown();
    }

    public RtpqManager manager() {
        return manager;
    }
}
