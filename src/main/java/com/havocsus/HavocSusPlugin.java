package com.havocsus;

import com.havocsus.command.EscortCommand;
import com.havocsus.command.SusCommandRegistrar;
import com.havocsus.dialog.WatchDialog;
import com.havocsus.escort.EscortManager;
import com.havocsus.hook.SusHook;
import com.havocsus.hook.VanishHook;
import com.havocsus.listener.EscortListener;
import com.havocsus.listener.SusBridgeListener;
import com.havocsus.escort.EscortSession;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class HavocSusPlugin extends JavaPlugin {

    private Settings settings;
    private VanishHook vanishHook;
    private SusHook susHook;
    private EscortManager escortManager;
    private SusCommandRegistrar susCommandRegistrar;
    private WatchDialog watchDialog;

    @Override
    public void onEnable() {
        bootstrapConfig();

        this.settings = new Settings(this);
        this.settings.load();

        this.vanishHook = new VanishHook(this);
        this.susHook = new SusHook(this);
        this.escortManager = new EscortManager(this);

        getServer().getPluginManager().registerEvents(new SusBridgeListener(this), this);
        getServer().getPluginManager().registerEvents(new EscortListener(this), this);
        vanishHook.registerVanishLock();

        EscortCommand command = new EscortCommand(this);        PluginCommand pluginCommand = getCommand("havocsus");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }

        this.susCommandRegistrar = new SusCommandRegistrar(this);
        susCommandRegistrar.register();

        if (WatchDialog.isSupported()) {
            this.watchDialog = new WatchDialog(this);
        } else {
            getLogger().info("Dialog API not available (needs 1.21.7+) - "
                    + "the watch list will be shown in chat instead.");
        }

        escortManager.startTasks();

        getLogger().info("Enabled v" + getPluginMeta().getVersion()
                + " | SUS: " + (susHook.isAvailable() ? "hooked" : "MISSING")
                + " | PremiumVanish: " + (vanishHook.isAvailable() ? "hooked" : "MISSING")
                + " | /sus: " + (susCommandRegistrar.hasClaimedSus() ? "registered by us" : "intercepted")
                + " | dialogs: " + (watchDialog != null ? "yes" : "chat fallback"));
    }

    /**
     * Writes the default config, but never lets a failure take the plugin down.
     *
     * saveDefaultConfig() reads config.yml out of the plugin jar through the
     * plugin classloader. When a plugin is hot-reloaded with PlugManX on
     * Paper/Purpur, the original classloader is closed and the server falls back
     * to the remapped jar in plugins/.paper-remapped/ - at which point
     * getResource() returns null and saveDefaultConfig() throws
     * IllegalArgumentException, aborting onEnable and disabling the plugin.
     *
     * The config on disk is perfectly fine in that situation, so there is no
     * reason to die. We use it if it's there and fall back to built-in defaults
     * if it isn't.
     */
    private void bootstrapConfig() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (configFile.exists()) {
            try {
                reloadConfig();
            } catch (Throwable t) {
                getLogger().warning("Could not read config.yml, using built-in defaults: " + t);
            }
            return;
        }
        try {
            saveDefaultConfig();
        } catch (Throwable t) {
            getLogger().warning("Could not write the default config: " + t);
            getLogger().warning("This almost always means the plugin was hot-reloaded (PlugManX /plm "
                    + "restart) on a Paper/Purpur server, which breaks resource loading from the "
                    + "remapped jar. Running on built-in defaults for now - do a full server restart "
                    + "to generate plugins/HavocSus/config.yml.");
        }
    }

    @Override
    public void onDisable() {
        if (escortManager != null) {
            escortManager.releaseAll("server shutdown");
            escortManager.stopTasks();
        }
        // Explicit teardown. Bukkit does this itself on a clean shutdown, but
        // being deliberate here leaves fewer dangling references behind if the
        // plugin is unloaded by a reload manager.
        try {
            getServer().getScheduler().cancelTasks(this);
            HandlerList.unregisterAll(this);
        } catch (Throwable ignored) {
            // shutting down anyway - never throw out of onDisable
        }
    }

    /** Teleports staff to a player and starts a leashed watch session. */
    public void startWatch(Player staff, Player target) {
        if (staff == null || target == null || staff.equals(target)) {
            return;
        }
        Location origin = staff.getLocation().clone();
        EscortSession existing = escortManager.session(staff);
        if (existing != null) {
            existing.allowNextTeleport();
        }
        escortManager.clearPending(staff);
        staff.teleport(target.getLocation());
        escortManager.engage(staff, target, origin);
    }

    /** Opens the watch list - a dialog where supported, chat otherwise. */
    public void openWatchList(Player staff) {
        if (watchDialog != null) {
            try {
                watchDialog.open(staff);
                return;
            } catch (Throwable t) {
                getLogger().warning("Dialog failed to open, falling back to chat: " + t);
            }
        }
        sendChatWatchList(staff);
    }

    /**
     * Fallback for servers without the Dialog API, and a safety net if building
     * the dialog ever throws. Clickable names, same behaviour.
     */
    private void sendChatWatchList(Player staff) {
        staff.sendMessage(settings().msg("list-header"));
        int shown = 0;
        for (Player online : getServer().getOnlinePlayers()) {
            if (online.equals(staff) || online.hasPermission("havocsus.hidefromlist")) {
                continue;
            }
            staff.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<gray> » <click:run_command:'/escort " + online.getName() + "'>"
                            + "<hover:show_text:'<green>Click to watch " + online.getName() + "'>"
                            + "<white><name></white></hover></click> <dark_gray><world></dark_gray>",
                    Placeholder.unparsed("name", online.getName()),
                    Placeholder.unparsed("world", online.getWorld().getName())));
            shown++;
        }
        if (shown == 0) {
            staff.sendMessage(settings().msg("list-empty"));
        }
    }

    public void reloadEverything() {
        reloadConfig();
        settings.load();
    }

    public Settings settings() {
        return settings;
    }

    public VanishHook vanish() {
        return vanishHook;
    }

    public SusHook sus() {
        return susHook;
    }

    public EscortManager escorts() {
        return escortManager;
    }
}
