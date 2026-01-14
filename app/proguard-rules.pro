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
-keep public class com.google.mlkit.vision.face.** { *; }
-keep public class com.google.mlkit.vision.common.** { *; }

# Keep data model classes (e.g., Room entities) from being obfuscated.
# This is a safeguard against issues with reflection, serialization, or Parcelable.
-keep class com.facelapse.app.data.local.entity.** { *; }
