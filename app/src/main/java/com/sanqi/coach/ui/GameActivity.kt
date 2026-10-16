package com.sanqi.coach.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.sanqi.coach.coach.CoachSession
import com.sanqi.coach.coach.Grade
import com.sanqi.coach.coach.MoveRating
import com.sanqi.coach.core.AnyEngine
import com.sanqi.coach.core.GameEngine
import com.sanqi.coach.core.Move
import com.sanqi.coach.core.chess.ChessEngine
import com.sanqi.coach.core.chess.ChessPosition
import com.sanqi.coach.core.go.GoEngine
import com.sanqi.coach.core.go.GoPosition
import com.sanqi.coach.core.xiangqi.XiangqiEngine
import com.sanqi.coach.core.xiangqi.XiangqiPosition
import com.sanqi.coach.data.GameStore
import com.sanqi.coach.data.StoredGame
import com.sanqi.coach.data.StoredMove
import com.sanqi.coach.tts.TtsManager
import java.util.Locale

class GameActivity : AppCompatActivity() {

    companion object {
        const val K_KIND = "kind"
        const val K_MODE = "mode"
        const val K_REVIEW = "reviewId"
        const val K_SIZE = "size"
        const val K_RESUME = "resume"

        fun kindLabel(kind: String): String = when (kind) {
            "go" -> "围棋"
            "xiangqi" -> "中国象棋"
            "chess" -> "国际象棋"
            else -> kind
        }
    }

    private lateinit var kind: String
    private var mode: CoachSession.Mode = CoachSession.Mode.TWO_PLAYER
    private lateinit var session: CoachSession<Any>
    private lateinit var engine: GameEngine<Any>
    private var goEngine: GoEngine? = null
    private var xiangqiEngine: XiangqiEngine? = null
    private var chessEngine: ChessEngine? = null

    private lateinit var boardView: BoardView
    private lateinit var statusText: TextView
    private lateinit var coachText: TextView
    private lateinit var actionRow: LinearLayout
    private lateinit var titleView: TextView

    private var tts: TtsManager? = null

    private var busy = false
    private var gameId: String = ""
    private var startedAt = 0L
    private var boardSize = 19

    private var selectFrom = -1
    private val targets = mutableSetOf<Int>()
    private var pending: Move? = null
    private var pendingPreview: CoachSession.Preview? = null

    private val goDead = mutableSetOf<Int>()
    private var goEnded = false
    private var gameDone = false
    private var goResult = ""

    private var feedback = ""
    private var lastExplanation: String? = null

    private var reviewId: String? = null
    private var reviewMoves: List<StoredMove> = emptyList()
    private var reviewIndex = -1
    private var reviewResult = ""
    private var resumeMoves: List<StoredMove> = emptyList()

    private lateinit var prefs: android.content.SharedPreferences
    private var aiDepth = 3
    private var confirmOn = true
    private var ttsOn = true
    private var ttsRate = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kind = intent.getStringExtra(K_KIND) ?: "chess"
        reviewId = intent.getStringExtra(K_REVIEW)
        prefs = getSharedPreferences("coach", MODE_PRIVATE)
        aiDepth = prefs.getInt("aiDepth", 3)
        confirmOn = prefs.getBoolean("confirmOn", true)
        ttsOn = prefs.getBoolean("ttsOn", true)
        ttsRate = prefs.getFloat("ttsRate", 1.0f)
        val resumeId = intent.getStringExtra(K_RESUME)
        val stored: com.sanqi.coach.data.StoredGame? = if (resumeId != null) GameStore.load(this, resumeId) else null
        if (stored != null && stored.kind == kind) {
            mode = try {
                CoachSession.Mode.valueOf(stored.mode)
            } catch (_: Exception) {
                CoachSession.Mode.TWO_PLAYER
            }
            boardSize = stored.boardSize
            gameId = stored.id
            startedAt = stored.startedAt
            resumeMoves = stored.moves
        } else if (reviewId != null) {
            mode = CoachSession.Mode.REVIEW
            gameId = reviewId ?: ""
            boardSize = intent.getIntExtra(K_SIZE, 19)
        } else {
            newFreshGame(intent.getStringExtra(K_MODE))
        }
        buildEngine()
        session = newSession()
        tts = TtsManager(this)
        if (resumeMoves.isNotEmpty()) {
            for (m in resumeMoves) {
                val grade = Grade.entries.firstOrNull { it.label == m.grade }
                val rating = if (m.grade.isNotEmpty()) {
                    MoveRating(m.notation, m.loss, grade ?: Grade.GOOD, null, m.explanation)
                } else null
                session.commitReview(Move(m.from, m.to, m.promo), rating)
            }
            feedback = "已恢复上局，继续训练"
        }
        if (reviewId != null) loadReview()
        buildUi()
        refreshBoard()
        renderStatus()
        if (mode == CoachSession.Mode.VS_AI && session.sideToMove() == 1 && !gameDone && session.gameOverText == null) {
            aiTurn()
        }
    }

    private fun newFreshGame(modeName: String?) {
        mode = if (modeName != null) {
            try {
                CoachSession.Mode.valueOf(modeName)
            } catch (_: Exception) {
                CoachSession.Mode.TWO_PLAYER
            }
        } else {
            CoachSession.Mode.TWO_PLAYER
        }
        boardSize = intent.getIntExtra(K_SIZE, 19)
        gameId = "g" + System.currentTimeMillis()
        startedAt = System.currentTimeMillis()
    }

    private fun newSession(): CoachSession<Any> =
        CoachSession(engine, mode, aiDepth, kind == "chess", engine.initialPosition())

    private fun buildEngine() {
        when (kind) {
            "go" -> {
                val e = GoEngine(boardSize)
                goEngine = e
                engine = AnyEngine(e)
            }
            "xiangqi" -> {
                val e = XiangqiEngine()
                xiangqiEngine = e
                engine = AnyEngine(e)
            }
            else -> {
                val e = ChessEngine()
                chessEngine = e
                engine = AnyEngine(e)
            }
        }
    }

    private fun makeAdapter(): BoardAdapter = when (kind) {
        "go" -> GoBoard(goEngine!!)
        "xiangqi" -> XiangqiBoard(xiangqiEngine!!)
        else -> ChessBoard(chessEngine!!)
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(android.graphics.Color.parseColor("#F6F1E4"))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(16, 40, 24, 12)
        val back = MaterialButton(this)
        back.text = "返回"
        back.isAllCaps = false
        back.setOnClickListener { finish() }
        top.addView(back, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        titleView = TextView(this)
        titleView.textSize = 19f
        titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        top.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = 24
        })
        root.addView(top)

        val holder = FrameLayout(this)
        boardView = BoardView(this)
        boardView.adapter = makeAdapter()
        boardView.onTap = { onTap(it) }
        holder.addView(boardView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        root.addView(holder, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.45f))

        statusText = TextView(this)
        statusText.textSize = 15f
        statusText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        statusText.setPadding(36, 12, 36, 4)
        root.addView(statusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(this)
        coachText = TextView(this)
        coachText.textSize = 14f
        coachText.setPadding(36, 4, 36, 16)
        scroll.addView(coachText)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.55f))

        val actionScroll = HorizontalScrollView(this)
        actionScroll.isFillViewport = false
        actionRow = LinearLayout(this)
        actionRow.orientation = LinearLayout.HORIZONTAL
        actionRow.setPadding(20, 4, 20, 32)
        actionScroll.addView(actionRow)
        root.addView(actionScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        titleView.text = kindLabel(kind) + if (mode == CoachSession.Mode.REVIEW) " · 复盘" else ""
        setContentView(root)
    }

    private fun setActions(vararg defs: Pair<String, () -> Unit>) {
        actionRow.removeAllViews()
        for (d in defs) {
            val b = MaterialButton(this)
            b.text = d.first
            b.isAllCaps = false
            b.setOnClickListener { d.second() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = 20
            actionRow.addView(b, lp)
        }
    }

    private fun speak(text: String?) {
        if (ttsOn && text != null && text.isNotEmpty()) tts?.speak(text, ttsRate)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------------- 棋盘交互 ----------------

    private fun onTap(idx: Int) {
        if (busy || mode == CoachSession.Mode.REVIEW || gameDone) return
        if (goEnded) {
            toggleDead(idx)
            return
        }
        if (session.gameOverText != null) return
        if (pendingPreview != null) cancelPending()
        if (kind == "go") {
            val m = Move(idx, idx, 0)
            if (!session.isMoveLegal(m)) {
                toast("这里不能落子")
                return
            }
            proposeMove(m)
        } else {
            if (selectFrom == -1) {
                val moves = session.legalMovesFrom(idx)
                if (moves.isNotEmpty()) {
                    selectFrom = idx
                    targets.clear()
                    targets.addAll(moves.map { it.to })
                    refreshBoard()
                }
            } else {
                val cand = session.legalMovesFrom(selectFrom).filter { it.to == idx }
                val mv = if (cand.isNotEmpty()) {
                    cand.firstOrNull { it.promo == 2 } ?: cand.first()
                } else null
                if (mv != null) {
                    proposeMove(mv)
                } else if (session.legalMovesFrom(idx).isNotEmpty()) {
                    selectFrom = idx
                    targets.clear()
                    targets.addAll(session.legalMovesFrom(idx).map { it.to })
                    refreshBoard()
                } else {
                    clearSelection()
                    refreshBoard()
                }
            }
        }
    }

    private fun clearSelection() {
        selectFrom = -1
        targets.clear()
        boardView.selectFrom = -1
        boardView.targetHighlights = emptySet()
    }

    private fun proposeMove(m: Move) {
        if (confirmOn) {
            pending = m
            busy = true
            renderStatus("正在替对方推演……")
            Thread {
                val preview = session.analyze(aiDepth, m)
                runOnUiThread {
                    busy = false
                    pendingPreview = preview
                    boardView.pendingMove = m
                    refreshBoard()
                    renderStatus()
                }
            }.start()
        } else {
            commit(m, null)
        }
    }

    private fun cancelPending() {
        val m = pending
        pending = null
        pendingPreview = null
        boardView.pendingMove = null
        if (m != null && kind != "go" && m.from >= 0) {
            selectFrom = m.from
            targets.clear()
            targets.addAll(session.legalMovesFrom(m.from).map { it.to })
            boardView.selectFrom = selectFrom
            boardView.targetHighlights = targets
        }
        refreshBoard()
    }

    private fun confirmPending() {
        val m = pending ?: return
        val p = pendingPreview
        pending = null
        pendingPreview = null
        boardView.pendingMove = null
        commit(m, p)
    }

    private fun commit(m: Move, preview: CoachSession.Preview?) {
        busy = true
        renderStatus("正在落子……")
        Thread {
            val entry = session.commit(m, preview)
            val r = entry.rating
            val f = if (r != null) {
                "第 " + session.history.size + " 手  " + r.notation + "【" + r.grade.label + "】\n" + r.explanation
            } else ""
            runOnUiThread {
                busy = false
                feedback = f
                lastExplanation = r?.explanation
                clearSelection()
                speak(r?.explanation)
                if (session.gameOverText != null) {
                    gameDone = true
                    saveGame()
                    refreshBoard()
                    renderStatus()
                } else if (mode == CoachSession.Mode.VS_AI && session.sideToMove() == 1) {
                    refreshBoard()
                    aiTurn()
                } else {
                    refreshBoard()
                    renderStatus()
                }
            }
        }.start()
    }

    private fun aiTurn() {
        busy = true
        renderStatus("对方正在思考……")
        Thread {
            var entry = session.aiMove()
            if (entry == null && kind == "go" && session.gameOverText == null) {
                entry = session.commitPass()
            }
            val r = entry?.rating
            val f = if (r != null) {
                "对方走了 " + r.notation + "【" + r.grade.label + "】\n" + r.explanation
            } else ""
            runOnUiThread {
                busy = false
                feedback = f
                lastExplanation = r?.explanation
                checkGoEnd()
                if (session.gameOverText != null) gameDone = true
                if (gameDone) saveGame()
                refreshBoard()
                renderStatus()
            }
        }.start()
    }

    private fun userPass() {
        if (busy || gameDone) return
        busy = true
        Thread {
            session.commitPass()
            runOnUiThread {
                busy = false
                feedback = "已停一手；双方连续停手后进入终局计分"
                checkGoEnd()
                if (goEnded) {
                    goDead.clear()
                } else if (mode == CoachSession.Mode.VS_AI && session.sideToMove() == 1) {
                    aiTurn()
                    return@runOnUiThread
                }
                refreshBoard()
                renderStatus()
            }
        }.start()
    }

    private fun checkGoEnd() {
        if (kind != "go") return
        val p = session.current as GoPosition
        goEnded = p.consecutivePasses >= 2
    }

    private fun toggleDead(idx: Int) {
        val p = session.current as GoPosition
        val engineGo = goEngine ?: return
        val color = engineGo.stoneAt(p, idx)
        if (color == 0) return
        val seen = HashSet<Int>()
        val stack = ArrayDeque<Int>()
        stack.addLast(idx)
        seen.add(idx)
        val size = p.size
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            val x = c % size
            val y = c / size
            for (d in intArrayOf(1, -1)) {
                val nx = x + d
                if (nx in 0 until size) {
                    val n = y * size + nx
                    if (!seen.contains(n) && engineGo.stoneAt(p, n) == color) {
                        seen.add(n)
                        stack.addLast(n)
                    }
                }
                val ny = y + d
                if (ny in 0 until size) {
                    val n = ny * size + x
                    if (!seen.contains(n) && engineGo.stoneAt(p, n) == color) {
                        seen.add(n)
                        stack.addLast(n)
                    }
                }
            }
        }
        if (goDead.containsAll(seen)) goDead.removeAll(seen) else goDead.addAll(seen)
        refreshBoard()
    }

    private fun finishScoring() {
        val p = session.current as GoPosition
        val engineGo = goEngine ?: return
        val s = engineGo.scoreArea(p, goDead)
        goResult = s.winnerText
        goEnded = false
        gameDone = true
        feedback = "对局结束：" + s.winnerText +
            String.format(Locale.US, "（黑 %.1f · 白 %.1f）", s.black, s.white)
        saveGame()
        speak(feedback)
        refreshBoard()
        renderStatus()
    }

    private fun hint() {
        if (busy || gameDone || goEnded) return
        busy = true
        renderStatus("正在寻找推荐走法……")
        Thread {
            val s = engine.searchBest(session.current, aiDepth)
            val notation = s.bestMove?.let { m ->
                engine.applyMove(engine.copyPosition(session.current), m).notation
            }
            runOnUiThread {
                busy = false
                boardView.hintTo = s.bestMove?.to ?: -1
                refreshBoard()
                feedback = if (notation != null) {
                    "推荐走法：" + notation + "（当前局面评分 " + s.score + "）"
                } else {
                    "当前没有找到更好的走法，可以考虑停一手"
                }
                renderStatus()
            }
        }.start()
    }

    private fun undo() {
        if (busy || mode == CoachSession.Mode.REVIEW || gameDone) return
        if (session.history.isEmpty()) return
        busy = true
        Thread {
            session.undo()
            runOnUiThread {
                busy = false
                feedback = "已悔棋"
                goEnded = false
                goDead.clear()
                clearSelection()
                refreshBoard()
                renderStatus()
            }
        }.start()
    }

    // ---------------- 状态渲染 ----------------

    private fun renderStatus(busyMsg: String? = null) {
        if (mode == CoachSession.Mode.REVIEW) {
            renderReview()
            return
        }
        val side = engine.sideName(session.sideToMove())
        statusText.text = when {
            gameDone -> "对局结束：" + (session.gameOverText ?: goResult)
            goEnded -> "终局确认：点击棋子标记死子，然后点「完成计分」"
            busyMsg != null -> busyMsg
            pendingPreview != null -> "暂选就绪：先看对方最强应对，再决定走不走"
            else -> "第 " + (session.history.size + 1) + " 手 · 轮到 " + side
        }

        val sb = StringBuilder()
        pendingPreview?.let { p ->
            sb.append(p.treeText).append("\n\n").append(p.explanation)
        }
        if (sb.isEmpty()) sb.append(feedback)
        coachText.text = sb.toString()

        when {
            gameDone -> setActions(
                "复盘本局" to { openReviewSelf() },
                "回到首页" to { finish() }
            )
            goEnded -> setActions(
                "完成计分" to { finishScoring() },
                "恢复对局" to {
                    goEnded = false
                    goDead.clear()
                    refreshBoard()
                    renderStatus()
                }
            )
            pendingPreview != null -> setActions(
                "确认落子" to { confirmPending() },
                "换一个走法" to { cancelPending() },
                "悔棋" to { undo() }
            )
            else -> {
                val list = mutableListOf<Pair<String, () -> Unit>>()
                if (kind == "go") list.add("停一手" to { userPass() })
                list.add("提示" to { hint() })
                list.add("悔棋" to { undo() })
                if (lastExplanation != null) list.add("朗读讲解" to { speak(lastExplanation) })
                setActions(*list.toTypedArray())
            }
        }
        refreshBoard()
    }

    private fun refreshBoard() {
        when (kind) {
            "go" -> (boardView.adapter as GoBoard).pos = session.current as GoPosition
            "xiangqi" -> (boardView.adapter as XiangqiBoard).pos = session.current as XiangqiPosition
            else -> (boardView.adapter as ChessBoard).pos = session.current as ChessPosition
        }
        boardView.lastMove = session.history.lastOrNull()?.move
        boardView.deadMarks = goDead
        boardView.showCoords = prefs.getBoolean("showCoords", true)
        boardView.selectFrom = selectFrom
        boardView.targetHighlights = targets
        boardView.refresh()
    }

    // ---------------- 保存与复盘 ----------------

    private fun saveGame() {
        if (mode == CoachSession.Mode.REVIEW) return
        val moves = session.history.map { e ->
            val r = e.rating
            StoredMove(
                e.move.from, e.move.to, e.move.promo,
                r?.notation ?: e.applied.notation,
                r?.loss ?: 0, r?.grade?.label ?: "", r?.explanation ?: ""
            )
        }
        val game = StoredGame(
            id = gameId,
            kind = kind,
            mode = mode.name,
            aiDepth = aiDepth,
            boardSize = boardSize,
            startedAt = startedAt,
            updatedAt = System.currentTimeMillis(),
            finished = gameDone,
            resultText = session.gameOverText ?: goResult,
            moves = moves
        )
        GameStore.save(this, game)
    }

    override fun onStop() {
        super.onStop()
        if (mode != CoachSession.Mode.REVIEW && session.history.isNotEmpty()) saveGame()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private fun openReviewSelf() {
        val intent = android.content.Intent(this, GameActivity::class.java)
        intent.putExtra(K_KIND, kind)
        intent.putExtra(K_SIZE, boardSize)
        intent.putExtra(K_REVIEW, gameId)
        startActivity(intent)
        finish()
    }

    private fun loadReview() {
        val g = GameStore.load(this, reviewId!!)
        if (g != null) {
            reviewMoves = g.moves
            reviewResult = g.resultText
        }
    }

    private fun renderReview() {
        val total = reviewMoves.size
        statusText.text = "复盘 · 第 " + (reviewIndex + 1) + " / " + total + " 手" +
            (if (reviewResult.isNotEmpty()) " · " + reviewResult else "")
        val m = reviewMoves.getOrNull(reviewIndex)
        coachText.text = if (m != null) {
            val g = if (m.grade.isNotEmpty()) "【" + m.grade + "】" else ""
            "第 " + (reviewIndex + 1) + " 手  " + m.notation + g + "\n" + m.explanation
        } else {
            "点击「下一步」开始打谱回顾，每一步都有当时的评分与讲解。"
        }
        setActions(
            "上一步" to { stepReview(-1) },
            "下一步" to { stepReview(1) },
            "返回" to { finish() }
        )
    }

    private fun stepReview(delta: Int) {
        val target = (reviewIndex + delta).coerceIn(-1, reviewMoves.size - 1)
        if (target == reviewIndex) return
        session = newSession()
        for (i in 0..target) {
            val m = reviewMoves[i]
            val grade = Grade.entries.firstOrNull { it.label == m.grade }
            val rating = if (m.grade.isNotEmpty()) {
                MoveRating(m.notation, m.loss, grade ?: Grade.GOOD, null, m.explanation)
            } else null
            session.commitReview(Move(m.from, m.to, m.promo), rating)
        }
        reviewIndex = target
        refreshBoard()
        renderReview()
    }
}
