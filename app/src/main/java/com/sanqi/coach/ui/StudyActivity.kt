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
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.sanqi.coach.content.Content
import com.sanqi.coach.content.StudyItem

class StudyActivity : ImmersiveActivity() {

    companion object {
        const val K_ID = "studyId"
    }

    private var item: StudyItem? = null
    private var kit: GameKit? = null
    private var step = -1

    private lateinit var boardView: BoardView
    private lateinit var titleView: TextView
    private lateinit var infoText: TextView

    private var listRoot: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(K_ID)
        val all = Content.studies(this)
        val chosen = if (id != null) all.firstOrNull { it.id == id } else null
        if (chosen == null) {
            renderList(all)
        } else {
            item = chosen
            kit = GameKit(chosen.kind, chosen.size)
            renderPlayer()
        }
    }

    private fun renderList(all: List<StudyItem>) {
        val scroll = ScrollView(this)
        listRoot = LinearLayout(this)
        listRoot!!.orientation = LinearLayout.VERTICAL
        listRoot!!.setPadding(px(20), px(20), px(20), px(16))
        scroll.addView(listRoot!!)

        val title = TextView(this)
        title.text = "棋谱教学"
        title.textSize = 24f
        title.setTypeface(Typeface.DEFAULT_BOLD)
        title.setTextColor(getColor(com.sanqi.coach.R.color.coach_accent))
        listRoot!!.addView(title)

        val hint = TextView(this)
        hint.text = "跟着经典棋谱一手一手摆，每步都有讲解。"
        hint.textSize = 13f
        hint.setTextColor(Color.parseColor("#666666"))
        val hlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        hlp.topMargin = px(8)
        listRoot!!.addView(hint, hlp)

        for (s in all) {
            val card = MaterialCardView(this)
            card.radius = px(16).toFloat()
            card.cardElevation = 0f
            card.strokeWidth = px(1)
            card.strokeColor = getColor(com.sanqi.coach.R.color.coach_outline)
            card.setCardBackgroundColor(getColor(com.sanqi.coach.R.color.coach_surface))
            card.useCompatPadding = true
            card.setOnClickListener {
                startActivity(Intent(this, StudyActivity::class.java).putExtra(K_ID, s.id))
            }
            val inner = LinearLayout(this)
            inner.orientation = LinearLayout.VERTICAL
            inner.setPadding(px(16), px(14), px(16), px(14))
            val t = TextView(this)
            t.text = "[" + GameActivity.kindLabel(s.kind) + "] " + s.title
            t.textSize = 16f
            t.setTypeface(Typeface.DEFAULT_BOLD)
            inner.addView(t)
            val d = TextView(this)
            d.text = s.desc
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

    private fun renderPlayer() {
        val s = item!!
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
        titleView.text = s.title
        root.addView(top)

        val holder = FrameLayout(this)
        boardView = BoardView(this)
        boardView.adapter = k.adapter()
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
        val prev = MaterialButton(this)
        prev.text = "上一步"
        prev.isAllCaps = false
        prev.setOnClickListener {
            if (step >= 0) {
                step--
                refresh()
            }
        }
        val next = MaterialButton(this)
        next.text = "下一步"
        next.isAllCaps = false
        next.setOnClickListener {
            val s2 = item!!
            if (step < s2.moves.size - 1) {
                step++
                refresh()
            } else {
                Toast.makeText(this, "棋谱已全部讲完", Toast.LENGTH_SHORT).show()
            }
        }
        row.addView(prev)
        row.addView(next, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = px(8)
            })
        root.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.fitsSystemWindows = true
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        val s = item!!
        val k = kit!!
        var pos: Any = s.setupBoard?.let { k.positionFromBoard(it, 0) } ?: k.engine.initialPosition()
        var lastNotation: String? = null
        for (i in 0..step) {
            val applied = k.engine.applyMove(pos, s.moves[i])
            pos = applied.position
            lastNotation = applied.notation
        }
        k.setBoardPos(boardView, pos)
        boardView.lastMove = if (step >= 0) s.moves[step] else null
        boardView.refresh()

        val note = s.notes.getOrNull(step) ?: ""
        infoText.text = if (step < 0) {
            s.desc + "\n\n点「下一步」开始打谱。"
        } else if (note.isNotEmpty()) {
            "第 " + (step + 1) + " 手：" + lastNotation + "\n\n" + note
        } else {
            "第 " + (step + 1) + " 手：" + lastNotation
        }
    }
}
