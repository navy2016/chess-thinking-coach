package com.sanqi.coach.coach

import com.sanqi.coach.core.AppliedMove
import com.sanqi.coach.core.GameEngine
import com.sanqi.coach.core.GameStatus
import com.sanqi.coach.core.Move
import com.sanqi.coach.core.go.GoPosition
import com.sanqi.coach.engine.AnalysisBackend

enum class Grade(val label: String) {
    BEST("最佳"), GOOD("良好"), INACCURATE("可疑"), MISTAKE("失误"), BLUNDER("严重失误");

    companion object {
        fun fromLoss(loss: Int): Grade = when {
            loss <= 30 -> BEST
            loss <= 80 -> GOOD
            loss <= 180 -> INACCURATE
            loss <= 350 -> MISTAKE
            else -> BLUNDER
        }
    }
}

data class MoveRating(
    val notation: String,
    val loss: Int?,
    val grade: Grade?,
    val bestNotation: String?,
    val explanation: String,
    val assessment: MoveAssessment? = null
)

class CoachSession<P : Any>(
    private val engine: GameEngine<P>,
    val mode: Mode,
    private val aiDepth: Int,
    private val stalemateIsDraw: Boolean,
    start: P? = null,
    private val backend: AnalysisBackend<P>? = null
) {
    enum class Mode { TWO_PLAYER, VS_AI, REVIEW }

    data class Entry<P : Any>(val posBefore: P, val move: Move, val applied: AppliedMove<P>, val rating: MoveRating?)
    data class EvidenceStep(val move: Move, val notation: String, val side: Int, val captured: Int,
                            val check: Boolean, val status: GameStatus)
    data class Reply(val score: Int, val moves: List<Move>, val notations: List<String>, val mateIn: Int? = null,
                     val winrate: Double? = null, val steps: List<EvidenceStep> = emptyList(),
                     val evidence: SearchEvidence? = null)
    data class Preview(
        val revision: Long,
        val move: Move,
        val notation: String,
        val captured: Int,
        val check: Boolean,
        val status: GameStatus,
        val loss: Int?,
        val grade: Grade?,
        val bestNotation: String?,
        val replies: List<Reply>,
        val analysis: PositionAnalysis,
        val replyDepth: Int,
        val repliesLimited: Boolean,
        val assessment: MoveAssessment,
        val playedStep: EvidenceStep,
        val alternative: List<EvidenceStep>,
        val teaching: TeachingAnalysis? = null
    )

    private val initial: P = engine.copyPosition(start ?: engine.initialPosition())
    private val entries = mutableListOf<Entry<P>>()
    val history: List<Entry<P>> get() = entries.toList()
    var current: P = engine.copyPosition(initial)
        private set
    var revision: Long = 0
        private set
    var gameOverText: String? = null
        private set
    var endStatus: GameStatus? = null
        private set
    var winner: Int? = null
        private set

    fun sideToMove(): Int = engine.sideToMove(current)
    fun sideName(side: Int): String = engine.sideName(side)
    fun lastEntry(): Entry<P>? = entries.lastOrNull()
    fun legalMovesFrom(idx: Int): List<Move> = engine.legalMoves(current).filter { it.from == idx }
    fun isMoveLegal(move: Move): Boolean = engine.legalMoves(current).contains(move)
    fun accepts(preview: Preview): Boolean = preview.revision == revision && gameOverText == null && isMoveLegal(preview.move)

    fun analyze(depth: Int, move: Move, cancelled: () -> Unit = {}, onProgress: (PositionAnalysis) -> Unit = {}): Preview =
        previewAnalysis(depth, move, cancelled, onProgress, null)

    fun analyzeBest(depth: Int, cancelled: () -> Unit = {}, onProgress: (PositionAnalysis) -> Unit = {}): Preview? {
        val baseline = analyzePosition(engine.copyPosition(current), entries.map { it.move }, depth, null, cancelled,
            com.sanqi.coach.engine.analysisMillis(depth) / 2, onProgress)
        val move = baseline.candidates.firstOrNull()?.move ?: return null
        return previewAnalysis(depth, move, cancelled, onProgress, baseline)
    }

    private fun previewAnalysis(depth: Int, move: Move, cancelled: () -> Unit,
                                onProgress: (PositionAnalysis) -> Unit, baseline: PositionAnalysis?): Preview {
        val sourceRevision = revision
        val source = engine.copyPosition(current)
        require(move in engine.legalMoves(source))
        val history = entries.map { it.move }
        val budget = com.sanqi.coach.engine.analysisMillis(depth)
        val analysis = baseline ?: analyzePosition(source, history, depth, move, cancelled, budget / 2, onProgress)
        val applied = engine.applyMove(source, move)
        val best = analysis.candidates.firstOrNull()?.move
        val bestNotation = if (best != null && best != move) engine.applyMove(source, best).notation else null
        val ended = applied.status in listOf(GameStatus.CHECKMATE, GameStatus.STALEMATE, GameStatus.DRAW)
        val remaining = (budget - analysis.searchMillis).coerceAtLeast(0).toInt()
        val response = if (ended || remaining == 0) null else analyzePosition(applied.position, history + move, depth, null, cancelled,
            remaining, onProgress)
        val replies = response?.candidates?.take(3)?.map { candidate ->
            val steps = evidenceSteps(applied.position, candidate.moves.take(8), cancelled)
            Reply(candidate.score, steps.map { it.move }, steps.map { it.notation }, candidate.mateIn,
                candidate.winrate, steps, candidate.evidence)
        } ?: emptyList()
        val assessment = MoveAssessment.from(analysis, move, response?.candidates?.firstOrNull())
        val alternative = if (best != null && best != move)
            evidenceSteps(source, analysis.candidates.first().moves.take(8), cancelled) else emptyList()
        cancelled()
        return Preview(sourceRevision, move, applied.notation, applied.captured, applied.check,
            applied.status, assessment.loss, assessment.grade, bestNotation, replies, analysis,
            response?.completedDepth ?: 0, response?.limited ?: (!ended && remaining == 0), assessment,
            EvidenceStep(move, applied.notation, engine.sideToMove(source), applied.captured, applied.check, applied.status),
            alternative, TeachingAnalysis.create(engine, source, move, replies, alternative, cancelled))
    }

    private fun evidenceSteps(source: P, moves: List<Move>, cancelled: () -> Unit): List<EvidenceStep> {
        var pos = engine.copyPosition(source)
        return moves.map { move ->
            cancelled()
            require(move in engine.legalMoves(pos))
            val side = engine.sideToMove(pos)
            val result = engine.applyMove(pos, move)
            pos = result.position
            EvidenceStep(move, result.notation, side, result.captured, result.check, result.status)
        }
    }

    fun alternativePosition(preview: Preview, steps: Int): P {
        require(accepts(preview))
        var pos = engine.copyPosition(current)
        for (step in preview.alternative.take(steps.coerceAtLeast(0))) pos = engine.applyMove(pos, step.move).position
        return pos
    }

    private fun analyzePosition(pos: P, moves: List<Move>, level: Int, forced: Move?, cancelled: () -> Unit,
                                millis: Int, onProgress: (PositionAnalysis) -> Unit): PositionAnalysis =
        backend?.analyze(pos, moves, level, forced, cancelled, millis, onProgress)
            ?: PositionAnalyzer(engine).analyze(pos, level, forced, cancelled = cancelled)

    fun candidates(depth: Int, cancelled: () -> Unit = {}, onProgress: (PositionAnalysis) -> Unit = {}): PositionAnalysis =
        analyzePosition(engine.copyPosition(current), entries.map { it.move }, depth, null, cancelled,
            com.sanqi.coach.engine.analysisMillis(depth) / 2, onProgress)

    fun previewPosition(preview: Preview, branch: Int, steps: Int): P {
        require(accepts(preview))
        var pos = engine.applyMove(engine.copyPosition(current), preview.move).position
        val line = preview.replies.getOrNull(branch)?.moves.orEmpty()
        for (move in line.take(steps.coerceAtLeast(0))) pos = engine.applyMove(pos, move).position
        return pos
    }

    private fun push(move: Move, rating: MoveRating?): Entry<P> {
        require(gameOverText == null && isMoveLegal(move))
        val applied = engine.applyMove(current, move)
        val entry = Entry(current, move, applied, rating)
        current = applied.position
        entries.add(entry)
        revision++
        val mover = engine.sideToMove(entry.posBefore)
        endStatus = applied.status.takeIf { it in listOf(GameStatus.CHECKMATE, GameStatus.STALEMATE, GameStatus.DRAW) }
        winner = when (endStatus) {
            GameStatus.CHECKMATE -> mover
            GameStatus.STALEMATE -> if (stalemateIsDraw) null else mover
            else -> null
        }
        gameOverText = when (endStatus) {
            GameStatus.CHECKMATE -> engine.sideName(mover) + "胜（将死）"
            GameStatus.STALEMATE -> if (stalemateIsDraw) "和棋（逼和）" else engine.sideName(mover) + "胜（困毙）"
            GameStatus.DRAW -> "和棋"
            else -> null
        }
        return entry
    }

    fun commit(move: Move, preview: Preview?, explanation: String = ""): Entry<P> {
        val ready = preview ?: analyze(aiDepth, move)
        require(ready.move == move && accepts(ready))
        return push(move, MoveRating(ready.notation, ready.loss, ready.grade, ready.bestNotation, explanation, ready.assessment))
    }

    fun commitPass(): Entry<P> = push(Move(-1, -1), null)
    fun commitReview(move: Move, rating: MoveRating?): Entry<P> = push(move, rating)

    fun undo() {
        if (entries.isEmpty()) return
        val n = if (mode == Mode.VS_AI && sideToMove() == 0 && entries.size >= 2) 2 else 1
        repeat(n) { entries.removeAt(entries.lastIndex) }
        current = if (entries.isEmpty()) engine.copyPosition(initial) else entries.last().applied.position
        revision++
        gameOverText = null
        endStatus = null
        winner = null
    }

    fun resumeGo() {
        require(current is GoPosition && (current as GoPosition).consecutivePasses >= 2)
        while (entries.lastOrNull()?.move == Move(-1, -1)) entries.removeAt(entries.lastIndex)
        current = if (entries.isEmpty()) engine.copyPosition(initial) else entries.last().applied.position
        revision++
        gameOverText = null
        endStatus = null
        winner = null
    }
}
