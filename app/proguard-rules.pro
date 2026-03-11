# Keep Accessibility Service
-keep class com.ridesmart.service.RideSmartService { *; }

# Keep data classes used in DataStore serialization
-keep class com.ridesmart.model.** { *; }
-keep class com.ridesmart.data.RideEntry { *; }

# Keep enums
-keepclassmembers enum com.ridesmart.** { *; }

# Keep BootReceiver
-keep class com.ridesmart.receiver.BootReceiver { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
