package com.sanqi.coach.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.sanqi.coach.data.GameStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReplayListActivity : AppCompatActivity() {

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
        root.setPadding(48, 60, 48, 48)
        scroll.addView(root)

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        val back = com.google.android.material.button.MaterialButton(this)
        back.text = "返回"
        back.isAllCaps = false
        back.setOnClickListener { finish() }
        top.addView(back)
        val title = TextView(this)
        title.text = "我的棋局"
        title.textSize = 22f
        title.setTypeface(Typeface.DEFAULT_BOLD)
        title.gravity = Gravity.CENTER_VERTICAL
        val tlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        tlp.leftMargin = 24
        top.addView(title, tlp)
        root.addView(top)

        val games = GameStore.list(this)
        if (games.isEmpty()) {
            val empty = TextView(this)
            empty.text = "还没有对局记录。回到首页开一局，每一步都会被记录、评分，方便复盘。"
            empty.textSize = 15f
            empty.setTextColor(Color.parseColor("#777777"))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = 48
            root.addView(empty, lp)
        } else {
            for (g in games) {
                addItem(g)
            }
        }
        scroll.fitsSystemWindows = true
        setContentView(scroll)
    }

    private fun addItem(g: com.sanqi.coach.data.StoredGame) {
        val card = MaterialCardView(this)
        card.radius = 18f
        card.cardElevation = 4f
        card.useCompatPadding = true
        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL
        inner.setPadding(36, 28, 36, 28)

        val main = TextView(this)
        main.text = GameActivity.kindLabel(g.kind) + " · " + g.moves.size + " 手 · " +
            (if (g.resultText.isNotEmpty()) g.resultText else "进行中")
        main.textSize = 16f
        main.setTypeface(Typeface.DEFAULT_BOLD)
        inner.addView(main)

        val sub = TextView(this)
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        sub.text = fmt.format(Date(g.updatedAt)) + " · 点击复盘" + " · 长按删除"
        sub.textSize = 12f
        sub.setTextColor(Color.parseColor("#888888"))
        inner.addView(sub)

        card.addView(inner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        card.setOnClickListener {
            val i = Intent(this, GameActivity::class.java)
            i.putExtra(GameActivity.K_KIND, g.kind)
            i.putExtra(GameActivity.K_SIZE, g.boardSize)
            i.putExtra(GameActivity.K_REVIEW, g.id)
            startActivity(i)
        }
        card.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setMessage("删除这局记录？")
                .setPositiveButton("删除") { _, _ ->
                    GameStore.delete(this, g.id)
                    buildUi()
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = 20
        root.addView(card, lp)
    }
}
