package de.davscha.denkwerk.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Vorlesen über die Sprachausgabe von Android (offline möglich, kann Deutsch).
 * Markdown-Zeichen und Code-Blöcke werden vor dem Vorlesen entfernt.
 */
class Speaker(context: Context, private val onSpeakingChanged: (String?) -> Unit) {

    private var ready = false
    private var pending: (() -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.GERMANY
            pending?.invoke()
            pending = null
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = onSpeakingChanged(utteranceId)
            override fun onDone(utteranceId: String) = onSpeakingChanged(null)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = onSpeakingChanged(null)
            override fun onStop(utteranceId: String, interrupted: Boolean) = onSpeakingChanged(null)
        })
    }

    fun speak(id: String, text: String, pitch: Float, rate: Float) {
        val clean = cleanForSpeech(text)
        if (clean.isEmpty()) return
        val action = {
            tts.setPitch(pitch)
            tts.setSpeechRate(rate)
            // Lange Antworten in Absätze teilen – TextToSpeech hat eine Längengrenze pro Aufruf
            val parts = clean.chunked(3500)
            parts.forEachIndexed { i, part ->
                tts.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
            }
        }
        if (ready) action() else pending = { action() }
    }

    fun stop() {
        pending = null
        tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    companion object {
        /** Entfernt Code-Blöcke und Markdown-Zeichen, damit nichts Seltsames vorgelesen wird. */
        fun cleanForSpeech(text: String): String =
            text
                .replace(Regex("```[\\s\\S]*?(```|$)"), " (Code) ")
                .replace(Regex("`([^`]*)`"), "$1")
                .replace(Regex("[*_#>~|]"), " ")
                .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}
