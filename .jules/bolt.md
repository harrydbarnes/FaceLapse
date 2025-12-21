# Bolt's Journal

## 2024-05-22 - [Android Bitmap Allocation Churn]
**Learning:** In Android video generation loops, allocating temporary buffers (like `IntArray` for pixel data) inside the loop causes massive GC pressure (e.g., 8MB * 300 frames = 2.4GB churn).
**Action:** Always hoist buffer allocations and helper objects (Paint, SimpleDateFormat) outside the processing loop.
