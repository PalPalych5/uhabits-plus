# UHabit Next

<p align="center">
  <img alt="License: GPL v3" src="https://img.shields.io/badge/License-GPLv3-blue.svg" />
  <img alt="Platform" src="https://img.shields.io/badge/Platform-Android-green.svg" />
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.3.20-purple.svg" />
  <img alt="AGP" src="https://img.shields.io/badge/AGP-8.13.2-orange.svg" />
  <img alt="Database" src="https://img.shields.io/badge/SQLite-Schema%20v35-informational.svg" />
</p>

**UHabit Next** is an enhanced, open-source personal productivity and habit-tracking application for Android. Building upon the elegant, privacy-first foundation of [Loop Habit Tracker](https://github.com/iSoron/uhabits), UHabit Next unifies habit formation, structured task execution, focus sessions, organizational containers, and multi-domain analytics into a single native experience.

---

## Capabilities

### 1. Habit Tracking & Strength
* **Scientifically Grounded Scoring**: Habit strength is computed over time using an exponential formula. Every completion builds momentum; occasional missed days decrease strength smoothly rather than abruptly resetting progress.
* **Flexible Schedules & Numerical Targets**: Supports daily cadences, weekly goals (e.g., 3 times per week), periodic schedules, and numerical targets (with customizable units like minutes, pages, or milliliters).
* **Reminders & Home Screen Widgets**: Customizable per-habit notifications and home screen widgets to log repetitions directly.

### 2. Task Management & Prioritization
* **Task Lifecycle & Hierarchy**: Plan, track, and complete discrete action items alongside recurring habits.
* **Subtasks & Parent Relationships**: Break complex initiatives into ordered subtasks with clear execution status.
* **Priority Classification & Eisenhower Matrix**: Organize tasks into Urgent/Important quadrants for focused daily planning.
* **Task Drafts & Tagging**: Capture thoughts quickly into task drafts and categorize tasks with flexible, searchable tags.
* **Deadlines & Planned Dates**: Assign planned execution dates and strict deadlines with overdue warnings and chronological sorting.

### 3. Focus Tracking & Habit Linkage
* **Pomodoro & Stopwatch Modes**: Integrated focus timers with custom intervals, breaks, and session labels.
* **Automatic Habit Time Accrual**: Link tasks directly to numerical minute habits; completed focus sessions automatically accrue tracked time toward your habit goals under strict non-retroactive attribution rules.
* **Process Resilience**: Active sessions survive background pauses, process recreation, and device configuration changes without data loss.

### 4. Multi-Scale Planning & History Calendar
* **Planning Calendar**: Visualize upcoming deadlines, scheduled tasks, and habit commitments across Month, Week, and Day views.
* **History Calendar**: Review past focus session execution timelines with daily aggregations and non-overlapping interval geometry.
* **Timezone & Boundary Safety**: Accurate handling of midnight transitions, daily distribution, and daylight saving time (DST) shifts.

### 5. Hierarchical Organizational Containers
* **Structured Workspaces**: Group habits and tasks into nested containers (projects, areas, spheres of life).
* **Scoped Views & Filtering**: Focus on an individual container or browse aggregate views with recursive subtree calculations.
* **Custom Styling**: Assign distinct colors and icons to containers for quick visual recognition.

### 6. Unified Statistics & Analytics
* **Four Dedicated Analytical Tabs**:
  * **Overview**: Combined high-level summary of habit strength, completed tasks, and total focus time.
  * **Habits**: Completion rates, ongoing cycle status, streaks, and responsive multi-cadence bar charts.
  * **Tasks**: Completion cohorts, urgency/importance breakdown, and drill-down into finished items.
  * **Focus**: Total focus duration, session frequency, and chronological session lists.
* **Interactive Filtering & Drill-Downs**: Filter analytics by timeframe (7 days, 30 days, 90 days, or custom ranges) and container scope. Tap any cohort or cycle to inspect contributing records.

### 7. Modern Adaptive UI & Accessibility
* **Theme Support**: Polished styling in Light, Dark, and true AMOLED themes.
* **Accessibility-First**: All interactive controls maintain minimum 48×48 dp touch target geometries, with robust layout resilience under 200% system font scaling and narrow screen widths (down to 320 dp).
* **Modern Settings**: Redesigned, card-based preferences architecture.

### 8. Privacy-First & Offline Architecture
* **100% Local Storage**: Operates entirely offline without requiring user accounts or third-party cloud backends.
* **Schema Reliability**: SQLite Schema v35 with safe, backward-compatible automated migrations.
* **Complete Data Ownership**: Export and import full database archives (JSON v4 snapshots) and spreadsheet-compatible CSV records at any time.

---

## Screenshots

> [!NOTE]
> The screenshots below illustrate the core habit-tracking interface inherited from the upstream Loop Habit Tracker project. Updated gallery screenshots featuring UHabit Next task management, calendars, focus tracking, and unified statistics will be added in upcoming releases.

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

* **Architecture**: Kotlin Multiplatform (`uhabits-core` domain logic, `uhabits-android` presentation and system integration)
* **Language**: Kotlin 2.3.20
* **Build System**: Gradle 8.13 with Gradle Wrapper
* **Android Gradle Plugin (AGP)**: 8.13.2
* **Compilation Target**: `compileSdk 36`, `minSdk 28`
* **Java Development Kit (JDK)**: JDK 17 (e.g., Eclipse Temurin 17)
* **Code Shrinking & Optimization**: R8 8.13.19 (bundled with AGP)
* **Database**: SQLite (via Kotlin Multiplatform IO abstractions), Schema v35

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

Generated APKs are located in:
* Debug APK: `uhabits-android/build/outputs/apk/debug/uhabits-android-debug.apk`
* Release APK: `uhabits-android/build/outputs/apk/release/uhabits-android-release-unsigned.apk`

For additional setup and troubleshooting details, see [docs/BUILD.md](docs/BUILD.md).

---

## Project Status & Upstream Relationship

* **Development Fork**: This repository (`PalPalych5/uhabits-plus`) is an independent, active development fork advancing the UHabit Next initiative.
* **Upstream Attribution**: UHabit Next is derived from [Loop Habit Tracker](https://github.com/iSoron/uhabits), created by **Álinson Santos Xavier** and contributors. We acknowledge and appreciate the foundational engineering and design of the original project.
* **Non-Affiliation Notice**: UHabit Next is not the official upstream Loop Habit Tracker release. Upstream Loop binaries distributed on Google Play or F-Droid do not contain UHabit Next task management, focus tracking, or architectural extensions.
* **Third-Party Components**: Third-party notices and licenses (including Tabler Icons under MIT) are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [NOTICE.md](NOTICE.md).

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
