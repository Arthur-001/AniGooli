# Contributing

Contributions of all kinds are welcome! If you plan to work on a new feature or significant change, please open an issue first to discuss the idea and avoid duplicated effort.

## Getting Started
1. **Fork & Clone** the repository:
   ```bash
   git clone https://github.com/<your-username>/AniGooli.git
   cd AniGooli
   ```
2. **Create a branch** for your change:
   ```bash
   git checkout -b feat/your-feature-name
   ```
3. **Make your changes** in Android Studio or your preferred editor:
   - Keep code formatted and clean (Detekt rules live in `config/detekt/detekt.yml`).
   - If your change modifies app code, features, or resources, increment `versionCode` and update `versionName` in `app/build.gradle.kts`.
4. **Verify via GitHub Actions:**
   - Push your branch to your fork and open a Pull Request against `main`.
   - GitHub Actions automatically runs unit tests (`testDebugUnitTest`) and builds Debug/Release APKs on PRs, so even if your local hardware is limited, CI verifies your build for you.

## Pull Requests and Commits
- Commit messages should be concise sentences without trailing periods; add an optional description sentence with a period when helpful (e.g., `git commit -am "Fix player seek gesture" -m "Clamp seek preview within episode duration."`).
- Add a clear PR description explaining what changed, why, and any relevant issue or documentation links.
