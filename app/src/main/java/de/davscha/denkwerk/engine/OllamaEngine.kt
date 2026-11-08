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
data class OllamaModel(val name: String, val sizeBytes: Long, val vision: Boolean, val tools: Boolean = false)

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
            val caps = runCatching { capabilities(name) }.getOrDefault(emptySet())
            OllamaModel(name, o.optLong("size"), vision = "vision" in caps, tools = "tools" in caps)
        }.sortedBy { it.name.lowercase() }
    }

    /**
     * Was das Modell kann, z. B. {"completion", "vision", "tools"}.
     * Ältere Ollama-Versionen ohne "capabilities": Vision-Modelle erkennt man am "Projector".
     */
    fun capabilities(model: String): Set<String> {
        val o = JSONObject(post("/api/show", JSONObject().put("model", model), timeoutMs = 8000))
        o.optJSONArray("capabilities")?.let { caps -> return (0 until caps.length()).map { caps.optString(it) }.toSet() }
        return if (o.has("projector_info")) setOf("completion", "vision") else setOf("completion")
    }

    fun hasVision(model: String): Boolean = "vision" in capabilities(model)

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
class OllamaEngine(
    private val hostProvider: () -> Pair<String, Int>,
    /** Kontextgröße in Tokens – Ollama nimmt sonst oft nur 4096, dann „vergisst“ das Modell Bilder und Anfang */
    private val contextProvider: () -> Int = { 16384 },
) : ChatEngine {

    private var client: OllamaClient? = null
    private var model: String = ""
    private val history = mutableListOf<JSONObject>()
    private var temperature = 0.9
    @Volatile private var activeConnection: HttpURLConnection? = null

    override var supportsImages = false
        private set

    /** true = das Modell kann Werkzeuge aufrufen (Websuche, Seite lesen …) */
    var supportsTools = false
        private set

    /** [modelPath] ist hier der Name des Ollama-Modells, z. B. "gemma4:e4b". */
    override suspend fun load(modelPath: String): Accelerator = withContext(Dispatchers.IO) {
        val (host, port) = hostProvider()
        val c = OllamaClient(host, port)
        c.version() // erreichbar?
        val caps = runCatching { c.capabilities(modelPath) }.getOrDefault(emptySet())
        supportsImages = "vision" in caps
        supportsTools = "tools" in caps
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
        val userMsg = userMessage(text, imagePaths)
        val (reply, _) = streamChat(JSONArray(history + userMsg), tools = null) { emit(it) }
        rememberTurn(text, imagePaths)
        if (reply.isNotEmpty()) history += JSONObject().put("role", "assistant").put("content", reply)
    }.flowOn(Dispatchers.IO)

    /**
     * Wie [send], aber das Modell darf Werkzeuge benutzen. Ablauf je Runde:
     * Anfrage mit Werkzeugliste → Modell antwortet mit Text oder mit Werkzeug-Aufrufen →
     * Denkwerk führt sie aus ([runTool]) und schickt die Ergebnisse zurück → nächste Runde.
     */
    fun sendWithTools(
        text: String,
        imagePaths: List<String>,
        tools: JSONArray,
        runTool: suspend (name: String, args: JSONObject) -> ToolOutput,
    ): Flow<ToolStreamEvent> = flow {
        val userMsg = userMessage(text, imagePaths)
        val turn = mutableListOf(userMsg)
        stopped = false
        for (round in 1..MAX_TOOL_ROUNDS) {
            // In der letzten Runde keine Werkzeuge mehr anbieten – dann muss eine Antwort kommen
            val offer = if (round < MAX_TOOL_ROUNDS) tools else null
            val (reply, calls) = streamChat(JSONArray(history + turn), offer) { emit(ToolStreamEvent.Text(it)) }
            if (stopped) break
            if (calls == null || calls.length() == 0) {
                if (reply.isNotEmpty()) turn += JSONObject().put("role", "assistant").put("content", reply)
                break
            }
            turn += JSONObject().put("role", "assistant").put("content", reply).put("tool_calls", calls)
            for (i in 0 until calls.length()) {
                val fn = calls.getJSONObject(i).optJSONObject("function") ?: continue
                val name = fn.optString("name")
                // Argumente kommen meist als Objekt, manche Modelle schicken einen JSON-Text
                val args = fn.optJSONObject("arguments")
                    ?: runCatching { JSONObject(fn.optString("arguments")) }.getOrDefault(JSONObject())
                emit(ToolStreamEvent.ToolStart(name, args))
                val out = runCatching { runTool(name, args) }
                    .getOrElse { e -> ToolOutput("Fehler: ${e.message ?: e.javaClass.simpleName}", "Fehler: ${e.message}", ok = false) }
                emit(ToolStreamEvent.ToolDone(name, out))
                turn += JSONObject().put("role", "tool").put("tool_name", name).put("content", out.forModel)
                if (stopped) break
            }
            if (stopped) break
        }
        // Verlauf: das Bild nur als Hinweis, Werkzeug-Runden komplett (damit Rückfragen funktionieren)
        rememberTurn(text, imagePaths)
        history += turn.drop(1)
    }.flowOn(Dispatchers.IO)

    private fun userMessage(text: String, imagePaths: List<String>): JSONObject {
        val userMsg = JSONObject().put("role", "user").put("content", text)
        if (imagePaths.isNotEmpty()) {
            check(supportsImages) { "Dieses Modell kann keine Bilder ansehen." }
            val arr = JSONArray()
            imagePaths.take(MAX_IMAGES).forEach { p -> arr.put(Base64.encodeToString(File(p).readBytes(), Base64.NO_WRAP)) }
            userMsg.put("images", arr)
        }
        return userMsg
    }

    /** Deine Nachricht im Verlauf merken (Bilder nur als Hinweis, damit nicht jedes Mal Megabytes mitgehen). */
    private fun rememberTurn(text: String, imagePaths: List<String>) {
        history += JSONObject().put("role", "user").put("content", when (imagePaths.size) {
            0 -> text
            1 -> "[schickt ein Bild] $text"
            else -> "[schickt ${imagePaths.size} Bilder] $text"
        })
    }

    /**
     * Eine Anfrage an /api/chat mit Mitschreiben. Gibt den Text und – falls das Modell
     * Werkzeuge aufrufen will – die tool_calls zurück.
     */
    private suspend fun streamChat(
        messages: JSONArray,
        tools: JSONArray?,
        onText: suspend (String) -> Unit,
    ): Pair<String, JSONArray?> {
        val c = client ?: error("Kein Ollama-Modell geladen")
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
            .put("keep_alive", "30m")
            .put(
                "options",
                JSONObject()
                    .put("temperature", temperature)
                    .put("num_ctx", contextProvider())
                    // leichte Strafe für Wiederholungen – verhindert Schleifen wie „getgetget…“
                    .put("repeat_penalty", 1.1)
                    .put("repeat_last_n", 256),
            )
        if (tools != null && tools.length() > 0) body.put("tools", tools)

        val conn = c.open("/api/chat", timeoutMs = 180_000)
        activeConnection = conn
        val reply = StringBuilder()
        var calls: JSONArray? = null
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
            // Ollama schickt eine JSON-Zeile pro Wortstück; Werkzeug-Aufrufe kommen als message.tool_calls
            val reader = conn.inputStream.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val o = JSONObject(line)
                o.optString("error").takeIf { it.isNotEmpty() }?.let { throw IOException(it) }
                val msg = o.optJSONObject("message")
                val chunk = msg?.optString("content").orEmpty()
                if (chunk.isNotEmpty()) {
                    reply.append(chunk)
                    onText(chunk)
                }
                msg?.optJSONArray("tool_calls")?.let { tc ->
                    val all = calls ?: JSONArray().also { calls = it }
                    for (i in 0 until tc.length()) all.put(tc.get(i))
                }
                if (o.optBoolean("done")) break
            }
        } catch (e: IOException) {
            // Stopp-Knopf trennt die Verbindung – das ist kein Fehler
            if (activeConnection != null) throw e
        } finally {
            activeConnection = null
            conn.disconnect()
        }
        return reply.toString() to calls
    }

    @Volatile private var stopped = false

    override fun stop() {
        stopped = true
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
        supportsTools = false
    }

    private companion object {
        const val MAX_IMAGES = 8
        /** Höchstens so viele Werkzeug-Runden pro Nachricht */
        const val MAX_TOOL_ROUNDS = 6
    }
}
