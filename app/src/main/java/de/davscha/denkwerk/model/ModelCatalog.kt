package de.davscha.denkwerk.model

import android.content.Context
import android.os.Environment
import java.io.File

/** Ein mitgeliefertes Modell, das beim ersten Start von Hugging Face geladen wird. */
data class ModelInfo(
    val id: String,
    val title: String,
    val description: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
) {
    /** Liegt die Datei schon irgendwo (auch im Ordner von Character Fun), wird sie mitbenutzt. */
    fun file(context: Context): File =
        searchDirs(context).map { File(it, fileName) }.firstOrNull { it.exists() && it.length() > 0 }
            ?: File(modelsDir(context), fileName)
    fun partFile(context: Context) = File(modelsDir(context), "$fileName.part")
    // Die fertige Datei entsteht erst, wenn der Download vollständig ist
    fun isDownloaded(context: Context) = file(context).let { it.exists() && it.length() > 0 }
}

/** Interner App-Ordner – wird beim Deinstallieren mitgelöscht. */
fun internalModelsDir(context: Context) = File(context.filesDir, "models").apply { mkdirs() }

/** Dauerhafter Ordner auf dem Tablet – übersteht Neuinstallationen, sichtbar in "Eigene Dateien". */
fun sharedModelsDir() = File(Environment.getExternalStorageDirectory(), "Denkwerk/Modelle")

/** Mit Erlaubnis "Zugriff auf alle Dateien" der dauerhafte Ordner, sonst der interne. */
fun hasSharedStorage() = Environment.isExternalStorageManager()

fun modelsDir(context: Context): File =
    if (hasSharedStorage()) sharedModelsDir().apply { mkdirs() } else internalModelsDir(context)

/** Ordner von Character Fun – dieselben Gemma-Dateien müssen nicht doppelt geladen werden. */
fun characterFunModelsDir() = File(Environment.getExternalStorageDirectory(), "CharacterFun/Modelle")

/** Wo nach vorhandenen Modellen gesucht wird. */
fun searchDirs(context: Context): List<File> = buildList {
    if (hasSharedStorage()) {
        add(sharedModelsDir())
        add(characterFunModelsDir())
    }
    add(internalModelsDir(context))
}

/**
 * Feste Liste – dieselben Dateien wie in Googles AI Edge Gallery,
 * festgenagelt auf eine Version, damit Größe und Inhalt stimmen.
 */
object ModelCatalog {
    val E2B = ModelInfo(
        id = "gemma-4-e2b",
        title = "Gemma 4 E2B",
        description = "Schnell – Standard",
        fileName = "gemma-4-E2B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
            "6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm?download=true",
        sizeBytes = 2_588_147_712,
    )

    val E4B = ModelInfo(
        id = "gemma-4-e4b",
        title = "Gemma 4 E4B",
        description = "Klüger, aber langsamer",
        fileName = "gemma-4-E4B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/" +
            "28299f30ee4d43294517a4ac93abd6163412f07f/gemma-4-E4B-it.litertlm?download=true",
        sizeBytes = 3_659_530_240,
    )

    val all = listOf(E2B, E4B)

    /**
     * Eigene Modelle: jede .litertlm-Datei im Modell-Ordner, die nicht zur festen Liste gehört.
     * Man kann sie per "Datei wählen" importieren oder einfach selbst in Denkwerk/Modelle legen
     * (Dateien aus CharacterFun/Modelle werden ebenfalls gefunden).
     */
    fun customModels(context: Context): List<ModelInfo> {
        val known = all.map { it.fileName }.toSet()
        return searchDirs(context)
            .flatMap { dir -> dir.listFiles { f -> f.isFile && f.name.endsWith(".litertlm") && f.name !in known }?.toList().orEmpty() }
            .distinctBy { it.name }
            .sortedBy { it.name.lowercase() }
            .map { f ->
                ModelInfo(
                    id = "custom:${f.name}",
                    title = f.name.removeSuffix(".litertlm"),
                    description = "Eigene Datei",
                    fileName = f.name,
                    url = "",
                    sizeBytes = f.length(),
                )
            }
    }
}
