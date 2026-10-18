# UHabit Next

<p align="center">
  <img alt="License: GPL v3" src="https://img.shields.io/badge/License-GPLv3-blue.svg" />
  <img alt="Platform" src="https://img.shields.io/badge/Platform-Android-green.svg" />
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.3.20-purple.svg" />
  <img alt="AGP" src="https://img.shields.io/badge/AGP-8.13.2-orange.svg" />
</p>

**UHabit Next** is an enhanced, open-source personal productivity application for Android. Building upon the elegant, privacy-first foundation of [Loop Habit Tracker](https://github.com/iSoron/uhabits), UHabit Next unifies habit formation, task management, hierarchical organizational containers, and in-depth performance analytics into a single native experience.

---

## Capabilities

### 1. Habit Tracking & Consistency
* **Advanced Habit Scoring**: Scientifically grounded formula calculates habit strength over time. Every completion builds momentum; occasional missed days decrease strength smoothly rather than resetting progress completely.
* **Flexible Schedules & Target Values**: Supports daily, weekly (e.g., 3 times per week), periodic schedules, and numerical targets (with customizable units like minutes, pages, or milliliters).
* **Reliable Reminders**: Customizable per-habit reminders with direct check-in notifications.
* **Home Screen Widgets**: Quick-check widgets to log repetitions directly from your home screen.

### 2. Native Task Management
* **Task Lifecycle & Hierarchy**: Plan, track, and complete discrete action items alongside recurring habits.
* **Subtasks & Parent Relationships**: Break down larger initiatives into actionable steps with ordered subtasks.
* **Planning Dates & Deadlines**: Assign planned execution dates and strict deadlines with chronological sorting and overdue identification.
* **Integrated Calendar Events**: View upcoming deadlines and scheduled tasks across daily and monthly calendar projections.

### 3. Organizational Containers
* **Hierarchical Workspaces**: Group habits and tasks into structured containers (projects, areas, spheres of responsibility).
* **Scoped Views & Filtering**: Focus on specific containers or browse aggregate lists with recursive subtree aggregation.
* **Custom Visual Cues**: Assign distinct colors and icons to containers for instant orientation.

### 4. In-Depth Statistics & Analytics
* **Habit Strength Trends**: Visualize long-term habit formation progress with smooth historical curves.
* **Completion Calendars & Frequency Heatmaps**: Inspect historical performance and monthly completion grids at a glance.
* **Optimized Computation Engine**: Fast, memoized evaluation for multi-year histories without UI stutter.

### 5. Privacy-First & Offline Architecture
* **100% Offline by Design**: Operates entirely on your device without mandatory accounts, external trackers, or third-party telemetry.
* **Data Ownership**: Full export and import support for spreadsheets (CSV) and raw database backups (SQLite).

---

## Screenshots

> [!NOTE]
> The screenshots below illustrate the core habit-tracking interface inherited from the upstream Loop Habit Tracker project. Updated screenshots featuring UHabit Next task management, organizational containers, and adaptive navigation will be added in a future release.

<p align="center">
  <img src="screenshots/1.png" alt="Main habit list" width="28%" />
  &nbsp;
  <img src="screenshots/3.png" alt="Habit strength" width="28%" />
  &nbsp;
  <img src="screenshots/4.png" alt="Habit history and streaks" width="28%" />
</p>

<p align="center">
  <img src="screenshots/2.png" alt="Edit habit" width="28%" />
  &nbsp;
  <img src="screenshots/5.png" alt="Widgets" width="28%" />
  &nbsp;
  <img src="screenshots/6.png" alt="Dark mode" width="28%" />
</p>

---

## Technology Stack & Requirements

* **Architecture**: Kotlin Multiplatform (`uhabits-core` domain & logic, `uhabits-android` presentation & platform integration)
* **Language**: Kotlin 2.3.20
* **Build System**: Gradle 8.13 with Gradle Wrapper
* **Android Gradle Plugin (AGP)**: 8.13.2
* **Compilation Target**: `compileSdk 36`, `minSdk 21`
* **Java Development Kit (JDK)**: JDK 17 (e.g., Eclipse Temurin 17)
* **Code Shrinking & Optimization**: R8 8.13.19 (bundled with AGP)
* **Database**: SQLite (via native KMP IO abstractions)

---

## Building from Source

### Prerequisites

1. **JDK 17**: Ensure `JAVA_HOME` points to a valid JDK 17 installation:
   ```bash
   java -version
   ```
2. **Android SDK**: Install Android SDK command-line tools and platform components (SDK Platform 36 and platform-tools). Ensure `ANDROID_HOME` is set.

### Build Commands

Clone the repository and build using the Gradle wrapper:

```bash
# Clone the repository
git clone https://github.com/PalPalych5/uhabits-plus.git
cd uhabits-plus

# Build the Debug APK
./gradlew :uhabits-android:assembleDebug

# Build the Unsigned Release APK
./gradlew :uhabits-android:assembleRelease

# Run Core JVM and Android Unit Tests
./gradlew :uhabits-core:jvmTest :uhabits-android:testDebugUnitTest :uhabits-android:testReleaseUnitTest
```

Generated APKs will be located in:
* Debug APK: `uhabits-android/build/outputs/apk/debug/uhabits-android-debug.apk`
* Release APK: `uhabits-android/build/outputs/apk/release/uhabits-android-release-unsigned.apk`

For additional setup and troubleshooting details, see [docs/BUILD.md](docs/BUILD.md).

---

## Project Status & Upstream Relationship

* **Development Fork**: This repository (`PalPalych5/uhabits-plus`) is an independent, active development fork advancing the UHabit Next initiative.
* **Upstream Attribution**: UHabit Next is derived from [Loop Habit Tracker](https://github.com/iSoron/uhabits), created by **Álinson Santos Xavier** and contributors. We acknowledge and appreciate the excellent engineering and design of the original project.
* **Non-Affiliation Notice**: UHabit Next is not the official upstream Loop Habit Tracker release. Upstream Loop binaries distributed on Google Play or F-Droid do not contain UHabit Next task-management, container, or architectural enhancements.
* **Distribution**: Prebuilt binaries are not currently published to app stores. Users and contributors are invited to compile directly from source or follow the repository's GitHub releases.

---

## Contributing

Contributions, feedback, and bug reports are welcome! Please review [docs/GUIDELINES.md](docs/GUIDELINES.md) before submitting pull requests.

---

## License

```
Copyright (C) 2016-2025 Álinson Santos Xavier <isoron@gmail.com>
Copyright (C) 2025-2026 UHabit Next contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <https://www.gnu.org/licenses/>.
```
