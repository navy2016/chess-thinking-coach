---
name: chess_thinking_coach_project_layout
description: chess-thinking-coach 项目的工程结构与构建约定，搭建或修改该 Android 工程时应读取
type: workflow
---
# chess-thinking-coach 项目结构约定

- 平台：Android，Gradle 单模块工程
- 公共层：`Types.kt` + `GameEngine` 接口，供三个规则引擎并行实现
  - 围棋：提子、劫、禁入点、数子
  - 象棋：全部规则、将军/将死
  - 国象：易位、过路兵、升变
- 测试：各引擎配 JUnit 单元测试
- CI：GitHub Actions workflow 执行 test + assembleDebug + artifact 上传
- 远端仓库：GitHub 私有仓库，命名 chess-thinking-coach