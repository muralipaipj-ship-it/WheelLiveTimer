package com.mallupai.wheelmovementtracker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/** Compact button-only control screen for the live wheel tracker. */
public class MainActivity extends Activity {
    private static final int CAPTURE_REQUEST = 301;
    private static final int NOTIFICATION_REQUEST = 302;
    private static final int EXPORT_REQUEST = 303;
    private static final int IMPORT_REQUEST = 304;

    private static final int BACKGROUND = 0xFF080B16;
    private static final int SURFACE = 0xFF1B2338;
    private static final int BORDER = 0xFF2B3652;
    private static final int PRIMARY = 0xFF62E6FF;
    private static final int TEXT = 0xFFF4F7FF;

    private MovementStore store;
    private MediaProjectionManager projectionManager;
    private Button dataCountButton;
    private Button forecastPercentButton;
    private Button minimumSamplesButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new MovementStore(this);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(22), dp(16), dp(20));
        root.setBackgroundColor(BACKGROUND);

        TextView title = new TextView(this);
        title.setText("WHEEL TRACKER");
        title.setTextSize(22);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title);

        Button start = button("START FLOATING TRACKER", true);
        start.setOnClickListener(view -> beginCapture());
        root.addView(start);

        dataCountButton = button("", false);
        dataCountButton.setOnClickListener(view -> chooseDataCount());
        root.addView(dataCountButton);

        forecastPercentButton = button("", false);
        forecastPercentButton.setOnClickListener(view -> chooseForecastPercent());
        root.addView(forecastPercentButton);

        minimumSamplesButton = button("", false);
        minimumSamplesButton.setOnClickListener(view -> chooseMinimumSamples());
        root.addView(minimumSamplesButton);

        Button report = button("REPORT • LAST 5 RECORDS", false);
        report.setOnClickListener(view -> showReport());
        root.addView(report);

        root.addView(buttonRow("EXPORT DATA", view -> exportData(),
                "IMPORT DATA", view -> importData()));
        root.addView(buttonRow("STOP", view -> {
                    stopService(new Intent(this, CaptureService.class));
                    Toast.makeText(this, "Tracking stopped", Toast.LENGTH_SHORT).show();
                },
                "RESET", view -> confirmReset()));

        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setBackgroundColor(BACKGROUND);
        page.addView(root);
        setContentView(page);
        updateButtons();

        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_REQUEST);
        }
    }

    private LinearLayout buttonRow(String leftText, android.view.View.OnClickListener leftAction,
                                   String rightText, android.view.View.OnClickListener rightAction) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, dp(7), 0, 0);
        row.setLayoutParams(rowParams);

        Button left = button(leftText, false);
        left.setLayoutParams(weighted(0));
        left.setOnClickListener(leftAction);
        row.addView(left);
        Button right = button(rightText, false);
        right.setLayoutParams(weighted(dp(7)));
        right.setOnClickListener(rightAction);
        row.addView(right);
        return row;
    }

    private void updateButtons() {
        if (dataCountButton == null) return;
        dataCountButton.setText("SCAN DATA COUNT • " + store.rollbackLabel().toUpperCase());
        forecastPercentButton.setText("MINIMUM FORECAST • "
                + store.getMinimumForecastPercent() + "%");
        minimumSamplesButton.setText("MINIMUM SAMPLE SIZE • " + store.getMinimumSamples());
    }

    private void chooseDataCount() {
        chooseNumber("Scan data count", "Enter 1–2000, or 0 for all saved rounds.",
                store.getRollback(), 0, 2000, value -> store.setRollback(value));
    }

    private void chooseForecastPercent() {
        chooseNumber("Minimum forecast percentage",
                "The next forecast is shown only when its final fruit score reaches this percentage.",
                store.getMinimumForecastPercent(), 0, 100,
                value -> store.setMinimumForecastPercent(value));
    }

    private void chooseMinimumSamples() {
        chooseNumber("Minimum sample size",
                "The app waits for this many saved rounds before showing a forecast.",
                store.getMinimumSamples(), 1, 2000,
                value -> store.setMinimumSamples(value));
    }

    private void chooseNumber(String title, String message, int current,
                              int minimum, int maximum, NumberSetter setter) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(current));
        input.setSelectAllOnFocus(true);
        input.setPadding(dp(14), dp(10), dp(14), dp(10));

        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(dp(20), 0, dp(20), 0);
        holder.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setView(holder)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    try {
                        int value = Integer.parseInt(input.getText().toString().trim());
                        if (value < minimum || value > maximum) throw new NumberFormatException();
                        setter.set(value);
                        updateButtons();
                    } catch (NumberFormatException error) {
                        Toast.makeText(this, "Enter a number from " + minimum + " to " + maximum,
                                Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void showReport() {
        String reportText = store.report(5);
        TextView content = new TextView(this);
        content.setText(reportText);
        content.setTextSize(11);
        content.setTextColor(0xFF162033);
        content.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        content.setTextIsSelectable(true);
        content.setPadding(dp(16), dp(12), dp(16), dp(18));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Scanned data • patterns • profit")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .setNeutralButton("Share", (item, which) -> shareReport(reportText))
                .create();
        dialog.setOnShowListener(item -> {
            Window window = dialog.getWindow();
            if (window != null) window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.round(getResources().getDisplayMetrics().heightPixels * .76f));
        });
        dialog.show();
    }

    private void shareReport(String reportText) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, "Wheel tracker report");
        share.putExtra(Intent.EXTRA_TEXT, reportText);
        startActivity(Intent.createChooser(share, "Share report"));
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("Reset all saved rounds?")
                .setMessage("Settings are kept. Recorded rounds and performance are removed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset", (dialog, which) -> {
                    store.clear();
                    Toast.makeText(this, "Saved history reset", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void beginCapture() {
        if (projectionManager == null) {
            Toast.makeText(this, "Screen capture is not available", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, "Allow floating-window permission, then tap Start again",
                    Toast.LENGTH_LONG).show();
            return;
        }
        startActivityForResult(projectionManager.createScreenCaptureIntent(), CAPTURE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == EXPORT_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                writeExport(data.getData());
            }
            return;
        }
        if (requestCode == IMPORT_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                readImport(data.getData());
            }
            return;
        }
        if (requestCode != CAPTURE_REQUEST) return;
        if (resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, CaptureService.class);
            service.putExtra("code", resultCode);
            service.putExtra("data", data);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
            else startService(service);
            Toast.makeText(this, "Live tracker started", Toast.LENGTH_SHORT).show();
            moveTaskToBack(true);
        } else {
            Toast.makeText(this, "Screen-capture permission cancelled", Toast.LENGTH_SHORT).show();
        }
    }

    private void exportData() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "WheelTracker-data.json");
        startActivityForResult(intent, EXPORT_REQUEST);
    }

    private void importData() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, IMPORT_REQUEST);
    }

    private void writeExport(Uri uri) {
        try (OutputStream stream = getContentResolver().openOutputStream(uri);
             OutputStreamWriter writer = stream == null ? null
                     : new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
            if (writer == null) throw new Exception("File cannot be opened");
            writer.write(store.exportJson());
            writer.flush();
            Toast.makeText(this, "Exported " + store.size() + " rounds",
                    Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, "Export failed: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void readImport(Uri uri) {
        try (InputStream stream = getContentResolver().openInputStream(uri);
             BufferedReader reader = stream == null ? null : new BufferedReader(
                     new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            if (reader == null) throw new Exception("File cannot be opened");
            StringBuilder raw = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) raw.append(line).append('\n');
            chooseImportMode(raw.toString());
        } catch (Exception error) {
            Toast.makeText(this, "Import failed: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void chooseImportMode(String raw) {
        new AlertDialog.Builder(this)
                .setTitle("Import tracker data")
                .setMessage("Merge keeps current rounds and skips duplicates. Replace loads only the selected file.")
                .setPositiveButton("Replace", (dialog, which) -> applyImport(raw, false))
                .setNeutralButton("Merge", (dialog, which) -> applyImport(raw, true))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applyImport(String raw, boolean merge) {
        try {
            MovementStore.ImportResult result = store.importJson(raw, merge);
            updateButtons();
            Toast.makeText(this, result.display(), Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "Import failed: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? BACKGROUND : TEXT);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(44));
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
        button.setBackground(rounded(primary ? PRIMARY : SURFACE,
                11, primary ? 0 : BORDER, primary ? 0 : 1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(7), 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private LinearLayout.LayoutParams weighted(int leftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(leftMargin, 0, 0, 0);
        return params;
    }

    private GradientDrawable rounded(int fill, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), stroke);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateButtons();
    }

    private interface NumberSetter {
        void set(int value);
    }
}
