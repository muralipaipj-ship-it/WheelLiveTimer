package com.mallupai.wheelmovementtracker;

import java.nio.ByteBuffer;

/** Lightweight ring correlation and 77-sector calibration for the portrait 9-sector wheel. */
public final class MovementAnalyzer {
    public interface Listener {
        void onLive(String text);

        void onMovement(Result result);
    }

    public static final class Result {
        public final int movement;
        public final int startPosition;
        public final int winnerPosition;
        public final float confidence;

        Result(int movement, int startPosition, int winnerPosition, float confidence) {
            this.movement = movement;
            this.startPosition = startPosition;
            this.winnerPosition = winnerPosition;
            this.confidence = confidence;
        }
    }

    private static final int SAMPLES = 72;
    private static final double DEGREES_PER_SECTOR = 40.0;
    private final Listener listener;
    private float[] previous;
    private boolean moving;
    private long lastMotionNs;
    private double accumulatedDegrees;
    private float confidence;
    private int lastDetectedPosition;
    private int stablePosition;
    private int spinStartPosition;
    private int geometryCx;
    private int geometryCy;
    private int geometryRadius;
    private int frameCount;
    private boolean geometryReady;

    public MovementAnalyzer(Listener listener) {
        this.listener = listener;
    }

    public void accept(ByteBuffer bytes, int width, int height, int pixelStride,
                       int rowStride, long nowNs) {
        frameCount++;
        if (!geometryReady || (!moving && frameCount % 20 == 0)) {
            calibrateGeometry(bytes, width, height, pixelStride, rowStride);
        }
        int detectedPosition = detectWinnerPosition(
                bytes, width, height, pixelStride, rowStride);
        if (detectedPosition > 0) lastDetectedPosition = detectedPosition;

        float[] current = signature(bytes, width, height, pixelStride, rowStride);
        if (current == null) return;
        if (previous == null) {
            previous = current;
            stablePosition = lastDetectedPosition;
            return;
        }

        Shift shift = findShift(previous, current);
        previous = current;
        confidence = shift.confidence;
        boolean frameMoved = Math.abs(shift.samples) >= 1;
        if (frameMoved) {
            if (!moving) {
                moving = true;
                accumulatedDegrees = 0;
                spinStartPosition = stablePosition > 0 ? stablePosition : lastDetectedPosition;
            }
            accumulatedDegrees += -shift.samples * (360.0 / SAMPLES);
            lastMotionNs = nowNs;
            listener.onLive("moving "
                    + Math.round(accumulatedDegrees / DEGREES_PER_SECTOR) + " sectors");
        } else if (moving && nowNs - lastMotionNs > 280_000_000L) {
            moving = false;
            int movement = mod9((int) Math.round(accumulatedDegrees / DEGREES_PER_SECTOR));
            int winnerPosition = lastDetectedPosition > 0
                    ? lastDetectedPosition : stablePosition;
            int startPosition = spinStartPosition > 0 ? spinStartPosition : 0;
            if (Math.abs(accumulatedDegrees) >= 18) {
                listener.onMovement(new Result(movement, startPosition,
                        winnerPosition, confidence));
            }
            if (winnerPosition > 0) stablePosition = winnerPosition;
            listener.onLive("stable");
        } else if (!moving && lastDetectedPosition > 0) {
            stablePosition = lastDetectedPosition;
        }
    }

    private float[] signature(ByteBuffer bytes, int width, int height,
                              int pixelStride, int rowStride) {
        ensureDefaultGeometry(width, height);
        int cx = geometryCx;
        int cy = geometryCy;
        int radius = geometryRadius;
        if (cx - radius < 0 || cy - radius < 0
                || cx + radius >= width || cy + radius >= height) return null;
        int sampleRadius = Math.round(radius * .72f);
        float[] output = new float[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            double angle = -Math.PI / 2 + i * 2 * Math.PI / SAMPLES;
            float total = 0;
            for (int r = -2; r <= 2; r++) {
                int x = Math.max(0, Math.min(width - 1,
                        Math.round(cx + (sampleRadius + r) * (float) Math.cos(angle))));
                int y = Math.max(0, Math.min(height - 1,
                        Math.round(cy + (sampleRadius + r) * (float) Math.sin(angle))));
                int position = y * rowStride + x * pixelStride;
                int red = bytes.get(position) & 255;
                int green = bytes.get(position + 1) & 255;
                int blue = bytes.get(position + 2) & 255;
                total += (red * .30f + green * .59f + blue * .11f) / 255f;
            }
            output[i] = total / 5f;
        }
        return output;
    }

    /** Finds the green 77 wedge and converts its angle into the named sector at the pointer. */
    private int detectWinnerPosition(ByteBuffer bytes, int width, int height,
                                     int pixelStride, int rowStride) {
        ensureDefaultGeometry(width, height);
        int cx = geometryCx;
        int cy = geometryCy;
        int radius = geometryRadius;
        if (cx - radius < 0 || cy - radius < 0
                || cx + radius >= width || cy + radius >= height) return 0;

        int bestSector = -1;
        float bestScore = 0;
        for (int sector = 0; sector < 9; sector++) {
            double centerAngle = -Math.PI / 2 + sector * 2 * Math.PI / 9.0;
            float score = 0;
            for (double offset : new double[]{-.22, -.11, 0, .11, .22}) {
                double angle = centerAngle + offset;
                for (double fraction : new double[]{.40, .50, .60, .70}) {
                    int x = Math.max(0, Math.min(width - 1,
                            (int) Math.round(cx + radius * fraction * Math.cos(angle))));
                    int y = Math.max(0, Math.min(height - 1,
                            (int) Math.round(cy + radius * fraction * Math.sin(angle))));
                    int position = y * rowStride + x * pixelStride;
                    int red = bytes.get(position) & 255;
                    int green = bytes.get(position + 1) & 255;
                    int blue = bytes.get(position + 2) & 255;
                    if (green > 85 && green > red * 1.12f && green > blue * 1.05f) {
                        score += 1;
                    }
                    if (green > 145 && red > 85 && blue < 125) score += .35f;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestSector = sector;
            }
        }

        // The green 77 sector must have enough green evidence; otherwise retain the
        // previous stable label during motion, dimming, or the result popup.
        if (bestSector < 0 || bestScore < 4.0f) return 0;
        return mod9(-bestSector) + 1;
    }

    /**
     * Adapts the wheel center/radius to small layout and device differences by locating
     * the distinctive green 77 wedge near the expected portrait-game region.
     */
    private void calibrateGeometry(ByteBuffer bytes, int width, int height,
                                   int pixelStride, int rowStride) {
        int bestCx = Math.round(width * .50f);
        int bestCy = Math.round(height * .46f);
        int bestRadius = Math.round(width * .35f);
        float bestScore = -1;
        float[] xFractions = {.48f, .50f, .52f};
        float[] yFractions = {.43f, .45f, .46f, .47f, .49f};
        float[] radiusFractions = {.31f, .33f, .35f, .37f, .39f};
        for (float xFraction : xFractions) {
            for (float yFraction : yFractions) {
                for (float radiusFraction : radiusFractions) {
                    int cx = Math.round(width * xFraction);
                    int cy = Math.round(height * yFraction);
                    int radius = Math.round(width * radiusFraction);
                    if (cx - radius < 0 || cy - radius < 0
                            || cx + radius >= width || cy + radius >= height) continue;
                    float score = strongestGreenSectorScore(bytes, cx, cy, radius,
                            width, height, pixelStride, rowStride);
                    if (score > bestScore) {
                        bestScore = score;
                        bestCx = cx;
                        bestCy = cy;
                        bestRadius = radius;
                    }
                }
            }
        }

        if (!geometryReady || bestScore >= 4.5f) {
            if (geometryReady) {
                geometryCx = Math.round(geometryCx * .72f + bestCx * .28f);
                geometryCy = Math.round(geometryCy * .72f + bestCy * .28f);
                geometryRadius = Math.round(geometryRadius * .72f + bestRadius * .28f);
            } else {
                geometryCx = bestCx;
                geometryCy = bestCy;
                geometryRadius = bestRadius;
            }
            geometryReady = true;
        }
    }

    private float strongestGreenSectorScore(ByteBuffer bytes, int cx, int cy, int radius,
                                            int width, int height, int pixelStride,
                                            int rowStride) {
        float best = 0;
        for (int sector = 0; sector < 9; sector++) {
            double centerAngle = -Math.PI / 2 + sector * 2 * Math.PI / 9.0;
            float score = 0;
            for (double offset : new double[]{-.18, -.09, 0, .09, .18}) {
                double angle = centerAngle + offset;
                for (double fraction : new double[]{.42, .54, .66}) {
                    int x = Math.max(0, Math.min(width - 1,
                            (int) Math.round(cx + radius * fraction * Math.cos(angle))));
                    int y = Math.max(0, Math.min(height - 1,
                            (int) Math.round(cy + radius * fraction * Math.sin(angle))));
                    int position = y * rowStride + x * pixelStride;
                    int red = bytes.get(position) & 255;
                    int green = bytes.get(position + 1) & 255;
                    int blue = bytes.get(position + 2) & 255;
                    if (green > 85 && green > red * 1.12f && green > blue * 1.05f) {
                        score += 1;
                    }
                }
            }
            best = Math.max(best, score);
        }
        return best;
    }

    private void ensureDefaultGeometry(int width, int height) {
        if (geometryReady) return;
        geometryCx = Math.round(width * .50f);
        geometryCy = Math.round(height * .46f);
        geometryRadius = Math.round(width * .35f);
        geometryReady = true;
    }

    private Shift findShift(float[] oldSignature, float[] newSignature) {
        int best = 0;
        float bestError = Float.MAX_VALUE;
        float secondError = Float.MAX_VALUE;
        for (int shift = -14; shift <= 14; shift++) {
            float error = 0;
            for (int i = 0; i < SAMPLES; i++) {
                int j = (i + shift + SAMPLES) % SAMPLES;
                float difference = oldSignature[j] - newSignature[i];
                error += difference * difference;
            }
            if (error < bestError) {
                secondError = bestError;
                bestError = error;
                best = shift;
            } else if (error < secondError) {
                secondError = error;
            }
        }
        float confidence = Math.max(0,
                Math.min(1, (secondError - bestError) / (secondError + .0001f)));
        return new Shift(best, confidence);
    }

    private int mod9(int value) {
        int result = value % 9;
        return result < 0 ? result + 9 : result;
    }

    private static final class Shift {
        final int samples;
        final float confidence;

        Shift(int samples, float confidence) {
            this.samples = samples;
            this.confidence = confidence;
        }
    }
}
