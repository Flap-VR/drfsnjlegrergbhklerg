package dev.rtpq;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class RtpqListener implements Listener {

    private final RtpqPlugin plugin;
    private final RtpqManager manager;

    public RtpqListener(RtpqPlugin plugin, RtpqManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.handleQuit(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getFinalDamage() <= 0) return;
        if (!plugin.getConfig().getBoolean("settings.cancel-on-damage", true)) return;
        if (manager.hasSession(player.getUniqueId())) {
            manager.cancelSession(player.getUniqueId(), "cancel-damage");
        }
    }
}
