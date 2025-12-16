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

    int y_idx = 0;
    int uv_idx = frameSize;

    // Native C++ implementation of ARGB to NV12 conversion.
    // Process 2x2 pixel blocks for performance.
    // This reduces loop overhead and eliminates the conditional branch for UV subsampling in the hot path.
    // Safe because width and height are enforced multiples of 16 in VideoGenerator.kt.

    for (int j = 0; j < height; j += 2) {
        for (int i = 0; i < width; i += 2) {
            // Top-left pixel (i, j) - used for Y and UV
            int p1 = pixels[y_idx + i];
            int r1 = (p1 >> 16) & 0xff;
            int g1 = (p1 >> 8) & 0xff;
            int b1 = p1 & 0xff;
            yuv[y_idx + i] = clamp(((BT601_Y_R * r1 + BT601_Y_G * g1 + BT601_Y_B * b1 + 128) >> 8) + 16);

            // Top-right pixel (i+1, j) - Y only
            int p2 = pixels[y_idx + i + 1];
            yuv[y_idx + i + 1] = clamp(((BT601_Y_R * ((p2 >> 16) & 0xff) + BT601_Y_G * ((p2 >> 8) & 0xff) + BT601_Y_B * (p2 & 0xff) + 128) >> 8) + 16);

            // Bottom-left pixel (i, j+1) - Y only
            int p3 = pixels[y_idx + width + i];
            yuv[y_idx + width + i] = clamp(((BT601_Y_R * ((p3 >> 16) & 0xff) + BT601_Y_G * ((p3 >> 8) & 0xff) + BT601_Y_B * (p3 & 0xff) + 128) >> 8) + 16);

            // Bottom-right pixel (i+1, j+1) - Y only
            int p4 = pixels[y_idx + width + i + 1];
            yuv[y_idx + width + i + 1] = clamp(((BT601_Y_R * ((p4 >> 16) & 0xff) + BT601_Y_G * ((p4 >> 8) & 0xff) + BT601_Y_B * (p4 & 0xff) + 128) >> 8) + 16);

            // Subsample U and V from the top-left pixel of the 2x2 block.
            // NV12 format: UV pairs are stored sequentially after the Y plane.
            int u = ((BT601_U_R * r1 + BT601_U_G * g1 + BT601_U_B * b1 + 128) >> 8) + 128;
            int v = ((BT601_V_R * r1 + BT601_V_G * g1 + BT601_V_B * b1 + 128) >> 8) + 128;

            yuv[uv_idx++] = clamp(u);
            yuv[uv_idx++] = clamp(v);
        }
        y_idx += 2 * width;
    }

    // Release critical arrays
    // nullptr checks removed as early returns handle null scenarios
    env->ReleasePrimitiveArrayCritical(argb, pixels, JNI_ABORT); // JNI_ABORT = release without copying back
    env->ReleasePrimitiveArrayCritical(yuv420sp, yuv, 0); // 0 = copy back and release
}
