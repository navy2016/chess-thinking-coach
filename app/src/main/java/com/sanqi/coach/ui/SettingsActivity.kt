package com.sanqi.coach.ui

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.sanqi.coach.R
import com.sanqi.coach.engine.EngineModelStore
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

class SettingsActivity : ImmersiveActivity() {
    private lateinit var root: LinearLayout
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var speechStatus: TextView
    private var tts: TtsManager? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var download: Job? = null
    private var modelStore: EngineModelStore? = null
    private val downloadButtons = mutableListOf<MaterialButton>()
    private var onlineToggle: CheckBox? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("coach", MODE_PRIVATE)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(16), px(20), px(24)) }
        val scroll = ScrollView(this).apply { addView(root) }
        root.addView(action(getString(R.string.back)) { finish() })
        heading(R.string.settings_title)
        check(R.string.setting_confirm, "confirmOn", true)
        check(R.string.setting_coordinates, "showCoords", true)
        check(R.string.setting_speech, "ttsOn", true)
        heading(R.string.analysis_budget)
        val levels = LinearLayout(this)
        val levelButtons = mutableListOf<MaterialButton>()
        for ((index, resource) in listOf(R.string.level_fast, R.string.level_standard, R.string.level_strong).withIndex()) {
            val choice = action(getString(resource)) {
                prefs.edit().putInt("aiDepth", index + 2).apply()
                levelButtons.forEachIndexed { i, button -> button.isChecked = i == index }
            }
            choice.isCheckable = true
            choice.backgroundTintList = getColorStateList(R.color.coach_choice)
            choice.isChecked = prefs.getInt("aiDepth", 3) == index + 2
            levelButtons.add(choice)
            levels.addView(choice, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(levels)
        label(getString(R.string.budget_notice))
        heading(R.string.online_coach_title)
        val coachSettings = com.sanqi.coach.coach.OnlineCoachSettings(this)
        label(getString(R.string.online_coach_notice))
        onlineToggle = CheckBox(this).apply {
            setText(R.string.online_coach_enable)
            isChecked = coachSettings.enabled
            setOnCheckedChangeListener { _, checked ->
                try { coachSettings.setEnabled(checked) }
                catch (_: Exception) {
                    isChecked = false
                    android.widget.Toast.makeText(this@SettingsActivity, R.string.online_coach_config_needed, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        root.addView(onlineToggle)
        root.addView(action(getString(R.string.online_coach_configure)) { configureOnlineCoach() })
        heading(R.string.speech_settings)
        speechStatus = label(getString(R.string.tts_initializing))
        root.addView(CheckBox(this).apply {
            setText(R.string.edge_option)
            isChecked = prefs.getBoolean("edgeTts", false) && prefs.getBoolean("edgeConsent", false)
            setOnClickListener {
                if (isChecked && !prefs.getBoolean("edgeConsent", false)) {
                    isChecked = false
                    AlertDialog.Builder(this@SettingsActivity).setTitle(R.string.edge_option).setMessage(R.string.edge_privacy)
                        .setPositiveButton(R.string.edge_enable) { _, _ ->
                            prefs.edit().putBoolean("edgeConsent", true).putBoolean("edgeTts", true).apply()
                            isChecked = true
                            resetSpeech()
                        }.setNegativeButton(R.string.close, null).show()
                } else {
                    prefs.edit().putBoolean("edgeTts", isChecked).apply()
                    resetSpeech()
                }
            }
        })
        label(getString(R.string.edge_notice))
        root.addView(action(getString(R.string.edge_voice)) {
            val names = arrayOf(getString(R.string.edge_xiaoxiao), getString(R.string.edge_yunxi))
            AlertDialog.Builder(this).setTitle(R.string.edge_voice).setSingleChoiceItems(names,
                com.sanqi.coach.tts.EdgeTtsClient.VOICES.indexOf(prefs.getString("edgeVoice", com.sanqi.coach.tts.EdgeTtsClient.VOICES.first()))) { dialog, index ->
                prefs.edit().putString("edgeVoice", com.sanqi.coach.tts.EdgeTtsClient.VOICES[index]).apply()
                resetSpeech()
                dialog.dismiss()
            }.show()
        })
        root.addView(action(getString(R.string.tts_choose_engine)) {
            val engines = tts?.engines().orEmpty()
            val labels = (listOf(getString(R.string.tts_system_default)) + engines.map { it.second }).toTypedArray()
            AlertDialog.Builder(this).setTitle(R.string.tts_choose_engine).setItems(labels) { _, which ->
                prefs.edit().putString("ttsEngine", if (which == 0) null else engines[which - 1].first).apply()
                resetSpeech()
            }.show()
        })
        val rateLabel = label(getString(R.string.speech_rate, prefs.getFloat("ttsRate", 1f)))
        root.addView(SeekBar(this).apply {
            max = 150
            progress = ((prefs.getFloat("ttsRate", 1f) - 0.5f) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) {
                    if (!user) return
                    val rate = 0.5f + value / 100f
                    prefs.edit().putFloat("ttsRate", rate).apply()
                    rateLabel.text = getString(R.string.speech_rate, rate)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        })
        root.addView(action(getString(R.string.tts_test)) {
            tts?.speak(getString(R.string.tts_sample), prefs.getFloat("ttsRate", 1f))
        })
        root.addView(action(getString(R.string.tts_stop)) { tts?.stop() })
        root.addView(action(getString(R.string.tts_system_settings)) {
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            catch (_: android.content.ActivityNotFoundException) { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
        })
        root.addView(action(getString(R.string.tts_install_data)) {
            try {
                val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                prefs.getString("ttsEngine", null)?.let { intent.setPackage(it) }
                startActivity(intent)
            } catch (_: android.content.ActivityNotFoundException) { speechStatus.setText(R.string.tts_no_engine) }
        })
        heading(R.string.engine_settings)
        label(getString(R.string.engine_download_notice))
        val store = EngineModelStore(this)
        modelStore = store
        val catalog = try { store.catalog() } catch (_: Exception) { emptyList() }
        if (catalog.isEmpty()) label(getString(R.string.engine_not_packaged))
        for ((kind, title) in listOf("go" to R.string.game_go, "xiangqi" to R.string.game_xiangqi, "chess" to R.string.game_chess)) {
            val choices = catalog.filter { kind in it.games }
            if (choices.isEmpty()) continue
            val selection = label(getString(R.string.selected_engine, getString(title), store.selected(kind).let { "${it.title} ${it.version}" }))
            root.addView(action(getString(R.string.choose_engine_for, getString(title))) {
                val names = choices.map { "${it.title} ${it.version}" }.toTypedArray()
                AlertDialog.Builder(this).setTitle(R.string.choose_analysis_engine).setItems(names) { _, index ->
                    val engine = choices[index]
                    if (!store.installed(engine)) {
                        AlertDialog.Builder(this).setMessage(R.string.engine_models_needed).setPositiveButton(R.string.close, null).show()
                    } else {
                        prefs.edit().putString("engine_$kind", engine.id).apply()
                        selection.text = getString(R.string.selected_engine, getString(title), names[index])
                    }
                }.show()
            })
        }
        for (engine in catalog) {
            label("${engine.title} ${engine.version}", true)
            val state = label(getString(if (engine.models.isEmpty()) R.string.engine_no_model
                else if (store.installed(engine)) R.string.model_installed else R.string.model_not_installed))
            label(engine.license)
            if (engine.models.isNotEmpty() && !store.installed(engine)) state.text = getString(R.string.download_saved,
                store.downloadedBytes(engine) / 1048576.0, engine.models.sumOf { it.bytes } / 1048576.0)
            if (engine.models.isNotEmpty()) root.addView(action(getString(R.string.download_model, engine.models.sumOf { it.bytes } / (1024 * 1024))) {
                if (download?.isActive == true) return@action
                downloadButtons.forEach { it.isEnabled = false }
                state.setText(R.string.download_connecting)
                download = scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val context = currentCoroutineContext()
                            var lastUpdate = 0L
                            var lastPhase: com.sanqi.coach.engine.ResumableModelDownloader.Phase? = null
                            var restarted = false
                            store.download(engine, { context.ensureActive() }) { bytes, total, phase, attempt ->
                                val now = System.nanoTime()
                                if (phase == com.sanqi.coach.engine.ResumableModelDownloader.Phase.RESTARTING) restarted = true
                                if (now - lastUpdate > 200_000_000L || bytes == total || phase != lastPhase) {
                                    lastUpdate = now
                                    lastPhase = phase
                                    val restartNotice = if (restarted) getString(R.string.download_restart_notice) else ""
                                    runOnUiThread { if (!isDestroyed && download?.isActive == true) {
                                        val phaseText = when (phase) {
                                            com.sanqi.coach.engine.ResumableModelDownloader.Phase.CONNECTING -> getString(R.string.download_connecting)
                                            com.sanqi.coach.engine.ResumableModelDownloader.Phase.RETRYING -> getString(R.string.download_retrying, attempt)
                                            com.sanqi.coach.engine.ResumableModelDownloader.Phase.VERIFYING -> getString(R.string.download_verifying)
                                            else -> getString(R.string.download_progress, bytes * 100 / maxOf(1, total))
                                        }
                                        state.text = getString(R.string.download_state, phaseText, bytes / 1048576.0, total / 1048576.0) + restartNotice
                                    } }
                                }
                            }
                        }
                        state.setText(R.string.model_installed)
                    } catch (_: CancellationException) {
                        state.setText(R.string.download_cancelled)
                    } catch (_: Exception) {
                        state.setText(R.string.download_failed)
                    } finally {
                        downloadButtons.forEach { it.isEnabled = true }
                    }
                }
            }.also { downloadButtons.add(it) })
        }
        root.addView(action(getString(R.string.cancel_download)) {
            download?.cancel()
            store.cancelDownload()
        })
        root.addView(action(getString(R.string.engine_licenses)) {
            fun notices(path: String): String = assets.list(path).orEmpty().joinToString("\n\n") { name ->
                val file = "$path/$name"
                if (assets.list(file).orEmpty().isNotEmpty()) notices(file)
                else "$name\n" + assets.open(file).bufferedReader().use { it.readText() }
            }
            val content = try { notices("engines/licenses") + "\n\n" + notices("speech") } catch (_: Exception) { getString(R.string.engine_not_packaged) }
            AlertDialog.Builder(this).setTitle(R.string.engine_licenses).setMessage(content).setPositiveButton(R.string.close, null).show()
        })
        setContentView(scroll)
    }

    private fun configureOnlineCoach() {
        val settings = com.sanqi.coach.coach.OnlineCoachSettings(this)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), 0, px(20), 0) }
        form.addView(TextView(this).apply { setText(R.string.online_coach_privacy) })
        val endpoint = android.widget.EditText(this).apply {
            setHint(R.string.online_coach_endpoint); setText(settings.endpoint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val model = android.widget.EditText(this).apply { setHint(R.string.online_coach_model); setText(settings.model); isSingleLine = true }
        val secret = android.widget.EditText(this).apply {
            setHint(R.string.online_coach_key)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        form.addView(endpoint); form.addView(model); form.addView(secret)
        val dialog = AlertDialog.Builder(this).setTitle(R.string.online_coach_configure)
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton(R.string.online_coach_save, null).setNegativeButton(R.string.close, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    settings.save(endpoint.text.toString(), model.text.toString(), secret.text.toString())
                    settings.setEnabled(true)
                    onlineToggle?.isChecked = true
                    secret.text.clear()
                    dialog.dismiss()
                } catch (_: Exception) {
                    android.widget.Toast.makeText(this, R.string.online_coach_config_invalid, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun resetSpeech() {
        tts?.shutdown()
        tts = TtsManager(this) { speechStatus.text = it }
    }
    private fun heading(resource: Int) { label(getString(resource), true).apply { textSize = 22f; setPadding(0, px(20), 0, px(8)) } }
    private fun label(value: String, bold: Boolean = false): TextView = TextView(this).apply {
        text = value; textSize = if (bold) 17f else 14f
        setTextColor(getColor(R.color.ink)); setPadding(0, px(4), 0, px(4))
        if (bold) setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        root.addView(this)
    }
    private fun action(value: String, click: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = value; isAllCaps = false; cornerRadius = px(14)
        setOnClickListener { click() }
    }
    private fun check(resource: Int, key: String, default: Boolean) {
        root.addView(CheckBox(this).apply {
            setText(resource); isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
        })
    }
    override fun onResume() {
        super.onResume()
        if (::speechStatus.isInitialized) resetSpeech()
    }
    override fun onStop() { download?.cancel(); modelStore?.cancelDownload(); tts?.stop(); super.onStop() }
    override fun onDestroy() { scope.cancel(); tts?.shutdown(); super.onDestroy() }
}
