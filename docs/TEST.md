# Testing UHabit Next

See [BUILD.md](BUILD.md) for the JDK, Android SDK and Gradle setup. Select checks appropriate to the change:

```sh
./gradlew :uhabits-core:jvmTest
./gradlew :uhabits-android:testDebugUnitTest
```

On Windows use `gradlew.bat`. Android instrumentation tests need a configured device or emulator and isolated test data. Build and JVM results do not establish visual or device behavior. Describe the tests, device configuration where applicable, and any checks that could not be run in the pull request.
