package dev.rtpq;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Built-in preferences. Reads two BYTE keys from the player's PersistentDataContainer:
 *   rtpq:allow_requests  (0 = disabled, anything else = enabled)
 *   rtpq:allow_random    (0 = disabled, anything else = enabled)
 * Missing keys fall back to the "defaults" section of config.yml.
 * A settings plugin can simply write these keys, no dependency needed.
 */
public final class PdcPreferences implements RtpqPreferences {

    private final Plugin plugin;
    private final NamespacedKey requestsKey;
    private final NamespacedKey randomKey;

    public PdcPreferences(Plugin plugin) {
        this.plugin = plugin;
        this.requestsKey = new NamespacedKey(plugin, "allow_requests");
        this.randomKey = new NamespacedKey(plugin, "allow_random");
    }

    @Override
    public boolean allowsRequests(Player player) {
        Byte value = player.getPersistentDataContainer().get(requestsKey, PersistentDataType.BYTE);
        if (value == null) {
            return plugin.getConfig().getBoolean("defaults.allow-requests", true);
        }
        return value != 0;
    }

    @Override
    public boolean allowsRandom(Player player) {
        Byte value = player.getPersistentDataContainer().get(randomKey, PersistentDataType.BYTE);
        if (value == null) {
            return plugin.getConfig().getBoolean("defaults.allow-random", true);
        }
        return value != 0;
    }
}
