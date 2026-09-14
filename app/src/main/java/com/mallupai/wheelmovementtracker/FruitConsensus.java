package com.mallupai.wheelmovementtracker;

import java.util.Arrays;
import java.util.Locale;

/** Combines movement-destination and named-sector probabilities into one fruit signal. */
public final class FruitConsensus {
    public static final int MANGO = 0;
    public static final int WATERMELON = 1;
    public static final int SEVENTY_SEVEN = 2;

    public final double[] movementScores;
    public final double[] namedScores;
    public final double[] combinedScores;
    public final int winner;
    public final boolean analysesAgree;

    private FruitConsensus(double[] movementScores, double[] namedScores,
                           double[] combinedScores, int winner,
                           boolean analysesAgree) {
        this.movementScores = movementScores;
        this.namedScores = namedScores;
        this.combinedScores = combinedScores;
        this.winner = winner;
        this.analysesAgree = analysesAgree;
    }

    public static FruitConsensus from(PatternAnalyzer.Prediction movement,
                                      PatternAnalyzer.Prediction named,
                                      int startPosition) {
        int start = startPosition >= 1 && startPosition <= 9 ? startPosition : 1;
        double[] movementFruit = new double[3];
        double[] namedFruit = new double[3];

        if (movement != null && movement.allScores != null) {
            for (int jump = 0; jump < Math.min(9, movement.allScores.length); jump++) {
                int destination = ((start - 1 + jump) % 9) + 1;
                movementFruit[fruitIndex(destination)] += movement.allScores[jump];
            }
        }
        if (named != null && named.allScores != null) {
            for (int index = 0; index < Math.min(9, named.allScores.length); index++) {
                namedFruit[fruitIndex(index + 1)] += named.allScores[index];
            }
        }
        normalize(movementFruit);
        normalize(namedFruit);

        double[] combined = new double[3];
        for (int i = 0; i < combined.length; i++) {
            combined[i] = (movementFruit[i] + namedFruit[i]) / 2.0;
        }
        normalize(combined);
        int movementWinner = top(movementFruit);
        int namedWinner = top(namedFruit);
        return new FruitConsensus(movementFruit, namedFruit, combined,
                top(combined), movementWinner == namedWinner);
    }

    public String winnerName() {
        return fruitName(winner);
    }

    public double winnerScore() {
        return combinedScores[winner];
    }

    public String finalDisplay() {
        return String.format(Locale.US, "%s %.1f%%", winnerName(), winnerScore() * 100.0);
    }

    public String totalsDisplay() {
        return String.format(Locale.US,
                "Mango %.1f%%  •  Watermelon %.1f%%  •  77 %.1f%%",
                combinedScores[MANGO] * 100.0,
                combinedScores[WATERMELON] * 100.0,
                combinedScores[SEVENTY_SEVEN] * 100.0);
    }

    public String sourceDisplay() {
        return String.format(Locale.US,
                "Movement: M %.1f / W %.1f / 77 %.1f%%\n"
                        + "Names: M %.1f / W %.1f / 77 %.1f%%",
                movementScores[MANGO] * 100.0,
                movementScores[WATERMELON] * 100.0,
                movementScores[SEVENTY_SEVEN] * 100.0,
                namedScores[MANGO] * 100.0,
                namedScores[WATERMELON] * 100.0,
                namedScores[SEVENTY_SEVEN] * 100.0);
    }

    public String agreementDisplay() {
        return analysesAgree ? "BOTH ANALYSES AGREE" : "MIXED SIGNAL • HIGHEST TOTAL SHOWN";
    }

    public String winningFruitSourcesDisplay() {
        return String.format(Locale.US, "Movement %.1f%%  •  Names %.1f%%",
                movementScores[winner] * 100.0, namedScores[winner] * 100.0);
    }

    private static int fruitIndex(int position) {
        String fruit = WheelLabels.fruitForPosition(position);
        if ("M".equals(fruit)) return MANGO;
        if ("W".equals(fruit)) return WATERMELON;
        return SEVENTY_SEVEN;
    }

    private static String fruitName(int index) {
        if (index == MANGO) return "MANGO";
        if (index == WATERMELON) return "WATERMELON";
        return "77";
    }

    private static int top(double[] values) {
        int result = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[result]) result = i;
        }
        return result;
    }

    private static void normalize(double[] values) {
        double sum = Arrays.stream(values).sum();
        if (sum <= 0) {
            Arrays.fill(values, 1.0 / values.length);
            return;
        }
        for (int i = 0; i < values.length; i++) values[i] /= sum;
    }
}
