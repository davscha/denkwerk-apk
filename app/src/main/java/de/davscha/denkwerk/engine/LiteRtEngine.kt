package de.davscha.denkwerk.engine

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException

/**
 * Gemma 4 über Googles LiteRT-LM – dieselbe Technik wie in der AI Edge Gallery.
 * Versucht zuerst den Grafikchip, notfalls den Prozessor.
 */
class LiteRtEngine(private val context: Context) : ChatEngine {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    override var supportsImages = false
        private set

    override suspend fun load(modelPath: String): Accelerator = withContext(Dispatchers.IO) {
        close()
        // Reihenfolge: Grafikchip mit Mehr-Token-Vorhersage (schneller), Grafikchip ohne, Prozessor –
        // jeweils zuerst mit Bildverständnis, notfalls ohne
        data class Attempt(val acc: Accelerator, val speculative: Boolean, val vision: Boolean)
        val attempts = listOf(
            Attempt(Accelerator.GPU, true, true),
            Attempt(Accelerator.GPU, true, false),
            Attempt(Accelerator.GPU, false, true),
            Attempt(Accelerator.GPU, false, false),
            Attempt(Accelerator.CPU, false, true),
            Attempt(Accelerator.CPU, false, false),
        )
        var lastError: Throwable? = null
        for (a in attempts) {
            try {
                engine = createEngine(modelPath, a.acc, a.speculative, a.vision)
                supportsImages = a.vision
                Log.i(TAG, "Modell geladen: $a")
                return@withContext a.acc
            } catch (e: Throwable) {
                Log.w(TAG, "Laden fehlgeschlagen: $a", e)
                lastError = e
            }
        }
        throw IllegalStateException(lastError?.message ?: "Modell konnte nicht geladen werden", lastError)
    }

    @OptIn(ExperimentalApi::class)
    private fun createEngine(modelPath: String, acc: Accelerator, speculative: Boolean, vision: Boolean): Engine {
        fun backend() = if (acc == Accelerator.GPU) Backend.GPU() else Backend.CPU()
        ExperimentalFlags.enableSpeculativeDecoding = speculative
        try {
            val e = Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = backend(),
                    visionBackend = if (vision) backend() else null,
                    maxNumImages = if (vision) 1 else null,
                    maxNumTokens = MAX_TOKENS,
                    // Beschleunigt das zweite Laden
                    cacheDir = context.cacheDir.absolutePath,
                )
            )
            try {
                e.initialize()
            } catch (t: Throwable) {
                runCatching { e.close() }
                throw t
            }
            return e
        } finally {
            ExperimentalFlags.enableSpeculativeDecoding = false
        }
    }

    override fun startConversation(systemPrompt: String, history: List<Pair<Boolean, String>>, temperature: Double) {
        val e = engine ?: error("Kein Modell geladen")
        conversation?.let { old -> runCatching { old.close() } }
        conversation = e.createConversation(
            ConversationConfig(
                systemInstruction = systemPrompt.takeIf { it.isNotBlank() }?.let { Contents.of(it) },
                initialMessages = history
                    .filter { (_, text) -> text.isNotBlank() }
                    .map { (fromUser, text) -> if (fromUser) Message.user(text) else Message.model(text) },
                samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = temperature),
            )
        )
    }

    /** LiteRT-LM auf dem Gerät: ein Bild pro Nachricht (mehr kostet zu viel Grafikspeicher). */
    override val maxImages: Int get() = if (supportsImages) 1 else 0

    override fun send(text: String, imagePaths: List<String>): Flow<String> = callbackFlow {
        val c = conversation ?: error("Kein Gespräch gestartet")
        // Bild vor dem Text, so erwartet es Gemma
        val parts = buildList {
            if (imagePaths.isNotEmpty()) {
                check(supportsImages) { "Dieses Modell kann gerade keine Bilder ansehen." }
                add(Content.ImageFile(imagePaths.first()))
            }
            add(Content.Text(text))
        }
        c.sendMessageAsync(
            Contents.of(parts),
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    trySend(message.toString())
                }

                override fun onDone() {
                    close()
                }

                override fun onError(throwable: Throwable) {
                    // Abbruch per Stopp-Knopf ist kein Fehler
                    if (throwable is CancellationException) close() else close(throwable)
                }
            },
            mapOf("enable_thinking" to false),
        )
        awaitClose { }
    }.buffer(Channel.UNLIMITED) // kein Wortstück darf verloren gehen

    override fun stop() {
        runCatching { conversation?.cancelProcess() }
    }

    override fun close() {
        runCatching { conversation?.close() }
        conversation = null
        runCatching { engine?.close() }
        engine = null
        supportsImages = false
    }

    private companion object {
        const val TAG = "LiteRtEngine"
        const val MAX_TOKENS = 4096
    }
}
