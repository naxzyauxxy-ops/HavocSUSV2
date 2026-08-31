package com.havocsus.alerts;

import com.havocsus.HavocSusPlugin;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Listens to the anti-cheats' own flag events and records them.
 *
 * Every supported anti-cheat fires a Bukkit event when it flags someone, but
 * they share no interface and most are paid, so compiling against them isn't an
 * option. Instead each source is described in config - event class, and which
 * getters to try for the player, check name and violation level - and hooked
 * reflectively. Anything we can load, we listen to.
 *
 * Being config-driven matters: if a class or method name is wrong for your
 * version, you fix it in config.yml and reload, with no rebuild. /hs diag lists
 * exactly which sources bound successfully.
 */
public final class AlertBridge {

    private record Source(String id, String eventClass, List<String> playerGetters,
                          List<String> checkGetters, List<String> violationGetters) {
    }

    private final HavocSusPlugin plugin;
    private final AlertStore store;
    private final Listener listener = new Listener() {
    };
    private final Map<String, String> results = new LinkedHashMap<>();

    public AlertBridge(HavocSusPlugin plugin, AlertStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public Map<String, String> results() {
        return results;
    }

    public int boundCount() {
        return (int) results.values().stream().filter(v -> v.startsWith("hooked")).count();
    }

    public void register() {
        results.clear();
        ConfigurationSection sources = plugin.getConfig().getConfigurationSection("alerts.sources");
        if (sources == null) {
            plugin.getLogger().warning("No alerts.sources configured - live alert capture is off.");
            return;
        }

        for (String id : sources.getKeys(false)) {
            ConfigurationSection section = sources.getConfigurationSection(id);
            if (section == null || !section.getBoolean("enabled", true)) {
                continue;
            }
            String eventClass = section.getString("event", "");
            if (eventClass == null || eventClass.isBlank()) {
                continue;
            }
            Source source = new Source(id, eventClass,
                    candidates(section.getString("player", "getPlayer")),
                    candidates(section.getString("check", "getCheck")),
                    candidates(section.getString("violation", "getViolations")));
            bind(source);
        }

        int bound = boundCount();
        plugin.getLogger().info("Live alert capture: " + bound + " of "
                + results.size() + " sources hooked.");
    }

    private static List<String> candidates(String raw) {
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
        }
        return out;
    }

    private void bind(Source source) {
        Class<?> eventClass;
        try {
            eventClass = Class.forName(source.eventClass());
        } catch (Throwable t) {
            // Overwhelmingly just means that anti-cheat isn't installed.
            results.put(source.id(), "not installed");
            return;
        }
        if (!Event.class.isAssignableFrom(eventClass)) {
            results.put(source.id(), "not a Bukkit event");
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Event> cast = (Class<? extends Event>) eventClass;
            plugin.getServer().getPluginManager().registerEvent(
                    cast, listener, EventPriority.MONITOR,
                    (l, event) -> handle(source, event),
                    plugin, true);
            results.put(source.id(), "hooked (" + eventClass.getSimpleName() + ")");
        } catch (Throwable t) {
            results.put(source.id(), "failed: " + t);
        }
    }

    private void handle(Source source, Event event) {
        try {
            Player player = resolvePlayer(event, source.playerGetters());
            if (player == null) {
                return;
            }
            String check = resolveString(event, source.checkGetters());
            double violation = resolveDouble(event, source.violationGetters());
            store.record(player.getUniqueId(), player.getName(),
                    prettyId(source.id()), check, violation);
        } catch (Throwable t) {
            // A malformed mapping must never break the anti-cheat's own event.
            results.put(source.id(), "error: " + t);
        }
    }

    // ------------------------------------------------------------------
    // reflective plumbing
    // ------------------------------------------------------------------

    private Player resolvePlayer(Object event, List<String> getters) {
        for (String getter : getters) {
            Object value = invoke(event, getter);
            Player player = toPlayer(value);
            if (player != null) {
                return player;
            }
        }
        // Some events expose the player only as a field on a wrapper; try the
        // usual second hop.
        for (String getter : getters) {
            Object value = invoke(event, getter);
            if (value == null) {
                continue;
            }
            for (String nested : List.of("getBukkitPlayer", "getPlayer", "player", "getUniqueId", "getUUID")) {
                Player player = toPlayer(invoke(value, nested));
                if (player != null) {
                    return player;
                }
            }
        }
        return null;
    }

    private Player toPlayer(Object value) {
        if (value instanceof Player player) {
            return player;
        }
        if (value instanceof UUID uuid) {
            return plugin.getServer().getPlayer(uuid);
        }
        if (value instanceof OfflinePlayer offline) {
            return offline.getPlayer();
        }
        if (value instanceof String name) {
            return plugin.getServer().getPlayerExact(name);
        }
        return null;
    }

    private String resolveString(Object event, List<String> getters) {
        for (String getter : getters) {
            Object value = invoke(event, getter);
            if (value == null) {
                continue;
            }
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
            if (value instanceof Enum<?> constant) {
                return constant.name();
            }
            // A Check object: ask it for its name.
            for (String nested : List.of("getCheckName", "getName", "name", "getType")) {
                Object inner = invoke(value, nested);
                if (inner instanceof String text && !text.isBlank()) {
                    return text;
                }
                if (inner instanceof Enum<?> constant) {
                    return constant.name();
                }
            }
            String fallback = String.valueOf(value);
            if (!fallback.isBlank() && !fallback.contains("@")) {
                return fallback;
            }
        }
        return "Unknown";
    }

    private double resolveDouble(Object event, List<String> getters) {
        for (String getter : getters) {
            Object value = invoke(event, getter);
            if (value instanceof Number number) {
                return number.doubleValue();
            }
        }
        return 0.0D;
    }

    private Object invoke(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String prettyId(String id) {
        if (id == null || id.isEmpty()) {
            return "Anticheat";
        }
        return id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1);
    }
}
