# RTPQ

Paper 1.21.11, Java 21.

## Build
```
mvn clean package
```
Drop `target/RTPQ-1.0.0.jar` into `plugins/` and restart.

## Commands
| Command | What it does |
|---|---|
| `/rtpq` | Pick a random eligible player, then countdown and teleport to them |
| `/rtpq <player>` | Send that player a request (clickable ACCEPT / DENY) |
| `/rtpq accept [player]` | Accept a request, the sender's countdown starts |
| `/rtpq deny [player]` | Deny a request |
| `/rtpq cancel` | Cancel your countdown, or your outgoing requests |
| `/rtpq reload` | Reload `config.yml` |

## Permissions
`rtpq.use` (default true), `rtpq.request` (true), `rtpq.exempt` (never picked randomly),
`rtpq.bypass.cooldown`, `rtpq.bypass.countdown`, `rtpq.bypass.prefs` (op), `rtpq.reload` (op).

## Hooking up the settings plugin

**Option A - no dependency.** Write a BYTE to the player's PersistentDataContainer:

```java
NamespacedKey requests = NamespacedKey.fromString("rtpq:allow_requests");
NamespacedKey random   = NamespacedKey.fromString("rtpq:allow_random");
player.getPersistentDataContainer().set(requests, PersistentDataType.BYTE, (byte) 0); // 0 = off, 1 = on
```

**Option B - service override.** Depend on RTPQ and register your own provider:

```java
getServer().getServicesManager().register(
    RtpqPreferences.class, new MyPrefs(), this, ServicePriority.Normal);
```

RTPQ always asks the highest-priority provider, so Option B wins over Option A.

## Events
`RtpqRequestEvent` and `RtpqTeleportEvent` are both cancellable. The teleport event also lets you change the destination.
