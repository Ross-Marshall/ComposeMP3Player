# ComposeMP3Player (V2)

An Android-native music player built with **Jetpack Compose** and **Media3**, optimized for high-performance playback of large local libraries stored on external SD cards.

## 🎧 Overview
This project was born out of a need to manage a library of over 200+ playlists (315+ hours of music) refactored from original mix tapes. It focuses on deep-file scanning of `.m3u` files and seamless integration with automotive Bluetooth systems (specifically tested with Tesla).

## 🚀 Features
* **Media3 & MediaSession:** Robust background playback and metadata synchronization for Bluetooth displays.
* **M3U Deep Search:** Scans `.m3u` files and maps them to physical high-bitrate MP3 files across internal and external storage.
* **Persistent Custom Art:** Support for custom playlist covers using Android's `OpenDocument` API to ensure images persist across device reboots.
* **Linux-Friendly Workflow:** Built with a focus on file-system integrity and `adb`-friendly management.
* **Large Library Support:** Optimized to handle 200+ playlists without UI lag.

## 🛠️ Tech Stack
* **Language:** Kotlin
* **UI:** Jetpack Compose
* **Audio Engine:** Google Media3 (ExoPlayer)
* **Storage:** Scoped Storage with Persistable URI permissions
* **Architecture:** MVVM (Model-View-ViewModel)

## 📂 Project Structure
* `MainActivity.kt`: Entry point and UI hosting.
* `MusicViewModel.kt`: The "brain" — handles Media3 initialization, playlist loading, and search logic.
* `MusicModels.kt`: Data classes for Playlists and Tracks.
* `AndroidManifest.xml`: Configured for Foreground Services and MediaSession actions.

## 🐧 Developer Notes (Linux/Ubuntu)
To deploy this project from an Ubuntu environment:

1. **Build:**
   `./gradlew assembleDebug`

2. **Install via ADB:**
   `adb install app/build/outputs/apk/debug/app-debug.apk`

3. **Clear MediaStore Cache (if needed):**
   `adb shell content delete --uri content://media/external/audio/playlists`

## 📝 License
This project is for personal use and archival of a private music collection.
