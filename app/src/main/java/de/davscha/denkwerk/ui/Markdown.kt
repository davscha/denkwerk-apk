package de.davscha.denkwerk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Ein Baustein einer Markdown-Antwort. */
private sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Bullet(val text: String, val indent: Int) : Block
    data class Numbered(val number: String, val text: String, val indent: Int) : Block
    data class Quote(val text: String) : Block
    data class Code(val language: String, val code: String) : Block
    data class Table(val lines: List<String>) : Block
    data object Rule : Block
    /** Bilder aus ![Beschreibung](Adresse) */
    data class Images(val images: List<GalleryImage>) : Block
}

/** ![Beschreibung](Adresse) */
private val IMAGE_MD = Regex("!\\[([^\\]]*)]\\((\\S+?)(?:\\s+\"[^\"]*\")?\\)")
private const val IMG_MARK = "\u0000IMG"

/** Zieht Bilder aus einer Zeile heraus: Zeile ohne Bilder + eine Markierungszeile dahinter. */
private fun splitImages(lines: List<String>): List<String> = lines.flatMap { line ->
    val found = IMAGE_MD.findAll(line).toList()
    if (found.isEmpty()) listOf(line)
    else {
        val rest = line.replace(IMAGE_MD, "").let { if (it.isBlank()) null else it }
        val marker = IMG_MARK + found.joinToString("\u0002") { it.groupValues[1] + "\u0001" + it.groupValues[2] }
        listOfNotNull(rest, marker)
    }
}

/** Zerlegt Markdown in Blöcke. Ein noch offener Code-Block (beim Mitschreiben) zählt schon als Code. */
private fun parseBlocks(md: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val lines = splitImages(md.replace("\r", "").split("\n"))
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotBlank()) blocks += Block.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        val indent = (line.length - trimmed.length) / 2
        when {
            line.startsWith(IMG_MARK) -> {
                flush()
                val imgs = line.removePrefix(IMG_MARK).split('\u0002').map { part ->
                    GalleryImage(url = part.substringAfter('\u0001'), title = part.substringBefore('\u0001'))
                }.filter { it.url.startsWith("http") }
                if (imgs.isNotEmpty()) {
                    // Direkt aufeinanderfolgende Bilder zu einer Reihe zusammenfassen
                    val prev = blocks.lastOrNull()
                    if (prev is Block.Images) blocks[blocks.lastIndex] = Block.Images(prev.images + imgs)
                    else blocks += Block.Images(imgs)
                }
            }
            trimmed.startsWith("```") -> {
                flush()
                val lang = trimmed.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.appendLine(lines[i])
                    i++
                }
                blocks += Block.Code(lang, code.toString().trimEnd('\n'))
            }
            trimmed.isEmpty() -> flush()
            Regex("^#{1,6}\\s").containsMatchIn(trimmed) -> {
                flush()
                val level = trimmed.takeWhile { it == '#' }.length
                blocks += Block.Heading(level, trimmed.drop(level).trim())
            }
            Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(trimmed) -> {
                flush()
                blocks += Block.Rule
            }
            Regex("^[-*+]\\s").containsMatchIn(trimmed) -> {
                flush()
                blocks += Block.Bullet(trimmed.drop(2), indent)
            }
            Regex("^\\d+[.)]\\s").containsMatchIn(trimmed) -> {
                flush()
                val num = trimmed.takeWhile { it.isDigit() }
                blocks += Block.Numbered(num, trimmed.drop(num.length + 1).trim(), indent)
            }
            trimmed.startsWith(">") -> {
                flush()
                blocks += Block.Quote(trimmed.removePrefix(">").trim())
            }
            trimmed.startsWith("|") -> {
                flush()
                val rows = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    rows += lines[i].trim()
                    i++
                }
                blocks += Block.Table(rows)
                continue
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line.trim())
            }
        }
        i++
    }
    flush()
    return blocks
}

private val linkStyle = TextLinkStyles(SpanStyle(color = Color(0xFF7FD8FF), textDecoration = TextDecoration.Underline))

/** **fett**, *kursiv*, `Code`, ~~durchgestrichen~~, [Links](url) und nackte https://-Adressen – Links sind antippbar. */
fun inlineMarkdown(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("(\\*\\*[^*]+\\*\\*|__[^_]+__|`[^`]+`|~~[^~]+~~|\\*[^*\\s][^*]*\\*|\\[[^\\]]+]\\([^)\\s]+\\)|https?://[^\\s)\\]>\"]+)")
    var last = 0
    for (m in pattern.findAll(text)) {
        append(text.substring(last, m.range.first))
        val t = m.value
        when {
            t.startsWith("**") || t.startsWith("__") ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t.substring(2, t.length - 2)) }
            t.startsWith("`") ->
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground, fontSize = 14.sp)) {
                    append(" ${t.substring(1, t.length - 1)} ")
                }
            t.startsWith("~~") ->
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(t.substring(2, t.length - 2)) }
            t.startsWith("[") -> {
                val label = t.substring(1, t.indexOf("]("))
                val url = t.substring(t.indexOf("](") + 2, t.length - 1)
                withLink(LinkAnnotation.Url(url, linkStyle)) { append(label) }
            }
            t.startsWith("http") -> {
                // Satzzeichen am Ende gehören nicht zur Adresse
                val url = t.trimEnd('.', ',', ';', ':', '!', '?')
                withLink(LinkAnnotation.Url(url, linkStyle)) { append(url) }
                append(t.substring(url.length))
            }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(t.substring(1, t.length - 1)) }
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

/** Zeigt eine KI-Antwort mit Markdown an. */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    /** Bild angetippt: alle Bilder dieser Reihe + Position (für die Slideshow) */
    onOpenImages: ((List<GalleryImage>, Int) -> Unit)? = null,
) {
    val blocks = remember(markdown) { parseBlocks(markdown) }
    val openGallery = onOpenImages ?: LocalOpenGallery.current
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val body = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in blocks) {
            when (b) {
                is Block.Paragraph -> Text(inlineMarkdown(b.text, codeBg), style = body)
                is Block.Heading -> Text(
                    inlineMarkdown(b.text, codeBg),
                    style = body.copy(
                        fontSize = when (b.level) { 1 -> 22.sp; 2 -> 19.sp; else -> 17.sp },
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    modifier = Modifier.padding(top = 4.dp),
                )
                is Block.Bullet -> Row(Modifier.padding(start = (b.indent * 16).dp)) {
                    Text(if (b.indent == 0) "•" else "◦", style = body, modifier = Modifier.width(18.dp))
                    Text(inlineMarkdown(b.text, codeBg), style = body)
                }
                is Block.Numbered -> Row(Modifier.padding(start = (b.indent * 16).dp)) {
                    Text("${b.number}.", style = body, modifier = Modifier.width(26.dp))
                    Text(inlineMarkdown(b.text, codeBg), style = body)
                }
                is Block.Quote -> Row {
                    Box(Modifier.width(3.dp).height(24.dp).background(MaterialTheme.colorScheme.primary))
                    Spacer(Modifier.width(10.dp))
                    Text(inlineMarkdown(b.text, codeBg), style = body.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                }
                is Block.Code -> CodeBlock(b.language, b.code)
                is Block.Table -> TableBlock(b.lines, codeBg)
                Block.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                is Block.Images -> ImageStrip(b.images, onOpen = { i -> openGallery(b.images, i) })
            }
        }
    }
}

/** Markdown-Tabelle als echtes Raster: Kopfzeile fett, Zellen mit **fett** usw., zur Seite wischbar. */
@Composable
private fun TableBlock(lines: List<String>, cellBg: Color) {
    val rows = lines
        .filterNot { Regex("^\\|?\\s*:?-{2,}").containsMatchIn(it) }
        .map { row -> row.trim().trim('|').split("|").map { it.trim() } }
        .filter { r -> r.any { it.isNotEmpty() } }
    if (rows.isEmpty()) return
    val cols = rows.maxOf { it.size }
    // Spaltenbreite nach dem längsten Inhalt (ohne Markdown-Zeichen), begrenzt
    val widths = (0 until cols).map { c ->
        val longest = rows.maxOf { r -> r.getOrNull(c)?.replace(Regex("[*_`]"), "")?.length ?: 0 }
        (longest * 7.5f).coerceIn(70f, 260f).dp
    }
    val textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 19.sp)
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .horizontalScroll(rememberScrollState()),
    ) {
        rows.forEachIndexed { ri, r ->
            Row(Modifier.background(if (ri == 0) cellBg else Color.Transparent)) {
                for (c in 0 until cols) {
                    Text(
                        inlineMarkdown(r.getOrNull(c).orEmpty(), cellBg),
                        style = if (ri == 0) textStyle.copy(fontWeight = FontWeight.SemiBold) else textStyle,
                        modifier = Modifier.width(widths[c]).padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
            if (ri < rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        }
    }
}

/** Malen-Funktion für Prompt-Blöcke (vom Chat bereitgestellt; null = ComfyUI nicht eingerichtet) */
val LocalPaint = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }

/** Sieht der Block wie ein positiver Bild-Prompt aus? (Kommaliste, kein Code, kein Negativ-Prompt) */
private fun looksLikeImagePrompt(language: String, code: String): Boolean {
    if (language.lowercase() !in setOf("", "text", "txt", "prompt", "positive", "plaintext")) return false
    val t = code.trim().lowercase()
    if (t.count { it == ',' } < 3 || t.contains('{') || t.contains(';') || t.contains("=")) return false
    if (t.startsWith("worst quality") || t.startsWith("lowres") || t.startsWith("bad ") || t.startsWith("negative")) return false
    return true
}

/** Code-Block mit Sprache, Kopier-Knopf und waagerechtem Scrollen. */
@Composable
private fun CodeBlock(language: String, code: String) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0B0B0D)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF222226))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(language.ifEmpty { "code" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            // „🎨 Malen“ bei Bild-Prompts (Tag-Listen), wenn ComfyUI eingerichtet ist
            val paint = LocalPaint.current
            if (paint != null && looksLikeImagePrompt(language, code)) {
                Text(
                    "🎨 Malen",
                    fontSize = 12.sp,
                    color = Mint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { paint(code) }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        copyToClipboard(context, code)
                        copied = true
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(if (copied) "Kopiert" else "Kopieren", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            highlight(code),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = Color(0xFFE6E6E6),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        )
    }
}

/** Ganz einfache Syntax-Farben: Schlüsselwörter, Texte in Anführungszeichen, Kommentare, Zahlen. */
private fun highlight(code: String): AnnotatedString = buildAnnotatedString {
    val keywords = setOf(
        "fun", "val", "var", "class", "object", "if", "else", "when", "for", "while", "return", "import", "package",
        "def", "from", "as", "in", "is", "not", "and", "or", "None", "True", "False", "null", "true", "false",
        "function", "const", "let", "new", "this", "self", "public", "private", "static", "void", "int", "string",
        "try", "catch", "except", "finally", "with", "async", "await", "lambda", "data", "interface", "override",
    )
    val token = Regex("(//[^\\n]*|#[^\\n]*|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|\\b\\d+(?:\\.\\d+)?\\b|\\b[A-Za-z_][A-Za-z0-9_]*\\b)")
    var last = 0
    for (m in token.findAll(code)) {
        append(code.substring(last, m.range.first))
        val t = m.value
        val color = when {
            t.startsWith("//") || t.startsWith("#") -> Color(0xFF6A9955)
            t.startsWith("\"") || t.startsWith("'") -> Color(0xFFCE9178)
            t[0].isDigit() -> Color(0xFFB5CEA8)
            t in keywords -> Color(0xFFC586C0)
            t[0].isUpperCase() -> Color(0xFF4EC9B0)
            else -> null
        }
        if (color != null) withStyle(SpanStyle(color = color)) { append(t) } else append(t)
        last = m.range.last + 1
    }
    append(code.substring(last))
}

fun copyToClipboard(context: android.content.Context, text: String) {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
    cm?.setPrimaryClip(android.content.ClipData.newPlainText("Denkwerk", text))
}
