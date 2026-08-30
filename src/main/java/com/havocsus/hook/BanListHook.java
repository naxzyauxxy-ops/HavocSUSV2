package com.havocsus.hook;

import com.havocsus.HavocSusPlugin;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Reads active bans out of DonutPunishments' in-memory BanCache.
 *
 * Unlike SUS, DonutPunishments isn't obfuscated - BanCache holds a static
 * BY_UUID map of BanEntry objects with public id/uuid/player/reason/expiresAt/
 * bannedAt fields. Reading that is cheap and needs no database credentials.
 *
 * It is still reflection into someone else's internals, so every failure path
 * degrades to "no ban list dialog, use /banlist" rather than breaking anything.
 */
public final class BanListHook {

    public record BanEntry(String id, String player, String reason,
                           Long expiresAt, long bannedAt) {

        public boolean isPermanent() {
            return expiresAt == null || expiresAt <= 0L;
        }
    }

    private final HavocSusPlugin plugin;
    private boolean available;
    private Field byUuidField;
    private Field idField;
    private Field playerField;
    private Field reasonField;
    private Field expiresField;
    private Field bannedAtField;
    private boolean warned;

    public BanListHook(HavocSusPlugin plugin) {
        this.plugin = plugin;
        resolve();
    }

    private void resolve() {
        if (plugin.getServer().getPluginManager().getPlugin("DonutPunishments") == null) {
            return;
        }
        try {
            // Load through DonutPunishments' OWN classloader. Class.forName here
            // uses HavocSus's loader, which is not guaranteed to see another
            // plugin's classes - that lookup failing is why the ban list fell
            // back to chat.
            Class<?> cache = loadFromOwner("xyz.ezstudio.ban.BanCache");
            byUuidField = cache.getDeclaredField("BY_UUID");
            byUuidField.setAccessible(true);

            Class<?> entry = loadFromOwner("xyz.ezstudio.ban.BanCache$BanEntry");
            idField = field(entry, "id");
            playerField = field(entry, "player");
            reasonField = field(entry, "reason");
            expiresField = field(entry, "expiresAt");
            bannedAtField = field(entry, "bannedAt");

            available = true;
        } catch (Throwable t) {
            plugin.getLogger().info("Ban list dialog unavailable (" + t.getClass().getSimpleName()
                    + ") - the ban list button will run /banlist instead.");
        }
    }

    private Class<?> loadFromOwner(String name) throws ClassNotFoundException {
        var owner = plugin.getServer().getPluginManager().getPlugin("DonutPunishments");
        if (owner != null) {
            try {
                return owner.getClass().getClassLoader().loadClass(name);
            } catch (ClassNotFoundException ignored) {
                // fall through to our own loader
            }
        }
        return Class.forName(name);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    public boolean isAvailable() {
        return available;
    }

    /** Active bans, newest first. Empty if the cache can't be read. */
    public List<BanEntry> bans() {
        if (!available) {
            return List.of();
        }
        try {
            Object raw = byUuidField.get(null);
            if (!(raw instanceof Map<?, ?> map)) {
                return List.of();
            }
            List<BanEntry> out = new ArrayList<>();
            for (Object value : map.values()) {
                if (value == null) {
                    continue;
                }
                Object expires = expiresField.get(value);
                out.add(new BanEntry(
                        String.valueOf(idField.get(value)),
                        String.valueOf(playerField.get(value)),
                        String.valueOf(reasonField.get(value)),
                        expires instanceof Long l ? l : null,
                        bannedAtField.getLong(value)));
            }
            out.sort(Comparator.comparingLong(BanEntry::bannedAt).reversed());
            return out;
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("Could not read the ban cache: " + t
                        + " (this warning won't repeat)");
            }
            return List.of();
        }
    }
}
