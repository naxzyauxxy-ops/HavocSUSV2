package com.havocsus.alerts;

import com.havocsus.HavocSusPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Announces anti-cheat flags to staff in chat, clickable to start watching.
 *
 * The whole design constraint here is "don't be annoying". A cheater trips the
 * same check dozens of times a minute, and one line per flag would bury real
 * chat within seconds. So there are two throttles: one per player and a longer
 * one per player-and-check, and the line carries the running total rather than
 * appearing once per hit.
 */
public final class AlertNotifier {

    private final HavocSusPlugin plugin;
    private final AlertStore store;

    private final Map<UUID, Long> lastPlayerAlert = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCheckAlert = new ConcurrentHashMap<>();

    public AlertNotifier(HavocSusPlugin plugin, AlertStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public void announce(Player target, String antiCheat, String check, double violation) {
        if (target == null || !plugin.getConfig().getBoolean("alerts.chat.enabled", true)) {
            return;
        }
        if (violation < plugin.getConfig().getDouble("alerts.chat.min-violation", 0.0D)) {
            return;
        }
        if (plugin.isWatchProtected(target)
                && plugin.getConfig().getBoolean("alerts.chat.skip-staff", true)) {
            return;
        }

        long now = System.currentTimeMillis();
        long playerGap = plugin.getConfig().getLong("alerts.chat.cooldown-seconds", 20L) * 1000L;
        long checkGap = plugin.getConfig().getLong("alerts.chat.per-check-cooldown-seconds", 60L) * 1000L;

        UUID uuid = target.getUniqueId();
        String checkKey = uuid + ":" + antiCheat + ":" + check;

        Long lastForPlayer = lastPlayerAlert.get(uuid);
        Long lastForCheck = lastCheckAlert.get(checkKey);
        if (lastForPlayer != null && now - lastForPlayer < playerGap) {
            return;
        }
        if (lastForCheck != null && now - lastForCheck < checkGap) {
            return;
        }
        lastPlayerAlert.put(uuid, now);
        lastCheckAlert.put(checkKey, now);

        int total = store.total(uuid);
        String watchCommand = plugin.getConfig().getString("alerts.chat.click-command", "/hs %player%")
                .replace("%player%", target.getName());

        Component line = MiniMessage.miniMessage().deserialize(
                plugin.settings().rawMessage("alert-line"),
                Placeholder.unparsed("player", target.getName()),
                Placeholder.unparsed("anticheat", antiCheat),
                Placeholder.unparsed("check", check),
                Placeholder.unparsed("vl", format(violation)),
                Placeholder.unparsed("total", String.valueOf(total)))
                .clickEvent(ClickEvent.runCommand(watchCommand))
                .hoverEvent(HoverEvent.showText(MiniMessage.miniMessage().deserialize(
                        plugin.settings().rawMessage("alert-hover"),
                        Placeholder.unparsed("player", target.getName()))));

        String permission = plugin.getConfig().getString("alerts.chat.permission", "havocsus.alerts");
        for (Player staff : plugin.getServer().getOnlinePlayers()) {
            if (staff.equals(target) || !staff.hasPermission(permission)) {
                continue;
            }
            staff.sendMessage(line);
        }
    }

    public void clear(UUID uuid) {
        lastPlayerAlert.remove(uuid);
    }

    private static String format(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.format("%.1f", value);
    }
}
