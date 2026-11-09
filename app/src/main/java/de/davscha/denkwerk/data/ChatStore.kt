package de.davscha.denkwerk.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import de.davscha.denkwerk.model.hasSharedStorage
import org.json.JSONObject
import java.io.File

/**
 * Speichert jeden Chat als eigene JSON-Datei im Ordner Chats, angehängte Bilder in Chats/Bilder.
 * Mit der Erlaubnis "Zugriff auf alle Dateien" liegt alles in /Denkwerk und übersteht Neuinstallationen.
 */
class ChatStore(private val context: Context) {

    private val sharedDir get() = File(Environment.getExternalStorageDirectory(), "Denkwerk")
    val dataDir: File get() = (if (hasSharedStorage()) sharedDir else context.filesDir).apply { mkdirs() }
    private val chatsDir get() = File(dataDir, "Chats").apply { mkdirs() }
    private val imagesDir get() = File(chatsDir, "Bilder").apply { mkdirs() }
    private val docsDir get() = File(chatsDir, "Dokumente").apply { mkdirs() }

    private fun file(id: String) = File(chatsDir, "chat-$id.json")

    /** Alle Chats, neueste zuerst. */
    fun loadAll(): List<Chat> =
        chatsDir.listFiles { f -> f.isFile && f.name.startsWith("chat-") && f.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { f -> runCatching { Chat.fromJson(JSONObject(f.readText())) }.getOrNull() }
            .sortedByDescending { it.updatedAt }

    fun save(chat: Chat) {
        // Erst in eine Hilfsdatei schreiben, dann umbenennen – so geht bei einem Absturz nichts kaputt
        val tmp = File(chatsDir, "chat-${chat.id}.json.tmp")
        tmp.writeText(chat.toJson().toString(2))
        tmp.renameTo(file(chat.id))
    }

    fun delete(chat: Chat) {
        file(chat.id).delete()
        // Bilder, die nur zu diesem Chat gehörten, mit aufräumen
        chat.messages.flatMap { it.imagePaths }.forEach { path ->
            File(path).takeIf { it.parentFile == imagesDir }?.delete()
        }
        chat.messages.flatMap { it.documents }.forEach { d ->
            File(d.textPath).takeIf { it.parentFile == docsDir }?.delete()
        }
    }

    /** Ein Bild als verkleinertes JPEG (max. 1024 px) in Chats/Bilder ablegen. Gibt den Pfad zurück. */
    fun importImage(uri: Uri): String? = runCatching {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val maxSide = 1024
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true,
            )
        } else bitmap
        val f = File(imagesDir, "bild-${System.currentTimeMillis()}.jpg")
        f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        f.absolutePath
    }.getOrNull()

    // ---------- Dokumente ----------

    /**
     * Liest ein Dokument aus (Text, Code, Markdown, CSV, JSON, PDF, Word) und legt den Text
     * als .txt in Chats/Dokumente ab. Wirft einen Fehler mit verständlicher Meldung.
     */
    fun importDocument(uri: Uri): DocAttachment {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "dokument"
        val mime = context.contentResolver.getType(uri).orEmpty()
        val lower = name.lowercase()
        val text = when {
            mime == "application/pdf" || lower.endsWith(".pdf") -> DocReader.pdf(context, uri)
            lower.endsWith(".docx") || mime.contains("wordprocessingml") -> DocReader.docx(context, uri)
            else -> DocReader.plain(context, uri)
        }.replace("\r\n", "\n").replace(Regex("\n{3,}"), "\n\n").trim()
        if (text.isBlank()) error("In „$name“ wurde kein Text gefunden (eingescannte PDFs gehen nicht).")
        val f = File(docsDir, "doc-${System.currentTimeMillis()}.txt")
        f.writeText(text)
        return DocAttachment(name, f.absolutePath, text.length)
    }

    // ---------- Gedächtnis (Memory) ----------

    private val memoryFile get() = File(dataDir, "gedaechtnis.json")

    fun loadMemory(): List<MemoryFact> = runCatching {
        val arr = JSONObject(memoryFile.readText()).getJSONArray("fakten")
        List(arr.length()) { MemoryFact.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun saveMemory(facts: List<MemoryFact>) {
        val json = JSONObject().put("version", 1).put("fakten", org.json.JSONArray(facts.map { it.toJson() }))
        val tmp = File(dataDir, "gedaechtnis.json.tmp")
        tmp.writeText(json.toString(2))
        tmp.renameTo(memoryFile)
    }

    // ---------- Prompt-Bibliothek ----------

    private val promptsFile get() = File(dataDir, "prompts.json")

    fun loadPrompts(): List<SavedPrompt> {
        val f = promptsFile
        if (!f.exists()) {
            savePrompts(SavedPrompt.samples)
            return SavedPrompt.samples
        }
        return runCatching {
            val arr = JSONObject(f.readText()).getJSONArray("prompts")
            List(arr.length()) { SavedPrompt.fromJson(arr.getJSONObject(it)) }
        }.getOrElse { SavedPrompt.samples }
    }

    fun savePrompts(prompts: List<SavedPrompt>) {
        val json = JSONObject().put("version", 1).put("prompts", org.json.JSONArray(prompts.map { it.toJson() }))
        val tmp = File(dataDir, "prompts.json.tmp")
        tmp.writeText(json.toString(2))
        tmp.renameTo(promptsFile)
    }

    // ---------- Assistenten (Presets) ----------

    private val presetsFile get() = File(dataDir, "assistenten.json")

    /** Alle Assistenten; beim ersten Start kommen die Beispiele hinein. */
    fun loadPresets(): List<Preset> {
        val f = presetsFile
        if (!f.exists()) {
            savePresets(Preset.samples)
            return Preset.samples
        }
        val list = runCatching {
            val arr = JSONObject(f.readText()).getJSONArray("assistenten")
            List(arr.length()) { Preset.fromJson(arr.getJSONObject(it)) }
        }.getOrElse { Preset.samples }
        // Prompt-Schmied (Danbooru-Tags) ist veraltet – einmal entfernen
        if (list.any { it.id == Preset.OLD_PROMPT_SMITH_ID }) {
            val cleaned = list.filter { it.id != Preset.OLD_PROMPT_SMITH_ID }
            savePresets(cleaned)
            return cleaned
        }
        return list
    }

    fun savePresets(presets: List<Preset>) {
        val json = JSONObject().put("version", 1).put("assistenten", org.json.JSONArray(presets.map { it.toJson() }))
        val tmp = File(dataDir, "assistenten.json.tmp")
        tmp.writeText(json.toString(2))
        tmp.renameTo(presetsFile)
    }

    /** Chat als Markdown-Text (zum Teilen). */
    fun toMarkdown(chat: Chat): String = buildString {
        appendLine("# ${chat.title}")
        appendLine()
        chat.messages.filter { !it.isError }.forEach { m ->
            appendLine(if (m.fromUser) "**Du:**" else "**Denkwerk** (${m.modelLabel}):")
            if (m.imagePaths.isNotEmpty()) appendLine("_[${m.imagePaths.size} Bild(er)]_")
            appendLine(m.text)
            appendLine()
        }
    }
}
