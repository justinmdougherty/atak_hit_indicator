package com.atakmap.android.hitIndicator;

import android.os.Parcel;
import android.os.Parcelable;

import com.atakmap.coremap.maps.coords.GeoPoint;

public class Target implements Parcelable {
    private String id;
    private GeoPoint location;
    private int hitCount;
    private long lastSeen;
    private long calibrationTime;
    private double batteryVoltage;
    private BallisticsCalculator.BallisticsData ballisticsData; // New field for ballistics
    private int shotsFired; // Track shots fired at this target
    private long lastShotTime; // When the last shot was fired
    private double averageTimeOfFlight; // Average ToF for this target

    // GPS Quality tracking
    private int satelliteCount; // Number of satellites used
    private double hdop; // Horizontal Dilution of Precision
    private String altitudeReference; // "MSL" or "HAE"
    private boolean hasGpsQuality; // Whether GPS quality data is available

    // Constructors
    public Target(String id) {
        this.id = id;
        this.hitCount = 0;
        this.lastSeen = System.currentTimeMillis();
        this.calibrationTime = 0;
        this.batteryVoltage = -1.0; // Default value (unknown)
        this.ballisticsData = null;
        this.shotsFired = 0;
        this.lastShotTime = 0;
        this.averageTimeOfFlight = 0.0;
        this.satelliteCount = 0;
        this.hdop = 99.9; // High value indicates poor quality
        this.altitudeReference = "Unknown";
        this.hasGpsQuality = false;
        this.satelliteCount = 0;
        this.hdop = 0.0;
        this.altitudeReference = "MSL";
        this.hasGpsQuality = false;
    }

    public Target(String id, GeoPoint location) {
        this(id);
        this.location = location;
    }

    // Parcelable implementation
    protected Target(Parcel in) {
        id = in.readString();
        double lat = in.readDouble();
        double lon = in.readDouble();
        double alt = in.readDouble();
        if (lat != 0 && lon != 0) {
            location = new GeoPoint(lat, lon, alt);
        }
        hitCount = in.readInt();
        lastSeen = in.readLong();
        calibrationTime = in.readLong();
        batteryVoltage = in.readDouble();
        shotsFired = in.readInt();
        lastShotTime = in.readLong();
        averageTimeOfFlight = in.readDouble();
        satelliteCount = in.readInt();
        hdop = in.readDouble();
        altitudeReference = in.readString();
        hasGpsQuality = in.readByte() != 0;
        ballisticsData = null; // Will be recalculated when needed
    }

    public static final Creator<Target> CREATOR = new Creator<Target>() {
        @Override
        public Target createFromParcel(Parcel in) {
            return new Target(in);
        }

        @Override
        public Target[] newArray(int size) {
            return new Target[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(id);
        if (location != null) {
            dest.writeDouble(location.getLatitude());
            dest.writeDouble(location.getLongitude());
            dest.writeDouble(location.getAltitude());
        } else {
            dest.writeDouble(0);
            dest.writeDouble(0);
            dest.writeDouble(0);
        }
        dest.writeInt(hitCount);
        dest.writeLong(lastSeen);
        dest.writeLong(calibrationTime);
        dest.writeDouble(batteryVoltage);
        dest.writeInt(shotsFired);
        dest.writeLong(lastShotTime);
        dest.writeDouble(averageTimeOfFlight);
        dest.writeInt(satelliteCount);
        dest.writeDouble(hdop);
        dest.writeString(altitudeReference);
        dest.writeByte((byte) (hasGpsQuality ? 1 : 0));
        // Note: ballisticsData is not parcelable, will need to be recalculated
    }

    // Getters and setters
    public String getId() {
        return id;
    }

    public GeoPoint getLocation() {
        return location;
    }

    public void setLocation(GeoPoint location) {
        this.location = location;
        this.lastSeen = System.currentTimeMillis();
    }

    public int getHitCount() {
        return hitCount;
    }

    public void incrementHitCount() {
        this.hitCount++;
        this.lastSeen = System.currentTimeMillis();
    }

    public void resetHitCount() {
        this.hitCount = 0;
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public void updateLastSeen() {
        this.lastSeen = System.currentTimeMillis();
    }

    public long getCalibrationTime() {
        return calibrationTime;
    }

    public void setCalibrationTime(long calibrationTime) {
        this.calibrationTime = calibrationTime;
    }

    public double getBatteryVoltage() {
        return batteryVoltage;
    }

    public void setBatteryVoltage(double batteryVoltage) {
        this.batteryVoltage = batteryVoltage;
        this.lastSeen = System.currentTimeMillis();
    }

    public BallisticsCalculator.BallisticsData getBallisticsData() {
        return ballisticsData;
    }

    public void setBallisticsData(BallisticsCalculator.BallisticsData ballisticsData) {
        this.ballisticsData = ballisticsData;
    }

    public int getShotsFired() {
        return shotsFired;
    }

    public void incrementShotsFired() {
        this.shotsFired++;
        this.lastShotTime = System.currentTimeMillis();
    }

    public long getLastShotTime() {
        return lastShotTime;
    }

    public double getAverageTimeOfFlight() {
        return averageTimeOfFlight;
    }

    public void setAverageTimeOfFlight(double averageTimeOfFlight) {
        this.averageTimeOfFlight = averageTimeOfFlight;
    }

    public boolean hasBallisticsData() {
        return ballisticsData != null;
    }

    public String getBallisticsSummary() {
        if (ballisticsData == null) {
            return "No ballistics data";
        }
        return String.format("MV: %.0f m/s, BC: %.3f, ToF: %.3fs",
                ballisticsData.muzzleVelocity,
                ballisticsData.ballisticCoefficient,
                ballisticsData.timeOfFlight);
    }

    // GPS Quality getters and setters
    public int getSatelliteCount() {
        return satelliteCount;
    }

    public double getHdop() {
        return hdop;
    }

    public String getAltitudeReference() {
        return altitudeReference;
    }

    public boolean hasGpsQuality() {
        return hasGpsQuality;
    }

    public void setGpsQuality(int satellites, double hdop, String altitudeRef) {
        this.satelliteCount = satellites;
        this.hdop = hdop;
        this.altitudeReference = altitudeRef;
        this.hasGpsQuality = true;
        this.lastSeen = System.currentTimeMillis();
    }

    public String getGpsQualitySummary() {
        if (!hasGpsQuality) {
            return "GPS quality unknown";
        }
        return String.format("Sats: %d, HDOP: %.1f, Alt: %s",
                satelliteCount, hdop, altitudeReference);
    }

    public boolean isGpsQualityGood() {
        return hasGpsQuality && satelliteCount >= 4 && hdop <= 2.0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        Target target = (Target) o;
        return id.equals(target.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}