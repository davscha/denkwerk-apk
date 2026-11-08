package de.davscha.denkwerk.ui

import androidx.compose.foundation.background
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
}

/** Zerlegt Markdown in Blöcke. Ein noch offener Code-Block (beim Mitschreiben) zählt schon als Code. */
private fun parseBlocks(md: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val lines = md.replace("\r", "").split("\n")
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

/** **fett**, *kursiv*, `Code`, ~~durchgestrichen~~ und [Links](url) als formatierter Text. */
fun inlineMarkdown(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("(\\*\\*[^*]+\\*\\*|__[^_]+__|`[^`]+`|~~[^~]+~~|\\*[^*\\s][^*]*\\*|\\[[^\\]]+]\\([^)]+\\))")
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
                withStyle(SpanStyle(color = Color(0xFF7FD8FF), textDecoration = TextDecoration.Underline)) { append(label) }
            }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(t.substring(1, t.length - 1)) }
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

/** Zeigt eine KI-Antwort mit Markdown an. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parseBlocks(markdown) }
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
                is Block.Table -> Text(
                    b.lines.filterNot { Regex("^\\|?\\s*:?-{2,}").containsMatchIn(it) }
                        .joinToString("\n") { row -> row.trim('|').split("|").joinToString("  │  ") { it.trim() } },
                    style = body.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(codeBg)
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                )
                Block.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
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
