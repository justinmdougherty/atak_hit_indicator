
package com.atakmap.android.hitIndicator;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Bluetooth Low Energy connections for the Hit Indicator system.
 * Handles multiple BLE devices advertising hit and position data from LoRa
 * sensors.
 */
public class BLEManager {
    private static final String TAG = "BLEManager";

    // Custom service UUID for Hit Indicator data
    public static final UUID HIT_INDICATOR_SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc");

    // Characteristic UUIDs
    public static final UUID POSITION_CHARACTERISTIC_UUID = UUID.fromString("12345678-1234-1234-1234-123456789abd");
    public static final UUID HIT_CHARACTERISTIC_UUID = UUID.fromString("12345678-1234-1234-1234-123456789abe");
    public static final UUID BATTERY_CHARACTERISTIC_UUID = UUID.fromString("12345678-1234-1234-1234-123456789abf");
    public static final UUID CALIBRATION_CHARACTERISTIC_UUID = UUID.fromString("12345678-1234-1234-1234-123456789ac0");

    // Interface for BLE events
    public interface BLEListener {
        void onDeviceDiscovered(BluetoothDevice device, int rssi);

        void onDeviceConnected(BluetoothDevice device);

        void onDeviceDisconnected(BluetoothDevice device);

        void onDataReceived(BluetoothDevice device, String characteristicUuid, byte[] data);

        void onError(String message);

        void onScanStarted();

        void onScanStopped();
    }

    private final Context context;
    private final BLEListener listener;
    private final Handler mainHandler;

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;

    // Track connected devices and their GATT connections
    private final Map<String, BluetoothGatt> connectedDevices = new ConcurrentHashMap<>();
    private final Map<String, BluetoothDevice> discoveredDevices = new ConcurrentHashMap<>();

    private boolean isScanning = false;
    private boolean isInitialized = false;

    // Track scanning duration for debugging
    private Handler scanTimeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable scanTimeoutRunnable;

    // Scan callback for BLE device discovery
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device != null) {
                String deviceAddress = device.getAddress();
                String deviceName = "Unknown";

                try {
                    deviceName = device.getName();
                    if (deviceName == null || deviceName.isEmpty()) {
                        deviceName = "Unknown";
                    }
                } catch (SecurityException e) {
                    deviceName = "Permission Denied";
                }

                Log.d(TAG, "BLE Device found: " + deviceName + " (" + deviceAddress + ") RSSI: " + result.getRssi());

                // Check if device advertises our service UUID
                if (result.getScanRecord() != null) {
                    List<ParcelUuid> serviceUuids = result.getScanRecord().getServiceUuids();
                    if (serviceUuids != null) {
                        for (ParcelUuid uuid : serviceUuids) {
                            Log.d(TAG, "Device " + deviceName + " advertises service: " + uuid.toString());
                            if (HIT_INDICATOR_SERVICE_UUID.equals(uuid.getUuid())) {
                                Log.i(TAG, "Found Hit Indicator device: " + deviceName);
                            }
                        }
                    } else {
                        Log.d(TAG, "Device " + deviceName + " advertises no service UUIDs");
                    }
                }

                if (!discoveredDevices.containsKey(deviceAddress)) {
                    discoveredDevices.put(deviceAddress, device);
                    if (listener != null) {
                        mainHandler.post(() -> listener.onDeviceDiscovered(device, result.getRssi()));
                    }
                }
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            Log.d(TAG, "Batch scan results: " + results.size() + " devices");
            for (ScanResult result : results) {
                onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            String error = "BLE scan failed with error code: " + errorCode;
            final String errorMessage;

            switch (errorCode) {
                case SCAN_FAILED_ALREADY_STARTED:
                    errorMessage = "Scan already started";
                    break;
                case SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                    errorMessage = "Application registration failed";
                    break;
                case SCAN_FAILED_FEATURE_UNSUPPORTED:
                    errorMessage = "Feature unsupported";
                    break;
                case SCAN_FAILED_INTERNAL_ERROR:
                    errorMessage = "Internal error";
                    break;
                case 5: // SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES (API 26+)
                    errorMessage = "Out of hardware resources";
                    break;
                case 6: // SCAN_FAILED_SCANNING_TOO_FREQUENTLY (API 26+)
                    errorMessage = "Scanning too frequently";
                    break;
                default:
                    errorMessage = "Unknown error (code: " + errorCode + ")";
                    break;
            }

            Log.e(TAG, error + " - " + errorMessage);
            isScanning = false;

            if (listener != null) {
                mainHandler.post(() -> listener.onError("BLE scan failed: " + errorMessage));
            }
        }
    };

    // GATT callback for device connections and data
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            BluetoothDevice device = gatt.getDevice();
            String deviceAddress = device.getAddress();

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to device: " + deviceAddress);
                connectedDevices.put(deviceAddress, gatt);

                // Discover services
                if (hasBluetoothPermissions()) {
                    gatt.discoverServices();
                }

                if (listener != null) {
                    mainHandler.post(() -> listener.onDeviceConnected(device));
                }

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from device: " + deviceAddress);
                connectedDevices.remove(deviceAddress);
                gatt.close();

                if (listener != null) {
                    mainHandler.post(() -> listener.onDeviceDisconnected(device));
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered for device: " + gatt.getDevice().getAddress());
                setupCharacteristicNotifications(gatt);
            } else {
                Log.w(TAG, "Service discovery failed with status: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            String characteristicUuid = characteristic.getUuid().toString();

            if (listener != null) {
                mainHandler.post(() -> listener.onDataReceived(gatt.getDevice(), characteristicUuid, data));
            }
        }
    };

    public BLEManager(Context context, BLEListener listener) {
        this.context = context;
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());
        initialize();
    }

    private void initialize() {
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.e(TAG, "BLE not supported on this device");
            if (listener != null) {
                listener.onError("BLE not supported on this device");
            }
            return;
        }

        bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            Log.e(TAG, "Unable to initialize BluetoothManager");
            if (listener != null) {
                listener.onError("Unable to initialize BluetoothManager");
            }
            return;
        }

        bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain BluetoothAdapter");
            if (listener != null) {
                listener.onError("Unable to obtain BluetoothAdapter");
            }
            return;
        }

        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        isInitialized = true;
        Log.d(TAG, "BLEManager initialized successfully");
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    public boolean isBLESupported() {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context,
                    Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context,
                            Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    public boolean startScanning() {
        return startScanningWithDiagnostics();
    }

    public void stopScanning() {
        if (!isScanning || bleScanner == null) {
            return;
        }

        try {
            // Cancel scan status updates
            if (scanTimeoutRunnable != null) {
                scanTimeoutHandler.removeCallbacks(scanTimeoutRunnable);
                scanTimeoutRunnable = null;
            }

            bleScanner.stopScan(scanCallback);
            isScanning = false;
            Log.d(TAG, "BLE scanning stopped");

            if (listener != null) {
                listener.onScanStopped();
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception stopping BLE scan", e);
        }
    }

    public boolean connectToDevice(BluetoothDevice device) {
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions");
            if (listener != null) {
                listener.onError("Missing Bluetooth permissions");
            }
            return false;
        }

        String deviceAddress = device.getAddress();
        if (connectedDevices.containsKey(deviceAddress)) {
            Log.w(TAG, "Already connected to device: " + deviceAddress);
            return true;
        }

        try {
            BluetoothGatt gatt = device.connectGatt(context, false, gattCallback);
            if (gatt != null) {
                Log.d(TAG, "Connecting to device: " + deviceAddress);
                return true;
            } else {
                Log.e(TAG, "Failed to create GATT connection");
                if (listener != null) {
                    listener.onError("Failed to create GATT connection");
                }
                return false;
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception connecting to device", e);
            if (listener != null) {
                listener.onError("Permission denied for device connection");
            }
            return false;
        }
    }

    public void disconnectDevice(BluetoothDevice device) {
        String deviceAddress = device.getAddress();
        BluetoothGatt gatt = connectedDevices.get(deviceAddress);

        if (gatt != null) {
            try {
                gatt.disconnect();
                Log.d(TAG, "Disconnecting from device: " + deviceAddress);
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception disconnecting device", e);
            }
        }
    }

    private void setupCharacteristicNotifications(BluetoothGatt gatt) {
        BluetoothGattService service = gatt.getService(HIT_INDICATOR_SERVICE_UUID);
        if (service == null) {
            Log.w(TAG, "Hit Indicator service not found on device");
            return;
        }

        // Enable notifications for all characteristics
        enableCharacteristicNotification(gatt, service, POSITION_CHARACTERISTIC_UUID);
        enableCharacteristicNotification(gatt, service, HIT_CHARACTERISTIC_UUID);
        enableCharacteristicNotification(gatt, service, BATTERY_CHARACTERISTIC_UUID);
        enableCharacteristicNotification(gatt, service, CALIBRATION_CHARACTERISTIC_UUID);
    }

    private void enableCharacteristicNotification(BluetoothGatt gatt, BluetoothGattService service,
            UUID characteristicUuid) {
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUuid);
        if (characteristic != null) {
            try {
                gatt.setCharacteristicNotification(characteristic, true);
                Log.d(TAG, "Enabled notifications for characteristic: " + characteristicUuid);
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception enabling notifications", e);
            }
        }
    }

    public List<BluetoothDevice> getDiscoveredDevices() {
        return new ArrayList<>(discoveredDevices.values());
    }

    public List<BluetoothDevice> getConnectedDevices() {
        List<BluetoothDevice> devices = new ArrayList<>();
        for (BluetoothGatt gatt : connectedDevices.values()) {
            devices.add(gatt.getDevice());
        }
        return devices;
    }

    public boolean isScanning() {
        return isScanning;
    }

    public boolean isConnected(BluetoothDevice device) {
        return connectedDevices.containsKey(device.getAddress());
    }

    /**
     * Check if any devices are connected
     */
    public boolean hasConnectedDevices() {
        return !connectedDevices.isEmpty();
    }

    /**
     * Check if connected to a specific device
     */
    public boolean isConnectedToDevice(BluetoothDevice device) {
        return connectedDevices.containsKey(device.getAddress());
    }

    /**
     * Get bonded (paired) devices
     */
    public List<BluetoothDevice> getBondedDevices() {
        List<BluetoothDevice> bondedDevices = new ArrayList<>();
        if (bluetoothAdapter != null) {
            try {
                bondedDevices.addAll(bluetoothAdapter.getBondedDevices());
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception getting bonded devices", e);
            }
        }
        return bondedDevices;
    }

    /**
     * Start BLE scanning
     */
    public boolean startScan() {
        return startScanning();
    }

    /**
     * Connect to a device
     */
    public boolean connect(BluetoothDevice device) {
        return connectToDevice(device);
    }

    /**
     * Disconnect from a device
     */
    public void disconnect(BluetoothDevice device) {
        disconnectDevice(device);
    }

    /**
     * Check if Bluetooth is supported
     */
    public boolean isBluetoothSupported() {
        return isBLESupported();
    }

    /**
     * Write data to all connected devices
     */
    public boolean writeToAllDevices(byte[] data) {
        if (connectedDevices.isEmpty()) {
            Log.w(TAG, "No connected devices to write to");
            return false;
        }

        boolean success = false;
        for (BluetoothGatt gatt : connectedDevices.values()) {
            if (writeToDevice(gatt, data)) {
                success = true;
            }
        }
        return success;
    }

    /**
     * Write data to a specific GATT connection
     */
    private boolean writeToDevice(BluetoothGatt gatt, byte[] data) {
        if (gatt == null || data == null) {
            return false;
        }

        try {
            BluetoothGattService service = gatt.getService(HIT_INDICATOR_SERVICE_UUID);
            if (service != null) {
                BluetoothGattCharacteristic characteristic = service.getCharacteristic(CALIBRATION_CHARACTERISTIC_UUID);
                if (characteristic != null) {
                    characteristic.setValue(data);
                    return gatt.writeCharacteristic(characteristic);
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception writing to device", e);
        }
        return false;
    }

    public void destroy() {
        Log.d(TAG, "Destroying BLEManager");

        stopScanning();

        // Disconnect all devices
        for (BluetoothGatt gatt : connectedDevices.values()) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception during cleanup", e);
            }
        }

        connectedDevices.clear();
        discoveredDevices.clear();
    }

    /**
     * Enhanced scanning with diagnostics and fallback options
     */
    public boolean startScanningWithDiagnostics() {
        Log.d(TAG, "Starting BLE scan with diagnostics...");

        // Detailed pre-checks with logging
        if (!isInitialized) {
            Log.e(TAG, "BLEManager not initialized");
            if (listener != null) {
                listener.onError("BLE Manager not initialized");
            }
            return false;
        }

        if (!isBLESupported()) {
            Log.e(TAG, "BLE not supported on this device");
            if (listener != null) {
                listener.onError("BLE not supported on this device");
            }
            return false;
        }

        if (!isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth is disabled");
            if (listener != null) {
                listener.onError("Bluetooth is disabled - please enable it");
            }
            return false;
        }

        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions");
            if (listener != null) {
                listener.onError("Missing Bluetooth permissions");
            }
            return false;
        }

        if (isScanning) {
            Log.w(TAG, "Already scanning");
            return true;
        }

        if (bleScanner == null) {
            Log.e(TAG, "BLE scanner not available");
            if (listener != null) {
                listener.onError("BLE scanner not available");
            }
            return false;
        }

        return startScanWithFilters();
    }

    /**
     * Try scanning with service filter first, then fallback to no filter
     */
    private boolean startScanWithFilters() {
        // First try with service UUID filter
        if (startScanWithServiceFilter()) {
            return true;
        }

        Log.w(TAG, "Service filter scan failed, trying without filters...");

        // Fallback: scan without filters to see all devices
        return startScanWithoutFilters();
    }

    /**
     * Scan with service UUID filter (preferred method)
     */
    private boolean startScanWithServiceFilter() {
        try {
            List<ScanFilter> filters = new ArrayList<>();
            ScanFilter.Builder filterBuilder = new ScanFilter.Builder();
            filterBuilder.setServiceUuid(new ParcelUuid(HIT_INDICATOR_SERVICE_UUID));
            filters.add(filterBuilder.build());

            ScanSettings.Builder settingsBuilder = new ScanSettings.Builder();
            settingsBuilder.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);
            settingsBuilder.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES);

            bleScanner.startScan(filters, settingsBuilder.build(), scanCallback);
            isScanning = true;
            Log.d(TAG, "BLE scanning started with service filter: " + HIT_INDICATOR_SERVICE_UUID);

            // Start periodic status updates
            startScanStatusUpdates();

            if (listener != null) {
                listener.onScanStarted();
            }
            return true;

        } catch (SecurityException e) {
            Log.e(TAG, "Security exception with service filter scan", e);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Exception with service filter scan", e);
            return false;
        }
    }

    /**
     * Scan without filters (fallback method to see all BLE devices)
     */
    private boolean startScanWithoutFilters() {
        try {
            ScanSettings.Builder settingsBuilder = new ScanSettings.Builder();
            settingsBuilder.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);
            settingsBuilder.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES);

            // Scan without filters to see all devices
            bleScanner.startScan(new ArrayList<>(), settingsBuilder.build(), scanCallback);
            isScanning = true;
            Log.d(TAG, "BLE scanning started WITHOUT filters (fallback mode)");

            // Start periodic status updates
            startScanStatusUpdates();

            if (listener != null) {
                listener.onScanStarted();
            }
            return true;

        } catch (SecurityException e) {
            Log.e(TAG, "Security exception with unfiltered scan", e);
            if (listener != null) {
                listener.onError("Permission denied for BLE scanning");
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Exception with unfiltered scan", e);
            if (listener != null) {
                listener.onError("BLE scan failed: " + e.getMessage());
            }
            return false;
        }
    }

    /**
     * Start periodic status updates during scanning
     */
    private void startScanStatusUpdates() {
        // Cancel any existing timeout
        if (scanTimeoutRunnable != null) {
            scanTimeoutHandler.removeCallbacks(scanTimeoutRunnable);
        }

        scanTimeoutRunnable = new Runnable() {
            private int scanDuration = 0;

            @Override
            public void run() {
                scanDuration += 5;
                if (isScanning) {
                    int deviceCount = discoveredDevices.size();
                    Log.i(TAG, "BLE Scan Status: " + scanDuration + "s elapsed, " + deviceCount + " devices found");

                    if (listener != null) {
                        listener.onError("Scanning: " + scanDuration + "s, " + deviceCount + " devices found");
                    }

                    // Continue checking every 5 seconds for up to 30 seconds
                    if (scanDuration < 30) {
                        scanTimeoutHandler.postDelayed(this, 5000);
                    } else {
                        Log.w(TAG, "BLE Scan completed after 30 seconds. Found " + deviceCount + " devices.");
                        stopScanning();
                    }
                }
            }
        };

        // Start first status update after 5 seconds
        scanTimeoutHandler.postDelayed(scanTimeoutRunnable, 5000);
    }

    /**
     * Get detailed permission status for debugging
     */
    public String getPermissionStatus() {
        StringBuilder status = new StringBuilder();
        status.append("BLE Permission Status:\n");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            boolean bluetoothScan = ContextCompat.checkSelfPermission(context,
                    Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
            boolean bluetoothConnect = ContextCompat.checkSelfPermission(context,
                    Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            boolean bluetoothAdvertise = ContextCompat.checkSelfPermission(context,
                    Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;

            status.append("- BLUETOOTH_SCAN: ").append(bluetoothScan ? "GRANTED" : "DENIED").append("\n");
            status.append("- BLUETOOTH_CONNECT: ").append(bluetoothConnect ? "GRANTED" : "DENIED").append("\n");
            status.append("- BLUETOOTH_ADVERTISE: ").append(bluetoothAdvertise ? "GRANTED" : "DENIED").append("\n");
        } else {
            // Android 11 and below
            boolean bluetoothLegacy = ContextCompat.checkSelfPermission(context,
                    Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED;
            boolean bluetoothAdmin = ContextCompat.checkSelfPermission(context,
                    Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED;
            boolean fineLocation = ContextCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            boolean coarseLocation = ContextCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

            status.append("- BLUETOOTH: ").append(bluetoothLegacy ? "GRANTED" : "DENIED").append("\n");
            status.append("- BLUETOOTH_ADMIN: ").append(bluetoothAdmin ? "GRANTED" : "DENIED").append("\n");
            status.append("- ACCESS_FINE_LOCATION: ").append(fineLocation ? "GRANTED" : "DENIED").append("\n");
            status.append("- ACCESS_COARSE_LOCATION: ").append(coarseLocation ? "GRANTED" : "DENIED").append("\n");
        }

        status.append("Android Version: ").append(Build.VERSION.SDK_INT).append("\n");
        status.append("BLE Supported: ").append(isBLESupported()).append("\n");
        status.append("Bluetooth Enabled: ").append(isBluetoothEnabled()).append("\n");
        status.append("Scanner Available: ").append(bleScanner != null).append("\n");

        return status.toString();
    }

    /**
     * Log detailed diagnostic information
     */
    public void logDiagnostics() {
        Log.i(TAG, "=== BLE DIAGNOSTICS ===");
        Log.i(TAG, getPermissionStatus());
        Log.i(TAG, "=== END DIAGNOSTICS ===");
    }
}
