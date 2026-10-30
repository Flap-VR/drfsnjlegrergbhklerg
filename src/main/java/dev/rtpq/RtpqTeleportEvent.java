package dev.rtpq;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired right before an RTPQ teleport actually happens (after the countdown). */
public class RtpqTeleportEvent extends Event implements Cancellable {

    public enum Cause { RANDOM, REQUEST }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Player target;
    private final Cause cause;
    private Location destination;
    private boolean cancelled;

    public RtpqTeleportEvent(Player player, Player target, Location destination, Cause cause) {
        this.player = player;
        this.target = target;
        this.destination = destination;
        this.cause = cause;
    }

    public Player getPlayer() { return player; }
    public Player getTarget() { return target; }
    public Cause getCause() { return cause; }
    public Location getDestination() { return destination; }
    public void setDestination(Location destination) { this.destination = destination; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
