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
import de.davscha.denkwerk.data.DocAttachment
import de.davscha.denkwerk.data.SavedPrompt
import de.davscha.denkwerk.data.MemoryFact
import de.davscha.denkwerk.data.ImageRecipe
import de.davscha.denkwerk.engine.ComfyClient
import de.davscha.denkwerk.data.ToolEvent
import de.davscha.denkwerk.engine.DownloadRequest
import de.davscha.denkwerk.engine.ToolStreamEvent
import de.davscha.denkwerk.engine.Toolbox
import de.davscha.denkwerk.engine.PseudoToolCalls
import kotlinx.coroutines.CompletableDeferred
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

enum class Screen { Chat, Settings, Presets, Prompts }

/** Zustand des Sprachmodells, das gerade benutzt wird. */
sealed interface EngineStatus {
    data object None : EngineStatus
    data class Loading(val label: String) : EngineStatus
    data class Ready(val label: String, val accelerator: Accelerator, val vision: Boolean, val tools: Boolean = false) : EngineStatus
    data class Failed(val label: String, val message: String) : EngineStatus
}

sealed interface DownloadState {
    data object Missing : DownloadState
    data class Running(val done: Long, val total: Long) : DownloadState
    data object Done : DownloadState
    data class Failed(val message: String) : DownloadState
}

/** Ein Eintrag in der Modell-Auswahl oben im Chat. */
data class ModelChoice(val source: Source, val id: String, val title: String, val subtitle: String, val vision: Boolean?, val tools: Boolean? = null)

private val KNOWN_TOOLS = setOf(
    "bild_malen",
    "uhrzeit", "rechnen", "web_suche", "bilder_suchen", "videos_suchen", "video_verstehen", "bild_rueckwaertssuche", "anime_bild_quelle", "seite_lesen", "bilder_auf_seite", "webseite_durchsuchen", "auf_seite_suchen",
    "herunterladen", "merken", "vergessen",
)

/** Zusatz zur Anweisung, wenn Werkzeuge aktiv sind */
private const val TOOL_HINT =
    "Möchte der Nutzer ein Bild gemalt/erstellt/generiert haben und bild_malen ist verfügbar, beschreibe das Bild auf Englisch in klaren Sätzen (Motiv, Aussehen, Kleidung, Pose, Ort, Licht, Stil, Kamera) und ruf bild_malen auf. " +
        "Soll das zuletzt gemalte Bild nur geändert werden („das gleiche Bild, aber …“), übernimm die alte Beschreibung, bau die Änderung ein und setze gleiches_bild=true. " +
            "Fragt der Nutzer, woher ein Bild stammt, wer es gemacht hat oder nach ähnlichen/größeren Fassungen, nutze bild_rueckwaertssuche " +
        "(bei Anime/Zeichnungen zusätzlich anime_bild_quelle, falls vorhanden). " +
        "Bei Videos wähle die passende Quelle: Dokus/Reportagen/TV-Sendungen → quelle=mediathek, alte Filme/Archivmaterial → archiv. " +
        "Für Videos nutze videos_suchen (erscheinen automatisch als Kärtchen); soll ein YouTube-Video zusammengefasst oder " +
        "erklärt werden, nutze video_verstehen mit dessen Adresse. " +
        "Du hast Werkzeuge: uhrzeit, rechnen, web_suche, bilder_suchen, videos_suchen, video_verstehen, seite_lesen, bilder_auf_seite, webseite_durchsuchen, auf_seite_suchen und herunterladen. " +
        "Soll auf einer bestimmten Webseite nach etwas Bestimmtem gesucht werden, nutze zuerst auf_seite_suchen (benutzt das Suchfeld der Seite); " +
        "findet das nichts, nimm webseite_durchsuchen. " +
        "Nennt der Nutzer eine Webseite und sucht Infos oder Bilder darauf, nutze webseite_durchsuchen – es durchsucht auch die Unterseiten, " +
        "nicht nur die Startseite (bei Bildern bilder=true setzen). seite_lesen nur für genau eine einzelne Seite. " +
        "Wenn der Nutzer Bilder sehen möchte, benutze bilder_suchen – die Bilder erscheinen automatisch als Galerie im Chat, " +
        "also keine Linkliste dazu schreiben. " +
        "Mit merken speicherst du dauerhafte Fakten über den Nutzer (wenn er \"merk dir\" sagt oder Wichtiges über sich erzählt), mit vergessen löschst du sie. " +
        "WICHTIG bei Fakten und Nachrichten: Stütze dich nur auf das, was in den Werkzeug-Ergebnissen steht, und nenne die Quellen. " +
        "Kennzeichne Belege im Text nur mit [Nummer]; schreibe am Ende KEINE eigene Quellenliste – die Quellen erscheinen automatisch als Kärtchen. " +
        "Sag immer ehrlich, woher eine Information stammt: aus einem Werkzeug-Ergebnis, aus deinem eigenen Wissen oder aus deiner Bildbeschreibung. " +
        "Ist ein Werkzeug fehlgeschlagen, behaupte nie, es habe etwas ergeben. " +
        "Nenne Namen von Personen auf Fotos nur, wenn sie aus Quellen hervorgehen (Bildunterschrift, Artikel, Fundstelle der Rückwärtssuche) – " +
        "rate sie nicht allein aus dem Gesicht; sag sonst, dass du die Person nicht sicher bestimmen kannst. " +
        "Nenne Details wie Torschützen, Minuten, Aufstellungen, Trainer, Zahlen oder Zitate NUR, wenn sie wörtlich in den Ergebnissen stehen – " +
        "fehlen sie, schreib z. B. „Die Torschützen stehen nicht in den gelesenen Quellen“. " +
        "Erfinde keine Namen, Zitate, Zahlen oder Zusammenhänge; steht etwas nicht in den Quellen, sag das offen. " +
        "Bei politischen oder umstrittenen Themen bleib sachlich und neutral: gib wieder, was berichtet wird und von wem, " +
        "übernimm keine Wertungen ungeprüft (auch nicht die des Nutzers) und nenne verschiedene Sichtweisen, wenn die Quellen sie hergeben. " +
        "Antworte immer in der Sprache der letzten Nachricht des Nutzers (fragt er auf Englisch, antworte auf Englisch). " +
        "Prüfe vor dem Absenden, ob sich deine Aussagen widersprechen (z. B. Ergebnis, Halbzeitstand, Wettbewerb). " +
        "Rufe Werkzeuge immer über die Werkzeug-Schnittstelle auf und schreibe niemals JSON wie {\"action\": …} in deine Antwort. " +
        "Nutze web_suche für aktuelle Informationen oder alles nach deinem Wissensstand, seite_lesen wenn eine Adresse genannt wird " +
        "oder ein Treffer genauer gelesen werden soll, rechnen für exakte Rechnungen und uhrzeit für Datum und Uhrzeit. " +
        "herunterladen nur, wenn der Nutzer ausdrücklich Dateien oder Bilder speichern möchte. " +
        "Nenne bei Informationen aus dem Web die Quelle (Adresse) am Ende. Erfinde keine Adressen."

/** Anweisung für den Assistenten-Entwurf mit KI-Hilfe */
private const val PRESET_DESIGNER_PROMPT =
    "Du entwirfst KI-Assistenten für eine Chat-App. Der Nutzer gibt Stichpunkte. " +
        "Antworte AUSSCHLIESSLICH mit einem JSON-Objekt, ohne Erklärung, mit diesen Feldern: " +
        "\"name\" (kurzer deutscher Name, höchstens 3 Wörter), " +
        "\"emoji\" (genau ein passendes Emoji), " +
        "\"description\" (ein kurzer Satz, höchstens 60 Zeichen), " +
        "\"systemPrompt\" (ausführliche Anweisung an die KI auf Deutsch, 4 bis 8 Sätze, beginnt mit \"Du bist\", " +
        "beschreibt Rolle, Tonfall, Antwortformat und was zu vermeiden ist), " +
        "\"temperature\" (Zahl zwischen 0.1 und 1.2: sachlich niedrig, kreativ hoch), " +
        "\"starters\" (Liste mit 3 kurzen Beispiel-Fragen auf Deutsch, die man diesem Assistenten stellen könnte)."

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val context get() = getApplication<Application>()
    private val store = ChatStore(app)
    val settings = Settings(app)

    // ---------- Seiten ----------
    var screen by mutableStateOf(Screen.Chat)
        private set

    fun openSettings() { screen = Screen.Settings }
    fun openPresets() { editingPreset = null; screen = Screen.Presets }
    fun openPrompts() { screen = Screen.Prompts }

    // ---------- Prompt-Bibliothek ----------
    val prompts = mutableStateListOf<SavedPrompt>()
    /** Prompt im Bearbeiten-Dialog (neu oder vorhanden) */
    var editingPrompt by mutableStateOf<SavedPrompt?>(null)

    fun savePrompt(p: SavedPrompt) {
        val clean = p.copy(title = SavedPrompt.slug(p.title.ifBlank { p.text }))
        val i = prompts.indexOfFirst { it.id == clean.id }
        if (i >= 0) prompts[i] = clean else prompts.add(clean)
        editingPrompt = null
        val copy = prompts.toList()
        viewModelScope.launch(Dispatchers.IO) { runCatching { store.savePrompts(copy) } }
    }

    fun deletePrompt(id: String) {
        prompts.removeAll { it.id == id }
        editingPrompt = null
        val copy = prompts.toList()
        viewModelScope.launch(Dispatchers.IO) { runCatching { store.savePrompts(copy) } }
    }

    /** Eigene Nachricht als Prompt speichern (öffnet die Bibliothek mit Dialog) */
    fun promptFromMessage(text: String) {
        editingPrompt = SavedPrompt(title = SavedPrompt.slug(text), text = text)
        screen = Screen.Prompts
    }
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

    // ---------- Werkzeuge ----------
    /** Werkzeug-Kärtchen der Antwort, die gerade entsteht */
    var streamingTools by mutableStateOf<List<ToolEvent>>(emptyList())
        private set

    /** Bilder aus der Bildersuche der Antwort, die gerade entsteht */
    var streamingWebImages by mutableStateOf<List<de.davscha.denkwerk.data.WebImage>>(emptyList())
        private set
    var streamingWebVideos by mutableStateOf<List<de.davscha.denkwerk.data.WebVideo>>(emptyList())
        private set
    var streamingSources by mutableStateOf<List<de.davscha.denkwerk.data.SourceLink>>(emptyList())
        private set
    /** Gedanken des Denk-Modells während der Antwort */
    var streamingThinking by mutableStateOf("")
        private set
    var thinkingState by mutableStateOf(true)
        private set

    fun setThinking(on: Boolean) {
        settings.thinking = on
        thinkingState = on
    }

    /** Bild aus der Slideshow speichern (du hast selbst getippt – keine Rückfrage) */
    fun saveWebImage(url: String) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { toolbox.saveUrl(url) } }
            toast = r.fold({ "Gespeichert: Denkwerk/Downloads/${it.name}" }, { "Speichern fehlgeschlagen: ${it.message}" })
        }
    }

    // ---------- Websuche vor der Antwort ----------
    var webModeState by mutableStateOf(1)
        private set

    fun setWebMode(mode: Int) {
        settings.webMode = mode
        webModeState = mode
    }

    /** 🌐-Knopf im Eingabefeld: nächste Nachrichten in diesem Chat immer mit Websuche */
    var forceWeb by mutableStateOf(false)

    /** Sieht die Nachricht wie eine Wissensfrage aus? (Danke, Smalltalk usw. nicht) */
    private fun looksLikeQuestion(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.length < 8) return false
        // Uhrzeit/Datum weiß die App selbst – eine Websuche liefert nur veraltete Uhrzeiten von Webseiten
        if (isTimeQuestion(t)) return false
        if (Regex("^(danke|ok|okay|super|cool|gut|na gut|alles klar|prima|ja|nein|hallo|hi|tschüss)\\b").containsMatchIn(t) && t.length < 30) return false
        // Aufgaben, die keine Fakten brauchen
        if (Regex("^(schreib|formulier|übersetz|korrigier|verbesser|mach (mir )?(einen|eine) (prompt|gedicht|text))").containsMatchIn(t)) return false
        return t.contains('?') ||
            Regex("^(wer|was|wann|wo|wie|warum|wieso|weshalb|welche[rsmn]?|gibt es|ist|sind|kann|hat|haben|stimmt|erklär|kennst du|weißt du|such" +
                "|who|what|when|where|why|how|which|is|are|was|were|did|does|do|can|has|have|tell me|search|find)\\b").containsMatchIn(t)
    }

    private fun isTimeQuestion(t: String) = Regex(
        "^(wie spät|wieviel uhr|wie viel uhr|welche uhrzeit|welcher tag|welches datum|was für ein tag|den wievielten|der wievielte|" +
            "what time|what day|what's the date|what is the date)|^(uhrzeit|datum)\\??$",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(t.trim())

    /** Will der Nutzer ein Bild gemalt haben? */
    private fun wantsPaint(text: String): Boolean {
        val t = text.trim().lowercase()
        if (Regex("\\b(mal|male|malst|zeichne|zeichnest|generier\\w*|erstell\\w*|erzeug\\w*|paint|draw|generate|create)\\b.*\\b(bild\\w*|foto|zeichnung|illustration|gemälde|image|picture|drawing)\\b")
                .containsMatchIn(t)) return true
        if (Regex("^(bitte )?(male|zeichne|paint|draw)\\b").containsMatchIn(t)) return true
        return Regex("\\b(noch|weiteres|neues|anderes|another|new) (ein |eins |one )?(bild|image|picture)").containsMatchIn(t)
    }

    /** Will der Nutzer außer dem Bild noch etwas (Tabelle, Erklärung …)? Dann nach dem Malen nicht sofort aufhören */
    private fun asksMoreThanImage(text: String) =
        Regex("\\b(und|dazu|außerdem|sowie|plus|and|also)\\b.*\\b(tabelle|liste|erklär\\w*|schreib\\w*|text|info\\w*|fakten|geschichte|table|list|explain|write|story)",
            RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** Beschreibung des zuletzt gemalten Bildes (aus der 🎨-Karte) */
    private fun lastPaintDescription(): String? =
        currentChat?.messages.orEmpty().asReversed().firstNotNullOfOrNull { m ->
            m.toolEvents.lastOrNull { it.name == "bild_malen" && it.ok && it.detail.isNotBlank() }?.detail
        }

    /** Suchanfrage aus der Nachricht machen: Höflichkeitsfloskeln und „such im Internet“ weg */
    private fun searchQueryFrom(text: String): String =
        text.replace(Regex("(?i)\\b(bitte|kannst du( mir)?|könntest du|sag mir|erzähl mir|such(e)?( mal)?( mir)?( im (internet|web|netz))?( nach)?|schau( mal)?( im (internet|web|netz))?( nach)?)\\b"), " ")
            .replace(Regex("[?!.]+$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(150)

    private fun shouldPreSearch(m: ChatMessage): Boolean {
        if (m.text.isBlank() || (m.documents.isNotEmpty() && m.text.length < 40)) return false
        return forceWeb || webModeState == 2 || (webModeState == 1 && looksLikeQuestion(m.text))
    }

    /** Wartende Rückfrage „Dateien speichern?“ */
    class DownloadConfirm(val request: DownloadRequest, val answer: CompletableDeferred<Boolean>)
    var downloadConfirm by mutableStateOf<DownloadConfirm?>(null)
        private set

    fun answerDownload(ok: Boolean) {
        downloadConfirm?.answer?.complete(ok)
        downloadConfirm = null
    }

    /** true = das aktive Modell darf Werkzeuge benutzen */
    val toolsActive: Boolean
        get() = settings.toolsEnabled && (engineStatus as? EngineStatus.Ready)?.tools == true

    /** Für den Schalter in den Einstellungen (State, damit die Oberfläche mitgeht) */
    var toolsEnabledState by mutableStateOf(true)
        private set

    fun setToolsEnabled(on: Boolean) {
        settings.toolsEnabled = on
        toolsEnabledState = on
        engineSyncKey = null
    }

    private val toolbox = Toolbox(
        downloadDir = { java.io.File(store.dataDir, "Downloads") },
        searxngUrl = { settings.searxngUrl },
        safeSearch = { settings.safeSearch },
        pageBudget = { docBudget() },
        confirmDownload = { req ->
            val d = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) { downloadConfirm = DownloadConfirm(req, d) }
            d.await()
        },
        remember = { fact -> withContext(Dispatchers.Main) { if (memoryEnabledState) addMemory(fact) else null } },
        forget = { query -> withContext(Dispatchers.Main) { forgetMemory(query) } },
        chatImages = { imagesInCurrentChat() },
        allowedDirs = { listOf(store.dataDir, context.filesDir, context.cacheDir) },
        sauceNaoKey = { settings.sauceNaoKey },
        videoFilter = { Triple(settings.videoShowYoutube, settings.videoShowSocial, settings.videoBlocked) },
        paint = { prompt, same -> paintWithSeed(prompt, same) },
        paintReady = { paintAvailable },
        recipeInfo = { recipeDescription() },
    )

    // ---------- Bilder malen (ComfyUI) ----------
    var recipe by mutableStateOf(ImageRecipe())
        private set
    var comfyStatus by mutableStateOf("")
        private set
    var comfyBusy by mutableStateOf(false)
        private set
    /** Seitenverhältnisse des ResolutionSelector */
    var comfyAspects by mutableStateOf(ImageRecipe.DEFAULT_ASPECTS)
        private set

    /** ComfyUI eingetragen? Dann wird bild_malen angeboten */
    val paintAvailable: Boolean get() = settings.comfyHost.isNotBlank()
    /** Fortschritt des laufenden Bildes in Sekunden (null = kein Bild in Arbeit) */
    var paintSeconds by mutableStateOf<Int?>(null)
        private set

    fun updateRecipe(r: ImageRecipe) {
        recipe = r
        settings.imageRecipe = r
    }

    fun saveComfy(host: String, port: Int) {
        settings.comfyHost = host
        settings.comfyPort = port
    }

    /** Verbinden und prüfen, ob die Anima-Dateien auf dem PC liegen */
    fun refreshComfy() {
        if (comfyBusy) return
        comfyBusy = true
        comfyStatus = "Verbinde …"
        viewModelScope.launch {
            try {
                val c = ComfyClient(settings.comfyHost, settings.comfyPort)
                val info = withContext(Dispatchers.IO) { c.test() }
                val missing = withContext(Dispatchers.IO) { runCatching { c.missingFiles(recipe) }.getOrDefault(emptyList()) }
                withContext(Dispatchers.IO) { c.aspects() }.takeIf { it.isNotEmpty() }?.let { comfyAspects = it }
                comfyStatus = if (missing.isEmpty()) "Verbunden · $info · MOSS-Dateien gefunden"
                else "Verbunden · $info – es fehlt: " + missing.joinToString()
            } catch (e: Throwable) {
                comfyStatus = "Nicht erreichbar: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                comfyBusy = false
            }
        }
    }

    /** Für die Werkzeug-Beschreibung */
    private fun recipeDescription(): String = "Modell: ${ComfyClient.shortName(recipe.unet)} (MOSS Image), Seitenverhältnis ${recipe.aspect}."

    /**
     * Malt ein Bild: Ollama-Modell vorher entladen (Grafikspeicher!), Prompt zusammensetzen
     * (Qualitäts-Tags + LoRA-Trigger + Tags), Workflow schicken, Bild speichern. Gibt den Dateipfad zurück.
     */
    /** Festgehaltener Seed (🔒 am Bild) – solange gesetzt, malen alle Bilder damit */
    var seedLock by mutableStateOf<Long?>(null)
        private set

    fun toggleSeedLock(seed: Long) {
        seedLock = if (seedLock == seed) null else seed
        toast = if (seedLock != null) "Seed $seed festgehalten – neue Bilder behalten den Aufbau" else "Seed wieder frei – jedes Bild wird neu gewürfelt"
    }

    /** Letzte Nutzernachricht der laufenden Antwort – erkennt „das gleiche Bild, aber …“ auch ohne Werkzeug-Parameter */
    private var lastUserText = ""

    private val sameImageWords = Regex(
        "(gleiche[nms]?|selbe[nms]?|dasselbe|vorige[nms]?|letzte[nms]?|diese[nms]?) (bild|motiv|szene)|dasselbe|änder|nochmal|noch ?mal|stattdessen|" +
            "\\b(same|previous|last|this) (image|picture|pic)|\\bchange\\b|instead",
        RegexOption.IGNORE_CASE,
    )

    /** Seed des zuletzt im Chat gemalten Bildes (steckt im Dateinamen …-s<seed>.png) */
    private fun lastPaintedSeed(): Long? =
        currentChat?.messages.orEmpty().asReversed().flatMap { it.imagePaths.reversed() }.firstNotNullOfOrNull { ComfyClient.seedOf(it) }

    /** Für das Werkzeug: Seed wählen (festgehalten › gleiches Bild › neu) und malen */
    private suspend fun paintWithSeed(tags: String, same: Boolean): Pair<String, String> {
        val reuse = if (same || sameImageWords.containsMatchIn(lastUserText)) lastPaintedSeed() else null
        val seed = seedLock ?: reuse
        val note = when {
            seedLock != null -> " · 🔒 Seed fest"
            reuse != null -> " · gleicher Seed"
            else -> ""
        }
        return paintImage(tags, seed) to note
    }

    suspend fun paintImage(tags: String, fixedSeed: Long? = null): String {
        val r = recipe
        withContext(Dispatchers.Main) { paintSeconds = 0 }
        try {
            // Grafikspeicher frei machen: Ollama-Modell entladen (lädt bei der nächsten Frage von selbst wieder)
            if (settings.source == Source.OLLAMA && settings.ollamaModel.isNotBlank()) {
                withContext(Dispatchers.IO) { runCatching { OllamaClient(settings.ollamaHost, settings.ollamaPort).unload(settings.ollamaModel) } }
            }
            // MOSS versteht Sätze, Wortgruppen und JSON – nur Code-Zäune entfernen, Zeilen bleiben
            val positive = tags.replace(Regex("```\\w*"), "").trim()
            val seed = fixedSeed ?: (Math.random() * 1e15).toLong()
            val client = ComfyClient(settings.comfyHost, settings.comfyPort)
            val bytes = withContext(Dispatchers.IO) {
                // Dateien können in Unterordnern liegen (z. B. moss\\RealisticFreedom.safetensors) – echten Namen suchen
                val names = mapOf(
                    "unet" to client.resolve("UNETLoader", "unet_name", r.unet),
                    "lora" to client.resolve("LoraLoaderModelOnly", "lora_name", r.lora),
                    "clip" to client.resolve("CLIPLoader", "clip_name", r.clip),
                    "vae" to client.resolve("VAELoader", "vae_name", r.vae),
                )
                val wf = ComfyClient.buildWorkflow(r, positive, seed, names)
                client.run(wf) { s ->
                    viewModelScope.launch(Dispatchers.Main) { paintSeconds = s }
                }
            }
            return withContext(Dispatchers.IO) {
                val dir = java.io.File(store.dataDir, "Bilder-KI").apply { mkdirs() }
                val f = java.io.File(dir, "bild-${System.currentTimeMillis()}-s$seed.png")
                f.writeBytes(bytes)
                f.absolutePath
            }
        } finally {
            withContext(Dispatchers.Main) { paintSeconds = null }
        }
    }

    /** „🎨 Malen“-Knopf an einem Prompt-Code-Block: malt direkt und hängt das Bild als neue Nachricht an */
    fun paintFromPrompt(prompt: String) {
        val chat = currentChat ?: return
        if (paintSeconds != null) { toast = "Es wird gerade schon ein Bild gemalt."; return }
        if (!paintAvailable) { toast = "Erst ComfyUI einrichten: Einstellungen → Bilder malen"; openSettings(); return }
        val short = prompt.replace(Regex("\\s+"), " ").take(60)
        val msg = ChatMessage(
            fromUser = false,
            text = "",
            modelLabel = "ComfyUI · " + ComfyClient.shortName(recipe.unet),
            toolEvents = listOf(ToolEvent("bild_malen", short, "läuft …", running = true)),
        )
        upsert(chat.copy(messages = chat.messages + msg))
        viewModelScope.launch {
            val start = System.currentTimeMillis()
            val result = runCatching { paintImage(prompt, seedLock) }
            val secs = (System.currentTimeMillis() - start) / 1000
            val c = chats.firstOrNull { it.id == chat.id } ?: return@launch
            val done = result.fold(
                { path -> msg.copy(toolEvents = listOf(ToolEvent("bild_malen", short, "fertig · $secs s")), imagePaths = listOf(path)) },
                { e -> msg.copy(toolEvents = listOf(ToolEvent("bild_malen", short, "Fehler: ${e.message}", ok = false))) },
            )
            upsert(c.copy(messages = c.messages.map { if (it.id == msg.id) done else it }))
        }
    }

    /** Alle Bilder im offenen Chat, neueste zuerst: angehängte, heruntergeladene und gefundene */
    private fun imagesInCurrentChat(): List<String> =
        currentChat?.messages.orEmpty().asReversed().flatMap { m -> m.imagePaths.reversed() + m.webImages.map { it.url } }

    /** Neuestes Bild im offenen Chat (für „In Google Lens öffnen“) */
    fun lastChatImage(): de.davscha.denkwerk.data.WebImage? =
        imagesInCurrentChat().firstOrNull()?.let { u -> de.davscha.denkwerk.data.WebImage(u, local = !u.startsWith("http")) }

    /** Rückwärtssuche aus der Slideshow starten (braucht ein Modell mit Werkzeugen) */
    fun reverseSearch(image: de.davscha.denkwerk.data.WebImage) {
        if (!toolsActive) {
            toast = "Dafür braucht es ein Ollama-Modell mit Tools – nimm sonst „Google Lens“."
            return
        }
        send("Mach eine Bild-Rückwärtssuche zu diesem Bild: Woher stammt es, wo kommt es noch vor, gibt es eine größere Fassung? Bild: ${image.url}")
    }

    // ---------- Gedächtnis (Memory) ----------
    val memory = mutableStateListOf<MemoryFact>()
    var memoryEnabledState by mutableStateOf(true)
        private set

    fun setMemoryEnabled(on: Boolean) {
        settings.memoryEnabled = on
        memoryEnabledState = on
        engineSyncKey = null
    }

    /** Fakt merken (doppelte werden übersprungen). Gibt den gespeicherten Text zurück. */
    fun addMemory(text: String): String {
        val clean = text.trim().trimEnd('.').replace(Regex("\\s+"), " ").take(300)
        if (memory.none { it.text.equals(clean, ignoreCase = true) }) {
            memory.add(MemoryFact(text = clean))
            persistMemory()
        }
        return clean
    }

    fun updateMemory(id: String, text: String) {
        val i = memory.indexOfFirst { it.id == id }
        if (i < 0) return
        if (text.isBlank()) memory.removeAt(i) else memory[i] = memory[i].copy(text = text.trim())
        persistMemory()
    }

    fun deleteMemory(id: String) {
        memory.removeAll { it.id == id }
        persistMemory()
    }

    /** Löscht alle Fakten, die das Stichwort enthalten (oder umgekehrt). */
    fun forgetMemory(query: String): Int {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return 0
        val hits = memory.filter { val t = it.text.lowercase(); t.contains(q) || q.contains(t) }
        memory.removeAll(hits)
        if (hits.isNotEmpty()) persistMemory()
        return hits.size
    }

    private fun persistMemory() {
        engineSyncKey = null // Anweisung ändert sich
        val copy = memory.toList()
        viewModelScope.launch(Dispatchers.IO) { runCatching { store.saveMemory(copy) } }
    }

    /** Block für die Anweisung an die KI */
    private fun memoryBlock(): String {
        if (!memoryEnabledState || memory.isEmpty()) return ""
        val lines = memory.joinToString("\n") { "- ${it.text}" }.take(2500)
        return "\n\nWas du über den Nutzer weißt (nutze es nur, wenn es zur Frage passt):\n$lines"
    }

    // ---------- Vorlesen ----------
    var speakingId by mutableStateOf<String?>(null)
        private set
    private val speaker = Speaker(app) { id -> viewModelScope.launch(Dispatchers.Main) { speakingId = id } }

    init {
        recipe = settings.imageRecipe
        thinkingState = settings.thinking
        toolsEnabledState = settings.toolsEnabled
        memoryEnabledState = settings.memoryEnabled
        webModeState = settings.webMode
        refreshStorage()
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { store.loadAll() }
            chats.clear()
            chats.addAll(loaded)
            val p = withContext(Dispatchers.IO) { store.loadPresets() }
            presets.clear()
            presets.addAll(p)
            val mem = withContext(Dispatchers.IO) { store.loadMemory() }
            memory.clear()
            memory.addAll(mem)
            val pr = withContext(Dispatchers.IO) { store.loadPrompts() }
            prompts.clear()
            prompts.addAll(pr)
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
        forceWeb = false
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

    // ---------- Assistent mit KI-Hilfe entwerfen ----------

    var presetAiBusy by mutableStateOf(false)
        private set
    /** Zählt hoch, wenn die KI einen Entwurf geliefert hat – der Editor übernimmt dann die Felder */
    var presetDraftVersion by mutableStateOf(0)
        private set

    /** Aus Stichpunkten macht das aktive Modell Name, Symbol, Beschreibung, Anweisung und Starter. */
    fun draftPresetWithAi(notes: String) {
        val eng = engine
        if (eng == null || engineStatus !is EngineStatus.Ready) {
            toast = "Dafür muss ein Modell geladen sein."
            return
        }
        if (generating || presetAiBusy || notes.isBlank()) return
        presetAiBusy = true
        engineSyncKey = null // das Modell führt gleich ein anderes Gespräch
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { eng.startConversation(PRESET_DESIGNER_PROMPT, emptyList(), 0.5) }
                val raw = StringBuilder()
                eng.send("Stichpunkte für den Assistenten:\n${notes.trim()}").collect { raw.append(it) }
                val o = extractJson(raw.toString())
                    ?: error("Die KI hat kein passendes Ergebnis geliefert – bitte nochmal versuchen.")
                val base = editingPreset ?: Preset()
                editingPreset = base.copy(
                    name = o.optString("name").trim().ifEmpty { base.name }.take(40),
                    emoji = o.optString("emoji").trim().ifEmpty { base.emoji }.take(4),
                    description = o.optString("description").trim().ifEmpty { base.description }.take(80),
                    systemPrompt = o.optString("systemPrompt").trim().ifEmpty { base.systemPrompt },
                    temperature = o.optDouble("temperature", base.temperature.toDouble()).toFloat().coerceIn(0f, 1.5f),
                    starters = o.optJSONArray("starters")?.let { a -> List(a.length()) { a.optString(it).trim() } }
                        ?.filter { it.isNotBlank() }?.take(4)?.ifEmpty { null } ?: base.starters,
                )
                presetDraftVersion++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast = e.message ?: "Entwurf fehlgeschlagen"
            } finally {
                presetAiBusy = false
                engineSyncKey = null
            }
        }
    }

    /** Sucht das JSON-Objekt in der Antwort (Modelle schreiben gern ```json … ``` drumherum). */
    private fun extractJson(text: String): org.json.JSONObject? {
        val clean = text.replace(Regex("<think>[\\s\\S]*?</think>"), "")
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { org.json.JSONObject(clean.substring(start, end + 1)) }.getOrNull()
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

    fun send(text: String, imagePaths: List<String> = emptyList(), documents: List<DocAttachment> = emptyList()) {
        if (generating) return
        if (text.isBlank() && imagePaths.isEmpty() && documents.isEmpty()) return
        // „Merk dir …“ ohne Werkzeuge (z. B. Gemma auf dem Gerät): direkt ins Gedächtnis
        if (memoryEnabledState && !toolsActive) {
            Regex("^(?:bitte\\s+)?merke?\\s+dir[,:]?\\s+(?:dass\\s+)?(.+)", RegexOption.IGNORE_CASE).find(text.trim())?.let { m ->
                val fact = addMemory(m.groupValues[1])
                toast = "🧠 Gemerkt: $fact"
            }
        }
        if (engineStatus !is EngineStatus.Ready) {
            toast = when (engineStatus) {
                is EngineStatus.Loading -> "Das Modell lädt noch – einen Moment."
                else -> "Kein Modell bereit. Wähle oben ein Modell oder öffne die Einstellungen."
            }
            return
        }
        val base = currentChat ?: Chat(
            title = if (text.isBlank() && documents.isNotEmpty()) documents.first().name else Chat.titleFrom(text),
            presetId = pendingPresetId,
        )
        val userMsg = ChatMessage(fromUser = true, text = text.trim(), imagePaths = imagePaths.take(maxImages.coerceAtLeast(1)), documents = documents)
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
        answerDownload(false)
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
        return "$chatId:$size:$activeLabel:${prompt.hashCode()}:$temp:$toolsActive:${memoryBlock().hashCode()}"
    }

    private fun generate(
        chatId: String,
        history: List<ChatMessage>,
        userMsg: ChatMessage,
        isFirst: Boolean,
        previousVariants: List<Variant> = emptyList(),
    ) {
        val eng = engine ?: return
        val (basePrompt, temperature) = promptFor(chats.firstOrNull { it.id == chatId })
        val useTools = toolsActive && eng is OllamaEngine
        val withMemory = basePrompt + memoryBlock()
        val today = java.text.SimpleDateFormat("EEEE, d. MMMM yyyy, 'es ist' HH:mm 'Uhr'", java.util.Locale.GERMANY).format(java.util.Date())
        val systemPrompt = if (useTools) {
            withMemory + "\n\n" + TOOL_HINT + " Heute ist $today. Schreibe in Suchanfragen statt „gestern“, „heute“ oder „letzte Woche“ " +
                "immer das konkrete Datum (z. B. „7. November 2026“)."
        } else withMemory
        val reply = ChatMessage(fromUser = false, text = "", modelLabel = activeLabel)
        lastUserText = userMsg.text
        streamingTools = emptyList()
        streamingWebImages = emptyList()
        streamingWebVideos = emptyList()
        streamingSources = emptyList()
        streamingThinking = ""
        (eng as? OllamaEngine)?.let { oe ->
            oe.thinkingEnabled = { thinkingState }
            oe.onThinking = { piece -> viewModelScope.launch(Dispatchers.Main) { streamingThinking = (streamingThinking + piece).takeLast(4000) } }
        }
        chats.firstOrNull { it.id == chatId }?.let { upsert(it.copy(messages = it.messages + reply), save = false) }
        generating = true
        stopRequested = false
        streamingId = reply.id
        streamingText = ""
        speaker.stop()

        generateJob = viewModelScope.launch {
            var finalText = ""
            val toolImages = mutableListOf<String>()
            val toolTexts = StringBuilder() // alles, was die Werkzeuge gelesen haben – für die Faktenprüfung
            var loopDetected = false
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
                var prompt = withContext(Dispatchers.IO) { fullPrompt(userMsg) }

                // Websuche VOR der Antwort – kleine Modelle überschätzen sonst ihr Wissen und suchen nicht von selbst
                if (shouldPreSearch(userMsg)) {
                    val query = searchQueryFrom(userMsg.text)
                    streamingTools = streamingTools + ToolEvent("web_suche", query, "läuft …", running = true)
                    // Auf dem Gerät (kleiner Kontext) nur einen Artikel lesen
                    val readTop = if (eng is OllamaEngine) 3 else 1
                    val out = withContext(Dispatchers.IO) {
                        runCatching { toolbox.run("web_suche", org.json.JSONObject().put("anfrage", query).put("treffer_lesen", readTop)) }
                    }
                    val res = out.getOrNull()
                    streamingTools = streamingTools.map {
                        if (it.running && it.name == "web_suche") it.copy(running = false, summary = res?.summary ?: "Fehler: ${out.exceptionOrNull()?.message}", ok = res?.ok ?: false) else it
                    }
                    if (res != null && res.ok) {
                        streamingSources = streamingSources + res.sources
                        toolTexts.append(res.forModel).append('\n')
                        val budget = if (eng is OllamaEngine) (settings.ollamaContext * 2).coerceAtLeast(6000) else 3500
                        prompt = "Aktuelle Websuche zu meiner Frage (stütze dich NUR darauf, nenne Belege mit [Nummer]; " +
                            "nenne keine Details wie Namen, Torschützen, Minuten oder Zahlen, die nicht in diesen Ergebnissen stehen, " +
                            "und sag ehrlich, wenn die Ergebnisse die Frage nicht oder nur teilweise beantworten):\n\n" +
                            res.forModel.take(budget) + "\n\n---\nMeine Frage: " + prompt
                    }
                }
                val start = System.currentTimeMillis()
                var firstChunkAt = 0L
                var chunks = 0
                fun onText(piece: String) {
                    if (chunks == 0) firstChunkAt = System.currentTimeMillis()
                    chunks++
                    streamingText += piece
                    // Modell hängt in einer Schleife („getgetget…“)? Dann abbrechen
                    if (!loopDetected && chunks % 8 == 0 && isLooping(streamingText)) {
                        loopDetected = true
                        stopRequested = true
                        eng.stop()
                    }
                }
                if (useTools) {
                    (eng as OllamaEngine).finishAfterPaint = !asksMoreThanImage(userMsg.text)
                    eng.sendWithTools(prompt, userMsg.imagePaths, toolbox.specs()) { name, args ->
                        toolbox.run(name, args)
                    }.collect { ev ->
                        when (ev) {
                            is ToolStreamEvent.Text -> onText(ev.text)
                            is ToolStreamEvent.Thinking -> streamingThinking = (streamingThinking + ev.text).takeLast(4000)
                            is ToolStreamEvent.RemoveText -> {
                                val at = streamingText.indexOf(ev.text)
                                if (at >= 0) streamingText = streamingText.removeRange(at, at + ev.text.length).trimEnd() + "\n\n"
                            }
                            is ToolStreamEvent.ToolStart -> {
                                // Text vor dem Werkzeug-Aufruf ist meist nur „Ich schaue mal nach …“ – Absatz dahinter
                                if (streamingText.isNotBlank() && !streamingText.endsWith("\n\n")) streamingText += "\n\n"
                                streamingTools = streamingTools + ToolEvent(ev.name, describeArgs(ev.name, ev.args), "läuft …", running = true)
                            }
                            is ToolStreamEvent.ToolDone -> {
                                val i = streamingTools.indexOfLast { it.name == ev.name && it.running }
                                val doneEv = ToolEvent(ev.name, streamingTools.getOrNull(i)?.detail.orEmpty(), ev.output.summary, ev.output.ok)
                                streamingTools = if (i >= 0) streamingTools.toMutableList().also { it[i] = doneEv } else streamingTools + doneEv
                                toolImages += ev.output.images
                                streamingWebImages = streamingWebImages + ev.output.webImages
                                streamingWebVideos = streamingWebVideos + ev.output.webVideos
                                streamingSources = streamingSources + ev.output.sources
                                if (ev.output.ok) toolTexts.append(ev.output.forModel).append('\n')
                            }
                        }
                    }
                    // Bild gewünscht, aber das Modell hat bild_malen nicht aufgerufen (schreibt z. B. „[Bild-Beschreibung …]“)?
                    // Dann malt die App selbst: Beschreibung kurz vom Modell holen, ComfyUI malen lassen
                    if (!stopRequested && paintAvailable && wantsPaint(userMsg.text) && streamingTools.none { it.name == "bild_malen" }) {
                        streamingText = streamingText.replace(Regex("\\[(Bild-?[Bb]eschreibung|Bild|Image)[^\\]]*\\]\\s*"), "").trim()
                        streamingTools = streamingTools + ToolEvent("bild_malen", "Beschreibung schreiben …", "läuft …", running = true)
                        val desc = withContext(Dispatchers.IO) {
                            runCatching {
                                OllamaClient(settings.ollamaHost, settings.ollamaPort).describe(
                                    settings.ollamaModel,
                                    "Write ONE English image description for an image generator (2–5 sentences: subject, appearance, clothing, pose, " +
                                        "setting, lighting, style, camera). Output only the description, nothing else.\n\n" +
                                        (lastPaintDescription()?.takeIf { sameImageWords.containsMatchIn(userMsg.text) }?.let { "Previous image description (keep it, change only what is asked): $it\n\n" } ?: "") +
                                        "Request: ${userMsg.text}",
                                )
                            }.getOrDefault("")
                        }.replace(Regex("```\\w*"), "").trim().trim('"')
                        if (desc.isBlank()) {
                            streamingTools = streamingTools.map { if (it.running) it.copy(running = false, summary = "Fehler: keine Beschreibung", ok = false) else it }
                        } else {
                            streamingTools = streamingTools.map { if (it.running) it.copy(detail = desc.take(400)) else it }
                            val t0 = System.currentTimeMillis()
                            val res = runCatching { paintWithSeed(desc, false) }
                            val secs0 = (System.currentTimeMillis() - t0) / 1000
                            streamingTools = streamingTools.map {
                                if (it.running) it.copy(running = false, summary = res.fold({ (_, note) -> "fertig · $secs0 s$note" }, { e -> "Fehler: ${e.message}" }), ok = res.isSuccess) else it
                            }
                            res.getOrNull()?.let { (path, _) -> toolImages += path }
                        }
                    }
                } else {
                    eng.send(prompt, userMsg.imagePaths).collect { piece -> onText(piece) }
                }
                val secs = (System.currentTimeMillis() - (if (firstChunkAt > 0) firstChunkAt else start)) / 1000f
                tps = if (secs > 0.2f) chunks / secs else 0f
                if (streamingTools.isNotEmpty()) tps = 0f // Werkzeug-Pausen verfälschen die Geschwindigkeit
                finalText = streamingText
                if (!stopRequested) engineSyncKey = syncKey(chatId, history.size + 2)
            } catch (e: CancellationException) {
                finalText = streamingText
            } catch (e: Throwable) {
                finalText = streamingText
                error = (e.message ?: e.javaClass.simpleName).let { msg ->
                    if (msg.contains("repeat limit", ignoreCase = true))
                        "Das Modell hing in einer Wiederholungsschleife fest. Tipp: „Neu erzeugen“ oder ein anderes Modell probieren."
                    else msg
                }
            } finally {
                val text = when {
                    error != null && finalText.isBlank() -> "Fehler: $error"
                    error != null -> "$finalText\n\n_(abgebrochen: ${error})_"
                    loopDetected -> cutLoop(finalText).trim() + "\n\n_(Wiederholungsschleife erkannt – Antwort gestoppt. Tipp: neu erzeugen oder in den Einstellungen mehr Kontext für Ollama wählen.)_"
                    finalText.isBlank() && stopRequested -> "_(gestoppt)_"
                    else -> finalText.trim()
                }
                var done = reply.copy(
                    text = if (streamingSources.isNotEmpty()) stripSourceList(text) else text,
                    tokensPerSec = tps,
                    isError = error != null && finalText.isBlank() && streamingTools.isEmpty(),
                    toolEvents = streamingTools.map { if (it.running) it.copy(running = false, summary = "abgebrochen", ok = false) else it },
                    imagePaths = toolImages.toList(),
                    webImages = streamingWebImages.distinctBy { it.url },
                    webVideos = streamingWebVideos.distinctBy { it.url },
                    sources = streamingSources.distinctBy { it.url },
                    // Faktenprüfung: Namen und Zahlen, die in keiner gelesenen Quelle stehen
                    unverified = if (error == null && toolTexts.isNotEmpty())
                        runCatching { de.davscha.denkwerk.engine.FactCheck.unverified(text, toolTexts.toString(), userMsg.text) }.getOrDefault(emptyList())
                    else emptyList(),
                )
                if (previousVariants.isNotEmpty()) {
                    val all = previousVariants + done.asVariant()
                    done = done.copy(variants = all, variantIndex = all.lastIndex)
                }
                chats.firstOrNull { it.id == chatId }?.let { c ->
                    upsert(c.copy(messages = c.messages.map { if (it.id == reply.id) done else it }))
                }
                streamingId = null
                streamingText = ""
                streamingTools = emptyList()
                streamingWebImages = emptyList()
                streamingWebVideos = emptyList()
                streamingSources = emptyList()
                streamingThinking = ""
                answerDownload(false)
                generating = false
                generateJob = null
            }
            if (isFirst && error == null && !stopRequested) makeTitle(chatId, userMsg.text)
        }
    }

    /**
     * Erkennt Endlosschleifen: Die letzten 400 Zeichen bestehen nur aus einem kurzen Muster
     * (bis 50 Zeichen), das sich immer wiederholt.
     */
    private fun isLooping(text: String): Boolean {
        val window = 400
        if (text.length < window + 50) return false
        val tail = text.substring(text.length - window - 50)
        for (period in 1..50) {
            var same = true
            for (i in tail.length - window until tail.length) {
                if (tail[i] != tail[i - period]) { same = false; break }
            }
            if (same) return true
        }
        return false
    }

    /** Schneidet den sich wiederholenden Schwanz ab (bis auf ein paar Zeichen). */
    private fun cutLoop(text: String): String {
        for (period in 1..50) {
            var start = text.length - period
            while (start - period >= 0 && text.regionMatches(start - period, text, start, period)) start -= period
            if (text.length - start >= 400) return text.substring(0, minOf(text.length, start + period)) + " …"
        }
        return text
    }

    /**
     * Entfernt eine „Quellen:“-Liste am Ende der Antwort (die Quellen stehen schon als Kärtchen darunter).
     * Nur wenn nach der Überschrift wirklich nur noch Listenpunkte / [1]-Verweise kommen.
     */
    private fun stripSourceList(text: String): String {
        val lines = text.trimEnd().lines()
        val head = lines.indexOfLast { l ->
            Regex("^\\s*(#{1,6}\\s*)?[*_]{0,2}(Quellen|Quelle|Quellenangaben|Sources)[*_]{0,2}\\s*:?[*_]{0,2}\\s*$", RegexOption.IGNORE_CASE).matches(l)
        }
        // Auch Überschriften wie „Quellen ([1], [2])“ oder „**Quellen & Hinweise**“
        val head2 = if (head >= 0) head else lines.indexOfLast { l ->
            Regex("^\\s*(#{1,6}\\s*)?[*_]{0,2}(Quellen|Quellenangaben|Sources)\\b.{0,40}$", RegexOption.IGNORE_CASE).matches(l)
        }
        if (head2 < 0) return text
        val rest = lines.drop(head2 + 1).filter { it.isNotBlank() }
        val onlyList = rest.isNotEmpty() && rest.all { Regex("^\\s*([-*•]|\\d+[.)]|\\[\\d+])").containsMatchIn(it) }
        // … oder ein kurzer Absatz, der nur sagt, woher die Infos stammen
        val onlySourceTalk = rest.isNotEmpty() && rest.size <= 3 && rest.joinToString(" ").length < 500 &&
            Regex("quelle|basier|stamm|artikel|bericht|\\[\\d+]", RegexOption.IGNORE_CASE).containsMatchIn(rest.joinToString(" "))
        return if (onlyList || onlySourceTalk || rest.isEmpty()) lines.take(head2).joinToString("\n").trimEnd() else text
    }

    /** Kurze Beschreibung der Werkzeug-Argumente fürs Kärtchen */
    private fun describeArgs(name: String, args: org.json.JSONObject): String = when (name) {
        "web_suche", "videos_suchen" -> args.optString("anfrage")
        "video_verstehen" -> args.optString("url").removePrefix("https://").removePrefix("www.")
        "bild_malen" -> args.optString("prompt").take(400)

        "bild_rueckwaertssuche", "anime_bild_quelle" -> args.optString("bild").let { b ->
            when {
                b.isBlank() -> "letztes Bild im Chat"
                b.startsWith("http") -> b.removePrefix("https://").removePrefix("http://").substringBefore('/')
                else -> java.io.File(b).name
            }
        }
        "auf_seite_suchen" -> args.optString("url").removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/') +
            " · „" + args.optString("suche") + "“"
        "webseite_durchsuchen" -> args.optString("url").removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/') +
            args.optString("suche").takeIf { it.isNotBlank() }?.let { " · „$it“" }.orEmpty()
        "seite_lesen", "bilder_auf_seite" -> args.optString("url")
        "rechnen" -> args.optString("ausdruck")
        "herunterladen" -> args.optJSONArray("urls")?.let { "${it.length()} Datei(en)" } ?: args.optString("url")
        else -> ""
    }

    /** Wie viele Zeichen eines Dokuments mitgeschickt werden (passend zum Kontext des Modells). */
    private fun docBudget(): Int =
        if (settings.source == Source.OLLAMA) ((settings.ollamaContext * 2.5).toInt() - 3000).coerceAtLeast(4000) else 6_000

    /** Deine Nachricht samt angehängter Dokumente, so wie sie ans Modell geht. */
    private fun fullPrompt(m: ChatMessage): String {
        val text = m.text.ifBlank {
            when {
                m.documents.isNotEmpty() -> "Fasse das Dokument kurz zusammen."
                m.imagePaths.size > 1 -> "Beschreibe diese Bilder."
                else -> "Beschreibe dieses Bild."
            }
        }
        if (m.documents.isEmpty()) return text
        val each = docBudget() / m.documents.size
        return buildString {
            m.documents.forEach { d ->
                val content = runCatching { java.io.File(d.textPath).readText() }.getOrDefault("(Datei nicht mehr vorhanden)")
                append("[Dokument: ${d.name}]\n")
                if (content.length > each) {
                    append(content.take(each))
                    append("\n[… gekürzt: erste $each von ${content.length} Zeichen]\n\n")
                } else {
                    append(content)
                    append("\n\n")
                }
            }
            append(text)
        }
    }

    suspend fun importDocument(uri: Uri): DocAttachment? {
        val r = withContext(Dispatchers.IO) { runCatching { store.importDocument(uri) } }
        r.exceptionOrNull()?.let { toast = it.message ?: "Dokument konnte nicht gelesen werden" }
        return r.getOrNull()
    }

    /** Lange Chats kürzen, damit sie ins Kontextfenster passen (neueste Nachrichten bleiben). */
    private fun trimHistory(history: List<ChatMessage>): List<Pair<Boolean, String>> {
        val budget = if (settings.source == Source.OLLAMA) (settings.ollamaContext * 2.5).toInt() else 9_000
        val usable = history.filter { !it.isError && (it.text.isNotBlank() || it.documents.isNotEmpty()) }
        val kept = ArrayDeque<ChatMessage>()
        var used = 0
        for (m in usable.asReversed()) {
            val t = if (m.fromUser) {
                val p = fullPrompt(m)
                if (m.imagePaths.isNotEmpty()) "[Bild] $p" else p
            } else {
                // Gemalte Bilder: dem Modell zeigen, DASS bild_malen benutzt wurde – sonst ahmt es nur den Text nach
                val painted = m.toolEvents.filter { it.name == "bild_malen" && it.ok }
                val note = painted.joinToString("") { "[Werkzeug bild_malen aufgerufen mit prompt=\"${it.detail}\" – Bild wurde angezeigt]\n" }
                note + PseudoToolCalls.strip(m.text, KNOWN_TOOLS)
            }
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
                add(ModelChoice(Source.OLLAMA, it.name, it.name, "Ollama · PC", vision = it.vision, tools = it.tools))
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
                        Source.OLLAMA -> OllamaEngine(
                            hostProvider = { settings.ollamaHost to settings.ollamaPort },
                            contextProvider = { settings.ollamaContext },
                        )
                    }
                    e to e.load(target)
                }
                engine = eng
                engineStatus = EngineStatus.Ready(label, acc, eng.supportsImages, (eng as? OllamaEngine)?.supportsTools == true)
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
