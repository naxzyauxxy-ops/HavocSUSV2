package com.havocsus;

import com.havocsus.command.EscortCommand;
import com.havocsus.escort.EscortManager;
import com.havocsus.hook.SusHook;
import com.havocsus.hook.VanishHook;
import com.havocsus.listener.EscortListener;
import com.havocsus.listener.SusBridgeListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class HavocSusPlugin extends JavaPlugin {

    private Settings settings;
    private VanishHook vanishHook;
    private SusHook susHook;
    private EscortManager escortManager;

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

        escortManager.startTasks();

        getLogger().info("Enabled v" + getPluginMeta().getVersion()
                + " | SUS: " + (susHook.isAvailable() ? "hooked" : "MISSING")
                + " | PremiumVanish: " + (vanishHook.isAvailable() ? "hooked" : "MISSING")
                + " | /sus direct-teleport: " + (settings.susDirectTeleport ? "on" : "off"));
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
