package com.atakmap.android.hitIndicator;

import static com.atakmap.android.maps.MapView.getMapView;

import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.coremap.maps.conversion.EGM96;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.log.Log; // Use ATAK Log

import java.util.Locale;
import java.util.UUID;

/**
 * Example code to create and manage a test marker on the ATAK map
 * demonstrating correct altitude handling (HAE for placement, MSL for display).
 */
public class MarkerCreator {

    private static final String TAG = "MarkerCreator"; // Logging Tag
    private static final String TEST_MARKER_UID = "test-marker-" + UUID.randomUUID().toString();
    private static final String PLUGIN_MAP_GROUP_NAME = "My Plugin Markers"; // Consistent group name

    // Reference to the created marker for removal
    private static Marker testMarker = null;

    /**
     * Creates and adds a marker to the map using HAE for placement
     * and setting metadata for MSL display.
     *
     * @param mapView The active MapView instance.
     */
    public static void createTestMarker(MapView mapView) {
        if (mapView == null) {
            Log.e(TAG, "MapView instance is null. Cannot create marker.");
            return;
        }

        // 1. Define the coordinates and desired MSL altitude
        double latitude = 38.28461;
        double longitude = -77.14389;
        double desiredMSL = 51.0; // The altitude we want displayed (in meters MSL)

        // 2. Calculate the Geoid Undulation (Height of Geoid above Ellipsoid)
        //    This value is negative in most land areas.
        //    You MUST replace this with your actual EGM96 lookup or equivalent.
        //    Example: double undulation = EGM96.getOffset(latitude, longitude);
        //    Based on the initial observation (33.7 HAE -> 68 MSL), the undulation
        //    might be around -34.3m, but use a precise value.
        //    HAE = MSL + Undulation (approximately, signs matter!)
        //    Let's *assume* an undulation value for demonstration.
        //    A typical value for the US East Coast might be around -30m to -35m.
        double undulation = EGM96.getOffset(latitude, longitude);
        //double undulation = -34.3; // *** REPLACE WITH YOUR EGM96.getOffset(latitude, longitude) ***
        Log.d(TAG, "Geoid Undulation: " + undulation + " m at " + latitude + ", " + longitude);

        // 3. Calculate the HAE altitude required for placement
        double haeAltitude = desiredMSL + undulation;
        Log.d(TAG, "Desired MSL: " + desiredMSL + " m");
        Log.d(TAG, "Calculated HAE for placement: " + haeAltitude + " m");

        // 4. Create the GeoPoint using HAE altitude
        //    The default AltitudeReference is HAE if not specified, but being explicit is good.
        GeoPoint point = new GeoPoint(latitude, longitude, haeAltitude, GeoPoint.AltitudeReference.HAE);

        // 5. Check if marker already exists (optional, prevents duplicates if called multiple times)
        MapGroup pluginGroup = findOrCreatePluginGroup(mapView);
        if (pluginGroup == null) {
            Log.e(TAG, "Failed to find or create plugin map group.");
            return; // Exit if group cannot be obtained
        }
        PointMapItem existing = (PointMapItem) pluginGroup.deepFindItem("uid", TEST_MARKER_UID);
        if (existing instanceof Marker) {
            Log.d(TAG, "Test marker already exists. Updating point and metadata.");
            testMarker = (Marker) existing;
            testMarker.setPoint(point); // Update location/altitude
        } else {
            Log.d(TAG, "Creating new test marker with UID: " + TEST_MARKER_UID);
            // 6. Create the PointMapItem (Marker)
            testMarker = new Marker(point, TEST_MARKER_UID); // Use consistent UID

            // 7. Set the marker type (COT type)
            testMarker.setType("a-f-G-U-C");

            // 8. Set other optional properties (like callsign)
            testMarker.setMetaString("callsign", "Test Marker");
            testMarker.setTitle("Test Marker"); // Sets the label shown on the map

            // 9. Add the marker to the map group
            pluginGroup.addItem(testMarker);
        }

        // 10. IMPORTANT: Set the 'height' metadata to the desired MSL value for display
        testMarker.setMetaDouble("height", desiredMSL);
        Log.d(TAG, "Set marker 'height' metadata to: " + desiredMSL + " m (MSL)");

        // 11. Persist the marker state (important for visibility and saving)
        testMarker.persist(mapView.getMapEventDispatcher(), null, MarkerCreator.class);

        Log.i(TAG, "Test marker created/updated successfully at HAE: " + haeAltitude + " m, displaying MSL: " + desiredMSL + " m");

        // Optional: Pan/Zoom map to the marker location
        // mapView.getMapController().panTo(point, true);
    }

    /**
     * Removes the test marker created by createTestMarker.
     *
     * @param mapView The active MapView instance.
     */
    public static void removeTestMarker(MapView mapView) {
        if (mapView == null) {
            Log.e(TAG, "MapView instance is null. Cannot remove marker.");
            return;
        }

        MapGroup pluginGroup = findOrCreatePluginGroup(mapView);
        if (pluginGroup == null) {
            Log.e(TAG, "Plugin map group not found. Cannot remove marker.");
            return; // Cannot find group to remove from
        }

        // Find the marker by UID within the specific group
        PointMapItem item = (PointMapItem) pluginGroup.deepFindItem("uid", TEST_MARKER_UID);
        if (item instanceof Marker) {
            Log.d(TAG, "Removing test marker with UID: " + TEST_MARKER_UID);
            pluginGroup.removeItem(item);
            testMarker = null; // Clear the reference
        } else {
            Log.d(TAG, "Test marker with UID " + TEST_MARKER_UID + " not found in group " + PLUGIN_MAP_GROUP_NAME);
            // Also clear local reference if somehow it exists but map item doesn't
            if (item == null) testMarker = null;
        }
    }

    /**
     * Finds or creates the map group for this plugin's markers.
     * @param mapView The MapView instance.
     * @return The MapGroup or null if the root group is unavailable.
     */
    private static MapGroup findOrCreatePluginGroup(MapView mapView) {
        MapGroup rootGroup = mapView.getRootGroup();
        if (rootGroup == null) {
            Log.e(TAG, "Root MapGroup is null.");
            return null;
        }

        MapGroup pluginGroup = rootGroup.findMapGroup(PLUGIN_MAP_GROUP_NAME);
        if (pluginGroup == null) {
            pluginGroup = rootGroup.addGroup(PLUGIN_MAP_GROUP_NAME);
            if (pluginGroup != null) {
                // Set properties for the new group
                pluginGroup.setMetaBoolean("editable", true);
                pluginGroup.setMetaBoolean("visible", true);
                // Persist group properties if needed
                //pluginGroup.get(mapView.getMapEventDispatcher(), null, MarkerCreator.class);
                Log.d(TAG, "Created plugin map group: " + PLUGIN_MAP_GROUP_NAME);
            } else {
                Log.e(TAG, "Failed to create plugin map group: " + PLUGIN_MAP_GROUP_NAME);
            }
        }
        return pluginGroup;
    }

}


