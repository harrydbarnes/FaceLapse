# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /usr/local/android-sdk/tools/proguard/proguard-android.txt
# (or proguard-android-optimize.txt)

# Keep JNI methods and their classes from being removed or renamed by R8.
# This is crucial for native code interoperability.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ML Kit Face Detection rules
# Optimized to keep only necessary classes for shrinking
-keep public class com.google.mlkit.vision.face.* { *; }
-keep public class com.google.android.gms.internal.mlkit_vision_face.* { *; }
-keep public class com.google.mlkit.vision.common.InputImage { *; }

# Keep Room entities based on annotation.
# This ensures data model classes are preserved regardless of package.
-keep @androidx.room.Entity class * { *; }
