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

    // Constructors
    public Target(String id) {
        this.id = id;
        this.hitCount = 0;
        this.lastSeen = System.currentTimeMillis();
        this.calibrationTime = 0;
        this.batteryVoltage = -1.0; // Default value (unknown)
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Target target = (Target) o;
        return id.equals(target.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}