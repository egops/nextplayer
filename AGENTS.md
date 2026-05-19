# AGENTS.md

## Cursor Cloud specific instructions

This is an Android-native video player app (**Next Player**) written in Kotlin, built with Gradle.

### Environment prerequisites

- **Android SDK** must be installed at `/opt/android-sdk` with `ANDROID_HOME` / `ANDROID_SDK_ROOT` set.
- **JDK 21** is required (Gradle 9.2.1 + AGP 8.13.2).
- **JAVA_HOME** must point to the JDK 21 installation.
- These env vars are persisted in `~/.bashrc` by the setup.

### Key commands

| Task | Command |
|---|---|
| Lint (ktlint) | `./gradlew ktlintCheck -Pkotlin.jvm.target.validation.mode=warning` |
| Unit tests | `./gradlew testDebugUnitTest -Pkotlin.jvm.target.validation.mode=warning` |
| Build debug APK | `./gradlew assembleDebug -Pkotlin.jvm.target.validation.mode=warning` |
| Format code | `./gradlew ktlintFormat -Pkotlin.jvm.target.validation.mode=warning` |

### Known gotcha: JVM target validation

The `core:model` module uses the `kotlin-jvm` plugin (not Android) and sets Kotlin JVM target to 17, but does not set Java source/target compatibility. With JDK 21, the Java compiler defaults to target 21, which triggers a Kotlin validation error. Pass `-Pkotlin.jvm.target.validation.mode=warning` to all Gradle commands to work around this.

### Project structure

Multi-module Gradle project. See `settings.gradle.kts` for module list. Key modules:
- `:app` — main Android application
- `:core:*` — shared layers (data, database, datastore, domain, media, model, ui)
- `:feature:*` — feature modules (player, settings, videopicker)

### Testing notes

- Only `core:domain` has unit tests (`GetSortedVideosUseCaseTest`, 8 tests).
- Instrumented tests (`androidTest`) require an Android emulator/device — not available in Cloud Agent VMs.
- No backend services, databases, or Docker dependencies needed.
