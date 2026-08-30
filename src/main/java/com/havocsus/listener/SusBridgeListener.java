package com.havocsus.listener;

import com.havocsus.HavocSusPlugin;
import com.havocsus.escort.EscortManager;
import com.havocsus.escort.EscortSession;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.UUID;

/**
 * The actual "put the two plugins together" seam.
 *
 * SUS teleports staff to a flagged player. We watch for the click that caused
 * it, then for the teleport itself, and the instant it lands we hand the staff
 * member over to the escort manager.
 */
public final class SusBridgeListener implements Listener {

    private final HavocSusPlugin plugin;

    public SusBridgeListener(HavocSusPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * MONITOR so we observe the click after SUS has decided what to do with it.
     * We never cancel or modify anything here.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onSusGuiClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player staff)) {
            return;
        }
        if (staff.hasPermission("havocsus.bypass")) {
            return;
        }

        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!plugin.sus().isSusGui(holder)) {
            return;
        }

        // SUS 1.0.7+ branches on event.isRightClick(): left-click runs the
        // teleport, right-click just clears the flag and leaves you standing
        // where you are. Arming an escort on a right-click would leave a live
        // pending window attached to a player we never travelled to.
        if (plugin.settings().ignoreRightClick && event.isRightClick()) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        UUID targetId = plugin.sus().readTargetUuid(clicked);
        if (targetId == null) {
            return;
        }
        if (targetId.equals(staff.getUniqueId())) {
            return;
        }

        plugin.escorts().markPending(staff, targetId);
    }

    /**
     * Turns `/sus <online player>` into a direct teleport-and-watch.
     *
     * SUS's own handling of that argument opens the player's history GUI - it
     * does NOT teleport (verified in SusCommand: the player branch calls
     * GuiManager, not TeleportManager). So there was no way to go straight to
     * someone without opening a menu and clicking their head. This provides it.
     *
     * We cancel the event before SUS's executor runs, then teleport and engage
     * the escort ourselves. Everything else falls through untouched:
     *   /sus                 -> SUS's main GUI (clicking a head still works)
     *   /sus reload | clear  -> SUS
     *   /sus <offline name>  -> SUS's history GUI
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSusDirectTeleport(PlayerCommandPreprocessEvent event) {
        if (!plugin.settings().susDirectTeleport) {
            return;
        }
        Player staff = event.getPlayer();
        if (staff.hasPermission("havocsus.bypass")) {
            return;
        }

        String[] parts = event.getMessage().substring(1).trim().split("\\s+");
        String root = parts[0].toLowerCase(Locale.ROOT);
        int colon = root.indexOf(':');
        if (colon >= 0 && colon + 1 < root.length()) {
            root = root.substring(colon + 1);
        }
        if (!root.equals("sus") && !root.equals("suspicious")) {
            return;
        }

        // Bare /sus while already watching someone escalates to the punish
        // screen instead of reopening SUS's list.
        if (parts.length < 2) {
            EscortSession active = plugin.escorts().session(staff);
            if (active != null && active.target() != null && active.target().isOnline()
                    && staff.hasPermission("havocsus.punish")) {
                event.setCancelled(true);
                plugin.openPunishList(staff, active.target());
            }
            return;
        }

        String arg = parts[1];
        if (plugin.settings().susPassthroughArgs.contains(arg.toLowerCase(Locale.ROOT))) {
            return; // reload, clear, help...
        }
        if (!staff.hasPermission("havocsus.use")) {
            return;
        }

        // Only intercept for players who are actually online. An offline or
        // misspelled name still reaches SUS, so history lookups keep working.
        Player target = plugin.getServer().getPlayerExact(arg);
        if (target == null) {
            target = plugin.getServer().getPlayer(arg);
        }
        if (target == null || target.equals(staff)) {
            return;
        }

        event.setCancelled(true);

        final Player watched = target;
        Location origin = staff.getLocation().clone();

        EscortSession existing = plugin.escorts().session(staff);
        if (existing != null) {
            existing.allowNextTeleport();
        }
        plugin.escorts().clearPending(staff);

        staff.teleport(watched.getLocation());
        plugin.escorts().engage(staff, watched, origin);
    }

    /**
     * The teleport SUS performs. MONITOR + not cancelled means it actually
     * happened; we engage one tick later so the move is fully settled before we
     * change gamemode.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player staff = event.getPlayer();
        if (!plugin.escorts().hasPending(staff)) {
            return;
        }

        EscortManager.Pending pending = plugin.escorts().consumePending(staff);
        if (pending == null) {
            return;
        }
        UUID targetId = pending.target();

        // getFrom() is the exact spot they stood in before SUS moved them, which
        // is what /escort quit should return them to. Falling back to the
        // location snapshotted at click time if it's somehow unavailable.
        Location origin = event.getFrom() != null
                ? event.getFrom().clone()
                : pending.origin();

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player target = plugin.getServer().getPlayer(targetId);
            if (target == null || !target.isOnline() || !staff.isOnline()) {
                return;
            }
            EscortSession existing = plugin.escorts().session(staff);
            if (existing != null) {
                existing.consumeTeleportPass();
            }
            plugin.escorts().engage(staff, target, origin);
        });
    }
}
