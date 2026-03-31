# ── ACCESSIBILITY SERVICE ─────────────────────────────────────────────────────
-keep class com.ridesmart.service.RideSmartService { *; }
-keep class com.ridesmart.receiver.BootReceiver { *; }
-keep class com.ridesmart.ui.LockScreenWakeActivity { *; }

# ── ROOM DATABASE ─────────────────────────────────────────────────────────────
-keep class com.ridesmart.data.RideEntry { *; }
-keep class com.ridesmart.data.RideDao { *; }
-keep class com.ridesmart.data.RideDatabase { *; }
-keep class com.ridesmart.data.Converters { *; }

# ── MODELS & ENUMS ────────────────────────────────────────────────────────────
-keep enum com.ridesmart.model.Signal { *; }
-keep enum com.ridesmart.model.VehicleType { *; }
-keep enum com.ridesmart.model.FuelType { *; }
-keep class com.ridesmart.model.** { *; }

# ── PARSERS (used via reflection in ParserFactory) ────────────────────────────
-keep class com.ridesmart.parser.** { *; }

# ── DATASTORE ─────────────────────────────────────────────────────────────────
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# ── FIREBASE ─────────────────────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# ── ML KIT (OCR for Uber) ─────────────────────────────────────────────────────
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ── COROUTINES ────────────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ── GENERAL KOTLIN ────────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# ── LOGGING & DEBUGGING ───────────────────────────────────────────────────────
# Prevent R8 from stripping any Log calls (useful if using proguard-android-optimize.txt)
-keep class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
