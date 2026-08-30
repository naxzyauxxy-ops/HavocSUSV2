package com.havocsus.hook;

import com.havocsus.HavocSusPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEvent;

import java.lang.reflect.Method;

/**
 * Talks to PremiumVanish through its public API class, de.myzelyam.api.vanish.VanishAPI.
 *
 * Everything is reflective on purpose. PremiumVanish is a paid resource, so this
 * project neither bundles it nor compiles against it - if it's on the server we
 * use it, if it isn't, escorts still work (just without vanish).
 */
public final class VanishHook {

    private final HavocSusPlugin plugin;

    private Method hidePlayer;
    private Method showPlayer;
    private Method isInvisible;
    private boolean available;

    /** Held in a field so the registered handler isn't collected. */
    private final Listener lockListener = new Listener() {
    };

    public VanishHook(HavocSusPlugin plugin) {
        this.plugin = plugin;
        resolve();
    }

    private void resolve() {
        if (plugin.getServer().getPluginManager().getPlugin("PremiumVanish") == null) {
            plugin.getLogger().info("PremiumVanish not found - escorts will run unvanished.");
            return;
        }
        try {
            // Resolve through PremiumVanish's own classloader first, for the
            // same reason as the ban hook: our loader isn't guaranteed to see
            // another plugin's classes.
            Class<?> api = loadFromOwner("de.myzelyam.api.vanish.VanishAPI");
            hidePlayer = api.getMethod("hidePlayer", Player.class);
            showPlayer = api.getMethod("showPlayer", Player.class);
            isInvisible = api.getMethod("isInvisible", Player.class);
            available = true;
        } catch (Throwable t) {
            plugin.getLogger().warning("PremiumVanish is installed but its API could not be resolved: " + t);
        }
    }

    private Class<?> loadFromOwner(String name) throws ClassNotFoundException {
        var owner = plugin.getServer().getPluginManager().getPlugin("PremiumVanish");
        if (owner != null) {
            try {
                return owner.getClass().getClassLoader().loadClass(name);
            } catch (ClassNotFoundException ignored) {
                // fall through
            }
        }
        return Class.forName(name);
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean isVanished(Player player) {
        if (!available) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(isInvisible.invoke(null, player));
        } catch (Throwable t) {
            return false;
        }
    }

    public void hide(Player player) {
        if (!available) {
            return;
        }
        try {
            hidePlayer.invoke(null, player);
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to vanish " + player.getName() + ": " + t);
        }
    }

    public void show(Player player) {
        if (!available) {
            return;
        }
        try {
            showPlayer.invoke(null, player);
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to un-vanish " + player.getName() + ": " + t);
        }
    }

    /**
     * Locks vanish on for anyone in an escort session.
     *
     * Blocking /v isn't enough - PremiumVanish also has a hotbar toggle item,
     * and other plugins can call the API directly. PlayerShowEvent is
     * cancellable, so we veto every un-vanish attempt at the source regardless
     * of what triggered it.
     *
     * Registered reflectively: PlayerShowEvent lives in PremiumVanish's jar, but
     * it extends org.bukkit.event.player.PlayerEvent and implements Cancellable,
     * both of which are plain Bukkit types - so we can handle it without ever
     * compiling against PremiumVanish.
     */
    public void registerVanishLock() {
        if (!available) {
            return;
        }
        try {
            Class<?> raw = loadFromOwner("de.myzelyam.api.vanish.PlayerShowEvent");
            if (!Event.class.isAssignableFrom(raw)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Class<? extends Event> showEvent = (Class<? extends Event>) raw;

            plugin.getServer().getPluginManager().registerEvent(
                    showEvent,
                    lockListener,
                    EventPriority.HIGHEST,
                    (listener, event) -> {
                        if (!(event instanceof PlayerEvent playerEvent)
                                || !(event instanceof Cancellable cancellable)) {
                            return;
                        }
                        if (!plugin.settings().lockVanish) {
                            return;
                        }
                        Player player = playerEvent.getPlayer();
                        if (player == null || !plugin.escorts().isEscorting(player)) {
                            return;
                        }
                        if (player.hasPermission("havocsus.bypass.vanish")) {
                            return;
                        }
                        cancellable.setCancelled(true);
                        player.sendMessage(plugin.settings().msg("vanish-locked"));
                    },
                    plugin,
                    true);

            plugin.getLogger().info("Vanish lock active - staff cannot un-vanish mid-escort.");
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not install the vanish lock: " + t);
        }
    }
}
