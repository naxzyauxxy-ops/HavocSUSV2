package com.havocsus.dialog;

import com.havocsus.HavocSusPlugin;
import com.havocsus.escort.EscortSession;
import com.havocsus.hook.BanListHook;
import com.havocsus.hook.FlagStatsHook;
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

    private static final int TOOLTIP_CHECKS = 4;
    private static final int CHECK_LINES = 12;

    /** Body lines per page on the list screens. */
    private static final int LIST_LINES = 10;

    private final HavocSusPlugin plugin;

    public WatchDialog(HavocSusPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Whether a player is hidden from the lists.
     *
     * This is OFF by default, and deliberately so. havocsus.hidefromlist is an
     * opt-OUT node, which means any admin holding a wildcard ("*" or
     * "havocsus.*" in LuckPerms) matches it and vanishes from every list -
     * which looks exactly like "nobody is online" on a server full of staff.
     * Only servers that have actually set the node up should turn this on.
     */
    private boolean isHidden(Player player) {
        if (plugin.getConfig().getBoolean("watch-list.respect-hide-permission", false)
                && player.hasPermission("havocsus.hidefromlist")) {
            return true;
        }
        // Staff aren't watch targets, and someone already being watched by
        // another staff member isn't available either.
        return plugin.isWatchProtected(player) || plugin.watcherOf(player) != null;
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
            if (online.equals(staff) || isHidden(online)) {
                continue;
            }
            candidates.add(online);
        }
        // Worst offenders first - that's the whole point of the list. Ties and
        // unflagged players fall back to alphabetical so the order is stable.
        if (plugin.flagStats().isAvailable()
                && plugin.getConfig().getBoolean("alerts.sort-watch-list-by-alerts", true)) {
            candidates.sort(Comparator
                    .comparingInt((Player p) -> plugin.flagStats().alertCount(p.getUniqueId()))
                    .reversed()
                    .thenComparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        } else {
            candidates.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        }

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
        buttons.add(ActionButton.create(
                Component.text("Back", ACCENT), null, 150,
                DialogAction.customClick(
                        (view, audience) -> runSync(() -> openMenu(staff)), clickOptions())));

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
        FlagStatsHook stats = plugin.flagStats();
        FlagStatsHook.PlayerFlags flags = stats.summary(target.getUniqueId());
        int alerts = flags == null ? 0 : flags.amount();

        // Alert count goes in the label, not just the tooltip - staff shouldn't
        // have to hover every name to find the worst offender.
        Component label = alerts > 0
                ? Component.text()
                        .append(Component.text(name, NamedTextColor.WHITE))
                        .append(Component.text("  " + alerts, alertColour(alerts)))
                        .build()
                : Component.text(name, MUTED);

        Component tooltip = buildTooltip(target, flags, alerts);

        return ActionButton.create(label, tooltip, 150,
                DialogAction.customClick(
                        (view, audience) -> runSync(() -> startWatch(staff, target.getUniqueId())),
                        clickOptions()));
    }

    private Component buildTooltip(Player target, FlagStatsHook.PlayerFlags flags, int alerts) {
        var builder = Component.text();
        if (alerts > 0 && flags != null) {
            builder.append(Component.text(alerts + " alerts", alertColour(alerts)))
                    .append(Component.text("  ·  " + flags.antiCheat(), MUTED))
                    .append(Component.newline());

            List<FlagStatsHook.CheckStat> top = plugin.flagStats().checks(target.getUniqueId());
            int shown = 0;
            for (FlagStatsHook.CheckStat check : top) {
                if (shown++ >= TOOLTIP_CHECKS) {
                    break;
                }
                builder.append(Component.text(check.checkName(), NamedTextColor.WHITE))
                        .append(Component.text(" x" + check.amount()
                                + " (vl " + formatVl(check.violationLevel()) + ")", MUTED))
                        .append(Component.newline());
            }
            if (top.size() > TOOLTIP_CHECKS) {
                builder.append(Component.text("+" + (top.size() - TOOLTIP_CHECKS)
                        + " more checks", MUTED)).append(Component.newline());
            }
        } else {
            builder.append(Component.text("No alerts on record", MUTED)).append(Component.newline());
        }
        builder.append(Component.text("Click to teleport and watch", GOOD));
        return builder.build();
    }

    /** Cool for a handful of alerts, hot for a lot. */
    private static TextColor alertColour(int alerts) {
        if (alerts >= 50) {
            return DANGER;
        }
        if (alerts >= 15) {
            return WARN;
        }
        return GOOD;
    }

    private static String formatVl(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.format("%.1f", value);
    }

    /**
     * The hub. Everything hangs off this so staff have one entry point rather
     * than a set of commands to memorise.
     */
    public void openMenu(Player staff) {
        EscortSession session = plugin.escorts().session(staff);
        Player watching = session == null ? null : session.target();

        int online = Math.max(0, plugin.getServer().getOnlinePlayers().size() - 1);
        List<FlagStatsHook.PlayerFlags> top = plugin.flagStats().topAlerts(1);
        List<BanListHook.BanEntry> bans = plugin.bans().bans();

        List<DialogBody> body = new ArrayList<>();
        if (watching != null && watching.isOnline()) {
            body.add(DialogBody.plainMessage(Component.text()
                    .append(Component.text("Watching ", MUTED))
                    .append(Component.text(watching.getName(), NamedTextColor.WHITE))
                    .build()));
        } else if (!top.isEmpty()) {
            FlagStatsHook.PlayerFlags worst = top.get(0);
            body.add(DialogBody.plainMessage(Component.text()
                    .append(Component.text("Most alerts: ", MUTED))
                    .append(Component.text(worst.name(), NamedTextColor.WHITE))
                    .append(Component.text("  " + worst.amount(), alertColour(worst.amount())))
                    .build()));
        }

        List<ActionButton> buttons = new ArrayList<>();

        buttons.add(ActionButton.create(
                Component.text("Watch list  (" + online + ")", ACCENT),
                Component.text("Online players, worst alerts first", MUTED),
                150,
                DialogAction.customClick(
                        (view, audience) -> runSync(() -> open(staff)), clickOptions())));

        buttons.add(ActionButton.create(
                Component.text("Top alerts", WARN),
                Component.text("Highest alert counts on record, online or not", MUTED),
                150,
                DialogAction.customClick(
                        (view, audience) -> runSync(() -> openTopAlerts(staff, 0)), clickOptions())));

        // Always a dialog, even with zero bans or no hook - a button that does
        // nothing visible is worse than a screen that says "none".
        buttons.add(ActionButton.create(
                Component.text("Ban list" + (plugin.bans().isAvailable()
                        ? "  (" + bans.size() + ")" : ""), DANGER),
                Component.text("Currently active bans", MUTED),
                150,
                DialogAction.customClick(
                        (view, audience) -> runSync(() -> openBanList(staff, 0)), clickOptions())));

        buttons.add(ActionButton.create(
                Component.text("Punish a player", DANGER),
                Component.text("Pick anyone online - no need to watch them first", MUTED),
                150,
                DialogAction.customClick(
                        (view, audience) -> runSync(() -> openPunishPicker(staff)), clickOptions())));

        if (watching != null && watching.isOnline()) {
            final Player target = watching;
            buttons.add(ActionButton.create(
                    Component.text("Checks · " + target.getName(), ACCENT), null, 150,
                    DialogAction.customClick(
                            (view, audience) -> runSync(() -> openChecks(staff, target)),
                            clickOptions())));
            buttons.add(ActionButton.create(
                    Component.text("Punish · " + target.getName(), DANGER), null, 150,
                    DialogAction.customClick(
                            (view, audience) -> runSync(() -> openPunish(staff, target)),
                            clickOptions())));
            buttons.add(stopWatchingButton(staff));
        }

        showScreen(staff,
                Component.text("HavocSus", ACCENT, TextDecoration.BOLD),
                body, buttons,
                ActionButton.create(Component.text("Close", MUTED), null, 100, null));
    }

    /** Pick any online player to punish, without starting a watch session. */
    public void openPunishPicker(Player staff) {
        List<Player> candidates = new ArrayList<>();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (!online.equals(staff) && !isHidden(online)) {
                candidates.add(online);
            }
        }
        candidates.sort(Comparator
                .comparingInt((Player p) -> plugin.flagStats().alertCount(p.getUniqueId()))
                .reversed()
                .thenComparing(Player::getName, String.CASE_INSENSITIVE_ORDER));

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(candidates.isEmpty()
                ? Component.text("Nobody else is online.", MUTED)
                : Component.text("Pick a player to punish.", MUTED)));

        List<ActionButton> buttons = new ArrayList<>();
        for (Player target : candidates) {
            if (buttons.size() >= MAX_BUTTONS) {
                break;
            }
            int alerts = plugin.flagStats().alertCount(target.getUniqueId());
            buttons.add(ActionButton.create(
                    alerts > 0
                            ? Component.text()
                                    .append(Component.text(target.getName(), NamedTextColor.WHITE))
                                    .append(Component.text("  " + alerts, alertColour(alerts)))
                                    .build()
                            : Component.text(target.getName(), MUTED),
                    Component.text("Open the punish screen for " + target.getName(), MUTED),
                    150,
                    DialogAction.customClick(
                            (view, audience) -> runSync(() -> openPunish(staff, target)),
                            clickOptions())));
        }
        buttons.add(ActionButton.create(Component.text("Back", ACCENT), null, 150,
                DialogAction.customClick(
                        (view, audience) -> runSync(() -> openMenu(staff)), clickOptions())));

        showScreen(staff,
                Component.text("Punish a player", DANGER, TextDecoration.BOLD),
                body, buttons,
                ActionButton.create(Component.text("Close", MUTED), null, 100, null));
    }

    /** Leaderboard of alert counts, including players who are offline. */
    public void openTopAlerts(Player staff, int page) {
        List<FlagStatsHook.PlayerFlags> all = plugin.flagStats()
                .topAlerts(plugin.getConfig().getInt("alerts.top-limit", 60));

        List<DialogBody> body = new ArrayList<>();
        if (all.isEmpty()) {
            body.add(DialogBody.plainMessage(Component.text(
                    "No alerts on record yet.", MUTED)));
        }

        int pages = Math.max(1, (all.size() + LIST_LINES - 1) / LIST_LINES);
        int current = Math.max(0, Math.min(page, pages - 1));
        int from = current * LIST_LINES;
        int to = Math.min(from + LIST_LINES, all.size());

        for (int i = from; i < to; i++) {
            FlagStatsHook.PlayerFlags entry = all.get(i);
            boolean online = plugin.getServer().getPlayer(entry.uuid()) != null;
            body.add(DialogBody.plainMessage(Component.text()
                    .append(Component.text((i + 1) + ". ", MUTED))
                    .append(Component.text(entry.name(),
                            online ? NamedTextColor.WHITE : MUTED))
                    .append(Component.text("  " + entry.amount(), alertColour(entry.amount())))
                    .append(Component.text("  " + entry.antiCheat()
                            + " · " + entry.lastCheck(), MUTED))
                    .build()));
        }

        List<ActionButton> buttons = new ArrayList<>();
        // Only online players can actually be watched, so only they get buttons.
        for (int i = from; i < to; i++) {
            FlagStatsHook.PlayerFlags entry = all.get(i);
            Player target = plugin.getServer().getPlayer(entry.uuid());
            if (target == null || target.equals(staff)) {
                continue;
            }
            buttons.add(watchButton(staff, target));
        }
        addPaging(buttons, current, pages,
                p -> openTopAlerts(staff, p), () -> openMenu(staff));

        showScreen(staff,
                Component.text("Top alerts", WARN, TextDecoration.BOLD),
                body, buttons,
                ActionButton.create(Component.text("Close", MUTED), null, 100, null));
    }

    /** Active bans, newest first. */
    public void openBanList(Player staff, int page) {
        List<BanListHook.BanEntry> all = plugin.bans().bans();

        List<DialogBody> body = new ArrayList<>();
        if (all.isEmpty()) {
            body.add(DialogBody.plainMessage(Component.text(
                    plugin.bans().isAvailable()
                            ? "No active bans."
                            : "Ban data isn't readable from here.", MUTED)));
        }

        int pages = Math.max(1, (all.size() + LIST_LINES - 1) / LIST_LINES);
        int current = Math.max(0, Math.min(page, pages - 1));
        int from = current * LIST_LINES;
        int to = Math.min(from + LIST_LINES, all.size());

        for (int i = from; i < to; i++) {
            BanListHook.BanEntry ban = all.get(i);
            body.add(DialogBody.plainMessage(Component.text()
                    .append(Component.text(ban.player(), NamedTextColor.WHITE))
                    .append(Component.text("  " + (ban.isPermanent()
                            ? "permanent" : remaining(ban.expiresAt())), DANGER))
                    .append(Component.text("  " + ban.reason(), MUTED))
                    .build()));
        }
        if (!all.isEmpty()) {
            body.add(DialogBody.plainMessage(Component.text(
                    all.size() + " active · page " + (current + 1) + " of " + pages, MUTED)));
        }

        List<ActionButton> buttons = new ArrayList<>();
        if (all.isEmpty()) {
            buttons.add(ActionButton.create(
                    Component.text("Run /banlist", MUTED),
                    Component.text("Ask DonutPunishments directly, in chat", MUTED),
                    150,
                    DialogAction.customClick(
                            (view, audience) -> runSync(() ->
                                    plugin.getServer().dispatchCommand(staff, "banlist")),
                            clickOptions())));
        }
        addPaging(buttons, current, pages,
                p -> openBanList(staff, p), () -> openMenu(staff));

        showScreen(staff,
                Component.text("Ban list", DANGER, TextDecoration.BOLD),
                body, buttons,
                ActionButton.create(Component.text("Close", MUTED), null, 100, null));
    }

    private void addPaging(List<ActionButton> buttons, int current, int pages,
                           java.util.function.IntConsumer goToPage, Runnable back) {
        if (current > 0) {
            final int previous = current - 1;
            buttons.add(ActionButton.create(Component.text("\u00ab Previous", MUTED), null, 150,
                    DialogAction.customClick((view, audience) ->
                            runSync(() -> goToPage.accept(previous)), clickOptions())));
        }
        if (current < pages - 1) {
            final int next = current + 1;
            buttons.add(ActionButton.create(Component.text("Next \u00bb", MUTED), null, 150,
                    DialogAction.customClick((view, audience) ->
                            runSync(() -> goToPage.accept(next)), clickOptions())));
        }
        buttons.add(ActionButton.create(Component.text("Back", ACCENT), null, 150,
                DialogAction.customClick((view, audience) ->
                        runSync(back), clickOptions())));
    }

    private static String remaining(Long expiresAt) {
        if (expiresAt == null) {
            return "permanent";
        }
        long ms = expiresAt - System.currentTimeMillis();
        if (ms <= 0) {
            return "expired";
        }
        long days = ms / 86_400_000L;
        if (days > 0) {
            return days + "d left";
        }
        long hours = ms / 3_600_000L;
        if (hours > 0) {
            return hours + "h left";
        }
        return Math.max(1L, ms / 60_000L) + "m left";
    }

    /** Full per-check breakdown for one player. */
    public void openChecks(Player staff, Player target) {
        List<FlagStatsHook.CheckStat> stats = plugin.flagStats().checks(target.getUniqueId());
        FlagStatsHook.PlayerFlags flags = plugin.flagStats().summary(target.getUniqueId());

        List<DialogBody> body = new ArrayList<>();
        if (flags != null) {
            body.add(DialogBody.plainMessage(Component.text()
                    .append(Component.text(flags.amount() + " alerts", alertColour(flags.amount())))
                    .append(Component.text("  ·  " + flags.antiCheat(), MUTED))
                    .build()));
        }
        if (stats.isEmpty()) {
            body.add(DialogBody.plainMessage(Component.text("No checks on record.", MUTED)));
        } else {
            int shown = 0;
            for (FlagStatsHook.CheckStat check : stats) {
                if (shown++ >= CHECK_LINES) {
                    body.add(DialogBody.plainMessage(Component.text(
                            "+" + (stats.size() - CHECK_LINES) + " more", MUTED)));
                    break;
                }
                body.add(DialogBody.plainMessage(Component.text()
                        .append(Component.text(check.checkName(), NamedTextColor.WHITE))
                        .append(Component.text("  x" + check.amount(), alertColour(check.amount())))
                        .append(Component.text("   vl " + formatVl(check.violationLevel())
                                + "   " + check.antiCheat(), MUTED))
                        .build()));
            }
        }

        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(ActionButton.create(
                Component.text("Punish", DANGER), null, 150,
                DialogAction.customClick(
                        (view, audience) -> runSync(() -> openPunish(staff, target)),
                        clickOptions())));
        buttons.add(stopWatchingButton(staff));

        showScreen(staff,
                Component.text(target.getName() + " · checks", ACCENT, TextDecoration.BOLD),
                body, buttons, ActionButton.create(Component.text("Close", MUTED), null, 100, null));
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
        buttons.add(ActionButton.create(
                Component.text("View checks", ACCENT),
                Component.text("Every check this player has tripped", MUTED),
                150,
                DialogAction.customClick(
                        (view, audience) -> runSync(() -> openChecks(staff, target)),
                        clickOptions())));
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
        List<ActionButton> safeButtons = new ArrayList<>(buttons);

        // A multi-action dialog with zero buttons is not a valid dialog, and
        // trying to show one means the screen silently never opens. An empty
        // list is a normal state here (nobody online, no bans), so guarantee a
        // way back instead of letting it fail.
        if (safeButtons.isEmpty()) {
            safeButtons.add(ActionButton.create(
                    Component.text("Back", ACCENT), null, 150,
                    DialogAction.customClick(
                            (view, audience) -> runSync(() -> openMenu(staff)),
                            clickOptions())));
        }

        int columns = safeButtons.size() <= 6 ? 1 : 2;
        final List<DialogBody> finalBody = List.copyOf(body);
        final List<ActionButton> finalButtons = List.copyOf(safeButtons);

        try {
            Dialog dialog = Dialog.create(builder -> builder.empty()
                    .base(DialogBase.builder(title)
                            .canCloseWithEscape(true)
                            .body(finalBody)
                            .build())
                    .type(DialogType.multiAction(finalButtons, exitButton, columns)));

            staff.showDialog(dialog);
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not show dialog (" + finalButtons.size()
                    + " buttons, " + finalBody.size() + " body lines): " + t);
            staff.sendMessage(plugin.settings().msg("dialog-failed"));
        }
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
                        (view, audience) -> runSync(() -> runPunish(staff, target, reason, command)),
                        clickOptions()));
    }

    /**
     * Runs the punishment and, crucially, says what happened.
     *
     * Silently firing a command and hoping is how "banning doesn't work" turns
     * into an unanswerable bug report - the refusal (missing permission, an
     * exempt target, an unknown reason key) is printed by the punishment plugin
     * and easily missed behind a dialog. So we echo the exact command, report a
     * dispatch that wasn't accepted, and log both.
     */
    private void runPunish(Player staff, Player target, PunishHook.Reason reason, String command) {
        if (!staff.isOnline()) {
            return;
        }
        boolean console = plugin.getConfig().getBoolean("punish.run-as-console", false);
        boolean accepted;
        try {
            accepted = console
                    ? plugin.getServer().dispatchCommand(
                            plugin.getServer().getConsoleSender(), command)
                    // Default: as the staff member, so their permissions apply
                    // and the punishment is attributed to them.
                    : plugin.getServer().dispatchCommand(staff, command);
        } catch (Throwable t) {
            accepted = false;
            plugin.getLogger().warning("Punish command threw for /" + command + ": " + t);
        }

        if (accepted) {
            staff.sendMessage(plugin.settings().msg("punish-sent",
                    "<target>", target.getName(),
                    "<reason>", reason.key()));
            if (plugin.getConfig().getBoolean("punish.log-commands", true)) {
                plugin.getLogger().info(staff.getName() + " ran /" + command);
            }
        } else {
            staff.sendMessage(plugin.settings().msg("punish-failed", "<command>", command));
            plugin.getLogger().warning("Punish command was not accepted: /" + command
                    + " (is DonutPunishments loaded, and does " + staff.getName()
                    + " have punishments.punish?)");
        }
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
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                action.run();
            } catch (Throwable t) {
                // Without this, a failure inside a button callback vanishes into
                // the scheduler and the button just looks dead.
                plugin.getLogger().warning("Dialog action failed: " + t);
            }
        });
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
