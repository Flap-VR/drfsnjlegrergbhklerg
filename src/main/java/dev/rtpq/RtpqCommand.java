package dev.rtpq;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RtpqCommand implements TabExecutor {

    private final RtpqPlugin plugin;
    private final RtpqManager manager;

    public RtpqCommand(RtpqPlugin plugin, RtpqManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("rtpq.reload")) {
                manager.send(sender, "no-permission");
                return true;
            }
            plugin.reloadConfig();
            manager.send(sender, "reloaded");
            return true;
        }

        if (!(sender instanceof Player player)) {
            manager.send(sender, "players-only");
            return true;
        }
        if (!player.hasPermission("rtpq.use")) {
            manager.send(player, "no-permission");
            return true;
        }

        if (args.length == 0) {
            manager.startRandom(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "accept" -> manager.accept(player, args.length > 1 ? args[1] : null);
            case "deny" -> manager.deny(player, args.length > 1 ? args[1] : null);
            case "cancel" -> manager.cancel(player);
            default -> {
                if (!player.hasPermission("rtpq.request")) {
                    manager.send(player, "no-permission");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null) {
                    manager.send(player, "player-not-found", "%player%", args[0]);
                } else {
                    manager.sendRequest(player, target);
                }
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!(sender instanceof Player player)) return out;

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String sub : List.of("accept", "deny", "cancel")) {
                if (sub.startsWith(prefix)) out.add(sub);
            }
            if (sender.hasPermission("rtpq.reload") && "reload".startsWith(prefix)) out.add("reload");
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.equals(player) || !player.canSee(p)) continue;
                if (!player.hasPermission("rtpq.bypass.prefs") && !manager.allowsRequests(p)) continue;
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("deny"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (String name : manager.pendingSenderNames(player)) {
                if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(name);
            }
        }
        return out;
    }
}
