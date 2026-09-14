package com.mallupai.wheelmovementtracker;

/** Names of the nine sectors in clockwise order beginning at the 77 sector. */
public final class WheelLabels {
    private static final String[] CLOCKWISE_FROM_77 = {
            "77", "w1", "m1", "w2", "m2", "w3", "m3", "w4", "m4"
    };

    private WheelLabels() {
    }

    public static String nameForPosition(int position) {
        if (position < 1 || position > 9) return "?";
        return CLOCKWISE_FROM_77[position - 1];
    }

    public static String fruitForPosition(int position) {
        String name = nameForPosition(position);
        if ("77".equals(name) || "?".equals(name)) return name;
        return name.substring(0, 1).toUpperCase();
    }

    public static String predictionDisplay(PatternAnalyzer.Prediction prediction) {
        if (prediction == null || prediction.movement == null) return "learning";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < prediction.movement.length; i++) {
            if (i > 0) out.append("   ");
            int position = prediction.movement[i] + 1;
            out.append(nameForPosition(position)).append(' ')
                    .append(String.format(java.util.Locale.US, "%.1f", prediction.score[i] * 100))
                    .append('%');
        }
        return out.toString();
    }

    public static String allProbabilitiesDisplay(PatternAnalyzer.Prediction prediction) {
        if (prediction == null || prediction.allScores == null) return "learning";
        StringBuilder out = new StringBuilder();
        for (int position = 1; position <= 9; position++) {
            if (position > 1) out.append((position - 1) % 3 == 0 ? '\n' : "   ");
            out.append(nameForPosition(position)).append(' ')
                    .append(String.format(java.util.Locale.US, "%.1f%%",
                            prediction.allScores[position - 1] * 100.0));
        }
        return out.toString();
    }

    public static String repeatedDisplay(PatternAnalyzer.Prediction prediction) {
        if (prediction == null || prediction.repeatedCount < 2
                || prediction.repeatedPattern == null
                || prediction.repeatedPattern.isEmpty()) {
            return "Repeated names: none yet";
        }
        String[] parts = prediction.repeatedPattern.split(" → ");
        StringBuilder out = new StringBuilder("Repeated names: ");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append(" → ");
            out.append(nameForPosition(parseCanonical(parts[i]) + 1));
        }
        return out.append("  ×").append(prediction.repeatedCount).toString();
    }

    private static int parseCanonical(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            int slash = value.indexOf('/');
            String positive = slash > 0 ? value.substring(1, slash) : value;
            return Integer.parseInt(positive);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public static String transition(String startName, String winnerName) {
        return (startName == null ? "?" : startName) + " → "
                + (winnerName == null ? "?" : winnerName);
    }
}
