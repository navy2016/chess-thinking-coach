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
import com.sanqi.coach.coach.CandidateLine
import com.sanqi.coach.coach.TentativePlans
import com.sanqi.coach.coach.TeachingInteraction
import com.sanqi.coach.coach.OnlineCoachClient
import com.sanqi.coach.coach.OnlineCoachSettings
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

class GameActivity : ImmersiveActivity() {
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
    private var professional: com.sanqi.coach.engine.ProfessionalEngineBackend? = null
    private var showSidebar = false
    private val replyCards = mutableListOf<LinearLayout>()
    private var previousLineButton: MaterialButton? = null
    private var nextLineButton: MaterialButton? = null
    private var lineCounter: TextView? = null
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
    private var alternative = false
    private val plans = TentativePlans()
    private var candidates: com.sanqi.coach.coach.PositionAnalysis? = null
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
    private var safeWidth = 0
    private var safeHeight = 0
    private lateinit var playbackPanel: LinearLayout
    private var progressText: TextView? = null
    private var analysisElapsed = 0f
    private var engineChoice: String? = null
    private val teaching = TeachingInteraction()
    private val onlineCoach = OnlineCoachClient()
    private var lessonJob: Job? = null
    private var lessonGeneration = 0L
    private var lessonBody: LinearLayout? = null
    private var lessonPreview: CoachSession.Preview? = null
    private var lessonParts = emptyList<Explainer.Part>()
    private var lessonOnline = false
    private var lessonThinking = true
    private var lessonNotice = ""
    private val discussion = mutableListOf<Pair<String, String>>()
    private var lessonCitations = emptyMap<String, List<Explainer.Part>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("coach", MODE_PRIVATE)
        kind = intent.getStringExtra(K_KIND) ?: "chess"
        engineChoice = prefs.getString("engine_$kind", null)
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
        if (mode != CoachSession.Mode.REVIEW) professional = com.sanqi.coach.engine.ProfessionalEngineBackend(this, kind, engine)
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
        for (index in 0..1) savedInstanceState?.getBundle("plan$index")?.let { state ->
            val values = state.getIntArray("move") ?: return@let
            val move = Move(values[0], values[1], values[2])
            if (!gameDone && session.isMoveLegal(move)) plans.restore(index, move,
                state.getInt("branch"), state.getInt("step"), state.getBoolean("alternative"))
        }
        val restoredPlan = plans.select(savedInstanceState?.getInt("activePlan", 0) ?: 0, session.revision)
        showPlan(if (savedInstanceState?.getBoolean("editingPlan") == true) null else restoredPlan)
        buildUi()
        render()
    }

    override fun onResume() {
        super.onResume()
        if (loadFailed || !::session.isInitialized) return
        val choice = prefs.getString("engine_$kind", null)
        if (choice != engineChoice) {
            cancelWork()
            clearPreview()
            engineChoice = choice
            feedback = getString(R.string.engine_selection_changed)
        }
        resetTeachingSpeech()
        render()
        if (mode != CoachSession.Mode.REVIEW && !gameDone && !scoring()) {
            if (mode == CoachSession.Mode.VS_AI && session.sideToMove() == 1) aiTurn()
        }
    }

    private fun resetTeachingSpeech() {
        tts?.shutdown()
        tts = TtsManager(this, offlineOnly = !OnlineCoachSettings(this).enabled) { message ->
            if (!isFinishing && !isDestroyed && message !in listOf(getString(R.string.tts_ready), getString(R.string.tts_speaking),
                    getString(R.string.tts_initializing), getString(R.string.edge_ready), getString(R.string.edge_loading)))
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun newSession(): CoachSession<Any> = CoachSession(engine, mode, aiDepth, kind == "chess", backend = professional)

    private fun restoreMove(move: StoredMove) {
        val grade = Grade.entries.firstOrNull { it.name == move.gradeName || it.label == move.gradeName }
        val rating = if (move.analysis != null || grade != null || move.explanation.isNotEmpty())
            MoveRating(move.notation, move.loss, grade, null, move.explanation, move.analysis) else null
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
            cornerRadius = dp(if (primary) 24 else 12)
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
        val widthDp = safeWidth.takeIf { it > 0 } ?: resources.configuration.screenWidthDp
        val heightDp = safeHeight.takeIf { it > 0 } ?: resources.configuration.screenHeightDp
        wide = widthDp > heightDp
        val panelWidth = minOf((widthDp * 0.32f).toInt().coerceIn(240, 300), (widthDp * 0.48f).toInt())
        showSidebar = wide && resources.configuration.fontScale < 1.4f &&
            widthDp - panelWidth - 168 >= (heightDp - 40) * (if (kind == "xiangqi") 0.9f else 1f)
        val root = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.coach_surface))
        }
        val left = column().apply { setPadding(dp(12), dp(12), dp(10), dp(8)) }
        information = text("", if (wide) 14f else 13f)
        records = text("", 13f)
        controls = column()
        left.addView(information)
        if (showSidebar) {
            left.removeView(information)
            val infoBody = column()
            infoBody.addView(text(getString(R.string.match_information), 18f, true))
            infoBody.addView(information)
            infoBody.addView(text(getString(R.string.move_record), 14f, true).apply { setPadding(0, dp(18), 0, dp(8)) })
            infoBody.addView(records)
            val recordScroll = ScrollView(this).apply { addView(infoBody) }
            left.addView(recordScroll, LinearLayout.LayoutParams(-1, 0, 1f))
            left.addView(controls)
            root.addView(left, LinearLayout.LayoutParams(dp(168), -1))
        } else if (!wide) {
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
            setTextColor(color(R.color.coach_muted))
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        if (wide && !showSidebar) {
            left.removeView(information)
            val compact = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            compact.addView(button(getString(R.string.back)) { finish() }, LinearLayout.LayoutParams(-2, -2))
            compact.addView(information, LinearLayout.LayoutParams(0, -2, 1f))
            center.addView(compact)
        }
        val boardTools = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        boardTools.addView(badge, LinearLayout.LayoutParams(0, -2, 1f))
        boardTools.addView(button(getString(R.string.reset_board)) { board.resetViewport() })
        center.addView(boardTools, LinearLayout.LayoutParams(-1, -2))
        board = BoardView(this).apply {
            adapter = kit.adapter()
            onTap = { onBoardTap(it) }
            contentDescription = getString(R.string.board_description)
        }
        center.addView(board, LinearLayout.LayoutParams(-1, 0, 1f))
        var boardHeightPx = 0
        if (wide) root.addView(center, LinearLayout.LayoutParams(0, -1, 1f))
        else {
            val ratio = if (kind == "xiangqi") 10f / 9f else 1f
            val reserved = (160 * resources.configuration.fontScale).toInt()
            val boardHeight = minOf((widthDp * ratio + 52).toInt(), ((heightDp - reserved).coerceAtLeast(0) * 0.80f).toInt())
            boardHeightPx = dp(boardHeight)
            root.addView(center, LinearLayout.LayoutParams(-1, boardHeightPx))
        }
        val right = column().apply { setPadding(dp(12), dp(8), dp(12), dp(8)) }
        playbackPanel = column()
        right.addView(playbackPanel)
        analysisPanel = column()
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(analysisPanel) }
        right.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        right.addView(actions, LinearLayout.LayoutParams(-1, -2))
        if (wide) root.addView(right, LinearLayout.LayoutParams(dp(panelWidth), -1))
        else root.addView(right, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val density = resources.displayMetrics.density
            val w = ((view.width - view.paddingLeft - view.paddingRight) / density).toInt()
            val h = ((view.height - view.paddingTop - view.paddingBottom) / density).toInt()
            val keyboard = androidx.core.view.ViewCompat.getRootWindowInsets(view)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            if (!wide) {
                val height = if (keyboard) minOf(boardHeightPx, dp(maxOf(80, h / 3))) else boardHeightPx
                if (center.layoutParams.height != height) center.layoutParams = center.layoutParams.apply { this.height = height }
            }
            if (keyboard) return@addOnLayoutChangeListener
            if (w > 0 && h > 0 && (abs(w - safeWidth) > 2 || abs(h - safeHeight) > 2)) {
                safeWidth = w
                safeHeight = h
                root.post {
                    if (!isFinishing && !isDestroyed && root.isAttachedToWindow) { buildUi(); render() }
                }
            }
        }
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
        information.text = if (showSidebar) {
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
        if (showSidebar) {
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
            preview != null -> getString(if (alternative) R.string.alternative_badge else R.string.plan_badge,
                planName(plans.active), lineStep)
            busy -> getString(busyMessage)
            pending != null -> getString(R.string.plan_waiting_badge, planName(plans.active))
            scoring() -> getString(R.string.scoring_badge)
            mode == CoachSession.Mode.REVIEW -> getString(R.string.review_badge)
            else -> getString(R.string.live_badge)
        }
        badge.setTextColor(color(if (preview != null || pending != null) R.color.coach_purple else R.color.coach_muted))
        badge.setBackgroundColor(color(if (preview != null || pending != null) R.color.coach_purple_surface else R.color.paper))
        analysisPanel.removeAllViews()
        playbackPanel.removeAllViews()
        progressText = null
        lessonBody = null
        actions.removeAllViews()
        replyCards.clear()
        previousLineButton = null
        nextLineButton = null
        lineCounter = null
        if (mode != CoachSession.Mode.REVIEW && !gameDone && !scoring() &&
            !(mode == CoachSession.Mode.VS_AI && session.sideToMove() == 1)) renderPlanTabs()
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
                progressText = text(getString(R.string.analysis_wait), 14f)
                analysisPanel.addView(progressText)
                addAction(getString(R.string.cancel_analysis)) {
                    cancelWork()
                    if (mode == CoachSession.Mode.VS_AI && session.sideToMove() == 1) finish()
                    else selectPlan(plans.active)
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
                pending?.let { move ->
                    analysisPanel.addView(text(getString(R.string.preview_move,
                        engine.applyMove(session.current, move).notation), 17f, true))
                    analysisPanel.addView(text(getString(R.string.plan_waiting_help), 14f))
                    addAction(getString(R.string.change_move)) { editPlan() }
                    addAction(getString(R.string.analyze_plan), true) { propose(move, false) }
                }
                analysisPanel.addView(text(feedback.ifEmpty { getString(R.string.choose_move_help) }, 14f))
                if (!showSidebar) {
                    analysisPanel.addView(text(getString(R.string.move_record), 14f, true))
                    analysisPanel.addView(text(records.text.toString(), 12f))
                }
                analysisPanel.addView(button(getString(R.string.compare_candidates)) { showCandidates() })
                candidates?.let { result ->
                    analysisPanel.addView(text(getString(R.string.my_candidates_only), 13f, true))
                    for (candidate in result.candidates.take(3)) {
                        analysisPanel.addView(button(getString(R.string.candidate_score,
                            engine.applyMove(session.current, candidate.move).notation, Explainer.score(this, kind, candidate))) {
                            propose(candidate.move)
                        })
                    }
                }
                analysisPanel.addView(button(getString(R.string.engine_settings)) { startActivity(Intent(this, SettingsActivity::class.java)) })
                if (lastExplanation.isNotEmpty()) analysisPanel.addView(button(getString(R.string.read_explanation)) { speak(lastExplanation, true) })
                if (!showSidebar) {
                    val row = LinearLayout(this)
                    row.addView(button(getString(R.string.undo), enabled = session.history.isNotEmpty()) { undo() }, LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(button(getString(R.string.resign)) { resign() }, LinearLayout.LayoutParams(0, -2, 1f))
                    analysisPanel.addView(row)
                }
                if (kind == "go" && !showSidebar && pending == null) addAction(getString(R.string.pass)) { propose(Move(-1, -1)) }
            }
        }
        if (mode != CoachSession.Mode.REVIEW && !gameDone) addAction(getString(R.string.tts_stop)) { cancelLesson(); render() }
        refreshBoard()
    }

    private fun addAction(label: String, primary: Boolean = false, enabled: Boolean = true, click: () -> Unit) {
        actions.addView(button(label, primary, enabled, click), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
    }

    private fun planName(index: Int): String = getString(if (index == 0) R.string.plan_a else R.string.plan_b)

    private fun renderPlanTabs() {
        val row = LinearLayout(this)
        for (index in 0..1) {
            val plan = plans.get(index)
            val label = getString(R.string.plan_tab, planName(index), plan?.preview?.notation
                ?: if (plan != null) engine.applyMove(session.current, plan.move).notation else getString(R.string.plan_empty))
            row.addView(button(label, primary = index == plans.active) { selectPlan(index) }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        playbackPanel.addView(row)
    }

    private fun selectPlan(index: Int) {
        cancelLesson()
        if (busy) cancelWork()
        showPlan(plans.select(index, session.revision))
        render()
        preview?.let { p ->
            val other = plans.get(1 - plans.active)?.preview
            narrate("plan:${p.revision}:${p.move}", if (other == null) Explainer.brief(this, p)
                else getString(R.string.teaching_compare, p.notation, Explainer.brief(this, p), other.notation, Explainer.brief(this, other)))
        }
    }

    private fun showPlan(plan: TentativePlans.Plan?) {
        preview = plan?.preview?.takeIf { session.accepts(it) }
        pending = plan?.move
        branch = plan?.branch ?: 0
        lineStep = plan?.step ?: 0
        alternative = plan?.alternative ?: false
        analysisElapsed = plan?.elapsed ?: 0f
        selected = -1
        targets = emptySet()
    }

    private fun editPlan() {
        cancelLesson()
        pending = null
        preview = null
        branch = 0
        lineStep = 0
        alternative = false
        selected = -1
        targets = emptySet()
        feedback = getString(R.string.plan_edit_help, planName(plans.active))
        render()
    }

    private fun renderPreview(p: CoachSession.Preview) {
        if (lessonPreview !== p) {
            cancelLesson()
            teaching.clear()
            lessonPreview = p
            lessonParts = emptyList()
            lessonOnline = false
            lessonThinking = lineStep == 0
            lessonNotice = ""
            discussion.clear()
            lessonCitations = emptyMap()
        }
        analysisPanel.addView(text(getString(R.string.hypothetical_analysis), 20f, true))
        analysisPanel.addView(text(getString(R.string.preview_move, p.notation), 17f, true))
        analysisPanel.addView(text(Explainer.assessment(this, p.assessment), 14f))
        analysisPanel.addView(text(getString(R.string.my_score, Explainer.score(this, kind, p.assessment.played)), 14f))
        val other = plans.get(1 - plans.active)?.preview
        if (other != null) {
            analysisPanel.addView(text(getString(R.string.other_plan_score, planName(1 - plans.active), other.notation,
                Explainer.score(this, kind, other.assessment.played)), 14f))
            analysisPanel.addView(text(getString(if (plans.comparable()) R.string.plans_same_search else R.string.plans_separate_search), 12f))
        }
        p.analysis.engine?.let { analysisPanel.addView(text(it, 13f, true)) }
        analysisPanel.addView(text(getString(R.string.analysis_elapsed, analysisElapsed), 13f))
        if (p.analysis.limited || p.repliesLimited) analysisPanel.addView(text(getString(R.string.analysis_limited), 13f))
        renderTeaching(p)
        analysisPanel.addView(text(getString(R.string.opponent_replies), 17f, true).apply { setPadding(0, dp(12), 0, dp(6)) })
        if (kind != "go") analysisPanel.addView(text(getString(R.string.reply_depth, p.replyDepth), 12f))
        if (p.replies.isEmpty()) analysisPanel.addView(text(getString(R.string.no_reply), 13f))
        p.assessment.reply?.let { analysisPanel.addView(text(Explainer.source(this, it.evidence), 12f)) }
        for ((index, reply) in p.replies.withIndex()) {
            val card = column().apply {
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(color(if (!alternative && index == branch) R.color.coach_selected else R.color.coach_surface))
                    setStroke(dp(if (!alternative && index == branch) 2 else 1), color(if (!alternative && index == branch) R.color.coach_accent else R.color.coach_outline))
                }
                isFocusable = true
                isSelected = !alternative && index == branch
                androidx.core.view.ViewCompat.setStateDescription(this, if (!alternative && index == branch) getString(R.string.branch_selected) else null)
                contentDescription = getString(R.string.branch_number, index + 1)
                setOnClickListener { branch = index; alternative = false; lineStep = minOf(1, reply.moves.size); updateLineSelection() }
            }
            val value = Explainer.score(this, kind, CandidateLine(reply.moves.first(), reply.score, reply.moves,
                reply.mateIn, reply.winrate, reply.evidence))
            card.addView(text(getString(R.string.reply_engine_heading, index + 1, value), 13f, true))
            card.addView(text(reply.notations.mapIndexed { i, notation ->
                getString(R.string.reply_step, i + 1, sideLabel((session.sideToMove() + 1 + i) % 2), notation)
            }.joinToString("\n"), 14f))
            card.visibility = if (lessonThinking) android.view.View.GONE else android.view.View.VISIBLE
            replyCards.add(card)
            analysisPanel.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        if (!lessonThinking && (p.replies.isNotEmpty() || p.alternative.isNotEmpty())) {
            branch = branch.coerceIn(0, maxOf(0, p.replies.lastIndex))
            val total = if (alternative) p.alternative.size else p.replies.getOrNull(branch)?.moves?.size ?: 0
            lineStep = lineStep.coerceIn(0, total)
            val playback = LinearLayout(this)
            previousLineButton = button(getString(R.string.previous_symbol), enabled = lineStep > 0) { lineStep--; updateLineSelection() }
                .apply { contentDescription = getString(R.string.previous_step) }
            nextLineButton = button(getString(R.string.next_symbol), enabled = lineStep < total) { lineStep++; updateLineSelection() }
                .apply { contentDescription = getString(R.string.next_step) }
            lineCounter = text(if (alternative) getString(R.string.alternative_progress, lineStep, total)
                else getString(R.string.line_progress, branch + 1, lineStep, total), 13f).apply { gravity = Gravity.CENTER }
            playback.gravity = Gravity.CENTER_VERTICAL
            playback.addView(previousLineButton, LinearLayout.LayoutParams(dp(48), -2))
            playback.addView(lineCounter, LinearLayout.LayoutParams(0, -2, 1f))
            playback.addView(nextLineButton, LinearLayout.LayoutParams(dp(48), -2))
            playbackPanel.addView(playback)
        }
        if (p.alternative.isNotEmpty()) analysisPanel.addView(text(getString(R.string.recommended_score,
            p.bestNotation.orEmpty(), Explainer.score(this, kind, p.assessment.reference)), 14f))
        analysisPanel.addView(text(getString(R.string.preview_tap_help), 13f))
        val details = column().apply {
            visibility = android.view.View.GONE
            addView(text(if (p.analysis.scoreUnit == "points") getString(R.string.engine_visits, p.analysis.nodes)
                else getString(R.string.analysis_depth, p.analysis.completedDepth, p.analysis.requestedDepth), 13f))
            addView(text(Explainer.source(this@GameActivity, p.assessment.played.evidence), 13f))
            p.assessment.played.evidence?.let { source ->
                addView(text(getString(R.string.source_models, source.models.joinToString("\n").ifEmpty { getString(R.string.engine_no_model) }), 12f))
                addView(text(source.settings, 12f))
            }
            addView(text(getString(when (kind) { "go" -> R.string.go_engine_unit; "xiangqi" -> R.string.xiangqi_score_unit; else -> R.string.chess_score_unit }), 13f))
            addView(text(getString(R.string.local_only_notice), 13f))
        }
        analysisPanel.addView(button(getString(R.string.analysis_details)) {
            details.visibility = if (details.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE
        })
        analysisPanel.addView(details)
        addAction(getString(R.string.change_move)) { editPlan() }
        addAction(getString(R.string.confirm_move), true) { commitPending() }
    }

    private fun renderTeaching(p: CoachSession.Preview) {
        analysisPanel.addView(text(getString(R.string.teaching_title), 18f, true))
        val online = OnlineCoachSettings(this).enabled
        analysisPanel.addView(button(getString(if (online) R.string.teaching_switch_offline else R.string.teaching_offline_mode)) {
            if (online) {
                OnlineCoachSettings(this).setEnabled(false)
                cancelLesson()
                lessonOnline = false
                lessonParts = Explainer.topic(this, p, TeachingInteraction.Topic.WHY)
                lessonNotice = getString(R.string.teaching_now_offline)
                discussion.clear()
                resetTeachingSpeech()
                render()
            } else startActivity(Intent(this, SettingsActivity::class.java))
        })
        if (lessonNotice.isNotEmpty()) analysisPanel.addView(text(lessonNotice, 13f))
        val choices = listOf(R.string.teaching_why to TeachingInteraction.Topic.WHY,
            R.string.teaching_risk to TeachingInteraction.Topic.RISK,
            R.string.teaching_alternative to TeachingInteraction.Topic.ALTERNATIVE,
            R.string.teaching_principle to TeachingInteraction.Topic.PRINCIPLE)
        for (pair in choices.chunked(2)) {
            val row = LinearLayout(this)
            for ((label, topic) in pair) row.addView(button(getString(label)) {
                cancelLesson()
                lessonThinking = false
                lessonOnline = false
                lessonNotice = ""
                lessonParts = Explainer.topic(this, p, topic)
                render()
                narrate("topic:${p.revision}:${p.move}:$topic", lessonParts.first().text)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            analysisPanel.addView(row)
        }
        val reveal = LinearLayout(this)
        reveal.addView(button(getString(R.string.teaching_think_first)) {
            cancelLesson()
            lessonThinking = true
            lessonOnline = false
            lessonParts = emptyList()
            alternative = false
            branch = 0
            lineStep = 0
            updateLineSelection(false)
            render()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        reveal.addView(button(getString(R.string.teaching_reveal)) {
            cancelLesson()
            lessonThinking = false
            lessonOnline = false
            lessonParts = Explainer.topic(this, p, TeachingInteraction.Topic.RISK)
            branch = 0
            alternative = false
            lineStep = minOf(1, p.replies.firstOrNull()?.moves?.size ?: 0)
            render()
            updateLineSelection()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        analysisPanel.addView(reveal)
        lessonBody = column()
        analysisPanel.addView(lessonBody)
        renderLessonBody(p)
        val speech = LinearLayout(this)
        speech.addView(button(getString(R.string.teaching_repeat)) {
            narrate("repeat", teaching.lastText.ifEmpty { Explainer.brief(this, p) }, true)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        speech.addView(button(getString(R.string.teaching_rephrase)) {
            if (online) askOnline(p, getString(R.string.teaching_rephrase_question))
            else {
                cancelLesson()
                lessonThinking = false
                lessonParts = Explainer.topic(this, p, TeachingInteraction.Topic.PRINCIPLE)
                lessonOnline = false
                lessonNotice = getString(R.string.teaching_offline_limits)
                render()
                narrate("rephrase:${p.revision}:${p.move}", lessonParts.first().text, true)
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        analysisPanel.addView(speech)
        if (online) {
            analysisPanel.addView(button(getString(R.string.teaching_online_explain)) { askOnline(p, getString(R.string.teaching_online_question)) })
            val question = android.widget.EditText(this).apply {
                setHint(R.string.teaching_question_hint)
                filters = arrayOf(android.text.InputFilter.LengthFilter(800))
                maxLines = 3
            }
            analysisPanel.addView(question)
            analysisPanel.addView(button(getString(R.string.teaching_send_question)) { askOnline(p, question.text.toString()) })
        } else analysisPanel.addView(text(getString(R.string.teaching_offline_limits), 12f))
        val evidence = column().apply { visibility = android.view.View.GONE }
        for (part in Explainer.parts(this, p)) evidence.addView(button(part.text) { locateAndRead(p, part) })
        analysisPanel.addView(button(getString(R.string.teaching_evidence)) {
            evidence.visibility = if (evidence.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE
        })
        analysisPanel.addView(evidence)
    }

    private fun renderLessonBody(p: CoachSession.Preview) {
        val body = lessonBody ?: return
        body.removeAllViews()
        if (lessonThinking) {
            body.addView(text(getString(R.string.teaching_choose_prompt), 15f, true))
            return
        }
        if (lessonOnline) body.addView(text(getString(R.string.teaching_ai_warning), 12f))
        if (lessonParts.isEmpty()) body.addView(text(Explainer.brief(this, p), 14f))
        for (part in lessonParts) {
            body.addView(text(part.text, 15f))
            val row = LinearLayout(this)
            row.addView(button(getString(R.string.teaching_read_part)) { narrate("manual", part.text, true) }, LinearLayout.LayoutParams(0, -2, 1f))
            if (lessonOnline) row.addView(button(getString(R.string.teaching_evidence)) {
                val cited = lessonCitations[part.id].orEmpty()
                AlertDialog.Builder(this).setTitle(R.string.teaching_evidence).setItems(cited.map { it.text }.toTypedArray()) { _, index ->
                    locateAndRead(p, cited[index])
                }.setNegativeButton(R.string.close, null).show()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            else if (part.branch != null || part.alternative) row.addView(button(getString(R.string.teaching_locate)) { locateAndRead(p, part) }, LinearLayout.LayoutParams(0, -2, 1f))
            body.addView(row)
        }
    }

    private fun locateAndRead(p: CoachSession.Preview, part: Explainer.Part) {
        if (preview !== p || !session.accepts(p)) return
        cancelLesson()
        lessonThinking = false
        alternative = part.alternative
        branch = part.branch ?: 0
        lineStep = part.step
        plans.playback(branch, lineStep, alternative)
        render()
        updateLineSelection(false)
        narrate("evidence:${p.revision}:${p.move}:${part.id}", part.text, true)
    }

    private fun askOnline(p: CoachSession.Preview, question: String) {
        val settings = OnlineCoachSettings(this)
        val configuration = settings.configuration()
        if (!settings.enabled || configuration == null) { toast(R.string.online_coach_config_needed); return }
        if (question.isBlank() || question.length > 800) { toast(R.string.teaching_question_needed); return }
        if (preview !== p || !session.accepts(p)) return
        cancelLesson()
        val token = lessonGeneration
        val evidence = Explainer.parts(this, p)
        val position = when (val pos = session.current) {
            is GoPosition -> "go;size=${pos.size};side=${pos.toMove};ko=${pos.koPoint};board=${pos.board.joinToString(",")}"
            else -> "$kind;${com.sanqi.coach.engine.PositionCodec.fen(pos)}"
        }
        lessonThinking = false
        lessonOnline = false
        lessonParts = Explainer.topic(this, p, TeachingInteraction.Topic.WHY)
        lessonNotice = getString(R.string.teaching_online_wait)
        render()
        lessonJob = scope.launch {
            try {
                val response = onlineCoach.explain(configuration, position, evidence.map { OnlineCoachClient.Evidence(it.id, it.text) },
                    question, resources.configuration.locales[0].language, discussion.toList())
                if (token != lessonGeneration || preview !== p || !session.accepts(p) || !settings.enabled ||
                    configuration.endpoint != settings.endpoint || configuration.model != settings.model) return@launch
                lessonCitations = response.mapIndexed { index, paragraph ->
                    "online_$index" to paragraph.evidenceIds.map { id -> evidence.first { it.id == id } }
                }.toMap()
                lessonParts = response.mapIndexed { index, paragraph ->
                    Explainer.Part(paragraph.text, id = "online_$index")
                }
                discussion.add(question to response.joinToString("\n") { it.text })
                if (discussion.size > 3) discussion.removeAt(0)
                lessonOnline = true
                lessonNotice = ""
                lessonJob = null
                render()
            } catch (_: CancellationException) {
            } catch (_: Exception) {
                if (token == lessonGeneration && preview === p && !isDestroyed) {
                    lessonJob = null
                    lessonOnline = false
                    lessonNotice = getString(R.string.teaching_online_fallback)
                    render()
                }
            }
        }
    }

    private fun updateLineSelection(read: Boolean = true) {
        val p = preview ?: return
        if (read) cancelLesson()
        val total = if (alternative) p.alternative.size else p.replies.getOrNull(branch)?.moves?.size ?: 0
        lineStep = lineStep.coerceIn(0, total)
        for ((index, card) in replyCards.withIndex()) {
            card.isSelected = !alternative && index == branch
            androidx.core.view.ViewCompat.setStateDescription(card, if (!alternative && index == branch) getString(R.string.branch_selected) else null)
            (card.background as GradientDrawable).apply {
                setColor(color(if (!alternative && index == branch) R.color.coach_selected else R.color.coach_surface))
                setStroke(dp(if (!alternative && index == branch) 2 else 1), color(if (!alternative && index == branch) R.color.coach_accent else R.color.coach_outline))
            }
        }
        lineCounter?.text = if (alternative) getString(R.string.alternative_progress, lineStep, total)
            else getString(R.string.line_progress, branch + 1, lineStep, total)
        plans.playback(branch, lineStep, alternative)
        badge.text = getString(if (alternative) R.string.alternative_badge else R.string.plan_badge, planName(plans.active), lineStep)
        previousLineButton?.apply { isEnabled = lineStep > 0; alpha = if (isEnabled) 1f else 0.4f }
        nextLineButton?.apply { isEnabled = lineStep < total; alpha = if (isEnabled) 1f else 0.4f }
        refreshBoard()
        if (read) {
            val point = p.teaching?.points?.firstOrNull { it.alternative == alternative && it.branch == branch && it.step == lineStep }
            val step = if (alternative) p.alternative.getOrNull(lineStep - 1) else p.replies.getOrNull(branch)?.steps?.getOrNull(lineStep - 1)
            val text = point?.let { Explainer.teachingPart(this, it).text }
                ?: step?.let { getString(R.string.teaching_step_brief, it.notation, Explainer.fact(this, it)) }
                ?: Explainer.brief(this, p)
            narrate("line:${p.revision}:${p.move}:$branch:$lineStep:$alternative", text)
        }
    }

    private fun refreshBoard() {
        var pos = session.current
        val p = preview
        val showAlternative = p != null && alternative
        val line = if (showAlternative) p?.alternative.orEmpty().map { it.move }.take(lineStep)
            else p?.replies?.getOrNull(branch)?.moves.orEmpty().take(lineStep)
        if (p != null && session.accepts(p)) pos = if (alternative) session.alternativePosition(p, lineStep)
            else session.previewPosition(p, branch, lineStep)
        else pending?.let { pos = engine.applyMove(engine.copyPosition(pos), it).position }
        kit.setBoardPos(board, pos)
        board.lastMove = if (p == null) session.lastEntry()?.move else null
        board.pendingMove = if (showAlternative) p?.alternative?.firstOrNull()?.move?.takeIf { lineStep > 0 } else p?.move ?: pending
        board.variationMoves = line
        board.variationStartSide = if (showAlternative) session.sideToMove() else (session.sideToMove() + 1) % 2
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
        if (preview != null || pending != null) { editPlan(); return }
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
        cancelLesson()
        teaching.clear()
        lessonPreview = null
        lessonParts = emptyList()
        discussion.clear()
        plans.clear()
        candidates = null
        alternative = false
        pending = null
        preview = null
        branch = 0
        lineStep = 0
        selected = -1
        targets = emptySet()
    }

    private fun cancelWork() {
        cancelLesson()
        request++
        job?.cancel()
        professional?.close()
        job = null
        busy = false
    }

    private fun <T> work(message: Int,
                         calculate: (() -> Unit, (com.sanqi.coach.coach.PositionAnalysis) -> Unit) -> T,
                         complete: (T) -> Unit) {
        if (busy) return
        busy = true
        busyMessage = message
        val token = ++request
        val revision = session.revision
        val started = System.nanoTime()
        render()
        job = scope.launch {
            try {
                val value = withContext(Dispatchers.Default) {
                    val context = currentCoroutineContext()
                    calculate({ context.ensureActive() }) { update ->
                        runOnUiThread {
                            if (token == request && revision == session.revision && busy && !isDestroyed && !isFinishing) {
                                progressText?.text = getString(R.string.analysis_live, update.completedDepth, update.nodes,
                                    (System.nanoTime() - started) / 1_000_000_000.0)
                            }
                        }
                    }
                }
                if (token != request || revision != session.revision || isFinishing || isDestroyed) return@launch
                busy = false
                job = null
                analysisElapsed = (System.nanoTime() - started) / 1_000_000_000f
                complete(value)
            } catch (_: CancellationException) {
                if (token == request) { busy = false; job = null }
            } catch (error: Exception) {
                if (token == request && !isFinishing && !isDestroyed) {
                    busy = false
                    job = null
                    showPlan(plans.get(plans.active))
                    feedback = if (error is com.sanqi.coach.engine.EngineException) getString(when (error.problem) {
                        com.sanqi.coach.engine.EngineProblem.MODEL_MISSING -> R.string.engine_models_needed
                        com.sanqi.coach.engine.EngineProblem.NOT_PACKAGED -> R.string.engine_not_packaged
                        com.sanqi.coach.engine.EngineProblem.INVALID_LINE -> R.string.engine_invalid_line
                        else -> R.string.engine_failed
                    }) else getString(R.string.engine_failed)
                    Toast.makeText(this@GameActivity, feedback, Toast.LENGTH_LONG).show()
                    render()
                }
            }
        }
    }

    private fun propose(move: Move, resetPlayback: Boolean = true) {
        if (busy || !session.isMoveLegal(move)) return
        if (!confirmOn && mode == CoachSession.Mode.TWO_PLAYER) {
            session.commitReview(move, null)
            clearPreview()
            feedback = getString(R.string.direct_without_analysis)
            updateResult()
            saveGame()
            render()
            return
        }
        cancelLesson()
        plans.stage(move, resetPlayback)
        showPlan(plans.get(plans.active))
        narrate("choose:${session.revision}:$move", getString(R.string.teaching_choose_prompt))
        work(R.string.analyzing, { check, progress -> session.analyze(aiDepth, move, check, progress) }) { p ->
            plans.put(p, analysisElapsed)
            showPlan(plans.get(plans.active))
            if (!confirmOn && mode != CoachSession.Mode.REVIEW) commitPending()
            else { render(); narrate("ready:${p.revision}:${p.move}", getString(R.string.teaching_ready_prompt)) }
        }
    }

    private fun commitPending() {
        if (busy) return
        val p = preview ?: return
        if (!session.accepts(p)) { clearPreview(); render(); return }
        val explanation = Explainer.brief(this, p)
        session.commit(p.move, p, explanation)
        feedback = explanation
        lastExplanation = explanation
        clearPreview()
        updateResult()
        saveGame()
        narrate("commit:${session.revision}", explanation)
        render()
        maybeAi()
    }

    private fun maybeAi() {
        if (mode == CoachSession.Mode.VS_AI && session.sideToMove() == 1 && !gameDone && !scoring()) aiTurn()
    }

    private fun aiTurn() {
        if (busy || gameDone || scoring()) return
        work(R.string.opponent_thinking, { check, progress -> session.analyzeBest(aiDepth, check, progress) }) { p ->
            if (p != null && session.accepts(p)) {
                val explanation = Explainer.brief(this, p)
                session.commit(p.move, p, explanation)
                feedback = explanation
                lastExplanation = explanation
                clearPreview()
                updateResult()
                saveGame()
                narrate("opponent:${session.revision}", getString(R.string.teaching_opponent, p.notation))
            }
            render()
        }
    }

    private fun showCandidates() {
        work(R.string.analyzing, { check, progress -> session.candidates(aiDepth, check, progress) }) { analysis ->
            candidates = analysis
            render()
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
                rating?.loss, rating?.grade?.name.orEmpty(), rating?.explanation.orEmpty(), rating?.assessment)
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
            outState.putInt("activePlan", plans.active)
            for (index in 0..1) plans.get(index)?.let { plan ->
                outState.putBundle("plan$index", Bundle().apply {
                    putIntArray("move", intArrayOf(plan.move.from, plan.move.to, plan.move.promo))
                    putInt("branch", plan.branch)
                    putInt("step", plan.step)
                    putBoolean("alternative", plan.alternative)
                })
            }
            outState.putBoolean("editingPlan", pending == null && preview == null)
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

    private fun speak(message: String, manual: Boolean = false) {
        if (manual || prefs.getBoolean("ttsOn", true)) tts?.speak(message, prefs.getFloat("ttsRate", 1f), true)
    }

    private fun narrate(key: String, message: String, manual: Boolean = false) {
        if (!manual && !prefs.getBoolean("ttsOn", true)) return
        teaching.narration(key, message, automatic = !manual)?.let { speak(it, manual) }
    }

    private fun cancelLesson() {
        if (lessonJob?.isActive == true) lessonNotice = getString(R.string.teaching_online_cancelled)
        lessonGeneration++
        lessonJob?.cancel()
        lessonJob = null
        tts?.stop()
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
            analysisPanel.addView(text(Explainer.source(this, it.analysis?.played?.evidence), 13f))
        }
        addAction(getString(R.string.previous_step), enabled = reviewIndex >= 0) { reviewIndex--; replayTo(reviewIndex); render() }
        addAction(getString(R.string.next_step), enabled = reviewIndex + 1 < reviewMoves.size) { reviewIndex++; replayTo(reviewIndex); render() }
    }
}
