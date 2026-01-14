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
# Using broad rules to ensure stability across subpackages and internal members.
-keep class com.google.mlkit.vision.face.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_face.** { *; }

# Keep Room entities based on annotation.
# This ensures data model classes are preserved regardless of package.
# Optimized to only keep fields and constructors required by Room.
-keep @androidx.room.Entity class * {
    <fields>;
    <init>(...);
}
