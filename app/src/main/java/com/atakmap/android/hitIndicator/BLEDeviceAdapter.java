package com.atakmap.android.hitIndicator;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.TextView;
import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for displaying BLE devices in a ListView
 */
public class BLEDeviceAdapter extends BaseAdapter {
    private static final String TAG = "BLEDeviceAdapter";

    private final Context context;
    private final List<BluetoothDevice> devices;
    private final DeviceConnectListener listener;
    private final LayoutInflater inflater;

    public interface DeviceConnectListener {
        void onConnectDevice(BluetoothDevice device);
    }

    public BLEDeviceAdapter(Context context, DeviceConnectListener listener) {
        this.context = context;
        this.listener = listener;
        this.devices = new ArrayList<>();
        this.inflater = LayoutInflater.from(context);
    }

    public void addDevice(BluetoothDevice device) {
        if (device != null && !devices.contains(device)) {
            devices.add(device);
            notifyDataSetChanged();
            Log.d(TAG, "Added BLE device: " + getDeviceName(device));
        }
    }

    public void removeDevice(BluetoothDevice device) {
        if (devices.remove(device)) {
            notifyDataSetChanged();
            Log.d(TAG, "Removed BLE device: " + getDeviceName(device));
        }
    }

    public void clear() {
        devices.clear();
        notifyDataSetChanged();
        Log.d(TAG, "Cleared device list");
    }

    @Override
    public int getCount() {
        return devices.size();
    }

    @Override
    public BluetoothDevice getItem(int position) {
        return devices.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;

        if (convertView == null) {
            convertView = inflater.inflate(R.layout.bt_device_item, parent, false);
            holder = new ViewHolder();
            holder.deviceNameText = convertView.findViewById(R.id.deviceNameText);
            holder.deviceAddressText = convertView.findViewById(R.id.deviceAddressText);
            holder.connectButton = convertView.findViewById(R.id.connectButton);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        BluetoothDevice device = getItem(position);
        if (device != null) {
            String deviceName = getDeviceName(device);
            String deviceAddress = device.getAddress();

            holder.deviceNameText.setText(deviceName);
            holder.deviceAddressText.setText(deviceAddress);

            // Update button text for BLE
            holder.connectButton.setText("Connect");
            holder.connectButton.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onConnectDevice(device);
                }
            });
        }

        return convertView;
    }

    private String getDeviceName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return (name != null && !name.isEmpty()) ? name : "Unknown BLE Device";
        } catch (SecurityException e) {
            Log.e(TAG, "Permission error getting device name", e);
            return "Permission Error";
        }
    }

    private static class ViewHolder {
        TextView deviceNameText;
        TextView deviceAddressText;
        Button connectButton;
    }
}
