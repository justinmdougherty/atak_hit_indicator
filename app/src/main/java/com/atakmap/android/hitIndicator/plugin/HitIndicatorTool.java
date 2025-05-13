package com.atakmap.android.hitIndicator.plugin;

import android.content.Context;
import android.graphics.drawable.Drawable;

import com.atakmap.android.hitIndicator.HitIndicatorMapComponent;
import com.atakmap.android.hitIndicator.plugin.support.AbstractPluginTool;

public class HitIndicatorTool extends AbstractPluginTool {

    public HitIndicatorTool(Context context) {
        super(context,
                "Hit Indicator",
                "Hit Indicator Plugin",
                getIcon(context),
                HitIndicatorMapComponent.SHOW_PLUGIN);
    }

    private static Drawable getIcon(Context context) {
        // Try to get the icon from resources
        try {
            return context.getResources().getDrawable(
                    context.getResources().getIdentifier("ic_launcher", "drawable",
                            context.getPackageName()));
        } catch (Exception e) {
            return null;
        }
    }
}