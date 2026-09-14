package com.mallupai.wheelmovementtracker;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Persistent local history for numeric movement and named wheel-sector analysis. */
public final class MovementStore {
    private static final String PREF = "wheel_sequence_history_v4";
    private static final String KEY = "rounds";
    private static final String SETTINGS_PREF = "wheel_tracker_settings_v1";
    private static final String KEY_ROLLBACK = "rollback_rounds";
    private static final String KEY_MIN_FORECAST = "minimum_forecast_percent";
    private static final String KEY_MIN_SAMPLES = "minimum_samples";
    private static final int MAX_ROUNDS = 2000;
    public static final int[] ROLLBACK_OPTIONS = {10, 20, 30, 50, 100, 200, 500, 0};

    private final Context context;

    public MovementStore(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Saves one completed result. Spin time is deliberately not stored. */
    public synchronized Outcome add(int roundNumber, int movement, int startPosition,
                                    int winnerPosition, String source) {
        movement = mod9(movement);
        startPosition = normalizePosition(startPosition);
        winnerPosition = normalizePosition(winnerPosition);

        JSONArray data = read();
        List<Integer> beforeMovements = movements(data);
        List<Integer> beforePositions = winnerPositions(data);
        int rollback = getRollback();
        PatternAnalyzer.Prediction previousMovement = movementPrediction(data, startPosition);
        PatternAnalyzer.Prediction previousName = PatternAnalyzer.predict(beforePositions, rollback);
        FruitConsensus previousFruit = FruitConsensus.from(
                previousMovement, previousName, startPosition);

        boolean eligible = beforeMovements.size() >= getMinimumSamples()
                && previousFruit.winnerScore() * 100.0 >= getMinimumForecastPercent();
        boolean movementTop1Win = eligible && previousMovement.topMovement() == movement;
        boolean movementTop3Win = eligible && previousMovement.contains(movement);
        int winnerIndex = winnerPosition > 0 ? winnerPosition - 1 : -1;
        boolean nameTop1Win = eligible && winnerIndex >= 0
                && previousName.topMovement() == winnerIndex;
        boolean nameTop3Win = eligible && winnerIndex >= 0
                && previousName.contains(winnerIndex);
        int actualFruit = fruitIndexForPosition(winnerPosition);
        boolean fruitTop1Win = eligible && previousFruit.winner == actualFruit;
        double fruitProfitUnits = eligible
                ? (fruitTop1Win ? (actualFruit == FruitConsensus.SEVENTY_SEVEN ? 7.0 : 1.0) : -1.0)
                : 0.0;

        int sequence = nextSequence(data);
        String startName = WheelLabels.nameForPosition(startPosition);
        String winnerName = WheelLabels.nameForPosition(winnerPosition);
        try {
            JSONObject row = new JSONObject();
            row.put("sequence", sequence);
            row.put("round", roundNumber);
            row.put("startPosition", startPosition);
            row.put("winnerPosition", winnerPosition);
            row.put("startName", startName);
            row.put("winnerName", winnerName);
            row.put("winnerFruit", WheelLabels.fruitForPosition(winnerPosition));
            row.put("movement", movement);
            row.put("rollback", rollback);
            row.put("movementPrediction", previousMovement.topMovement());
            row.put("namePrediction", previousName.topMovement());
            row.put("movementTop1", movementTop1Win);
            row.put("movementTop3", movementTop3Win);
            row.put("nameTop1", nameTop1Win);
            row.put("nameTop3", nameTop3Win);
            row.put("fruitPrediction", previousFruit.winnerName());
            row.put("fruitPredictionPercent", previousFruit.winnerScore() * 100.0);
            row.put("fruitTop1", fruitTop1Win);
            row.put("fruitProfitUnits", fruitProfitUnits);
            row.put("minimumForecastPercent", getMinimumForecastPercent());
            row.put("minimumSamples", getMinimumSamples());
            row.put("eligible", eligible);
            row.put("source", source == null ? "automatic" : source);
            data.put(row);
            while (data.length() > MAX_ROUNDS) data.remove(0);
            save(data);
        } catch (Exception ignored) {
            // A malformed persisted row must not interrupt screen tracking.
        }

        PatternAnalyzer.Prediction nextMovement = movementPrediction(data, winnerPosition);
        PatternAnalyzer.Prediction nextName = PatternAnalyzer.predict(winnerPositions(data), rollback);
        return new Outcome(sequence, roundNumber, startPosition, winnerPosition,
                startName, winnerName, movement, movementTop1Win, movementTop3Win,
                nameTop1Win, nameTop3Win, fruitTop1Win, fruitProfitUnits, eligible,
                previousMovement, nextMovement, previousName, nextName);
    }

    public synchronized List<Integer> movements() {
        return movements(read());
    }

    public synchronized PatternAnalyzer.Prediction movementPrediction() {
        JSONArray data = read();
        return movementPrediction(data, lastWinnerPosition(data));
    }

    public synchronized PatternAnalyzer.Prediction namedPrediction() {
        return PatternAnalyzer.predict(winnerPositions(read()), getRollback());
    }

    public synchronized FruitConsensus fruitConsensus() {
        JSONArray data = read();
        int startPosition = lastWinnerPosition(data);
        PatternAnalyzer.Prediction movement = movementPrediction(data, startPosition);
        PatternAnalyzer.Prediction named =
                PatternAnalyzer.predict(winnerPositions(data), getRollback());
        return FruitConsensus.from(movement, named, startPosition);
    }

    public synchronized int getRollback() {
        return context.getSharedPreferences(SETTINGS_PREF, Context.MODE_PRIVATE)
                .getInt(KEY_ROLLBACK, 50);
    }

    public synchronized void setRollback(int rollback) {
        int selected = rollback == 0 ? 0 : Math.max(1, Math.min(MAX_ROUNDS, rollback));
        context.getSharedPreferences(SETTINGS_PREF, Context.MODE_PRIVATE)
                .edit().putInt(KEY_ROLLBACK, selected).apply();
    }

    public synchronized int getMinimumForecastPercent() {
        return context.getSharedPreferences(SETTINGS_PREF, Context.MODE_PRIVATE)
                .getInt(KEY_MIN_FORECAST, 40);
    }

    public synchronized void setMinimumForecastPercent(int percent) {
        context.getSharedPreferences(SETTINGS_PREF, Context.MODE_PRIVATE).edit()
                .putInt(KEY_MIN_FORECAST, Math.max(0, Math.min(100, percent))).apply();
    }

    public synchronized int getMinimumSamples() {
        return context.getSharedPreferences(SETTINGS_PREF, Context.MODE_PRIVATE)
                .getInt(KEY_MIN_SAMPLES, 5);
    }

    public synchronized void setMinimumSamples(int samples) {
        context.getSharedPreferences(SETTINGS_PREF, Context.MODE_PRIVATE).edit()
                .putInt(KEY_MIN_SAMPLES, Math.max(1, Math.min(MAX_ROUNDS, samples))).apply();
    }

    public synchronized String rollbackLabel() {
        int rollback = getRollback();
        return rollback <= 0 ? "All saved" : "Last " + rollback;
    }

    public synchronized int size() {
        return read().length();
    }

    public synchronized int lastWinnerPosition() {
        return lastWinnerPosition(read());
    }

    public synchronized int lastRoundNumber() {
        JSONArray data = read();
        for (int i = data.length() - 1; i >= 0; i--) {
            JSONObject row = data.optJSONObject(i);
            if (row != null && row.optInt("round", -1) > 0) return row.optInt("round");
        }
        return -1;
    }

    private int lastWinnerPosition(JSONArray data) {
        for (int i = data.length() - 1; i >= 0; i--) {
            JSONObject row = data.optJSONObject(i);
            if (row == null) continue;
            int position = normalizePosition(row.optInt("winnerPosition", 0));
            if (position > 0) return position;
        }
        return 0;
    }

    private PatternAnalyzer.Prediction movementPrediction(JSONArray data,
                                                          int startPosition) {
        int rollback = getRollback();
        PatternAnalyzer.Prediction general = PatternAnalyzer.predict(movements(data), rollback);
        if (startPosition < 1 || startPosition > 9) return general;

        List<Integer> contextualHistory = movementsFromStart(data, startPosition);
        PatternAnalyzer.Prediction contextual =
                PatternAnalyzer.predict(contextualHistory, rollback);
        double contextWeight = contextualHistory.size() < 4
                ? 0 : Math.min(.48, .16 + contextualHistory.size() / 70.0);
        return PatternAnalyzer.blend(general, contextual, contextWeight,
                WheelLabels.nameForPosition(startPosition) + "-start");
    }

    public synchronized String scoreLine() {
        JSONArray data = read();
        int movementWins = 0;
        int movementLosses = 0;
        int nameWins = 0;
        int nameLosses = 0;
        int nameTop3 = 0;
        int fruitWins = 0;
        int fruitLosses = 0;
        double fruitProfit = 0;
        for (int i = 0; i < data.length(); i++) {
            JSONObject row = data.optJSONObject(i);
            if (row == null || !row.optBoolean("eligible", false)) continue;
            if (row.optBoolean("movementTop1", false)) movementWins++;
            else movementLosses++;
            if (row.optBoolean("nameTop1", false)) nameWins++;
            else nameLosses++;
            if (row.optBoolean("nameTop3", false)) nameTop3++;
            if (row.has("fruitPrediction")) {
                if (row.optBoolean("fruitTop1", false)) fruitWins++;
                else fruitLosses++;
                fruitProfit += row.optDouble("fruitProfitUnits", 0);
            }
        }
        return String.format(Locale.US,
                "%s • Move W/L %d/%d\nName W/L %d/%d • T3 %d\nFruit W/L %d/%d • Profit %s%%",
                rollbackLabel(), movementWins, movementLosses,
                nameWins, nameLosses, nameTop3, fruitWins, fruitLosses,
                fruitWins + fruitLosses == 0 ? "--"
                        : String.format(Locale.US, "%+.1f",
                        fruitProfit * 100.0 / (fruitWins + fruitLosses)));
    }

    public synchronized String recent(int count) {
        JSONArray data = read();
        StringBuilder out = new StringBuilder();
        int first = Math.max(0, data.length() - Math.max(1, count));
        for (int i = first; i < data.length(); i++) {
            JSONObject row = data.optJSONObject(i);
            if (row == null) continue;
            if (out.length() > 0) out.append("\n");
            out.append(formatRow(row));
        }
        return out.length() == 0 ? "No rounds recorded" : out.toString();
    }

    public synchronized String report() {
        return report(50);
    }

    public synchronized String report(int recentCount) {
        JSONArray data = read();
        int rollback = getRollback();
        int nextStart = lastWinnerPosition(data);
        PatternAnalyzer.Prediction nextMovement = movementPrediction(data, nextStart);
        PatternAnalyzer.Prediction nextName =
                PatternAnalyzer.predict(winnerPositions(data), rollback);
        FruitConsensus fruitConsensus =
                FruitConsensus.from(nextMovement, nextName, nextStart);

        int[] movementCounts = new int[9];
        int[] nameCounts = new int[9];
        int eligible = 0;
        int movementWins = 0;
        int movementTop3 = 0;
        int nameWins = 0;
        int nameTop3 = 0;
        int fruitEligible = 0;
        int fruitWins = 0;
        double fruitProfitUnits = 0;
        for (int i = 0; i < data.length(); i++) {
            JSONObject row = data.optJSONObject(i);
            if (row == null) continue;
            movementCounts[mod9(row.optInt("movement", 0))]++;
            int winner = normalizePosition(row.optInt("winnerPosition", 0));
            if (winner > 0) nameCounts[winner - 1]++;
            if (row.optBoolean("eligible", false)) {
                eligible++;
                if (row.optBoolean("movementTop1", false)) movementWins++;
                if (row.optBoolean("movementTop3", false)) movementTop3++;
                if (row.optBoolean("nameTop1", false)) nameWins++;
                if (row.optBoolean("nameTop3", false)) nameTop3++;
                if (row.has("fruitPrediction")) {
                    fruitEligible++;
                    if (row.optBoolean("fruitTop1", false)) fruitWins++;
                    fruitProfitUnits += row.optDouble("fruitProfitUnits", 0);
                }
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("WHEEL ADAPTIVE-SEQUENCE REPORT\n")
                .append("Saved rounds: ").append(data.length()).append('\n')
                .append("Rollback scan: ").append(rollbackLabel()).append('\n')
                .append("Minimum forecast: ").append(getMinimumForecastPercent()).append("%\n")
                .append("Minimum samples: ").append(getMinimumSamples()).append('\n')
                .append("Next jump starts from: ")
                .append(WheelLabels.nameForPosition(nextStart)).append('\n')
                .append("Movement W/L: ").append(movementWins).append(" / ")
                .append(Math.max(0, eligible - movementWins)).append('\n')
                .append("Name W/L: ").append(nameWins).append(" / ")
                .append(Math.max(0, eligible - nameWins)).append('\n')
                .append("Movement top-1: ").append(rate(movementWins, eligible))
                .append("  top-3: ").append(rate(movementTop3, eligible)).append('\n')
                .append("Name top-1: ").append(rate(nameWins, eligible))
                .append("  top-3: ").append(rate(nameTop3, eligible)).append('\n')
                .append("Fruit forecast W/L: ").append(fruitWins).append(" / ")
                .append(Math.max(0, fruitEligible - fruitWins)).append('\n')
                .append("Fruit forecast hit rate: ").append(rate(fruitWins, fruitEligible)).append('\n')
                .append("Flat-stake profit: ").append(signed(fruitProfitUnits)).append(" units\n")
                .append("Flat-stake profit %: ")
                .append(fruitEligible == 0 ? "not enough data"
                        : String.format(Locale.US, "%+.1f%%",
                        fruitProfitUnits * 100.0 / fruitEligible))
                .append("\n(x2 Mango/Watermelon, x8 77; one unit per eligible forecast)\n\n")
                .append("NEXT MOVEMENT TOP 3: ").append(nextMovement.display()).append('\n')
                .append("Confidence separation: ").append(nextMovement.confidenceDisplay()).append('\n')
                .append(nextMovement.repeatedDisplay()).append('\n')
                .append("All movement probabilities (+clockwise/-equivalent)\n")
                .append(nextMovement.allDisplay()).append('\n')
                .append("Adaptive weights: ").append(nextMovement.adaptiveSummary).append('\n')
                .append("Movement evidence: ").append(nextMovement.evidence).append("\n\n")
                .append("NEXT NAMED SECTOR TOP 3: ")
                .append(WheelLabels.predictionDisplay(nextName)).append('\n')
                .append(WheelLabels.repeatedDisplay(nextName)).append('\n')
                .append("All named-sector probabilities\n")
                .append(WheelLabels.allProbabilitiesDisplay(nextName)).append('\n')
                .append("Adaptive weights: ").append(nextName.adaptiveSummary).append('\n')
                .append("Name evidence: ").append(nextName.evidence).append("\n\n")
                .append("FINAL FRUIT CONSENSUS: ").append(fruitConsensus.finalDisplay()).append('\n')
                .append(fruitConsensus.totalsDisplay()).append('\n')
                .append(fruitConsensus.sourceDisplay()).append('\n')
                .append(fruitConsensus.agreementDisplay()).append("\n\n")
                .append("MOVEMENT COUNTS\n");
        for (int i = 0; i < movementCounts.length; i++) {
            out.append(PatternAnalyzer.format(i)).append('=').append(movementCounts[i])
                    .append(i == movementCounts.length - 1 ? '\n' : "   ");
        }
        out.append("\nNAMED SECTOR COUNTS\n");
        for (int i = 0; i < nameCounts.length; i++) {
            out.append(WheelLabels.nameForPosition(i + 1)).append('=').append(nameCounts[i])
                    .append(i == nameCounts.length - 1 ? '\n' : "   ");
        }
        out.append("\nNAME-SPECIFIC MOVEMENT SCAN\n")
                .append(nameSpecificMovementReport(data, rollback)).append("\n\n")
                .append("RECENT ROUND RECORDS (LAST ").append(Math.max(1, recentCount))
                .append(")\n").append(recent(recentCount)).append("\n\n")
                .append("Clockwise name order: 77 → w1 → m1 → w2 → m2 → w3 → m3 → w4 → m4\n")
                .append("Movement is modulo 9: 0=same, 1/-8, 2/-7, 3/-6, 4/-5, ")
                .append("5/-4, 6/-3, 7/-2, 8/-1.\n")
                .append("W/L counts describe recorded predictions, not guaranteed outcomes.");
        return out.toString();
    }

    public synchronized String exportJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("format", "wheel-movement-tracker");
            root.put("dataVersion", 1);
            root.put("rollback", getRollback());
            root.put("minimumForecastPercent", getMinimumForecastPercent());
            root.put("minimumSamples", getMinimumSamples());
            root.put("clockwiseOrder",
                    "77,w1,m1,w2,m2,w3,m3,w4,m4");
            root.put("rounds", read());
            return root.toString(2);
        } catch (Exception error) {
            return "{\"format\":\"wheel-movement-tracker\",\"rounds\":[]}";
        }
    }

    /** Imports tracker JSON. Merge skips matching rounds; replace rebuilds sequence numbers. */
    public synchronized ImportResult importJson(String raw, boolean merge) throws Exception {
        if (raw == null || raw.trim().isEmpty()) throw new Exception("The selected file is empty");

        JSONArray importedRows;
        int importedRollback = getRollback();
        int importedMinimumForecast = getMinimumForecastPercent();
        int importedMinimumSamples = getMinimumSamples();
        String trimmed = raw.trim();
        if (trimmed.startsWith("[")) {
            importedRows = new JSONArray(trimmed);
        } else {
            JSONObject root = new JSONObject(trimmed);
            String format = root.optString("format", "wheel-movement-tracker");
            if (!"wheel-movement-tracker".equals(format)) {
                throw new Exception("This is not a Wheel Movement Tracker data file");
            }
            importedRows = root.optJSONArray("rounds");
            if (importedRows == null) throw new Exception("No round data was found");
            importedRollback = root.optInt("rollback", importedRollback);
            importedMinimumForecast = root.optInt(
                    "minimumForecastPercent", importedMinimumForecast);
            importedMinimumSamples = root.optInt("minimumSamples", importedMinimumSamples);
        }

        JSONArray destination = merge ? read() : new JSONArray();
        Set<String> known = new HashSet<>();
        for (int i = 0; i < destination.length(); i++) {
            JSONObject row = destination.optJSONObject(i);
            if (row != null) known.add(importKey(row));
        }

        int imported = 0;
        int skipped = 0;
        int sequence = nextSequence(destination);
        for (int i = 0; i < importedRows.length(); i++) {
            JSONObject source = importedRows.optJSONObject(i);
            if (source == null) {
                skipped++;
                continue;
            }
            JSONObject row = sanitizeImportedRow(source);
            if (row == null) {
                skipped++;
                continue;
            }
            String key = importKey(row);
            if (merge && known.contains(key)) {
                skipped++;
                continue;
            }
            row.put("sequence", sequence++);
            row.put("source", "imported");
            destination.put(row);
            known.add(key);
            imported++;
        }
        if (!merge && imported == 0) {
            throw new Exception("No valid round records were found");
        }
        while (destination.length() > MAX_ROUNDS) destination.remove(0);
        save(destination);
        setRollback(importedRollback);
        setMinimumForecastPercent(importedMinimumForecast);
        setMinimumSamples(importedMinimumSamples);
        return new ImportResult(imported, skipped, destination.length(), merge);
    }

    private JSONObject sanitizeImportedRow(JSONObject source) {
        try {
            int movement = mod9(source.optInt("movement", 0));
            int startPosition = normalizePosition(source.optInt("startPosition", 0));
            int winnerPosition = normalizePosition(source.optInt("winnerPosition", 0));
            if (startPosition == 0 || winnerPosition == 0) return null;

            JSONObject row = new JSONObject(source.toString());
            row.put("round", source.optInt("round", -1));
            row.put("movement", movement);
            row.put("startPosition", startPosition);
            row.put("winnerPosition", winnerPosition);
            row.put("startName", WheelLabels.nameForPosition(startPosition));
            row.put("winnerName", WheelLabels.nameForPosition(winnerPosition));
            row.put("winnerFruit", WheelLabels.fruitForPosition(winnerPosition));
            return row;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String importKey(JSONObject row) {
        return row.optInt("round", -1) + "|"
                + row.optInt("startPosition", 0) + "|"
                + row.optInt("winnerPosition", 0) + "|"
                + mod9(row.optInt("movement", 0));
    }

    public synchronized void clear() {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private List<Integer> movements(JSONArray data) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject row = data.optJSONObject(i);
            if (row == null) continue;
            int value = row.optInt("movement", -1);
            if (value >= 0 && value <= 8) result.add(value);
        }
        return result;
    }

    /** Name sequence is stored as zero-based sector positions for the same analyzer. */
    private List<Integer> winnerPositions(JSONArray data) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject row = data.optJSONObject(i);
            if (row == null) continue;
            int position = normalizePosition(row.optInt("winnerPosition", 0));
            if (position > 0) result.add(position - 1);
        }
        return result;
    }

    /** Movement history conditioned on the named sector where the jump started. */
    private List<Integer> movementsFromStart(JSONArray data, int startPosition) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject row = data.optJSONObject(i);
            if (row == null) continue;
            if (normalizePosition(row.optInt("startPosition", 0)) != startPosition) continue;
            int value = row.optInt("movement", -1);
            if (value >= 0 && value <= 8) result.add(value);
        }
        return result;
    }

    private String nameSpecificMovementReport(JSONArray data, int rollback) {
        StringBuilder out = new StringBuilder();
        for (int position = 1; position <= 9; position++) {
            List<Integer> history = movementsFromStart(data, position);
            out.append(WheelLabels.nameForPosition(position))
                    .append(" (n=").append(history.size()).append("): ");
            if (history.isEmpty()) {
                out.append("learning");
            } else {
                out.append(PatternAnalyzer.predict(history, rollback).display());
            }
            if (position < 9) out.append('\n');
        }
        return out.toString();
    }

    private int nextSequence(JSONArray data) {
        int highest = 0;
        for (int i = 0; i < data.length(); i++) {
            JSONObject row = data.optJSONObject(i);
            if (row != null) highest = Math.max(highest, row.optInt("sequence", 0));
        }
        return highest + 1;
    }

    private String formatRow(JSONObject row) {
        int sequence = row.optInt("sequence", 0);
        int round = row.optInt("round", -1);
        int movement = mod9(row.optInt("movement", 0));
        int startPosition = normalizePosition(row.optInt("startPosition", 0));
        int winnerPosition = normalizePosition(row.optInt("winnerPosition", 0));
        String startName = WheelLabels.nameForPosition(startPosition);
        String winnerName = WheelLabels.nameForPosition(winnerPosition);
        String movementStatus = row.optBoolean("eligible", false)
                ? (row.optBoolean("movementTop1", false) ? "MW" : "ML") : "M-";
        String nameStatus = row.optBoolean("eligible", false)
                ? (row.optBoolean("nameTop1", false) ? "NW" : "NL") : "N-";
        return String.format(Locale.US, "#%d R%s %s→%s %s [%s/%s]",
                sequence, round < 0 ? "?" : String.valueOf(round), startName,
                winnerName, PatternAnalyzer.format(movement), movementStatus, nameStatus);
    }

    private JSONArray read() {
        try {
            String raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getString(KEY, "[]");
            JSONArray data = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < data.length(); i++) {
                JSONObject row = data.optJSONObject(i);
                if (row == null) continue;
                int start = normalizePosition(row.optInt("startPosition", 0));
                int winner = normalizePosition(row.optInt("winnerPosition", 0));
                if (start > 0) row.put("startName", WheelLabels.nameForPosition(start));
                if (winner > 0) {
                    row.put("winnerName", WheelLabels.nameForPosition(winner));
                    row.put("winnerFruit", WheelLabels.fruitForPosition(winner));
                }
            }
            return data;
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private void save(JSONArray data) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY, data.toString()).apply();
    }

    private static int normalizePosition(int position) {
        if (position < 1 || position > 9) return 0;
        return position;
    }

    private static int mod9(int value) {
        int result = value % 9;
        return result < 0 ? result + 9 : result;
    }

    private static int fruitIndexForPosition(int position) {
        String fruit = WheelLabels.fruitForPosition(position);
        if ("M".equals(fruit)) return FruitConsensus.MANGO;
        if ("W".equals(fruit)) return FruitConsensus.WATERMELON;
        return FruitConsensus.SEVENTY_SEVEN;
    }

    private static String signed(double value) {
        return String.format(Locale.US, "%+.1f", value);
    }

    private static String rate(int hits, int total) {
        return total == 0 ? "not enough data"
                : String.format(Locale.US, "%.1f%%", hits * 100.0 / total);
    }

    public static final class ImportResult {
        public final int imported;
        public final int skipped;
        public final int total;
        public final boolean merged;

        ImportResult(int imported, int skipped, int total, boolean merged) {
            this.imported = imported;
            this.skipped = skipped;
            this.total = total;
            this.merged = merged;
        }

        public String display() {
            return String.format(Locale.US,
                    "%s complete: %d imported, %d skipped, %d total rounds",
                    merged ? "Merge" : "Import", imported, skipped, total);
        }
    }

    public static final class Outcome {
        public final int sequence;
        public final int round;
        public final int startPosition;
        public final int winnerPosition;
        public final String startName;
        public final String winnerName;
        public final int actual;
        public final boolean movementTop1Win;
        public final boolean movementTop3Win;
        public final boolean nameTop1Win;
        public final boolean nameTop3Win;
        public final boolean fruitTop1Win;
        public final double fruitProfitUnits;
        public final boolean eligible;
        public final PatternAnalyzer.Prediction previousMovement;
        public final PatternAnalyzer.Prediction nextMovement;
        public final PatternAnalyzer.Prediction previousName;
        public final PatternAnalyzer.Prediction nextName;

        Outcome(int sequence, int round, int startPosition, int winnerPosition,
                String startName, String winnerName, int actual,
                boolean movementTop1Win, boolean movementTop3Win,
                boolean nameTop1Win, boolean nameTop3Win,
                boolean fruitTop1Win, double fruitProfitUnits, boolean eligible,
                PatternAnalyzer.Prediction previousMovement,
                PatternAnalyzer.Prediction nextMovement,
                PatternAnalyzer.Prediction previousName,
                PatternAnalyzer.Prediction nextName) {
            this.sequence = sequence;
            this.round = round;
            this.startPosition = startPosition;
            this.winnerPosition = winnerPosition;
            this.startName = startName;
            this.winnerName = winnerName;
            this.actual = actual;
            this.movementTop1Win = movementTop1Win;
            this.movementTop3Win = movementTop3Win;
            this.nameTop1Win = nameTop1Win;
            this.nameTop3Win = nameTop3Win;
            this.fruitTop1Win = fruitTop1Win;
            this.fruitProfitUnits = fruitProfitUnits;
            this.eligible = eligible;
            this.previousMovement = previousMovement;
            this.nextMovement = nextMovement;
            this.previousName = previousName;
            this.nextName = nextName;
        }

        public String status() {
            if (!eligible) return "LEARNING";
            return movementTop1Win ? "MOVE WIN" : "MOVE LOSS";
        }
    }
}
