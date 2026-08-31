package com.havocsus.escort;

import com.havocsus.Settings;
import com.havocsus.HavocSusPlugin;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class EscortManager {

    /** Blocks to look down before deciding staff are in mid-air. */
    private static final int AIRBORNE_CHECK = 4;

    /** A SUS click we've seen, waiting for the teleport it triggers. */
    public record Pending(UUID target, Location origin, boolean wasVanished, long expiresAtMs) {
        boolean expired() {
            return System.currentTimeMillis() > expiresAtMs;
        }
    }

    private final HavocSusPlugin plugin;
    private final Map<UUID, EscortSession> sessions = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();

    public EscortManager(HavocSusPlugin plugin) {
        this.plugin = plugin;
    }

    private BukkitTask tickTask;

    public void startTasks() {
        stopTasks();
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 2L);
    }

    public void stopTasks() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    // ------------------------------------------------------------------
    // pending SUS clicks
    // ------------------------------------------------------------------

    /** Called when staff clicks a flagged player in the SUS GUI, before SUS teleports them. */
    public void markPending(Player staff, UUID target) {
        Settings s = plugin.settings();
        // Snapshot where they are NOW - they're standing in a menu, so this is
        // their real pre-teleport position and the place /escort quit sends
        // them back to.
        // Vanish NOW, before the teleport, not after it lands. Vanishing a tick
        // later meant the suspect got a frame or two of a staff member popping
        // in beside them - which is exactly what they'd notice.
        boolean wasVanished = prepareVanish(staff);

        pending.put(staff.getUniqueId(), new Pending(
                target,
                staff.getLocation().clone(),
                wasVanished,
                System.currentTimeMillis() + (s.detectWindowTicks * 50L)));

        // If they're already escorting someone, let SUS's teleport through once
        // so the leash doesn't veto a legitimate switch to a different suspect.
        EscortSession existing = sessions.get(staff.getUniqueId());
        if (existing != null) {
            existing.allowNextTeleport();
        }
    }

    /** Takes the pending click if one is still live, otherwise null. */
    public Pending consumePending(Player staff) {
        Pending p = pending.remove(staff.getUniqueId());
        if (p == null || p.expired()) {
            return null;
        }
        return p;
    }

    public boolean hasPending(Player staff) {
        Pending p = pending.get(staff.getUniqueId());
        if (p == null) {
            return false;
        }
        if (p.expired()) {
            pending.remove(staff.getUniqueId());
            return false;
        }
        return true;
    }

    public void clearPending(Player staff) {
        pending.remove(staff.getUniqueId());
    }

    // ------------------------------------------------------------------
    // sessions
    // ------------------------------------------------------------------

    public EscortSession session(Player player) {
        return player == null ? null : sessions.get(player.getUniqueId());
    }

    public boolean isEscorting(Player player) {
        return player != null && sessions.containsKey(player.getUniqueId());
    }

    public List<EscortSession> sessionsTargeting(UUID targetId) {
        List<EscortSession> out = new ArrayList<>();
        for (EscortSession session : sessions.values()) {
            if (session.targetId().equals(targetId)) {
                out.add(session);
            }
        }
        return out;
    }

    /**
     * Vanishes staff immediately and reports whether they were ALREADY vanished
     * beforehand.
     *
     * That return value has to be carried into the session: capturing it later,
     * after we've done the vanishing, would always read "already vanished" and
     * staff would be left invisible when the session ended.
     */
    public boolean prepareVanish(Player staff) {
        boolean wasVanished = plugin.vanish().isVanished(staff);
        if (plugin.settings().autoVanish && !wasVanished) {
            plugin.vanish().hide(staff);
        }
        return wasVanished;
    }

    public void engage(Player staff, Player target) {
        engage(staff, target, staff == null ? null : staff.getLocation(), null);
    }

    public void engage(Player staff, Player target, Location origin) {
        engage(staff, target, origin, null);
    }

    /**
     * @param origin pre-teleport position, used as the exit destination. Callers
     *               must supply this from before the teleport happened.
     */
    public void engage(Player staff, Player target, Location origin, Boolean preVanished) {
        if (staff == null || target == null || staff.equals(target)) {
            return;
        }
        Settings s = plugin.settings();

        // Enforced here as well as in startWatch, because the GUI path reaches
        // engage() directly and a guard on only one route isn't a guard.
        if (plugin.isWatchProtected(target)) {
            staff.sendMessage(s.msg("cannot-watch-staff", "<target>", target.getName()));
            return;
        }
        Player watcher = plugin.watcherOf(target);
        if (watcher != null && !watcher.equals(staff)) {
            staff.sendMessage(s.msg("already-watched",
                    "<target>", target.getName(),
                    "<staff>", watcher.getName()));
            return;
        }

        EscortSession existing = sessions.get(staff.getUniqueId());
        if (existing != null) {
            // Switching suspects mid-session keeps the ORIGINAL return point,
            // otherwise each hop would overwrite it with the last suspect's spot.
            existing.retarget(target.getUniqueId());
            staff.sendMessage(s.msg("retargeted", "<target>", target.getName()));
            applyMode(staff, existing, existing.mode(), false);
            return;
        }

        // preVanished is the snapshot taken before we vanished them early; only
        // fall back to reading it live when nothing captured it first.
        boolean wasVanished = preVanished != null
                ? preVanished
                : prepareVanish(staff);

        EscortSession session = new EscortSession(staff, target.getUniqueId(), origin, wasVanished);
        sessions.put(staff.getUniqueId(), session);

        if (s.autoVanish && !wasVanished) {
            plugin.vanish().hide(staff);
        }

        applyMode(staff, session, s.spectatorOnTeleport ? EscortSession.Mode.SPECTATOR : EscortSession.Mode.ACTIVE, false);

        staff.sendMessage(s.msg("engaged",
                "<target>", target.getName(),
                "<radius>", trim(s.radius)));
        staff.sendMessage(s.msg("engaged-hint",
                "<mode>", s.activeGameMode.name().toLowerCase()));
        play(staff, s.engageSound);
    }

    /** Double-shift handler: spectator <-> solid. */
    public void toggleMode(Player staff, EscortSession session) {
        EscortSession.Mode next = session.mode() == EscortSession.Mode.SPECTATOR
                ? EscortSession.Mode.ACTIVE
                : EscortSession.Mode.SPECTATOR;
        applyMode(staff, session, next, true);
    }

    private void applyMode(Player staff, EscortSession session, EscortSession.Mode mode, boolean announce) {
        Settings s = plugin.settings();
        session.mode(mode);
        session.internalAction(true);
        try {
            if (mode == EscortSession.Mode.SPECTATOR) {
                staff.setGameMode(GameMode.SPECTATOR);
                if (announce) {
                    staff.sendMessage(s.msg("mode-spectator"));
                }
            } else {
                // Leaving spectator does NOT move you.
                //
                // This used to scan downward for ground and teleport you there,
                // so dropping out of spectator from any height dumped you at the
                // bottom of the world - and inside caves or over the void it
                // landed you somewhere useless or lethal. Staying put is what
                // staff expect; the only thing needed is not to plummet.
                staff.setGameMode(s.activeGameMode);

                boolean airborne = isAirborne(staff.getLocation());
                boolean flight = s.activeAllowFlight
                        || s.activeGameMode == GameMode.CREATIVE
                        || (s.hoverIfAirborne && airborne);
                staff.setAllowFlight(flight);
                if (flight && airborne) {
                    // Hover in place rather than fall out of the sky.
                    staff.setFlying(true);
                }
                if (announce) {
                    staff.sendMessage(s.msg("mode-active",
                            "<mode>", capitalise(s.activeGameMode.name())));
                }
            }
        } finally {
            session.internalAction(false);
        }
        if (announce) {
            play(staff, s.toggleSound);
        }
    }

    public void release(Player staff, String reason) {
        EscortSession session = sessions.remove(staff.getUniqueId());
        if (session == null) {
            return;
        }
        Settings s = plugin.settings();

        session.internalAction(true);
        try {
            if (staff.getGameMode() == GameMode.SPECTATOR && staff.getSpectatorTarget() != null) {
                staff.setSpectatorTarget(null);
            }
            staff.setGameMode(session.previousGameMode());
            if (s.restoreLocationOnExit) {
                Location back = session.returnLocation();
                if (back.getWorld() != null) {
                    staff.teleport(back);
                }
            }
            staff.setAllowFlight(session.previousAllowFlight());
            if (session.previousAllowFlight() && session.previousFlying()) {
                staff.setFlying(true);
            }
            if (s.autoVanish && !session.wasAlreadyVanished()) {
                plugin.vanish().show(staff);
            }
        } finally {
            session.internalAction(false);
        }

        pending.remove(staff.getUniqueId());
        staff.sendMessage(s.msg("released", "<reason>", reason == null ? "ended" : reason));
        play(staff, s.releaseSound);
    }

    /** Player logged out mid-session: restore state before they're gone, no teleport chatter. */
    public void releaseOnQuit(Player staff) {
        EscortSession session = sessions.remove(staff.getUniqueId());
        if (session == null) {
            return;
        }
        Settings s = plugin.settings();
        session.internalAction(true);
        try {
            staff.setGameMode(session.previousGameMode());
            if (s.restoreLocationOnExit && session.returnLocation().getWorld() != null) {
                staff.teleport(session.returnLocation());
            }
            staff.setAllowFlight(session.previousAllowFlight());
            if (s.autoVanish && !session.wasAlreadyVanished()) {
                plugin.vanish().show(staff);
            }
        } catch (Throwable ignored) {
            // player is on the way out; never let this break the quit handler
        } finally {
            session.internalAction(false);
        }
        pending.remove(staff.getUniqueId());
    }

    public void releaseAll(String reason) {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player staff = plugin.getServer().getPlayer(id);
            if (staff != null) {
                release(staff, reason);
            } else {
                sessions.remove(id);
            }
        }
    }

    // ------------------------------------------------------------------
    // leash tick
    // ------------------------------------------------------------------

    private void tick() {
        if (sessions.isEmpty()) {
            return;
        }
        Settings s = plugin.settings();

        for (UUID staffId : new ArrayList<>(sessions.keySet())) {
            EscortSession session = sessions.get(staffId);
            if (session == null) {
                continue;
            }
            Player staff = session.staff();
            if (staff == null || !staff.isOnline()) {
                sessions.remove(staffId);
                continue;
            }

            Player target = session.target();
            if (target == null || !target.isOnline()) {
                String name = lastKnownName(session.targetId());
                staff.sendMessage(s.msg("target-left", "<target>", name));
                release(staff, "target disconnected");
                continue;
            }

            Location staffLoc = staff.getLocation();
            Location targetLoc = target.getLocation();

            // Different world: drag them along, or end the session.
            if (staffLoc.getWorld() == null || !staffLoc.getWorld().equals(targetLoc.getWorld())) {
                if (s.followWorldChange) {
                    teleportInternal(session, staff, targetLoc);
                } else {
                    release(staff, "target changed world");
                }
                continue;
            }

            handleElytra(session, staff, target);

            if (s.hideVanishScoreboard) {
                hideSidebar(staff);
            }

            double distance = staffLoc.distance(targetLoc);

            if (distance > s.radius && !staff.hasPermission("havocsus.bypass.leash")) {
                if (s.pullBack) {
                    Location boundary = boundaryPoint(targetLoc, staffLoc, s.radius * 0.85D);
                    if (session.mode() == EscortSession.Mode.ACTIVE) {
                        Location safe = findSafeLanding(boundary);
                        if (safe != null) {
                            boundary = safe;
                        }
                    }
                    teleportInternal(session, staff, boundary);
                    if (session.shouldSendWallMessage()) {
                        staff.sendMessage(s.msg("pulled-back"));
                        play(staff, s.wallSound);
                    }
                }
                distance = Math.min(distance, s.radius);
            }

            if (s.actionBar) {
                boolean warn = distance >= s.warnAt;
                staff.sendActionBar(s.bare(warn ? "action-bar-warn" : "action-bar",
                        "<target>", target.getName(),
                        "<distance>", String.valueOf((int) Math.round(distance)),
                        "<radius>", trim(s.radius),
                        "<mode>", session.mode() == EscortSession.Mode.SPECTATOR
                                ? "Spectator" : capitalise(s.activeGameMode.name())));
            }
        }
    }

    /**
     * Clears the sidebar while escorting.
     *
     * PremiumVanish draws a vanish sidebar (ScoreboardOptions in its config),
     * which covers the screen during an investigation and fights our own action
     * bar readout. PV redraws on its own interval, so this runs every tick
     * rather than once.
     *
     * Note this clears whatever is in the SIDEBAR slot, not just PV's - if you
     * run another sidebar plugin, staff lose it for the duration of a session.
     * The permanent alternative is ScoreboardOptions.Enable: false in
     * PremiumVanish's own config.
     */
    private void hideSidebar(Player staff) {
        try {
            Objective objective = staff.getScoreboard().getObjective(DisplaySlot.SIDEBAR);
            if (objective != null) {
                objective.setDisplaySlot(null);
            }
        } catch (Throwable ignored) {
            // scoreboard APIs are best-effort here; never break the tick
        }
    }

    /**
     * Elytra follow.
     *
     * A gliding suspect outruns anything on foot, and the leash would just
     * rubber-band staff uselessly. So when the target starts gliding we snap to
     * them and attach spectator POV - you see exactly what they see, which is
     * also the most useful angle for judging flight cheats.
     *
     * Attaching happens once per glide. If staff detach manually (shift), we
     * don't fight them and re-attach a tick later; the next takeoff re-arms it.
     */
    private void handleElytra(EscortSession session, Player staff, Player target) {
        Settings s = plugin.settings();
        if (!s.elytraFollow) {
            return;
        }
        boolean gliding = target.isGliding();

        if (gliding && !session.povArmed()) {
            if (session.mode() != EscortSession.Mode.SPECTATOR) {
                if (!s.elytraForceSpectator) {
                    return;
                }
                applyMode(staff, session, EscortSession.Mode.SPECTATOR, false);
            }
            teleportInternal(session, staff, target.getLocation());
            setSpectatorTarget(session, staff, target);
            session.povArmed(true);
            staff.sendMessage(s.msg("pov-following", "<target>", target.getName()));
            return;
        }

        if (!gliding && session.povArmed()) {
            session.povArmed(false);
            if (staff.getGameMode() == GameMode.SPECTATOR && staff.getSpectatorTarget() != null) {
                setSpectatorTarget(session, staff, null);
                staff.sendMessage(s.msg("pov-released"));
            }
        }
    }

    private void setSpectatorTarget(EscortSession session, Player staff, Player target) {
        session.internalAction(true);
        try {
            // Only legal in spectator; guard rather than let it throw mid-tick.
            if (staff.getGameMode() == GameMode.SPECTATOR) {
                staff.setSpectatorTarget(target);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not attach spectator POV: " + t);
        } finally {
            session.internalAction(false);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** A point `distance` blocks from `centre`, along the direction of `from`. */
    public Location boundaryPoint(Location centre, Location from, double distance) {
        Vector direction = from.toVector().subtract(centre.toVector());
        if (direction.lengthSquared() < 1.0E-4D) {
            direction = new Vector(0, 0, 1);
        }
        direction.normalize().multiply(distance);
        Location out = centre.clone().add(direction);
        out.setYaw(from.getYaw());
        out.setPitch(from.getPitch());
        return out;
    }

    /** Teleports without our own listeners treating it as an escape attempt. */
    public void teleportInternal(EscortSession session, Player staff, Location destination) {
        session.internalAction(true);
        try {
            staff.teleport(destination);
        } finally {
            session.internalAction(false);
        }
    }

    /** True if there's no ground within a few blocks underfoot. */
    private boolean isAirborne(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        int x = location.getBlockX();
        int z = location.getBlockZ();
        int startY = location.getBlockY();
        for (int y = startY; y >= Math.max(world.getMinHeight(), startY - AIRBORNE_CHECK); y--) {
            if (world.getBlockAt(x, y, z).getType().isSolid()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Walks down from the given location looking for ground with two blocks of
     * headroom. Used only by the leash pull-back, where staff are being moved
     * anyway - never by the spectator toggle.
     */
    public Location findSafeLanding(Location origin) {
        World world = origin.getWorld();
        if (world == null) {
            return origin;
        }
        int x = origin.getBlockX();
        int z = origin.getBlockZ();
        int startY = Math.min(Math.max(origin.getBlockY(), world.getMinHeight()), world.getMaxHeight() - 3);

        for (int y = startY; y > world.getMinHeight(); y--) {
            Block ground = world.getBlockAt(x, y, z);
            Block feet = world.getBlockAt(x, y + 1, z);
            Block head = world.getBlockAt(x, y + 2, z);
            if (ground.getType().isSolid() && feet.isEmpty() && head.isEmpty()) {
                Location out = new Location(world, x + 0.5D, y + 1.0D, z + 0.5D,
                        origin.getYaw(), origin.getPitch());
                return out;
            }
        }
        int highest = world.getHighestBlockYAt(x, z);
        return new Location(world, x + 0.5D, highest + 1.0D, z + 0.5D, origin.getYaw(), origin.getPitch());
    }

    private String lastKnownName(UUID id) {
        String name = plugin.getServer().getOfflinePlayer(id).getName();
        return name == null ? "the suspect" : name;
    }

    private void play(Player player, Sound sound) {
        if (sound != null && plugin.settings().soundsEnabled) {
            player.playSound(player.getLocation(), sound, 0.7F, 1.0F);
        }
    }

    private static String trim(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.format("%.1f", value);
    }

    private static String capitalise(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.charAt(0) + value.substring(1).toLowerCase();
    }
}
