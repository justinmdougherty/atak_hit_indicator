package com.atakmap.android.hitIndicator;

import static com.atakmap.android.maps.MapView.getMapView;
import static com.atakmap.coremap.maps.conversion.EGM96.*;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;

import androidx.core.content.ContextCompat;

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
        BLEManager.BLEListener,
        MessageParser.MessageListener,
        TargetListAdapter.TargetActionListener,
        BLEDeviceAdapter.DeviceConnectListener {

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
    private Button diagButton;
    private Button scanTargetsButton;
    private Button resetAllTargetsButton;
    private Button removeAllTargetsButton;
    private Button calibrateAllButton;
    private Button generateTestTargetsButton;
    private Button backButtonSettings;
    private ListView deviceListView;
    private BLEDeviceAdapter deviceAdapter;

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
    private TextView detailGpsQualityText;
    private TextView detailBallisticsText;
    private Button detailBackButton;

    // Communication components
    private BLEManager bleManager;
    private MessageParser messageParser;
    private ShotTracker shotTracker; // New shot tracking system

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

        // this.detailView = View.inflate(context, R.layout.target_detail_view, null);

        // Initialize UI components
        initMainUI();
        initSettingsUI();
        initDetailUI();

        // Initialize BLE manager and message parser
        bleManager = new BLEManager(mapView.getContext(), this);
        messageParser = new MessageParser(this);

        // Initialize shot tracker for ballistics calculations
        shotTracker = new ShotTracker(new ShotTracker.ShotTrackerListener() {
            @Override
            public void onShotFired(String targetId, long shotTime) {
                Log.d(TAG, "Shot fired at target: " + targetId);
                updateStatus("Shot fired at " + targetId);

                // Send "expect hit" message to target
                if (bleManager != null && bleManager.hasConnectedDevices()) {
                    bleManager.writeToAllDevices(MessageParser.createShotExpectedMessage(targetId, shotTime));
                }
            }

            @Override
            public void onHitCorrelated(String targetId, BallisticsCalculator.ShotData shotData) {
                Log.d(TAG, "Hit correlated for target: " + targetId +
                        ", ToF: " + shotData.timeOfFlight + "s");
                updateStatus(String.format("Hit confirmed: %s (%.3fs)", targetId, shotData.timeOfFlight));

                // Update target with ballistics data
                Target target = targetManager.getTarget(targetId);
                if (target != null && shotData.ballistics != null) {
                    target.setBallisticsData(shotData.ballistics);
                    target.setAverageTimeOfFlight(shotData.timeOfFlight);
                    updateTargetAdapter();
                }
            }

            @Override
            public void onShotTimeout(String targetId, long shotTime) {
                Log.w(TAG, "Shot timeout for target: " + targetId);
                updateStatus("Shot missed or timeout: " + targetId);
            }

            @Override
            public void onBallisticsCalculated(String targetId, BallisticsCalculator.BallisticsData ballistics) {
                Log.d(TAG, String.format("Ballistics calculated for %s: MV=%.1f m/s, BC=%.3f",
                        targetId, ballistics.muzzleVelocity, ballistics.ballisticCoefficient));

                // Update the target detail view if it's currently showing this target
                refreshDetailView();
            }
        });

        // Update shot tracker with current self position
        updateShotTrackerPosition();
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
            diagButton = settingsView.findViewById(R.id.diagButton);
            scanDevicesButton = settingsView.findViewById(R.id.scanDevicesButton);
            scanTargetsButton = settingsView.findViewById(R.id.scanTargetsButton);
            resetAllTargetsButton = settingsView.findViewById(R.id.resetAllTargetsButton);
            removeAllTargetsButton = settingsView.findViewById(R.id.removeAllTargetsButton);
            calibrateAllButton = settingsView.findViewById(R.id.calibrateAllButton);
            generateTestTargetsButton = settingsView.findViewById(R.id.generateTestTargetsButton);
            backButtonSettings = settingsView.findViewById(R.id.backButton);
            deviceListView = settingsView.findViewById(R.id.deviceListView);
            detailVerticalOffsetText = detailView.findViewById(R.id.detailVerticalOffsetText);
            detailTiltAngleText = detailView.findViewById(R.id.detailTiltAngleText);
            elevProfileCaptionText = detailView.findViewById(R.id.elevProfileCaptionText);
            elevationProfileView = detailView.findViewById(R.id.elevationProfileView);
            detailTargetElevationText = detailView.findViewById(R.id.detailTargetElevationText);
            detailSelfElevationText = detailView.findViewById(R.id.detailSelfElevationText);

            // Set click listeners
            if (diagButton != null) {
                diagButton.setOnClickListener(v -> showBLEDiagnostics());
            } else {
                Log.e(TAG, "SettingsUI: diagButton not found");
            }

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

            if (generateTestTargetsButton != null) {
                generateTestTargetsButton.setOnClickListener(v -> generateTestTargets());
            } else {
                Log.e(TAG, "SettingsUI: generateTestTargetsButton not found");
            }

            if (backButtonSettings != null) {
                backButtonSettings.setOnClickListener(v -> showMainView());
            } else {
                Log.e(TAG, "SettingsUI: backButton not found!");
            }

            if (deviceListView != null) {
                deviceAdapter = new BLEDeviceAdapter(pluginContext, this);
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
            detailTargetIdText = detailView.findViewById(R.id.detailTargetIdText);
            detailRangeText = detailView.findViewById(R.id.detailRangeText);
            detailBearingText = detailView.findViewById(R.id.detailBearingText);
            detailVerticalOffsetText = detailView.findViewById(R.id.detailVerticalOffsetText);
            detailTiltAngleText = detailView.findViewById(R.id.detailTiltAngleText);
            detailVoltageText = detailView.findViewById(R.id.detailVoltageText);
            detailGpsQualityText = detailView.findViewById(R.id.detailGpsQualityText);
            detailBallisticsText = detailView.findViewById(R.id.detailBallisticsText);
            elevProfileCaptionText = detailView.findViewById(R.id.elevProfileCaptionText);
            elevationProfileView = detailView.findViewById(R.id.elevationProfileView);
            detailBackButton = detailView.findViewById(R.id.detailBackButton);

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
        if (target == null || target.getLocation() == null)
            return;

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
                p1.getLatitude(), p1.getLongitude(),
                p2.getLatitude(), p2.getLongitude(),
                results);
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

        // ... (Keep steps 1, 2, 3 as they are for calculating ground, slant, bearing
        // etc.)

        // 3) Compute distances & angles
        double ground = horizontalDistance(me, tgt); // Assuming horizontalDistance is defined
        double slant = slantDistance(me, tgt); // Assuming slantDistance is defined
        double bearing = me.bearingTo(tgt);
        if (bearing < 0)
            bearing += 360;

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

        // Add GPS quality information if available
        if (detailGpsQualityText != null) {
            String gpsQuality = target.getGpsQualitySummary();
            if (!gpsQuality.isEmpty()) {
                detailGpsQualityText.setText("GPS: " + gpsQuality);
                detailGpsQualityText.setVisibility(View.VISIBLE);
            } else {
                detailGpsQualityText.setVisibility(View.GONE);
            }
        }

        // Add ballistics data if available
        if (detailBallisticsText != null) {
            if (target.getBallisticsData() != null && target.getBallisticsData().isValid()) {
                detailBallisticsText.setText(String.format("BC: %.3f",
                        target.getBallisticsData().getBallisticCoefficient()));
                detailBallisticsText.setVisibility(View.VISIBLE);
            } else {
                detailBallisticsText.setVisibility(View.GONE);
            }
        }

        // 5) Elevation-profile caption (using MSL-based values)
        elevProfileCaptionText.setText(
                String.format(Locale.US,
                        "Climb %+,.1f m over %.1f m → Tilt %.1f°",
                        vertOffMSL, ground, tiltMSL));

        // 6) Draw the chart using the MSL altitudes and horizontal distance
        // Call the correct method: setProfileData
        Log.d(TAG, String.format("Calling setProfileData with: SelfMSL=%.1f, TargetMSL=%.1f, Dist=%.1f", selfMSL,
                targetMSL, ground));
        elevationProfileView.setProfileData(selfMSL, targetMSL, ground);
    }

    /**
     * Helper function to get Self MSL robustly.
     * Compatible with older SDKs lacking AltitudeReference.MSL.
     * Needs access to MapView and EGM96 calculation.
     * 
     * @param selfGeoPoint The GeoPoint of the self marker.
     * @return MSL altitude in meters, or Double.NaN if cannot be determined
     *         reliably.
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
            double undulation = EGM96.getOffset(selfGeoPoint.getLatitude(), selfGeoPoint.getLongitude()); // <<< USE THE
                                                                                                          // ACTUAL CALL
                                                                                                          // HERE
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
        if (p1 == null || p2 == null)
            return 0;

        // Create copies of the points with the same altitude to get true horizontal
        // distance
        GeoPoint p1Horizontal = new GeoPoint(p1.getLatitude(), p1.getLongitude(), 0.0);
        GeoPoint p2Horizontal = new GeoPoint(p2.getLatitude(), p2.getLongitude(), 0.0);

        // Now distanceTo will give us true horizontal distance
        return p1Horizontal.distanceTo(p2Horizontal);
    }

    private double slantDistance(GeoPoint p1, GeoPoint p2) {
        if (p1 == null || p2 == null)
            return 0;
        // Use the built-in distanceTo which calculates slant distance
        return p1.distanceTo(p2);
    }

    // --- UI Update Helpers ---

    /**
     * Update the connection status display in Settings.
     */
    private void updateConnectionStatus() {
        if (connectionStatusText != null) {
            if (bleManager != null && bleManager.hasConnectedDevices()) {
                List<BluetoothDevice> connectedDevices = bleManager.getConnectedDevices();
                if (!connectedDevices.isEmpty()) {
                    StringBuilder status = new StringBuilder("Connected to: ");
                    for (int i = 0; i < connectedDevices.size(); i++) {
                        BluetoothDevice device = connectedDevices.get(i);
                        String deviceName = "Unknown Device";
                        try {
                            if (device != null) {
                                deviceName = device.getName();
                                if (deviceName == null || deviceName.isEmpty()) {
                                    deviceName = device.getAddress();
                                }
                            }
                        } catch (SecurityException se) {
                            Log.e(TAG, "BLE Permission error", se);
                            deviceName = "Permission Error";
                        }
                        status.append(deviceName);
                        if (i < connectedDevices.size() - 1) {
                            status.append(", ");
                        }
                    }
                    connectionStatusText.setText(status.toString());
                    connectionStatusText.setTextColor(0xFF008800); // Greenish
                } else {
                    connectionStatusText.setText("Not connected");
                    connectionStatusText.setTextColor(0xFFFF0000); // Red
                }
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
            if (targetAdapter == null)
                Log.w(TAG, "targetAdapter null in updateTargetList");
            if (targetManager == null)
                Log.w(TAG, "targetManager null in updateTargetList");
        }
    }

    /**
     * Update the target adapter (alias for updateTargetList)
     */
    private void updateTargetAdapter() {
        updateTargetList();
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
        if (deviceAdapter != null && bleManager != null) {
            deviceAdapter.clear();
            if (bleManager.isBluetoothSupported() && bleManager.isBluetoothEnabled()) {
                try {
                    List<BluetoothDevice> bondedDevices = bleManager.getBondedDevices();
                    if (bondedDevices != null && !bondedDevices.isEmpty()) {
                        for (BluetoothDevice device : bondedDevices) {
                            deviceAdapter.addDevice(device);
                        }
                    } else {
                        Log.d(TAG, "No bonded devices.");
                    }
                } catch (SecurityException e) {
                    Log.e(TAG, "BLE permission missing", e);
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
        if (bleManager == null) {
            Log.e(TAG, "BLE Manager null");
            showToast("BLE Error - Manager not initialized");
            return;
        }

        // Detailed pre-checks with user feedback
        if (!bleManager.isBluetoothSupported()) {
            showToast("Bluetooth LE not supported on this device");
            updateStatus("BLE not supported");
            return;
        }

        if (!bleManager.isBluetoothEnabled()) {
            showToast("Please enable Bluetooth first");
            updateStatus("Bluetooth disabled");
            return;
        }

        if (deviceAdapter == null) {
            deviceAdapter = new BLEDeviceAdapter(pluginContext, this);
            if (deviceListView != null) {
                deviceListView.setAdapter(deviceAdapter);
            } else {
                Log.e(TAG, "deviceListView is null");
                showToast("UI Error - device list not found");
                return;
            }
        }

        deviceAdapter.clear();
        updateStatus("Scanning for BLE devices...");
        showToast("Starting BLE scan...");

        try {
            // Log diagnostics for debugging
            bleManager.logDiagnostics();

            // TEMPORARILY: Don't load paired devices first - just scan for BLE
            // loadPairedDevices(); // Show bonded devices first

            boolean scanStarted = bleManager.startScan(); // Start BLE scan
            if (scanStarted) {
                Log.d(TAG, "BLE scan started successfully");
                showToast("BLE scan started - scanning for NEW devices only");
                updateStatus("Scanning for NEW BLE devices... (10-30 sec)");
            } else {
                Log.e(TAG, "Failed to start BLE scan");
                showToast("Failed to start BLE scan");
                updateStatus("Scan failed - check permissions");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "BLE permission missing", e);
            showToast("Bluetooth permission denied");
            updateStatus("Permission error");
        } catch (Exception e) {
            Log.e(TAG, "Exception during BLE scan", e);
            showToast("Scan error: " + e.getMessage());
            updateStatus("Scan error");
        }
    }

    /**
     * Send QUERY message via BLE.
     */
    private void queryTargets() {
        if (bleManager == null || !bleManager.hasConnectedDevices()) {
            showToast("No connected devices");
            return;
        }
        updateStatus("Scanning for targets...");
        byte[] queryMessage = MessageParser.createQueryMessage();
        if (!bleManager.writeToAllDevices(queryMessage)) {
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
        if (bleManager == null || !bleManager.hasConnectedDevices()) {
            showToast("No connected devices");
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

    // private void clearTargetLine() {
    // // Find and remove any target lines
    // mapView.getMapGroup().deepRemoveItems("Path to*");
    // }

    /**
     * Start calibration for a specific target ID.
     */
    private void calibrateTarget(String targetId) {
        if (targetId == null || targetId.isEmpty()) {
            Log.w(TAG, "Cannot calibrate null/empty ID");
            return;
        }
        if (bleManager == null || !bleManager.hasConnectedDevices()) {
            showToast("No connected devices");
            return;
        }
        currentCalibrationTargetId = targetId;
        calibrationStartTime = System.currentTimeMillis();
        updateStatus("Calibrating target " + targetId + "...");
        bleManager.writeToAllDevices(MessageParser.createCalibrationMessage(targetId));
    }

    // --- Map Marker Logic ---

    /**
     * Refactored function to plot or update a map marker for a target,
     * using HAE for placement and MSL metadata for display.
     * Creates/updates only if location is valid. Removes marker if location becomes
     * invalid.
     *
     * Assumes the containing class has:
     * - private MapView mapView;
     * - private Map<String, Marker> targetMarkers; // e.g., HashMap
     * - private static final String TARGET_MAP_GROUP_NAME = "Target Markers"; // Or
     * your preferred name
     * - private static final String TAG = "YourClassName"; // Set your class TAG
     */
    private void updateTargetMarker(Target target) {
        // Ensure necessary members are available (add null checks if needed in your
        // context)
        // if (mapView == null || targetMarkers == null) {
        // Log.e(TAG, "MapView or targetMarkers map is null!");
        // return;
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
        boolean locationValid = rawGeo != null && GeoPoint.isValid(rawGeo.getLatitude(), rawGeo.getLongitude()); // More
                                                                                                                 // robust
                                                                                                                 // validity
                                                                                                                 // check

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
                if (item instanceof Marker) {
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

        // 4) Convert MSL to HAE for proper placement
        double haeAltitude = rawMSL; // Default to MSL if conversion fails
        if (!Double.isNaN(rawMSL)) {
            try {
                // Get the geoid offset (undulation) for this location
                double undulation = EGM96.getOffset(lat, lon);
                if (!Double.isNaN(undulation)) {
                    haeAltitude = rawMSL + undulation; // HAE = MSL + undulation
                } else {
                    Log.w(TAG, "EGM96 lookup returned NaN for target " + targetId + ", using MSL as HAE");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error calculating HAE for target " + targetId + ": " + e.getMessage());
            }
        }

        Log.d(TAG, "Target ID: " + targetId + " | Raw Location: " + lat + ", " + lon + " | Raw MSL: " + rawMSL + " m");
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
                Log.d(TAG, "Found existing marker in group for " + targetId + ", updating reference.");
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
            // Use the 'height' metadata if available for self, otherwise use point altitude
            // (which might be HAE)
            double selfMSL = selfMarker.getMetaDouble("height", selfPoint.getAltitude());
            // If selfPoint altitude was HAE and 'height' wasn't set, this comparison is
            // less accurate.
            // For best results, ensure self marker also has 'height' set to MSL.
            if (selfPoint.getAltitudeReference() == GeoPoint.AltitudeReference.HAE
                    && !selfMarker.hasMetaValue("height")) {
                Log.w(TAG,
                        "Self marker altitude appears to be HAE, elevation difference calculation might be less accurate without MSL height metadata.");
                // Optionally calculate self MSL from HAE if needed: selfMSL =
                // selfPoint.getAltitude() - EGM96.getOffset(selfPoint.getLatitude(),
                // selfPoint.getLongitude());
            }

            double elevDiff = rawMSL - selfMSL;
            Log.d(TAG, "Target ID: " + targetId + " | Self MSL: " + selfMSL + " m | Elev Diff: " + elevDiff + " m");
            remarks.append(String.format(Locale.US, "\nElev diff: %.1f m %s",
                    Math.abs(elevDiff), elevDiff >= 0 ? "Above" : "Below"));
        } else {
            Log.w(TAG, "Could not get self marker location to calculate elevation difference.");
        }

        double voltage = target.getBatteryVoltage();
        if (voltage >= 0) // Assuming negative voltage is invalid
            remarks.append(String.format(Locale.US, "\nVoltage: %.2f V", voltage));

        marker.setMetaString("remarks", remarks.toString());
        marker.setTitle("Target " + targetId + " (" + target.getHitCount() + ")"); // Update title

        // 9) Persist marker changes
        marker.persist(mapView.getMapEventDispatcher(), null, this.getClass()); // Persist changes

        Log.i(TAG, "Marker updated successfully for Target ID: " + targetId);
    }

    /**
     * Finds or creates a map group. (Helper function, similar to MarkerCreator)
     * 
     * @param mapView   The MapView instance.
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
                // group.persist(mapView.getMapEventDispatcher(), null, this.getClass());
                Log.d(TAG, "Created map group: " + groupName);
            } else {
                Log.e(TAG, "Failed to create map group: " + groupName);
            }
        }
        return group;
    }

    public BLEManager getBLEManager() {
        return bleManager;
    }

    // --- Helper Methods ---

    /**
     * Helper method to safely convert an object to double.
     */
    private double safeConvertToDouble(Object value) {
        if (value == null)
            return 0.0;
        if (value instanceof Double)
            return (Double) value;
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
        if (bleManager != null) {
            bleManager.destroy();
            bleManager = null;
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

    // ==============================================================================
    // Interface Implementations
    // ==============================================================================

    /****************************
     * DEVICE CONNECT LISTENER METHODS
     *****************************/
    @Override
    public void onConnectDevice(final BluetoothDevice device) {
        if (device == null || bleManager == null)
            return;

        // Check if already connected to this device
        if (bleManager.isConnectedToDevice(device)) {
            Log.d(TAG, "Already connected to this device, disconnecting.");
            bleManager.disconnect(device);
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
            boolean success = bleManager.connect(device);
            mapView.post(() -> {
                updateConnectionStatus();
                if (!success) {
                    showToast("Connection failed");
                    updateStatus("Connection failed");
                }
            });
        }).start();
    }

    /****************************
     * BLE LISTENER METHODS
     *****************************/
    @Override
    public void onDeviceDiscovered(BluetoothDevice device, int rssi) {
        Log.i(TAG, "NEW BLE DEVICE DISCOVERED: " + (device != null ? device.getAddress() : "null") + " RSSI: " + rssi);
        if (deviceAdapter != null && device != null) {
            mapView.post(() -> {
                deviceAdapter.addDevice(device);
                showToast("Found BLE device: " + device.getAddress());
                Log.i(TAG, "Added device to adapter: " + device.getAddress());
            });
        } else {
            Log.e(TAG, "Cannot add device - adapter: " + (deviceAdapter != null) + ", device: " + (device != null));
        }
    }

    @Override
    public void onDeviceConnected(BluetoothDevice device) {
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
    public void onDeviceDisconnected(BluetoothDevice device) {
        String name;
        try {
            name = device.getName();
            if (name == null || name.isEmpty()) {
                name = device.getAddress();
            }
        } catch (SecurityException e) {
            name = "device";
        }
        updateStatus("Disconnected from " + name);
        mapView.post(this::updateConnectionStatus);
    }

    @Override
    public void onDataReceived(BluetoothDevice device, String characteristicUuid, byte[] data) {
        if (messageParser != null) {
            // Process data based on characteristic UUID
            if (BLEManager.POSITION_CHARACTERISTIC_UUID.toString().equals(characteristicUuid) ||
                    BLEManager.HIT_CHARACTERISTIC_UUID.toString().equals(characteristicUuid) ||
                    BLEManager.BATTERY_CHARACTERISTIC_UUID.toString().equals(characteristicUuid) ||
                    BLEManager.CALIBRATION_CHARACTERISTIC_UUID.toString().equals(characteristicUuid)) {
                messageParser.processData(data);
            }
        }
    }

    @Override
    public void onError(String message) {
        updateStatus("BLE Error: " + message);
    }

    @Override
    public void onScanStarted() {
        updateStatus("BLE scan started");
    }

    @Override
    public void onScanStopped() {
        updateStatus("BLE scan stopped");
    }

    /****************************
     * MESSAGE PARSER LISTENER METHODS
     *****************************/
    @Override
    public void onPositionMessage(String id, GeoPoint location, double voltage) {
        if (targetManager == null)
            return;
        Target target = targetManager.updateTargetPosition(id, location);
        targetManager.updateTargetVoltage(id, voltage);
        mapView.post(() -> {
            updateStatus("Pos: " + id + " V:" + String.format(Locale.US, "%.2f", voltage));
            updateTargetMarker(target);
            updateTargetList();
        });
    }

    @Override
    public void onPositionMessageEnhanced(String id, GeoPoint location, double voltage,
            int satellites, double hdop, String altitudeRef) {
        if (targetManager == null)
            return;

        Target target = targetManager.updateTargetPosition(id, location);
        targetManager.updateTargetVoltage(id, voltage);

        // Update GPS quality information
        target.setGpsQuality(satellites, hdop, altitudeRef);

        // Update shot tracker with enhanced position
        if (shotTracker != null) {
            shotTracker.updateTargetPosition(id, location);
        }

        mapView.post(() -> {
            String gpsQuality = target.isGpsQualityGood() ? "Good" : "Poor";
            updateStatus(String.format("Pos: %s V:%.2f %s (%s)",
                    id, voltage, gpsQuality, altitudeRef));
            updateTargetMarker(target);
            updateTargetList();

            // Log detailed GPS quality for debugging
            Log.d(TAG, String.format("Target %s GPS: %s", id, target.getGpsQualitySummary()));

            // Warn if GPS quality is poor
            if (!target.isGpsQualityGood()) {
                Log.w(TAG, String.format("Poor GPS quality for target %s: Sats=%d, HDOP=%.1f",
                        id, satellites, hdop));
            }
        });
    }

    @Override
    public void onHitMessage(String id) {
        if (targetManager == null)
            return;
        Target target = targetManager.processHit(id); // Creates if not exists
        mapView.post(() -> {
            updateStatus("Hit: " + id + " (Total: " + target.getHitCount() + ")");
            updateTargetMarker(target);
            updateTargetList();
        });
    }

    @Override
    public void onCalibrationResponse(String id, long roundTripTime) {
        if (targetManager == null)
            return;
        if (id != null && id.equals(currentCalibrationTargetId) && calibrationStartTime > 0) {
            long elapsedTime = System.currentTimeMillis() - calibrationStartTime;
            targetManager.setCalibrationTime(id, elapsedTime);
            String status = "Calibrated " + id + ": " + elapsedTime + "ms";
            mapView.post(() -> {
                updateStatus(status);
                Target target = targetManager.getTarget(id);
                if (target != null)
                    updateTargetMarker(target);
                updateTargetList();
            });
            currentCalibrationTargetId = null;
        }
    }

    @Override
    public void onParseError(String error) {
        updateStatus("Parse error: " + error);
    }

    /****************************
     * DROP DOWN RECEIVER METHODS
     *****************************/
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action != null && action.equals(SHOW_PLUGIN)) {
            Log.d(TAG, "Received SHOW_PLUGIN intent.");
            showMainView(); // Show main view when triggered externally
        }
    }

    /****************************
     * DROP DOWN LISTENER METHODS
     *****************************/
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
                if (self != null && targetAdapter != null)
                    targetAdapter.setMyLocation(self);
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
        // clearTargetLine();
        Log.d(TAG, "DropDown Closed");
    }

    /****************************
     * TARGET ACTION LISTENER METHODS
     *****************************/
    @Override
    public void onResetTarget(Target target) {
        if (target == null || targetManager == null)
            return;
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

    /**
     * Show BLE diagnostics information
     */
    private void showBLEDiagnostics() {
        if (bleManager == null) {
            showToast("BLE Manager not initialized");
            return;
        }

        String diagnostics = bleManager.getPermissionStatus();
        Log.i(TAG, "BLE Diagnostics:\n" + diagnostics);

        // Show in a more user-friendly way
        StringBuilder userMessage = new StringBuilder();
        userMessage.append("BLE Status:\n");

        if (bleManager.isBLESupported()) {
            userMessage.append("✓ BLE Supported\n");
        } else {
            userMessage.append("✗ BLE NOT Supported\n");
        }

        if (bleManager.isBluetoothEnabled()) {
            userMessage.append("✓ Bluetooth Enabled\n");
        } else {
            userMessage.append("✗ Bluetooth Disabled\n");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            userMessage.append("Android 12+ Permissions:\n");
            boolean scanPerm = ContextCompat.checkSelfPermission(pluginContext,
                    Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
            boolean connectPerm = ContextCompat.checkSelfPermission(pluginContext,
                    Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;

            userMessage.append(scanPerm ? "✓" : "✗").append(" SCAN Permission\n");
            userMessage.append(connectPerm ? "✓" : "✗").append(" CONNECT Permission\n");
        } else {
            userMessage.append("Legacy Android Permissions:\n");
            boolean locationPerm = ContextCompat.checkSelfPermission(pluginContext,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            userMessage.append(locationPerm ? "✓" : "✗").append(" Location Permission\n");
        }

        // Update status and show toast
        updateStatus(userMessage.toString().replace("\n", " | "));
        showToast("Check logs for full diagnostics");

        // Also log the full diagnostics
        bleManager.logDiagnostics();
    }

    @Override
    public void onShotFiredMessage(String targetId, long timestamp) {
        Log.d(TAG, "Shot fired message received for target: " + targetId);

        if (shotTracker != null) {
            shotTracker.recordShotFired(targetId, timestamp);
        }

        mapView.post(() -> {
            updateStatus("Shot fired at " + targetId);
        });
    }

    /**
     * Update shot tracker with current self position for accurate ballistics
     * calculations
     */
    private void updateShotTrackerPosition() {
        if (shotTracker == null)
            return;

        // Get current self position from ATAK
        try {
            // Try to get position from ATAK's self marker
            Marker selfMarker = mapView.getSelfMarker();
            if (selfMarker != null) {
                GeoPoint selfPos = selfMarker.getPoint();
                shotTracker.updateFiringPosition(selfPos);
                Log.d(TAG, "Shot tracker position updated: " + selfPos);
            } else {
                Log.w(TAG, "Could not get self marker for shot tracker");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating shot tracker position: " + e.getMessage());
        }
    }

    /**
     * Refresh the detail view if currently visible
     */
    private void refreshDetailView() {
        if (currentView == VIEW_DETAIL && detailView != null && detailView.getVisibility() == View.VISIBLE) {
            // Find the currently displayed target and refresh its data
            List<Target> targets = targetManager.getAllTargets();
            if (!targets.isEmpty()) {
                // For now, refresh with the first target - in a real implementation,
                // you'd track which target is currently being displayed
                populateDetailView(targets.get(0));
            }
        }
    }

    /**
     * Generate test targets with fake data for testing the interface.
     * Creates targets at various distances and bearings from your location:
     * 38°17'04.7"N 77°08'38.5"W
     */
    private void generateTestTargets() {
        Log.d(TAG, "Generating test targets with fake data");

        if (targetManager == null) {
            Log.e(TAG, "targetManager null, cannot generate test targets");
            showToast("Error: Target manager not available");
            return;
        }

        // Your location: 38°17'04.7"N 77°08'38.5"W
        double baseLat = 38.284639; // 38°17'04.7"N
        double baseLon = -77.144028; // 77°08'38.5"W
        double baseAltMSL = 100.0; // Approximate MSL altitude for your area

        updateStatus("Generating test targets...");
        showToast("Creating test targets around your location");

        // Test Target 1: 300 yards NE (bearing ~45°)
        double target1Lat = baseLat + (300 * 0.9144 * Math.cos(Math.toRadians(45))) / 111111.0; // Convert yards to
                                                                                                // meters to degrees
        double target1Lon = baseLon
                + (300 * 0.9144 * Math.sin(Math.toRadians(45))) / (111111.0 * Math.cos(Math.toRadians(baseLat)));
        GeoPoint target1Pos = new GeoPoint(target1Lat, target1Lon, baseAltMSL + 5.0); // 5m higher

        Target target1 = targetManager.updateTargetPosition("T001", target1Pos);
        targetManager.updateTargetVoltage("T001", 4.1);
        target1.setGpsQuality(12, 1.2, "MSL"); // Good GPS quality
        target1.incrementHitCount(); // One hit
        target1.incrementHitCount(); // Two hits

        // Test Target 2: 500 yards SW (bearing ~225°)
        double target2Lat = baseLat + (500 * 0.9144 * Math.cos(Math.toRadians(225))) / 111111.0;
        double target2Lon = baseLon
                + (500 * 0.9144 * Math.sin(Math.toRadians(225))) / (111111.0 * Math.cos(Math.toRadians(baseLat)));
        GeoPoint target2Pos = new GeoPoint(target2Lat, target2Lon, baseAltMSL - 8.0); // 8m lower

        Target target2 = targetManager.updateTargetPosition("T002", target2Pos);
        targetManager.updateTargetVoltage("T002", 3.8);
        target2.setGpsQuality(8, 2.1, "MSL"); // Fair GPS quality
        target2.incrementHitCount(); // One hit

        // Test Target 3: 800 yards N (bearing 0°)
        double target3Lat = baseLat + (800 * 0.9144 * Math.cos(Math.toRadians(0))) / 111111.0;
        double target3Lon = baseLon
                + (800 * 0.9144 * Math.sin(Math.toRadians(0))) / (111111.0 * Math.cos(Math.toRadians(baseLat)));
        GeoPoint target3Pos = new GeoPoint(target3Lat, target3Lon, baseAltMSL + 15.0); // 15m higher

        Target target3 = targetManager.updateTargetPosition("T003", target3Pos);
        targetManager.updateTargetVoltage("T003", 4.2);
        target3.setGpsQuality(15, 0.8, "MSL"); // Excellent GPS quality
        // No hits yet

        // Test Target 4: 1000 yards SE (bearing ~135°)
        double target4Lat = baseLat + (1000 * 0.9144 * Math.cos(Math.toRadians(135))) / 111111.0;
        double target4Lon = baseLon
                + (1000 * 0.9144 * Math.sin(Math.toRadians(135))) / (111111.0 * Math.cos(Math.toRadians(baseLat)));
        GeoPoint target4Pos = new GeoPoint(target4Lat, target4Lon, baseAltMSL - 3.0); // 3m lower

        Target target4 = targetManager.updateTargetPosition("T004", target4Pos);
        targetManager.updateTargetVoltage("T004", 3.5); // Low battery
        target4.setGpsQuality(6, 3.5, "MSL"); // Poor GPS quality
        target4.incrementHitCount(); // One hit
        target4.incrementHitCount(); // Two hits
        target4.incrementHitCount(); // Three hits
        target4.incrementHitCount(); // Four hits

        // Test Target 5: 600 yards W (bearing 270°) - with ballistics data
        double target5Lat = baseLat + (600 * 0.9144 * Math.cos(Math.toRadians(270))) / 111111.0;
        double target5Lon = baseLon
                + (600 * 0.9144 * Math.sin(Math.toRadians(270))) / (111111.0 * Math.cos(Math.toRadians(baseLat)));
        GeoPoint target5Pos = new GeoPoint(target5Lat, target5Lon, baseAltMSL + 10.0); // 10m higher

        Target target5 = targetManager.updateTargetPosition("T005", target5Pos);
        targetManager.updateTargetVoltage("T005", 4.0);
        target5.setGpsQuality(11, 1.5, "MSL"); // Good GPS quality
        target5.incrementHitCount(); // One hit

        // Add some fake ballistics data to target 5
        try {
            // Create fake ballistics data with reasonable values
            BallisticsCalculator.BallisticsData fakeBallistics = new BallisticsCalculator.BallisticsData();
            fakeBallistics.muzzleVelocity = 850.0; // m/s (typical rifle)
            fakeBallistics.ballisticCoefficient = 0.485; // Typical BC for .308 Winchester
            fakeBallistics.ammunitionType = ".308 Winchester";
            fakeBallistics.bulletWeight = 175.0; // grams
            fakeBallistics.temperature = 20.0; // °C
            fakeBallistics.pressure = 101300.0; // Pa (1013 hPa)
            fakeBallistics.range = 600 * 0.9144; // Convert 600 yards to meters
            fakeBallistics.timeOfFlight = 0.75; // 750ms time of flight

            target5.setBallisticsData(fakeBallistics);
            target5.setAverageTimeOfFlight(0.75); // 750ms time of flight

            Log.d(TAG, "Added fake ballistics data to target T005");
        } catch (Exception e) {
            Log.w(TAG, "Could not add ballistics data to test target: " + e.getMessage());
        }

        // Update UI
        mapView.post(() -> {
            // Update all target markers on the map
            updateTargetList();
            for (Target target : targetManager.getAllTargets()) {
                updateTargetMarker(target);
            }

            // Show main view to see the results
            showMainView();
        });

        updateStatus("Generated 5 test targets (300-1000 yards)");
        showToast("Test targets created! Check main view.");

        Log.d(TAG, String.format("Test targets created around %.6f, %.6f", baseLat, baseLon));
    }
}