# AniGooli AI Agent Instructions

1. **Mandatory Version Bump on Every Change:**
   Whenever updating the application, making new changes, adding features, fixing bugs, or deleting code/assets, **always** increment `versionCode` (by `+1`) and update `versionName` (semantic versioning: patch `x.y.Z` for fixes/tweaks, minor `x.Y.0` for new features, major `X.0.0` for breaking changes) in `app/build.gradle.kts`.
   When publishing a GitHub Release, push the matching `v<versionName>` tag so `.github/workflows/release.yaml` publishes `AniGooli-v<versionName>.apk`.

2. **Cloud-Only Gradle Builds:**
   Local hardware is limited — **never** run `./gradlew` builds or tests locally. Push changes to GitHub (`Arthur-001/AniGooli`) and verify unit tests, lint, and Debug/Release APK builds via GitHub Actions (`.github/workflows/build-apk.yaml` and `.github/workflows/release.yaml`).

3. **Branding & Ownership:**
   AniGooli belongs to `Arthur-001` (`https://github.com/Arthur-001/AniGooli`, package `com.anigooli.app`). Do not introduce external Animite URLs, logos, or branding assets into the app UI or resources.
