package com.havocsus.hook;

import com.havocsus.HavocSusPlugin;
import com.havocsus.alerts.AlertStore;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads alert counts and per-check breakdowns from SUS's own database.
 *
 * SUS has no API, but it does have plain SQL underneath: one table with a row
 * per player (amount / anti_cheat / last_check / last_violation_level) and one
 * with a row per (player, anti-cheat, check). UUIDs are stored dashed, via
 * UUID.toString().
 *
 * Nothing about the table NAMES is assumed. An earlier version hardcoded them
 * and probed with "SELECT * FROM flags LIMIT 1", which passes as long as some
 * table by that name exists - so a wrong guess about the columns sailed through
 * the probe and then made every real query throw, which in game looked like a
 * checks page that was simply empty. Tables are now identified by the columns
 * they actually contain, and resolution retries instead of giving up at startup.
 */
public final class FlagStatsHook {

    public record PlayerFlags(UUID uuid, String name, int amount, String antiCheat,
                              String lastCheck, double lastViolation, long lastFlaggedAt) {
    }

    public record CheckStat(String antiCheat, String checkName, int amount,
                            double violationLevel, long lastFlaggedAt) {
    }

    /** Names SUS is known to use, tried when the database won't list its tables. */
    private static final List<String> FALLBACK_TABLES = List.of(
            "flags", "flag_history", "sus_flags", "sus_flag_history");

    private static final int HISTORY_FALLBACK_LIMIT = 5000;

    private final HavocSusPlugin plugin;
    private final AlertStore live;

    private final Map<UUID, PlayerFlags> summaries = new ConcurrentHashMap<>();
    private final Map<UUID, List<CheckStat>> checks = new ConcurrentHashMap<>();

    private volatile String jdbcUrl;
    private volatile String user;
    private volatile String password;

    /** Row-per-player table. Null if SUS doesn't have one we recognise. */
    private volatile String summaryTable;
    /** Row-per-check table. */
    private volatile String historyTable;

    private volatile String lastError = "not resolved yet";
    private volatile String dbDescription = "unknown";

    public FlagStatsHook(HavocSusPlugin plugin, AlertStore live) {
        this.plugin = plugin;
        this.live = live;
    }

    public boolean isAvailable() {
        return jdbcUrl != null && (summaryTable != null || historyTable != null);
    }

    // ------------------------------------------------------------------
    // connection
    // ------------------------------------------------------------------

    private boolean resolveConnection() {
        if (jdbcUrl != null) {
            return true;
        }
        if (!plugin.getConfig().getBoolean("alerts.enabled", true)) {
            lastError = "disabled in config";
            return false;
        }
        Plugin sus = plugin.getServer().getPluginManager().getPlugin("Sus");
        if (sus == null) {
            // Not a fault. HavocSus captures alerts itself; the SUS database is
            // only ever a source of history from before it was running.
            lastError = "SUS not installed - using live capture only";
            dbDescription = "none (SUS not installed)";
            return false;
        }

        File dataFolder = sus.getDataFolder();
        File configFile = new File(dataFolder, "config.yml");
        if (!configFile.isFile()) {
            lastError = "SUS config not found at " + configFile.getPath();
            return false;
        }

        YamlConfiguration susConfig = YamlConfiguration.loadConfiguration(configFile);
        String type = String.valueOf(susConfig.getString("DATABASE.TYPE", "sqlite"))
                .toLowerCase(Locale.ROOT);

        if (type.startsWith("mysql") || type.startsWith("maria")) {
            String host = susConfig.getString("DATABASE.MYSQL.HOST", "localhost");
            int port = susConfig.getInt("DATABASE.MYSQL.PORT", 3306);
            String database = susConfig.getString("DATABASE.MYSQL.DATABASE", "sus");
            boolean ssl = susConfig.getBoolean("DATABASE.MYSQL.USE-SSL", false);
            user = susConfig.getString("DATABASE.MYSQL.USER", "root");
            password = susConfig.getString("DATABASE.MYSQL.PASSWORD", "");
            jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=" + ssl + "&characterEncoding=utf8";
            dbDescription = "mysql " + host + ":" + port + "/" + database;
        } else {
            String fileName = susConfig.getString("DATABASE.SQLITE.FILE", "flags.db");
            File db = new File(dataFolder, fileName);
            if (!db.isFile()) {
                // Not an error - SUS creates this lazily. We just try again later.
                lastError = "waiting for " + db.getPath() + " to exist";
                return false;
            }
            jdbcUrl = "jdbc:sqlite:" + db.getAbsolutePath();
            user = null;
            password = null;
            dbDescription = "sqlite " + db.getPath();
        }
        return true;
    }

    private Connection connect() throws Exception {
        if (jdbcUrl == null) {
            throw new IllegalStateException("no database configured");
        }
        ensureDriver();
        return user == null
                ? DriverManager.getConnection(jdbcUrl)
                : DriverManager.getConnection(jdbcUrl, user, password);
    }

    /**
     * Nudges the JDBC driver into registering itself.
     *
     * DriverManager discovers drivers via the calling classloader, and the
     * driver here arrives through Paper's library loader, so an explicit load
     * is a cheap guard against "No suitable driver" on some setups.
     */
    private void ensureDriver() {
        try {
            Class.forName(jdbcUrl.startsWith("jdbc:sqlite")
                    ? "org.sqlite.JDBC"
                    : "com.mysql.cj.jdbc.Driver");
        } catch (Throwable ignored) {
            // If it's genuinely missing, connect() reports it properly.
        }
    }

    // ------------------------------------------------------------------
    // schema discovery
    // ------------------------------------------------------------------

    /** Identifies tables by the columns they contain, not by their names. */
    private void discoverTables(Connection connection) {
        String foundSummary = null;
        String foundHistory = null;

        for (String table : listTables(connection)) {
            Set<String> columns = columnsOf(connection, table);
            if (columns.isEmpty() || !columns.contains("player_uuid")) {
                continue;
            }
            // Order matters: the history table also has `amount`, so test for
            // the per-check columns first.
            if (foundHistory == null
                    && columns.contains("check_name") && columns.contains("violation_level")) {
                foundHistory = table;
            } else if (foundSummary == null
                    && columns.contains("amount") && columns.contains("last_check")) {
                foundSummary = table;
            }
        }

        summaryTable = foundSummary;
        historyTable = foundHistory;

        if (isAvailable()) {
            lastError = "ok";
            plugin.getLogger().info("Alert stats hooked into SUS (" + dbDescription
                    + ", summary=" + summaryTable + ", history=" + historyTable + ").");
        } else {
            lastError = "no recognisable flag tables in " + dbDescription;
        }
    }

    private List<String> listTables(Connection connection) {
        Set<String> names = new LinkedHashSet<>();
        for (String query : List.of(
                "SELECT name FROM sqlite_master WHERE type='table'",
                "SHOW TABLES")) {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(query)) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
                if (!names.isEmpty()) {
                    return new ArrayList<>(names);
                }
            } catch (Throwable ignored) {
                // wrong dialect - try the next one
            }
        }
        return FALLBACK_TABLES;
    }

    private Set<String> columnsOf(Connection connection, String table) {
        Set<String> columns = new LinkedHashSet<>();
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT * FROM " + table + " WHERE 1=0");
             ResultSet rs = statement.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                columns.add(meta.getColumnLabel(i).toLowerCase(Locale.ROOT));
            }
        } catch (Throwable ignored) {
            // not a table we can read; skip it
        }
        return columns;
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
        if (!resolveConnection()) {
            return;
        }
        List<UUID> online = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            online.add(player.getUniqueId());
        }

        try (Connection connection = connect()) {
            // Retry discovery every cycle until it sticks. SUS creates its
            // tables lazily, so failing once at startup must not be permanent.
            if (!isAvailable()) {
                discoverTables(connection);
                if (!isAvailable()) {
                    return;
                }
            }
            loadSummaries(connection);
            if (online.isEmpty()) {
                checks.clear();
            } else {
                loadChecks(connection, online);
            }
            lastError = "ok";
        } catch (Throwable t) {
            lastError = t.toString();
            plugin.getLogger().warning("Could not read SUS alert data: " + t);
        }
    }

    private void loadSummaries(Connection connection) throws Exception {
        Map<UUID, PlayerFlags> loaded = new HashMap<>();

        if (summaryTable != null) {
            String sql = "SELECT player_uuid, player_name, amount, anti_cheat, last_check, "
                    + "last_violation_level, last_flagged_at FROM " + summaryTable;
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
        } else if (historyTable != null) {
            // No per-player table: fold the history down ourselves so alert
            // counts still work.
            String sql = "SELECT player_uuid, player_name, anti_cheat, check_name, amount, "
                    + "last_flagged_at FROM " + historyTable
                    + " ORDER BY last_flagged_at DESC LIMIT " + HISTORY_FALLBACK_LIMIT;
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    UUID uuid = parseUuid(rs.getString("player_uuid"));
                    if (uuid == null) {
                        continue;
                    }
                    PlayerFlags existing = loaded.get(uuid);
                    int amount = rs.getInt("amount") + (existing == null ? 0 : existing.amount());
                    loaded.put(uuid, new PlayerFlags(
                            uuid,
                            rs.getString("player_name"),
                            amount,
                            rs.getString("anti_cheat"),
                            rs.getString("check_name"),
                            0.0D,
                            Math.max(rs.getLong("last_flagged_at"),
                                    existing == null ? 0L : existing.lastFlaggedAt())));
                }
            }
        }

        summaries.clear();
        summaries.putAll(loaded);
    }

    private void loadChecks(Connection connection, List<UUID> online) throws Exception {
        if (historyTable == null) {
            return;
        }
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
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            // Some stores drop the dashes; rebuild them rather than lose the row.
            String trimmed = raw.trim().replace("-", "");
            if (trimmed.length() != 32) {
                return null;
            }
            try {
                return UUID.fromString(trimmed.replaceFirst(
                        "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------
    // reads (main thread, cache only)
    // ------------------------------------------------------------------

    /**
     * Live capture wins over the database.
     *
     * If we've seen alerts for this player ourselves, that data is current and
     * definitely correct. The database is history from before the server came
     * up. They are not added together - SUS records the same alerts, so summing
     * would double-count everything.
     */
    public PlayerFlags summary(UUID uuid) {
        if (live.has(uuid)) {
            String name = live.name(uuid);
            if (name == null) {
                Player online = plugin.getServer().getPlayer(uuid);
                name = online != null ? online.getName() : "?";
            }
            return new PlayerFlags(uuid, name, live.total(uuid),
                    live.lastAntiCheat(uuid), live.lastCheck(uuid),
                    live.lastViolation(uuid), live.lastFlaggedAt(uuid));
        }
        return summaries.get(uuid);
    }

    public int alertCount(UUID uuid) {
        if (live.has(uuid)) {
            return live.total(uuid);
        }
        PlayerFlags flags = summaries.get(uuid);
        return flags == null ? 0 : flags.amount();
    }

    public List<CheckStat> checks(UUID uuid) {
        if (live.has(uuid)) {
            List<CheckStat> out = new ArrayList<>();
            for (AlertStore.CheckTally tally : live.checks(uuid)) {
                out.add(new CheckStat(tally.antiCheat(), tally.checkName(),
                        tally.amount(), tally.violationLevel(), tally.lastFlaggedAt()));
            }
            return out;
        }
        return checks.getOrDefault(uuid, List.of());
    }

    public List<PlayerFlags> topAlerts(int limit) {
        Map<UUID, PlayerFlags> merged = new HashMap<>(summaries);
        for (UUID uuid : live.players()) {
            PlayerFlags fromLive = summary(uuid);
            if (fromLive != null) {
                merged.put(uuid, fromLive);
            }
        }
        List<PlayerFlags> all = new ArrayList<>(merged.values());
        all.sort(Comparator.comparingInt(PlayerFlags::amount).reversed());
        return all.size() <= limit ? all : new ArrayList<>(all.subList(0, limit));
    }

    /** Human-readable state, for /hs diag. */
    public List<String> diagnostics() {
        List<String> lines = new ArrayList<>();
        boolean susPresent = plugin.getServer().getPluginManager().getPlugin("Sus") != null;
        lines.add("SUS plugin: " + (susPresent
                ? "found" : "not installed (live capture only - this is fine)"));
        lines.add("Database: " + dbDescription);
        lines.add("Connection: " + (jdbcUrl == null ? "not resolved" : "resolved"));
        lines.add("Summary table: " + (summaryTable == null ? "none" : summaryTable));
        lines.add("History table: " + (historyTable == null ? "none" : historyTable));
        lines.add("Cached players: " + summaries.size());
        lines.add("Cached check rows: " + checks.values().stream().mapToInt(List::size).sum());
        lines.add("Last result: " + lastError);
        lines.add("Live alerts captured: " + live.totalAlerts()
                + " across " + live.size() + " players");
        return lines;
    }
}
