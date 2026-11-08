package de.davscha.denkwerk.data

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.util.zip.ZipInputStream

/** Holt den reinen Text aus verschiedenen Dateiarten. */
object DocReader {

    /** Größte Datei, die gelesen wird (größere sind für ein Sprachmodell eh zu lang) */
    private const val MAX_BYTES = 8L shl 20

    /** Text- und Code-Dateien: als UTF-8 lesen */
    fun plain(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytesCompat(MAX_BYTES) }
            ?: error("Datei konnte nicht geöffnet werden")
        // Binärdateien (Bilder, Archive …) erkennen: viele Null-Bytes
        if (bytes.take(4000).count { it == 0.toByte() } > 10) error("Das ist keine Textdatei.")
        return String(bytes, Charsets.UTF_8)
    }

    /** PDF: Text aller Seiten über PdfBox (eingescannte PDFs ohne Textebene liefern nichts) */
    fun pdf(context: Context, uri: Uri): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        return context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { doc ->
                PDFTextStripper().apply { sortByPosition = true }.getText(doc)
            }
        } ?: error("PDF konnte nicht geöffnet werden")
    }

    /** Word (.docx): ist ein ZIP – der Text steht in word/document.xml */
    fun docx(context: Context, uri: Uri): String {
        val xml = context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var found: String? = null
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "word/document.xml") {
                        found = String(zip.readNBytesCompat(MAX_BYTES * 4), Charsets.UTF_8)
                        break
                    }
                }
                found
            }
        } ?: error("Word-Datei konnte nicht gelesen werden")
        return xml
            .replace(Regex("</w:p>"), "\n")
            .replace(Regex("<w:tab/>"), "\t")
            .replace(Regex("<w:br/>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (total < limit) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}
