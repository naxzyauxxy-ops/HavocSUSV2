package com.havocsus.alerts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Alerts HavocSus has seen for itself, recorded straight off the anti-cheats.
 *
 * This exists because reading SUS's database turned out to be an unreliable way
 * to get check data - it depends on their storage being present, populated and
 * shaped the way we expect. Listening to the anti-cheat events directly removes
 * every one of those assumptions: if an alert reaches chat, it reaches here.
 *
 * In memory only. The SUS database still supplies history from before the
 * server started; this supplies everything since.
 */
public final class AlertStore {

    public record CheckKey(String antiCheat, String checkName) {
    }

    public static final class CheckTally {
        private final String antiCheat;
        private final String checkName;
        private int amount;
        private double violationLevel;
        private long lastFlaggedAt;

        CheckTally(String antiCheat, String checkName) {
            this.antiCheat = antiCheat;
            this.checkName = checkName;
        }

        public String antiCheat() {
            return antiCheat;
        }

        public String checkName() {
            return checkName;
        }

        public int amount() {
            return amount;
        }

        public double violationLevel() {
            return violationLevel;
        }

        public long lastFlaggedAt() {
            return lastFlaggedAt;
        }
    }

    private static final class PlayerRecord {
        private volatile String name;
        private volatile String lastAntiCheat = "";
        private volatile String lastCheck = "";
        private volatile double lastViolation;
        private volatile long lastFlaggedAt;
        private final Map<CheckKey, CheckTally> tallies = new ConcurrentHashMap<>();

        private int total() {
            int sum = 0;
            for (CheckTally tally : tallies.values()) {
                sum += tally.amount();
            }
            return sum;
        }
    }

    private final Map<UUID, PlayerRecord> records = new ConcurrentHashMap<>();

    /** Called from anti-cheat events, which may fire off the main thread. */
    public void record(UUID uuid, String name, String antiCheat, String checkName, double violationLevel) {
        if (uuid == null) {
            return;
        }
        String ac = antiCheat == null || antiCheat.isBlank() ? "Anticheat" : antiCheat;
        String check = checkName == null || checkName.isBlank() ? "Unknown" : checkName;

        PlayerRecord record = records.computeIfAbsent(uuid, k -> new PlayerRecord());
        if (name != null && !name.isBlank()) {
            record.name = name;
        }
        record.lastAntiCheat = ac;
        record.lastCheck = check;
        record.lastViolation = violationLevel;
        record.lastFlaggedAt = System.currentTimeMillis();

        CheckTally tally = record.tallies.computeIfAbsent(new CheckKey(ac, check),
                k -> new CheckTally(ac, check));
        synchronized (tally) {
            tally.amount++;
            tally.violationLevel = Math.max(tally.violationLevel, violationLevel);
            tally.lastFlaggedAt = record.lastFlaggedAt;
        }
    }

    public boolean has(UUID uuid) {
        PlayerRecord record = records.get(uuid);
        return record != null && !record.tallies.isEmpty();
    }

    public int total(UUID uuid) {
        PlayerRecord record = records.get(uuid);
        return record == null ? 0 : record.total();
    }

    public String name(UUID uuid) {
        PlayerRecord record = records.get(uuid);
        return record == null ? null : record.name;
    }

    public String lastAntiCheat(UUID uuid) {
        PlayerRecord record = records.get(uuid);
        return record == null ? "" : record.lastAntiCheat;
    }

    public String lastCheck(UUID uuid) {
        PlayerRecord record = records.get(uuid);
        return record == null ? "" : record.lastCheck;
    }

    public double lastViolation(UUID uuid) {
        PlayerRecord record = records.get(uuid);
        return record == null ? 0.0D : record.lastViolation;
    }

    public long lastFlaggedAt(UUID uuid) {
        PlayerRecord record = records.get(uuid);
        return record == null ? 0L : record.lastFlaggedAt;
    }

    /** Highest-count checks first. */
    public List<CheckTally> checks(UUID uuid) {
        PlayerRecord record = records.get(uuid);
        if (record == null) {
            return List.of();
        }
        List<CheckTally> out = new ArrayList<>(record.tallies.values());
        out.sort(Comparator.comparingInt(CheckTally::amount).reversed());
        return out;
    }

    public List<UUID> players() {
        return new ArrayList<>(records.keySet());
    }

    public int size() {
        return records.size();
    }

    public int totalAlerts() {
        int sum = 0;
        for (PlayerRecord record : records.values()) {
            sum += record.total();
        }
        return sum;
    }
}
