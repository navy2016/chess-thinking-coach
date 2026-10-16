---
name: chess_coach_project_setup
description: chess-thinking-coach 的 Android 技术栈与构建方式，改构建配置前先读
type: workflow
---
仓库 github.com/navy2016/chess-thinking-coach，Android 单模块 `:app`，applicationId/namespace 为 `com.sanqi.coach`。

- Gradle 8.10.2（wrapper）、AGP 8.7.3、Kotlin 2.0.21、Java 17、compileSdk/targetSdk 35、minSdk 24，release 未开混淆。
- CI：`.github/workflows/android.yml`（android-ci）先跑 `./gradlew testDebugUnitTest --stacktrace` 再 `./gradlew assembleDebug --stacktrace`。
- 代码位于 `~/workspace`，本地构建用 `./gradlew`。