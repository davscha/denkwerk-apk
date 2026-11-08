package de.davscha.denkwerk.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import de.davscha.denkwerk.data.ChatMessage
import de.davscha.denkwerk.data.DocAttachment
import de.davscha.denkwerk.data.ToolEvent
import de.davscha.denkwerk.engine.Toolbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import de.davscha.denkwerk.data.Source
import de.davscha.denkwerk.engine.Accelerator
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ChatScreen(vm: AppViewModel, onMenu: (() -> Unit)?) {
    var input by remember { mutableStateOf("") }
    var images by remember { mutableStateOf<List<String>>(emptyList()) }
    var docs by remember { mutableStateOf<List<DocAttachment>>(emptyList()) }
    var editingId by remember { mutableStateOf<String?>(null) }
    val chat = vm.currentChat
    val messages = chat?.messages.orEmpty()

    // Beim Wechsel des Chats Eingabe zurücksetzen
    LaunchedEffect(vm.currentChatId) {
        editingId = null
    }

    // Slideshow für alle Bilder im Chat
    var gallery by remember { mutableStateOf<Pair<List<GalleryImage>, Int>?>(null) }
    gallery?.let { (imgs, start) ->
        SlideshowDialog(
            imgs,
            start,
            onDismiss = { gallery = null },
            onSave = { vm.saveWebImage(it.url) },
            onReverse = { img -> gallery = null; vm.reverseSearch(img) },
        )
    }

    CompositionLocalProvider(LocalOpenGallery provides { imgs, i -> gallery = imgs to i }) {
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(vm, onMenu)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                EmptyState(
                    vm = vm,
                    onSuggestion = { text ->
                        // Starter mit ":" am Ende kommen ins Eingabefeld, damit du weiterschreiben kannst
                        if (text.trimEnd().endsWith(":")) input = text.trimEnd() + " " else vm.send(text)
                    },
                )
            } else {
                MessageList(
                    vm = vm,
                    messages = messages,
                    onEdit = { msg ->
                        editingId = msg.id
                        input = msg.text
                    },
                )
            }
            vm.toast?.let { msg ->
                LaunchedEffect(msg) {
                    kotlinx.coroutines.delay(3500)
                    vm.toast = null
                }
                Text(
                    msg,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { vm.toast = null }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }

        vm.downloadConfirm?.let { c ->
            AlertDialog(
                onDismissRequest = { vm.answerDownload(false) },
                title = { Text("Dateien speichern?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Die KI möchte ${c.request.urls.size} Datei(en) nach Denkwerk/Downloads speichern:", fontSize = 14.sp)
                        c.request.urls.take(10).forEach { u ->
                            Text("• " + u.substringBefore('?').takeLast(70), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { vm.answerDownload(true) }) { Text("Speichern") } },
                dismissButton = { TextButton(onClick = { vm.answerDownload(false) }) { Text("Ablehnen") } },
            )
        }

        InputBar(
            vm = vm,
            text = input,
            onText = { input = it },
            images = images,
            onImages = { images = it },
            docs = docs,
            onDocs = { docs = it },
            editing = editingId != null,
            onCancelEdit = { editingId = null; input = "" },
            onSend = {
                val id = editingId
                if (id != null) {
                    vm.editMessage(id, input)
                    editingId = null
                } else {
                    vm.send(input, images, docs)
                }
                input = ""
                images = emptyList()
                docs = emptyList()
            },
        )
    }
    }
}

// ---------------------------------------------------------------------------
// Kopfzeile mit Modell-Auswahl
// ---------------------------------------------------------------------------

@Composable
private fun TopBar(vm: AppViewModel, onMenu: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onMenu != null) IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Chats") } else Spacer(Modifier.width(8.dp))

        Box(Modifier.weight(1f)) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { open = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            statusTitle(vm.engineStatus),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 260.dp),
                        )
                        Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(20.dp))
                    }
                    StatusLine(vm.engineStatus)
                }
            }
            ModelMenu(vm, open) { open = false }
        }

        vm.currentPreset?.let { p ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { vm.openPresets() }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(p.emoji, fontSize = 14.sp)
                Spacer(Modifier.width(4.dp))
                Text(p.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
            }
        }
        IconButton(onClick = { vm.newChat(vm.currentPreset?.id) }) { Icon(Icons.Default.EditNote, "Neuer Chat") }
    }
}

private fun statusTitle(s: EngineStatus): String = when (s) {
    EngineStatus.None -> "Modell wählen"
    is EngineStatus.Loading -> s.label.substringBefore(" · ")
    is EngineStatus.Ready -> s.label.substringBefore(" · ")
    is EngineStatus.Failed -> s.label.substringBefore(" · ")
}

@Composable
private fun StatusLine(s: EngineStatus) {
    val (text, color) = when (s) {
        EngineStatus.None -> "Kein Modell geladen" to MaterialTheme.colorScheme.onSurfaceVariant
        is EngineStatus.Loading -> "Lädt …" to MaterialTheme.colorScheme.secondary
        is EngineStatus.Ready -> buildString {
            append(
                when (s.accelerator) {
                    Accelerator.GPU -> "Gerät · Grafikchip"
                    Accelerator.CPU -> "Gerät · Prozessor"
                    Accelerator.NETWORK -> "Ollama · PC"
                }
            )
            if (s.vision) append(" · Bilder ✓")
            if (s.tools) append(" · Tools ✓")
        } to Mint
        is EngineStatus.Failed -> "Fehler: ${s.message}" to MaterialTheme.colorScheme.error
    }
    Text(text, fontSize = 11.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp))
}

@Composable
private fun ModelMenu(vm: AppViewModel, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.widthIn(min = 260.dp)) {
        val choices = vm.modelChoices
        val internal = choices.filter { it.source == Source.INTERNAL }
        val ollama = choices.filter { it.source == Source.OLLAMA }
        MenuHeader("Auf dem Gerät")
        if (internal.isEmpty()) MenuHint("Noch kein Modell heruntergeladen")
        internal.forEach { c -> ChoiceItem(vm, c, onDismiss) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 4.dp))
        MenuHeader("Ollama · PC")
        if (ollama.isEmpty()) MenuHint(if (vm.ollamaBusy) "Verbinde …" else "Nicht verbunden")
        ollama.forEach { c -> ChoiceItem(vm, c, onDismiss) }
        if (!vm.ollamaBusy) {
            DropdownMenuItem(
                text = { Text("Ollama neu verbinden", fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp)) },
                onClick = { vm.testOllama() },
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 4.dp))
        DropdownMenuItem(
            text = { Text("Modelle verwalten …") },
            leadingIcon = { Icon(Icons.Default.Settings, null, modifier = Modifier.size(18.dp)) },
            onClick = { onDismiss(); vm.openSettings() },
        )
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun MenuHint(text: String) {
    Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}

@Composable
private fun ChoiceItem(vm: AppViewModel, c: ModelChoice, onDismiss: () -> Unit) {
    val active = vm.isActive(c)
    DropdownMenuItem(
        text = {
            Column {
                Text(c.title, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                val caps = listOfNotNull("versteht Bilder".takeIf { c.vision == true }, "Tools".takeIf { c.tools == true })
                if (caps.isNotEmpty()) Text(caps.joinToString(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingIcon = { if (active) Icon(Icons.Default.Check, null, tint = Mint) },
        onClick = { onDismiss(); vm.select(c) },
    )
}

// ---------------------------------------------------------------------------
// Startbildschirm
// ---------------------------------------------------------------------------

private data class Suggestion(val icon: ImageVector, val title: String, val prompt: String)

private val suggestions = listOf(
    Suggestion(Icons.Default.Lightbulb, "Ideen sammeln", "Gib mir 10 kreative Ideen für ein kleines Wochenendprojekt mit Python."),
    Suggestion(Icons.Default.School, "Einfach erklären", "Erkläre mir ohne Formeln, wie ein neuronales Netz lernt – mit einem Alltagsbeispiel."),
    Suggestion(Icons.Default.Code, "Code schreiben", "Schreibe ein Kotlin-Beispiel für eine Liste mit Suchfeld in Jetpack Compose."),
    Suggestion(Icons.Default.EditNote, "Text verbessern", "Hilf mir, eine freundliche, kurze E-Mail zu schreiben, in der ich einen Termin verschiebe."),
)

@Composable
private fun EmptyState(vm: AppViewModel, onSuggestion: (String) -> Unit) {
    val preset = vm.currentPreset
    // Mit Assistent: seine Starter; sonst die allgemeinen Vorschläge
    val cards = if (preset != null && preset.starters.isNotEmpty()) {
        preset.starters.map { Suggestion(Icons.Default.AutoAwesome, it.trimEnd(':', ' '), it) }
    } else suggestions
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (preset != null) {
            PresetAvatar(preset.emoji, 64)
            Spacer(Modifier.height(12.dp))
            Text(preset.name, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (preset.description.isNotBlank()) {
                Text(preset.description, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        } else {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Mint),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp)) }
            Spacer(Modifier.height(16.dp))
            Text("Wobei kann ich helfen?", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(28.dp))
        Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            cards.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { s ->
                        Column(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 84.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                                .clickable { onSuggestion(s.prompt) }
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(s.icon, null, tint = Mint, modifier = Modifier.size(20.dp))
                            Text(s.title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text(s.prompt, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Nachrichten
// ---------------------------------------------------------------------------

@Composable
private fun MessageList(vm: AppViewModel, messages: List<ChatMessage>, onEdit: (ChatMessage) -> Unit) {
    val state = rememberLazyListState()
    // Neue Nachricht oder neuer Text beim Mitschreiben: nach unten scrollen
    LaunchedEffect(messages.size, vm.currentChatId) {
        if (messages.isNotEmpty()) state.scrollToItem(messages.lastIndex)
    }
    LaunchedEffect(vm.streamingText.length / 80) {
        if (vm.streamingId != null && messages.isNotEmpty()) state.scrollToItem(messages.lastIndex, 100_000)
    }
    val lastAnswerId = messages.lastOrNull { !it.fromUser }?.id
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(messages, key = { it.id }) { m ->
            Box(Modifier.widthIn(max = 820.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (m.fromUser) {
                    UserMessage(m, canEdit = !vm.generating, onEdit = { onEdit(m) }, onSavePrompt = { vm.promptFromMessage(m.text) })
                } else {
                    AssistantMessage(vm, m, isLast = m.id == lastAnswerId)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserMessage(m: ChatMessage, canEdit: Boolean, onEdit: () -> Unit, onSavePrompt: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (m.imagePaths.isNotEmpty()) {
            // Ein Bild groß, mehrere als waagerecht wischbare Reihe – Antippen öffnet die Slideshow
            val openGallery = LocalOpenGallery.current
            val side = if (m.imagePaths.size == 1) 180.dp else 120.dp
            val local = m.imagePaths.map { GalleryImage(url = it, title = File(it).name, local = true) }
            Row(
                Modifier.padding(bottom = 6.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                m.imagePaths.forEachIndexed { i, path ->
                    AsyncImage(
                        model = File(path),
                        contentDescription = "Angehängtes Bild",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(side).clip(RoundedCornerShape(14.dp)).clickable { openGallery(local, i) },
                    )
                }
            }
        }
        if (m.documents.isNotEmpty()) {
            Row(
                Modifier.padding(bottom = 6.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) { m.documents.forEach { d -> DocChip(d, onRemove = null) } }
        }
        if (m.text.isNotBlank()) {
            Box {
                Text(
                    m.text,
                    fontSize = 16.sp,
                    lineHeight = 23.sp,
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .combinedClickable(onClick = {}, onLongClick = { menu = true })
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Kopieren") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        onClick = { menu = false; copyToClipboard(context, m.text) },
                    )
                    DropdownMenuItem(
                        text = { Text("Als Prompt speichern") },
                        leadingIcon = { Icon(Icons.Default.Bookmarks, null) },
                        onClick = { menu = false; onSavePrompt() },
                    )
                    if (canEdit) {
                        DropdownMenuItem(
                            text = { Text("Bearbeiten") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = { menu = false; onEdit() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantMessage(vm: AppViewModel, m: ChatMessage, isLast: Boolean) {
    val context = LocalContext.current
    val streaming = vm.streamingId == m.id
    val text = if (streaming) vm.streamingText else m.text
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }
    val tools = if (streaming) vm.streamingTools else m.toolEvents
    val webImages = if (streaming) vm.streamingWebImages else m.webImages
    val webVideos = if (streaming) vm.streamingWebVideos else m.webVideos
    val sources = if (streaming) emptyList() else m.sources
    val openGallery = LocalOpenGallery.current
    Column(Modifier.fillMaxWidth()) {
        if (tools.isNotEmpty()) {
            Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                tools.forEach { t ->
                    // Rückwärtssuche gescheitert? Antippen gibt das Bild an Google Lens weiter
                    val lens = if (t.name == "bild_rueckwaertssuche" && !t.ok && !t.running) vm.lastChatImage() else null
                    ToolCard(t, onClick = lens?.let { img -> { openInLens(context, img) } })
                }
            }
        }
        // Gefundene Videos – antippen spielt ab, „Zusammenfassen“ fragt die KI nach dem Inhalt
        if (webVideos.isNotEmpty()) {
            Box(Modifier.padding(bottom = 10.dp)) {
                VideoStrip(webVideos, onSummarize = if (vm.generating) null else { v -> vm.send("Fasse dieses Video kurz zusammen: ${v.url}") })
            }
        }
        // Gefundene Bilder aus dem Web – direkt sichtbar, Antippen öffnet die Slideshow
        if (webImages.isNotEmpty()) {
            Box(Modifier.padding(bottom = 10.dp)) { ImageStrip(webImages, onOpen = { i -> openGallery(webImages, i) }) }
        }
        when {
            streaming && text.isEmpty() && tools.none { it.running } -> TypingDots()
            streaming && text.isEmpty() -> {}
            m.isError -> Text(text, color = MaterialTheme.colorScheme.error, fontSize = 15.sp)
            else -> MarkdownText(text)
        }
        // Quellen, auf die sich die Antwort stützt – antippen öffnet die Seite
        if (sources.isNotEmpty()) SourcesRow(sources)
        // Heruntergeladene Bilder (auf dem Gerät)
        if (!streaming && m.imagePaths.isNotEmpty()) {
            val local = m.imagePaths.map { GalleryImage(url = it, title = File(it).name, local = true) }
            Box(Modifier.padding(top = 8.dp)) { ImageStrip(local, onOpen = { i -> openGallery(local, i) }) }
        }
        if (!streaming && text.isNotEmpty()) {
            Row(
                Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallAction(if (copied) Icons.Default.Check else Icons.Default.ContentCopy, "Kopieren") {
                    copyToClipboard(context, m.text)
                    copied = true
                }
                if (!m.isError) {
                    SmallAction(
                        if (vm.speakingId == m.id) Icons.Default.StopCircle else Icons.AutoMirrored.Filled.VolumeUp,
                        "Vorlesen",
                    ) { vm.toggleSpeak(m) }
                }
                if (isLast && !vm.generating) SmallAction(Icons.Default.Refresh, "Neu erzeugen") { vm.regenerate() }
                // Fassungen durchblättern: ‹ 2/3 ›
                if (m.variants.size > 1) {
                    val canSwitch = !vm.generating
                    SmallAction(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Vorherige Fassung") {
                        if (canSwitch) vm.showVariant(m.id, m.variantIndex - 1)
                    }
                    Text("${m.variantIndex + 1}/${m.variants.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SmallAction(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Nächste Fassung") {
                        if (canSwitch) vm.showVariant(m.id, m.variantIndex + 1)
                    }
                }
                Spacer(Modifier.width(6.dp))
                val info = buildString {
                    append(m.modelLabel.substringBefore(" · "))
                    if (m.tokensPerSec > 0f) append(" · ${"%.1f".format(m.tokensPerSec)} Tok/s")
                }
                Text(info, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(icon, label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
    }
}

/** „Quellen“ unter der Antwort: nummerierte Kärtchen mit Seite und Titel */
@Composable
private fun SourcesRow(sources: List<de.davscha.denkwerk.data.SourceLink>) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Quellen", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            sources.forEachIndexed { i, s ->
                val host = runCatching { java.net.URL(s.url).host.removePrefix("www.") }.getOrDefault(s.url)
                Column(
                    Modifier
                        .widthIn(max = 220.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { runCatching { uri.openUri(s.url) } }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text("${i + 1} · $host", fontSize = 11.sp, color = Mint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.title, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Kärtchen für einen Werkzeug-Aufruf, z. B. „🔍 Websuche · „wetter potsdam“ · 6 Treffer“ */
@Composable
private fun ToolCard(t: ToolEvent, onClick: (() -> Unit)? = null) {
    val (icon, label) = Toolbox.label(t.name)
    Column {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 13.sp)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        if (t.detail.isNotBlank()) {
            // Suchbegriff kürzer kappen, damit rechts die Anzahl („5 Bilder“) sichtbar bleibt
            Text(" · ${t.detail}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
        }
        Spacer(Modifier.width(6.dp))
        if (t.running) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(12.dp), color = Mint)
        } else {
            Text(
                t.summary,
                fontSize = 12.sp,
                color = if (t.ok) Mint else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
        }
    }
    // Fehler vollständig lesbar machen (oben wird er abgeschnitten)
    if (!t.ok && !t.running && t.summary.length > 30) {
        Text(
            t.summary,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.error,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
    if (onClick != null) {
        Text(
            "→ In Google Lens öffnen",
            fontSize = 12.sp,
            color = Mint,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onClick() }.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
    }
}

/** Drei pulsierende Punkte, solange die KI nachdenkt. */
@Composable
private fun TypingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(9.dp).alpha(a).clip(CircleShape).background(Mint))
        }
    }
}

// ---------------------------------------------------------------------------
// Eingabe
// ---------------------------------------------------------------------------

@Composable
private fun InputBar(
    vm: AppViewModel,
    text: String,
    onText: (String) -> Unit,
    images: List<String>,
    onImages: (List<String>) -> Unit,
    docs: List<DocAttachment>,
    onDocs: (List<DocAttachment>) -> Unit,
    editing: Boolean,
    onCancelEdit: () -> Unit,
    onSend: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val maxImages = vm.maxImages
    val free = (maxImages - images.size).coerceAtLeast(0)
    var attachMenu by remember { mutableStateOf(false) }
    var readingDoc by remember { mutableStateOf(false) }

    // Gerät: ein Bild (ersetzt das alte) · Ollama: mehrere Bilder auf einmal
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { vm.importImage(uri)?.let { onImages(listOf(it)) } }
    }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val added = uris.take(free).mapNotNull { vm.importImage(it) }
            onImages(images + added)
            if (uris.size > free) vm.toast = "Höchstens $maxImages Bilder pro Nachricht."
        }
    }
    // Dokumente: Text, Code, Markdown, CSV, JSON, PDF, Word
    val pickDocs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            readingDoc = true
            val added = uris.take(MAX_DOCS - docs.size).mapNotNull { vm.importDocument(it) }
            readingDoc = false
            onDocs(docs + added)
            if (uris.size > MAX_DOCS - docs.size) vm.toast = "Höchstens $MAX_DOCS Dokumente pro Nachricht."
        }
    }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) onText(if (text.isBlank()) spoken else "$text $spoken")
    }
    val vision = vm.visionAvailable
    fun pickImages() {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        when {
            !vision -> vm.toast = "Dieses Modell versteht keine Bilder."
            maxImages <= 1 -> pickOne.launch(request)
            free == 0 -> vm.toast = "Höchstens $maxImages Bilder pro Nachricht."
            else -> pickMany.launch(request)
        }
    }

    // Prompt-Bibliothek: "/" am Anfang zeigt passende Prompts
    val slashQuery = if (text.startsWith("/") && !text.contains(' ') && !text.contains('\n')) text.drop(1).lowercase() else null
    val slashHits = slashQuery?.let { q -> vm.prompts.filter { it.title.contains(q) || it.text.lowercase().contains(q) }.take(6) }

    // Cursor bleibt beim Tippen, springt aber ans Ende, wenn der Text von außen kommt (Prompt, Sprache …)
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    val shown = if (field.text == text) field else TextFieldValue(text, TextRange(text.length))

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Tag-Vorschläge beim Tippen (Assistent mit „Tag-Vorschläge“, z. B. Prompt-Schmied)
        val tagMode = vm.currentPreset?.tagHelper == true && de.davscha.denkwerk.data.TagLibrary.loaded
        val sep = maxOf(text.lastIndexOf(','), text.lastIndexOf('\n'))
        val token = text.substring(sep + 1).trimStart()
        val tagHits = if (tagMode && slashHits == null && token.length >= 2 && !token.endsWith(" "))
            de.davscha.denkwerk.data.TagLibrary.suggest(token, de.davscha.denkwerk.data.TagField.ANY, 12) else emptyList()
        if (tagHits.isNotEmpty()) {
            Row(
                Modifier
                    .widthIn(max = 820.dp)
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tagHits.forEach { t ->
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                            .clickable {
                                val before = if (sep >= 0) text.substring(0, sep + 1) + " " else ""
                                onText(before + t.name + ", ")
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(t.name, fontSize = 13.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (t.count >= 1_000_000) "${t.count / 1_000_000}M" else if (t.count >= 1000) "${t.count / 1000}k" else "${t.count}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (slashHits != null) {
            Column(
                Modifier
                    .widthIn(max = 820.dp)
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                    .padding(vertical = 6.dp),
            ) {
                if (slashHits.isEmpty()) {
                    Text("Kein Prompt gefunden", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
                slashHits.forEach { p ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onText(p.text) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text("/${p.title}", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Mint)
                        Text(p.text.replace('\n', ' ').trim(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(
                    "Bibliothek verwalten …",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().clickable { onText(""); vm.openPrompts() }.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        Column(
            Modifier
                .widthIn(max = 820.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(6.dp),
        ) {
            if (editing) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Edit, null, tint = Mint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Nachricht bearbeiten – danach antwortet die KI neu", fontSize = 12.sp, color = Mint, modifier = Modifier.weight(1f))
                    IconButton(onClick = onCancelEdit, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Abbrechen", modifier = Modifier.size(16.dp)) }
                }
            }
            if (docs.isNotEmpty() || readingDoc) {
                Row(
                    Modifier.padding(start = 8.dp, top = 6.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    docs.forEach { d -> DocChip(d, onRemove = { onDocs(docs - d) }) }
                    if (readingDoc) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp), color = Mint)
                        Text("Lese Dokument …", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (images.isNotEmpty()) {
                Row(
                    Modifier.padding(start = 8.dp, top = 6.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    images.forEach { path ->
                        Box {
                            AsyncImage(
                                model = File(path),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
                            )
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.background)
                                    .clickable { onImages(images - path) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Default.Close, "Bild entfernen", modifier = Modifier.size(14.dp)) }
                        }
                    }
                    // Weiteres Bild hinzufügen, solange Platz ist
                    if (maxImages > 1 && free > 0) {
                        Box(
                            Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                                .clickable { pickImages() },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.Add, "Weiteres Bild") }
                    }
                    if (maxImages > 1) {
                        Text("${images.size}/$maxImages", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                // 🌐 – Websuche für diesen Chat erzwingen
                IconButton(onClick = {
                    vm.forceWeb = !vm.forceWeb
                    vm.toast = if (vm.forceWeb) "🌐 Websuche an – jede Nachricht wird vorher im Netz recherchiert" else "🌐 Websuche wieder nach Einstellung"
                }, enabled = !editing, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Public,
                        "Websuche erzwingen",
                        tint = if (vm.forceWeb) Mint else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // "+" – Bild oder Dokument anhängen
                Box {
                    IconButton(onClick = { attachMenu = true }, enabled = !editing) { Icon(Icons.Default.Add, "Anhängen") }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Bild")
                                    Text(
                                        when {
                                            !vision -> "Modell versteht keine Bilder"
                                            maxImages > 1 -> "bis zu $maxImages Bilder"
                                            else -> "ein Bild"
                                        },
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.Image, null, tint = if (vision) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                            onClick = { attachMenu = false; pickImages() },
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Dokument")
                                    Text("PDF, Word, Text, Code, CSV …", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.Description, null) },
                            onClick = {
                                attachMenu = false
                                if (docs.size >= MAX_DOCS) vm.toast = "Höchstens $MAX_DOCS Dokumente pro Nachricht."
                                else pickDocs.launch(arrayOf("*/*"))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Prompt-Bibliothek") },
                            leadingIcon = { Icon(Icons.Default.Bookmarks, null) },
                            onClick = { attachMenu = false; onText("/") },
                        )
                    }
                }
                Box(Modifier.weight(1f).padding(vertical = 12.dp, horizontal = 2.dp)) {
                    if (text.isEmpty()) Text("Frag Denkwerk … ( / für Prompts)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                    BasicTextField(
                        value = shown,
                        onValueChange = { v ->
                            field = v
                            if (v.text != text) onText(v.text)
                        },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                        cursorBrush = SolidColor(Mint),
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                when {
                    vm.generating -> RoundButton(Icons.Default.Stop, "Stopp", filled = true) { vm.stop() }
                    text.isBlank() && images.isEmpty() && docs.isEmpty() -> IconButton(onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Sprich mit Denkwerk …")
                        runCatching { speech.launch(intent) }.onFailure { vm.toast = "Keine Spracherkennung gefunden." }
                    }) { Icon(Icons.Default.Mic, "Spracheingabe") }
                    else -> RoundButton(Icons.Default.ArrowUpward, "Senden", filled = true, onClick = onSend)
                }
            }
        }
        if (vm.engineStatus is EngineStatus.Loading) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(12.dp), color = Mint)
                Spacer(Modifier.width(6.dp))
                Text("Modell wird geladen …", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text(
                "Denkwerk kann Fehler machen. Wichtiges bitte prüfen.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Kärtchen für ein angehängtes Dokument: Name und Länge, optional mit ✕ */
@Composable
private fun DocChip(d: DocAttachment, onRemove: (() -> Unit)?) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 10.dp, end = if (onRemove != null) 4.dp else 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Description, null, tint = Mint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(d.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
            Text(
                if (d.chars >= 1000) "${d.chars / 1000}k Zeichen" else "${d.chars} Zeichen",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Entfernen", modifier = Modifier.size(14.dp)) }
        }
    }
}

/** Höchstens so viele Dokumente pro Nachricht */
private const val MAX_DOCS = 5

/** Obergrenze für die Android-Bildauswahl (das Modell begrenzt zusätzlich). */
private const val MAX_PICK = 8

@Composable
private fun RoundButton(icon: ImageVector, label: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(4.dp)
            .size(40.dp)
            .clip(CircleShape)
            .background(if (filled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = MaterialTheme.colorScheme.background, modifier = Modifier.size(22.dp))
    }
}
