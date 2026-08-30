package com.havocsus.escort;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

public final class EscortSession {

    public enum Mode {
        /** Ghost. Noclip, invisible, cannot touch anything. */
        SPECTATOR,
        /** Solid. Still vanished, still leashed, but physically present. */
        ACTIVE
    }

    private final UUID staffId;
    private UUID targetId;

    // Pre-session state, restored verbatim on exit.
    private final Location returnLocation;
    private final GameMode previousGameMode;
    private final boolean previousAllowFlight;
    private final boolean previousFlying;
    private final boolean wasAlreadyVanished;

    private Mode mode = Mode.SPECTATOR;
    private long lastSneakMs;
    private long lastWallMessageMs;

    /**
     * Set while HavocSus itself is changing gamemode or teleporting, so our own
     * listeners don't fight our own actions.
     */
    private boolean internalAction;

    /** One-shot pass for the SUS teleport that re-targets an active session. */
    private boolean allowNextTeleport;

    /**
     * @param origin where the staff member was standing BEFORE the teleport.
     *               This must be passed in rather than read from the player:
     *               sessions are created a tick after SUS has already moved
     *               them, so reading it here would record the suspect's
     *               position and send staff straight back to it on exit.
     */
    public EscortSession(Player staff, UUID targetId, Location origin, boolean wasAlreadyVanished) {
        this.staffId = staff.getUniqueId();
        this.targetId = targetId;
        this.returnLocation = (origin == null ? staff.getLocation() : origin).clone();
        this.previousGameMode = staff.getGameMode();
        this.previousAllowFlight = staff.getAllowFlight();
        this.previousFlying = staff.isFlying();
        this.wasAlreadyVanished = wasAlreadyVanished;
    }

    public UUID staffId() {
        return staffId;
    }

    public UUID targetId() {
        return targetId;
    }

    public void retarget(UUID newTarget) {
        this.targetId = newTarget;
    }

    public Player staff() {
        return Bukkit.getPlayer(staffId);
    }

    public Player target() {
        return targetId == null ? null : Bukkit.getPlayer(targetId);
    }

    public Location returnLocation() {
        return returnLocation.clone();
    }

    public GameMode previousGameMode() {
        return previousGameMode;
    }

    public boolean previousAllowFlight() {
        return previousAllowFlight;
    }

    public boolean previousFlying() {
        return previousFlying;
    }

    public boolean wasAlreadyVanished() {
        return wasAlreadyVanished;
    }

    public Mode mode() {
        return mode;
    }

    public void mode(Mode mode) {
        this.mode = mode;
    }

    public long lastSneakMs() {
        return lastSneakMs;
    }

    public void lastSneakMs(long value) {
        this.lastSneakMs = value;
    }

    /** Rate-limits the "you hit the wall" spam to once a second. */
    public boolean shouldSendWallMessage() {
        long now = System.currentTimeMillis();
        if (now - lastWallMessageMs < 1000L) {
            return false;
        }
        lastWallMessageMs = now;
        return true;
    }

    public boolean isInternalAction() {
        return internalAction;
    }

    public void internalAction(boolean value) {
        this.internalAction = value;
    }

    public boolean consumeTeleportPass() {
        if (allowNextTeleport) {
            allowNextTeleport = false;
            return true;
        }
        return false;
    }

    public void allowNextTeleport() {
        this.allowNextTeleport = true;
    }
}
