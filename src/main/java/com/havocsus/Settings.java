package com.havocsus;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class Settings {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /**
     * Built-in copies of every message.
     *
     * These are the last line of defence: if config.yml can't be read - a
     * hot-reload having broken resource loading, a truncated file, a bad edit -
     * Bukkit's jar-backed defaults are gone too, and every lookup would return
     * an empty string. Silent blank messages are worse than a broken config,
     * because the plugin looks like it's doing nothing at all.
     */
    private static final Map<String, String> DEFAULT_MESSAGES = new HashMap<>();

    static {
        DEFAULT_MESSAGES.put("prefix", "<gradient:#5b8cff:#a45bff><bold>Escort</bold></gradient> <dark_gray>»</dark_gray> ");
        DEFAULT_MESSAGES.put("engaged", "<gray>Escorting <white><target></white>. You are <aqua>vanished</aqua> and locked within <yellow><radius></yellow> blocks.");
        DEFAULT_MESSAGES.put("engaged-hint", "<dark_gray>Double-shift to drop into <white><mode></white> · <white>/escort quit</white> to leave.</dark_gray>");
        DEFAULT_MESSAGES.put("retargeted", "<gray>Now escorting <white><target></white>.</gray>");
        DEFAULT_MESSAGES.put("released", "<gray>Escort ended. <dark_gray>(<reason>)</dark_gray>");
        DEFAULT_MESSAGES.put("target-left", "<red><target></red> left the server.");
        DEFAULT_MESSAGES.put("mode-spectator", "<aqua>Spectator.</aqua> <dark_gray>You pass through blocks; nobody can see you.</dark_gray>");
        DEFAULT_MESSAGES.put("mode-active", "<green><mode>.</green> <dark_gray>Still vanished, still leashed.</dark_gray>");
        DEFAULT_MESSAGES.put("wall", "<red>Leash limit.</red> <gray>You can't get more than <yellow><radius></yellow> blocks from <white><target></white>.</gray>");
        DEFAULT_MESSAGES.put("pulled-back", "<red>Pulled back to the leash boundary.</red>");
        DEFAULT_MESSAGES.put("spectate-blocked", "<red>You may only spectate <white><target></white> during an escort.</red>");
        DEFAULT_MESSAGES.put("teleport-blocked", "<red>That teleport would break the escort leash.</red>");
        DEFAULT_MESSAGES.put("command-blocked", "<red>/<command> is blocked during an escort.</red> <gray>Only <white>/punish</white> and <white>/sus</white> are allowed.</gray>");
        DEFAULT_MESSAGES.put("vanish-locked", "<red>You can't un-vanish during an escort.</red> <gray>Use <white>/escort quit</white> first.</gray>");
        DEFAULT_MESSAGES.put("build-blocked", "<red>You can't break or place blocks during an escort.</red>");
        DEFAULT_MESSAGES.put("interaction-blocked", "<red>You can't touch the world while escorting.</red>");
        DEFAULT_MESSAGES.put("gamemode-locked", "<red>Gamemode is locked during an escort.</red> <gray>Double-shift instead.</gray>");
        DEFAULT_MESSAGES.put("not-escorting", "<red>You aren't in an escort session.</red>");
        DEFAULT_MESSAGES.put("list-header", "<gray>Click a name to teleport and watch them:</gray>");
        DEFAULT_MESSAGES.put("list-empty", "<gray>Nobody else is online.</gray>");
        DEFAULT_MESSAGES.put("player-not-found", "<red><name> isn't online.</red>");
        DEFAULT_MESSAGES.put("patrol-started", "<gray>Free spectate. <aqua>Vanished</aqua>, no leash - watch anyone you like.");
        DEFAULT_MESSAGES.put("patrol-hint", "<dark_gray>Double-shift for <white><mode></white> · <white>/escort quit</white> to leave. Building and commands stay locked.</dark_gray>");
        DEFAULT_MESSAGES.put("patrol-disabled", "<red>Free spectate is disabled on this server.</red>");
        DEFAULT_MESSAGES.put("already-in-session", "<red>You're already in a session.</red> <gray>Use <white>/escort quit</white> first.</gray>");
        DEFAULT_MESSAGES.put("action-bar-patrol", "<gray>Patrol <dark_gray>|</dark_gray> <mode>");
        DEFAULT_MESSAGES.put("action-bar", "<gray><target> <dark_gray>|</dark_gray> <distance>/<radius>m <dark_gray>|</dark_gray> <mode>");
        DEFAULT_MESSAGES.put("action-bar-warn", "<red><target> <dark_gray>|</dark_gray> <distance>/<radius>m <dark_gray>|</dark_gray> <mode>");
        DEFAULT_MESSAGES.put("reloaded", "<green>Config reloaded.</green>");
        DEFAULT_MESSAGES.put("radius-set", "<green>Leash radius set to <yellow><radius></yellow> blocks.</green>");
        DEFAULT_MESSAGES.put("no-permission", "<red>No permission.</red>");
        DEFAULT_MESSAGES.put("usage", "<gray>/escort <white>spec</white> · <white>quit</white> · <white>status</white> · <white>radius <n></white> · <white>reload</white></gray>");
    }

    private final HavocSusPlugin plugin;

    // engage
    public boolean spectatorOnTeleport = true;
    public boolean autoVanish = true;
    public boolean restoreLocationOnExit = true;
    public int detectWindowTicks = 60;
    public boolean susDirectTeleport = true;
    public boolean registerSusIfAbsent = true;
    public Set<String> susPassthroughArgs = new HashSet<>();
    public boolean ignoreRightClick = true;

    // patrol (free spectate)
    public boolean patrolEnabled = true;

    // leash
    public double radius = 150.0D;
    public double warnAt = 140.0D;
    public boolean pullBack = true;
    public boolean actionBar = true;
    public boolean followWorldChange = true;

    // double sneak
    public boolean doubleSneakEnabled = true;
    public long doubleSneakWindowMs = 350L;
    public GameMode activeGameMode = GameMode.SURVIVAL;
    public boolean activeAllowFlight = false;

    // restrictions
    public boolean blockSpectateOthers = true;
    public boolean blockExternalTeleports = true;
    public boolean blockBlockChanges = true;
    public boolean blockInteractions = true;
    public boolean lockGameMode = true;
    public boolean lockVanish = true;
    public Set<String> allowedCommands = new HashSet<>();
    public Set<String> alwaysDeniedCommands = new HashSet<>();

    // sounds
    public boolean soundsEnabled = true;
    public Sound engageSound;
    public Sound releaseSound;
    public Sound toggleSound;
    public Sound wallSound;

    private String prefix = "";

    public Settings(HavocSusPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration c = plugin.getConfig();

        spectatorOnTeleport = c.getBoolean("engage.spectator-on-teleport", true);
        autoVanish = c.getBoolean("engage.auto-vanish", true);
        restoreLocationOnExit = c.getBoolean("engage.restore-location-on-exit", true);
        detectWindowTicks = Math.max(5, c.getInt("engage.detect-window-ticks", 60));
        susDirectTeleport = c.getBoolean("sus-command.direct-teleport", true);
        registerSusIfAbsent = c.getBoolean("sus-command.register-if-absent", true);
        susPassthroughArgs = new HashSet<>();
        List<String> passthrough = c.getStringList("sus-command.passthrough-args");
        if (passthrough.isEmpty()) {
            passthrough = List.of("reload", "clear", "help", "history");
        }
        for (String raw : passthrough) {
            if (raw != null && !raw.isBlank()) {
                susPassthroughArgs.add(raw.toLowerCase(Locale.ROOT).trim());
            }
        }
        ignoreRightClick = c.getBoolean("engage.ignore-right-click", true);
        patrolEnabled = c.getBoolean("patrol.enabled", true);

        radius = Math.max(8.0D, c.getDouble("leash.radius", 150.0D));
        warnAt = Math.min(radius - 1.0D, c.getDouble("leash.warn-at", radius - 10.0D));
        pullBack = c.getBoolean("leash.pull-back", true);
        actionBar = c.getBoolean("leash.action-bar", true);
        followWorldChange = c.getBoolean("leash.follow-world-change", true);

        doubleSneakEnabled = c.getBoolean("double-sneak.enabled", true);
        doubleSneakWindowMs = Math.max(80L, c.getLong("double-sneak.window-ms", 350L));
        activeGameMode = parseGameMode(c.getString("double-sneak.survival-gamemode", "SURVIVAL"));
        activeAllowFlight = c.getBoolean("double-sneak.allow-flight", false);

        blockSpectateOthers = c.getBoolean("restrictions.block-spectate-other-entities", true);
        blockExternalTeleports = c.getBoolean("restrictions.block-external-teleports", true);
        blockBlockChanges = c.getBoolean("restrictions.block-block-changes", true);
        blockInteractions = c.getBoolean("restrictions.block-interactions-in-active-mode", true);
        lockGameMode = c.getBoolean("restrictions.lock-gamemode", true);
        lockVanish = c.getBoolean("restrictions.lock-vanish", true);

        alwaysDeniedCommands = new HashSet<>();
        List<String> denied = c.getStringList("restrictions.always-denied-commands");
        if (denied.isEmpty()) {
            denied = List.of("v", "vanish", "pv", "premiumvanish", "unvanish", "vanishtoggle");
        }
        for (String raw : denied) {
            if (raw != null && !raw.isBlank()) {
                alwaysDeniedCommands.add(raw.toLowerCase(Locale.ROOT).replace("/", "").trim());
            }
        }

        allowedCommands = new HashSet<>();
        List<String> configured = c.getStringList("restrictions.allowed-commands");
        if (configured.isEmpty()) {
            // Never fall through to "everything allowed" on a malformed config.
            configured = List.of("punish", "sus", "suspicious", "havocsus", "escort", "hs");
        }
        for (String raw : configured) {
            if (raw != null && !raw.isBlank()) {
                allowedCommands.add(raw.toLowerCase(Locale.ROOT).replace("/", "").trim());
            }
        }

        soundsEnabled = c.getBoolean("sounds.enabled", true);
        engageSound = parseSound(c.getString("sounds.engage", "BLOCK_BEACON_ACTIVATE"));
        releaseSound = parseSound(c.getString("sounds.release", "BLOCK_BEACON_DEACTIVATE"));
        toggleSound = parseSound(c.getString("sounds.toggle", "UI_BUTTON_CLICK"));
        wallSound = parseSound(c.getString("sounds.wall", "BLOCK_NOTE_BLOCK_BASS"));

        prefix = c.getString("messages.prefix", "");
        if (prefix == null || prefix.isEmpty()) {
            prefix = DEFAULT_MESSAGES.getOrDefault("prefix", "");
        }
    }

    public void setRadius(double newRadius) {
        this.radius = Math.max(8.0D, newRadius);
        this.warnAt = Math.max(4.0D, this.radius - 10.0D);
        plugin.getConfig().set("leash.radius", this.radius);
        plugin.getConfig().set("leash.warn-at", this.warnAt);
        plugin.saveConfig();
    }

    /**
     * Builds a prefixed message. Replacements are supplied as flat pairs:
     * msg("engaged", "<target>", name, "<radius>", "150")
     */
    public Component msg(String key, String... replacements) {
        return MM.deserialize(prefix + raw(key, replacements));
    }

    /** Same as {@link #msg} but without the prefix - used for action bars. */
    public Component bare(String key, String... replacements) {
        return MM.deserialize(raw(key, replacements));
    }

    private String raw(String key, String... replacements) {
        String value = plugin.getConfig().getString("messages." + key, "");
        if (value == null || value.isEmpty()) {
            value = DEFAULT_MESSAGES.getOrDefault(key, "");
        }
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            value = value.replace(replacements[i], replacements[i + 1]);
        }
        return value;
    }

    private static GameMode parseGameMode(String name) {
        if (name == null) {
            return GameMode.SURVIVAL;
        }
        try {
            GameMode mode = GameMode.valueOf(name.toUpperCase(Locale.ROOT));
            return mode == GameMode.SPECTATOR ? GameMode.SURVIVAL : mode;
        } catch (IllegalArgumentException ex) {
            return GameMode.SURVIVAL;
        }
    }

    private static Sound parseSound(String name) {
        if (name == null) {
            return null;
        }
        // Sound is an interface backed by a registry in modern Paper, so go
        // through the registry rather than Enum.valueOf.
        try {
            org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(
                    name.toLowerCase(Locale.ROOT).replace('_', '.'));
            if (key != null) {
                Sound sound = org.bukkit.Registry.SOUNDS.get(key);
                if (sound != null) {
                    return sound;
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return null;
    }
}
