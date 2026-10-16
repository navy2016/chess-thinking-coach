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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.sanqi.coach.data.GameStore
import com.sanqi.coach.data.StoredGame

class HomeActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        buildUi()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(48, 72, 48, 56)
        scroll.addView(root)

        val title = TextView(this)
        title.text = "三棋思考教练"
        title.textSize = 30f
        title.setTypeface(Typeface.DEFAULT_BOLD)
        title.setTextColor(Color.parseColor("#1B5E20"))
        root.addView(title)

        val sub = TextView(this)
        sub.text = "落子前替你想一步，落子后讲给你听"
        sub.textSize = 14f
        sub.setTextColor(Color.parseColor("#555555"))
        val subLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        subLp.topMargin = 8
        root.addView(sub, subLp)

        val unfinished = GameStore.list(this).firstOrNull { !it.finished }
        if (unfinished != null) {
            val b = MaterialButton(this)
            b.text = "继续上局：" + GameActivity.kindLabel(unfinished.kind) + " · 已下 " + unfinished.moves.size + " 手"
            b.isAllCaps = false
            b.setOnClickListener { resume(unfinished) }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = 40
            root.addView(b, lp)
        }

        addCard("围棋", "9 / 13 / 19 路 · 提子、打劫、中国规则数子") { pick("go") }
        addCard("中国象棋", "完整规则 · 每步推演确认、评分讲解") { pick("xiangqi") }
        addCard("国际象棋", "易位、升变、将杀 · 变化树陪想") { pick("chess") }

        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        addEntry(row1, "棋谱教学") { startActivity(Intent(this, StudyActivity::class.java)) }
        addEntry(row1, "残局挑战") { startActivity(Intent(this, PuzzleActivity::class.java)) }
        addEntry(row2, "我的棋局") { startActivity(Intent(this, ReplayListActivity::class.java)) }
        addEntry(row2, "设置") { startActivity(Intent(this, SettingsActivity::class.java)) }
        val r1 = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        r1.topMargin = 40
        root.addView(row1, r1)
        val r2 = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        r2.topMargin = 20
        root.addView(row2, r2)

        scroll.fitsSystemWindows = true
        setContentView(scroll)
    }

    private fun addCard(label: String, desc: String, onClick: () -> Unit) {
        val card = MaterialCardView(this)
        card.radius = 24f
        card.cardElevation = 6f
        card.useCompatPadding = true
        card.setOnClickListener { onClick() }
        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL
        inner.setPadding(40, 32, 40, 32)
        val t = TextView(this)
        t.text = label
        t.textSize = 22f
        t.setTypeface(Typeface.DEFAULT_BOLD)
        t.setTextColor(Color.parseColor("#1C1B17"))
        inner.addView(t)
        val d = TextView(this)
        d.text = desc
        d.textSize = 13f
        d.setTextColor(Color.parseColor("#666666"))
        val dlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        dlp.topMargin = 6
        inner.addView(d, dlp)
        card.addView(inner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = 36
        root.addView(card, lp)
    }

    private fun addEntry(row: LinearLayout, label: String, onClick: () -> Unit) {
        val b = MaterialButton(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.marginEnd = 20
        row.addView(b, lp)
    }

    private fun resume(g: StoredGame) {
        val i = Intent(this, GameActivity::class.java)
        i.putExtra(GameActivity.K_KIND, g.kind)
        i.putExtra(GameActivity.K_RESUME, g.id)
        i.putExtra(GameActivity.K_SIZE, g.boardSize)
        startActivity(i)
    }

    private fun pick(kind: String) {
        if (kind == "go") {
            val sizes = arrayOf("9 路棋盘", "13 路棋盘", "19 路棋盘")
            AlertDialog.Builder(this)
                .setTitle("选择围棋棋盘")
                .setItems(sizes) { _, which ->
                    pickMode(kind, intArrayOf(9, 13, 19)[which])
                }
                .show()
        } else {
            pickMode(kind, 0)
        }
    }

    private fun pickMode(kind: String, size: Int) {
        val modes = arrayOf("双人对弈（同屏轮流）", "和电脑对弈（我执先手）")
        AlertDialog.Builder(this)
            .setTitle("选择对弈方式")
            .setItems(modes) { _, which ->
                val i = Intent(this, GameActivity::class.java)
                i.putExtra(GameActivity.K_KIND, kind)
                i.putExtra(GameActivity.K_MODE, if (which == 0) "TWO_PLAYER" else "VS_AI")
                if (size > 0) i.putExtra(GameActivity.K_SIZE, size)
                startActivity(i)
            }
            .show()
    }
}
