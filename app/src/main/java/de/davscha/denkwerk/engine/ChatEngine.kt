package de.davscha.denkwerk.engine

import kotlinx.coroutines.flow.Flow

/** Auf welchem Chip das Modell gerade rechnet. */
enum class Accelerator(val label: String) { GPU("Grafikchip"), CPU("Prozessor"), NETWORK("PC (Ollama)") }

/**
 * Die eine Schnittstelle, über die die App mit einem Sprachmodell redet.
 * Zwei Umsetzungen: [LiteRtEngine] (auf dem Gerät) und [OllamaEngine] (PC im Netzwerk).
 */
interface ChatEngine {
    /** Lädt das Modell. Dauert einige Sekunden – nie im UI-Thread aufrufen. */
    suspend fun load(modelPath: String): Accelerator

    /**
     * Startet ein Gespräch. [systemPrompt] ist die Anweisung an die KI, [history] ist der bisherige
     * Verlauf (true = von dir, false = von der KI) – so lässt sich ein gespeicherter Chat fortsetzen.
     */
    fun startConversation(systemPrompt: String, history: List<Pair<Boolean, String>>, temperature: Double)

    /** Schickt eine Nachricht (optional mit Bildern); die Antwort kommt Stück für Stück. */
    fun send(text: String, imagePaths: List<String> = emptyList()): Flow<String>

    /** Wie viele Bilder höchstens in eine Nachricht passen */
    val maxImages: Int

    /** true = das geladene Modell kann Bilder ansehen */
    val supportsImages: Boolean

    /** Bricht eine laufende Antwort ab. */
    fun stop()

    /** Gibt Modell und Speicher frei. */
    fun close()
}
