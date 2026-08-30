package com.havocsus.command;

import com.havocsus.HavocSusPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Registers /sus at runtime, but only when nothing else already owns it.
 *
 * Declaring `sus` as an alias in plugin.yml would have been a coin flip: if
 * HavocSus happened to load before the SUS plugin, we'd take the name and SUS's
 * own command would be exiled to sus:sus, breaking their GUI. Checking the
 * command map at enable time is deterministic - if SUS is installed it keeps
 * /sus and we fall back to intercepting it, and if SUS isn't installed we
 * provide /sus ourselves so the command always exists.
 */
public final class SusCommandRegistrar {

    private final HavocSusPlugin plugin;
    private boolean claimed;

    public SusCommandRegistrar(HavocSusPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean hasClaimedSus() {
        return claimed;
    }

    public void register() {
        if (!plugin.settings().registerSusIfAbsent) {
            return;
        }
        try {
            CommandMap map = plugin.getServer().getCommandMap();
            if (map == null) {
                return;
            }
            Command existing = map.getCommand("sus");
            if (existing != null) {
                plugin.getLogger().info("/sus is already provided by another plugin - "
                        + "leaving it alone and intercepting it instead.");
                return;
            }

            Command command = new Command("sus", "Open the HavocSus watch list.",
                    "/sus [player]", List.of("suspicious")) {

                @Override
                public boolean execute(CommandSender sender, String label, String[] args) {
                    return handle(sender, args);
                }

                @Override
                public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                    return complete(sender, args);
                }
            };
            command.setPermission("havocsus.use");

            map.register("havocsus", command);

            // The client keeps its own copy of the command tree, so anyone
            // already online needs it resent or /sus stays red in chat.
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                online.updateCommands();
            }

            claimed = true;
            plugin.getLogger().info("Registered /sus (nothing else was using it).");
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not register /sus: " + t);
        }
    }

    private boolean handle(CommandSender sender, String[] args) {
        if (!(sender instanceof Player staff)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (!staff.hasPermission("havocsus.use")) {
            staff.sendMessage(plugin.settings().msg("no-permission"));
            return true;
        }

        if (args.length >= 1 && (args[0].equalsIgnoreCase("quit")
                || args[0].equalsIgnoreCase("exit"))) {
            if (!plugin.escorts().isEscorting(staff)) {
                staff.sendMessage(plugin.settings().msg("not-escorting"));
                return true;
            }
            plugin.escorts().release(staff, "left manually");
            return true;
        }

        if (args.length >= 1) {
            Player target = plugin.getServer().getPlayerExact(args[0]);
            if (target == null) {
                target = plugin.getServer().getPlayer(args[0]);
            }
            if (target == null || target.equals(staff)) {
                staff.sendMessage(plugin.settings().msg("player-not-found",
                        "<name>", args[0]));
                return true;
            }
            plugin.startWatch(staff, target);
            return true;
        }

        plugin.openSusScreen(staff);
        return true;
    }

    private List<String> complete(CommandSender sender, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        if ("quit".startsWith(prefix)) {
            names.add("quit");
        }
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                names.add(online.getName());
            }
        }
        return names;
    }
}
