package com.atakmap.android.hitIndicator;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

// Assuming GeoCalculations might not be needed if using GeoPoint.distanceTo directly
// import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;
// Import ATAK Time formatting if needed, otherwise use standard Java
// import com.atakmap.android.maps.MapView; // Not used directly here
import com.atakmap.coremap.maps.time.CoordinatedTime; // Potentially useful for consistent time


import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;


/**
 * Adapter for displaying targets in a ListView, showing Slant and Horizontal Range.
 */
public class TargetListAdapter extends BaseAdapter {

    // Keep TargetActionListener for Reset button and item click
    public interface TargetActionListener {
        void onResetTarget(Target target);
        void onLocateTarget(Target target); // Handles item click (e.g., show detail view)
    }

    private static final String TAG = "TargetListAdapter";
    private final List<Target> targets;
    private final Context context;
    private final LayoutInflater inflater;
    private final TargetActionListener listener;
    private GeoPoint myLocation; // User's current location
    private final SimpleDateFormat timeFormatter; // Use consistent formatter

    public TargetListAdapter(Context context, TargetActionListener listener) {
        this.context = context;
        this.targets = new ArrayList<>();
        this.inflater = LayoutInflater.from(context);
        this.listener = listener;

        // Initialize a time formatter (adjust format as needed)
        // Using "HH:mm:ss" as requested previously
        timeFormatter = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        // Consider setting timezone explicitly if needed e.g., TimeZone.getTimeZone("UTC")
        timeFormatter.setTimeZone(TimeZone.getDefault());
    }

    /**
     * Updates the adapter's knowledge of the user's current location.
     * @param location The current GeoPoint of the user.
     */
    public void setMyLocation(GeoPoint location) {
        this.myLocation = location;
        notifyDataSetChanged(); // Trigger list update as distances/bearings change
    }

    @Override
    public int getCount() {
        return targets.size();
    }

    @Override
    public Target getItem(int position) {
        // Add bounds check for safety
        if (position >= 0 && position < targets.size()) {
            return targets.get(position);
        }
        return null; // Or handle error appropriately
    }

    @Override
    public long getItemId(int position) {
        // Using position is simple but can be problematic if list order changes frequently
        // without calling notifyDataSetChanged. Using hashcode of a stable ID is better.
        // Example: return getItem(position).getId().hashCode();
        return position;
    }

    @Override
    public View getView(final int position, View convertView, ViewGroup parent) {
        ViewHolder holder;

        if (convertView == null) {
            // Inflate the layout defined in target_list_item.xml
            convertView = inflater.inflate(R.layout.target_list_item, parent, false); // Use correct layout ID

            holder = new ViewHolder();
            // Find all views by their IDs from the layout
            holder.targetItemIcon = convertView.findViewById(R.id.targetItemIcon);
            holder.targetItemIdText = convertView.findViewById(R.id.targetItemIdText);
            holder.targetItemHitCountText = convertView.findViewById(R.id.targetItemHitCountText);
            holder.targetItemLastSeenText = convertView.findViewById(R.id.targetItemLastSeenText);
            holder.targetItemVoltageText = convertView.findViewById(R.id.targetItemVoltageText);
            // Get references for the range TextViews based on the updated layout
            holder.targetItemSlantRangeText = convertView.findViewById(R.id.targetItemSlantRangeText); // Updated ID
            holder.targetItemHorizontalRangeText = convertView.findViewById(R.id.targetItemHorizontalRangeText); // New ID
            holder.targetItemBearingText = convertView.findViewById(R.id.targetItemBearingText);
            holder.targetItemResetButton = convertView.findViewById(R.id.targetItemResetButton);

            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        final Target target = getItem(position);

        // Ensure target is not null before proceeding
        if (target == null) {
            Log.e(TAG, "Target at position " + position + " is null.");
            // Optionally hide the view or display an error state
            holder.targetItemIdText.setText("Error: Null Target");
            // Clear other fields...
            return convertView; // Return early
        }


        // --- Populate Center Section ---
        holder.targetItemIdText.setText("Target ID: " + target.getId());
        holder.targetItemHitCountText.setText("Hits: " + target.getHitCount());

        // Format and display last seen time using the member formatter
        long lastSeen = target.getLastSeen();
        if (lastSeen > 0) {
            // Use the pre-initialized timeFormatter
            holder.targetItemLastSeenText.setText("Last Seen: " + timeFormatter.format(new Date(lastSeen)));
        } else {
            holder.targetItemLastSeenText.setText("Last Seen: Never");
        }

        // Display voltage if available
        double voltage = target.getBatteryVoltage();
        if (voltage >= 0) { // Check for valid voltage (e.g., non-negative)
            holder.targetItemVoltageText.setText(String.format(Locale.US, "Voltage: %.2f V", voltage));
        } else {
            holder.targetItemVoltageText.setText("Voltage: N/A");
        }


        // --- Populate Right Section (Ranges & Bearing) ---
        GeoPoint targetLocation = target.getLocation();
        if (myLocation != null && targetLocation != null) {
            // 1. Calculate SLANT RANGE (LRF distance) using standard distanceTo
            double slantRangeMeters = myLocation.distanceTo(targetLocation);

            // 2. Calculate approximate HORIZONTAL distance
            // Note: Uses altitudes directly from GeoPoints. For higher accuracy,
            // ensure both points use MSL altitude before this calculation.
            double verticalDiff = targetLocation.getAltitude() - myLocation.getAltitude();
            double horizontalRangeMeters = Double.NaN;
            if (!Double.isNaN(slantRangeMeters) && !Double.isNaN(verticalDiff)) {
                double slantSq = slantRangeMeters * slantRangeMeters;
                double vertSq = verticalDiff * verticalDiff;
                if (slantSq >= vertSq && slantSq > 0) { // Check slantSq > 0 to avoid sqrt(0) issues if points identical
                    horizontalRangeMeters = Math.sqrt(slantSq - vertSq);
                } else {
                    // Slant is less than vertical (unlikely unless points are vertically aligned)
                    // or points are identical. Horizontal distance is effectively 0.
                    horizontalRangeMeters = 0.0;
                }
            }

            // 3. Calculate Bearing
            double bearing = myLocation.bearingTo(targetLocation);
            if (bearing < 0) bearing += 360; // Normalize to 0-360

            // 4. Format and Display Ranges
            // Slant Range
            if (Double.isNaN(slantRangeMeters)) {
                holder.targetItemSlantRangeText.setText("LOS Range: Error");
            } else if (slantRangeMeters < 1000) {
                holder.targetItemSlantRangeText.setText(String.format(Locale.US, "LOS Range: %.0f m", slantRangeMeters));
            } else {
                holder.targetItemSlantRangeText.setText(String.format(Locale.US, "LOS Range: %.1f km", slantRangeMeters / 1000.0));
            }
            // Horizontal Range
            if (Double.isNaN(horizontalRangeMeters)) {
                holder.targetItemHorizontalRangeText.setText("Horiz: Error");
            } else if (horizontalRangeMeters < 1000) {
                holder.targetItemHorizontalRangeText.setText(String.format(Locale.US, "Horiz: %.0f m", horizontalRangeMeters));
            } else {
                holder.targetItemHorizontalRangeText.setText(String.format(Locale.US, "Horiz: %.1f km", horizontalRangeMeters / 1000.0));
            }

            // 5. Format and Display Bearing
            holder.targetItemBearingText.setText(String.format(Locale.US, "Bearing: %.0f°", bearing));

        } else {
            // Handle cases where location data is missing
            holder.targetItemSlantRangeText.setText("LOS Range: N/A");
            holder.targetItemHorizontalRangeText.setText("Horiz: N/A");
            holder.targetItemBearingText.setText("Bearing: ---°");
        }


        // --- Populate Left Section (Icon) ---
        // Set icon color based on last seen time
        long timeSinceLastSeen = System.currentTimeMillis() - lastSeen; // Use already fetched lastSeen
        int iconColor;
        // Define time thresholds (e.g., in milliseconds)
        final long ONE_MINUTE_MS = 60 * 1000;
        final long FIVE_MINUTES_MS = 5 * ONE_MINUTE_MS;

        if (lastSeen <= 0) { // Never seen or invalid time
            iconColor = 0xFFFF0000; // Red
        } else if (timeSinceLastSeen < ONE_MINUTE_MS) { // Less than 1 minute
            iconColor = 0xFF00FF00; // Green
        } else if (timeSinceLastSeen < FIVE_MINUTES_MS) { // Less than 5 minutes
            iconColor = 0xFFFFFF00; // Yellow
        } else { // 5 minutes or older
            iconColor = 0xFFFF0000; // Red
        }
        holder.targetItemIcon.setColorFilter(iconColor); // Apply color filter


        // --- Set Listeners ---
        // Reset Button Listener
        holder.targetItemResetButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onResetTarget(target);
                Log.d(TAG,"Reset button clicked for target: " + target.getId()); // Use Log.d for debug
            }
        });

        // Item Click Listener (for showing details/locating)
        convertView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onLocateTarget(target); // Call the locate/detail method
                Log.d(TAG,"Clicked on target item: " + target.getId()); // Use Log.d for debug
            }
        });

        return convertView;
    }

    /**
     * Updates the list of targets displayed by the adapter.
     * @param newTargets The new list of targets. Clears existing targets first.
     */
    public void updateTargets(List<Target> newTargets) {
        targets.clear();
        if (newTargets != null) {
            targets.addAll(newTargets);
        }
        // Consider sorting targets here if needed, e.g., by ID or distance
        // Collections.sort(targets, Comparator.comparing(Target::getId));
        Log.d(TAG, "Target list updated with " + targets.size() + " targets.");
        notifyDataSetChanged(); // Crucial to update the ListView UI
    }

    // Removed the incorrect calculate3DDistance method.
    // Calculations are now done directly in getView using standard methods.

    /**
     * ViewHolder pattern to improve ListView performance by caching view lookups.
     */
    private static class ViewHolder {
        ImageView targetItemIcon;
        TextView targetItemIdText;
        TextView targetItemHitCountText;
        TextView targetItemLastSeenText;
        TextView targetItemVoltageText;
        // Updated TextView references for ranges
        TextView targetItemSlantRangeText;
        TextView targetItemHorizontalRangeText;
        TextView targetItemBearingText;
        ImageButton targetItemResetButton;
    }
}
