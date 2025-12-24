# Sentinel's Journal

## 2024-05-22 - [Journal Initialized]
**Vulnerability:** N/A
**Learning:** Initialized the sentinel journal to track critical security learnings.
**Prevention:** N/A

## 2024-05-22 - [Native Integer Overflow in YUV Encoding]
**Vulnerability:** The JNI function `encodeYUV420SP` calculated `frameSize` (width * height) and `requiredYuvSize` using signed 32-bit integers without overflow checks. A maliciously large image could cause these values to wrap around (potentially becoming negative or small), bypassing the buffer size check (`yuvLen < requiredYuvSize`). This would lead to a heap buffer overflow when the loop writes pixel data.
**Learning:** Even when high-level code (Kotlin) seemingly enforces limits, native code (C++) must validate inputs independently because it can be called directly or assumptions might change. Signed integer arithmetic in C++ is dangerous for size calculations.
**Prevention:** Always use 64-bit integers (`int64_t`) for intermediate size calculations in C++ to detect 32-bit overflows, and check against `std::numeric_limits<jint>::max()` before casting back to Java types. Explicitly validate logical constraints (e.g., even dimensions for subsampling) in the native layer.
