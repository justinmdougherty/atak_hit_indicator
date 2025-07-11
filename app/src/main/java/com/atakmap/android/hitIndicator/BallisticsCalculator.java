package com.atakmap.android.hitIndicator;

import android.util.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.conversion.EGM96;

/**
 * Advanced ballistics calculator for the Hit Indicator system.
 * Calculates ballistic coefficient, muzzle velocity, trajectory data,
 * and provides shooting solutions based on time-of-flight measurements.
 */
public class BallisticsCalculator {
    private static final String TAG = "BallisticsCalculator";

    // Constants
    private static final double GRAVITY = 9.80665; // m/s² standard gravity
    private static final double AIR_DENSITY_SEA_LEVEL = 1.225; // kg/m³ at 15°C, sea level
    private static final double STANDARD_TEMPERATURE = 15.0; // °C
    private static final double STANDARD_PRESSURE = 101325.0; // Pa

    // Ballistics data structure
    public static class BallisticsData {
        public double range; // meters
        public double timeOfFlight; // seconds
        public double muzzleVelocity; // m/s
        public double ballisticCoefficient; // G1 BC
        public double dropAngle; // degrees (negative = drop)
        public double elevation; // meters above firing position
        public double energyAtTarget; // joules (if bullet weight provided)
        public double velocityAtTarget; // m/s
        public String ammunitionType = "Unknown";
        public double bulletWeight = 0.0; // grams
        public long timestamp;

        // Environmental conditions
        public double temperature = STANDARD_TEMPERATURE; // °C
        public double pressure = STANDARD_PRESSURE; // Pa
        public double humidity = 50.0; // %
        public double windSpeed = 0.0; // m/s
        public double windDirection = 0.0; // degrees (0 = headwind)

        public BallisticsData() {
            this.timestamp = System.currentTimeMillis();
        }

        /**
         * Check if this ballistics data is valid
         */
        public boolean isValid() {
            return ballisticCoefficient > 0 && muzzleVelocity > 0 && timeOfFlight > 0;
        }

        /**
         * Get the ballistic coefficient
         */
        public double getBallisticCoefficient() {
            return ballisticCoefficient;
        }
    }

    // Shot data for individual shots
    public static class ShotData {
        public String targetId;
        public long shotTime; // when shot was fired
        public long hitTime; // when hit was detected
        public double timeOfFlight; // calculated
        public GeoPoint firingPosition;
        public GeoPoint targetPosition;
        public BallisticsData ballistics;
        public boolean isValid;

        public ShotData(String targetId, long shotTime, GeoPoint firingPosition, GeoPoint targetPosition) {
            this.targetId = targetId;
            this.shotTime = shotTime;
            this.firingPosition = firingPosition;
            this.targetPosition = targetPosition;
            this.isValid = false;
        }

        public void recordHit(long hitTime) {
            this.hitTime = hitTime;
            this.timeOfFlight = (hitTime - shotTime) / 1000.0; // convert to seconds
            this.isValid = true;
        }
    }

    /**
     * Calculate ballistics data from time-of-flight measurement
     */
    public static BallisticsData calculateFromTimeOfFlight(double range, double timeOfFlight,
            double elevationDifference) {
        BallisticsData data = new BallisticsData();
        data.range = range;
        data.timeOfFlight = timeOfFlight;
        data.elevation = elevationDifference;

        // Calculate apparent muzzle velocity (ignoring air resistance)
        double apparentVelocity = range / timeOfFlight;

        // Account for gravitational drop to get actual muzzle velocity
        // Using kinematic equation: y = v₀t + ½gt²
        double gravitationalDrop = 0.5 * GRAVITY * timeOfFlight * timeOfFlight;
        double actualElevationNeeded = elevationDifference + gravitationalDrop;

        // Calculate muzzle velocity components
        double horizontalVelocity = range / timeOfFlight;
        double verticalVelocity = actualElevationNeeded / timeOfFlight;
        data.muzzleVelocity = Math.sqrt(horizontalVelocity * horizontalVelocity + verticalVelocity * verticalVelocity);

        // Calculate drop angle
        data.dropAngle = Math.toDegrees(Math.atan2(-gravitationalDrop, range));

        // Estimate ballistic coefficient using simplified drag model
        data.ballisticCoefficient = estimateBallisticCoefficient(data.muzzleVelocity, range, timeOfFlight);

        // Calculate velocity at target (accounting for drag)
        data.velocityAtTarget = calculateVelocityAtTarget(data.muzzleVelocity, range, data.ballisticCoefficient);

        Log.d(TAG, String.format("Ballistics calculated: MV=%.1f m/s, BC=%.3f, ToF=%.3fs, Range=%.1fm",
                data.muzzleVelocity, data.ballisticCoefficient, timeOfFlight, range));

        return data;
    }

    /**
     * Estimate ballistic coefficient using measured vs. theoretical time of flight
     */
    private static double estimateBallisticCoefficient(double muzzleVelocity, double range, double measuredToF) {
        // Theoretical time of flight without air resistance
        double theoreticalToF = range / muzzleVelocity;

        // The difference indicates air resistance effect
        double dragEffect = measuredToF - theoreticalToF;

        // Simplified BC estimation (typical rifle bullets are 0.3-0.7)
        // Higher BC = less drag effect
        if (dragEffect <= 0) {
            return 0.5; // Default reasonable value
        }

        // Empirical formula based on typical ballistics data
        double bc = 0.5 * Math.exp(-dragEffect * 2.0);

        // Clamp to reasonable range
        return Math.max(0.1, Math.min(1.0, bc));
    }

    /**
     * Calculate velocity at target accounting for air resistance
     */
    private static double calculateVelocityAtTarget(double muzzleVelocity, double range, double bc) {
        // Simplified air resistance model
        // Actual ballistics software uses complex differential equations
        double dragFactor = Math.exp(-range / (bc * 1000.0)); // Simplified exponential decay
        return muzzleVelocity * dragFactor;
    }

    /**
     * Calculate trajectory drop at given range
     */
    public static double calculateDrop(double range, double muzzleVelocity, double bc) {
        double timeOfFlight = range / muzzleVelocity; // Simplified
        double gravitationalDrop = 0.5 * GRAVITY * timeOfFlight * timeOfFlight;

        // Air resistance extends time of flight, increasing drop
        double airResistanceFactor = 1.0 + (range / (bc * 2000.0));

        return gravitationalDrop * airResistanceFactor;
    }

    /**
     * Calculate optimal firing angle for given range and conditions
     */
    public static double calculateFiringAngle(double range, double elevationDifference,
            double muzzleVelocity, double bc) {
        // Iterate to find angle that hits target
        double bestAngle = 0.0;
        double minError = Double.MAX_VALUE;

        // Try angles from -10 to +45 degrees
        for (double angle = -10.0; angle <= 45.0; angle += 0.1) {
            double radians = Math.toRadians(angle);
            double vx = muzzleVelocity * Math.cos(radians);
            double vy = muzzleVelocity * Math.sin(radians);

            // Calculate time to reach target range
            double timeOfFlight = range / vx;

            // Calculate vertical position at that time
            double drop = calculateDrop(range, muzzleVelocity, bc);
            double actualElevation = vy * timeOfFlight - drop;

            double error = Math.abs(actualElevation - elevationDifference);
            if (error < minError) {
                minError = error;
                bestAngle = angle;
            }
        }

        return bestAngle;
    }

    /**
     * Generate a complete shooting solution
     */
    public static ShootingSolution calculateShootingSolution(GeoPoint firingPosition,
            GeoPoint targetPosition,
            BallisticsData ballisticsData) {
        ShootingSolution solution = new ShootingSolution();

        // Calculate range and bearing
        solution.range = firingPosition.distanceTo(targetPosition);
        solution.bearing = firingPosition.bearingTo(targetPosition);

        // Calculate elevation difference
        double firingElevation = EGM96.getMSL(firingPosition);
        double targetElevation = EGM96.getMSL(targetPosition);
        solution.elevationDifference = targetElevation - firingElevation;

        // Calculate required firing angle
        if (ballisticsData.muzzleVelocity > 0) {
            solution.firingAngle = calculateFiringAngle(solution.range,
                    solution.elevationDifference,
                    ballisticsData.muzzleVelocity,
                    ballisticsData.ballisticCoefficient);
        }

        // Calculate predicted time of flight
        solution.predictedTimeOfFlight = solution.range / ballisticsData.muzzleVelocity;

        // Calculate drop
        solution.drop = calculateDrop(solution.range, ballisticsData.muzzleVelocity,
                ballisticsData.ballisticCoefficient);

        // Calculate energy at target
        if (ballisticsData.bulletWeight > 0) {
            double velocityAtTarget = calculateVelocityAtTarget(ballisticsData.muzzleVelocity,
                    solution.range,
                    ballisticsData.ballisticCoefficient);
            // KE = ½mv² (convert grain to kg: grain ÷ 15432.4)
            double massKg = ballisticsData.bulletWeight / 15432.4;
            solution.energyAtTarget = 0.5 * massKg * velocityAtTarget * velocityAtTarget;
        }

        Log.d(TAG, String.format("Shooting solution: Range=%.1fm, Angle=%.2f°, ToF=%.3fs, Drop=%.2fm",
                solution.range, solution.firingAngle, solution.predictedTimeOfFlight, solution.drop));

        return solution;
    }

    /**
     * Shooting solution data structure
     */
    public static class ShootingSolution {
        public double range; // meters
        public double bearing; // degrees true
        public double elevationDifference; // meters
        public double firingAngle; // degrees above horizontal
        public double predictedTimeOfFlight; // seconds
        public double drop; // meters
        public double energyAtTarget; // joules
        public long calculatedAt;

        public ShootingSolution() {
            this.calculatedAt = System.currentTimeMillis();
        }

        public String getFormattedSolution() {
            return String.format(
                    "Range: %.0fm\nBearing: %.1f°\nElevation: %+.1fm\nAngle: %+.2f°\nToF: %.3fs\nDrop: %.2fm",
                    range, bearing, elevationDifference, firingAngle, predictedTimeOfFlight, drop);
        }
    }
}
