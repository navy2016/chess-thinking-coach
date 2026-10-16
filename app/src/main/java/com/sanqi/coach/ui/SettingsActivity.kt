package com.sanqi.coach.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("coach", MODE_PRIVATE)

        val scroll = ScrollView(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(48, 60, 48, 56)
        scroll.addView(root)

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        val back = MaterialButton(this)
        back.text = "返回"
        back.isAllCaps = false
        back.setOnClickListener { finish() }
        top.addView(back)
        val title = TextView(this)
        title.text = "设置"
        title.textSize = 22f
        val tlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        tlp.leftMargin = 24
        title.gravity = android.view.Gravity.CENTER_VERTICAL
        top.addView(title, tlp)
        root.addView(top)

        fun addCheck(label: String, key: String, def: Boolean): CheckBox {
            val cb = CheckBox(this)
            cb.text = label
            cb.isChecked = prefs.getBoolean(key, def)
            cb.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = 36
            root.addView(cb, lp)
            return cb
        }

        addCheck("教练流程：落子前先替对方推演，确认后才落子", "confirmOn", true)
        addCheck("讲解朗读（TTS 语音播报）", "ttsOn", true)
        addCheck("显示棋盘坐标", "showCoords", true)

        val depthTitle = TextView(this)
        depthTitle.text = "AI 推演深度（越大棋力越强、等待越久）"
        depthTitle.textSize = 15f
        val dlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        dlp.topMargin = 48
        root.addView(depthTitle, dlp)

        val rg = RadioGroup(this)
        rg.orientation = RadioGroup.HORIZONTAL
        val depths = listOf("快速" to 2, "标准" to 3, "较强" to 4)
        val current = prefs.getInt("aiDepth", 3)
        for ((label, d) in depths) {
            val rb = RadioButton(this)
            rb.text = label
            rb.isChecked = d == current
            rb.setOnCheckedChangeListener { _, checked ->
                if (checked) prefs.edit().putInt("aiDepth", d).apply()
            }
            rg.addView(rb, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val rglp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        rglp.topMargin = 16
        root.addView(rg, rglp)

        val note = TextView(this)
        note.text = "说明：讲解由本地引擎事实 + 模板生成，完全离线可用。" +
            "后续版本将提供接入大模型 API 的开关，讲解会更口语化。"
        note.textSize = 13f
        val nlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        nlp.topMargin = 64
        root.addView(note, nlp)

        setContentView(scroll)
    }
}
