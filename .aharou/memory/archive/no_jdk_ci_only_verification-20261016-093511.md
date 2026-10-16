---
name: no_jdk_ci_only_verification
description: 写本项目引擎或测试代码前必读——容器无 JDK、验证只能靠 CI JUnit4，对可用库有严格限制
type: workflow
---
- 本地容器没有 JDK，无法编译验证；CI 在 GitHub Actions 上跑 JUnit4 单测作为唯一验证手段，所以代码必须一次写对。
- 硬约束：纯 Kotlin/JVM，只用最朴素的 Kotlin 标准库；禁止 Android 类、禁止第三方库、禁止 kotlin.test；测试只能用 `org.junit.Test` 和 `org.junit.Assert`。