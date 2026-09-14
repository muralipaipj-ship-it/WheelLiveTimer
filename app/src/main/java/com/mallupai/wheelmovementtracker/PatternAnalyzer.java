package com.mallupai.wheelmovementtracker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Adaptive ensemble analysis for canonical modulo-9 movement or sector sequences. */
public final class PatternAnalyzer {
    private static final int SYMBOLS = 9;
    private static final int MODEL_COUNT = 4;
    private static final int BACKTEST_ROUNDS = 36;
    private static final String[] MODEL_NAMES = {"Frequency", "Sequence", "Delta", "Cycle"};

    private PatternAnalyzer() {
    }

    public static final class Prediction {
        public final int[] movement;
        public final double[] score;
        public final double[] allScores;
        public final int samples;
        public final int totalSamples;
        public final int rollback;
        public final String evidence;
        public final String adaptiveSummary;
        public final String repeatedPattern;
        public final int repeatedCount;
        public final double confidence;

        Prediction(int[] movement, double[] score, double[] allScores, int samples,
                   int totalSamples, int rollback, String evidence, String adaptiveSummary,
                   String repeatedPattern, int repeatedCount, double confidence) {
            this.movement = movement;
            this.score = score;
            this.allScores = allScores;
            this.samples = samples;
            this.totalSamples = totalSamples;
            this.rollback = rollback;
            this.evidence = evidence;
            this.adaptiveSummary = adaptiveSummary;
            this.repeatedPattern = repeatedPattern;
            this.repeatedCount = repeatedCount;
            this.confidence = confidence;
        }

        public int topMovement() {
            return movement[0];
        }

        public boolean contains(int value) {
            for (int item : movement) if (item == value) return true;
            return false;
        }

        public String display() {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < movement.length; i++) {
                if (i > 0) out.append("   ");
                out.append(format(movement[i])).append(' ')
                        .append(percent(score[i]));
            }
            return out.toString();
        }

        public String allDisplay() {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < allScores.length; i++) {
                if (i > 0) out.append(i % 3 == 0 ? '\n' : "   ");
                out.append(format(i)).append(' ').append(percent(allScores[i]));
            }
            return out.toString();
        }

        public String confidenceDisplay() {
            return String.format(Locale.US, "%.0f%%", confidence * 100.0);
        }

        public String repeatedDisplay() {
            if (repeatedCount < 2 || repeatedPattern.isEmpty()) {
                return "Repeated pattern: none yet";
            }
            return "Repeated pattern: " + repeatedPattern + "  ×" + repeatedCount;
        }
    }

    public static Prediction predict(List<Integer> rawHistory) {
        return predict(rawHistory, 0);
    }

    /**
     * Runs four probability models and weights them using their recent back-test quality.
     * A rollback of zero means all saved data; otherwise only the newest N values are used.
     */
    public static Prediction predict(List<Integer> rawHistory, int rollback) {
        List<Integer> full = sanitize(rawHistory);
        List<Integer> history = trim(full, rollback);
        int n = history.size();

        double[][] models = modelDistributions(history);
        double[] weights = adaptiveWeights(history);
        double[] combined = new double[SYMBOLS];
        for (int model = 0; model < MODEL_COUNT; model++) {
            for (int value = 0; value < SYMBOLS; value++) {
                combined[value] += models[model][value] * weights[model];
            }
        }
        normalize(combined);

        int suffixOrder = strongestSuffixOrder(history);
        Repeat repeat = findMostRepeated(history);
        String evidence = "used " + n + " of " + full.size() + " rounds"
                + " • suffix order " + suffixOrder
                + " • modulo-9 +/− math";
        return buildPrediction(combined, n, full.size(), rollback, evidence,
                modelSummary(weights), repeat.display, repeat.count);
    }

    /** Blends a general sequence with a start-sector-specific movement sequence. */
    public static Prediction blend(Prediction general, Prediction context,
                                   double contextWeight, String contextLabel) {
        if (general == null) return context;
        if (context == null || context.samples < 4 || contextWeight <= 0) return general;
        double weight = Math.max(0, Math.min(.55, contextWeight));
        double[] combined = new double[SYMBOLS];
        for (int i = 0; i < SYMBOLS; i++) {
            combined[i] = general.allScores[i] * (1.0 - weight)
                    + context.allScores[i] * weight;
        }
        normalize(combined);
        String evidence = general.evidence + " • " + contextLabel
                + " context " + context.samples + " rounds";
        String adaptive = general.adaptiveSummary + " • Context "
                + String.format(Locale.US, "%.0f%%", weight * 100.0);
        return buildPrediction(combined, general.samples, general.totalSamples,
                general.rollback, evidence, adaptive, general.repeatedPattern,
                general.repeatedCount);
    }

    private static Prediction buildPrediction(double[] distribution, int samples,
                                              int totalSamples, int rollback,
                                              String evidence, String adaptive,
                                              String repeatedPattern, int repeatedCount) {
        int[] ranking = rank(distribution);
        int[] best = {ranking[0], ranking[1], ranking[2]};
        double[] bestScores = {
                distribution[best[0]], distribution[best[1]], distribution[best[2]]
        };
        return new Prediction(best, bestScores, Arrays.copyOf(distribution, SYMBOLS),
                samples, totalSamples, rollback, evidence, adaptive, repeatedPattern,
                repeatedCount, confidence(distribution));
    }

    private static double[][] modelDistributions(List<Integer> history) {
        return new double[][]{
                frequencyModel(history),
                sequenceModel(history),
                deltaModel(history),
                cycleModel(history)
        };
    }

    private static double[] model(int index, List<Integer> history) {
        switch (index) {
            case 0:
                return frequencyModel(history);
            case 1:
                return sequenceModel(history);
            case 2:
                return deltaModel(history);
            default:
                return cycleModel(history);
        }
    }

    /** Recency-weighted frequencies with Laplace smoothing. */
    private static double[] frequencyModel(List<Integer> history) {
        double[] points = prior(.65);
        int n = history.size();
        for (int i = 0; i < n; i++) {
            int age = n - 1 - i;
            double recency = Math.pow(.965, age);
            points[history.get(i)] += .55 + 1.45 * recency;
        }
        addWindow(points, history, 5, 2.7);
        addWindow(points, history, 12, 1.6);
        addWindow(points, history, 30, .9);
        return normalized(points);
    }

    /** Suffix/n-gram matching from order one through six. */
    private static double[] sequenceModel(List<Integer> history) {
        double[] points = prior(.50);
        int n = history.size();
        for (int order = 1; order <= Math.min(6, n - 1); order++) {
            for (int start = 0; start + order < n; start++) {
                boolean same = true;
                for (int j = 0; j < order; j++) {
                    if (!history.get(start + j).equals(history.get(n - order + j))) {
                        same = false;
                        break;
                    }
                }
                if (same) {
                    double recency = .55 + .45 * (start + order) / (double) n;
                    points[history.get(start + order)] +=
                            (1.25 + order * order * .72) * recency;
                }
            }
        }

        if (n >= 2) {
            int last = history.get(n - 1);
            int run = 1;
            while (run < Math.min(8, n) && history.get(n - 1 - run) == last) run++;
            for (int i = run; i < n; i++) {
                boolean sameRun = true;
                for (int j = 0; j < run; j++) {
                    if (history.get(i - run + j) != last) {
                        sameRun = false;
                        break;
                    }
                }
                if (sameRun) points[history.get(i)] += 1.2 + run * .8;
            }
        }
        return normalized(points);
    }

    /** First- and second-difference transition model on the modulo-9 ring. */
    private static double[] deltaModel(List<Integer> history) {
        double[] points = prior(.50);
        int n = history.size();
        if (n < 2) return normalized(points);

        int latestDelta = mod9(history.get(n - 1) - history.get(n - 2));
        for (int i = 1; i + 1 < n; i++) {
            int historicalDelta = mod9(history.get(i) - history.get(i - 1));
            if (historicalDelta == latestDelta) {
                double recency = .6 + .4 * i / (double) n;
                points[history.get(i + 1)] += 2.6 * recency;
            }
        }
        points[mod9(history.get(n - 1) + latestDelta)] += 1.7;

        if (n >= 3) {
            int previousDelta = mod9(history.get(n - 2) - history.get(n - 3));
            int latestAcceleration = mod9(latestDelta - previousDelta);
            for (int i = 2; i + 1 < n; i++) {
                int deltaA = mod9(history.get(i - 1) - history.get(i - 2));
                int deltaB = mod9(history.get(i) - history.get(i - 1));
                if (mod9(deltaB - deltaA) == latestAcceleration) {
                    points[history.get(i + 1)] += 1.5;
                }
            }
            points[mod9(history.get(n - 1) + latestDelta + latestAcceleration)] += 1.1;
        }
        return normalized(points);
    }

    /** Detects repeating cycles from two to twelve positions. */
    private static double[] cycleModel(List<Integer> history) {
        double[] points = prior(.50);
        int n = history.size();
        for (int period = 2; period <= Math.min(12, n - 1); period++) {
            int comparisons = 0;
            int matches = 0;
            int start = Math.max(period, n - 45);
            for (int i = start; i < n; i++) {
                comparisons++;
                if (history.get(i).equals(history.get(i - period))) matches++;
            }
            if (comparisons == 0) continue;
            double rate = matches / (double) comparisons;
            if (rate >= .24) {
                int nextIndex = n - period;
                if (nextIndex >= 0) {
                    points[history.get(nextIndex)] +=
                            1.0 + rate * rate * (5.0 + period * .12);
                }
            }
        }
        return normalized(points);
    }

    /** Learns which of the four models has performed best in the newest rounds. */
    private static double[] adaptiveWeights(List<Integer> history) {
        double[] base = {.31, .31, .23, .15};
        int n = history.size();
        if (n < 6) return base;

        double[] quality = new double[MODEL_COUNT];
        int first = Math.max(3, n - BACKTEST_ROUNDS);
        int tests = 0;
        for (int target = first; target < n; target++) {
            List<Integer> prefix = history.subList(Math.max(0, target - 240), target);
            int actual = history.get(target);
            double recency = .65 + .35 * (target - first + 1)
                    / (double) Math.max(1, n - first);
            for (int model = 0; model < MODEL_COUNT; model++) {
                double[] prediction = model(model, prefix);
                int[] ranking = rank(prediction);
                double result = prediction[actual] * 2.1;
                if (ranking[0] == actual) result += 1.0;
                if (ranking[0] == actual || ranking[1] == actual || ranking[2] == actual) {
                    result += .35;
                }
                quality[model] += result * recency;
            }
            tests++;
        }

        double sum = 0;
        for (int model = 0; model < MODEL_COUNT; model++) {
            double average = tests == 0 ? 0 : quality[model] / tests;
            quality[model] = base[model] * (.45 + average);
            sum += quality[model];
        }
        if (sum <= 0) return base;
        for (int model = 0; model < MODEL_COUNT; model++) quality[model] /= sum;
        return quality;
    }

    private static int strongestSuffixOrder(List<Integer> history) {
        int n = history.size();
        int strongest = 0;
        for (int order = 1; order <= Math.min(6, n - 1); order++) {
            for (int start = 0; start + order < n; start++) {
                boolean same = true;
                for (int j = 0; j < order; j++) {
                    if (!history.get(start + j).equals(history.get(n - order + j))) {
                        same = false;
                        break;
                    }
                }
                if (same) strongest = order;
            }
        }
        return strongest;
    }

    private static Repeat findMostRepeated(List<Integer> history) {
        Repeat best = new Repeat("", 0, 0);
        int largestLength = Math.min(6, history.size() / 2);
        for (int length = 2; length <= largestLength; length++) {
            Map<String, Integer> counts = new HashMap<>();
            for (int start = 0; start + length <= history.size(); start++) {
                String key = key(history, start, length);
                counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
            }
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                int count = entry.getValue();
                if (count > best.count || (count == best.count && length > best.length)) {
                    best = new Repeat(formatKey(entry.getKey()), count, length);
                }
            }
        }
        return best;
    }

    private static void addWindow(double[] points, List<Integer> history,
                                  int requested, double weight) {
        int size = Math.min(requested, history.size());
        if (size == 0) return;
        int start = history.size() - size;
        double unit = weight * SYMBOLS / size;
        for (int i = start; i < history.size(); i++) {
            double recency = .65 + .35 * (i - start + 1) / (double) size;
            points[history.get(i)] += unit * recency;
        }
    }

    private static String key(List<Integer> history, int start, int length) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < length; i++) {
            if (i > 0) out.append(',');
            out.append(history.get(start + i));
        }
        return out.toString();
    }

    private static String formatKey(String key) {
        if (key.isEmpty()) return "";
        String[] parts = key.split(",");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append(" → ");
            try {
                out.append(format(Integer.parseInt(parts[i])));
            } catch (NumberFormatException ignored) {
                out.append(parts[i]);
            }
        }
        return out.toString();
    }

    private static List<Integer> sanitize(List<Integer> input) {
        List<Integer> result = new ArrayList<>();
        if (input != null) {
            for (Integer value : input) {
                if (value != null) result.add(mod9(value));
            }
        }
        return result;
    }

    private static List<Integer> trim(List<Integer> history, int rollback) {
        if (rollback <= 0 || history.size() <= rollback) return new ArrayList<>(history);
        return new ArrayList<>(history.subList(history.size() - rollback, history.size()));
    }

    private static double[] prior(double amount) {
        double[] points = new double[SYMBOLS];
        Arrays.fill(points, amount);
        return points;
    }

    private static double[] normalized(double[] values) {
        double[] copy = Arrays.copyOf(values, values.length);
        normalize(copy);
        return copy;
    }

    private static void normalize(double[] values) {
        double sum = 0;
        for (double value : values) sum += Math.max(0, value);
        if (sum <= 0) {
            Arrays.fill(values, 1.0 / values.length);
            return;
        }
        for (int i = 0; i < values.length; i++) values[i] = Math.max(0, values[i]) / sum;
    }

    private static int[] rank(double[] distribution) {
        Integer[] order = new Integer[SYMBOLS];
        for (int i = 0; i < SYMBOLS; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(
                (Integer i) -> distribution[i]).reversed().thenComparingInt(i -> i));
        int[] ranking = new int[SYMBOLS];
        for (int i = 0; i < SYMBOLS; i++) ranking[i] = order[i];
        return ranking;
    }

    private static double confidence(double[] distribution) {
        double entropy = 0;
        for (double probability : distribution) {
            if (probability > 0) entropy -= probability * Math.log(probability);
        }
        return Math.max(0, Math.min(1, 1.0 - entropy / Math.log(SYMBOLS)));
    }

    private static String modelSummary(double[] weights) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < MODEL_COUNT; i++) {
            if (i > 0) out.append(" • ");
            out.append(MODEL_NAMES[i]).append(' ')
                    .append(String.format(Locale.US, "%.0f%%", weights[i] * 100.0));
        }
        return out.toString();
    }

    private static String percent(double value) {
        return String.format(Locale.US, "%.1f%%", value * 100.0);
    }

    public static String format(int movement) {
        int canonical = mod9(movement);
        if (canonical == 0) return "0";
        return "+" + canonical + "/-" + (SYMBOLS - canonical);
    }

    private static int mod9(int value) {
        int result = value % SYMBOLS;
        return result < 0 ? result + SYMBOLS : result;
    }

    private static final class Repeat {
        final String display;
        final int count;
        final int length;

        Repeat(String display, int count, int length) {
            this.display = display;
            this.count = count;
            this.length = length;
        }
    }
}
