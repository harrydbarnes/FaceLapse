## 2024-05-23 - [Correct Logging Practices in Android]
**Vulnerability:** Usage of `e.printStackTrace()` in production Android code (`VideoGenerator.kt`).
**Learning:** `printStackTrace()` writes to `System.err`, which is not properly routed to the Android logging system (Logcat) in all environments, lacks tag filtering, and cannot be easily stripped by Proguard/R8 rules. This can lead to uncontrolled information leakage (stack traces) in release builds.
**Prevention:** Always use `android.util.Log` (e.g., `Log.e(TAG, "msg", e)`) for error reporting. Define a `private const val TAG` to ensure consistent filtering.
