package com.atakmap.android.hitIndicator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import com.atakmap.coremap.log.Log; // Import Log
// No GeoPoint needed here unless for other utility methods you might add
// No AltitudeReference needed here
// No EGM96 needed here (calculation happens *before* calling this view)

import java.util.Locale;

public class ElevationProfileView extends View {
    private final Paint linePaint = new Paint();
    private final Paint fillPaint = new Paint();
    private final Paint textPaint = new Paint();
    private final Paint gridPaint = new Paint();
    private final Paint labelPaint = new Paint();
    private final Path profilePath = new Path();

    private static final String TAG = "ElevationProfileView"; // Logging tag

    // Store the MSL altitudes and distance directly
    private double myMSLAltitude = Double.NaN; // Use NaN for unset state
    private double targetMSLAltitude = Double.NaN; // Use NaN for unset state
    private double distance = 0;

    // Calculated display range based on MSL values
    private double minDisplayAltitude = 0;
    private double maxDisplayAltitude = 1; // Initialize to prevent division by zero

    private boolean hasValidData = false; // Flag to track if valid data is set

    public ElevationProfileView(Context context) {
        super(context);
        init();
    }

    public ElevationProfileView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        linePaint.setColor(Color.GREEN);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(3f);
        linePaint.setAntiAlias(true);

        fillPaint.setColor(Color.argb(100, 0, 200, 0)); // Semi-transparent green
        fillPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(24f);
        textPaint.setAntiAlias(true); // Smoother text

        gridPaint.setColor(Color.DKGRAY); // Darker gray for grid
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setAntiAlias(true);

        labelPaint.setColor(Color.LTGRAY); // Lighter gray for labels
        labelPaint.setTextSize(20f);
        labelPaint.setAntiAlias(true);
    }

    /**
     * Sets the profile data using pre-calculated MSL altitudes and distance.
     * This is the primary method to update the view's data.
     *
     * @param myMSL Valid MSL altitude of the user's location (meters). Use Double.NaN if unknown.
     * @param targetMSL Valid MSL altitude of the target's location (meters). Use Double.NaN if unknown.
     * @param distance Horizontal distance between the points (meters).
     */
    public void setProfileData(double myMSL, double targetMSL, double distance) {
        // Check if provided altitudes are valid numbers
        if (Double.isNaN(myMSL) || Double.isNaN(targetMSL)) {
            Log.w(TAG, "setProfileData called with invalid (NaN) altitude(s). Clearing profile.");
            clearProfileData();
            return;
        }

        this.myMSLAltitude = myMSL;
        this.targetMSLAltitude = targetMSL;
        this.distance = distance;
        this.hasValidData = true; // Mark data as valid

        // Calculate display range based directly on the provided MSL values
        double rawMin = Math.min(myMSL, targetMSL);
        double rawMax = Math.max(myMSL, targetMSL);
        double range = rawMax - rawMin;

        // Add padding (ensure range is slightly more than zero if altitudes are equal)
        double pad = Math.max(1.0, range * 0.05); // 5% padding, min 1m
        if (range < 0.1) { // Handle very close altitudes
            pad = 1.0;
        }

        minDisplayAltitude = rawMin - pad;
        maxDisplayAltitude = rawMax + pad;

        // Ensure max is always greater than min
        if (maxDisplayAltitude <= minDisplayAltitude) {
            maxDisplayAltitude = minDisplayAltitude + 1.0;
        }

        Log.d(TAG, String.format(Locale.US, "SetProfileData: MyMSL=%.1f, TargetMSL=%.1f, Dist=%.1f | DisplayRange: %.1f to %.1f",
                myMSL, targetMSL, distance, minDisplayAltitude, maxDisplayAltitude));

        invalidate(); // Request redraw
    }

    /** Clears the profile data and redraws an empty view */
    public void clearProfileData() {
        this.hasValidData = false;
        this.myMSLAltitude = Double.NaN; // Use NaN
        this.targetMSLAltitude = Double.NaN; // Use NaN
        this.distance = 0;
        this.minDisplayAltitude = 0;
        this.maxDisplayAltitude = 1;
        Log.d(TAG, "Profile data cleared.");
        invalidate();
    }


    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Only draw if valid data has been set via setProfileData
        if (!hasValidData) {
            String noDataMsg = "No profile data";
            float msgWidth = textPaint.measureText(noDataMsg);
            canvas.drawText(noDataMsg, (getWidth() - msgWidth) / 2f, getHeight() / 2f, textPaint);
            return;
        }

        int width = getWidth();
        int height = getHeight();

        // Basic checks for valid dimensions and range
        if (height <= 0 || width <= 0 || maxDisplayAltitude <= minDisplayAltitude) {
            Log.w(TAG, "Cannot draw profile with invalid dimensions or altitude range.");
            return;
        }

        // Draw grid first (background)
        drawGrid(canvas, width, height);

        // Calculate Y coordinates based *directly* on the stored MSL altitudes
        profilePath.reset();

        // Start point (My Location) - Left side
        float startX = 0;
        float startY = height - (float) ((myMSLAltitude - minDisplayAltitude) / (maxDisplayAltitude - minDisplayAltitude) * height);

        // End point (Target Location) - Right side
        float endX = width;
        float endY = height - (float) ((targetMSLAltitude - minDisplayAltitude) / (maxDisplayAltitude - minDisplayAltitude) * height);

        // Clamp Y values to view bounds to prevent drawing outside
        startY = Math.max(0f, Math.min(height, startY));
        endY = Math.max(0f, Math.min(height, endY));

        // Define the profile line
        profilePath.moveTo(startX, startY);
        profilePath.lineTo(endX, endY);

        // Define the fill path under the line
        Path fillPath = new Path(profilePath); // Copy the line path
        fillPath.lineTo(endX, height); // Line down to bottom right
        fillPath.lineTo(startX, height); // Line to bottom left
        fillPath.close(); // Close path back to start (completes the shape)

        // Draw the fill first
        canvas.drawPath(fillPath, fillPaint);

        // Draw the profile line on top of the fill
        canvas.drawPath(profilePath, linePaint);

        // Draw Min/Max altitude labels (using display range)
        String minLabel = String.format(Locale.US, "%.0f m", minDisplayAltitude);
        String maxLabel = String.format(Locale.US, "%.0f m", maxDisplayAltitude);
        canvas.drawText(maxLabel, 10, textPaint.getTextSize() + 5, textPaint);
        canvas.drawText(minLabel, 10, height - 10, textPaint);

        // Draw distance labels at bottom
        String leftDistLabel = "0 m";
        String rightDistLabel = String.format(Locale.US, "%.0f m", distance);
        float labelY = height - 5;
        canvas.drawText(leftDistLabel, 10, labelY, labelPaint);
        float rightLabelWidth = labelPaint.measureText(rightDistLabel);
        canvas.drawText(rightDistLabel, width - rightLabelWidth - 10, labelY, labelPaint);
    }

    // drawGrid remains the same
    private void drawGrid(Canvas canvas, int width, int height) {
        int numHLines = 5;
        if (height <= 0 || numHLines <= 0) return;
        float hSpacing = (float)height / numHLines;
        for (int i = 0; i <= numHLines; i++) {
            float y = i * hSpacing;
            y = Math.max(0.5f, Math.min(height - 0.5f, y));
            canvas.drawLine(0, y, width, y, gridPaint);
        }

        int numVLines = 5;
        if (width <= 0 || numVLines <= 0) return;
        float vSpacing = (float)width / numVLines;
        for (int i = 0; i <= numVLines; i++) {
            float x = i * vSpacing;
            x = Math.max(0.5f, Math.min(width - 0.5f, x));
            canvas.drawLine(x, 0, x, height, gridPaint);
        }
    }
}
