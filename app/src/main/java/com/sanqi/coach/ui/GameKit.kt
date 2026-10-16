package com.sanqi.coach.ui

import com.sanqi.coach.core.AnyEngine
import com.sanqi.coach.core.GameEngine
import com.sanqi.coach.core.chess.ChessEngine
import com.sanqi.coach.core.go.GoEngine
import com.sanqi.coach.core.go.GoPosition
import com.sanqi.coach.core.xiangqi.XiangqiEngine
import com.sanqi.coach.core.xiangqi.XiangqiPosition

/**
 * 按棋种组装引擎与棋盘适配器，供教学 / 残局页面复用。
 */
class GameKit(val kind: String, private val goSize: Int) {

    val goEngine: GoEngine? = if (kind == "go") GoEngine(goSize) else null
    val xiangqiEngine: XiangqiEngine? = if (kind == "xiangqi") XiangqiEngine() else null
    val chessEngine: ChessEngine? = if (kind == "chess") ChessEngine() else null

    val engine: GameEngine<Any> = when (kind) {
        "go" -> AnyEngine(goEngine!!)
        "xiangqi" -> AnyEngine(xiangqiEngine!!)
        "chess" -> AnyEngine(chessEngine!!)
        else -> throw IllegalArgumentException(kind)
    }

    fun adapter(): BoardAdapter = when (kind) {
        "go" -> GoBoard(goEngine!!)
        "xiangqi" -> XiangqiBoard(xiangqiEngine!!)
        "chess" -> ChessBoard(chessEngine!!)
        else -> throw IllegalArgumentException(kind)
    }

    fun positionFromBoard(board: IntArray, toMove: Int): Any = when (kind) {
        "go" -> GoPosition(goSize, board, toMove, intArrayOf(0, 0), -1, 0)
        "xiangqi" -> xiangqiEngine!!.fromBoard(board, toMove)
        "chess" -> chessEngine!!.fromBoard(board, true, 0, -1)
        else -> throw IllegalArgumentException(kind)
    }

    fun setBoardPos(view: BoardView, pos: Any) {
        when (kind) {
            "go" -> (view.adapter as GoBoard).pos = pos as GoPosition
            "xiangqi" -> (view.adapter as XiangqiBoard).pos = pos as XiangqiPosition
            "chess" -> (view.adapter as ChessBoard).pos = pos as com.sanqi.coach.core.chess.ChessPosition
        }
        view.refresh()
    }
}
