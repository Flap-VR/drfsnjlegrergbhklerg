package dev.rtpq;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired when a player sends an /rtpq &lt;player&gt; request, before it is delivered. */
public class RtpqRequestEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player sender;
    private final Player target;
    private boolean cancelled;

    public RtpqRequestEvent(Player sender, Player target) {
        this.sender = sender;
        this.target = target;
    }

    public Player getSender() { return sender; }
    public Player getTarget() { return target; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
