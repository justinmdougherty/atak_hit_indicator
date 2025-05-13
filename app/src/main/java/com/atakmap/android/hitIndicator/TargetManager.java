package com.atakmap.android.hitIndicator;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Parcel;
import android.util.Base64;
import android.util.Log;

import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TargetManager {
    private static final String TAG = "TargetManager";
    private static final String PREFS_NAME = "hitIndicator_prefs";
    private static final String PREF_TARGETS = "targets";

    private final Map<String, Target> targets;
    private final Context context;

    public TargetManager(Context context) {
        this.context = context;
        this.targets = new HashMap<>();
        loadTargets();
    }

    public Target updateTargetPosition(String id, GeoPoint location) {
        Target target = targets.get(id);

        if (target == null) {
            target = new Target(id, location);
            targets.put(id, target);
        } else {
            target.setLocation(location);
        }

        saveTargets();
        return target;
    }

    public Target processHit(String id) {
        Target target = targets.get(id);

        if (target == null) {
            target = new Target(id);
            targets.put(id, target);
        }

        target.incrementHitCount();
        saveTargets();
        return target;
    }

    public void setCalibrationTime(String id, long calibrationTime) {
        Target target = targets.get(id);

        if (target != null) {
            target.setCalibrationTime(calibrationTime);
            saveTargets();
        }
    }

    public void updateTargetVoltage(String id, double voltage) {
        Target target = targets.get(id);

        if (target != null) {
            target.setBatteryVoltage(voltage);
            saveTargets();
        } else {
            Log.w(TAG, "Received voltage for unknown target ID: " + id);
        }
    }

    public void resetHitCount(String id) {
        Target target = targets.get(id);

        if (target != null) {
            target.resetHitCount();
            saveTargets();
        }
    }

    public void resetAllHitCounts() {
        for (Target target : targets.values()) {
            target.resetHitCount();
        }
        saveTargets();
    }

    public List<Target> getAllTargets() {
        return new ArrayList<>(targets.values());
    }

    public Target getTarget(String id) {
        return targets.get(id);
    }

    public void removeTarget(String id) {
        targets.remove(id);
        saveTargets();
    }

    public void clearTargets() {
        targets.clear();
        saveTargets();
    }

    private void saveTargets() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            // Convert targets to serialized bytes
            Parcel parcel = Parcel.obtain();
            parcel.writeInt(targets.size());

            for (Target target : targets.values()) {
                parcel.writeParcelable(target, 0);
            }

            byte[] bytes = parcel.marshall();
            parcel.recycle();

            // Save as Base64 encoded string
            String serialized = Base64.encodeToString(bytes, Base64.DEFAULT);
            editor.putString(PREF_TARGETS, serialized);
            editor.apply();

        } catch (Exception e) {
            Log.e(TAG, "Error saving targets", e);
        }
    }

    private void loadTargets() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String serialized = prefs.getString(PREF_TARGETS, null);

            if (serialized != null) {
                byte[] bytes = Base64.decode(serialized, Base64.DEFAULT);

                Parcel parcel = Parcel.obtain();
                parcel.unmarshall(bytes, 0, bytes.length);
                parcel.setDataPosition(0);

                int size = parcel.readInt();

                for (int i = 0; i < size; i++) {
                    Target target = parcel.readParcelable(Target.class.getClassLoader());
                    if (target != null) {
                        targets.put(target.getId(), target);
                    }
                }

                parcel.recycle();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error loading targets", e);
        }
    }
}