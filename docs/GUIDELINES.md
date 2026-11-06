# Contributing to UHabit Next

UHabit Next is an independent, experimental fork of Loop Habit Tracker. It has no stable public release; features, architecture, and data formats are actively evolving.

## Communication & Issues

- Source code and issue tracking for UHabit Next take place on [GitHub](https://github.com/PalPalych5/uhabits-plus).
- Upstream Loop Habit Tracker is hosted at [iSoron/uhabits](https://github.com/iSoron/uhabits). Upstream copyright and license notices are preserved.
- Use this repository's issues and pull requests for bug reports and contributions.

## Submitting Code

- **Target branch:** Submit pull requests against `main` unless advised otherwise.
- **Scope & size:** Keep pull requests focused on a single concern. Separate bug fixes, new features, and refactoring into independent pull requests.
- **Discuss first:** For substantial changes (new features, architectural changes, data schema migrations), open an issue or draft pull request first to discuss the approach.
- **Safety:** Never include signing keystores, private credentials, local machine paths, or user data in pull requests.

## Code Style & Formatting

- **Kotlin:** We follow standard Kotlin code style and [ktlint](https://ktlint.github.io/). Run `./gradlew ktlintCheck` to verify formatting.
- **Java:** Follow the conventions of the surrounding legacy Java code when modifying existing classes; all new code should be written in Kotlin.

## Verification

- Run relevant unit tests before opening a pull request: `./gradlew :uhabits-core:jvmTest` and `./gradlew :uhabits-android:testDebugUnitTest`.
- Describe the changes, tests executed, device configurations (if applicable), and any known limitations in your pull request description.
- See [BUILD.md](BUILD.md), [TEST.md](TEST.md), and [QA Strategy](../tools/qa/README.md) for build and testing details.
