package com.havocsus.dialog;

import com.havocsus.HavocSusPlugin;
import com.havocsus.hook.PunishHook;
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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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
    private static final TextColor WARN = TextColor.color(0xFFD479);

    /** Buttons past this point make the screen unusable, so we cut it off. */
    private static final int MAX_BUTTONS = 40;

    /** Reasons per page in a punishment category. */
    private static final int PAGE_SIZE = 20;

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

    /**
     * The punish screen: shown when staff run /sus again while already watching
     * someone.
     *
     * A real reason list runs to dozens of entries (the shipped DonutPunishments
     * config has 60), and dumping all of them into one multi-action dialog gives
     * you an unusable wall of buttons. So this is a category screen - bans,
     * mutes, kicks - and each category is paged.
     */
    public void openPunish(Player staff, Player target) {
        List<PunishHook.Reason> reasons = plugin.punishments().reasons();

        // Grouped by type, preserving the order they appear in the file so
        // related reasons stay together instead of being alphabetised apart.
        Map<String, List<PunishHook.Reason>> byType = new LinkedHashMap<>();
        for (PunishHook.Reason reason : reasons) {
            byType.computeIfAbsent(reason.type().toLowerCase(Locale.ROOT),
                    k -> new ArrayList<>()).add(reason);
        }

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text()
                .append(Component.text("Punishing ", MUTED))
                .append(Component.text(target.getName(), NamedTextColor.WHITE))
                .build()));
        if (reasons.isEmpty()) {
            body.add(DialogBody.plainMessage(Component.text(
                    "No punishment reasons are configured.", DANGER)));
        } else {
            body.add(DialogBody.plainMessage(Component.text(
                    reasons.size() + " reasons · duration scales with prior offences", MUTED)));
        }

        List<ActionButton> buttons = new ArrayList<>();
        for (Map.Entry<String, List<PunishHook.Reason>> entry : byType.entrySet()) {
            String type = entry.getKey();
            int count = entry.getValue().size();
            buttons.add(ActionButton.create(
                    Component.text(prettify(type) + "  (" + count + ")", colourFor(type)),
                    Component.text("Browse the " + count + " " + type + " reasons", MUTED),
                    150,
                    DialogAction.customClick(
                            (view, audience) -> runSync(() ->
                                    openPunishCategory(staff, target, type, 0)),
                            clickOptions())));
        }
        buttons.add(stopWatchingButton(staff));

        showScreen(staff,
                Component.text("Punish " + target.getName(), DANGER, TextDecoration.BOLD),
                body, buttons, ActionButton.create(Component.text("Close", MUTED), null, 100, null));
    }

    /** One page of reasons for a single punishment type. */
    public void openPunishCategory(Player staff, Player target, String type, int page) {
        List<PunishHook.Reason> all = new ArrayList<>();
        for (PunishHook.Reason reason : plugin.punishments().reasons()) {
            if (reason.type().equalsIgnoreCase(type)) {
                all.add(reason);
            }
        }
        if (all.isEmpty()) {
            openPunish(staff, target);
            return;
        }

        int pages = (all.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int current = Math.max(0, Math.min(page, pages - 1));
        int from = current * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, all.size());

        List<ActionButton> buttons = new ArrayList<>();
        for (PunishHook.Reason reason : all.subList(from, to)) {
            buttons.add(punishButton(staff, target, reason));
        }

        if (current > 0) {
            final int previous = current - 1;
            buttons.add(ActionButton.create(
                    Component.text("« Previous page", MUTED), null, 150,
                    DialogAction.customClick(
                            (view, audience) -> runSync(() ->
                                    openPunishCategory(staff, target, type, previous)),
                            clickOptions())));
        }
        if (current < pages - 1) {
            final int next = current + 1;
            buttons.add(ActionButton.create(
                    Component.text("Next page »", MUTED), null, 150,
                    DialogAction.customClick(
                            (view, audience) -> runSync(() ->
                                    openPunishCategory(staff, target, type, next)),
                            clickOptions())));
        }
        buttons.add(ActionButton.create(
                Component.text("Back", ACCENT), null, 150,
                DialogAction.customClick(
                        (view, audience) -> runSync(() -> openPunish(staff, target)),
                        clickOptions())));

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text()
                .append(Component.text(target.getName(), NamedTextColor.WHITE))
                .append(Component.text("  ·  page " + (current + 1) + " of " + pages, MUTED))
                .build()));

        showScreen(staff,
                Component.text(prettify(type) + " reasons", colourFor(type), TextDecoration.BOLD),
                body, buttons, ActionButton.create(Component.text("Close", MUTED), null, 100, null));
    }

    private ActionButton stopWatchingButton(Player staff) {
        return ActionButton.create(
                Component.text("Stop watching", GOOD),
                Component.text("End the session and go back where you were", MUTED),
                150,
                DialogAction.customClick(
                        (view, audience) -> runSync(() -> {
                            if (plugin.escorts().isEscorting(staff)) {
                                plugin.escorts().release(staff, "left via dialog");
                            }
                        }),
                        clickOptions()));
    }

    /** Shared plumbing so every screen is built the same way. */
    private void showScreen(Player staff, Component title, List<DialogBody> body,
                            List<ActionButton> buttons, ActionButton exitButton) {
        int columns = buttons.size() <= 6 ? 1 : 2;
        final List<DialogBody> finalBody = List.copyOf(body);
        final List<ActionButton> finalButtons = List.copyOf(buttons);

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(true)
                        .body(finalBody)
                        .build())
                .type(DialogType.multiAction(finalButtons, exitButton, columns)));

        staff.showDialog(dialog);
    }

    private static TextColor colourFor(String type) {
        if ("ban".equalsIgnoreCase(type)) {
            return DANGER;
        }
        if ("mute".equalsIgnoreCase(type)) {
            return WARN;
        }
        return ACCENT;
    }

    private ActionButton punishButton(Player staff, Player target, PunishHook.Reason reason) {
        TextColor colour = colourFor(reason.type());
        String durationLabel = reason.durationLabel();

        Component tooltip = Component.text()
                .append(Component.text(reason.type().toUpperCase(Locale.ROOT), colour))
                .append(durationLabel.isEmpty()
                        ? Component.empty()
                        : Component.text(" · " + durationLabel, MUTED))
                .append(reason.message().isBlank()
                        ? Component.empty()
                        : Component.newline().append(Component.text(reason.message(), MUTED)))
                .build();

        String command = plugin.punishments().buildCommand(target.getName(), reason);

        return ActionButton.create(
                Component.text(prettify(reason.key()), colour),
                tooltip,
                150,
                DialogAction.customClick(
                        (view, audience) -> runSync(() -> {
                            if (staff.isOnline()) {
                                // Dispatched as the staff member so their
                                // permissions apply and the punishment is
                                // attributed to them, not to console.
                                plugin.getServer().dispatchCommand(staff, command);
                            }
                        }),
                        clickOptions()));
    }

    private static String prettify(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(key.charAt(0)) + key.substring(1);
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
