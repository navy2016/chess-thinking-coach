package com.sanqi.coach.engine

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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

    fun download(engine: Engine, cancelled: () -> Unit, progress: (Long, Long) -> Unit) {
        val dir = directory(engine)
        val required = engine.models.filter { File(dir, it.name).length() != it.bytes }.sumOf { it.bytes }
        require(dir.usableSpace > required + 32L * 1024 * 1024) { "INSUFFICIENT_SPACE" }
        val total = engine.models.sumOf { it.bytes }
        var completed = 0L
        for (model in engine.models) {
            cancelled()
            val target = File(dir, model.name)
            if (target.isFile && target.length() == model.bytes && digest(target, cancelled) == model.sha256) {
                completed += model.bytes
                progress(completed, total)
                continue
            }
            require(dir.usableSpace > model.bytes + 32L * 1024 * 1024) { "INSUFFICIENT_SPACE" }
            val temporary = File(dir, model.name + ".part")
            var address = URL(model.url)
            var connection: HttpURLConnection? = null
            try {
                for (redirect in 0..5) {
                    cancelled()
                    require(address.protocol == "https")
                    val current = address.openConnection() as HttpURLConnection
                    current.connectTimeout = 15000
                    current.readTimeout = 15000
                    current.instanceFollowRedirects = false
                    connection = current
                    val status = current.responseCode
                    if (status in 300..399) {
                        val location = current.getHeaderField("Location") ?: error("REDIRECT_WITHOUT_LOCATION")
                        address = URL(address, location)
                        current.disconnect()
                        if (redirect == 5) error("TOO_MANY_REDIRECTS")
                        continue
                    }
                    check(status == 200) { "HTTP_$status" }
                    break
                }
                val source = connection ?: error("NO_CONNECTION")
                val hash = MessageDigest.getInstance("SHA-256")
                var received = 0L
                source.inputStream.use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        cancelled()
                        val n = input.read(buffer)
                        if (n < 0) break
                        received += n
                        check(received <= model.bytes) { "MODEL_SIZE_MISMATCH" }
                        output.write(buffer, 0, n)
                        hash.update(buffer, 0, n)
                        progress(completed + received, total)
                    }
                    output.fd.sync()
                } }
                check(received == model.bytes && hex(hash.digest()) == model.sha256) { "MODEL_CHECKSUM_MISMATCH" }
                cancelled()
                check(temporary.renameTo(target)) { "MODEL_INSTALL_FAILED" }
                completed += received
            } finally {
                connection?.disconnect()
                temporary.delete()
            }
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
