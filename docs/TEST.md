# Testing UHabit Next

See [BUILD.md](BUILD.md) for the JDK, Android SDK and Gradle setup. Select checks appropriate to the change:

## Running Unit Tests

Unit tests run quickly inside a JVM on the host machine and do not require an Android emulator or device:

```sh
./gradlew :uhabits-core:jvmTest
./gradlew :uhabits-android:testDebugUnitTest
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

## Running Instrumented Tests

Android instrumentation tests require an appropriately configured device or emulator (API 35+ recommended):

```sh
./gradlew :uhabits-android:connectedDebugAndroidTest
```

Or run targeted test classes:

```sh
./gradlew :uhabits-android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.isoron.uhabits.qa.TaskSectionVisibilityAndroidTest
```

**Testing Invariants:**
- Always run instrumentation tests against isolated synthetic test datasets. Never run destructive test setups or clear commands against physical devices or environments containing real user data.
- Build and JVM results verify logic correctness, but do not establish device, layout, or visual behavior.
- For testing strategy, tiered QA levels, and visual verification guidelines, see [QA Strategy and Verification](../tools/qa/README.md).
