package com.sanqi.coach.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.sanqi.coach.R
import com.sanqi.coach.coach.CoachSession
import com.sanqi.coach.coach.Explainer
import com.sanqi.coach.coach.Grade
import com.sanqi.coach.coach.MoveRating
import com.sanqi.coach.core.GameEngine
import com.sanqi.coach.core.GameStatus
import com.sanqi.coach.core.Move
import com.sanqi.coach.core.go.GoPosition
import com.sanqi.coach.data.GameStore
import com.sanqi.coach.data.StoredGame
import com.sanqi.coach.data.StoredMove
import com.sanqi.coach.tts.TtsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

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
    private lateinit var kit: GameKit
    private lateinit var engine: GameEngine<Any>
    private lateinit var session: CoachSession<Any>
    private lateinit var prefs: android.content.SharedPreferences
    private var mode = CoachSession.Mode.TWO_PLAYER
    private var boardSize = 19
    private var gameId = ""
    private var startedAt = 0L
    private var aiDepth = 3
    private var confirmOn = true
    private var gameDone = false
    private var resultText = ""
    private var loadFailed = false
    private var reviewMoves: List<StoredMove> = emptyList()
    private var reviewIndex = -1
    private var pending: Move? = null
    private var preview: CoachSession.Preview? = null
    private var branch = 0
    private var lineStep = 0
    private var selected = -1
    private var targets: Set<Int> = emptySet()
    private val dead = mutableSetOf<Int>()
    private var feedback = ""
    private var lastExplanation = ""
    private var busy = false
    private var busyMessage = 0
    private var request = 0L
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tts: TtsManager? = null
    private lateinit var board: BoardView
    private lateinit var information: TextView
    private lateinit var records: TextView
    private lateinit var badge: TextView
    private lateinit var analysisPanel: LinearLayout
    private lateinit var actions: LinearLayout
    private lateinit var controls: LinearLayout
    private var wide = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("coach", MODE_PRIVATE)
        kind = intent.getStringExtra(K_KIND) ?: "chess"
        aiDepth = prefs.getInt("aiDepth", 3).coerceIn(2, 4)
        confirmOn = prefs.getBoolean("confirmOn", true)
        val reviewId = intent.getStringExtra(K_REVIEW)
        val resumeId = savedInstanceState?.getString("activeId") ?: intent.getStringExtra(K_RESUME)
        val stored = (reviewId ?: resumeId)?.let { GameStore.load(this, it) }
        if ((reviewId != null || resumeId != null) && stored == null) {
            loadFailed = true
            AlertDialog.Builder(this).setMessage(R.string.load_failed).setPositiveButton(R.string.close) { _, _ -> finish() }.show()
            return
        }
        mode = if (reviewId != null) CoachSession.Mode.REVIEW else
            CoachSession.Mode.entries.firstOrNull { it.name == (stored?.mode ?: intent.getStringExtra(K_MODE)) }
                ?.takeUnless { it == CoachSession.Mode.REVIEW } ?: CoachSession.Mode.TWO_PLAYER
        aiDepth = stored?.aiDepth?.coerceIn(2, 4) ?: aiDepth
        boardSize = stored?.boardSize ?: intent.getIntExtra(K_SIZE, 19)
        gameId = stored?.id ?: "g${System.currentTimeMillis()}"
        startedAt = stored?.startedAt ?: System.currentTimeMillis()
        kit = GameKit(kind, boardSize)
        engine = kit.engine
        session = newSession()
        gameDone = stored?.finished ?: false
        resultText = stored?.resultText.orEmpty()
        try {
            if (mode == CoachSession.Mode.REVIEW) {
                reviewMoves = stored?.moves.orEmpty()
                reviewIndex = (savedInstanceState?.getInt("reviewIndex", -1) ?: -1).coerceIn(-1, reviewMoves.size - 1)
                replayTo(reviewIndex)
            } else {
                for (move in stored?.moves.orEmpty()) restoreMove(move)
                updateResult()
            }
        } catch (_: IllegalArgumentException) {
            loadFailed = true
            AlertDialog.Builder(this).setMessage(R.string.invalid_record).setPositiveButton(R.string.close) { _, _ -> finish() }.show()
            return
        }
        val restoredDead = savedInstanceState?.getIntArray("dead")?.toList() ?: stored?.deadStones.orEmpty()
        if (kind == "go") {
            val pos = session.current as GoPosition
            dead.addAll(restoredDead.filter { it in pos.board.indices && pos.board[it] != 0 })
        }
        savedInstanceState?.getIntArray("pending")?.let {
            val move = Move(it[0], it[1], it[2])
            if (!gameDone && session.isMoveLegal(move)) pending = move
        }
        branch = savedInstanceState?.getInt("branch", 0) ?: 0
        lineStep = savedInstanceState?.getInt("lineStep", 0) ?: 0
        tts = TtsManager(this)
        buildUi()
        render()
    }

    override fun onResume() {
        super.onResume()
        if (loadFailed || !::session.isInitialized) return
        render()
        if (mode != CoachSession.Mode.REVIEW && !gameDone && !scoring()) {
            if (mode == CoachSession.Mode.VS_AI && session.sideToMove() == 1) aiTurn()
            else pending?.let { propose(it, false) }
        }
    }

    private fun newSession(): CoachSession<Any> = CoachSession(engine, mode, aiDepth, kind == "chess")

    private fun restoreMove(move: StoredMove) {
        val grade = Grade.entries.firstOrNull { it.label == move.gradeName }
        val rating = grade?.let { MoveRating(move.notation, move.loss, it, null, move.explanation) }
        session.commitReview(Move(move.from, move.to, move.promo), rating)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun color(id: Int): Int = getColor(id)
    private fun text(value: String, size: Float = 15f, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = value
        textSize = size
        setTextColor(color(R.color.ink))
        if (bold) setTypeface(Typeface.DEFAULT_BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun button(label: String, primary: Boolean = false, enabled: Boolean = true, click: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            this.text = label
            isAllCaps = false
            textSize = 14f
            minWidth = 0
            minimumWidth = 0
            minimumHeight = dp(48)
            cornerRadius = dp(24)
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(color(R.color.coach_outline))
            backgroundTintList = ColorStateList.valueOf(color(if (primary) R.color.coach_accent else R.color.coach_surface))
            setTextColor(color(if (primary) R.color.coach_on_accent else R.color.ink))
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.4f
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { click() }
        }

    private fun buildUi() {
        wide = resources.configuration.screenWidthDp >= 720 &&
            resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
        val root = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.coach_surface))
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val left = column().apply { setPadding(dp(12), dp(12), dp(10), dp(8)) }
        information = text("", if (wide) 14f else 13f)
        records = text("", 13f)
        controls = column()
        left.addView(information)
        if (wide) {
            left.removeView(information)
            val infoBody = column()
            infoBody.addView(text(getString(R.string.match_information), 18f, true))
            infoBody.addView(information)
            infoBody.addView(text(getString(R.string.move_record), 14f, true).apply { setPadding(0, dp(18), 0, dp(8)) })
            infoBody.addView(records)
            val recordScroll = ScrollView(this).apply { addView(infoBody) }
            left.addView(recordScroll, LinearLayout.LayoutParams(-1, 0, 1f))
            left.addView(controls)
            root.addView(left, LinearLayout.LayoutParams(dp(150), -1))
        } else {
            val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            header.addView(button(getString(R.string.back)) { finish() }, LinearLayout.LayoutParams(-2, -2))
            left.removeView(information)
            header.addView(information, LinearLayout.LayoutParams(0, -2, 1f))
            header.setPadding(dp(10), 0, dp(12), 0)
            root.addView(header)
        }
        val center = column().apply { setBackgroundColor(color(R.color.paper)) }
        badge = text("", 12f, true).apply {
            gravity = Gravity.CENTER
            setTextColor(color(R.color.coach_purple))
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        center.addView(badge, LinearLayout.LayoutParams(-1, -2))
        board = BoardView(this).apply {
            adapter = kit.adapter()
            onTap = { onBoardTap(it) }
            contentDescription = getString(R.string.board_description)
        }
        center.addView(board, LinearLayout.LayoutParams(-1, 0, 1f))
        if (wide) root.addView(center, LinearLayout.LayoutParams(0, -1, 1f))
        else root.addView(center, LinearLayout.LayoutParams(-1, 0, 1.25f))
        val right = column().apply { setPadding(dp(12), dp(8), dp(12), dp(8)) }
        analysisPanel = column()
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(analysisPanel) }
        right.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        right.addView(actions, LinearLayout.LayoutParams(-1, -2))
        if (wide) root.addView(right, LinearLayout.LayoutParams(dp(265), -1))
        else root.addView(right, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        androidx.core.view.ViewCompat.requestApplyInsets(root)
    }

    private fun sideLabel(side: Int): String = getString(when (kind) {
        "go" -> if (side == 0) R.string.side_black else R.string.side_white
        "xiangqi" -> if (side == 0) R.string.side_red else R.string.side_black
        else -> if (side == 0) R.string.side_white else R.string.side_black
    })

    private fun scoring(): Boolean = !gameDone && kind == "go" && (session.current as GoPosition).consecutivePasses >= 2

    private fun render() {
        val turn = sideLabel(session.sideToMove())
        val depth = getString(when (aiDepth) { 2 -> R.string.level_fast; 4 -> R.string.level_strong; else -> R.string.level_standard })
        val modeLabel = getString(when {
            mode == CoachSession.Mode.REVIEW -> R.string.mode_review
            confirmOn -> R.string.mode_guided
            else -> R.string.mode_direct
        })
        information.text = if (wide) {
            buildString {
                append(getString(R.string.info_side, turn)).append("\n")
                if (kind == "go") {
                    val pos = session.current as GoPosition
                    append(getString(R.string.info_go, pos.size, kit.goEngine!!.komi)).append("\n")
                    append(getString(R.string.info_captures, pos.captures[0], pos.captures[1])).append("\n")
                }
                append(getString(R.string.info_level, depth)).append("\n")
                append(getString(R.string.info_turn, session.history.size + 1)).append("\n")
                append(getString(R.string.info_mode, modeLabel))
            }
        } else getString(R.string.compact_information, turn, session.history.size + 1, modeLabel)
        records.text = session.history.mapIndexed { i, entry -> "${i + 1}. ${entry.applied.notation}" }.joinToString("\n")
            .ifEmpty { getString(R.string.no_moves) }
        controls.removeAllViews()
        if (wide) {
            val firstRow = LinearLayout(this)
            firstRow.addView(button(getString(R.string.back)) { finish() }, LinearLayout.LayoutParams(0, -2, 1f))
            if (mode != CoachSession.Mode.REVIEW) {
                firstRow.addView(button(getString(R.string.undo), enabled = !busy && !gameDone && session.history.isNotEmpty()) { undo() }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            controls.addView(firstRow)
            if (mode != CoachSession.Mode.REVIEW) {
                val secondRow = LinearLayout(this)
                if (kind == "go") secondRow.addView(button(getString(R.string.pass), enabled = !busy && !gameDone && !scoring()) { clearPreview(); propose(Move(-1, -1)) }, LinearLayout.LayoutParams(0, -2, 1f))
                secondRow.addView(button(getString(R.string.resign), enabled = !busy && !gameDone && !scoring()) { resign() }, LinearLayout.LayoutParams(0, -2, 1f))
                controls.addView(secondRow)
            }
        }
        badge.text = when {
            preview != null -> getString(R.string.preview_badge)
            busy -> getString(busyMessage)
            scoring() -> getString(R.string.scoring_badge)
            mode == CoachSession.Mode.REVIEW -> getString(R.string.review_badge)
            else -> getString(R.string.live_badge)
        }
        analysisPanel.removeAllViews()
        actions.removeAllViews()
        when {
            mode == CoachSession.Mode.REVIEW -> renderReview()
            gameDone -> {
                analysisPanel.addView(text(resultText, 20f, true))
                analysisPanel.addView(button(getString(R.string.review_game)) { openReview() })
                if (!wide) addAction(getString(R.string.back)) { finish() }
                addAction(getString(R.string.new_game), true) { newGame() }
            }
            busy -> {
                analysisPanel.addView(text(getString(busyMessage), 18f, true))
                analysisPanel.addView(text(getString(R.string.analysis_wait), 13f))
                addAction(getString(R.string.cancel_analysis)) {
                    cancelWork()
                    clearPreview()
                    if (mode == CoachSession.Mode.VS_AI && session.sideToMove() == 1) finish() else render()
                }
            }
            scoring() -> {
                analysisPanel.addView(text(getString(R.string.scoring_help), 16f, true))
                addAction(getString(R.string.resume_play)) { clearPreview(); session.resumeGo(); dead.clear(); saveGame(); render(); maybeAi() }
                addAction(getString(R.string.finish_scoring), true) { finishScoring() }
            }
            preview != null -> renderPreview(preview!!)
            else -> {
                analysisPanel.addView(text(getString(R.string.choose_move), 19f, true))
                analysisPanel.addView(text(feedback.ifEmpty { getString(R.string.choose_move_help) }, 14f))
                if (!wide) {
                    analysisPanel.addView(text(getString(R.string.move_record), 14f, true))
                    analysisPanel.addView(text(records.text.toString(), 12f))
                }
                analysisPanel.addView(button(getString(R.string.compare_candidates)) { showCandidates() })
                if (lastExplanation.isNotEmpty()) analysisPanel.addView(button(getString(R.string.read_explanation)) { speak(lastExplanation) })
                if (!wide) {
                    val row = LinearLayout(this)
                    row.addView(button(getString(R.string.undo), enabled = session.history.isNotEmpty()) { undo() }, LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(button(getString(R.string.resign)) { resign() }, LinearLayout.LayoutParams(0, -2, 1f))
                    analysisPanel.addView(row)
                }
                if (kind == "go" && !wide) addAction(getString(R.string.pass)) { propose(Move(-1, -1)) }
            }
        }
        refreshBoard()
    }

    private fun addAction(label: String, primary: Boolean = false, enabled: Boolean = true, click: () -> Unit) {
        actions.addView(button(label, primary, enabled, click), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
    }

    private fun renderPreview(p: CoachSession.Preview) {
        analysisPanel.addView(text(getString(R.string.hypothetical_analysis), 20f, true))
        analysisPanel.addView(text(getString(R.string.preview_move, p.notation), 17f, true))
        analysisPanel.addView(text(getString(R.string.preview_grade, Explainer.gradeLabel(this, p.grade), p.loss), 14f).apply {
            setTextColor(color(if (p.loss <= 80) R.color.coach_positive else R.color.coach_warning))
        })
        analysisPanel.addView(text(getString(R.string.analysis_depth, p.analysis.completedDepth, p.analysis.requestedDepth), 12f))
        if (p.analysis.limited || p.repliesLimited) analysisPanel.addView(text(getString(R.string.analysis_limited), 12f))
        analysisPanel.addView(text(getString(if (kind == "go") R.string.go_score_unit else R.string.chess_score_unit), 12f))
        if (p.analysis.heuristic) analysisPanel.addView(text(getString(R.string.heuristic_notice), 12f))
        analysisPanel.addView(text(getString(R.string.opponent_replies), 17f, true).apply { setPadding(0, dp(12), 0, dp(6)) })
        analysisPanel.addView(text(getString(R.string.reply_depth, p.replyDepth), 12f))
        if (p.replies.isEmpty()) analysisPanel.addView(text(getString(R.string.no_reply), 13f))
        for ((index, reply) in p.replies.withIndex()) {
            val card = column().apply {
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(color(if (index == branch) R.color.coach_selected else R.color.coach_surface))
                    setStroke(dp(if (index == branch) 2 else 1), color(if (index == branch) R.color.coach_accent else R.color.coach_outline))
                }
                setOnClickListener { branch = index; lineStep = reply.moves.size; render() }
            }
            card.addView(text(getString(R.string.reply_heading, index + 1, reply.score), 13f, true))
            card.addView(text(reply.notations.mapIndexed { i, notation ->
                getString(R.string.reply_step, i + 1, sideLabel((session.sideToMove() + 1 + i) % 2), notation)
            }.joinToString("   "), 13f))
            analysisPanel.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        if (p.replies.isNotEmpty()) {
            branch = branch.coerceIn(0, p.replies.lastIndex)
            val total = p.replies[branch].moves.size
            lineStep = lineStep.coerceIn(0, total)
            val playback = LinearLayout(this)
            playback.addView(button(getString(R.string.previous_step), enabled = lineStep > 0) { lineStep--; render() }, LinearLayout.LayoutParams(0, -2, 1f))
            playback.addView(text("$lineStep / $total", 12f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-2, -1))
            playback.addView(button(getString(R.string.next_step), enabled = lineStep < total) { lineStep++; render() }, LinearLayout.LayoutParams(0, -2, 1f))
            analysisPanel.addView(playback)
        }
        analysisPanel.addView(button(getString(R.string.local_explanation)) {
            val message = Explainer.explain(this, p)
            AlertDialog.Builder(this).setTitle(R.string.local_explanation).setMessage(message)
                .setPositiveButton(R.string.read_aloud) { _, _ -> speak(message) }.setNegativeButton(R.string.close, null).show()
        })
        analysisPanel.addView(text(getString(R.string.local_only_notice), 11f))
        addAction(getString(R.string.change_move)) { clearPreview(); render() }
        addAction(getString(R.string.confirm_move), true) { commitPending() }
    }

    private fun refreshBoard() {
        var pos = session.current
        val p = preview
        val line = p?.replies?.getOrNull(branch)?.moves.orEmpty().take(lineStep)
        if (p != null && session.accepts(p)) pos = session.previewPosition(p, branch, lineStep)
        kit.setBoardPos(board, pos)
        board.lastMove = if (p == null) session.lastEntry()?.move else null
        board.pendingMove = p?.move
        board.variationMoves = line
        board.variationStartSide = (session.sideToMove() + 1) % 2
        board.numberVariation = kind == "go"
        board.selectFrom = selected
        board.targetHighlights = targets
        board.deadMarks = dead
        board.showCoords = prefs.getBoolean("showCoords", true)
        board.refresh()
    }

    private fun onBoardTap(index: Int) {
        if (busy || gameDone || mode == CoachSession.Mode.REVIEW) return
        if (scoring()) { toggleDead(index); return }
        if (mode == CoachSession.Mode.VS_AI && session.sideToMove() != 0) return
        if (preview != null) { clearPreview(); render(); return }
        if (kind == "go") {
            val move = Move(index, index)
            if (session.isMoveLegal(move)) propose(move) else toast(R.string.illegal_move)
            return
        }
        val candidates = if (selected >= 0) session.legalMovesFrom(selected).filter { it.to == index } else emptyList()
        if (candidates.isNotEmpty()) {
            if (candidates.size > 1 && candidates.any { it.promo != 0 }) {
                val labels = candidates.map { getString(when (it.promo) {
                    2 -> R.string.piece_queen; 3 -> R.string.piece_rook; 4 -> R.string.piece_bishop; else -> R.string.piece_knight
                }) }.toTypedArray()
                AlertDialog.Builder(this).setTitle(R.string.choose_promotion).setItems(labels) { _, which -> propose(candidates[which]) }.show()
            } else propose(candidates.first())
        } else {
            val moves = session.legalMovesFrom(index)
            selected = if (moves.isEmpty()) -1 else index
            targets = moves.map { it.to }.toSet()
            refreshBoard()
        }
    }

    private fun clearPreview() {
        pending = null
        preview = null
        branch = 0
        lineStep = 0
        selected = -1
        targets = emptySet()
    }

    private fun cancelWork() {
        request++
        job?.cancel()
        job = null
        busy = false
    }

    private fun <T> work(message: Int, calculate: (()->Unit) -> T, complete: (T) -> Unit) {
        if (busy) return
        busy = true
        busyMessage = message
        val token = ++request
        val revision = session.revision
        render()
        job = scope.launch {
            try {
                val value = withContext(Dispatchers.Default) {
                    val context = currentCoroutineContext()
                    calculate { context.ensureActive() }
                }
                if (token != request || revision != session.revision || isFinishing || isDestroyed) return@launch
                busy = false
                job = null
                complete(value)
            } catch (_: CancellationException) {
                if (token == request) { busy = false; job = null }
            } catch (_: Exception) {
                if (token == request && !isFinishing && !isDestroyed) {
                    busy = false
                    job = null
                    clearPreview()
                    feedback = getString(R.string.analysis_failed)
                    render()
                }
            }
        }
    }

    private fun propose(move: Move, resetPlayback: Boolean = true) {
        if (busy || !session.isMoveLegal(move)) return
        pending = move
        if (resetPlayback) { branch = 0; lineStep = 0 }
        work(R.string.analyzing, { check -> session.analyze(aiDepth, move, check) }) { p ->
            preview = p
            branch = branch.coerceIn(0, maxOf(0, p.replies.lastIndex))
            lineStep = lineStep.coerceIn(0, p.replies.getOrNull(branch)?.moves?.size ?: 0)
            if (!confirmOn && mode != CoachSession.Mode.REVIEW) commitPending() else render()
        }
    }

    private fun commitPending() {
        if (busy) return
        val p = preview ?: return
        if (!session.accepts(p)) { clearPreview(); render(); return }
        val explanation = Explainer.explain(this, p)
        session.commit(p.move, p, explanation)
        feedback = explanation
        lastExplanation = explanation
        clearPreview()
        updateResult()
        saveGame()
        speak(explanation)
        render()
        maybeAi()
    }

    private fun maybeAi() {
        if (mode == CoachSession.Mode.VS_AI && session.sideToMove() == 1 && !gameDone && !scoring()) aiTurn()
    }

    private fun aiTurn() {
        if (busy || gameDone || scoring()) return
        work(R.string.opponent_thinking, { check ->
            val choice = session.candidates(aiDepth, check).candidates.firstOrNull()?.move
            choice?.let { session.analyze(aiDepth, it, check) }
        }) { p ->
            if (p != null && session.accepts(p)) {
                val explanation = Explainer.explain(this, p)
                session.commit(p.move, p, explanation)
                feedback = explanation
                lastExplanation = explanation
                clearPreview()
                updateResult()
                saveGame()
                speak(explanation)
            }
            render()
        }
    }

    private fun showCandidates() {
        work(R.string.analyzing, { check -> session.candidates(aiDepth, check) }) { analysis ->
            render()
            val candidates = analysis.candidates.take(3)
            val labels = candidates.map {
                getString(R.string.candidate_label, engine.applyMove(session.current, it.move).notation, it.score)
            }.toTypedArray()
            if (labels.isNotEmpty()) AlertDialog.Builder(this).setTitle(R.string.compare_candidates)
                .setItems(labels) { _, which -> propose(candidates[which].move) }.setNegativeButton(R.string.close, null).show()
        }
    }

    private fun undo() {
        if (busy || gameDone || mode == CoachSession.Mode.REVIEW) return
        cancelWork()
        clearPreview()
        dead.clear()
        session.undo()
        feedback = getString(R.string.undo_done)
        saveGame()
        render()
        maybeAi()
    }

    private fun updateResult() {
        val status = session.endStatus ?: return
        gameDone = true
        resultText = when {
            session.winner != null -> getString(R.string.winner_result, sideLabel(session.winner!!),
                getString(if (status == GameStatus.CHECKMATE) R.string.end_checkmate else R.string.end_no_moves))
            else -> getString(R.string.end_draw)
        }
    }

    private fun toggleDead(index: Int) {
        val pos = session.current as GoPosition
        val stone = pos.board[index]
        if (stone == 0) return
        val group = mutableSetOf(index)
        val queue = ArrayDeque<Int>()
        queue.add(index)
        while (queue.isNotEmpty()) {
            val square = queue.removeFirst()
            val x = square % pos.size
            val y = square / pos.size
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until pos.size || ny !in 0 until pos.size) continue
                val next = ny * pos.size + nx
                if (pos.board[next] == stone && group.add(next)) queue.add(next)
            }
        }
        if (dead.containsAll(group)) dead.removeAll(group) else dead.addAll(group)
        refreshBoard()
    }

    private fun finishScoring() {
        val score = kit.goEngine!!.scoreArea(session.current as GoPosition, dead)
        val difference = score.black - score.white
        resultText = if (difference == 0.0) getString(R.string.end_draw) else
            getString(R.string.go_result, sideLabel(if (difference > 0) 0 else 1), abs(difference) / 2)
        gameDone = true
        saveGame()
        render()
    }

    private fun resign() {
        AlertDialog.Builder(this).setTitle(R.string.resign).setMessage(R.string.resign_question)
            .setPositiveButton(R.string.resign) { _, _ ->
                clearPreview()
                gameDone = true
                resultText = getString(R.string.resign_result, sideLabel(1 - session.sideToMove()))
                saveGame()
                render()
            }.setNegativeButton(R.string.close, null).show()
    }

    private fun newGame() {
        startActivity(Intent(this, GameActivity::class.java).putExtra(K_KIND, kind).putExtra(K_SIZE, boardSize).putExtra(K_MODE, mode.name))
        finish()
    }

    private fun saveGame() {
        if (mode == CoachSession.Mode.REVIEW || loadFailed) return
        val moves = session.history.map { entry ->
            val rating = entry.rating
            StoredMove(entry.move.from, entry.move.to, entry.move.promo, entry.applied.notation,
                rating?.loss ?: 0, rating?.grade?.label.orEmpty(), rating?.explanation.orEmpty())
        }
        try {
            GameStore.save(this, StoredGame(gameId, kind, mode.name, aiDepth, boardSize, startedAt,
                System.currentTimeMillis(), gameDone, resultText, moves, dead.toList()))
        } catch (_: Exception) {
            toast(R.string.save_failed)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (!loadFailed && ::session.isInitialized) {
            saveGame()
            outState.putString("activeId", gameId)
            outState.putInt("reviewIndex", reviewIndex)
            pending?.let { outState.putIntArray("pending", intArrayOf(it.from, it.to, it.promo)) }
            outState.putInt("branch", branch)
            outState.putInt("lineStep", lineStep)
            outState.putIntArray("dead", dead.toIntArray())
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        cancelWork()
        tts?.stop()
        if (!loadFailed && ::session.isInitialized) saveGame()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun speak(message: String) {
        if (prefs.getBoolean("ttsOn", true)) tts?.speak(message, prefs.getFloat("ttsRate", 1f))
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun openReview() {
        startActivity(Intent(this, GameActivity::class.java).putExtra(K_KIND, kind).putExtra(K_SIZE, boardSize).putExtra(K_REVIEW, gameId))
        finish()
    }

    private fun replayTo(index: Int) {
        session = newSession()
        for (move in reviewMoves.take(index + 1)) restoreMove(move)
    }

    private fun renderReview() {
        analysisPanel.addView(text(getString(R.string.review_progress, reviewIndex + 1, reviewMoves.size), 18f, true))
        if (resultText.isNotEmpty()) analysisPanel.addView(text(resultText, 14f))
        reviewMoves.getOrNull(reviewIndex)?.let {
            analysisPanel.addView(text(it.notation, 16f, true))
            analysisPanel.addView(text(it.explanation, 14f))
        }
        addAction(getString(R.string.previous_step), enabled = reviewIndex >= 0) { reviewIndex--; replayTo(reviewIndex); render() }
        addAction(getString(R.string.next_step), enabled = reviewIndex + 1 < reviewMoves.size) { reviewIndex++; replayTo(reviewIndex); render() }
    }
}
