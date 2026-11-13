# MCBL — Minecraft Bedrock Launcher for Android

MCBL is an Android-focused downstream fork of [LeviLauncher for Android](https://github.com/LiteLDev/LeviLauncher-Android). It keeps the native Bedrock runtime and version-isolation architecture while developing the MCBL identity and update channel.

The upstream LeviLauncher project and its contributors are credited under the Apache License 2.0. See [LICENSE](LICENSE) and the bundled third-party notices.

## What MCBL does

- **Run Bedrock inside the launcher.** Imported game builds are prepared by the native Android runtime and opened in MCBL Minecraft activity. They are not installed as separate Android applications for every version.
- **Manage multiple versions.** Download builds available to the signed-in Google Play account or import a user-provided official `.apk` / `.apks` set.
- **Keep custom instances separate.** Imported versions can use per-version data, cache, external game files and mod directories through the storage-profile system. The official installed game uses its own profile.
- **Prepare native libraries per version.** The runtime extracts and caches the selected build Android native libraries and prepares the game activity before launch.
- **Keep worlds and packs organized.** The inherited content tools support world and resource-pack management, backups and world inspection.
- **Use legitimate account and store validation.** The official Google Play Minecraft installation remains a required base game; account and platform checks are not bypassed.
- **Verify version packages before import.** APK/APKS sets are staged and checked for one base APK, matching Minecraft package/version/signature metadata, safe archive paths and size limits. Existing instance data is not overwritten on name collisions.
- **Verify Google Play downloads.** Completed split downloads are not marked ready until every APK matches the base build and signer.

## Current Android runtime requirements

- Android 9 / API 28 or newer.
- 64-bit ARM device with `arm64-v8a`.
- An official, licensed Minecraft Bedrock copy installed through Google Play.
- The installed Google Play base game must be version **1.21.80 or newer**.
- At least 1 GB of currently available RAM is the upstream minimum guideline; 3 GB total RAM is recommended.
- At least 2 GB free storage is recommended; 5 GB or more is preferable for multiple versions and worlds.

MCBL performs a shared runtime compatibility check before native runtime preparation. It blocks versions below the current supported baseline and incompatible Android/ARM64 environments. Low RAM and low storage are reported as warnings because those measurements vary while Android is running; a warning is not a promise that Minecraft will launch successfully.

### Version support is not unlimited

MCBL can manage multiple builds, but it does **not** currently promise that every historical Bedrock release can run. The Android runtime baseline currently requires Minecraft **1.21.80+** and ARM64. Older versions are rejected rather than advertised as runnable. Support for another game version requires compatible native-library loading, platform integration and real-device testing.

## Importing and running a version

1. Install the official Minecraft Bedrock app from Google Play and launch it once.
2. Open MCBL version/download screen.
3. Download an available build for the signed-in Play account, or import an official APK/APKS set that you are licensed to use.
4. Give the imported instance a name and keep version isolation enabled when you want separate game data.
5. Select the instance in the version screen and press **Launch**.
6. Review any compatibility warnings shown during runtime preparation.

Downloaded/imported versions are launched through MCBL native runtime. The normal Android Package Installer is not used to switch between all those versions.

## Development setup

This fork uses the Android/NDK toolchain required by the upstream runtime:

- JDK 21
- Android SDK Platform 36
- Android NDK r28c (`28.2.13676358`)
- CMake and Xmake
- Android Studio or a command-line Gradle environment

Build locally:

```bash
./gradlew assembleDebug
```

The project also contains unit tests and split-set validation coverage:

```bash
./gradlew :app:testDebugUnitTest
```

See [Phase 10 APK import and download integrity](docs/PHASE10_APK_VERSION_PIPELINE.md) for accepted APKS layouts, safety limits and validation details.

Some integrations require local configuration and are not committed to this repository. The Android CI workflow downloads the Discord Social SDK from a configured private release source and may use Firebase / CurseForge credentials supplied through GitHub Actions secrets. Do not commit API keys, signing keys, Firebase service files or private SDK binaries.

## MCBL fork identity and compatibility notes

- The application display name and update-check target are MCBL-specific.
- The current Android application ID remains `org.levimc.launcher` so the inherited Microsoft/Xbox authentication callback and associated integrations are not silently broken. A future application-ID change needs its own correctly registered authentication redirect and service configuration.
- The MCBL update checker points to this repository GitHub Releases. Until this fork publishes its own releases, automatic updates may have no release to offer.
- The launcher source is Apache-2.0. Keep the upstream license, relevant copyright notices and third-party notices when distributing modified builds.
- Minecraft is a trademark of Microsoft/Mojang. MCBL is an independent community fork and is not endorsed by Microsoft or Mojang.
- Do not include or redistribute Minecraft APKs, assets, credentials or proprietary libraries in this repository.

## Upstream project

[LiteLDev/LeviLauncher-Android](https://github.com/LiteLDev/LeviLauncher-Android) provides the upstream Android runtime and launcher architecture. MCBL retains that attribution and records its downstream changes here.

## License

Apache License 2.0. See [LICENSE](LICENSE).
