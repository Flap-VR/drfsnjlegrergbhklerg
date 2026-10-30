package dev.rtpq;

import org.bukkit.entity.Player;

/**
 * Hook for other plugins (e.g. your upcoming settings plugin).
 * Register an implementation through Bukkit's ServicesManager with a priority
 * higher than Lowest and RTPQ will use it instead of its built-in PDC lookup.
 */
public interface RtpqPreferences {

    /** Can other players send /rtpq &lt;thisPlayer&gt; requests? */
    boolean allowsRequests(Player player);

    /** Can this player be picked by a random /rtpq? */
    boolean allowsRandom(Player player);
}
