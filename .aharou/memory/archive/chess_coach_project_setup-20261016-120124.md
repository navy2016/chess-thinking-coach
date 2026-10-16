---
name: chess_coach_project_setup
description: chess-thinking-coach 安卓项目的构建栈与 CI 配置，继续开发或修改构建时读
type: workflow
---
项目 chess-thinking-coach（单模块 `:app`，namespace/applicationId 为 `com.sanqi.coach`）。

- 构建栈：Gradle wrapper 8.10.2，AGP 8.7.3，Kotlin 2.0.21；compileSdk/targetSdk 35，minSdk 24，Java 17。
- gradle.properties：`android.useAndroidX=true`、`android.nonTransitiveRClass=true`。
- CI：`.github/workflows/android.yml`，push 触发，步骤为 `./gradlew testDebugUnitTest` 与 `./gradlew assembleDebug`，env 设 `GRADLE_OPTS=-Dorg.gradle.daemon=false`。
- 入口 Activity：`.ui.HomeActivity`（exported launcher）。