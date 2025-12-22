#include <jni.h>
#include <algorithm>
#include <android/bitmap.h>

// Standard BT.601 coefficients
// Y = 0.257*R + 0.504*G + 0.098*B + 16
// U = -0.148*R - 0.291*G + 0.439*B + 128
// V = 0.439*R - 0.368*G - 0.071*B + 128

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

// Helper function for Y calculation
inline jbyte rgbToY(int r, int g, int b) {
    return clamp(((BT601_Y_R * r + BT601_Y_G * g + BT601_Y_B * b + 128) >> 8) + 16);
}

// RAII wrapper for local references
class ScopedLocalRef {
public:
    ScopedLocalRef(JNIEnv* env, jobject localRef) : env_(env), localRef_(localRef) {}
    ~ScopedLocalRef() {
        if (localRef_ != nullptr) {
            env_->DeleteLocalRef(localRef_);
        }
    }
    // Delete copy constructor and assignment operator
    ScopedLocalRef(const ScopedLocalRef&) = delete;
    ScopedLocalRef& operator=(const ScopedLocalRef&) = delete;

    // Movable
    ScopedLocalRef(ScopedLocalRef&& other) noexcept : env_(other.env_), localRef_(other.localRef_) {
        other.localRef_ = nullptr;
    }

    ScopedLocalRef& operator=(ScopedLocalRef&& other) noexcept {
        if (this != &other) {
            if (localRef_ != nullptr) {
                env_->DeleteLocalRef(localRef_);
            }
            env_ = other.env_;
            localRef_ = other.localRef_;
            other.localRef_ = nullptr;
        }
        return *this;
    }

private:
    JNIEnv* env_;
    jobject localRef_;
};

// Helper function to throw IllegalArgumentException
void throwIllegalArgument(JNIEnv* env, jclass exceptionClass, const char* message) {
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_facelapse_app_domain_VideoGenerator_encodeYUV420SP(
        JNIEnv* env,
        [[maybe_unused]] jclass clazz,
        jbyteArray yuv420sp,
        jobject bitmap,
        jint width,
        jint height) {

    jbyte* yuv = nullptr;
    void* pixels = nullptr;
    AndroidBitmapInfo info;
    int ret;

    // Pre-fetch IllegalArgumentException class
    jclass illegalArgumentExceptionClass = env->FindClass("java/lang/IllegalArgumentException");

    // Check if FindClass succeeded before proceeding
    if (illegalArgumentExceptionClass == nullptr) {
        return; // Exception already pending from FindClass.
    }

    ScopedLocalRef exceptionClassGuard(env, illegalArgumentExceptionClass);

    // Get bitmap info
    if ((ret = AndroidBitmap_getInfo(env, bitmap, &info)) < 0) {
        throwIllegalArgument(env, illegalArgumentExceptionClass, "AndroidBitmap_getInfo failed.");
        return;
    }

    // Check format (must be RGBA_8888)
    // ANDROID_BITMAP_FORMAT_RGBA_8888 = 1
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
         throwIllegalArgument(env, illegalArgumentExceptionClass, "Bitmap must be ARGB_8888 format.");
         return;
    }

    // Safety check: dimensions
    if (info.width != (uint32_t)width || info.height != (uint32_t)height) {
         throwIllegalArgument(env, illegalArgumentExceptionClass, "Bitmap dimensions do not match expected width/height.");
         return;
    }

    jsize yuvLen = env->GetArrayLength(yuv420sp);
    int frameSize = width * height;
    int requiredYuvSize = frameSize * 3 / 2;

    if (yuvLen < requiredYuvSize) {
        throwIllegalArgument(env, illegalArgumentExceptionClass, "YUV output array is too small.");
        return;
    }

    // Lock bitmap pixels
    if ((ret = AndroidBitmap_lockPixels(env, bitmap, &pixels)) < 0) {
        throwIllegalArgument(env, illegalArgumentExceptionClass, "AndroidBitmap_lockPixels failed.");
        return;
    }

    // Get critical access to YUV array
    yuv = (jbyte*) env->GetPrimitiveArrayCritical(yuv420sp, nullptr);
    if (yuv == nullptr) {
        AndroidBitmap_unlockPixels(env, bitmap);
        // JVM will throw OOME automatically
        return;
    }

    int y_idx = 0;
    int uv_idx = frameSize;
    uint32_t stride = info.stride; // Bytes per row

    // Process 2x2 blocks
    // In RGBA_8888, bytes are R, G, B, A in memory order.
    // We access bytes directly to avoid endianness confusion.

    for (int j = 0; j < height; j += 2) {
        uint8_t* row1 = (uint8_t*)pixels + j * stride;
        uint8_t* row2 = (uint8_t*)pixels + (j + 1) * stride;

        for (int i = 0; i < width; i += 2) {
            // P1 (i, j)
            int offset1 = i * 4;
            int r1 = row1[offset1];
            int g1 = row1[offset1 + 1];
            int b1 = row1[offset1 + 2];
            yuv[y_idx + i] = rgbToY(r1, g1, b1);

            // P2 (i+1, j)
            int offset2 = (i + 1) * 4;
            int r2 = row1[offset2];
            int g2 = row1[offset2 + 1];
            int b2 = row1[offset2 + 2];
            yuv[y_idx + i + 1] = rgbToY(r2, g2, b2);

            // P3 (i, j+1)
            int offset3 = i * 4;
            int r3 = row2[offset3];
            int g3 = row2[offset3 + 1];
            int b3 = row2[offset3 + 2];
            yuv[y_idx + width + i] = rgbToY(r3, g3, b3);

            // P4 (i+1, j+1)
            int offset4 = (i + 1) * 4;
            int r4 = row2[offset4];
            int g4 = row2[offset4 + 1];
            int b4 = row2[offset4 + 2];
            yuv[y_idx + width + i + 1] = rgbToY(r4, g4, b4);

            // UV from P1
            int u = ((BT601_U_R * r1 + BT601_U_G * g1 + BT601_U_B * b1 + 128) >> 8) + 128;
            int v = ((BT601_V_R * r1 + BT601_V_G * g1 + BT601_V_B * b1 + 128) >> 8) + 128;

            yuv[uv_idx++] = clamp(u);
            yuv[uv_idx++] = clamp(v);
        }
        y_idx += 2 * width;
    }

    env->ReleasePrimitiveArrayCritical(yuv420sp, yuv, 0);
    AndroidBitmap_unlockPixels(env, bitmap);
}
