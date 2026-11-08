package de.davscha.denkwerk.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.davscha.denkwerk.data.Chat
import de.davscha.denkwerk.data.ChatMessage
import de.davscha.denkwerk.data.ChatStore
import de.davscha.denkwerk.data.Preset
import de.davscha.denkwerk.data.Variant
import de.davscha.denkwerk.data.Settings
import de.davscha.denkwerk.data.Source
import de.davscha.denkwerk.engine.Accelerator
import de.davscha.denkwerk.engine.ChatEngine
import de.davscha.denkwerk.engine.LiteRtEngine
import de.davscha.denkwerk.engine.OllamaClient
import de.davscha.denkwerk.engine.OllamaEngine
import de.davscha.denkwerk.engine.OllamaModel
import de.davscha.denkwerk.engine.Speaker
import de.davscha.denkwerk.model.ModelCatalog
import de.davscha.denkwerk.model.ModelDownloader
import de.davscha.denkwerk.model.ModelInfo
import de.davscha.denkwerk.model.hasSharedStorage
import de.davscha.denkwerk.model.modelsDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { Chat, Settings, Presets }

/** Zustand des Sprachmodells, das gerade benutzt wird. */
sealed interface EngineStatus {
    data object None : EngineStatus
    data class Loading(val label: String) : EngineStatus
    data class Ready(val label: String, val accelerator: Accelerator, val vision: Boolean) : EngineStatus
    data class Failed(val label: String, val message: String) : EngineStatus
}

sealed interface DownloadState {
    data object Missing : DownloadState
    data class Running(val done: Long, val total: Long) : DownloadState
    data object Done : DownloadState
    data class Failed(val message: String) : DownloadState
}

/** Ein Eintrag in der Modell-Auswahl oben im Chat. */
data class ModelChoice(val source: Source, val id: String, val title: String, val subtitle: String, val vision: Boolean?)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val context get() = getApplication<Application>()
    private val store = ChatStore(app)
    val settings = Settings(app)

    // ---------- Seiten ----------
    var screen by mutableStateOf(Screen.Chat)
        private set

    fun openSettings() { screen = Screen.Settings }
    fun openPresets() { editingPreset = null; screen = Screen.Presets }
    fun back() {
        if (screen == Screen.Presets && editingPreset != null) editingPreset = null else screen = Screen.Chat
    }

    // ---------- Assistenten (Presets) ----------
    val presets = mutableStateListOf<Preset>()
    /** Assistent für den nächsten neuen Chat (solange noch keine Nachricht geschickt wurde) */
    var pendingPresetId by mutableStateOf<String?>(null)
        private set
    /** Assistent, der gerade im Editor offen ist */
    var editingPreset by mutableStateOf<Preset?>(null)

    /** Assistent des offenen (oder gleich entstehenden) Chats */
    val currentPreset: Preset?
        get() = (currentChat?.presetId ?: pendingPresetId.takeIf { currentChat == null })?.let { id -> presets.firstOrNull { it.id == id } }

    fun presetOf(chat: Chat?): Preset? = chat?.presetId?.let { id -> presets.firstOrNull { it.id == id } }

    // ---------- Chats ----------
    val chats = mutableStateListOf<Chat>()
    var currentChatId by mutableStateOf<String?>(null)
        private set
    val currentChat: Chat? get() = chats.firstOrNull { it.id == currentChatId }

    /** Kurzer Hinweis unten (z. B. "Kein Modell geladen") */
    var toast by mutableStateOf<String?>(null)

    // ---------- Antwort, die gerade geschrieben wird ----------
    var generating by mutableStateOf(false)
        private set
    var streamingId by mutableStateOf<String?>(null)
        private set
    var streamingText by mutableStateOf("")
        private set
    private var generateJob: Job? = null
    @Volatile private var stopRequested = false

    // ---------- Modell ----------
    private var engine: ChatEngine? = null
    var engineStatus by mutableStateOf<EngineStatus>(EngineStatus.None)
        private set
    private var activeLabel = ""
    private var loadJob: Job? = null
    /** Welcher Chat-Stand gerade im Modell steckt – passt er nicht, wird das Gespräch neu aufgebaut. */
    private var engineSyncKey: String? = null

    /** "QUELLE:id" des gewählten Modells – als State, damit die Häkchen in der Oberfläche mitgehen */
    var activeKey by mutableStateOf("")
        private set

    val visionAvailable: Boolean get() = (engineStatus as? EngineStatus.Ready)?.vision == true

    /** Wie viele Bilder das aktive Modell pro Nachricht nimmt (Ollama mehrere, Gerät eins). */
    val maxImages: Int get() = if (visionAvailable) engine?.maxImages ?: 0 else 0

    // ---------- Modelle auf dem Gerät ----------
    var internalModels by mutableStateOf<List<ModelInfo>>(emptyList())
        private set
    val downloads = mutableStateMapOf<String, DownloadState>()
    private val downloadJobs = mutableMapOf<String, Job>()
    val isDownloading: Boolean get() = downloads.values.any { it is DownloadState.Running }
    var importProgress by mutableStateOf<String?>(null)
        private set
    var sharedStorage by mutableStateOf(false)
        private set

    // ---------- Ollama ----------
    var ollamaModels by mutableStateOf<List<OllamaModel>>(emptyList())
        private set
    var ollamaStatus by mutableStateOf("")
        private set
    var ollamaBusy by mutableStateOf(false)
        private set

    // ---------- Vorlesen ----------
    var speakingId by mutableStateOf<String?>(null)
        private set
    private val speaker = Speaker(app) { id -> viewModelScope.launch(Dispatchers.Main) { speakingId = id } }

    init {
        refreshStorage()
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { store.loadAll() }
            chats.clear()
            chats.addAll(loaded)
            val p = withContext(Dispatchers.IO) { store.loadPresets() }
            presets.clear()
            presets.addAll(p)
        }
        if (settings.ollamaHost.isNotBlank()) testOllama(silent = true)
        loadActiveModel()
    }

    /** Nach Rückkehr aus den Android-Einstellungen: Speicherort und Modelle neu prüfen. */
    fun refreshStorage() {
        val before = sharedStorage
        sharedStorage = hasSharedStorage()
        internalModels = ModelCatalog.all + ModelCatalog.customModels(context)
        internalModels.forEach { m ->
            if (downloads[m.id] !is DownloadState.Running) {
                downloads[m.id] = if (m.isDownloaded(context)) DownloadState.Done else DownloadState.Missing
            }
        }
        // Erlaubnis gerade erteilt? Dann liegen die Chats evtl. woanders
        if (before != sharedStorage && !generating) {
            viewModelScope.launch {
                val loaded = withContext(Dispatchers.IO) { store.loadAll() }
                if (loaded.isNotEmpty() || chats.isEmpty()) {
                    chats.clear()
                    chats.addAll(loaded)
                }
            }
        }
    }

    // =====================================================================
    // Chats verwalten
    // =====================================================================

    fun newChat(presetId: String? = null) {
        if (generating) stop()
        currentChatId = null
        pendingPresetId = presetId
        screen = Screen.Chat
        // Lieblingsmodell des Assistenten wählen, falls vorhanden
        val preset = presets.firstOrNull { it.id == presetId } ?: return
        if (preset.modelKey.isBlank()) return
        val choice = modelChoices.firstOrNull { "${it.source}:${it.id}" == preset.modelKey }
        when {
            choice == null -> toast = "Lieblingsmodell von ${preset.name} ist gerade nicht verfügbar – nutze das aktuelle."
            !isActive(choice) -> select(choice)
        }
    }

    // ---------- Assistenten bearbeiten ----------

    fun editPreset(preset: Preset?) { editingPreset = preset ?: Preset() }

    fun savePreset(preset: Preset) {
        val i = presets.indexOfFirst { it.id == preset.id }
        if (i >= 0) presets[i] = preset else presets.add(preset)
        editingPreset = null
        engineSyncKey = null
        persistPresets()
    }

    fun deletePreset(id: String) {
        presets.removeAll { it.id == id }
        if (pendingPresetId == id) pendingPresetId = null
        editingPreset = null
        persistPresets()
    }

    private fun persistPresets() {
        val copy = presets.toList()
        viewModelScope.launch(Dispatchers.IO) { runCatching { store.savePresets(copy) } }
    }

    fun openChat(id: String) {
        if (generating) stop()
        currentChatId = id
        screen = Screen.Chat
    }

    fun deleteChat(id: String) {
        val chat = chats.firstOrNull { it.id == id } ?: return
        if (currentChatId == id) newChat()
        chats.remove(chat)
        viewModelScope.launch(Dispatchers.IO) { store.delete(chat) }
    }

    fun renameChat(id: String, title: String) {
        val chat = chats.firstOrNull { it.id == id } ?: return
        upsert(chat.copy(title = title.trim().ifEmpty { chat.title }), touch = false)
    }

    fun togglePin(id: String) {
        val chat = chats.firstOrNull { it.id == id } ?: return
        upsert(chat.copy(pinned = !chat.pinned), touch = false)
    }

    fun exportMarkdown(id: String): String? = chats.firstOrNull { it.id == id }?.let { store.toMarkdown(it) }

    private fun upsert(chat: Chat, touch: Boolean = true, save: Boolean = true) {
        val updated = if (touch) chat.copy(updatedAt = System.currentTimeMillis()) else chat
        val i = chats.indexOfFirst { it.id == chat.id }
        if (i >= 0) chats[i] = updated else chats.add(0, updated)
        if (save) viewModelScope.launch(Dispatchers.IO) { runCatching { store.save(updated) } }
    }

    suspend fun importImage(uri: Uri): String? = withContext(Dispatchers.IO) { store.importImage(uri) }

    // =====================================================================
    // Nachrichten schicken
    // =====================================================================

    fun send(text: String, imagePaths: List<String> = emptyList()) {
        if (generating) return
        if (text.isBlank() && imagePaths.isEmpty()) return
        if (engineStatus !is EngineStatus.Ready) {
            toast = when (engineStatus) {
                is EngineStatus.Loading -> "Das Modell lädt noch – einen Moment."
                else -> "Kein Modell bereit. Wähle oben ein Modell oder öffne die Einstellungen."
            }
            return
        }
        val base = currentChat ?: Chat(title = Chat.titleFrom(text), presetId = pendingPresetId)
        val userMsg = ChatMessage(fromUser = true, text = text.trim(), imagePaths = imagePaths.take(maxImages.coerceAtLeast(1)))
        val history = base.messages
        val chat = base.copy(messages = history + userMsg)
        currentChatId = chat.id
        upsert(chat)
        generate(chat.id, history, userMsg, isFirst = history.isEmpty())
    }

    /** Letzte Antwort neu schreiben lassen – die alte Fassung bleibt erhalten (‹ 1/2 ›). */
    fun regenerate() {
        if (generating) return
        val chat = currentChat ?: return
        val lastUser = chat.messages.indexOfLast { it.fromUser }
        if (lastUser < 0) return
        val history = chat.messages.take(lastUser)
        val userMsg = chat.messages[lastUser]
        val old = chat.messages.drop(lastUser + 1).lastOrNull { !it.fromUser }
        val previous = when {
            old == null -> emptyList()
            old.variants.isEmpty() -> listOf(old.asVariant())
            else -> old.variants
        }
        upsert(chat.copy(messages = history + userMsg))
        generate(chat.id, history, userMsg, isFirst = false, previousVariants = previous)
    }

    /** Zwischen den Fassungen einer Antwort blättern. */
    fun showVariant(messageId: String, index: Int) {
        if (generating) return
        val chat = currentChat ?: return
        val msg = chat.messages.firstOrNull { it.id == messageId } ?: return
        if (index !in msg.variants.indices || index == msg.variantIndex) return
        upsert(chat.copy(messages = chat.messages.map { if (it.id == messageId) it.showVariant(index) else it }), touch = false)
        engineSyncKey = null // das Modell kennt jetzt eine andere Antwort
    }

    /** Eine eigene Nachricht ändern: alles danach fällt weg, die KI antwortet neu. */
    fun editMessage(messageId: String, newText: String) {
        if (generating || newText.isBlank()) return
        val chat = currentChat ?: return
        val i = chat.messages.indexOfFirst { it.id == messageId }
        if (i < 0 || !chat.messages[i].fromUser) return
        val history = chat.messages.take(i)
        val userMsg = chat.messages[i].copy(text = newText.trim(), id = de.davscha.denkwerk.data.newId())
        upsert(chat.copy(messages = history + userMsg))
        generate(chat.id, history, userMsg, isFirst = false)
    }

    fun stop() {
        if (!generating) return
        stopRequested = true
        engine?.stop()
        // Falls das Modell nicht reagiert: nach kurzer Zeit hart abbrechen
        val job = generateJob
        viewModelScope.launch {
            delay(2500)
            if (generateJob === job && generating) job?.cancel()
        }
    }

    /** Anweisung und Temperatur: vom Assistenten des Chats, sonst aus den Einstellungen. */
    private fun promptFor(chat: Chat?): Pair<String, Float> {
        val p = presetOf(chat)
        return if (p != null && p.systemPrompt.isNotBlank()) p.systemPrompt to p.temperature
        else settings.systemPrompt to (p?.temperature ?: settings.temperature)
    }

    private fun syncKey(chatId: String, size: Int): String {
        val (prompt, temp) = promptFor(chats.firstOrNull { it.id == chatId })
        return "$chatId:$size:$activeLabel:${prompt.hashCode()}:$temp"
    }

    private fun generate(
        chatId: String,
        history: List<ChatMessage>,
        userMsg: ChatMessage,
        isFirst: Boolean,
        previousVariants: List<Variant> = emptyList(),
    ) {
        val eng = engine ?: return
        val (systemPrompt, temperature) = promptFor(chats.firstOrNull { it.id == chatId })
        val reply = ChatMessage(fromUser = false, text = "", modelLabel = activeLabel)
        chats.firstOrNull { it.id == chatId }?.let { upsert(it.copy(messages = it.messages + reply), save = false) }
        generating = true
        stopRequested = false
        streamingId = reply.id
        streamingText = ""
        speaker.stop()

        generateJob = viewModelScope.launch {
            var finalText = ""
            var error: String? = null
            var tps = 0f
            try {
                val key = syncKey(chatId, history.size)
                if (engineSyncKey != key) {
                    withContext(Dispatchers.IO) {
                        eng.startConversation(systemPrompt, trimHistory(history), temperature.toDouble())
                    }
                }
                engineSyncKey = null // stimmt erst wieder, wenn die Antwort vollständig ist
                val prompt = userMsg.text.ifBlank { "Beschreibe dieses Bild." }
                val start = System.currentTimeMillis()
                var firstChunkAt = 0L
                var chunks = 0
                eng.send(prompt, userMsg.imagePaths).collect { piece ->
                    if (chunks == 0) firstChunkAt = System.currentTimeMillis()
                    chunks++
                    streamingText += piece
                }
                val secs = (System.currentTimeMillis() - (if (firstChunkAt > 0) firstChunkAt else start)) / 1000f
                tps = if (secs > 0.2f) chunks / secs else 0f
                finalText = streamingText
                if (!stopRequested) engineSyncKey = syncKey(chatId, history.size + 2)
            } catch (e: CancellationException) {
                finalText = streamingText
            } catch (e: Throwable) {
                finalText = streamingText
                error = e.message ?: e.javaClass.simpleName
            } finally {
                val text = when {
                    error != null && finalText.isBlank() -> "Fehler: $error"
                    error != null -> "$finalText\n\n_(abgebrochen: ${error})_"
                    finalText.isBlank() && stopRequested -> "_(gestoppt)_"
                    else -> finalText.trim()
                }
                var done = reply.copy(text = text, tokensPerSec = tps, isError = error != null && finalText.isBlank())
                if (previousVariants.isNotEmpty()) {
                    val all = previousVariants + done.asVariant()
                    done = done.copy(variants = all, variantIndex = all.lastIndex)
                }
                chats.firstOrNull { it.id == chatId }?.let { c ->
                    upsert(c.copy(messages = c.messages.map { if (it.id == reply.id) done else it }))
                }
                streamingId = null
                streamingText = ""
                generating = false
                generateJob = null
            }
            if (isFirst && error == null && !stopRequested) makeTitle(chatId, userMsg.text)
        }
    }

    /** Lange Chats kürzen, damit sie ins Kontextfenster passen (neueste Nachrichten bleiben). */
    private fun trimHistory(history: List<ChatMessage>): List<Pair<Boolean, String>> {
        val budget = if (settings.source == Source.OLLAMA) 24_000 else 7_000
        val usable = history.filter { !it.isError && it.text.isNotBlank() }
        val kept = ArrayDeque<ChatMessage>()
        var used = 0
        for (m in usable.asReversed()) {
            val t = if (m.imagePaths.isNotEmpty()) "[Bild] ${m.text}" else m.text
            if (used + t.length > budget && kept.isNotEmpty()) break
            kept.addFirst(m.copy(text = t))
            used += t.length
        }
        // Das Gespräch soll mit einer Nachricht von dir beginnen
        while (kept.isNotEmpty() && !kept.first().fromUser) kept.removeFirst()
        return kept.map { it.fromUser to it.text }
    }

    /** Bei Ollama schreibt die KI den Titel selbst, sonst bleiben die ersten Wörter. */
    private fun makeTitle(chatId: String, firstText: String) {
        if (settings.source != Source.OLLAMA || firstText.isBlank()) return
        val model = settings.ollamaModel.ifBlank { return }
        viewModelScope.launch {
            val title = runCatching {
                withContext(Dispatchers.IO) {
                    OllamaClient(settings.ollamaHost, settings.ollamaPort).generate(
                        model,
                        "Gib diesem Chat einen kurzen Titel (höchstens 5 Wörter, keine Anführungszeichen, " +
                            "kein Satzzeichen am Ende). Antworte nur mit dem Titel.\n\nNachricht: ${firstText.take(500)}",
                    )
                }
            }.getOrNull()
                ?.replace(Regex("<think>[\\s\\S]*?</think>"), "")
                ?.lines()?.firstOrNull { it.isNotBlank() }
                ?.trim('"', '„', '“', '*', ' ', '.')
                ?.take(50)
            if (!title.isNullOrBlank()) chats.firstOrNull { it.id == chatId }?.let { upsert(it.copy(title = title), touch = false) }
        }
    }

    // =====================================================================
    // Modell laden und wechseln
    // =====================================================================

    /** Alles, was oben im Chat zur Auswahl steht. */
    val modelChoices: List<ModelChoice>
        get() = buildList {
            internalModels.filter { downloads[it.id] == DownloadState.Done }.forEach {
                add(ModelChoice(Source.INTERNAL, it.id, it.title, "Auf dem Gerät", vision = null))
            }
            ollamaModels.forEach {
                add(ModelChoice(Source.OLLAMA, it.name, it.name, "Ollama · PC", vision = it.vision))
            }
        }

    fun isActive(choice: ModelChoice) = isActive(choice.source, choice.id)
    fun isActive(source: Source, id: String) = activeKey == "$source:$id"

    fun select(choice: ModelChoice) {
        if (isActive(choice) && engineStatus is EngineStatus.Ready) return
        settings.source = choice.source
        if (choice.source == Source.INTERNAL) settings.internalModelId = choice.id else settings.ollamaModel = choice.id
        loadActiveModel()
    }

    fun loadActiveModel() {
        if (generating) stop()
        loadJob?.cancel()
        val old = engine
        engine = null
        engineSyncKey = null
        val source = settings.source
        activeKey = "$source:" + (if (source == Source.INTERNAL) settings.internalModelId else settings.ollamaModel)
        val (label, target) = when (source) {
            Source.INTERNAL -> {
                val m = (ModelCatalog.all + ModelCatalog.customModels(context)).firstOrNull { it.id == settings.internalModelId }
                if (m == null || !m.isDownloaded(context)) {
                    engineStatus = EngineStatus.None
                    activeLabel = ""
                    viewModelScope.launch(Dispatchers.IO) { old?.close() }
                    return
                }
                "${m.title} · Gerät" to m.file(context).absolutePath
            }
            Source.OLLAMA -> {
                val name = settings.ollamaModel
                if (name.isBlank()) {
                    engineStatus = EngineStatus.None
                    activeLabel = ""
                    viewModelScope.launch(Dispatchers.IO) { old?.close() }
                    return
                }
                "$name · Ollama" to name
            }
        }
        activeLabel = label
        engineStatus = EngineStatus.Loading(label)
        loadJob = viewModelScope.launch {
            try {
                val (eng, acc) = withContext(Dispatchers.IO) {
                    old?.close()
                    val e: ChatEngine = when (source) {
                        Source.INTERNAL -> LiteRtEngine(context)
                        Source.OLLAMA -> OllamaEngine { settings.ollamaHost to settings.ollamaPort }
                    }
                    e to e.load(target)
                }
                engine = eng
                engineStatus = EngineStatus.Ready(label, acc, eng.supportsImages)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                engineStatus = EngineStatus.Failed(label, e.message ?: "Modell konnte nicht geladen werden")
            }
        }
    }

    // =====================================================================
    // Modelle herunterladen / importieren
    // =====================================================================

    fun download(model: ModelInfo) {
        if (downloadJobs.containsKey(model.id)) return
        downloads[model.id] = DownloadState.Running(0, model.sizeBytes)
        downloadJobs[model.id] = viewModelScope.launch {
            try {
                ModelDownloader.download(context, model) { done, total ->
                    viewModelScope.launch(Dispatchers.Main) {
                        if (downloads[model.id] is DownloadState.Running) downloads[model.id] = DownloadState.Running(done, total)
                    }
                }
                downloads[model.id] = DownloadState.Done
                // Erstes Modell? Gleich benutzen
                if (engineStatus is EngineStatus.None || engineStatus is EngineStatus.Failed) {
                    select(ModelChoice(Source.INTERNAL, model.id, model.title, "", null))
                }
            } catch (e: CancellationException) {
                downloads[model.id] = DownloadState.Missing
                throw e
            } catch (e: Exception) {
                downloads[model.id] = DownloadState.Failed(e.message ?: "Download fehlgeschlagen")
            } finally {
                downloadJobs.remove(model.id)
            }
        }
    }

    fun cancelDownload(model: ModelInfo) {
        downloadJobs.remove(model.id)?.cancel()
        downloads[model.id] = DownloadState.Missing
    }

    fun deleteModel(model: ModelInfo) {
        if (settings.source == Source.INTERNAL && settings.internalModelId == model.id) {
            viewModelScope.launch(Dispatchers.IO) { engine?.close() }
            engine = null
            engineStatus = EngineStatus.None
        }
        model.file(context).delete()
        refreshStorage()
    }

    /** Eigene .litertlm-Datei in den Modell-Ordner kopieren (LiteRT-LM braucht einen echten Dateipfad). */
    fun importModel(uri: Uri) {
        if (importProgress != null) return
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "modell.litertlm"
        if (!name.endsWith(".litertlm")) {
            toast = "Das ist keine .litertlm-Datei."
            return
        }
        val target = File(modelsDir(context), name)
        if (target.exists()) {
            toast = "Diese Datei liegt schon im Modell-Ordner."
            return
        }
        val total = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
        importProgress = "Kopiere $name …"
        viewModelScope.launch {
            val tmp = File(target.parentFile, "$name.part")
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(1 shl 20)
                            var copied = 0L
                            var lastReport = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                copied += n
                                if (copied - lastReport > (64L shl 20)) {
                                    lastReport = copied
                                    val pct = if (total > 0) " ${copied * 100 / total} %" else ""
                                    withContext(Dispatchers.Main) { importProgress = "Kopiere $name …$pct" }
                                }
                            }
                        }
                    }
                    if (!tmp.renameTo(target)) error("Datei konnte nicht gespeichert werden")
                }
                toast = "$name importiert"
            } catch (e: Exception) {
                tmp.delete()
                toast = "Import fehlgeschlagen: ${e.message}"
            } finally {
                importProgress = null
                refreshStorage()
            }
        }
    }

    // =====================================================================
    // Ollama
    // =====================================================================

    fun saveOllama(host: String, port: Int) {
        settings.ollamaHost = host.trim()
        settings.ollamaPort = port
    }

    fun testOllama(silent: Boolean = false) {
        if (ollamaBusy) return
        ollamaBusy = true
        if (!silent) ollamaStatus = "Verbinde …"
        viewModelScope.launch {
            try {
                val client = OllamaClient(settings.ollamaHost, settings.ollamaPort)
                val version = client.version()
                val models = client.models()
                ollamaModels = models
                ollamaStatus = "Verbunden · Ollama $version · ${models.size} Modelle"
            } catch (e: Throwable) {
                ollamaModels = emptyList()
                ollamaStatus = "Nicht erreichbar: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                ollamaBusy = false
            }
        }
    }

    // =====================================================================
    // Vorlesen
    // =====================================================================

    fun toggleSpeak(message: ChatMessage) {
        if (speakingId == message.id) {
            speaker.stop()
            speakingId = null
        } else {
            speaker.speak(message.id, message.text, settings.speechPitch, settings.speechRate)
        }
    }

    override fun onCleared() {
        speaker.shutdown()
        engine?.close()
        super.onCleared()
    }
}
