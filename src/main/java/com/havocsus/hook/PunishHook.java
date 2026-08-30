package com.havocsus.hook;

import com.havocsus.HavocSusPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the punishment reasons straight out of DonutPunishments' messages.yml.
 *
 * They're defined under `reasons:` there, each with a type, duration and
 * message, and /punish takes the reason key as its argument. Parsing their file
 * rather than hardcoding a list means the dialog always matches whatever the
 * server actually has configured - edit reasons there and they show up here.
 */
public final class PunishHook {

    public record Reason(String key, String type, String duration, String message) {

        public boolean isBan() {
            return "ban".equalsIgnoreCase(type);
        }

        public boolean isMute() {
            return "mute".equalsIgnoreCase(type);
        }

        /** Blank duration means permanent for bans/mutes, per their config comments. */
        public String durationLabel() {
            if (isBan() || isMute()) {
                return duration == null || duration.isBlank() ? "permanent" : duration;
            }
            return "";
        }
    }

    private static final String PLUGIN_NAME = "DonutPunishments";
    private static final long CACHE_MS = 30_000L;

    private final HavocSusPlugin plugin;
    private List<Reason> cached = List.of();
    private long loadedAt;

    public PunishHook(HavocSusPlugin plugin) {
        this.plugin = plugin;
        if (!isAvailable()) {
            plugin.getLogger().info(PLUGIN_NAME + " not found - the punish dialog will use "
                    + "the fallback reasons from HavocSus's own config.");
        }
    }

    public boolean isAvailable() {
        return plugin.getServer().getPluginManager().getPlugin(PLUGIN_NAME) != null;
    }

    public List<Reason> reasons() {
        long now = System.currentTimeMillis();
        if (cached.isEmpty() || now - loadedAt > CACHE_MS) {
            load();
            loadedAt = now;
        }
        return cached;
    }

    private void load() {
        File file = resolveMessagesFile();
        if (file == null || !file.isFile()) {
            cached = fallbackReasons();
            return;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection section = yaml.getConfigurationSection("reasons");
            if (section == null) {
                cached = fallbackReasons();
                return;
            }
            List<Reason> found = new ArrayList<>();
            for (String key : section.getKeys(false)) {
                ConfigurationSection entry = section.getConfigurationSection(key);
                if (entry == null) {
                    continue;
                }
                found.add(new Reason(
                        key,
                        entry.getString("type", "ban"),
                        entry.getString("duration", ""),
                        entry.getString("message", "")));
            }
            cached = found.isEmpty() ? fallbackReasons() : List.copyOf(found);
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not read " + PLUGIN_NAME + " reasons: " + t);
            cached = fallbackReasons();
        }
    }

    private File resolveMessagesFile() {
        Plugin punishments = plugin.getServer().getPluginManager().getPlugin(PLUGIN_NAME);
        if (punishments != null) {
            File file = new File(punishments.getDataFolder(), "messages.yml");
            if (file.isFile()) {
                return file;
            }
        }
        // Plugin might not be enabled yet; fall back to the conventional path.
        File parent = plugin.getDataFolder().getParentFile();
        return parent == null ? null : new File(parent, PLUGIN_NAME + "/messages.yml");
    }

    private List<Reason> fallbackReasons() {
        List<Reason> out = new ArrayList<>();
        for (String raw : plugin.getConfig().getStringList("punish.fallback-reasons")) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            // Format: key|type|duration
            String[] parts = raw.split("\\|");
            out.add(new Reason(
                    parts[0].trim(),
                    parts.length > 1 ? parts[1].trim() : "ban",
                    parts.length > 2 ? parts[2].trim() : "",
                    ""));
        }
        return List.copyOf(out);
    }

    /** The command DonutPunishments expects: /punish &lt;player&gt; &lt;reason key&gt;. */
    public String buildCommand(String targetName, Reason reason) {
        String template = plugin.getConfig().getString("punish.command", "");
        if (template == null || template.isBlank()) {
            template = "punish %player% %reason%";
        }
        return template
                .replace("%player%", targetName)
                .replace("%reason%", reason.key());
    }
}
