package com.havocsus.dialog;

import com.havocsus.HavocSusPlugin;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The /sus watch list, rendered with Minecraft's dialog screens (1.21.6+).
 *
 * IMPORTANT: every reference to the Dialog API is confined to this class. It is
 * only ever touched after {@link #isSupported()} returns true, so on a server
 * without the API the class is simply never loaded and nothing blows up.
 */
public final class WatchDialog {

    private static final TextColor ACCENT = TextColor.color(0x8AB4FF);
    private static final TextColor MUTED = TextColor.color(0x6C7A93);
    private static final TextColor DANGER = TextColor.color(0xFF8B8E);
    private static final TextColor GOOD = TextColor.color(0xAEFFC1);

    /** Buttons past this point make the screen unusable, so we cut it off. */
    private static final int MAX_BUTTONS = 40;

    private final HavocSusPlugin plugin;

    public WatchDialog(HavocSusPlugin plugin) {
        this.plugin = plugin;
    }

    public static boolean isSupported() {
        try {
            Class.forName("io.papermc.paper.dialog.Dialog");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public void open(Player staff) {
        List<Player> candidates = new ArrayList<>();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.equals(staff) || online.hasPermission("havocsus.hidefromlist")) {
                continue;
            }
            candidates.add(online);
        }
        candidates.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));

        boolean truncated = candidates.size() > MAX_BUTTONS;
        if (truncated) {
            candidates = candidates.subList(0, MAX_BUTTONS);
        }

        List<DialogBody> body = new ArrayList<>();
        if (candidates.isEmpty()) {
            body.add(DialogBody.plainMessage(
                    Component.text("Nobody else is online right now.", MUTED)));
        } else {
            body.add(DialogBody.plainMessage(Component.text()
                    .append(Component.text("Pick a player to drop into a vanished, leashed watch session.", MUTED))
                    .build()));
            body.add(DialogBody.plainMessage(Component.text()
                    .append(Component.text(candidates.size() + " online", ACCENT))
                    .append(Component.text("  ·  ", MUTED))
                    .append(Component.text("double-shift toggles spectator", MUTED))
                    .append(Component.text("  ·  ", MUTED))
                    .append(Component.text("/escort quit to leave", MUTED))
                    .build()));
            if (truncated) {
                body.add(DialogBody.plainMessage(Component.text(
                        "Showing the first " + MAX_BUTTONS + ". Use /sus <name> for anyone else.", MUTED)));
            }
        }

        List<ActionButton> buttons = new ArrayList<>();
        for (Player target : candidates) {
            buttons.add(watchButton(staff, target));
        }
        buttons.add(patrolButton(staff));

        // Single column reads better for a short list; two once it gets long.
        int columns = buttons.size() <= 6 ? 1 : 2;

        final List<DialogBody> finalBody = List.copyOf(body);
        final List<ActionButton> finalButtons = List.copyOf(buttons);
        final ActionButton exitButton = ActionButton.create(
                Component.text("Close", MUTED),
                null,
                100,
                null); // null action simply dismisses the dialog

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(
                                Component.text("Watch List", ACCENT, TextDecoration.BOLD))
                        .canCloseWithEscape(true)
                        .body(finalBody)
                        .build())
                // NOTE: the single-argument multiAction() returns a
                // MultiActionType.Builder, not a DialogType. This three-argument
                // overload returns the type itself and takes the exit button and
                // column count with it.
                .type(DialogType.multiAction(finalButtons, exitButton, columns)));

        staff.showDialog(dialog);
    }

    private ActionButton watchButton(Player staff, Player target) {
        String name = target.getName();
        String world = target.getWorld().getName();

        return ActionButton.create(
                Component.text(name, NamedTextColor.WHITE),
                Component.text()
                        .append(Component.text("Teleport to " + name, GOOD))
                        .append(Component.newline())
                        .append(Component.text(world, MUTED))
                        .build(),
                150,
                DialogAction.customClick(
                        (view, audience) -> runSync(() -> startWatch(staff, target.getUniqueId())),
                        clickOptions()));
    }

    private ActionButton patrolButton(Player staff) {
        return ActionButton.create(
                Component.text("Free spectate", DANGER),
                Component.text("Vanished spectator, no leash, watch anyone", MUTED),
                150,
                DialogAction.customClick(
                        (view, audience) -> runSync(() -> {
                            if (!plugin.escorts().isEscorting(staff)) {
                                plugin.escorts().beginPatrol(staff);
                            }
                        }),
                        clickOptions()));
    }

    private ClickCallback.Options clickOptions() {
        return ClickCallback.Options.builder()
                .uses(ClickCallback.UNLIMITED_USES)
                .lifetime(Duration.ofMinutes(10))
                .build();
    }

    /**
     * Dialog callbacks arrive off the main thread. Teleporting or changing
     * gamemode from there would trip Bukkit's thread checks, so everything
     * hops back onto the server thread first.
     */
    private void runSync(Runnable action) {
        plugin.getServer().getScheduler().runTask(plugin, action);
    }

    private void startWatch(Player staff, java.util.UUID targetId) {
        if (!staff.isOnline()) {
            return;
        }
        Player target = plugin.getServer().getPlayer(targetId);
        if (target == null || !target.isOnline() || target.equals(staff)) {
            return;
        }
        plugin.startWatch(staff, target);
    }
}
