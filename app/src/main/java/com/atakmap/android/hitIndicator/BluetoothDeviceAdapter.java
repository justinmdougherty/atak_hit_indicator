package com.atakmap.android.hitIndicator;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class BluetoothDeviceAdapter extends BaseAdapter {

    private static final String TAG = "BluetoothDeviceAdapter";

    public interface DeviceConnectListener {
        void onConnectDevice(BluetoothDevice device);
    }

    private final List<BluetoothDevice> devices;
    private final Context context;
    private final LayoutInflater inflater;
    private final DeviceConnectListener listener;

    public BluetoothDeviceAdapter(Context context, DeviceConnectListener listener) {
        this.context = context;
        this.devices = new ArrayList<>();
        this.inflater = LayoutInflater.from(context);
        this.listener = listener;
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
    public View getView(final int position, View convertView, ViewGroup parent) {
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

        final BluetoothDevice device = devices.get(position);

        // Set device name
        String deviceName = device.getName();
        if (deviceName == null || deviceName.isEmpty()) {
            deviceName = "Unknown Device";
        }
        holder.deviceNameText.setText(deviceName);

        // Set device address
        holder.deviceAddressText.setText(device.getAddress());

        // ADD THIS CODE HERE - Check if this device is currently connected
        boolean isConnected = false;
        if (listener instanceof HitIndicatorDropDownReceiver) {
            HitIndicatorDropDownReceiver receiver = (HitIndicatorDropDownReceiver)listener;
            BluetoothSerialManager manager = receiver.getBluetoothManager();
            if (manager != null && manager.isConnected() &&
                    manager.getConnectedDevice() != null &&
                    manager.getConnectedDevice().getAddress().equals(device.getAddress())) {
                isConnected = true;
            }
        }

        // ADD THIS CODE HERE - Set connect button text based on connection status
        holder.connectButton.setText(isConnected ? "Disconnect" : "Connect");

        // Set connect button listener
        holder.connectButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onConnectDevice(device);
            }
        });

        return convertView;
    }

    public void addDevice(BluetoothDevice device) {
        // Check if device already exists
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).getAddress().equals(device.getAddress())) {
                // Update existing device
                devices.set(i, device);
                notifyDataSetChanged();
                return;
            }
        }

        // Add new device
        devices.add(device);
        notifyDataSetChanged();
    }

    public void updateDevices(List<BluetoothDevice> deviceList) {
        devices.clear();
        if (deviceList != null) {
            devices.addAll(deviceList);
        }
        notifyDataSetChanged();
    }

    public void clear() {
        devices.clear();
        notifyDataSetChanged();
    }

    private static class ViewHolder {
        TextView deviceNameText;
        TextView deviceAddressText;
        Button connectButton;
    }
}