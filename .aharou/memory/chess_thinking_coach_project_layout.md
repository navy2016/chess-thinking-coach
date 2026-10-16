---
name: chess_thinking_coach_project_layout
description: chess-thinking-coach 项目的工程结构与构建约定，搭建或修改该 Android 工程时应读取
type: workflow
---
# 三棋思考教练（chess-thinking-coach）项目结构与环境约定

- 产品「三棋思考教练」（围棋/中国象棋/国际象棋）；核心流程：用户暂选落点→引擎替对方找最强应对→变化树→确认→评分讲解
- 项目根 `/root/workspace`；Android Gradle 单模块 app，Kotlin 2.0，根包 `com.sanqi.coach`；包结构含 core/go、core/xiangqi、core/chess 三棋规则引擎，另有 coach/ui/content/data/tts 等包
- 共享契约 `app/src/main/java/com/sanqi/coach/core/Types.kt`（含 `GameEngine<P>`、`Move`、`SearchResult`、`AppliedMove`、`GameStatus`），只读，严禁修改；供三引擎并行实现——围棋：提子、劫、禁入点、数子；象棋：全部规则、将军/将死；国象：易位、过路兵、升变
- 测试放 `app/src/test/java/com/sanqi/coach/core/...`，各引擎配 JUnit 单元测试
- 本地容器无 JDK，无法编译验证；代码由 GitHub Actions CI 跑 JUnit4 单测（test + assembleDebug + artifact 上传）验证，必须一次写对
- 远端仓库：GitHub 私有仓库 chess-thinking-coach