package com.sanqi.coach.engine

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class EngineModelStore(context: Context) {
    data class Model(val name: String, val url: String, val sha256: String, val bytes: Long)
    data class Engine(val id: String, val title: String, val version: String, val models: List<Model>, val license: String,
                      val binary: String, val games: List<String>)
    private val app = context.applicationContext
    private val verified = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun catalog(): List<Engine> {
        val json = JSONObject(app.assets.open("engines/catalog.json").bufferedReader().use { it.readText() })
        val engines = json.getJSONArray("engines")
        return List(engines.length()) { i ->
            val e = engines.getJSONObject(i)
            val models = e.getJSONArray("models")
            Engine(e.getString("id"), e.getString("name"), e.getString("version"), List(models.length()) { j ->
                val m = models.getJSONObject(j)
                val name = m.getString("name")
                require(name.matches(Regex("[A-Za-z0-9_.-]+")) && !name.startsWith('.'))
                val hash = m.getString("sha256")
                require(hash.matches(Regex("[a-f0-9]{64}")))
                Model(name, m.getString("url"), hash, m.getLong("bytes"))
            }, e.getString("license"), e.getString("binary"), e.getJSONArray("games").let { games ->
                List(games.length()) { games.getString(it) }
            })
        }
    }

    fun selected(kind: String): Engine {
        val choices = catalog().filter { kind in it.games }
        val id = app.getSharedPreferences("coach", Context.MODE_PRIVATE).getString("engine_$kind", null)
        return choices.firstOrNull { it.id == id } ?: choices.first()
    }

    fun directory(engine: Engine): File = File(app.filesDir, "engines/${engine.id}").apply { mkdirs() }
    fun executable(engine: Engine): File = File(app.applicationInfo.nativeLibraryDir, "lib${engine.binary}.so")
    fun installed(engine: Engine): Boolean = engine.models.all { File(directory(engine), it.name).length() == it.bytes }

    fun requireModels(engine: Engine, cancelled: () -> Unit) {
        for (model in engine.models) {
            val file = File(directory(engine), model.name)
            if (!file.isFile || file.length() != model.bytes) throw EngineException(EngineProblem.MODEL_MISSING)
            val key = "${file.path}:${file.lastModified()}:${model.sha256}"
            if (key !in verified) {
                if (digest(file, cancelled) != model.sha256) throw EngineException(EngineProblem.MODEL_MISSING)
                verified.add(key)
            }
        }
    }

    private val downloader = ResumableModelDownloader()

    fun cancelDownload() = downloader.cancel()

    fun downloadedBytes(engine: Engine): Long = engine.models.sumOf { model ->
        val target = File(directory(engine), model.name)
        if (target.length() == model.bytes) model.bytes
        else ResumableModelDownloader.partial(target, model.sha256).length().coerceAtMost(model.bytes)
    }

    fun download(engine: Engine, cancelled: () -> Unit,
                 progress: (Long, Long, ResumableModelDownloader.Phase, Int) -> Unit) {
        val dir = directory(engine)
        val total = engine.models.sumOf { it.bytes }
        var completed = 0L
        for (model in engine.models) {
            cancelled()
            val target = File(dir, model.name)
            if (target.isFile && target.length() == model.bytes && digest(target, cancelled) == model.sha256) {
                completed += model.bytes
                progress(completed, total, ResumableModelDownloader.Phase.VERIFYING, 0)
                continue
            }
            downloader.download(model.url, target, model.bytes, model.sha256, cancelled) { received, phase, attempt ->
                progress(completed + received, total, phase, attempt)
            }
            completed += model.bytes
        }
    }

    private fun digest(file: File, cancelled: () -> Unit): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                cancelled()
                val n = input.read(buffer)
                if (n < 0) break
                hash.update(buffer, 0, n)
            }
        }
        return hex(hash.digest())
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
