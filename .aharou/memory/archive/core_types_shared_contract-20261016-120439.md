---
name: core_types_shared_contract
description: 共享契约 core/Types.kt 只读及其关键约定（改动 core 目录前读）
type: workflow
---
app/src/main/java/com/sanqi/coach/core/Types.kt 是 UI 层直接依赖的共享契约文件，只读、严禁修改；发现契约问题只报告不改。它定义 GameEngine<P> 接口（initialPosition/sideToMove/sideName/legalMoves/applyMove/evaluate/searchBest/squareDesc/copyPosition）及 GameStatus/Move/SearchResult/AppliedMove。走法统一为 Move(from, to, promo)，from/to 为各棋种自定义的格子/交叉点索引、不通用；停着一律用 Move(-1,-1,0) 表示（围棋 PASS）；国象升变 promo 与棋子编码一致：2=后、3=车、4=象、5=马。GameStatus 枚举为 ONGOING/CHECK/CHECKMATE/STALEMATE/DRAW，applyMove 返回的 status 描述走完这一步之后的局面。SearchResult.score 一律为当前行棋方视角（negamax 风格）；将死取 ±100000、逼和/困毙取 0。applyMove 假定走法已通过 legalMoves 校验，不做二次检查。