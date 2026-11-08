package de.davscha.denkwerk.engine

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Ein Modell, das auf dem PC in Ollama installiert ist. */
data class OllamaModel(val name: String, val sizeBytes: Long, val vision: Boolean)

/** Kleine Hilfsfunktionen für die Ollama-Schnittstelle (HTTP + JSON). */
class OllamaClient(host: String, port: Int) {

    private val base = "http://${host.trim()}:$port"

    /** Ollama-Version – zugleich der Verbindungstest. */
    suspend fun version(): String = withContext(Dispatchers.IO) {
        JSONObject(get("/api/version", timeoutMs = 4000)).optString("version", "?")
    }

    /** Alle installierten Modelle, jeweils mit Prüfung, ob sie Bilder verstehen. */
    suspend fun models(): List<OllamaModel> = withContext(Dispatchers.IO) {
        val arr = JSONObject(get("/api/tags", timeoutMs = 8000)).optJSONArray("models") ?: JSONArray()
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val name = o.optString("name")
            OllamaModel(name, o.optLong("size"), vision = runCatching { hasVision(name) }.getOrDefault(false))
        }.sortedBy { it.name.lowercase() }
    }

    /** Kann das Modell Bilder ansehen? Neuere Ollama-Versionen melden "vision" unter capabilities. */
    fun hasVision(model: String): Boolean {
        val o = JSONObject(post("/api/show", JSONObject().put("model", model), timeoutMs = 8000))
        o.optJSONArray("capabilities")?.let { caps ->
            return (0 until caps.length()).any { caps.optString(it) == "vision" }
        }
        // Ältere Versionen: Vision-Modelle haben einen "Projector"
        return o.has("projector_info")
    }

    /** Einmalige Antwort ohne Verlauf, z. B. für Chat-Titel. */
    fun generate(model: String, prompt: String): String {
        val body = JSONObject().put("model", model).put("prompt", prompt).put("stream", false)
            .put("keep_alive", "30m")
            .put("options", JSONObject().put("temperature", 0.3).put("num_predict", 40))
        return JSONObject(post("/api/generate", body, timeoutMs = 60_000)).optString("response").trim()
    }

    /** Modell in den Grafikspeicher laden, damit die erste Antwort schnell kommt. */
    fun warmUp(model: String) {
        post("/api/generate", JSONObject().put("model", model).put("prompt", "").put("keep_alive", "30m"), timeoutMs = 180_000)
    }

    fun open(path: String, timeoutMs: Int): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = minOf(timeoutMs, 5000)
            readTimeout = timeoutMs
        }

    private fun get(path: String, timeoutMs: Int): String {
        val c = open(path, timeoutMs)
        try {
            if (c.responseCode !in 200..299) throw IOException("Ollama antwortet mit Fehler ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    fun post(path: String, body: JSONObject, timeoutMs: Int): String {
        val c = open(path, timeoutMs)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.readText().orEmpty()
                throw IOException(runCatching { JSONObject(err).optString("error") }.getOrNull()?.ifEmpty { null }
                    ?: "Ollama antwortet mit Fehler ${c.responseCode}")
            }
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }
}

/**
 * Die KI läuft auf dem PC in Ollama; das Tablet schickt die Nachrichten übers Heimnetz.
 * Ollama merkt sich nichts – deshalb hält diese Klasse den Verlauf und schickt ihn jedes Mal mit.
 */
class OllamaEngine(private val hostProvider: () -> Pair<String, Int>) : ChatEngine {

    private var client: OllamaClient? = null
    private var model: String = ""
    private val history = mutableListOf<JSONObject>()
    private var temperature = 0.9
    @Volatile private var activeConnection: HttpURLConnection? = null

    override var supportsImages = false
        private set

    /** [modelPath] ist hier der Name des Ollama-Modells, z. B. "gemma4:e4b". */
    override suspend fun load(modelPath: String): Accelerator = withContext(Dispatchers.IO) {
        val (host, port) = hostProvider()
        val c = OllamaClient(host, port)
        c.version() // erreichbar?
        supportsImages = runCatching { c.hasVision(modelPath) }.getOrDefault(false)
        c.warmUp(modelPath)
        client = c
        model = modelPath
        Accelerator.NETWORK
    }

    override fun startConversation(systemPrompt: String, history: List<Pair<Boolean, String>>, temperature: Double) {
        this.temperature = temperature
        this.history.clear()
        if (systemPrompt.isNotBlank()) this.history += JSONObject().put("role", "system").put("content", systemPrompt)
        history.filter { it.second.isNotBlank() }.forEach { (fromUser, text) ->
            this.history += JSONObject().put("role", if (fromUser) "user" else "assistant").put("content", text)
        }
    }

    /** Ollama nimmt mehrere Bilder pro Nachricht. */
    override val maxImages: Int get() = if (supportsImages) MAX_IMAGES else 0

    override fun send(text: String, imagePaths: List<String>): Flow<String> = flow {
        val c = client ?: error("Kein Ollama-Modell geladen")
        val userMsg = JSONObject().put("role", "user").put("content", text)
        if (imagePaths.isNotEmpty()) {
            check(supportsImages) { "Dieses Modell kann keine Bilder ansehen." }
            val arr = JSONArray()
            imagePaths.take(MAX_IMAGES).forEach { p -> arr.put(Base64.encodeToString(File(p).readBytes(), Base64.NO_WRAP)) }
            userMsg.put("images", arr)
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray(history + userMsg))
            .put("stream", true)
            .put("keep_alive", "30m")
            .put("options", JSONObject().put("temperature", temperature))

        val conn = c.open("/api/chat", timeoutMs = 180_000)
        activeConnection = conn
        val reply = StringBuilder()
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                throw IOException(runCatching { JSONObject(err).optString("error") }.getOrNull()?.ifEmpty { null }
                    ?: "Ollama antwortet mit Fehler ${conn.responseCode}")
            }
            // Ollama schickt eine JSON-Zeile pro Wortstück
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val o = JSONObject(line)
                    o.optString("error").takeIf { it.isNotEmpty() }?.let { throw IOException(it) }
                    val chunk = o.optJSONObject("message")?.optString("content").orEmpty()
                    if (chunk.isNotEmpty()) {
                        reply.append(chunk)
                        emit(chunk)
                    }
                    if (o.optBoolean("done")) break
                }
            }
        } catch (e: IOException) {
            // Stopp-Knopf trennt die Verbindung – das ist kein Fehler
            if (activeConnection != null) throw e
        } finally {
            activeConnection = null
            conn.disconnect()
        }
        // Verlauf weiterführen (das Bild nur als Hinweis, damit nicht jedes Mal Megabytes mitgehen)
        history += JSONObject().put("role", "user").put("content", when (imagePaths.size) {
            0 -> text
            1 -> "[schickt ein Bild] $text"
            else -> "[schickt ${imagePaths.size} Bilder] $text"
        })
        if (reply.isNotEmpty()) history += JSONObject().put("role", "assistant").put("content", reply.toString())
    }.flowOn(Dispatchers.IO)

    override fun stop() {
        val c = activeConnection ?: return
        activeConnection = null
        runCatching { c.disconnect() }
    }

    override fun close() {
        stop()
        client = null
        model = ""
        history.clear()
        supportsImages = false
    }

    private companion object {
        const val MAX_IMAGES = 8
    }
}
