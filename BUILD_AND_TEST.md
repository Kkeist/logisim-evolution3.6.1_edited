# Build, Test, and Release

## Quick test (Windows, no JAVA_HOME needed)

1. Install JDK 21 to `C:\Program Files\Java\jdk-21` if not already there.
2. Double-click **run.bat** in this folder (or run it from a command prompt).
3. The script sets `JAVA_HOME` for this run and starts Logisim Evolution with `gradlew run`. The window will stay open; close it when done. The console will pause so you can see any errors.

## Build releases (Windows and macOS)

The project uses Gradle and jpackage. **You must build on each OS to get that OS’s installer** (e.g. build on Windows for Windows, on macOS for macOS).

### Windows (MSI installer)

- **Requirement:** Build on a Windows machine with JDK 21 (e.g. `C:\Program Files\Java\jdk-21`). No need to set `JAVA_HOME` if you use **run.bat** (it sets it for the run).
- From the project root:

```batch
set "JAVA_HOME=C:\Program Files\Java\jdk-21"
gradlew.bat createMsi
```

- Output: `build\dist\logisim-evolution-<version>.msi`

### macOS (DMG / .app)

- **Requirement:** Build on a Mac with JDK 21 installed.
- From the project root:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew createDmg
```

- Output: `build/dist/logisim-evolution-<version>.dmg` and `build/dist/Logisim-evolution.app` (from the `createApp` task that `createDmg` depends on).

### One command for current platform

- **Windows:** `gradlew.bat createAll` → runs `createMsi`.
- **macOS:** `./gradlew createAll` → runs `createDmg`.
- **Linux:** `./gradlew createAll` → runs `createDeb` and `createRpm`.

## GitHub Actions release (Windows + macOS + JAR)

The workflow **Release** (`.github/workflows/release.yml`) builds installers and creates a GitHub Release.

**How to release:**

1. Set `version = 3.6.1` (or your version) in `gradle.properties` and commit.
2. **Option A – tag push:** Create and push a tag, e.g. `v3.6.1`:
   ```bash
   git tag v3.6.1
   git push origin v3.6.1
   ```
3. **Option B – manual run:** In the repo go to **Actions → Release → Run workflow**, choose branch and run.

**Artifacts:** The workflow builds on Windows and macOS and produces:

- `logisim-evolution-<version>.msi` — Windows installer
- `logisim-evolution-<version>.dmg` — macOS installer
- `logisim-evolution-<version>-all.jar` — cross‑platform JAR (any OS with Java)

These are attached to the GitHub Release for the chosen tag (e.g. **v3.6.1**).

## New features in this build

- **Copy/duplicate with auto-rename:** Pasting or duplicating components (e.g. SW1) automatically renames duplicates (SW2, SW3, …) when the label would conflict.
- **Beautify Wires:** Edit → Beautify Wires cleans up wire layout (merge overlapping, split at junctions) without moving components.
- **Auto-save:** File → Preferences → Layout: enable “Auto-save”, choose “After each change” or “Every N seconds”, and set the interval if needed. Saves only when the project already has a saved file.
