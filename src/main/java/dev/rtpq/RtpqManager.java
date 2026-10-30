package dev.rtpq;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RtpqManager {

    private static final class Session {
        final UUID player;
        final UUID target;
        final RtpqTeleportEvent.Cause cause;
        final Location start;
        final int total;
        int ticksLeft;
        int elapsed;
        int shown = -1;
        BukkitTask task;

        Session(UUID player, UUID target, RtpqTeleportEvent.Cause cause, Location start, int total) {
            this.player = player;
            this.target = target;
            this.cause = cause;
            this.start = start;
            this.total = total;
            this.ticksLeft = total;
        }
    }

    private static final class Request {
        final UUID sender;
        final UUID target;
        BukkitTask expiry;

        Request(UUID sender, UUID target) {
            this.sender = sender;
            this.target = target;
        }
    }

    private final RtpqPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Random random = new Random();

    private final Map<UUID, Session> sessions = new HashMap<>();
    /** target -> (sender -> request), insertion ordered so "latest" is the last entry */
    private final Map<UUID, Map<UUID, Request>> requests = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> requestCooldowns = new HashMap<>();

    public RtpqManager(RtpqPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ helpers

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    private RtpqPreferences prefs() {
        RtpqPreferences p = Bukkit.getServicesManager().load(RtpqPreferences.class);
        return p != null ? p : new PdcPreferences(plugin);
    }

    public Component parse(String raw, String... pairs) {
        String s = raw == null ? "" : raw;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            s = Pattern.compile(Pattern.quote(pairs[i]), Pattern.CASE_INSENSITIVE)
                    .matcher(s).replaceAll(Matcher.quoteReplacement(pairs[i + 1]));
        }
        return mm.deserialize(s);
    }

    public void send(CommandSender to, String key, String... pairs) {
        String raw = cfg().getString("messages.prefix", "")
                + cfg().getString("messages." + key, "<red>Missing message: " + key);
        to.sendMessage(parse(raw, pairs));
    }

    private void play(Player p, String path, Float pitchOverride) {
        ConfigurationSection s = cfg().getConfigurationSection("sounds." + path);
        if (s == null) return;
        String name = s.getString("sound", "");
        if (name.isBlank()) return;
        float pitch = pitchOverride != null ? pitchOverride : (float) s.getDouble("pitch", 1.0);
        try {
            p.playSound(Sound.sound(Key.key(name), Sound.Source.MASTER, (float) s.getDouble("volume", 1.0), pitch));
        } catch (Exception ignored) {
            // invalid sound key in config
        }
    }

    private void showTitle(Player p, String key, String... pairs) {
        ConfigurationSection s = cfg().getConfigurationSection("titles." + key);
        if (s == null) return;
        Title.Times times = Title.Times.times(
                Duration.ofMillis(s.getLong("fade-in-ms", 0)),
                Duration.ofMillis(s.getLong("stay-ms", 1000)),
                Duration.ofMillis(s.getLong("fade-out-ms", 0)));
        p.showTitle(Title.title(parse(s.getString("title", ""), pairs), parse(s.getString("subtitle", ""), pairs), times));
    }

    private void spawn(String name, Location loc, int count, double dx, double dy, double dz, double speed) {
        if (name == null || name.isBlank() || loc.getWorld() == null) return;
        Particle particle = Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
        if (particle == null) return;
        try {
            loc.getWorld().spawnParticle(particle, loc, count, dx, dy, dz, speed);
        } catch (IllegalArgumentException ignored) {
            // particle needs extra data, not supported here
        }
    }

    private boolean blockedWorld(Player p) {
        return cfg().getStringList("settings.blocked-worlds").contains(p.getWorld().getName());
    }

    public boolean hasSession(UUID id) {
        return sessions.containsKey(id);
    }

    // ------------------------------------------------------------------ random

    public void startRandom(Player player) {
        if (!canStart(player)) return;

        List<Player> pool = new ArrayList<>();
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (candidate.equals(player)) continue;
            if (eligibleForRandom(candidate, player)) pool.add(candidate);
        }
        if (pool.isEmpty()) {
            send(player, "no-players");
            return;
        }
        begin(player, pool.get(random.nextInt(pool.size())), RtpqTeleportEvent.Cause.RANDOM);
    }

    private boolean eligibleForRandom(Player candidate, Player seeker) {
        if (candidate.hasPermission("rtpq.exempt")) return false;
        if (!seeker.canSee(candidate)) return false;
        for (MetadataValue v : candidate.getMetadata("vanished")) {
            if (v.asBoolean()) return false;
        }
        if (cfg().getStringList("random.excluded-gamemodes").contains(candidate.getGameMode().name())) return false;
        if (cfg().getStringList("random.excluded-worlds").contains(candidate.getWorld().getName())) return false;
        if (!cfg().getBoolean("settings.cross-world", true) && !candidate.getWorld().equals(seeker.getWorld())) return false;
        return prefs().allowsRandom(candidate);
    }

    /** Checks active session, blocked world and cooldown; messages the player on failure. */
    private boolean canStart(Player player) {
        if (sessions.containsKey(player.getUniqueId())) {
            send(player, "already-active");
            return false;
        }
        if (blockedWorld(player)) {
            send(player, "world-blocked");
            return false;
        }
        if (!player.hasPermission("rtpq.bypass.cooldown")) {
            Long last = cooldowns.get(player.getUniqueId());
            if (last != null) {
                long left = last + cfg().getLong("settings.cooldown-seconds", 30) * 1000L - System.currentTimeMillis();
                if (left > 0) {
                    send(player, "cooldown", "%seconds%", String.valueOf((long) Math.ceil(left / 1000.0)));
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ countdown

    private void begin(Player player, Player target, RtpqTeleportEvent.Cause cause) {
        int seconds = Math.max(0, cfg().getInt("settings.countdown-seconds", 5));
        if (player.hasPermission("rtpq.bypass.countdown")) seconds = 0;

        Session s = new Session(player.getUniqueId(), target.getUniqueId(), cause, player.getLocation(), seconds * 20);
        sessions.put(player.getUniqueId(), s);

        play(player, "found", null);
        if (cause == RtpqTeleportEvent.Cause.RANDOM && cfg().getBoolean("random.notify-target", false)) {
            send(target, "random-notify", "%player%", player.getName());
        }
        if (cause == RtpqTeleportEvent.Cause.REQUEST) {
            send(target, "coming-notify", "%player%", player.getName(), "%seconds%", String.valueOf(seconds));
        }
        s.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(s), 0L, 2L);
    }

    private void tick(Session s) {
        Player p = Bukkit.getPlayer(s.player);
        if (p == null || !p.isOnline() || p.isDead()) {
            stop(s);
            return;
        }
        Player t = Bukkit.getPlayer(s.target);
        if (t == null || !t.isOnline()) {
            abort(s, p, "cancel-target-left", false);
            return;
        }
        if (moved(p, s)) {
            abort(s, p, "cancel-moved", true);
            return;
        }

        int secondsLeft = (int) Math.ceil(s.ticksLeft / 20.0);
        double progress = s.total == 0 ? 1.0 : 1.0 - (double) s.ticksLeft / s.total;

        if (secondsLeft > 0 && secondsLeft != s.shown) {
            s.shown = secondsLeft;
            showTitle(p, "countdown", "%seconds%", String.valueOf(secondsLeft), "%target%", t.getName());
            ConfigurationSection tick = cfg().getConfigurationSection("sounds.tick");
            if (tick != null) {
                double a = tick.getDouble("pitch-start", 0.8);
                double b = tick.getDouble("pitch-end", 2.0);
                play(p, "tick", (float) (a + (b - a) * progress));
            }
        }

        if (secondsLeft > 0) actionBar(p, progress, secondsLeft);
        spiral(p, s, progress);

        if (s.ticksLeft <= 0) {
            finish(s, p, t);
            return;
        }
        s.ticksLeft -= 2;
        s.elapsed += 2;
    }

    private boolean moved(Player p, Session s) {
        Location now = p.getLocation();
        if (!Objects.equals(now.getWorld(), s.start.getWorld())) return true;
        double tol = cfg().getDouble("settings.movement-tolerance", 0.1);
        return now.distanceSquared(s.start) > tol * tol; // yaw/pitch ignored: looking around is fine
    }

    private void actionBar(Player p, double progress, int secondsLeft) {
        ConfigurationSection c = cfg().getConfigurationSection("actionbar");
        if (c == null || !c.getBoolean("enabled", true)) return;
        int len = Math.max(1, c.getInt("length", 20));
        int filled = (int) Math.round(progress * len);
        String bar = c.getString("format", "%filled%%empty% %seconds%s");
        p.sendActionBar(parse(bar,
                "%filled%", c.getString("filled-char", "█").repeat(filled),
                "%empty%", c.getString("empty-char", "░").repeat(len - filled),
                "%seconds%", String.valueOf(secondsLeft)));
    }

    private void spiral(Player p, Session s, double progress) {
        ConfigurationSection c = cfg().getConfigurationSection("effects.countdown");
        if (c == null || !c.getBoolean("enabled", true)) return;
        String particle = c.getString("particle", "end_rod");
        int strands = Math.max(1, c.getInt("strands", 3));
        double radius = c.getDouble("radius", 1.0);
        double height = Math.max(0.1, c.getDouble("height", 2.0));
        if (c.getBoolean("converge", true)) radius *= (1.0 - 0.6 * progress);

        Location base = p.getLocation();
        for (int i = 0; i < strands; i++) {
            double angle = s.elapsed * 0.30 + i * (Math.PI * 2 / strands);
            double y = (s.elapsed * 0.04 + i * (height / strands)) % height;
            spawn(particle, base.clone().add(Math.cos(angle) * radius, y, Math.sin(angle) * radius), 1, 0, 0, 0, 0);
        }
    }

    private void burst(Location loc) {
        ConfigurationSection c = cfg().getConfigurationSection("effects.finish");
        if (c == null || !c.getBoolean("enabled", true)) return;
        String particle = c.getString("particle", "end_rod");
        int points = Math.max(4, c.getInt("ring-points", 36));
        double r = c.getDouble("ring-radius", 1.5);
        for (int h = 0; h < 3; h++) {
            for (int i = 0; i < points; i++) {
                double angle = i * (Math.PI * 2 / points);
                spawn(particle, loc.clone().add(Math.cos(angle) * r, 0.2 + h * 0.8, Math.sin(angle) * r), 1, 0, 0.05, 0, 0.02);
            }
        }
        spawn(c.getString("core-particle", "firework"), loc.clone().add(0, 1, 0), 40, 0.4, 0.6, 0.4, 0.05);
    }

    private void finish(Session s, Player p, Player t) {
        stop(s);

        if (!cfg().getBoolean("settings.cross-world", true) && !p.getWorld().equals(t.getWorld())) {
            p.clearTitle();
            send(p, "cancel-cross-world");
            return;
        }

        Location dest = t.getLocation();
        dest.setYaw(p.getLocation().getYaw());
        dest.setPitch(p.getLocation().getPitch());

        RtpqTeleportEvent event = new RtpqTeleportEvent(p, t, dest, s.cause);
        Bukkit.getPluginManager().callEvent(event);
        p.clearTitle();
        p.sendActionBar(Component.empty());
        if (event.isCancelled()) return;

        Location from = p.getLocation();
        p.teleportAsync(event.getDestination()).thenAccept(ok ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!ok) {
                        send(p, "teleport-failed");
                        return;
                    }
                    cooldowns.put(p.getUniqueId(), System.currentTimeMillis());
                    play(p, "success", null);
                    showTitle(p, "success", "%target%", t.getName());
                    send(p, "teleported", "%target%", t.getName());
                    if (cfg().getBoolean("settings.arrive-notify", true) && t.isOnline()) {
                        send(t, "arrived", "%player%", p.getName());
                    }
                    burst(from);
                    burst(p.getLocation());
                    if (cfg().getBoolean("effects.finish.lightning-effect", true) && p.getWorld() != null) {
                        p.getWorld().strikeLightningEffect(p.getLocation());
                    }
                }));
    }

    private void stop(Session s) {
        if (s.task != null) s.task.cancel();
        sessions.remove(s.player, s);
    }

    private void abort(Session s, Player p, String messageKey, boolean moveSound) {
        stop(s);
        p.clearTitle();
        p.sendActionBar(Component.empty());
        if (moveSound) play(p, "cancel", null);
        showTitle(p, "cancelled");
        send(p, messageKey);
    }

    /** Used by listeners (damage etc.). */
    public void cancelSession(UUID id, String messageKey) {
        Session s = sessions.get(id);
        if (s == null) return;
        Player p = Bukkit.getPlayer(id);
        if (p == null) {
            stop(s);
            return;
        }
        abort(s, p, messageKey, true);
    }

    // ------------------------------------------------------------------ requests

    public void sendRequest(Player sender, Player target) {
        if (sender.equals(target)) {
            send(sender, "request-self");
            return;
        }
        if (sessions.containsKey(sender.getUniqueId())) {
            send(sender, "already-active");
            return;
        }
        if (blockedWorld(sender)) {
            send(sender, "world-blocked");
            return;
        }
        if (!sender.hasPermission("rtpq.bypass.prefs") && !prefs().allowsRequests(target)) {
            send(sender, "requests-disabled", "%player%", target.getName());
            return;
        }

        long now = System.currentTimeMillis();
        Long lastReq = requestCooldowns.get(sender.getUniqueId());
        if (lastReq != null) {
            long left = lastReq + cfg().getLong("settings.request-cooldown-seconds", 5) * 1000L - now;
            if (left > 0) {
                send(sender, "request-cooldown", "%seconds%", String.valueOf((long) Math.ceil(left / 1000.0)));
                return;
            }
        }

        Map<UUID, Request> incoming = requests.computeIfAbsent(target.getUniqueId(), k -> new LinkedHashMap<>());
        if (incoming.containsKey(sender.getUniqueId())) {
            send(sender, "request-duplicate", "%player%", target.getName());
            return;
        }
        if (incoming.size() >= cfg().getInt("settings.max-pending-per-target", 5)) {
            send(sender, "request-full", "%player%", target.getName());
            return;
        }

        RtpqRequestEvent event = new RtpqRequestEvent(sender, target);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            if (incoming.isEmpty()) requests.remove(target.getUniqueId());
            return;
        }

        Request r = new Request(sender.getUniqueId(), target.getUniqueId());
        long expire = cfg().getLong("settings.request-expire-seconds", 60);
        r.expiry = Bukkit.getScheduler().runTaskLater(plugin, () -> expire(r), expire * 20L);
        incoming.put(sender.getUniqueId(), r);
        requestCooldowns.put(sender.getUniqueId(), now);

        String exp = String.valueOf(expire);
        send(sender, "request-sent", "%player%", target.getName(), "%expire%", exp);
        send(target, "request-received", "%player%", sender.getName(), "%expire%", exp);
        target.sendMessage(parse(cfg().getString("messages.request-buttons", ""), "%player%", sender.getName()));
        play(target, "request", null);
    }

    private Request findRequest(Player target, String name) {
        Map<UUID, Request> incoming = requests.get(target.getUniqueId());
        if (incoming == null || incoming.isEmpty()) return null;
        Request found = null;
        for (Request r : incoming.values()) {
            if (name == null) {
                found = r; // keeps the latest
            } else {
                Player sender = Bukkit.getPlayer(r.sender);
                if (sender != null && sender.getName().equalsIgnoreCase(name)) return r;
            }
        }
        return found;
    }

    public void accept(Player target, String name) {
        Request r = findRequest(target, name);
        if (r == null) {
            send(target, "no-request");
            return;
        }
        removeRequest(r);
        Player sender = Bukkit.getPlayer(r.sender);
        if (sender == null) {
            send(target, "sender-offline");
            return;
        }
        if (sessions.containsKey(sender.getUniqueId())) {
            send(target, "sender-busy", "%player%", sender.getName());
            return;
        }
        if (!canStart(sender)) {
            send(target, "sender-cant", "%player%", sender.getName());
            return;
        }
        send(target, "request-accepted", "%player%", sender.getName());
        send(sender, "request-accepted-sender", "%player%", target.getName());
        begin(sender, target, RtpqTeleportEvent.Cause.REQUEST);
    }

    public void deny(Player target, String name) {
        Request r = findRequest(target, name);
        if (r == null) {
            send(target, "no-request");
            return;
        }
        removeRequest(r);
        Player sender = Bukkit.getPlayer(r.sender);
        String senderName = sender != null ? sender.getName() : "?";
        send(target, "request-denied", "%player%", senderName);
        if (sender != null) send(sender, "request-denied-sender", "%player%", target.getName());
    }

    /** /rtpq cancel: stops your active countdown, otherwise your outgoing requests. */
    public void cancel(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s != null) {
            abort(s, player, "cancel-manual", false);
            return;
        }
        List<Request> outgoing = new ArrayList<>();
        for (Map<UUID, Request> m : requests.values()) {
            Request r = m.get(player.getUniqueId());
            if (r != null) outgoing.add(r);
        }
        if (outgoing.isEmpty()) {
            send(player, "nothing-to-cancel");
            return;
        }
        outgoing.forEach(this::removeRequest);
        send(player, "request-cancelled");
    }

    private void expire(Request r) {
        removeRequest(r);
        Player sender = Bukkit.getPlayer(r.sender);
        Player target = Bukkit.getPlayer(r.target);
        if (sender != null) {
            send(sender, "request-expired-sender", "%player%", target != null ? target.getName() : "?");
        }
        if (target != null) {
            send(target, "request-expired-target", "%player%", sender != null ? sender.getName() : "?");
        }
    }

    private void removeRequest(Request r) {
        if (r.expiry != null) r.expiry.cancel();
        Map<UUID, Request> m = requests.get(r.target);
        if (m != null) {
            m.remove(r.sender, r);
            if (m.isEmpty()) requests.remove(r.target);
        }
    }

    public List<String> pendingSenderNames(Player target) {
        List<String> names = new ArrayList<>();
        Map<UUID, Request> incoming = requests.get(target.getUniqueId());
        if (incoming == null) return names;
        for (Request r : incoming.values()) {
            Player p = Bukkit.getPlayer(r.sender);
            if (p != null) names.add(p.getName());
        }
        return names;
    }

    public boolean allowsRequests(Player p) {
        return prefs().allowsRequests(p);
    }

    // ------------------------------------------------------------------ lifecycle

    public void handleQuit(UUID id) {
        Session s = sessions.get(id);
        if (s != null) stop(s);

        Map<UUID, Request> incoming = requests.get(id);
        if (incoming != null) {
            for (Request r : new ArrayList<>(incoming.values())) {
                Player sender = Bukkit.getPlayer(r.sender);
                Player leaving = Bukkit.getPlayer(id);
                removeRequest(r);
                if (sender != null) {
                    send(sender, "request-target-left", "%player%", leaving != null ? leaving.getName() : "?");
                }
            }
        }

        List<Request> outgoing = new ArrayList<>();
        for (Map<UUID, Request> m : requests.values()) {
            Request r = m.get(id);
            if (r != null) outgoing.add(r);
        }
        outgoing.forEach(this::removeRequest);
    }

    public void shutdown() {
        for (Session s : new ArrayList<>(sessions.values())) {
            if (s.task != null) s.task.cancel();
        }
        sessions.clear();
        for (Map<UUID, Request> m : requests.values()) {
            for (Request r : m.values()) {
                if (r.expiry != null) r.expiry.cancel();
            }
        }
        requests.clear();
    }
}
