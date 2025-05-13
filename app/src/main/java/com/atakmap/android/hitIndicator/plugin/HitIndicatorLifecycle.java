package com.atakmap.android.hitIndicator.plugin;

import android.content.Context;

import com.atakmap.android.hitIndicator.HitIndicatorMapComponent;
import com.atakmap.android.hitIndicator.plugin.support.AbstractPluginLifecycle;

public class HitIndicatorLifecycle extends AbstractPluginLifecycle {

    public HitIndicatorLifecycle(Context ctx) {
        super(ctx, new HitIndicatorMapComponent());
    }
}