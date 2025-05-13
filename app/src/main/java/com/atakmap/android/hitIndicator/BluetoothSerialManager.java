package com.atakmap.android.hitIndicator;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages Bluetooth serial connections to the base station
 */
public class BluetoothSerialManager {
    private static final String TAG = "BluetoothSerialManager";

    // Standard Serial Port Profile UUID
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Bluetooth permission constants as strings to avoid direct references
    private static final String BLUETOOTH_CONNECT_PERMISSION = "android.permission.BLUETOOTH_CONNECT";
    private static final String BLUETOOTH_SCAN_PERMISSION = "android.permission.BLUETOOTH_SCAN";

    // Interface for Bluetooth events
    public interface BluetoothSerialListener {
        void onConnected(BluetoothDevice device);
        void onDisconnected();
        void onDataReceived(byte[] data);
        void onError(String message);
        void onDeviceDiscovered(BluetoothDevice device);
        void onDiscoveryFinished();
    }

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private BluetoothDevice connectedDevice;
    private InputStream inputStream;
    private OutputStream outputStream;
    private boolean isConnected = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean stopReading = false;
    private BluetoothSerialListener listener;

    // Receiver for Bluetooth discovery events
    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "Received broadcast action: " + action);

            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && listener != null) {
                    String deviceName = safeGetDeviceName(device);
                    Log.d(TAG, "Discovered device: " + deviceName + " [" + device.getAddress() + "]");
                    listener.onDeviceDiscovered(device);
                } else {
                    Log.e(TAG, "Device or listener is null in ACTION_FOUND");
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                Log.d(TAG, "Discovery finished");
                if (listener != null) {
                    listener.onDiscoveryFinished();
                }
            }
        }
    };

    public BluetoothSerialManager(Context context, BluetoothSerialListener listener) {
        this.context = context;
        this.listener = listener;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // Register for discovery broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        context.registerReceiver(discoveryReceiver, filter);
    }

    /**
     * Check if Bluetooth is supported on this device
     */
    public boolean isBluetoothSupported() {
        return bluetoothAdapter != null;
    }

    /**
     * Check if Bluetooth is enabled
     */
    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    /**
     * Helper method to check Bluetooth permissions based on Android version
     */
    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31) { // Android 12
            return ContextCompat.checkSelfPermission(context, BLUETOOTH_CONNECT_PERMISSION) ==
                    PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                    PackageManager.PERMISSION_GRANTED;
        }
    }

    /**
     * Helper method to safely get device name with permission checks
     */
    private String safeGetDeviceName(BluetoothDevice device) {
        if (device == null) return "Unknown Device";
        try {
            String name = device.getName();
            return (name != null && !name.isEmpty()) ? name : device.getAddress();
        } catch (SecurityException e) {
            Log.e(TAG, "Permission error getting device name", e);
            return "Unknown Device";
        }
    }

    /**
     * Get a list of paired devices
     */
    public List<BluetoothDevice> getPairedDevices() {
        List<BluetoothDevice> deviceList = new ArrayList<>();
        if (bluetoothAdapter != null) {
            try {
                Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
                deviceList.addAll(pairedDevices);
            } catch (SecurityException e) {
                Log.e(TAG, "Bluetooth permission error", e);
                if (listener != null) {
                    handler.post(() -> listener.onError("Bluetooth permission error"));
                }
            }
        }
        return deviceList;
    }

    /**
     * Start scanning for Bluetooth devices
     */
    public void startDiscovery() {
        Log.d(TAG, "startDiscovery called");
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth adapter is null");
            return;
        }

        // Check permissions based on Android version
        if (Build.VERSION.SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(context, BLUETOOTH_SCAN_PERMISSION) !=
                    PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Missing BLUETOOTH_SCAN permission");
                if (listener != null) {
                    handler.post(() -> listener.onError("Missing Bluetooth scan permission"));
                }
                return;
            }
        }

        // Cancel any ongoing discovery
        try {
            if (bluetoothAdapter.isDiscovering()) {
                Log.d(TAG, "Canceling existing discovery");
                bluetoothAdapter.cancelDiscovery();
            }

            // Start discovery
            boolean started = bluetoothAdapter.startDiscovery();
            Log.d(TAG, "Started Bluetooth discovery: " + (started ? "success" : "failed"));

            if (!started) {
                Log.e(TAG, "Failed to start Bluetooth discovery");
                if (listener != null) {
                    handler.post(() -> listener.onError("Failed to start Bluetooth discovery. Check permissions."));
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Bluetooth permission error", e);
            if (listener != null) {
                handler.post(() -> listener.onError("Bluetooth permission error: " + e.getMessage()));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting Bluetooth discovery", e);
            if (listener != null) {
                final String errorMsg = e.getMessage();
                handler.post(() -> listener.onError("Error starting discovery: " + errorMsg));
            }
        }
    }

    /**
     * Stop scanning for Bluetooth devices
     */
    public void stopDiscovery() {
        try {
            if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
                Log.d(TAG, "Stopped Bluetooth discovery");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Permission error stopping discovery", e);
        }
    }

    /**
     * Connect to a Bluetooth device
     */
    public boolean connect(BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "Cannot connect to null device");
            return false;
        }

        // Check permissions
        if (Build.VERSION.SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(context, BLUETOOTH_CONNECT_PERMISSION) !=
                    PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Missing BLUETOOTH_CONNECT permission");
                if (listener != null) {
                    handler.post(() -> listener.onError("Missing Bluetooth connect permission"));
                }
                return false;
            }
        }

        // Stop any discovery before connecting
        stopDiscovery();

        // Close existing connection if any
        disconnect();

        try {
            Log.d(TAG, "Connecting to " + safeGetDeviceName(device) + " [" + device.getAddress() + "]");
            bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            bluetoothSocket.connect();

            inputStream = bluetoothSocket.getInputStream();
            outputStream = bluetoothSocket.getOutputStream();
            connectedDevice = device;
            isConnected = true;

            // Start the read thread
            startReading();

            Log.d(TAG, "Connected to " + safeGetDeviceName(device));

            if (listener != null) {
                handler.post(() -> listener.onConnected(device));
            }

            return true;
        } catch (IOException e) {
            Log.e(TAG, "Connection failed: " + e.getMessage());
            if (listener != null) {
                final String errorMsg = e.getMessage();
                handler.post(() -> listener.onError("Connection failed: " + errorMsg));
            }
            disconnect();
            return false;
        }
    }

    /**
     * Disconnect from the current device
     */
    public void disconnect() {
        stopReading = true;

        try {
            if (bluetoothSocket != null) {
                bluetoothSocket.close();
                bluetoothSocket = null;
            }

            if (inputStream != null) {
                inputStream.close();
                inputStream = null;
            }

            if (outputStream != null) {
                outputStream.close();
                outputStream = null;
            }

            if (isConnected && listener != null) {
                handler.post(() -> listener.onDisconnected());
            }

            isConnected = false;
            connectedDevice = null;
            Log.d(TAG, "Bluetooth connection closed");
        } catch (IOException e) {
            Log.e(TAG, "Error closing Bluetooth connection: " + e.getMessage());
        }
    }

    /**
     * Write data to the connected device
     */
    public boolean writeData(byte[] data) {
        if (!isConnected || outputStream == null) {
            if (listener != null) {
                handler.post(() -> listener.onError("Not connected"));
            }
            return false;
        }

        try {
            outputStream.write(data);
            outputStream.flush();
            Log.d(TAG, "Data sent: " + new String(data));
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error writing data: " + e.getMessage());
            if (listener != null) {
                final String errorMsg = e.getMessage();
                handler.post(() -> listener.onError("Error writing data: " + errorMsg));
            }
            return false;
        }
    }

    /**
     * Start reading data from the input stream
     */
    private void startReading() {
        stopReading = false;

        executor.submit(() -> {
            byte[] buffer = new byte[1024];
            int bytesRead;

            while (!stopReading && isConnected && inputStream != null) {
                try {
                    bytesRead = inputStream.read(buffer);
                    if (bytesRead > 0) {
                        byte[] receivedData = new byte[bytesRead];
                        System.arraycopy(buffer, 0, receivedData, 0, bytesRead);

                        // Notify on main thread
                        final byte[] finalData = receivedData;
                        handler.post(() -> {
                            if (listener != null) {
                                listener.onDataReceived(finalData);
                            }
                        });
                    }
                } catch (IOException e) {
                    if (!stopReading) {
                        Log.e(TAG, "Error reading data: " + e.getMessage());
                        if (listener != null) {
                            final String errorMsg = e.getMessage();
                            handler.post(() -> listener.onError("Error reading data: " + errorMsg));
                        }
                        break;
                    }
                }
            }
        });
    }

    /**
     * Clean up resources
     */
    public void destroy() {
        try {
            context.unregisterReceiver(discoveryReceiver);
        } catch (IllegalArgumentException e) {
            // Safely ignore "receiver not registered" errors
            Log.d(TAG, "Receiver was not registered");
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering receiver: " + e.getMessage());
        }

        // Ensure we disconnect when destroying
        disconnect();

        // Shutdown the executor service
        if (!executor.isShutdown()) {
            executor.shutdown();
        }
    }

    /**
     * Check if currently connected
     */
    public boolean isConnected() {
        return isConnected;
    }

    /**
     * Get the currently connected device
     */
    public BluetoothDevice getConnectedDevice() {
        return connectedDevice;
    }
}