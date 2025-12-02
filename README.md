# FaceLapse

FaceLapse is an Android application for creating time-lapse videos of faces. It allows users to import photos, automatically align them based on face detection, and generate high-quality MP4 videos with optional date overlays.

## Features

*   **Photo Sources:**
    *   Import photos from **Google Photos** and **Local Storage** via the unified Android Photo Picker.
    *   Support for browsing, selecting, and re-selecting images.

*   **Face Alignment:**
    *   Uses **ML Kit** for on-device face detection.
    *   Automatically crops and aligns images to ensure the face remains consistent across frames.

*   **Project Management:**
    *   Create multiple projects.
    *   Add, remove, and reorder photos (using arrow buttons).
    *   Re-run face alignment on demand.
    *   All edits are stored locally using Room Database.

*   **Output Animation:**
    *   Generate **MP4** time-lapse videos.
    *   Export to local storage and share via Android Sharesheet.

*   **Date Overlay:**
    *   Optional date label on the video.
    *   Toggle overlay per project.
    *   Global settings for font size, date format, and "Day of Week" display.

*   **Material 3 Expressive Style:**
    *   Built with Jetpack Compose and Material 3.
    *   Supports Dynamic Colors and Light/Dark themes.
    *   **Adaptive Layout:** Uses a Bottom Navigation Bar on narrow screens and a Navigation Rail on wide screens.

*   **Performance & Privacy:**
    *   All processing is done **on-device**.
    *   Permissions are minimized (uses Photo Picker).

## Build Instructions

To build the project, use Gradle:

```bash
# Debug APK
./gradlew assembleDebug

# Release APK (Unsigned by default)
./gradlew assembleRelease
```

### Dependencies
*   **UI:** Jetpack Compose (Material 3)
*   **Image Loading:** Coil
*   **DI:** Hilt
*   **Database:** Room
*   **Storage:** DataStore
*   **ML:** ML Kit Face Detection
*   **Navigation:** Compose Navigation

## CI/CD Workflow

The project uses GitHub Actions for continuous integration and delivery:

*   **Build:** Runs on every push and pull request to `main` to verify the build and run tests.
*   **Release:** Automated versioning and release. Pushes to `main` trigger a version bump and tag creation. Pushes of new tags trigger the release creation and artifact upload.
