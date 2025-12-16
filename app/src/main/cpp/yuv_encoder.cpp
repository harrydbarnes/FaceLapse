#include <jni.h>
#include <algorithm>

// Standard BT.601 coefficients
// Y = 0.257*R + 0.504*G + 0.098*B + 16
// U = -0.148*R - 0.291*G + 0.439*B + 128
// V = 0.439*R - 0.368*G - 0.071*B + 128

// Integer implementation used in Kotlin:
// Y = ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16
// U = ((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128
// V = ((112 * R - 94 * G - 18 * B + 128) >> 8) + 128

static const int BT601_Y_R = 66;
static const int BT601_Y_G = 129;
static const int BT601_Y_B = 25;
static const int BT601_U_R = -38;
static const int BT601_U_G = -74;
static const int BT601_U_B = 112;
static const int BT601_V_R = 112;
static const int BT601_V_G = -94;
static const int BT601_V_B = -18;

// Helper function for clamping
inline jbyte clamp(int value) {
    return static_cast<jbyte>(std::clamp(value, 0, 255));
}

extern "C" JNIEXPORT void JNICALL
Java_com_facelapse_app_domain_VideoGenerator_encodeYUV420SP(
        JNIEnv* env,
        [[maybe_unused]] jclass clazz,
        jbyteArray yuv420sp,
        jintArray argb,
        jint width,
        jint height) {

    jbyte* yuv = nullptr;
    jint* pixels = nullptr;

    // Get pointers to the Java arrays.
    // We use GetPrimitiveArrayCritical for performance, which might pin the arrays
    // and disable garbage collection. It's crucial to release the arrays promptly.

    jsize yuvLen = env->GetArrayLength(yuv420sp);
    jsize argbLen = env->GetArrayLength(argb);
    int frameSize = width * height;
    int requiredYuvSize = frameSize * 3 / 2;

    // Safety check: ensure arrays are large enough
    if (argbLen < frameSize || yuvLen < requiredYuvSize) {
        jclass illegalArgumentExceptionClass = env->FindClass("java/lang/IllegalArgumentException");
        if (illegalArgumentExceptionClass != nullptr) {
            env->ThrowNew(illegalArgumentExceptionClass, "Input arrays are not large enough for the given width and height.");
        }
        return; // No resources acquired yet, safe to return
    }

    // Use GetPrimitiveArrayCritical for potentially faster access (avoids copying).
    // CAUTION: This may pause GC. Do not perform blocking operations or JNI calls inside the critical section.

    yuv = (jbyte*) env->GetPrimitiveArrayCritical(yuv420sp, nullptr);
    if (yuv == nullptr) {
        // OutOfMemoryError already thrown by JNI.
        return;
    }

    pixels = (jint*) env->GetPrimitiveArrayCritical(argb, nullptr);
    if (pixels == nullptr) {
        // OutOfMemoryError already thrown by JNI.
        // Must release yuv before returning.
        env->ReleasePrimitiveArrayCritical(yuv420sp, yuv, 0);
        return;
    }

    int uvIndex = frameSize;
    int index = 0;

    // Native C++ implementation of ARGB to NV12 conversion.

    for (int j = 0; j < height; ++j) {
        for (int i = 0; i < width; ++i) {
            int pixel = pixels[index];
            int R = (pixel >> 16) & 0xff;
            int G = (pixel >> 8) & 0xff;
            int B = pixel & 0xff;

            // Y
            int Y = ((BT601_Y_R * R + BT601_Y_G * G + BT601_Y_B * B + 128) >> 8) + 16;
            yuv[index] = clamp(Y);

            // NV12 interleaves U and V (U first)
            // Subsample: Calculate U/V only for even rows and columns
            // Optimization: Use bitwise AND for parity check
            if (((j & 1) == 0) && ((i & 1) == 0)) {
                int U = ((BT601_U_R * R + BT601_U_G * G + BT601_U_B * B + 128) >> 8) + 128;
                int V = ((BT601_V_R * R + BT601_V_G * G + BT601_V_B * B + 128) >> 8) + 128;

                // NV12: U then V
                yuv[uvIndex++] = clamp(U);
                yuv[uvIndex++] = clamp(V);
            }
            index++;
        }
    }

    // Release critical arrays
    // nullptr checks removed as early returns handle null scenarios
    env->ReleasePrimitiveArrayCritical(argb, pixels, JNI_ABORT); // JNI_ABORT = release without copying back
    env->ReleasePrimitiveArrayCritical(yuv420sp, yuv, 0); // 0 = copy back and release
}
