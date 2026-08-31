package com.havocsus.alerts;

import com.havocsus.HavocSusPlugin;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
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
    private static final List<String> GENERIC_PLAYER = List.of(
            "getPlayer", "getUser", "getTarget", "getViolator", "getWho");
    private static final List<String> GENERIC_CHECK = List.of(
            "getCheck", "getCheckName", "getHackType", "getType", "getDetection", "getName");
    private static final List<String> GENERIC_VIOLATION = List.of(
            "getViolations", "getViolation", "getVl", "getViolationLevel", "getLevel", "getScore");

    private final Listener listener = new Listener() {
    };
    private final Map<String, String> results = new LinkedHashMap<>();
    private final Map<String, Integer> hits = new LinkedHashMap<>();
    private final Set<String> boundClasses = new LinkedHashSet<>();

    public AlertBridge(HavocSusPlugin plugin, AlertStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    /** Source id -> state, with the number of alerts actually seen. */
    public Map<String, String> results() {
        Map<String, String> out = new LinkedHashMap<>();
        results.forEach((id, state) -> {
            int seen = hits.getOrDefault(id, 0);
            out.put(id, state.startsWith("hooked") ? state + ", " + seen + " seen" : state);
        });
        return out;
    }

    public int boundCount() {
        return (int) results.values().stream().filter(v -> v.startsWith("hooked")).count();
    }

    public void register() {
        results.clear();
        boundClasses.clear();
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

        if (plugin.getConfig().getBoolean("alerts.auto-scan", true)) {
            autoScan();
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
            boundClasses.add(source.eventClass());
            results.put(source.id(), "hooked (" + eventClass.getSimpleName() + ")");
        } catch (Throwable t) {
            results.put(source.id(), "failed: " + t);
        }
    }

    private void handle(Source source, Event event) {
        try {
            Player player = resolvePlayer(event, source.playerGetters());
            if (player == null) {
                // Hooked but unusable is worth surfacing - it looks identical to
                // "no alerts yet" otherwise, which is what made this hard to
                // diagnose in the first place.
                results.put(source.id(), "hooked but no player found on "
                        + event.getEventName() + " - fix the `player` mapping");
                return;
            }
            hits.merge(source.id(), 1, Integer::sum);
            String check = resolveString(event, source.checkGetters());
            double violation = resolveDouble(event, source.violationGetters());
            String antiCheat = prettyId(source.id());
            store.record(player.getUniqueId(), player.getName(), antiCheat, check, violation);

            // Anti-cheat events can fire off the main thread; chat and any
            // follow-up must not.
            final Player flagged = player;
            final String checkName = check;
            final double vl = violation;
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> plugin.alertNotifier().announce(flagged, antiCheat, checkName, vl));
        } catch (Throwable t) {
            // A malformed mapping must never break the anti-cheat's own event.
            results.put(source.id(), "error: " + t);
        }
    }

    /**
     * Finds flag events by looking inside the anti-cheat's own jar.
     *
     * Hardcoding class names does not scale - they differ per anti-cheat and per
     * version, and most of these are paid so the names can't be verified up
     * front. This scans each installed anti-cheat for classes that look like
     * flag events, loads them through that plugin's own classloader and binds
     * whatever is actually a Bukkit event.
     */
    private void autoScan() {
        List<String> names = plugin.getConfig().getStringList("alerts.auto-scan-plugins");
        for (String pluginName : names) {
            Plugin target = plugin.getServer().getPluginManager().getPlugin(pluginName);
            if (target == null) {
                continue;
            }
            String id = pluginName.toLowerCase(Locale.ROOT);
            if (results.containsKey(id) && results.get(id).startsWith("hooked")) {
                continue; // already covered by an explicit source
            }
            List<String> found = scanJar(target);
            if (found.isEmpty()) {
                results.putIfAbsent(id, "installed, no flag event found");
                continue;
            }
            int bound = 0;
            for (String className : found) {
                if (boundClasses.contains(className)) {
                    continue;
                }
                if (bindDiscovered(id, target, className)) {
                    bound++;
                }
            }
            if (bound == 0) {
                results.putIfAbsent(id, "installed, no bindable event");
            }
        }
    }

    private List<String> scanJar(Plugin target) {
        List<String> best = new ArrayList<>();
        List<String> fallback = new ArrayList<>();
        try {
            File file = new File(target.getClass().getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (!file.isFile()) {
                return List.of();
            }
            try (JarFile jar = new JarFile(file)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.endsWith(".class") || name.contains("$")) {
                        continue;
                    }
                    String className = name.substring(0, name.length() - 6).replace('/', '.');
                    String simple = className.substring(className.lastIndexOf('.') + 1)
                            .toLowerCase(Locale.ROOT);
                    if (!simple.endsWith("event")) {
                        continue;
                    }
                    if (simple.contains("flag") || simple.contains("violation")) {
                        best.add(className);
                    } else if (simple.contains("alert") || simple.contains("detect")
                            || simple.contains("check")) {
                        fallback.add(className);
                    }
                }
            }
        } catch (Throwable ignored) {
            return List.of();
        }
        // Prefer flag/violation events; only fall back to alert/detect names when
        // there are none, so we don't count the same alert twice.
        return best.isEmpty() ? fallback : best;
    }

    private boolean bindDiscovered(String id, Plugin owner, String className) {
        try {
            Class<?> raw = owner.getClass().getClassLoader().loadClass(className);
            if (!Event.class.isAssignableFrom(raw)
                    || java.lang.reflect.Modifier.isAbstract(raw.getModifiers())) {
                return false;
            }
            @SuppressWarnings("unchecked")
            Class<? extends Event> cast = (Class<? extends Event>) raw;

            Source source = new Source(id, className,
                    GENERIC_PLAYER, GENERIC_CHECK, GENERIC_VIOLATION);

            plugin.getServer().getPluginManager().registerEvent(
                    cast, listener, EventPriority.MONITOR,
                    (l, event) -> handle(source, event),
                    plugin, true);

            boundClasses.add(className);
            results.put(id, "hooked (" + raw.getSimpleName() + ", auto)");
            return true;
        } catch (Throwable t) {
            // No static getHandlerList, not an event, or unloadable - skip.
            return false;
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
