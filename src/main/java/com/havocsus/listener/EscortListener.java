package com.havocsus.listener;

import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import com.havocsus.Settings;
import com.havocsus.HavocSusPlugin;
import com.havocsus.escort.EscortSession;
import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.Locale;

public final class EscortListener implements Listener {

    private final HavocSusPlugin plugin;

    public EscortListener(HavocSusPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // the 150-block leash
    // ------------------------------------------------------------------

    /**
     * Soft wall. Instead of teleport-spamming, we simply refuse movement that
     * increases distance once you're at the limit, so it feels like an invisible
     * barrier rather than rubber-banding.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        EscortSession session = plugin.escorts().session(event.getPlayer());
        if (session == null) {
            return;
        }
        Player staff = event.getPlayer();
        if (staff.hasPermission("havocsus.bypass.leash")) {
            return;
        }
        Player target = session.target();
        if (target == null) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        // Head movement only - nothing to check.
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return;
        }

        Location targetLoc = target.getLocation();
        if (to.getWorld() == null || !to.getWorld().equals(targetLoc.getWorld())) {
            return; // the tick task handles cross-world
        }

        Settings s = plugin.settings();
        double newDistance = to.distance(targetLoc);
        if (newDistance <= s.radius) {
            return;
        }

        double oldDistance = from.getWorld() != null && from.getWorld().equals(targetLoc.getWorld())
                ? from.distance(targetLoc)
                : Double.MAX_VALUE;

        // Only block movement that makes it worse - otherwise staff who are
        // outside the radius (target sprinted off) could never walk back.
        if (newDistance <= oldDistance) {
            return;
        }

        Location blocked = from.clone();
        blocked.setYaw(to.getYaw());
        blocked.setPitch(to.getPitch());
        event.setTo(blocked);

        if (session.shouldSendWallMessage()) {
            staff.sendMessage(s.msg("wall",
                    "<radius>", String.valueOf((long) s.radius),
                    "<target>", target.getName()));
            if (s.soundsEnabled && s.wallSound != null) {
                staff.playSound(staff.getLocation(), s.wallSound, 0.5F, 1.4F);
            }
        }
    }

    /** Blocks any teleport that would land outside the bubble. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        EscortSession session = plugin.escorts().session(event.getPlayer());
        if (session == null || session.isInternalAction()) {
            return;
        }
        Settings s = plugin.settings();
        if (!s.blockExternalTeleports) {
            return;
        }
        Player staff = event.getPlayer();
        if (staff.hasPermission("havocsus.bypass.leash")) {
            return;
        }
        // A SUS click on a different suspect gets exactly one free pass.
        if (session.consumeTeleportPass() || plugin.escorts().hasPending(staff)) {
            return;
        }

        Player target = session.target();
        Location to = event.getTo();
        if (target == null || to == null || to.getWorld() == null) {
            return;
        }
        Location targetLoc = target.getLocation();
        boolean sameWorld = to.getWorld().equals(targetLoc.getWorld());
        if (sameWorld && to.distance(targetLoc) <= s.radius) {
            return;
        }

        event.setCancelled(true);
        if (session.shouldSendWallMessage()) {
            staff.sendMessage(s.msg("teleport-blocked"));
        }
    }

    /**
     * The important one. Vanilla spectator lets you click any player in the
     * spectator menu and jump straight to them - which would turn an escort into
     * a free tour of every base on the map. Only the suspect is allowed.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpectate(PlayerStartSpectatingEntityEvent event) {
        EscortSession session = plugin.escorts().session(event.getPlayer());
        if (session == null || !plugin.settings().blockSpectateOthers) {
            return;
        }
        Entity newTarget = event.getNewSpectatorTarget();
        if (newTarget != null && newTarget.getUniqueId().equals(session.targetId())) {
            return;
        }
        event.setCancelled(true);

        Player target = session.target();
        if (session.shouldSendWallMessage()) {
            event.getPlayer().sendMessage(plugin.settings().msg("spectate-blocked",
                    "<target>", target == null ? "the suspect" : target.getName()));
        }
    }

    // ------------------------------------------------------------------
    // double-shift toggle
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return; // only count the press, not the release
        }
        Settings s = plugin.settings();
        if (!s.doubleSneakEnabled) {
            return;
        }
        Player staff = event.getPlayer();
        EscortSession session = plugin.escorts().session(staff);
        if (session == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long last = session.lastSneakMs();
        if (last > 0L && (now - last) <= s.doubleSneakWindowMs) {
            session.lastSneakMs(0L);
            plugin.escorts().toggleMode(staff, session);
        } else {
            session.lastSneakMs(now);
        }
    }

    // ------------------------------------------------------------------
    // lockdown while escorting
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        EscortSession session = plugin.escorts().session(event.getPlayer());
        if (session == null || session.isInternalAction() || !plugin.settings().lockGameMode) {
            return;
        }
        event.setCancelled(true);
        if (session.shouldSendWallMessage()) {
            event.getPlayer().sendMessage(plugin.settings().msg("gamemode-locked"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player staff = event.getPlayer();
        EscortSession session = plugin.escorts().session(staff);
        if (session == null) {
            return;
        }
        String message = event.getMessage();
        if (message.length() < 2) {
            return;
        }
        String root = message.substring(1).split("\\s+")[0].toLowerCase(Locale.ROOT);
        // strip the plugin-qualified form, e.g. /essentials:tp -> tp, so nobody
        // sneaks past the whitelist by prefixing a namespace.
        int colon = root.indexOf(':');
        if (colon >= 0 && colon + 1 < root.length()) {
            root = root.substring(colon + 1);
        }

        // Hard deny first: vanish commands are refused even with the bypass
        // permission, otherwise a senior staffer could drop vanish mid-escort.
        if (plugin.settings().alwaysDeniedCommands.contains(root)) {
            event.setCancelled(true);
            staff.sendMessage(plugin.settings().msg("command-blocked", "<command>", root));
            return;
        }

        if (staff.hasPermission("havocsus.bypass.commands")) {
            return;
        }

        // Whitelist: anything not explicitly allowed is refused.
        if (plugin.settings().allowedCommands.contains(root)) {
            return;
        }
        event.setCancelled(true);
        staff.sendMessage(plugin.settings().msg("command-blocked", "<command>", root));
    }

    // Break and place are blocked in both modes - a spectator can't do it
    // anyway, but the moment they double-shift into survival they could.

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (shouldBlockBuilding(event.getPlayer())) {
            event.setCancelled(true);
            notify(event.getPlayer(), "build-blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (shouldBlockBuilding(event.getPlayer())) {
            event.setCancelled(true);
            notify(event.getPlayer(), "build-blocked");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) {
            return;
        }
        if (shouldBlockInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (shouldBlockInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player staff && shouldBlockInteraction(staff)) {
            event.setCancelled(true);
            notifyInteractionBlocked(staff);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player staff)) {
            return;
        }
        if (plugin.escorts().session(staff) == null) {
            return;
        }
        InventoryHolder holder = event.getInventory().getHolder();

        // Someone ELSE's inventory. This is the invsee / openinv case, and the
        // holder being a Player used to short-circuit straight to "allow",
        // which let staff open and empty the inventory of the very player they
        // were investigating.
        if (holder instanceof Player owner && !owner.getUniqueId().equals(staff.getUniqueId())) {
            if (plugin.settings().blockPlayerInventories) {
                event.setCancelled(true);
                notify(staff, "inventory-blocked");
            }
            return;
        }
        if (holder instanceof Player) {
            return; // their own inventory / ender chest
        }

        if (!shouldBlockInteraction(staff)) {
            return;
        }

        // Real world containers only - chests, barrels, furnaces, hoppers,
        // minecarts, horses. Virtual inventories (holder null or a plugin's own
        // InventoryHolder) are left alone, otherwise /punish and /sus would open
        // a menu and have it slammed shut.
        boolean worldContainer = holder instanceof BlockState || holder instanceof Entity;
        if (!worldContainer) {
            return;
        }
        event.setCancelled(true);
        notifyInteractionBlocked(staff);
    }

    /**
     * Backstop for the open blocker: if another plugin puts staff into a view of
     * someone else's inventory without a cancellable open event, clicks in the
     * top half still go nowhere.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player staff)) {
            return;
        }
        if (plugin.escorts().session(staff) == null || !plugin.settings().blockPlayerInventories) {
            return;
        }
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof Player owner && !owner.getUniqueId().equals(staff.getUniqueId())) {
            event.setCancelled(true);
            notify(staff, "inventory-blocked");
        }
    }

    /** No hoovering up the suspect's drops mid-investigation. */
    @EventHandler(ignoreCancelled = true)
    public void onPickup(PlayerAttemptPickupItemEvent event) {
        Player staff = event.getPlayer();
        if (plugin.escorts().session(staff) == null || !plugin.settings().blockItemPickup) {
            return;
        }
        event.setCancelled(true);
    }

    private boolean shouldBlockBuilding(Player player) {
        return plugin.settings().blockBlockChanges && plugin.escorts().isEscorting(player);
    }

    private boolean shouldBlockInteraction(Player player) {
        if (!plugin.settings().blockInteractions) {
            return false;
        }
        EscortSession session = plugin.escorts().session(player);
        return session != null && session.mode() == EscortSession.Mode.ACTIVE;
    }

    private void notify(Player player, String messageKey) {
        EscortSession session = plugin.escorts().session(player);
        if (session != null && session.shouldSendWallMessage()) {
            player.sendMessage(plugin.settings().msg(messageKey));
        }
    }

    private void notifyInteractionBlocked(Player player) {
        notify(player, "interaction-blocked");
    }

    // ------------------------------------------------------------------
    // teardown
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player quitter = event.getPlayer();

        // Staff logging out mid-escort: restore their real state now.
        plugin.escorts().releaseOnQuit(quitter);
        plugin.escorts().clearPending(quitter);

        // Suspect logging out: cut every escort trained on them.
        for (EscortSession session : plugin.escorts().sessionsTargeting(quitter.getUniqueId())) {
            Player staff = session.staff();
            if (staff != null && staff.isOnline()) {
                staff.sendMessage(plugin.settings().msg("target-left", "<target>", quitter.getName()));
                plugin.escorts().release(staff, "target disconnected");
            }
        }
    }
}
