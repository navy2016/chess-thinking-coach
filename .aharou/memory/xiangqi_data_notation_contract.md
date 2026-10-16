---
name: xiangqi_data_notation_contract
description: 象棋 XiangqiPosition 与中文记谱的数据契约（UI 层直接依赖），改象棋数据结构、记谱或对接 UI 前必读
type: fact
---
- `XiangqiPosition.board: IntArray` 长度 90，idx = y*9 + x；y=0 为红方底线（红在下），x=0 为红方视角最左（九路）。
- 棋子编码：0 空；红 1帅 2仕 3相 4马 5车 6炮 7兵；黑 -1将 -2士 -3象 -4马 -5车 -6炮 -7卒。
- applyMove/copyPosition 必须返回全新对象，绝不共享 board 数组。
- 记谱：红方用汉字一~九（x=8 为一路），黑方用数字 1~9（x=0 为 1 路）；示例锁定：初始红炮 y=2,x=7 平到 y=2,x=4 记作「炮二平五」。
- 附加公开 API：pieceAt / pieceLabel / isRed / fromBoard；sideName(0)="红方"，sideName(1)="黑方"。