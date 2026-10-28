# UHabit Next

> **🚧 Active Development — Experimental**
>
> UHabit Next is under active development and has not reached a stable public release.
>
> Features, interfaces and data formats may change. Bugs and incomplete functionality are expected. The current builds are primarily intended for development and testing, not general everyday use.
>
> There is currently no officially supported stable UHabit Next release.

UHabit Next is an open-source Android application developed from [Loop Habit Tracker](https://github.com/iSoron/uhabits). It brings habit tracking, tasks, activity organization, focus sessions and statistics together in one app.

## Features

The current development builds include:

- Habit tracking, schedules, reminders, history and widgets.
- Tasks and subtasks, with due dates, tags and priority classification.
- Containers for organizing habits and tasks hierarchically.
- A planning calendar with month, week and day views and focus-session history.
- Stopwatch and Pomodoro focus sessions, including task and numerical-habit links.
- Unified Statistics views for habits, tasks and focus sessions.
- Light, Dark and AMOLED themes.

These features are under development. Behavior, data migration, backup and synchronization should not be assumed stable or compatible across arbitrary versions. Some integrations and refinements remain incomplete; see the code and tests for their current scope.

## Screenshots

An up-to-date UHabit Next screenshot gallery will be added later.

## Building from Source

Use JDK 17 and an Android SDK with platform 36. The repository includes the Gradle 8.13 wrapper; the build uses Android Gradle Plugin 8.13.2 and Kotlin 2.3.20. The app targets Android SDK 36 and requires Android 9 (API 28) or newer.

```sh
git clone https://github.com/PalPalych5/uhabits-plus.git
cd uhabits-plus
./gradlew :uhabits-android:assembleDebug
```

On Windows, use `gradlew.bat` instead of `./gradlew`. The debug APK is written under `uhabits-android/build/outputs/apk/debug/`. For setup and test commands, see [Building](docs/BUILD.md).

## Project Relationship & Credits

UHabit Next is an independent fork and is not an official Loop Habit Tracker release. Loop was created by [Álinson Santos Xavier](https://github.com/iSoron) and its contributors; their work and authorship remain part of this project.

The project is licensed under the [GNU General Public License, version 3 or later](LICENSE.txt). See [NOTICE](NOTICE.md) and [Third-Party Notices](THIRD_PARTY_NOTICES.md) for additional credits and licenses.

## Contributing

Issues and pull requests are welcome in this repository. Please describe the problem, reproduction steps or proposed change, and the checks you ran. Discuss substantial changes before investing in a large implementation; the project is experimental and interfaces may change. See the [contributor guidelines](docs/GUIDELINES.md).
