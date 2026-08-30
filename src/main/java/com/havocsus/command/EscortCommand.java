package com.havocsus.command;

import com.havocsus.HavocSusPlugin;
import com.havocsus.escort.EscortSession;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class EscortCommand implements CommandExecutor, TabCompleter {

    private final HavocSusPlugin plugin;

    public EscortCommand(HavocSusPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(plugin.settings().msg("usage"));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            if (!sender.hasPermission("havocsus.admin")) {
                sender.sendMessage(plugin.settings().msg("no-permission"));
                return true;
            }
            plugin.reloadEverything();
            sender.sendMessage(plugin.settings().msg("reloaded"));
            return true;
        }

        if (sub.equals("radius")) {
            if (!sender.hasPermission("havocsus.admin")) {
                sender.sendMessage(plugin.settings().msg("no-permission"));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(plugin.settings().msg("usage"));
                return true;
            }
            try {
                plugin.settings().setRadius(Double.parseDouble(args[1]));
                sender.sendMessage(plugin.settings().msg("radius-set",
                        "<radius>", String.valueOf((long) plugin.settings().radius)));
            } catch (NumberFormatException ex) {
                sender.sendMessage(plugin.settings().msg("usage"));
            }
            return true;
        }

        if (!(sender instanceof Player staff)) {
            sender.sendMessage(Component.text("Players only."));
            return true;
        }

        if (sub.equals("quit") || sub.equals("exit") || sub.equals("stop")) {
            if (!plugin.escorts().isEscorting(staff)) {
                staff.sendMessage(plugin.settings().msg("not-escorting"));
                return true;
            }
            plugin.escorts().release(staff, "left manually");
            return true;
        }

        if (sub.equals("list") || sub.equals("dialog") || sub.equals("menu")) {
            plugin.openWatchList(staff);
            return true;
        }

        if (sub.equals("status")) {
            EscortSession session = plugin.escorts().session(staff);
            if (session == null) {
                staff.sendMessage(plugin.settings().msg("not-escorting"));
                return true;
            }
            Player current = session.target();
            String distance = "?";
            if (current != null && current.getWorld().equals(staff.getWorld())) {
                distance = String.valueOf((int) Math.round(staff.getLocation().distance(current.getLocation())));
            }
            staff.sendMessage(plugin.settings().bare("action-bar",
                    "<target>", current == null ? "?" : current.getName(),
                    "<distance>", distance,
                    "<radius>", String.valueOf((long) plugin.settings().radius),
                    "<mode>", session.mode().name()));
            return true;
        }

        // /escort <player> - start an escort by hand, no SUS GUI needed.
        if (!staff.hasPermission("havocsus.use")) {
            staff.sendMessage(plugin.settings().msg("no-permission"));
            return true;
        }
        Player target = plugin.getServer().getPlayerExact(args[0]);
        if (target == null || target.equals(staff)) {
            staff.sendMessage(plugin.settings().msg("usage"));
            return true;
        }
        EscortSession existing = plugin.escorts().session(staff);
        if (existing != null) {
            existing.allowNextTeleport();
        }
        // Snapshot before the teleport, so /escort quit returns them here.
        Location origin = staff.getLocation().clone();
        staff.teleport(target.getLocation());
        plugin.escorts().engage(staff, target, origin);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return Collections.emptyList();
        }
        List<String> options = new ArrayList<>(List.of("quit", "status", "list"));
        if (sender.hasPermission("havocsus.admin")) {
            options.add("reload");
            options.add("radius");
        }
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            options.add(online.getName());
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(prefix));
        return options;
    }
}
