package com.atakmap.android.hitIndicator;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks shots fired and correlates them with target hits to calculate
 * ballistics data.
 * Manages the timing between rifle shot detection and target hit detection.
 */
public class ShotTracker {
    private static final String TAG = "ShotTracker";

    // Maximum time to wait for a hit after a shot (seconds)
    private static final double MAX_HIT_DELAY = 10.0;

    // Minimum reasonable time of flight (to filter out false positives)
    private static final double MIN_TIME_OF_FLIGHT = 0.01; // 10ms

    public interface ShotTrackerListener {
        void onShotFired(String targetId, long shotTime);

        void onHitCorrelated(String targetId, BallisticsCalculator.ShotData shotData);

        void onShotTimeout(String targetId, long shotTime);

        void onBallisticsCalculated(String targetId, BallisticsCalculator.BallisticsData ballistics);
    }

    private final ShotTrackerListener listener;
    private final Handler timeoutHandler;

    // Track pending shots waiting for hits
    private final Map<String, List<BallisticsCalculator.ShotData>> pendingShots;

    // Track completed shots for analysis
    private final Map<String, List<BallisticsCalculator.ShotData>> completedShots;

    // Current firing position
    private GeoPoint currentFiringPosition;

    // Target positions cache
    private final Map<String, GeoPoint> targetPositions;

    public ShotTracker(ShotTrackerListener listener) {
        this.listener = listener;
        this.timeoutHandler = new Handler(Looper.getMainLooper());
        this.pendingShots = new ConcurrentHashMap<>();
        this.completedShots = new ConcurrentHashMap<>();
        this.targetPositions = new ConcurrentHashMap<>();
    }

    /**
     * Update the current firing position (shooter's location)
     */
    public void updateFiringPosition(GeoPoint position) {
        this.currentFiringPosition = position;
        Log.d(TAG, "Firing position updated: " + position);
    }

    /**
     * Update a target's position
     */
    public void updateTargetPosition(String targetId, GeoPoint position) {
        targetPositions.put(targetId, position);
        Log.d(TAG, "Target " + targetId + " position updated: " + position);
    }

    /**
     * Record a shot fired at a specific target
     */
    public void recordShotFired(String targetId) {
        recordShotFired(targetId, System.currentTimeMillis());
    }

    /**
     * Record a shot fired at a specific target with specific timestamp
     */
    public void recordShotFired(String targetId, long shotTime) {
        if (currentFiringPosition == null) {
            Log.w(TAG, "Cannot record shot - firing position not set");
            return;
        }

        GeoPoint targetPosition = targetPositions.get(targetId);
        if (targetPosition == null) {
            Log.w(TAG, "Cannot record shot - target position unknown for: " + targetId);
            return;
        }

        BallisticsCalculator.ShotData shotData = new BallisticsCalculator.ShotData(
                targetId, shotTime, currentFiringPosition, targetPosition);

        // Add to pending shots
        pendingShots.computeIfAbsent(targetId, k -> new ArrayList<>()).add(shotData);

        // Set timeout for this shot
        timeoutHandler.postDelayed(() -> handleShotTimeout(shotData),
                (long) (MAX_HIT_DELAY * 1000));

        Log.d(TAG, String.format("Shot recorded for target %s at %d", targetId, shotTime));

        if (listener != null) {
            listener.onShotFired(targetId, shotTime);
        }
    }

    /**
     * Record a hit detected on a target
     */
    public void recordHit(String targetId) {
        recordHit(targetId, System.currentTimeMillis());
    }

    /**
     * Record a hit detected on a target with specific timestamp
     */
    public void recordHit(String targetId, long hitTime) {
        List<BallisticsCalculator.ShotData> pending = pendingShots.get(targetId);
        if (pending == null || pending.isEmpty()) {
            Log.w(TAG, "Hit recorded but no pending shots for target: " + targetId);
            return;
        }

        // Find the oldest pending shot for this target
        BallisticsCalculator.ShotData matchedShot = null;
        for (BallisticsCalculator.ShotData shot : pending) {
            if (!shot.isValid) { // Only match unmatched shots
                double timeOfFlight = (hitTime - shot.shotTime) / 1000.0;
                if (timeOfFlight >= MIN_TIME_OF_FLIGHT && timeOfFlight <= MAX_HIT_DELAY) {
                    matchedShot = shot;
                    break; // Take the first (oldest) valid match
                }
            }
        }

        if (matchedShot != null) {
            // Record the hit
            matchedShot.recordHit(hitTime);

            // Move to completed shots
            completedShots.computeIfAbsent(targetId, k -> new ArrayList<>()).add(matchedShot);
            pending.remove(matchedShot);

            // Calculate ballistics
            calculateBallistics(matchedShot);

            Log.d(TAG, String.format("Hit correlated for target %s: ToF=%.3fs",
                    targetId, matchedShot.timeOfFlight));

            if (listener != null) {
                listener.onHitCorrelated(targetId, matchedShot);
            }
        } else {
            Log.w(TAG, "Hit recorded but no matching pending shot found for target: " + targetId);
        }
    }

    /**
     * Calculate ballistics data for a completed shot
     */
    private void calculateBallistics(BallisticsCalculator.ShotData shotData) {
        if (!shotData.isValid) {
            Log.w(TAG, "Cannot calculate ballistics for invalid shot data");
            return;
        }

        double range = shotData.firingPosition.distanceTo(shotData.targetPosition);
        double elevationDiff = getElevationDifference(shotData.firingPosition, shotData.targetPosition);

        BallisticsCalculator.BallisticsData ballistics = BallisticsCalculator.calculateFromTimeOfFlight(range,
                shotData.timeOfFlight, elevationDiff);

        shotData.ballistics = ballistics;

        Log.d(TAG, String.format("Ballistics calculated for target %s: MV=%.1f m/s, BC=%.3f",
                shotData.targetId, ballistics.muzzleVelocity, ballistics.ballisticCoefficient));

        if (listener != null) {
            listener.onBallisticsCalculated(shotData.targetId, ballistics);
        }
    }

    /**
     * Handle shot timeout (no hit detected within time limit)
     */
    private void handleShotTimeout(BallisticsCalculator.ShotData shotData) {
        if (shotData.isValid) {
            return; // Already matched with a hit
        }

        // Remove from pending shots
        List<BallisticsCalculator.ShotData> pending = pendingShots.get(shotData.targetId);
        if (pending != null) {
            pending.remove(shotData);
        }

        Log.d(TAG, "Shot timeout for target: " + shotData.targetId);

        if (listener != null) {
            listener.onShotTimeout(shotData.targetId, shotData.shotTime);
        }
    }

    /**
     * Get elevation difference between firing position and target
     */
    private double getElevationDifference(GeoPoint firingPos, GeoPoint targetPos) {
        try {
            // Use MSL (Mean Sea Level) elevations
            double firingElevation = com.atakmap.coremap.maps.conversion.EGM96.getMSL(firingPos);
            double targetElevation = com.atakmap.coremap.maps.conversion.EGM96.getMSL(targetPos);
            return targetElevation - firingElevation;
        } catch (Exception e) {
            Log.w(TAG, "Error calculating elevation difference: " + e.getMessage());
            return 0.0;
        }
    }

    /**
     * Get all completed shots for a target
     */
    public List<BallisticsCalculator.ShotData> getCompletedShots(String targetId) {
        List<BallisticsCalculator.ShotData> shots = completedShots.get(targetId);
        return shots != null ? new ArrayList<>(shots) : new ArrayList<>();
    }

    /**
     * Get all completed shots for all targets
     */
    public Map<String, List<BallisticsCalculator.ShotData>> getAllCompletedShots() {
        Map<String, List<BallisticsCalculator.ShotData>> result = new HashMap<>();
        for (Map.Entry<String, List<BallisticsCalculator.ShotData>> entry : completedShots.entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return result;
    }

    /**
     * Get pending shots count for a target
     */
    public int getPendingShotsCount(String targetId) {
        List<BallisticsCalculator.ShotData> pending = pendingShots.get(targetId);
        return pending != null ? pending.size() : 0;
    }

    /**
     * Get average ballistics data for a target
     */
    public BallisticsCalculator.BallisticsData getAverageBallisticsData(String targetId) {
        List<BallisticsCalculator.ShotData> shots = getCompletedShots(targetId);
        if (shots.isEmpty()) {
            return null;
        }

        BallisticsCalculator.BallisticsData avgData = new BallisticsCalculator.BallisticsData();
        double totalMV = 0, totalBC = 0, totalToF = 0, totalRange = 0;
        int count = 0;

        for (BallisticsCalculator.ShotData shot : shots) {
            if (shot.ballistics != null) {
                totalMV += shot.ballistics.muzzleVelocity;
                totalBC += shot.ballistics.ballisticCoefficient;
                totalToF += shot.ballistics.timeOfFlight;
                totalRange += shot.ballistics.range;
                count++;
            }
        }

        if (count > 0) {
            avgData.muzzleVelocity = totalMV / count;
            avgData.ballisticCoefficient = totalBC / count;
            avgData.timeOfFlight = totalToF / count;
            avgData.range = totalRange / count;
        }

        return avgData;
    }

    /**
     * Clear all shot data
     */
    public void clearAllData() {
        pendingShots.clear();
        completedShots.clear();
        Log.d(TAG, "All shot data cleared");
    }

    /**
     * Clear shot data for specific target
     */
    public void clearTargetData(String targetId) {
        pendingShots.remove(targetId);
        completedShots.remove(targetId);
        Log.d(TAG, "Shot data cleared for target: " + targetId);
    }

    /**
     * Get statistics summary
     */
    public String getStatisticsSummary(String targetId) {
        List<BallisticsCalculator.ShotData> shots = getCompletedShots(targetId);
        int pendingCount = getPendingShotsCount(targetId);

        if (shots.isEmpty()) {
            return String.format("Target %s: %d pending shots, no completed data", targetId, pendingCount);
        }

        BallisticsCalculator.BallisticsData avgData = getAverageBallisticsData(targetId);

        return String.format("Target %s: %d shots, %d pending\nAvg MV: %.1f m/s\nAvg BC: %.3f\nAvg ToF: %.3fs",
                targetId, shots.size(), pendingCount,
                avgData.muzzleVelocity, avgData.ballisticCoefficient, avgData.timeOfFlight);
    }
}
