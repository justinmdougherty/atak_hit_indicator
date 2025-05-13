package com.atakmap.android.hitIndicator;

import static com.atakmap.android.maps.MapView.getMapView;
import static com.atakmap.coremap.maps.conversion.EGM96.*;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;

import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.user.PlacePointTool;
import com.atakmap.coremap.maps.conversion.EGM96;
import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;

import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.coremap.log.Log;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import com.atakmap.android.hitIndicator.ElevationProfileView;


/**
 * Main UI controller for the Hit Indicator plugin.
 */
public class HitIndicatorDropDownReceiver extends DropDownReceiver implements
        DropDown.OnStateListener,
        BluetoothSerialManager.BluetoothSerialListener,
        MessageParser.MessageListener,
        TargetListAdapter.TargetActionListener,
        BluetoothDeviceAdapter.DeviceConnectListener {

    private static final String TAG = "HitIndicatorDropDownReceiver";
    public static final String SHOW_PLUGIN = "com.atakmap.android.hitIndicator.SHOW_PLUGIN";

    private static final String TARGET_MAP_GROUP_NAME = "Target Markers";

    // View states
    private static final int VIEW_MAIN = 0;
    private static final int VIEW_SETTINGS = 1;
    private static final int VIEW_DETAIL = 2;

    private final View mainView;
    private final View settingsView;
    private final View detailView;
    private final Context pluginContext;
    private final TargetManager targetManager;
    private final MapView mapView;

    private int currentView = VIEW_MAIN;

    // Main UI components
    private TextView statusText;
    private ListView targetListView;
    private ImageButton settingsButton;

    private ImageButton refreshButton;
    private TargetListAdapter targetAdapter;


    // Settings UI components
    private TextView connectionStatusText;
    private Button scanDevicesButton;
    private Button scanTargetsButton;
    private Button resetAllTargetsButton;
    private Button removeAllTargetsButton;
    private Button calibrateAllButton;
    private Button backButtonSettings;
    private ListView deviceListView;
    private BluetoothDeviceAdapter deviceAdapter;

    // Detail View UI components
    private TextView detailTargetIdText;
    private TextView detailRangeText;
    private TextView detailBearingText;
    private TextView detailVerticalOffsetText;
    private TextView detailTiltAngleText;
    private TextView elevProfileCaptionText;
    private ElevationProfileView elevationProfileView;
    private TextView detailTargetElevationText;
    private TextView detailSelfElevationText;

    private TextView detailVoltageText;
    private Button detailBackButton;

    // Communication components
    private BluetoothSerialManager bluetoothManager;
    private MessageParser messageParser;

    // Calibration variables
    private String currentCalibrationTargetId;
    private long calibrationStartTime;

    // Map markers for targets
    private final Map<String, Marker> targetMarkers = new HashMap<>();

    /**
     * Constructor
     */
    public HitIndicatorDropDownReceiver(MapView mapView, Context context, TargetManager targetManager) {
        super(mapView);
        this.mapView = mapView;
        this.pluginContext = context;
        this.targetManager = targetManager;

        // Inflate views
        this.mainView = View.inflate(context, R.layout.hit_indicator_main, null);
        this.settingsView = View.inflate(context, R.layout.hit_indicator_settings, null);
        Context themed = new ContextThemeWrapper(pluginContext, R.style.ATAKPluginTheme);
        this.detailView = LayoutInflater.from(themed)
                .inflate(R.layout.target_detail_view, null);

        //this.detailView = View.inflate(context, R.layout.target_detail_view, null);

        // Initialize UI components
        initMainUI();
        initSettingsUI();
        initDetailUI();

        // Initialize Bluetooth manager and message parser
        bluetoothManager = new BluetoothSerialManager(mapView.getContext(), this);
        messageParser = new MessageParser(this);
    }

    /**
     * Initialize main UI components
     */
    private void initMainUI() {
        try {
            statusText = mainView.findViewById(R.id.statusText);
            targetListView = mainView.findViewById(R.id.targetListView);
            settingsButton = mainView.findViewById(R.id.settingsButton);
            scanDevicesButton = mainView.findViewById(R.id.scanDevicesButton);
            refreshButton = mainView.findViewById(R.id.refreshButton);

            // Set click listeners
            if (settingsButton != null) {
                settingsButton.setOnClickListener(v -> showSettingsView());
            } else {
                Log.e(TAG, "MainUI: settingsButton not found");
            }

            if (scanTargetsButton != null) {
                scanTargetsButton.setOnClickListener(v -> queryTargets());
            } else {
                Log.e(TAG, "SettingsUI: scanTargetsButton not found");
            }
            if (refreshButton != null) {
                refreshButton.setOnClickListener(v -> queryTargets());
                Log.d(TAG, "Refresh button clicked");
            } else {
                Log.e(TAG, "Could not find refreshButton in the layout.");
            }

            targetAdapter = new TargetListAdapter(pluginContext, this);
            if (targetListView != null) {
                targetListView.setAdapter(targetAdapter);
                targetListView.setOnItemClickListener((parent, view, position, id) -> {
                    Target selectedTarget = targetAdapter.getItem(position);
                    if (selectedTarget != null) {
                        showDetailView(selectedTarget);
                    }
                });
            } else {
                Log.e(TAG, "MainUI: targetListView not found");
            }
            updateTargetList();

        } catch (Exception e) {
            Log.e(TAG, "Error initializing main UI", e);
        }
    }

    /**
     * Initialize settings UI components
     */
    private void initSettingsUI() {
        try {
            connectionStatusText = settingsView.findViewById(R.id.connectionStatusText);
            scanDevicesButton = settingsView.findViewById(R.id.scanDevicesButton);
            scanTargetsButton = settingsView.findViewById(R.id.scanTargetsButton);
            resetAllTargetsButton = settingsView.findViewById(R.id.resetAllTargetsButton);
            removeAllTargetsButton = settingsView.findViewById(R.id.removeAllTargetsButton);
            calibrateAllButton = settingsView.findViewById(R.id.calibrateAllButton);
            backButtonSettings = settingsView.findViewById(R.id.backButton);
            deviceListView = settingsView.findViewById(R.id.deviceListView);
            detailVerticalOffsetText = detailView.findViewById(R.id.detailVerticalOffsetText);
            detailTiltAngleText      = detailView.findViewById(R.id.detailTiltAngleText);
            elevProfileCaptionText   = detailView.findViewById(R.id.elevProfileCaptionText);
            elevationProfileView     = detailView.findViewById(R.id.elevationProfileView);
            detailTargetElevationText = detailView.findViewById(R.id.detailTargetElevationText);
            detailSelfElevationText   = detailView.findViewById(R.id.detailSelfElevationText);

            // Set click listeners
            if (scanDevicesButton != null) {
                scanDevicesButton.setOnClickListener(v -> scanBluetoothDevices());
            } else {
                Log.e(TAG, "SettingsUI: scanDevicesButton not found");
            }

            if (scanTargetsButton != null) {
                scanTargetsButton.setOnClickListener(v -> queryTargets());
            } else {
                Log.e(TAG, "SettingsUI: scanTargetsButton not found");
            }

            if (resetAllTargetsButton != null) {
                resetAllTargetsButton.setOnClickListener(v -> resetAllTargets());
            } else {
                Log.e(TAG, "SettingsUI: resetAllTargetsButton not found");
            }

            if (removeAllTargetsButton != null) {
                removeAllTargetsButton.setOnClickListener(v -> removeAllTargets());
            } else {
                Log.e(TAG, "SettingsUI: removeAllTargetsButton not found");
            }

            if (calibrateAllButton != null) {
                calibrateAllButton.setOnClickListener(v -> calibrateAllTargets());
            } else {
                Log.e(TAG, "SettingsUI: calibrateAllButton not found");
            }

            if (backButtonSettings != null) {
                backButtonSettings.setOnClickListener(v -> showMainView());
            } else {
                Log.e(TAG, "SettingsUI: backButton not found!");
            }

            if (deviceListView != null) {
                deviceAdapter = new BluetoothDeviceAdapter(pluginContext, this);
                deviceListView.setAdapter(deviceAdapter);
            } else {
                Log.e(TAG, "SettingsUI: deviceListView not found");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error initializing settings UI", e);
        }
    }

    /**
     * Initialize detail view UI components
     */
    private void initDetailUI() {
        try {
            detailTargetIdText       = detailView.findViewById(R.id.detailTargetIdText);
            detailRangeText          = detailView.findViewById(R.id.detailRangeText);
            detailBearingText        = detailView.findViewById(R.id.detailBearingText);
            detailVerticalOffsetText = detailView.findViewById(R.id.detailVerticalOffsetText);
            detailTiltAngleText      = detailView.findViewById(R.id.detailTiltAngleText);
            detailVoltageText        = detailView.findViewById(R.id.detailVoltageText);
            elevProfileCaptionText   = detailView.findViewById(R.id.elevProfileCaptionText);
            elevationProfileView     = detailView.findViewById(R.id.elevationProfileView);
            detailBackButton         = detailView.findViewById(R.id.detailBackButton);


            if (detailBackButton != null) {
                detailBackButton.setOnClickListener(v -> showMainView());
            } else {
                Log.e(TAG, "DetailUI: detailBackButton not found!");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing detail UI", e);
        }
    }


    // --- View Switching Logic ---

    /**
     * Show the main list view.
     */
    private void showMainView() {
        GeoPoint selfPoint = mapView.getSelfMarker().getPoint();
        if (selfPoint != null && targetAdapter != null) {
            targetAdapter.setMyLocation(selfPoint);
        }
        try {
            Log.d(TAG, "Showing main view");
            currentView = VIEW_MAIN;
            showDropDown(mainView, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, true, this);
            updateTargetList();
        } catch (Exception e) {
            Log.e(TAG, "Error showing main view", e);
        }
    }

    /**
     * Show the settings view.
     */
    private void showSettingsView() {
        try {
            Log.d(TAG, "Showing settings view");
            currentView = VIEW_SETTINGS;
            showDropDown(settingsView, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, FULL_HEIGHT, true, this);
            updateConnectionStatus();
            loadPairedDevices();
        } catch (Exception e) {
            Log.e(TAG, "Error showing settings view", e);
        }
    }

    private void drawLineToTarget(Target target) {
        if (target == null || target.getLocation() == null) return;

        try {
            // Get the target location
            GeoPoint targetLocation = target.getLocation();

            // Create a line marker or use an existing ATAK function to draw a line
            // You might need to check ATAK documentation for the specific method

            // For now, at least pan to show the target
            mapView.getMapController().panTo(targetLocation, true);

            // Log that we're trying to draw a line
            Log.d(TAG, "Attempting to draw line to target: " + target.getId());
        } catch (Exception e) {
            Log.e(TAG, "Error drawing line to target", e);
        }
    }

    /**
     * Horizontal (2D) distance on WGS‑84 ellipsoid, in meters.
     */
    private double groundDistance(GeoPoint p1, GeoPoint p2) {
        float[] results = new float[1];
        Location.distanceBetween(
                p1.getLatitude(),  p1.getLongitude(),
                p2.getLatitude(),  p2.getLongitude(),
                results
        );
        return results[0];
    }

    /**
     * Show the detail view for a specific target.
     */

    public void showDetailView(Target target) {
        if (target == null) {
            Log.w(TAG, "Cannot show detail view for null target");
            return;
        }

        // Check if detail view components are ready
        if (detailView == null || detailTargetIdText == null) {
            Log.e(TAG, "Detail view not properly initialized.");
            showToast("UI Error showing details.");
            return;
        }

        Log.d(TAG, "Showing detail view for target: " + target.getId());
        currentView = VIEW_DETAIL;

        // Populate Detail View Fields
        populateDetailView(target);
        drawLineToTarget(target);

        // Show the populated view with HALF_HEIGHT instead of FULL_HEIGHT
        try {
            showDropDown(detailView, HALF_WIDTH, HALF_HEIGHT, FULL_WIDTH, HALF_HEIGHT, false, this);
        } catch (Exception e) {
            Log.e(TAG, "Error showing detail view", e);
        }
        GeoPoint targetLocation = target.getLocation();
        if (targetLocation != null) {
            // Center on target
            mapView.getMapController().panTo(targetLocation, true);
        }

    }

    private void populateDetailView(Target target) {
        // 1) Target ID
        detailTargetIdText.setText("Target ID: " + target.getId());

        // 2) Grab points
        GeoPoint me = mapView.getSelfMarker().getPoint();
        GeoPoint tgt = target.getLocation();
        if (me == null || tgt == null) {
            detailRangeText.setText("Range:            —");
            detailBearingText.setText("Bearing:          —");
            detailVerticalOffsetText.setText("Vertical Offset:  —");
            detailTiltAngleText.setText("Tilt Angle:       —");
            detailVoltageText.setText("Voltage:          —");
            elevProfileCaptionText.setText("");
            return;
        }

        // Inside populateDetailView(Target target) method...

        // ... (Keep steps 1, 2, 3 as they are for calculating ground, slant, bearing etc.)

        // 3) Compute distances & angles
        double ground = horizontalDistance(me, tgt); // Assuming horizontalDistance is defined
        double slant = slantDistance(me, tgt);    // Assuming slantDistance is defined
        double bearing = me.bearingTo(tgt);
        if (bearing < 0) bearing += 360;

        // --- Determine MSL Altitudes ---
        // Target MSL (Assuming tgt.getAltitude() IS the raw MSL value)
        double targetMSL = Double.NaN;
        if (tgt != null && !Double.isNaN(tgt.getAltitude())) {
            targetMSL = tgt.getAltitude();
        } else {
            Log.w(TAG, "Target altitude is null or NaN in populateDetailView.");
            // Handle error - maybe clear profile and return?
            elevationProfileView.clearProfileData();
            // Set text fields to indicate error?
            return;
        }

        // Self MSL (Using the helper function logic)
        double selfMSL = getSelfMSL(me); // Call the helper function we discussed
        if (Double.isNaN(selfMSL)) {
            Log.w(TAG, "Could not determine self MSL altitude in populateDetailView.");
            // Handle error - maybe clear profile and return?
            elevationProfileView.clearProfileData();
            // Set text fields to indicate error?
            return;
        }


        // Use MSL altitudes for Vertical Offset and Tilt calculations for accuracy
        double vertOffMSL = targetMSL - selfMSL;
        double tiltMSL = Math.toDegrees(Math.atan2(vertOffMSL, ground));
        double voltage = target.getBatteryVoltage();


        // 4) Populate fields (using MSL-based calculations where appropriate)
        detailRangeText.setText(
                String.format(Locale.US, "Range:            %.1f m", slant)); // Slant range is geometric
        detailBearingText.setText(
                String.format(Locale.US, "Bearing:          %.1f°", bearing));

        // Add Target Elevation display
        // Assumes you have: TextView detailTargetElevationText;
        if (detailTargetElevationText != null) {
            detailTargetElevationText.setText(
                    String.format(Locale.US, "Target Elev:      %.1f m MSL", targetMSL));
        }

        // Add Self (Firing Line) Elevation display
        // Assumes you have: TextView detailSelfElevationText;
        if (detailSelfElevationText != null) {
            detailSelfElevationText.setText(
                    String.format(Locale.US, "Firing Ln Elev:   %.1f m MSL", selfMSL));
        }

        // Existing fields using MSL difference and tilt
        detailVerticalOffsetText.setText(
                String.format(Locale.US, "Vertical Offset:  %+,.1f m", vertOffMSL)); // Use MSL difference
        detailTiltAngleText.setText(
                String.format(Locale.US, "Tilt Angle:       %.1f°", tiltMSL)); // Use MSL-based tilt
        detailVoltageText.setText(
                String.format(Locale.US, "Voltage:          %.2f V", voltage));

        // 5) Elevation-profile caption (using MSL-based values)
        elevProfileCaptionText.setText(
                String.format(Locale.US,
                        "Climb %+,.1f m over %.1f m → Tilt %.1f°",
                        vertOffMSL, ground, tiltMSL));

        // 6) Draw the chart using the MSL altitudes and horizontal distance
        //    Call the correct method: setProfileData
        Log.d(TAG, String.format("Calling setProfileData with: SelfMSL=%.1f, TargetMSL=%.1f, Dist=%.1f", selfMSL, targetMSL, ground));
        elevationProfileView.setProfileData(selfMSL, targetMSL, ground);
    }


    /**
     * Helper function to get Self MSL robustly.
     * Compatible with older SDKs lacking AltitudeReference.MSL.
     * Needs access to MapView and EGM96 calculation.
     * @param selfGeoPoint The GeoPoint of the self marker.
     * @return MSL altitude in meters, or Double.NaN if cannot be determined reliably.
     */
    private double getSelfMSL(GeoPoint selfGeoPoint) {
        if (selfGeoPoint == null) {
            Log.w(TAG, "getSelfMSL: Input GeoPoint is null.");
            return Double.NaN;
        }

        MapView mapView = getMapView(); // Or however you access MapView
        Marker selfMarker = (mapView != null) ? mapView.getSelfMarker() : null;
        double selfMSL = Double.NaN;

        // 1. Try getting MSL from marker metadata first (most reliable source)
        if (selfMarker != null && selfMarker.hasMetaValue("height")) {
            selfMSL = selfMarker.getMetaDouble("height", Double.NaN);
            if (!Double.isNaN(selfMSL)) {
                Log.d(TAG, "getSelfMSL: Used 'height' metadata: " + selfMSL);
                return selfMSL; // Return MSL from metadata
            } else {
                Log.w(TAG, "getSelfMSL: 'height' metadata exists but is NaN.");
            }
        } else {
            Log.d(TAG, "getSelfMSL: No 'height' metadata found on self marker.");
        }

        // 2. If no valid metadata, use the GeoPoint altitude *only if reference is HAE*
        double altitude = selfGeoPoint.getAltitude();
        if (Double.isNaN(altitude)) {
            Log.w(TAG, "getSelfMSL: Self GeoPoint altitude is NaN.");
            return Double.NaN; // Altitude value itself is invalid
        }

        GeoPoint.AltitudeReference ref = selfGeoPoint.getAltitudeReference();

        // Check if the reference is explicitly HAE
        if (ref == GeoPoint.AltitudeReference.HAE) {
            // Calculate MSL from HAE using the known working call
            double undulation = EGM96.getOffset(selfGeoPoint.getLatitude(), selfGeoPoint.getLongitude()); // <<< USE THE ACTUAL CALL HERE
            if (!Double.isNaN(undulation)) {
                selfMSL = altitude - undulation;
                Log.d(TAG, "getSelfMSL: Calculated from HAE: " + selfMSL);
            } else {
                Log.e(TAG, "getSelfMSL: EGM96 lookup returned NaN for self location.");
                selfMSL = Double.NaN; // Indicate failure
            }
        }
        // If reference is NOT HAE
        else {
            Log.w(TAG, "getSelfMSL: Self GeoPoint altitude reference is not HAE ("
                    + (ref == null ? "null" : ref.name())
                    + "). Cannot determine MSL from GeoPoint altitude.");
            selfMSL = Double.NaN; // Return NaN as we can't be sure
        }

        return selfMSL;
    }


        private double horizontalDistance(GeoPoint p1, GeoPoint p2) {
            // Implementation using p1.distanceTo(p2) or Haversine formula
            if (p1 == null || p2 == null) return 0;
            return p1.distanceTo(p2); // This calculates slant distance in core, need actual horizontal if required
            // For true horizontal (approximated), you might need more complex geo math
            // or just use slant distance if the difference is negligible for your use case.
            // A simple approximation if elevation diff is small compared to distance:
            // return Math.sqrt(Math.pow(p1.distanceTo(p2), 2) - Math.pow(p1.getAltitude() - p2.getAltitude(), 2));
            // Let's assume p1.distanceTo is sufficient for now unless specified otherwise.
        }

        private double slantDistance(GeoPoint p1, GeoPoint p2) {
            if (p1 == null || p2 == null) return 0;
            // Use the built-in distanceTo which calculates slant distance
            return p1.distanceTo(p2);
        }


    // --- UI Update Helpers ---

    /**
     * Update the connection status display in Settings.
     */
    private void updateConnectionStatus() {
        if (connectionStatusText != null) {
            if (bluetoothManager != null && bluetoothManager.isConnected()) {
                BluetoothDevice device = bluetoothManager.getConnectedDevice();
                String deviceName = "Unknown Device";
                try {
                    if (device != null) {
                        deviceName = device.getName();
                        if (deviceName == null || deviceName.isEmpty()) {
                            deviceName = device.getAddress();
                        }
                    }
                } catch (SecurityException se) {
                    Log.e(TAG, "BT Permission error", se);
                    deviceName = "Permission Error";
                }
                connectionStatusText.setText("Connected to: " + deviceName);
                connectionStatusText.setTextColor(0xFF008800); // Greenish
            } else {
                connectionStatusText.setText("Not connected");
                connectionStatusText.setTextColor(0xFFFF0000); // Red
            }
        } else {
            Log.w(TAG, "connectionStatusText is null");
        }
    }

    /**
     * Update the target list adapter data. Runs on UI thread.
     */
    private void updateTargetList() {
        if (targetAdapter != null && targetManager != null) {
            List<Target> targets = targetManager.getAllTargets();
            mapView.post(() -> {
                if (targetAdapter != null) {
                    targetAdapter.updateTargets(targets);
                }
            });
        } else {
            if (targetAdapter == null) Log.w(TAG, "targetAdapter null in updateTargetList");
            if (targetManager == null) Log.w(TAG, "targetManager null in updateTargetList");
        }
    }

    /**
     * Update the status text view on the UI thread.
     */
    private void updateStatus(final String status) {
        if (statusText != null) {
            statusText.post(() -> statusText.setText(status));
        }
        Log.d(TAG, "Status: " + status);
    }

    /**
     * Show a toast message on the UI thread.
     */
    private void showToast(final String message) {
        mapView.post(() -> Toast.makeText(mapView.getContext(), message, Toast.LENGTH_SHORT).show());
    }

    // --- Bluetooth & Target Actions ---

    /**
     * Load paired Bluetooth devices into the settings list.
     */
    private void loadPairedDevices() {
        if (deviceAdapter != null && bluetoothManager != null) {
            deviceAdapter.clear();
            if (bluetoothManager.isBluetoothSupported() && bluetoothManager.isBluetoothEnabled()) {
                try {
                    List<BluetoothDevice> pairedDevices = bluetoothManager.getPairedDevices();
                    if (pairedDevices != null && !pairedDevices.isEmpty()) {
                        for (BluetoothDevice device : pairedDevices) {
                            deviceAdapter.addDevice(device);
                        }
                    } else {
                        Log.d(TAG, "No paired devices.");
                    }
                } catch (SecurityException e) {
                    Log.e(TAG, "BT permission missing", e);
                    showToast("Bluetooth permission error");
                }
            } else {
                updateStatus("Bluetooth off/unavailable");
            }
            deviceAdapter.notifyDataSetChanged();
        }
    }

    /**
     * Scan for nearby Bluetooth devices.
     */
    private void scanBluetoothDevices() {
        Log.d(TAG, "scanBluetoothDevices called");
        if (bluetoothManager == null) {
            Log.e(TAG, "BT Manager null");
            showToast("BT Error");
            return;
        }
        if (!bluetoothManager.isBluetoothSupported()) {
            showToast("Bluetooth not supported");
            return;
        }
        if (!bluetoothManager.isBluetoothEnabled()) {
            showToast("Please enable Bluetooth");
            return;
        }

        if (deviceAdapter == null) {
            deviceAdapter = new BluetoothDeviceAdapter(pluginContext, this);
            if (deviceListView != null) {
                deviceListView.setAdapter(deviceAdapter);
            } else {
                Log.e(TAG, "deviceListView is null");
                showToast("UI Error");
                return;
            }
        }

        deviceAdapter.clear();
        updateStatus("Scanning for BT devices...");
        try {
            loadPairedDevices(); // Show paired first
            bluetoothManager.startDiscovery(); // Scan for new
        } catch (SecurityException e) {
            Log.e(TAG, "BT permission missing", e);
            showToast("Bluetooth permission error");
        }
    }

    /**
     * Send QUERY message via Bluetooth.
     */
    private void queryTargets() {
        if (bluetoothManager == null || !bluetoothManager.isConnected()) {
            showToast("Not connected");
            return;
        }
        updateStatus("Scanning for targets...");
        if (!bluetoothManager.writeData(MessageParser.createQueryMessage())) {
            showToast("Send error");
            updateStatus("Error sending scan");
        }
    }

    /**
     * Reset hit counts for all targets.
     */
    private void resetAllTargets() {
        Log.d(TAG, "Resetting all hit counts");
        if (targetManager != null) {
            targetManager.resetAllHitCounts();
            updateStatus("All hit counts reset");
            updateTargetList();
            for (Target target : targetManager.getAllTargets()) {
                mapView.post(() -> updateTargetMarker(target));
            }
            showMainView();
        } else {
            Log.e(TAG, "targetManager null, cannot reset");
        }
    }

    /**
     * Remove all targets from manager and map.
     */
    private void removeAllTargets() {
        Log.d(TAG, "Removing all targets");
        if (!targetMarkers.isEmpty() && mapView != null && mapView.getRootGroup() != null) {
            for (Marker marker : targetMarkers.values()) {
                try {
                    mapView.getRootGroup().removeItem(marker);
                } catch (Exception e) {
                    Log.e(TAG, "Error removing marker", e);
                }
            }
            targetMarkers.clear();
        }
        if (targetManager != null) {
            targetManager.clearTargets();
            updateStatus("All targets removed");
            updateTargetList();
            showMainView();
        } else {
            Log.e(TAG, "targetManager null, cannot remove");
        }
    }

    /**
     * Start calibration process (simple version: calibrates first target).
     */
    private void calibrateAllTargets() {
        if (bluetoothManager == null || !bluetoothManager.isConnected()) {
            showToast("Not connected");
            return;
        }
        if (targetManager == null) {
            Log.e(TAG, "targetManager null");
            return;
        }
        List<Target> targets = targetManager.getAllTargets();
        if (targets.isEmpty()) {
            updateStatus("No targets to calibrate");
            return;
        }
        updateStatus("Starting calibration...");
        calibrateTarget(targets.get(0).getId());
    }

//    private void clearTargetLine() {
//        // Find and remove any target lines
//        mapView.getMapGroup().deepRemoveItems("Path to*");
//    }

    /**
     * Start calibration for a specific target ID.
     */
    private void calibrateTarget(String targetId) {
        if (targetId == null || targetId.isEmpty()) {
            Log.w(TAG, "Cannot calibrate null/empty ID");
            return;
        }
        if (bluetoothManager == null || !bluetoothManager.isConnected()) {
            showToast("Not connected");
            return;
        }
        currentCalibrationTargetId = targetId;
        calibrationStartTime = System.currentTimeMillis();
        updateStatus("Calibrating target " + targetId + "...");
        bluetoothManager.writeData(MessageParser.createCalibrationMessage(targetId));
    }

    // --- Map Marker Logic ---


    /**
     * Refactored function to plot or update a map marker for a target,
     * using HAE for placement and MSL metadata for display.
     * Creates/updates only if location is valid. Removes marker if location becomes invalid.
     *
     * Assumes the containing class has:
     * - private MapView mapView;
     * - private Map<String, Marker> targetMarkers; // e.g., HashMap
     * - private static final String TARGET_MAP_GROUP_NAME = "Target Markers"; // Or your preferred name
     * - private static final String TAG = "YourClassName"; // Set your class TAG
     */
    private void updateTargetMarker(Target target) {
        // Ensure necessary members are available (add null checks if needed in your context)
        // if (mapView == null || targetMarkers == null) {
        //     Log.e(TAG, "MapView or targetMarkers map is null!");
        //     return;
        // }
        if (target == null) {
            Log.w(TAG, "updateTargetMarker called with null target.");
            return;
        }

        String targetId = target.getId();
        String markerUID = "target_" + targetId; // Consistent UID for the marker

        // 1) Get the root map group and find/create the dedicated group for targets
        MapGroup targetGroup = findOrCreateMapGroup(mapView, TARGET_MAP_GROUP_NAME);
        if (targetGroup == null) {
            Log.e(TAG, "Failed to find or create target map group: " + TARGET_MAP_GROUP_NAME);
            return; // Cannot proceed without a map group
        }

        // 2) Grab the raw GPS point (expecting MSL altitude)
        GeoPoint rawGeo = target.getLocation();
        Log.d(TAG, "Target ID: " + targetId + " | Raw Location: " + rawGeo);
        boolean locationValid = rawGeo != null && GeoPoint.isValid(rawGeo.getLatitude(), rawGeo.getLongitude()); // More robust validity check

        if (!locationValid) {
            Log.d(TAG, "Target location invalid or null for ID: " + targetId + ". Removing marker.");
            // Remove existing marker from the map group and the tracking map
            Marker oldMarker = targetMarkers.remove(targetId);
            if (oldMarker != null) {
                targetGroup.removeItem(oldMarker);
                Log.d(TAG, "Removed marker with UID: " + oldMarker.getUID());
            } else {
                // Attempt removal by UID directly from group just in case map is out of sync
                PointMapItem item = (PointMapItem) targetGroup.deepFindItem("uid", markerUID);
                if(item instanceof Marker) {
                    targetGroup.removeItem(item);
                    Log.d(TAG, "Removed marker by UID lookup: " + markerUID);
                }
            }
            return; // Exit after removal
        }

        // Location is valid, proceed with marker creation/update
        double lat = rawGeo.getLatitude();
        double lon = rawGeo.getLongitude();
        double rawMSL = rawGeo.getAltitude(); // Assuming this is MSL from your target source
        Log.d(TAG, "Target ID: " + targetId + " | Raw Location: " + lat + ", " + lon + " | Raw MSL: " + rawMSL + " m");

        // 3) Compute geoid undulation (meters) using your EGM96 implementation
        double undulation = EGM96.getOffset(lat, lon); // Make sure EGM96 class/method is accessible
        Log.d(TAG, "Target ID: " + targetId + " | Geoid Undulation: " + undulation + " m");

        // 4) Calculate HAE altitude = MSL + undulation
        double haeAltitude = rawMSL + undulation;
        Log.d(TAG, "Target ID: " + targetId + " | Calculated HAE: " + haeAltitude + " m");

        // 5) Build the GeoPoint using HAE for placement
        GeoPoint placementPoint = new GeoPoint(lat, lon, haeAltitude, GeoPoint.AltitudeReference.HAE);
        Log.d(TAG, "Target ID: " + targetId + " | Placement Point (HAE): " + placementPoint);

        // 6) Create or update the marker
        Marker marker = targetMarkers.get(targetId);
        if (marker == null) {
            // Marker doesn't exist in our tracking map, try finding it in the group first
            PointMapItem existingItem = (PointMapItem) targetGroup.deepFindItem("uid", markerUID);
            if (existingItem instanceof Marker) {
                Log.d(TAG,"Found existing marker in group for " + targetId + ", updating reference.");
                marker = (Marker) existingItem;
                targetMarkers.put(targetId, marker); // Add to tracking map
                marker.setPoint(placementPoint); // Update position
            } else {
                Log.d(TAG, "Creating new marker for " + targetId + " with UID: " + markerUID);
                marker = new Marker(placementPoint, markerUID); // Use consistent UID
                marker.setType("a-f-G-U-C"); // Set your desired COT type
                marker.setMetaString("callsign", "Target " + targetId); // Set callsign if desired
                // marker.setMetaString("how", "h-g-i-g-o"); // Set 'how' if needed

                targetGroup.addItem(marker); // Add to the dedicated map group
                targetMarkers.put(targetId, marker); // Track the new marker
            }

        } else {
            // Marker exists in our tracking map, update its point
            Log.d(TAG, "Updating existing marker for " + targetId);
            marker.setPoint(placementPoint);
        }

        // 7) IMPORTANT: Set the 'height' metadata to the raw MSL for display
        marker.setMetaDouble("height", rawMSL);
        Log.d(TAG, "Target ID: " + targetId + " | Set marker 'height' metadata to: " + rawMSL + " m (MSL)");

        // 8) Build and set remarks (Example: Hit count, Elev Diff, Voltage)
        StringBuilder remarks = new StringBuilder("Hits: ").append(target.getHitCount());

        // Calculate elevation difference relative to self (using MSL for comparison)
        Marker selfMarker = mapView.getSelfMarker(); // Or however you get the self marker
        if (selfMarker != null && selfMarker.getPoint() != null) {
            GeoPoint selfPoint = selfMarker.getPoint();
            // Use the 'height' metadata if available for self, otherwise use point altitude (which might be HAE)
            double selfMSL = selfMarker.getMetaDouble("height", selfPoint.getAltitude());
            // If selfPoint altitude was HAE and 'height' wasn't set, this comparison is less accurate.
            // For best results, ensure self marker also has 'height' set to MSL.
            if (selfPoint.getAltitudeReference() == GeoPoint.AltitudeReference.HAE && !selfMarker.hasMetaValue("height")) {
                Log.w(TAG,"Self marker altitude appears to be HAE, elevation difference calculation might be less accurate without MSL height metadata.");
                // Optionally calculate self MSL from HAE if needed: selfMSL = selfPoint.getAltitude() - EGM96.getOffset(selfPoint.getLatitude(), selfPoint.getLongitude());
            }

            double elevDiff = rawMSL - selfMSL;
            Log.d(TAG, "Target ID: " + targetId + " | Self MSL: " + selfMSL + " m | Elev Diff: " + elevDiff + " m");
            remarks.append(String.format(Locale.US, "\nElev diff: %.1f m %s",
                    Math.abs(elevDiff), elevDiff >= 0 ? "Above" : "Below"));
        } else {
            Log.w(TAG,"Could not get self marker location to calculate elevation difference.");
        }

        double voltage = target.getBatteryVoltage();
        if (voltage >= 0) { // Assuming negative voltage is invalid
            remarks.append(String.format(Locale.US, "\nVoltage: %.2f V", voltage));
        }

        marker.setMetaString("remarks", remarks.toString());
        marker.setTitle("Target " + targetId + " (" + target.getHitCount() + ")"); // Update title

        // 9) Persist marker changes
        marker.persist(mapView.getMapEventDispatcher(), null, this.getClass()); // Persist changes

        Log.i(TAG, "Marker updated successfully for Target ID: " + targetId);
    }

    /**
     * Finds or creates a map group. (Helper function, similar to MarkerCreator)
     * @param mapView The MapView instance.
     * @param groupName The desired name for the map group.
     * @return The MapGroup or null if the root group is unavailable.
     */
    private MapGroup findOrCreateMapGroup(MapView mapView, String groupName) {
        MapGroup rootGroup = mapView.getRootGroup();
        if (rootGroup == null) {
            Log.e(TAG, "Root MapGroup is null.");
            return null;
        }

        MapGroup group = rootGroup.findMapGroup(groupName);
        if (group == null) {
            group = rootGroup.addGroup(groupName);
            if (group != null) {
                // Set properties for the new group (optional)
                group.setMetaBoolean("editable", true); // Allow users to interact?
                group.setMetaBoolean("visible", true);
                group.setMetaBoolean("addToObjList", false); // Prevent group itself showing in Overlay Mgr?
                // Persist group properties if needed
                //group.persist(mapView.getMapEventDispatcher(), null, this.getClass());
                Log.d(TAG, "Created map group: " + groupName);
            } else {
                Log.e(TAG, "Failed to create map group: " + groupName);
            }
        }
        return group;
    }




    public BluetoothSerialManager getBluetoothManager() {
        return bluetoothManager;
    }


    // --- Helper Methods ---

    /**
     * Helper method to safely convert an object to double.
     */
    private double safeConvertToDouble(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Double) return (Double) value;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            Log.e(TAG, "Cannot convert '" + value + "' to double.");
            return 0.0;
        }
    }


    /**
     * Clean up resources when the plugin is disposed.
     */
    @Override
    public void disposeImpl() {
        Log.d(TAG, "Disposing HitIndicatorDropDownReceiver");
        if (bluetoothManager != null) {
            bluetoothManager.destroy();
            bluetoothManager = null;
        }
        if (!targetMarkers.isEmpty() && mapView != null && mapView.getRootGroup() != null) {
            for (Marker marker : targetMarkers.values()) {
                try {
                    mapView.getRootGroup().removeItem(marker);
                } catch (Exception e) {
                    Log.e(TAG, "Error removing marker during dispose", e);
                }
            }
            targetMarkers.clear();
        }
        targetAdapter = null;
        deviceAdapter = null;
    }

    //==============================================================================
    // Interface Implementations
    //==============================================================================

    /**************************** DEVICE CONNECT LISTENER METHODS *****************************/
    @Override
    public void onConnectDevice(final BluetoothDevice device) {
        if (device == null || bluetoothManager == null) return;
        if (bluetoothManager.isConnected() && bluetoothManager.getConnectedDevice() != null &&
                bluetoothManager.getConnectedDevice().getAddress().equals(device.getAddress())) {
            Log.d(TAG, "Already connected to this device, disconnecting.");
            bluetoothManager.disconnect();
            return;
        }

        String deviceName;
        try {
            deviceName = device.getName();
            if (deviceName == null || deviceName.isEmpty()) {
                deviceName = device.getAddress();
            }
        } catch (SecurityException e) {
            deviceName = device.getAddress();
        }

        updateStatus("Connecting to " + deviceName + "...");

        new Thread(() -> {
            boolean success = bluetoothManager.connect(device);
            mapView.post(() -> {
                updateConnectionStatus();
                if (!success) {
                    showToast("Connection failed");
                    updateStatus("Connection failed");
                }
            });
        }).start();
    }

    /**************************** BLUETOOTH SERIAL LISTENER METHODS *****************************/
    @Override
    public void onConnected(BluetoothDevice device) {
        String name;
        try {
            name = device.getName();
            if (name == null || name.isEmpty()) {
                name = device.getAddress();
            }
        } catch (SecurityException e) {
            name = "device";
        }
        updateStatus("Connected to " + name);
        mapView.post(this::updateConnectionStatus);
    }

    @Override
    public void onDisconnected() {
        updateStatus("Disconnected");
        mapView.post(this::updateConnectionStatus);
    }

    @Override
    public void onDataReceived(byte[] data) {
        if (messageParser != null) messageParser.processData(data);
    }

    @Override
    public void onError(String message) {
        updateStatus("Comm Error: " + message);
    }

    @Override
    public void onDeviceDiscovered(BluetoothDevice device) {
        if (deviceAdapter != null && device != null) {
            mapView.post(() -> deviceAdapter.addDevice(device));
        }
    }

    @Override
    public void onDiscoveryFinished() {
        updateStatus("Bluetooth scan finished");
    }



    /**************************** MESSAGE PARSER LISTENER METHODS *****************************/
    @Override
    public void onPositionMessage(String id, GeoPoint location, double voltage) {
        if (targetManager == null) return;
        Target target = targetManager.updateTargetPosition(id, location);
        targetManager.updateTargetVoltage(id, voltage);
        mapView.post(() -> {
            updateStatus("Pos: " + id + " V:" + String.format(Locale.US, "%.2f", voltage));
            updateTargetMarker(target);
            updateTargetList();
        });
    }

    @Override
    public void onHitMessage(String id) {
        if (targetManager == null) return;
        Target target = targetManager.processHit(id); // Creates if not exists
        mapView.post(() -> {
            updateStatus("Hit: " + id + " (Total: " + target.getHitCount() + ")");
            updateTargetMarker(target);
            updateTargetList();
        });
    }

    @Override
    public void onCalibrationResponse(String id, long roundTripTime) {
        if (targetManager == null) return;
        if (id != null && id.equals(currentCalibrationTargetId) && calibrationStartTime > 0) {
            long elapsedTime = System.currentTimeMillis() - calibrationStartTime;
            targetManager.setCalibrationTime(id, elapsedTime);
            String status = "Calibrated " + id + ": " + elapsedTime + "ms";
            mapView.post(() -> {
                updateStatus(status);
                Target target = targetManager.getTarget(id);
                if (target != null) updateTargetMarker(target);
                updateTargetList();
            });
            currentCalibrationTargetId = null;
        }
    }

    @Override
    public void onParseError(String error) {
        updateStatus("Parse error: " + error);
    }

    /**************************** DROP DOWN RECEIVER METHODS *****************************/
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action != null && action.equals(SHOW_PLUGIN)) {
            Log.d(TAG, "Received SHOW_PLUGIN intent.");
            showMainView(); // Show main view when triggered externally
        }
    }

    /**************************** DROP DOWN LISTENER METHODS *****************************/
    @Override
    public void onDropDownSelectionRemoved() {
        Log.d(TAG, "DropDown Selection Removed");
    }

    @Override
    public void onDropDownVisible(boolean v) {
        Log.d(TAG, "DropDown visible: " + v);
        if (v) {
            if (currentView == VIEW_MAIN) {
                GeoPoint self = mapView.getSelfMarker().getPoint();
                if (self != null && targetAdapter != null) targetAdapter.setMyLocation(self);
                updateTargetList();
                updateConnectionStatus();
                loadPairedDevices();

            } else if (currentView == VIEW_SETTINGS) {
                updateConnectionStatus();
                loadPairedDevices();

            }
        }
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
        Log.d(TAG, "DropDown Size Changed");
    }

    @Override
    public void onDropDownClose() {
        //clearTargetLine();
        Log.d(TAG, "DropDown Closed");
    }

    /**************************** TARGET ACTION LISTENER METHODS *****************************/
    @Override
    public void onResetTarget(Target target) {
        if (target == null || targetManager == null) return;
        Log.d(TAG, "Resetting hits for " + target.getId());
        targetManager.resetHitCount(target.getId());
        mapView.post(() -> {
            updateStatus("Reset hits for " + target.getId());
            updateTargetMarker(target);
            updateTargetList();
        });
    }


    @Override
    public void onLocateTarget(Target target) {
        if (target == null) {
            showToast("Cannot locate null target.");
            return;
        }

        // This should call showDetailView instead of just panning the map
        showDetailView(target);

        // Optionally, also pan to the target
        GeoPoint loc = target.getLocation();
        if (loc != null && (loc.getLatitude() != 0.0 || loc.getLongitude() != 0.0)) {
            Log.d(TAG, "Locating target " + target.getId());
            mapView.getMapController().panTo(loc, true);
        } else {
            showToast("Target has no valid location.");
        }
    }





}