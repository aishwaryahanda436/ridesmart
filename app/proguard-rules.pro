# Keep Accessibility Service entry point
-keep class com.ridesmart.service.RideSmartService { *; }

# Keep model classes for serialization (e.g. DataStore, JSON)
-keep class com.ridesmart.model.** { *; }

# Keep specific data classes used in persistence
-keep class com.ridesmart.data.RideEntry { *; }

# Keep parcelize/data classes if used with reflection or IPC
-keepclassmembers class com.ridesmart.** implements android.os.Parcelable { *; }

# Keep enums to prevent stripping names
-keepclassmembers enum com.ridesmart.** { *; }

# Keep BootReceiver entry point
-keep class com.ridesmart.receiver.BootReceiver { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
