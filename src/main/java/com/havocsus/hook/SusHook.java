package com.havocsus.hook;

import com.havocsus.HavocSusPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * Read-only bridge to SUS (com.simplesetupmc.sus).
 *
 * SUS doesn't expose an API or fire a custom event, so we identify its GUI by
 * inventory-holder package and pull the flagged player's UUID out of the head
 * item's persistent data container - the same key SUS stamps on it. Nothing
 * from SUS is imported or reimplemented; we only read public Bukkit metadata.
 */
public final class SusHook {

    private static final String SUS_GUI_PACKAGE = "com.simplesetupmc.sus.gui.";

    /** Key SUS stores the flagged player's UUID under. Namespace is not assumed. */
    private static final String TARGET_UUID_KEY = "target_uuid";
    private static final String TARGET_NAME_KEY = "target_name";

    private final HavocSusPlugin plugin;
    private final boolean available;

    public SusHook(HavocSusPlugin plugin) {
        this.plugin = plugin;
        this.available = plugin.getServer().getPluginManager().getPlugin("Sus") != null;
        if (!available) {
            plugin.getLogger().warning("SUS not found - GUI detection is inactive. "
                    + "Escorts can still be started manually with /escort <player>.");
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /** True if this inventory holder belongs to one of SUS's GUIs. */
    public boolean isSusGui(InventoryHolder holder) {
        return holder != null && holder.getClass().getName().startsWith(SUS_GUI_PACKAGE);
    }

    /** Pulls the flagged player's UUID off a clicked SUS GUI item, or null. */
    public UUID readTargetUuid(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        String value = readByKeyName(container, TARGET_UUID_KEY);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Fallback display name, if SUS stored one. */
    public String readTargetName(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        return meta == null ? null : readByKeyName(meta.getPersistentDataContainer(), TARGET_NAME_KEY);
    }

    /**
     * Matches on the key name only, ignoring the namespace, so a SUS rename or
     * repackage doesn't silently break the bridge.
     */
    private String readByKeyName(PersistentDataContainer container, String keyName) {
        if (container == null || container.isEmpty()) {
            return null;
        }
        for (NamespacedKey key : container.getKeys()) {
            if (!key.getKey().equalsIgnoreCase(keyName)) {
                continue;
            }
            try {
                String value = container.get(key, PersistentDataType.STRING);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            } catch (IllegalArgumentException ignored) {
                // key exists but isn't a string - not ours
            }
        }
        return null;
    }
}
