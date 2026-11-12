# Building UHabit Next

UHabit Next is an experimental Android project. The following commands build the current source; they do not provide a supported stable release.

## Requirements

- JDK 17, with `JAVA_HOME` set to the JDK directory.
- Android SDK Platform 36 and platform-tools, with the SDK configured through `ANDROID_HOME` or `local.properties` (`sdk.dir`). Accept the relevant SDK licenses using Android Studio or `sdkmanager --licenses`.
- The repository's Gradle wrapper. It uses Gradle 8.13, Android Gradle Plugin 8.13.2 and Kotlin 2.3.20.

## Debug build

```sh
git clone https://github.com/PalPalych5/uhabits-plus.git
cd uhabits-plus
./gradlew :uhabits-android:assembleDebug -Puhabit.allowDefaultDebugKeystore=true
```

On Windows use `gradlew.bat` with the same arguments. The flag explicitly allows the local default debug signing key. To preserve update compatibility with an existing installation, configure its designated debug keystore using `UHABIT_DEBUG_KEYSTORE` or `uhabit.debug.keystore` in `local.properties` and omit the flag. A differently signed APK cannot update an existing installation. The debug APK is located in `uhabits-android/build/outputs/apk/debug/`. A release build requires appropriate signing configuration; this project does not provide a supported signed release through these instructions.

## Local tests

```sh
./gradlew :uhabits-core:jvmTest -Puhabit.allowDefaultDebugKeystore=true
./gradlew :uhabits-android:testDebugUnitTest -Puhabit.allowDefaultDebugKeystore=true
```

Android instrumentation tests require an appropriately configured device or emulator. Select tests for the affected behavior and use isolated test data; the JVM tests do not establish device or visual behavior.

For testing details and QA verification guidelines, see [QA strategy and verification](../tools/qa/README.md). Never run destructive test setup against a physical device or a dataset containing user data.
