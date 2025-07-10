# Keep ATAK API classes
-keep public class com.atakmap.android.** { *; }
-keep public interface com.atakmap.android.** { *; }
-keep public class com.atakmap.coremap.** { *; }
-keep public interface com.atakmap.coremap.** { *; }

# Keep common plugin component base classes and their constructors/methods
-keep class * extends com.atakmap.android.maps.MapComponent { <init>(...); *; }
-keep class * extends com.atakmap.android.dropdown.DropDownReceiver { <init>(...); *; }
-keep class * extends com.atakmap.android.ipc.AtakBroadcastReceiver { <init>(...); *; }
-keep class * extends com.atakmap.android.tools.AbstractTool { <init>(...); *; }
# Add other base classes your plugin uses (e.g., AssetManager, specific listeners)

# Keep classes that are referenced in AndroidManifest.xml
-keep public class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepnames class * extends android.app.Activity
-keepnames class * extends android.app.Application
-keepnames class * extends android.app.Service
-keepnames class * extends android.content.BroadcastReceiver
-keepnames class * extends android.content.ContentProvider
-keepnames class * extends android.preference.Preference

# If your plugin uses its own annotations for discovery by ATAK or itself
# -keep @path.to.your.Annotation class *
# -keep class * { @path.to.your.Annotation <fields>; }
# -keep class * { @path.to.your.Annotation <methods>; }

# Keep any native method names
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}