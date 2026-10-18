package com.sanqi.coach.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.sanqi.coach.content.Content
import com.sanqi.coach.content.PuzzleItem
import com.sanqi.coach.core.GameStatus
import com.sanqi.coach.core.Move

class PuzzleActivity : ImmersiveActivity() {

    companion object {
        const val K_ID = "puzzleId"
    }

    private var item: PuzzleItem? = null
    private var kit: GameKit? = null

    private lateinit var boardView: BoardView
    private lateinit var titleView: TextView
    private lateinit var infoText: TextView

    private var selectFrom = -1
    private val targets = mutableSetOf<Int>()

    private var listRoot: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(K_ID)
        val all = Content.puzzles(this)
        val chosen = if (id != null) all.firstOrNull { it.id == id } else null
        if (chosen == null) {
            renderList(all)
        } else {
            item = chosen
            kit = GameKit(chosen.kind, chosen.size)
            renderPlayer()
        }
    }

    private fun renderList(all: List<PuzzleItem>) {
        val scroll = ScrollView(this)
        listRoot = LinearLayout(this)
        listRoot!!.orientation = LinearLayout.VERTICAL
        listRoot!!.setPadding(px(20), px(20), px(20), px(16))
        scroll.addView(listRoot!!)

        val title = TextView(this)
        title.text = "残局挑战"
        title.textSize = 24f
        title.setTypeface(Typeface.DEFAULT_BOLD)
        title.setTextColor(getColor(com.sanqi.coach.R.color.coach_accent))
        listRoot!!.addView(title)

        val hint = TextView(this)
        hint.text = "一步练：找到制胜的那一手。答错不影响局面，可以反复尝试。"
        hint.textSize = 13f
        hint.setTextColor(Color.parseColor("#666666"))
        val hlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        hlp.topMargin = px(8)
        listRoot!!.addView(hint, hlp)

        for (p in all) {
            val card = MaterialCardView(this)
            card.radius = px(16).toFloat()
            card.cardElevation = 0f
            card.strokeWidth = px(1)
            card.strokeColor = getColor(com.sanqi.coach.R.color.coach_outline)
            card.setCardBackgroundColor(getColor(com.sanqi.coach.R.color.coach_surface))
            card.useCompatPadding = true
            card.setOnClickListener {
                startActivity(Intent(this, PuzzleActivity::class.java).putExtra(K_ID, p.id))
            }
            val inner = LinearLayout(this)
            inner.orientation = LinearLayout.VERTICAL
            inner.setPadding(px(16), px(14), px(16), px(14))
            val t = TextView(this)
            t.text = "[" + GameActivity.kindLabel(p.kind) + "] " + p.title
            t.textSize = 16f
            t.setTypeface(Typeface.DEFAULT_BOLD)
            inner.addView(t)
            val d = TextView(this)
            d.text = p.desc
            d.textSize = 13f
            d.setTextColor(Color.parseColor("#666666"))
            inner.addView(d)
            card.addView(inner, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = px(12)
            listRoot!!.addView(card, lp)
        }

        val back = MaterialButton(this)
        back.text = "返回首页"
        back.isAllCaps = false
        back.setOnClickListener { finish() }
        val blp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        blp.topMargin = px(16)
        listRoot!!.addView(back, blp)

        scroll.fitsSystemWindows = true
        setContentView(scroll)
    }

    private fun position(): Any {
        val p = item!!
        return kit!!.positionFromBoard(p.board, p.toMove)
    }

    private fun renderPlayer() {
        val p = item!!
        val k = kit!!
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#F6F1E4"))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(px(12), px(8), px(12), px(4))
        val back = MaterialButton(this)
        back.text = "返回"
        back.isAllCaps = false
        back.setOnClickListener { finish() }
        top.addView(back)
        titleView = TextView(this)
        titleView.textSize = 18f
        titleView.setTypeface(Typeface.DEFAULT_BOLD)
        top.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = px(12)
        })
        titleView.text = p.title
        root.addView(top)

        val holder = FrameLayout(this)
        boardView = BoardView(this)
        boardView.adapter = k.adapter()
        boardView.onTap = { onTap(it) }
        holder.addView(boardView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        root.addView(holder, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.4f))

        infoText = TextView(this)
        infoText.textSize = 14f
        infoText.setPadding(px(16), px(8), px(16), px(8))
        val infoScroll = ScrollView(this)
        infoScroll.addView(infoText)
        root.addView(infoScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.6f))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(px(12), px(4), px(12), px(8))
        val hintBtn = MaterialButton(this)
        hintBtn.text = "提示"
        hintBtn.isAllCaps = false
        hintBtn.setOnClickListener { showHint() }
        val retry = MaterialButton(this)
        retry.text = "重看题目"
        retry.isAllCaps = false
        retry.setOnClickListener { clearAttempt(); refreshBoard() }
        row.addView(hintBtn)
        row.addView(retry, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = px(8)
            })
        root.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.fitsSystemWindows = true
        setContentView(root)
        infoText.text = p.desc + "\n\n轮到你走：找到那关键的一手。"
        refreshBoard()
    }

    private fun clearAttempt() {
        selectFrom = -1
        targets.clear()
        boardView.hintTo = -1
        Toast.makeText(this, "局面已还原", Toast.LENGTH_SHORT).show()
    }

    private fun refreshBoard() {
        val p = item!!
        val k = kit!!
        k.setBoardPos(boardView, position())
        boardView.selectFrom = selectFrom
        boardView.targetHighlights = targets
        boardView.refresh()
    }

    private fun onTap(idx: Int) {
        val p = item!!
        val k = kit!!
        if (p.kind == "go") {
            val move = Move(idx, idx, 0)
            attempt(move)
            return
        }
        if (selectFrom == -1) {
            val moves = k.engine.legalMoves(position()).filter { it.from == idx }
            if (moves.isNotEmpty()) {
                selectFrom = idx
                targets.clear()
                targets.addAll(moves.map { it.to })
                refreshBoard()
            }
        } else {
            val cand = k.engine.legalMoves(position())
                .filter { it.from == selectFrom && it.to == idx }
            if (cand.isNotEmpty()) {
                attempt(cand.first())
            } else {
                selectFrom = -1
                targets.clear()
                refreshBoard()
            }
        }
    }

    private fun attempt(move: Move) {
        val p = item!!
        val k = kit!!
        val pos = position()
        if (!k.engine.legalMoves(pos).any { it == move }) {
            Toast.makeText(this, "这一步不合法，再想想", Toast.LENGTH_SHORT).show()
            return
        }
        val applied = k.engine.applyMove(k.engine.copyPosition(pos), move)
        val success = when (p.goal) {
            "mate" -> applied.status == GameStatus.CHECKMATE
            else -> move == p.solution || applied.captured > 0
        }
        if (success) {
            selectFrom = -1
            targets.clear()
            AlertDialog.Builder(this)
                .setTitle("挑战成功！")
                .setMessage(if (p.teach.isNotEmpty()) p.teach else "漂亮，正是这一手。")
                .setPositiveButton("再来一道") { _, _ ->
                    val all = Content.puzzles(this)
                    val others = all.filter { it.id != p.id }
                    if (others.isEmpty()) {
                        finish()
                    } else {
                        val next = others[(0 until others.size).random()]
                        startActivity(
                            Intent(this, PuzzleActivity::class.java).putExtra(K_ID, next.id))
                        finish()
                    }
                }
                .setNegativeButton("留在这里", null)
                .show()
        } else {
            Toast.makeText(this, "还没有达成目标，再试一次（局面不变）", Toast.LENGTH_SHORT).show()
            selectFrom = -1
            targets.clear()
        }
        refreshBoard()
    }

    private fun showHint() {
        val p = item!!
        boardView.hintTo = p.solution.to
        boardView.refresh()
        Toast.makeText(this, "提示：目标点已用金色圈标出", Toast.LENGTH_SHORT).show()
    }
}
