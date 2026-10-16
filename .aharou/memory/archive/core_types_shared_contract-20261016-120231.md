---
name: core_types_shared_contract
description: 共享契约 core/Types.kt 只读及其关键约定（改动 core 目录前读）
type: workflow
---
app/src/main/java/com/sanqi/coach/core/Types.kt 是 UI 层直接依赖的共享契约文件，只读、严禁修改；发现契约问题只报告不改。它定义 GameEngine<P> 接口（initialPosition/sideToMove/sideName/legalMoves/applyMove/evaluate/searchBest/squareDesc/copyPosition）及 GameStatus/Move/SearchResult/AppliedMove。关键约定：score 一律为当前行棋方视角（negamax 风格）；将死 ±100000、逼和/困毙 0；围棋停着用 Move(-1,-1,0)；applyMove 假定走法已通过 legalMoves 校验，不做二次检查。