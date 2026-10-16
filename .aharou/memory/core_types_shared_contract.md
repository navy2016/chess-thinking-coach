---
name: core_types_shared_contract
description: 共享契约 core/Types.kt 只读及其关键约定（改动 core 目录前读）
type: workflow
---
app/src/main/java/com/sanqi/coach/core/Types.kt 是 UI 层直接依赖的共享契约文件，只读、严禁修改；发现契约问题只报告不改（改 core、写引擎或解析走法前必读）。它定义 GameEngine<P> 接口（initialPosition/sideToMove/sideName/legalMoves/applyMove/evaluate/searchBest/squareDesc/copyPosition）及 GameStatus/Move/SearchResult/AppliedMove。跨棋种约定：统一走法 Move(from, to, promo)，from/to 为引擎自定义的格子/交叉点索引，停着一律 Move(-1,-1,0)（围棋 PASS）；国象升变 promo 编码与棋子编码一致（2=后、3=车、4=象、5=马）；score 一律为当前行棋方视角（negamax 风格），分越高对当前方越有利，将死 ±100000、逼和/困毙 0；GameStatus 取值 ONGOING/CHECK/CHECKMATE/STALEMATE/DRAW，applyMove 返回的 status 描述走完这一步后的局面，且假定走法已通过 legalMoves 校验、不做二次检查。