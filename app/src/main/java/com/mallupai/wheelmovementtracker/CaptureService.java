package com.mallupai.wheelmovementtracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Foreground screen-capture service for the wheel sequence tracker. */
public class CaptureService extends Service {
    private static final String CHANNEL = "wheel_tracker_live";
    private static final String OVERLAY_PREF = "wheel_overlay_settings_v1";
    private static final String KEY_WIDTH_PERCENT = "width_percent";
    private static final String KEY_HEIGHT_PERCENT = "height_percent";
    private static final int NOTIFICATION_ID = 20;
    private static final long MIN_SAVE_GAP_NS = 1_300_000_000L;
    private static final long OCR_GAP_NS = 700_000_000L;
    private static final Pattern ROUND_PATTERN = Pattern.compile(
            "(?i)\\bround\\s*[:#-]?\\s*(\\d{1,7})");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean ocrInFlight = new AtomicBoolean(false);
    private final ExecutorService ocrExecutor = Executors.newSingleThreadExecutor();

    private WindowManager windowManager;
    private LinearLayout overlay;
    private LinearLayout overlayBody;
    private ScrollView overlayScroll;
    private WindowManager.LayoutParams overlayParams;
    private TextView dragHandle;
    private TextView globalMinimizeButton;
    private TextView miniSummaryView;
    private TextView resizeHandle;
    private TextView roundView;
    private TextView sequenceView;
    private TextView movementView;
    private TextView patternView;
    private TextView probabilityView;
    private TextView scoreView;
    private TextView liveView;
    private TextView fruitView;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private MovementStore store;
    private MovementAnalyzer analyzer;
    private TextRecognizer textRecognizer;

    private volatile int currentRound = -1;
    private volatile long currentRoundChangedNs;
    private int previousWinnerPosition;
    private int lastSavedRound;
    private int overlayWidthPercent;
    private int overlayHeightPercent;
    private long lastSavedNs;
    private long lastOcrNs;
    private boolean overlayAttached;
    private boolean overlayMinimized = true;
    private String lastWinnerSummary = "waiting";
    private int pendingOverlayX;
    private int pendingOverlayY;
    private int pendingOverlayWidth;
    private int pendingOverlayHeight;
    private boolean dragFramePending;
    private boolean resizeFramePending;

    private final Choreographer.FrameCallback dragFrameCallback = frameTimeNanos -> {
        dragFramePending = false;
        applyPendingOverlayPosition();
    };
    private final Choreographer.FrameCallback resizeFrameCallback = frameTimeNanos -> {
        resizeFramePending = false;
        applyPendingOverlaySize();
    };

    @Override
    public void onCreate() {
        super.onCreate();
        store = new MovementStore(this);
        previousWinnerPosition = store.lastWinnerPosition();
        lastSavedRound = store.lastRoundNumber();
        overlayWidthPercent = getSharedPreferences(OVERLAY_PREF, MODE_PRIVATE)
                .getInt(KEY_WIDTH_PERCENT, 56);
        overlayHeightPercent = getSharedPreferences(OVERLAY_PREF, MODE_PRIVATE)
                .getInt(KEY_HEIGHT_PERCENT, 20);
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        analyzer = new MovementAnalyzer(new MovementAnalyzer.Listener() {
            @Override
            public void onLive(String text) {
                ui.post(() -> updateLive(text));
            }

            @Override
            public void onMovement(MovementAnalyzer.Result result) {
                saveMovement(result);
            }
        });
        startNotification();
        showOverlay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (projection != null || intent == null) return START_NOT_STICKY;
        int code = intent.getIntExtra("code", 0);
        Intent data = getProjectionIntent(intent);
        if (data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        projection = manager.getMediaProjection(code, data);
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        }, ui);
        startReader();
        updateLive("screen capture running");
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private Intent getProjectionIntent(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra("data", Intent.class);
        }
        return intent.getParcelableExtra("data");
    }

    private void startNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Wheel live tracking", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Wheel sequence tracking running")
                .setContentText("Round, movement, pattern and W/L analysis are active")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startReader() {
        WindowManager displayWindow = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (displayWindow == null) {
            stopSelf();
            return;
        }
        Rect bounds = Build.VERSION.SDK_INT >= 30
                ? displayWindow.getCurrentWindowMetrics().getBounds()
                : new Rect(0, 0, getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        int width = bounds.width();
        int height = bounds.height();
        int dpi = getResources().getDisplayMetrics().densityDpi;

        captureThread = new HandlerThread("wheel-capture");
        captureThread.start();
        Handler captureHandler = new Handler(captureThread.getLooper());
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null) analyzeFrame(image);
            } finally {
                if (image != null) image.close();
            }
        }, captureHandler);
        virtualDisplay = projection.createVirtualDisplay(
                "WheelMovementTracker", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, captureHandler);
    }

    private void analyzeFrame(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer bytes = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int width = image.getWidth();
        int height = image.getHeight();
        long frameNs = System.nanoTime();

        analyzer.accept(bytes, width, height, pixelStride, rowStride, frameNs);
        if (frameNs - lastOcrNs >= OCR_GAP_NS
                && ocrInFlight.compareAndSet(false, true)) {
            lastOcrNs = frameNs;
            Bitmap bitmap = imageToBitmap(image, width, height, bytes, pixelStride, rowStride);
            if (bitmap != null) submitRoundOcr(bitmap, height);
            else ocrInFlight.set(false);
        }
    }

    private void submitRoundOcr(Bitmap bitmap, int screenHeight) {
        int cropHeight = Math.min(bitmap.getHeight(), Math.round(screenHeight * .72f));
        Bitmap crop = bitmap;
        if (cropHeight > 0 && cropHeight < bitmap.getHeight()) {
            crop = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), cropHeight);
            bitmap.recycle();
        }
        Bitmap finalCrop = crop;
        ocrExecutor.execute(() -> {
            InputImage input = InputImage.fromBitmap(finalCrop, 0);
            textRecognizer.process(input)
                    .addOnSuccessListener(ocrExecutor, result -> updateRound(parseRound(result.getText())))
                    .addOnFailureListener(ocrExecutor, error -> { /* OCR is optional for movement. */ })
                    .addOnCompleteListener(ocrExecutor, task -> {
                        finalCrop.recycle();
                        ocrInFlight.set(false);
                    });
        });
    }

    private Bitmap imageToBitmap(Image image, int width, int height, ByteBuffer bytes,
                                 int pixelStride, int rowStride) {
        try {
            int rowPadding = rowStride - pixelStride * width;
            int bitmapWidth = width + rowPadding / pixelStride;
            Bitmap full = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888);
            bytes.rewind();
            full.copyPixelsFromBuffer(bytes);
            if (bitmapWidth == width) return full;
            Bitmap cropped = Bitmap.createBitmap(full, 0, 0, width, height);
            full.recycle();
            return cropped;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private int parseRound(String text) {
        if (text == null) return -1;
        Matcher matcher = ROUND_PATTERN.matcher(text.replace('\n', ' '));
        if (!matcher.find()) return -1;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private void updateRound(int round) {
        if (round <= 0) return;
        if (round != currentRound) currentRoundChangedNs = System.nanoTime();
        currentRound = round;
        ui.post(() -> {
            if (roundView != null) roundView.setText("ROUND " + round);
            updateMiniSummary(null);
        });
    }

    private void saveMovement(MovementAnalyzer.Result result) {
        long now = System.nanoTime();
        if (now - lastSavedNs < MIN_SAVE_GAP_NS) return;
        if (currentRound > 0 && currentRound == lastSavedRound) return;
        if (currentRound > 0 && currentRoundChangedNs > 0
                && now - currentRoundChangedNs < 3_000_000_000L) return;

        int startPosition = result.startPosition > 0
                ? result.startPosition : previousWinnerPosition;
        if (startPosition < 1 || startPosition > 9) startPosition = 1;
        int winnerPosition = result.winnerPosition > 0
                ? result.winnerPosition
                : ((startPosition - 1 + mod9(result.movement)) % 9) + 1;
        int movement = startPosition > 0 && winnerPosition > 0
                ? mod9(winnerPosition - startPosition) : result.movement;
        lastSavedNs = now;
        MovementStore.Outcome outcome = store.add(
                currentRound, movement, startPosition, winnerPosition, "automatic");
        previousWinnerPosition = winnerPosition;
        if (currentRound > 0) lastSavedRound = currentRound;
        ui.post(() -> showOutcome(outcome));
    }

    private void showOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null || overlayAttached) return;

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(6), dp(5), dp(6), dp(5));
        overlay.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF2182032);
        background.setStroke(dp(1), 0xFF62E6FF);
        background.setCornerRadius(dp(10));
        overlay.setBackground(background);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        dragHandle = overlayLabel("⋮⋮ WHEEL", 10, 0xFF62E6FF);
        dragHandle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        dragHandle.setMinHeight(dp(30));
        dragHandle.setGravity(Gravity.CENTER_VERTICAL);
        dragHandle.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        header.addView(dragHandle);
        globalMinimizeButton = overlayLabel("+", 16, 0xFFF4F7FF);
        globalMinimizeButton.setGravity(Gravity.CENTER);
        globalMinimizeButton.setMinWidth(dp(32));
        globalMinimizeButton.setMinHeight(dp(30));
        globalMinimizeButton.setBackground(sectionButtonBackground());
        globalMinimizeButton.setOnClickListener(view -> toggleGlobalMinimize());
        header.addView(globalMinimizeButton);
        overlay.addView(header);

        miniSummaryView = overlayLabel("R? • waiting", 9, 0xFFFFD45C);
        miniSummaryView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        miniSummaryView.setGravity(Gravity.CENTER_VERTICAL);
        miniSummaryView.setMinHeight(dp(24));
        fixedLines(miniSummaryView, 1);
        miniSummaryView.setVisibility(View.VISIBLE);
        overlay.addView(miniSummaryView);

        overlayBody = new LinearLayout(this);
        overlayBody.setOrientation(LinearLayout.VERTICAL);
        overlayScroll = new ScrollView(this);
        overlayScroll.setFillViewport(false);
        overlayScroll.setVerticalScrollBarEnabled(true);
        overlayScroll.addView(overlayBody);
        overlayScroll.setVisibility(View.GONE);
        overlay.addView(overlayScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout resultContent = sectionContent();
        roundView = overlayLabel("ROUND ?", 11, 0xFFF4F7FF);
        roundView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        fixedLines(roundView, 1);
        resultContent.addView(roundView);
        sequenceView = overlayLabel("SEQ -- • waiting", 8, 0xFFAAB4CA);
        sequenceView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        fixedLines(sequenceView, 1);
        resultContent.addView(sequenceView);
        movementView = overlayLabel("Winner waiting\nLEARNING", 9, 0xFFFFD45C);
        movementView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        fixedLines(movementView, 2);
        resultContent.addView(movementView);
        addCollapsibleSection(overlayBody, "RESULT", resultContent, true);

        patternView = overlayLabel("MOVE  learning\nNAME  learning\nPATTERN  none yet", 9,
                0xFFF4F7FF);
        patternView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        fixedLines(patternView, 3);
        addCollapsibleSection(overlayBody, "NEXT • TOP 3", patternView, false);

        probabilityView = overlayLabel("0       learning\n+1/-8   learning\n+2/-7   learning",
                8, 0xFFAAB4CA);
        probabilityView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        fixedLines(probabilityView, 3);
        addCollapsibleSection(overlayBody, "ALL MOVEMENT %", probabilityView, false);

        LinearLayout scoreContent = sectionContent();
        scoreView = overlayLabel("Move W/L 0/0 • Name W/L 0/0", 9, 0xFF7CFF9B);
        scoreView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        fixedLines(scoreView, 3);
        scoreContent.addView(scoreView);
        liveView = overlayLabel("Live: waiting • R?", 8, 0xFFAAB4CA);
        liveView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        fixedLines(liveView, 1);
        scoreContent.addView(liveView);
        addCollapsibleSection(overlayBody, "SCORE / LIVE", scoreContent, false);

        fruitView = overlayLabel("FINAL • LEARNING\nM --  W --  77 --\nMove --  Name --", 10,
                0xFFFFD45C);
        fruitView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        fixedLines(fruitView, 3);
        addCollapsibleSection(overlayBody, "FINAL FRUIT", fruitView, false);

        resizeHandle = overlayLabel("↘  DRAG TO RESIZE", 8, 0xFF62E6FF);
        resizeHandle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        resizeHandle.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        resizeHandle.setMinHeight(dp(25));
        resizeHandle.setPadding(dp(5), 0, dp(5), 0);
        resizeHandle.setBackground(sectionHeaderBackground());
        resizeHandle.setVisibility(View.GONE);
        resizeHandle.setOnTouchListener(new View.OnTouchListener() {
            float startX;
            float startY;
            int originalWidth;
            int originalHeight;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    startX = event.getRawX();
                    startY = event.getRawY();
                    originalWidth = overlayParams.width;
                    originalHeight = overlayParams.height;
                    pendingOverlayWidth = originalWidth;
                    pendingOverlayHeight = originalHeight;
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    pendingOverlayWidth = originalWidth
                            + Math.round(event.getRawX() - startX);
                    pendingOverlayHeight = originalHeight
                            + Math.round(event.getRawY() - startY);
                    if (!resizeFramePending) {
                        resizeFramePending = true;
                        Choreographer.getInstance().postFrameCallback(resizeFrameCallback);
                    }
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP
                        || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    applyPendingOverlaySize();
                    saveOverlaySize();
                    return true;
                }
                return true;
            }
        });
        overlay.addView(resizeHandle);

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        overlayWidthPercent = Math.max(36, Math.min(72, overlayWidthPercent));
        overlayHeightPercent = Math.max(12, Math.min(22, overlayHeightPercent));
        int overlayWidth = Math.max(dp(126),
                Math.round(screenWidth * overlayWidthPercent / 100f));
        overlayWidth = Math.min(overlayWidth, screenWidth - dp(16));
        overlayParams = new WindowManager.LayoutParams(
                overlayWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.x = dp(6);
        overlayParams.y = dp(20);
        pendingOverlayWidth = overlayWidth;
        pendingOverlayHeight = Math.round(screenHeight * overlayHeightPercent / 100f);

        dragHandle.setOnTouchListener(new View.OnTouchListener() {
            float startX;
            float startY;
            int originalX;
            int originalY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    startX = event.getRawX();
                    startY = event.getRawY();
                    originalX = overlayParams.x;
                    originalY = overlayParams.y;
                    pendingOverlayX = originalX;
                    pendingOverlayY = originalY;
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    pendingOverlayX = originalX + Math.round(event.getRawX() - startX);
                    pendingOverlayY = originalY + Math.round(event.getRawY() - startY);
                    if (!dragFramePending) {
                        dragFramePending = true;
                        Choreographer.getInstance().postFrameCallback(dragFrameCallback);
                    }
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP
                        || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    applyPendingOverlayPosition();
                    return true;
                }
                return true;
            }
        });

        try {
            windowManager.addView(overlay, overlayParams);
            overlayAttached = true;
            updateDashboard();
        } catch (WindowManager.BadTokenException | IllegalStateException ignored) {
            overlayAttached = false;
        }
    }

    private void applyPendingOverlaySize() {
        if (!overlayAttached || overlay == null || overlayParams == null
                || windowManager == null || overlayMinimized) return;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int minWidth = Math.max(dp(126), Math.round(screenWidth * .36f));
        int maxWidth = Math.round(screenWidth * .72f);
        int minHeight = Math.round(screenHeight * .12f);
        int maxHeight = Math.round(screenHeight * .22f);
        overlayParams.width = Math.max(minWidth, Math.min(maxWidth, pendingOverlayWidth));
        overlayParams.height = Math.max(minHeight, Math.min(maxHeight, pendingOverlayHeight));
        try {
            windowManager.updateViewLayout(overlay, overlayParams);
        } catch (IllegalArgumentException ignored) {
        }
        keepOverlayOnScreen();
    }

    private void saveOverlaySize() {
        if (overlayParams == null) return;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        overlayWidthPercent = Math.round(overlayParams.width * 100f / screenWidth);
        overlayHeightPercent = Math.round(overlayParams.height * 100f / screenHeight);
        getSharedPreferences(OVERLAY_PREF, MODE_PRIVATE).edit()
                .putInt(KEY_WIDTH_PERCENT, overlayWidthPercent)
                .putInt(KEY_HEIGHT_PERCENT, overlayHeightPercent)
                .apply();
    }

    private LinearLayout sectionContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(5), dp(3), dp(4), dp(4));
        return content;
    }

    private void addCollapsibleSection(LinearLayout parent, String title,
                                       View content, boolean expanded) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(29));
        row.setPadding(dp(5), 0, 0, 0);
        row.setBackground(sectionHeaderBackground());

        TextView label = overlayLabel(title, 9, 0xFFAAB4CA);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        row.addView(label);

        TextView toggle = overlayLabel(expanded ? "−" : "+", 15, 0xFFF4F7FF);
        toggle.setGravity(Gravity.CENTER);
        toggle.setMinWidth(dp(34));
        toggle.setMinHeight(dp(29));
        row.addView(toggle);
        content.setVisibility(expanded ? View.VISIBLE : View.GONE);

        View.OnClickListener toggleAction = view -> {
            boolean show = content.getVisibility() != View.VISIBLE;
            content.setVisibility(show ? View.VISIBLE : View.GONE);
            toggle.setText(show ? "−" : "+");
            keepOverlayOnScreen();
        };
        toggle.setOnClickListener(toggleAction);
        label.setOnClickListener(toggleAction);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, dp(2), 0, 0);
        row.setLayoutParams(rowParams);
        parent.addView(row);
        parent.addView(content);
    }

    private GradientDrawable sectionHeaderBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0xD91D2539);
        drawable.setCornerRadius(dp(7));
        return drawable;
    }

    private GradientDrawable sectionButtonBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0xFF273149);
        drawable.setCornerRadius(dp(8));
        return drawable;
    }

    private void fixedLines(TextView view, int lines) {
        view.setMinLines(lines);
        view.setMaxLines(lines);
        view.setIncludeFontPadding(false);
    }

    private void toggleGlobalMinimize() {
        overlayMinimized = !overlayMinimized;
        overlayScroll.setVisibility(overlayMinimized ? View.GONE : View.VISIBLE);
        resizeHandle.setVisibility(overlayMinimized ? View.GONE : View.VISIBLE);
        miniSummaryView.setVisibility(View.VISIBLE);
        globalMinimizeButton.setText(overlayMinimized ? "+" : "−");
        if (overlayParams != null && windowManager != null && overlay != null) {
            if (overlayMinimized) {
                if (overlayParams.height > 0) pendingOverlayHeight = overlayParams.height;
                overlayParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
            } else {
                int screenHeight = getResources().getDisplayMetrics().heightPixels;
                int desiredHeight = pendingOverlayHeight > 0 ? pendingOverlayHeight
                        : Math.round(screenHeight * overlayHeightPercent / 100f);
                overlayParams.height = Math.max(Math.round(screenHeight * .12f),
                        Math.min(Math.round(screenHeight * .22f), desiredHeight));
                if (overlayParams.y + overlayParams.height > Math.round(screenHeight * .24f)) {
                    overlayParams.y = dp(20);
                }
            }
            try {
                windowManager.updateViewLayout(overlay, overlayParams);
            } catch (IllegalArgumentException ignored) {
            }
        }
        keepOverlayOnScreen();
    }

    private void keepOverlayOnScreen() {
        if (overlay == null) return;
        overlay.post(() -> {
            pendingOverlayX = overlayParams == null ? 0 : overlayParams.x;
            pendingOverlayY = overlayParams == null ? 0 : overlayParams.y;
            applyPendingOverlayPosition();
        });
    }

    private void showOutcome(MovementStore.Outcome outcome) {
        lastWinnerSummary = outcome.winnerName + " " + PatternAnalyzer.format(outcome.actual);
        if (roundView != null) {
            roundView.setText("ROUND " + (outcome.round > 0 ? outcome.round : "?"));
        }
        if (sequenceView != null) {
            sequenceView.setText("SEQ " + outcome.sequence
                    + "  •  " + outcome.startName + " → " + outcome.winnerName);
        }
        if (movementView != null) {
            movementView.setText("Winner " + outcome.winnerName + " (P"
                    + outcome.winnerPosition + ")  •  "
                    + PatternAnalyzer.format(outcome.actual) + "\n"
                    + outcomeStatus(outcome));
            movementView.setTextColor(outcome.eligible && !outcome.movementTop1Win
                    ? 0xFFFF8A8A : 0xFFFFD400);
        }
        updateDashboard();
    }

    private void updateDashboard() {
        PatternAnalyzer.Prediction movement = store.movementPrediction();
        PatternAnalyzer.Prediction named = store.namedPrediction();
        FruitConsensus fruit = FruitConsensus.from(
                movement, named, store.lastWinnerPosition());
        int savedSamples = store.size();
        int requiredSamples = store.getMinimumSamples();
        int requiredPercent = store.getMinimumForecastPercent();
        double currentPercent = fruit.winnerScore() * 100.0;
        boolean forecastReady = savedSamples >= requiredSamples
                && currentPercent >= requiredPercent;
        if (dragHandle != null) {
            dragHandle.setText("⋮ WHEEL • "
                    + (store.getRollback() <= 0 ? "ALL" : store.getRollback()));
        }
        if (patternView != null) {
            if (forecastReady) {
                patternView.setText("M " + compactMovementTop(movement)
                        + "\nN " + compactNameTop(named)
                        + "\nP " + compactRepeat(movement)
                        + "  C " + movement.confidenceDisplay());
            } else {
                patternView.setText(String.format(Locale.US,
                        "WAIT • FORECAST HIDDEN\nSAMPLES %d / %d\nSCORE %.0f%% / %d%%",
                        savedSamples, requiredSamples, currentPercent, requiredPercent));
            }
        }
        if (probabilityView != null) {
            probabilityView.setText(compactMovementGrid(movement));
        }
        if (scoreView != null) scoreView.setText(store.scoreLine());
        if (fruitView != null) {
            if (forecastReady) {
                fruitView.setText(String.format(Locale.US,
                        "FINAL %s %.0f%%\nM %.0f%%  W %.0f%%  77 %.0f%%\nMOVE %.0f%%  NAME %.0f%%  %s",
                        fruit.winnerName(), currentPercent,
                        fruit.combinedScores[FruitConsensus.MANGO] * 100.0,
                        fruit.combinedScores[FruitConsensus.WATERMELON] * 100.0,
                        fruit.combinedScores[FruitConsensus.SEVENTY_SEVEN] * 100.0,
                        fruit.movementScores[fruit.winner] * 100.0,
                        fruit.namedScores[fruit.winner] * 100.0,
                        fruit.analysesAgree ? "AGREE" : "MIXED"));
                fruitView.setTextColor(fruitColor(fruit.winner));
            } else {
                fruitView.setText(String.format(Locale.US,
                        "WAIT • NO FORECAST\nSAMPLES %d/%d\nSCORE %.0f%%/%d%%",
                        savedSamples, requiredSamples, currentPercent, requiredPercent));
                fruitView.setTextColor(0xFFAAB4CA);
            }
        }
        updateMiniSummary(fruit);
    }

    private String compactMovementTop(PatternAnalyzer.Prediction prediction) {
        if (prediction == null || prediction.movement == null) return "learning";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(3, prediction.movement.length); i++) {
            if (i > 0) out.append(' ');
            String move = PatternAnalyzer.format(prediction.movement[i]);
            if (move.startsWith("+")) move = move.substring(1);
            out.append(move).append(':')
                    .append(Math.round(prediction.score[i] * 100.0));
        }
        return out.toString();
    }

    private String compactNameTop(PatternAnalyzer.Prediction prediction) {
        if (prediction == null || prediction.movement == null) return "learning";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(3, prediction.movement.length); i++) {
            if (i > 0) out.append(' ');
            out.append(WheelLabels.nameForPosition(prediction.movement[i] + 1))
                    .append(':').append(Math.round(prediction.score[i] * 100.0));
        }
        return out.toString();
    }

    private String compactRepeat(PatternAnalyzer.Prediction prediction) {
        if (prediction == null || prediction.repeatedCount < 2
                || prediction.repeatedPattern == null
                || prediction.repeatedPattern.isEmpty()) return "none";
        return prediction.repeatedPattern + " ×" + prediction.repeatedCount;
    }

    private String compactMovementGrid(PatternAnalyzer.Prediction prediction) {
        if (prediction == null || prediction.allScores == null) return "learning";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            if (i > 0) out.append(i % 3 == 0 ? '\n' : "  ");
            String move = PatternAnalyzer.format(i);
            if (move.startsWith("+")) move = move.substring(1);
            out.append(String.format(Locale.US, "%-5s %2.0f%%",
                    move, prediction.allScores[i] * 100.0));
        }
        return out.toString();
    }

    private void updateMiniSummary(FruitConsensus currentFruit) {
        if (miniSummaryView == null) return;
        FruitConsensus fruit = currentFruit == null ? store.fruitConsensus() : currentFruit;
        boolean ready = store.size() >= store.getMinimumSamples()
                && fruit.winnerScore() * 100.0 >= store.getMinimumForecastPercent();
        if (ready) {
            miniSummaryView.setText(String.format(Locale.US, "R%s • %s • %s %.0f%%",
                    currentRound > 0 ? String.valueOf(currentRound) : "?",
                    lastWinnerSummary, fruit.winnerName(), fruit.winnerScore() * 100.0));
            miniSummaryView.setTextColor(fruitColor(fruit.winner));
        } else {
            miniSummaryView.setText(String.format(Locale.US, "R%s • %s • WAIT %.0f%%/%d%%",
                    currentRound > 0 ? String.valueOf(currentRound) : "?",
                    lastWinnerSummary, fruit.winnerScore() * 100.0,
                    store.getMinimumForecastPercent()));
            miniSummaryView.setTextColor(0xFFAAB4CA);
        }
    }

    private int fruitColor(int fruit) {
        if (fruit == FruitConsensus.MANGO) return 0xFFFFD45C;
        if (fruit == FruitConsensus.WATERMELON) return 0xFF7CFFAA;
        return 0xFFFF8A8A;
    }

    private String outcomeStatus(MovementStore.Outcome outcome) {
        if (!outcome.eligible) return "LEARNING";
        return "M " + (outcome.movementTop1Win ? "WIN" : "LOSS")
                + " • N " + (outcome.nameTop1Win ? "WIN" : "LOSS")
                + " • F " + (outcome.fruitTop1Win ? "WIN" : "LOSS")
                + String.format(Locale.US, " • %+.0fu", outcome.fruitProfitUnits);
    }

    private void applyPendingOverlayPosition() {
        if (!overlayAttached || overlay == null || overlayParams == null
                || windowManager == null) return;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int maxX = Math.max(0, screenWidth - overlay.getWidth());
        int safeBandBottom = Math.round(screenHeight * .25f);
        int maxY = Math.max(0, safeBandBottom - overlay.getHeight());
        overlayParams.x = Math.max(0, Math.min(maxX, pendingOverlayX));
        overlayParams.y = Math.max(0, Math.min(maxY, pendingOverlayY));
        try {
            windowManager.updateViewLayout(overlay, overlayParams);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private View overlayDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(0xFF2B3652);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        params.setMargins(0, dp(4), 0, dp(4));
        divider.setLayoutParams(params);
        return divider;
    }

    private void updateLive(String text) {
        if (liveView != null) {
            liveView.setText(String.format(Locale.US, "Live: %s  •  R%s",
                    text, currentRound > 0 ? String.valueOf(currentRound) : "?"));
        }
    }

    private TextView overlayLabel(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.START);
        view.setLineSpacing(0, 1.08f);
        view.setPadding(0, dp(2), 0, dp(2));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int mod9(int value) {
        int result = value % 9;
        return result < 0 ? result + 9 : result;
    }

    @Override
    public void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (dragFramePending) {
            Choreographer.getInstance().removeFrameCallback(dragFrameCallback);
            dragFramePending = false;
        }
        if (resizeFramePending) {
            Choreographer.getInstance().removeFrameCallback(resizeFrameCallback);
            resizeFramePending = false;
        }
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (projection != null) projection.stop();
        if (captureThread != null) captureThread.quitSafely();
        ocrExecutor.shutdownNow();
        if (textRecognizer != null) textRecognizer.close();
        if (overlayAttached && overlay != null && windowManager != null) {
            try {
                windowManager.removeView(overlay);
            } catch (IllegalArgumentException ignored) {
            }
        }
        overlayAttached = false;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
