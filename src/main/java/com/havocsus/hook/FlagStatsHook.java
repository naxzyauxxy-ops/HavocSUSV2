package com.havocsus.hook;

import com.havocsus.HavocSusPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads alert counts and per-check breakdowns straight out of SUS's own
 * database, read-only.
 *
 * SUS has no API, but it does have a stable, unobfuscated schema:
 *
 *   flags         one row per player - amount, anti_cheat, last_check,
 *                 last_violation_level, last_flagged_at
 *   flag_history  one row per (player, anti-cheat, check) - amount,
 *                 violation_level, last_flagged_at
 *
 * Column names survive obfuscation because they're SQL strings, which makes
 * this far more durable than reflecting into their classes would be. We never
 * write, only read.
 *
 * All queries run off the main thread on a timer and land in a cache, so the
 * dialogs never touch JDBC while rendering.
 */
public final class FlagStatsHook {

    public record PlayerFlags(UUID uuid, String name, int amount, String antiCheat,
                              String lastCheck, double lastViolation, long lastFlaggedAt) {
    }

    public record CheckStat(String antiCheat, String checkName, int amount,
                            double violationLevel, long lastFlaggedAt) {
    }

    private final HavocSusPlugin plugin;

    private final Map<UUID, PlayerFlags> summaries = new ConcurrentHashMap<>();
    private final Map<UUID, List<CheckStat>> checks = new ConcurrentHashMap<>();

    private volatile boolean available;
    private volatile String jdbcUrl;
    private volatile String user;
    private volatile String password;
    private volatile String flagsTable = "flags";
    private volatile String historyTable = "flag_history";
    private volatile boolean warned;

    public FlagStatsHook(HavocSusPlugin plugin) {
        this.plugin = plugin;
        resolve();
    }

    public boolean isAvailable() {
        return available;
    }

    // ------------------------------------------------------------------
    // setup
    // ------------------------------------------------------------------

    private void resolve() {
        if (!plugin.getConfig().getBoolean("alerts.enabled", true)) {
            return;
        }
        Plugin sus = plugin.getServer().getPluginManager().getPlugin("Sus");
        File dataFolder = sus != null
                ? sus.getDataFolder()
                : new File(plugin.getDataFolder().getParentFile(), "Sus");

        File configFile = new File(dataFolder, "config.yml");
        if (!configFile.isFile()) {
            plugin.getLogger().info("SUS config not found - alert counts disabled.");
            return;
        }

        YamlConfiguration susConfig = YamlConfiguration.loadConfiguration(configFile);
        String type = String.valueOf(susConfig.getString("DATABASE.TYPE", "sqlite")).toLowerCase();

        String prefix = plugin.getConfig().getString("alerts.table-prefix", "");
        if (prefix == null) {
            prefix = "";
        }

        if (type.startsWith("mysql")) {
            String host = susConfig.getString("DATABASE.MYSQL.HOST", "localhost");
            int port = susConfig.getInt("DATABASE.MYSQL.PORT", 3306);
            String database = susConfig.getString("DATABASE.MYSQL.DATABASE", "sus");
            boolean ssl = susConfig.getBoolean("DATABASE.MYSQL.USE-SSL", false);
            this.user = susConfig.getString("DATABASE.MYSQL.USER", "root");
            this.password = susConfig.getString("DATABASE.MYSQL.PASSWORD", "");
            this.jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=" + ssl + "&characterEncoding=utf8";
            if (prefix.isEmpty()) {
                prefix = susConfig.getString("DATABASE.MYSQL.TABLE-PREFIX", "sus_");
            }
        } else {
            String fileName = susConfig.getString("DATABASE.SQLITE.FILE", "flags.db");
            File db = new File(dataFolder, fileName);
            if (!db.isFile()) {
                plugin.getLogger().info("SUS database (" + db.getName() + ") not found yet - "
                        + "alert counts will start working once SUS has recorded a flag.");
                return;
            }
            this.jdbcUrl = "jdbc:sqlite:" + db.getAbsolutePath();
            this.user = null;
            this.password = null;
        }

        this.flagsTable = prefix + "flags";
        this.historyTable = prefix + "flag_history";

        // Probe, and fall back to unprefixed names if the prefixed ones aren't
        // there - SQLite and MySQL setups don't agree on whether the prefix is
        // applied, so guessing once and checking beats assuming.
        if (!tableExists(flagsTable)) {
            if (tableExists("flags")) {
                flagsTable = "flags";
                historyTable = "flag_history";
            } else {
                plugin.getLogger().warning("Could not find SUS's flags table - alert counts disabled.");
                return;
            }
        }

        available = true;
        plugin.getLogger().info("Alert stats hooked into SUS (" + type + ", table " + flagsTable + ").");
    }

    private boolean tableExists(String table) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM " + table + " LIMIT 1")) {
            statement.executeQuery().close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private Connection connect() throws Exception {
        if (jdbcUrl == null) {
            throw new IllegalStateException("no database configured");
        }
        if (user == null) {
            return DriverManager.getConnection(jdbcUrl);
        }
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    // ------------------------------------------------------------------
    // refresh
    // ------------------------------------------------------------------

    public void startRefreshTask() {
        long period = Math.max(5L, plugin.getConfig().getLong("alerts.refresh-seconds", 10L)) * 20L;
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::refresh, 40L, period);
    }

    /** Runs off the main thread. Never call this from a dialog or listener. */
    public void refresh() {
        if (!available) {
            return;
        }
        List<UUID> online = new ArrayList<>();
        // getOnlinePlayers is safe to read here, but copy immediately.
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            online.add(player.getUniqueId());
        }
        try (Connection connection = connect()) {
            // Summaries cover everyone who has ever been flagged, so the
            // leaderboard still works when the offender is offline.
            loadSummaries(connection);
            if (online.isEmpty()) {
                checks.clear();
            } else {
                loadChecks(connection, online);
            }
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("Could not read SUS alert data: " + t
                        + " (this warning won't repeat)");
            }
        }
    }

    private void loadSummaries(Connection connection) throws Exception {
        Map<UUID, PlayerFlags> loaded = new HashMap<>();
        String sql = "SELECT player_uuid, player_name, amount, anti_cheat, last_check, "
                + "last_violation_level, last_flagged_at FROM " + flagsTable;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                UUID uuid = parseUuid(rs.getString("player_uuid"));
                if (uuid == null) {
                    continue;
                }
                loaded.put(uuid, new PlayerFlags(
                        uuid,
                        rs.getString("player_name"),
                        rs.getInt("amount"),
                        rs.getString("anti_cheat"),
                        rs.getString("last_check"),
                        rs.getDouble("last_violation_level"),
                        rs.getLong("last_flagged_at")));
            }
        }
        summaries.clear();
        summaries.putAll(loaded);
    }

    private void loadChecks(Connection connection, List<UUID> online) throws Exception {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < online.size(); i++) {
            in.append(i == 0 ? "?" : ",?");
        }
        String sql = "SELECT player_uuid, anti_cheat, check_name, amount, violation_level, "
                + "last_flagged_at FROM " + historyTable
                + " WHERE player_uuid IN (" + in + ")";

        Map<UUID, List<CheckStat>> loaded = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < online.size(); i++) {
                statement.setString(i + 1, online.get(i).toString());
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    UUID uuid = parseUuid(rs.getString("player_uuid"));
                    if (uuid == null) {
                        continue;
                    }
                    loaded.computeIfAbsent(uuid, k -> new ArrayList<>()).add(new CheckStat(
                            rs.getString("anti_cheat"),
                            rs.getString("check_name"),
                            rs.getInt("amount"),
                            rs.getDouble("violation_level"),
                            rs.getLong("last_flagged_at")));
                }
            }
        }
        for (List<CheckStat> list : loaded.values()) {
            list.sort(Comparator.comparingInt(CheckStat::amount).reversed());
        }
        checks.clear();
        checks.putAll(loaded);
    }

    private static UUID parseUuid(String raw) {
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // reads (main thread, cache only)
    // ------------------------------------------------------------------

    public PlayerFlags summary(UUID uuid) {
        return summaries.get(uuid);
    }

    public int alertCount(UUID uuid) {
        PlayerFlags flags = summaries.get(uuid);
        return flags == null ? 0 : flags.amount();
    }

    public List<CheckStat> checks(UUID uuid) {
        return checks.getOrDefault(uuid, List.of());
    }

    /** Everyone on record, worst first. Includes offline players. */
    public List<PlayerFlags> topAlerts(int limit) {
        List<PlayerFlags> all = new ArrayList<>(summaries.values());
        all.sort(Comparator.comparingInt(PlayerFlags::amount).reversed());
        return all.size() <= limit ? all : new ArrayList<>(all.subList(0, limit));
    }
}
