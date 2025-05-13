package com.atakmap.android.hitIndicator;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.dropdown.DropDownMapComponent;

/**
 * Main component class for the Hit Indicator plugin
 */
public class HitIndicatorMapComponent extends DropDownMapComponent {
    private static final String TAG = "HitIndicatorMapComponent";

    public static final String SHOW_PLUGIN = "com.atakmap.android.hitIndicator.SHOW_PLUGIN";

    private Context pluginContext;
    private MapView mapView;
    private HitIndicatorDropDownReceiver dropDownReceiver;
    private TargetManager targetManager;

    @Override
    public void onCreate(final Context context, final Intent intent, final MapView mapView) {
        super.onCreate(context, intent, mapView);

        this.mapView = mapView;
        this.pluginContext = context;

        Log.d(TAG, "Hit Indicator Plugin: onCreate");

        // Initialize managers
        targetManager = new TargetManager(context);

        // Create and register the drop down receiver
        dropDownReceiver = new HitIndicatorDropDownReceiver(mapView, context, targetManager);

        AtakBroadcast.DocumentedIntentFilter ddFilter = new AtakBroadcast.DocumentedIntentFilter();
        ddFilter.addAction(SHOW_PLUGIN);
        registerDropDownReceiver(dropDownReceiver, ddFilter);

        // Add a menu option for the plugin
        setupMenu();
    }

    /**
     * Set up the menu option for the plugin
     */
    private void setupMenu() {
        // You can add a menu item to launch your plugin here if needed
    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        // Clean up resources
        if (dropDownReceiver != null) {
            //dropDownReceiver.shutdown(); // Call shutdown to clean up Bluetooth
            dropDownReceiver.disposeImpl();
        }

        Log.d(TAG, "Hit Indicator Plugin: onDestroy");
        super.onDestroyImpl(context, view);
    }

    /**
     * Convenience method to show the plugin
     */
    public static void showPlugin(Context context) {
        Intent i = new Intent(SHOW_PLUGIN);
        AtakBroadcast.getInstance().sendBroadcast(i);
    }
}