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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import de.davscha.denkwerk.data.ChatMessage
import de.davscha.denkwerk.data.Source
import de.davscha.denkwerk.engine.Accelerator
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ChatScreen(vm: AppViewModel, onMenu: (() -> Unit)?) {
    var input by remember { mutableStateOf("") }
    var images by remember { mutableStateOf<List<String>>(emptyList()) }
    var editingId by remember { mutableStateOf<String?>(null) }
    val chat = vm.currentChat
    val messages = chat?.messages.orEmpty()

    // Beim Wechsel des Chats Eingabe zurücksetzen
    LaunchedEffect(vm.currentChatId) {
        editingId = null
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(vm, onMenu)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                EmptyState(onSuggestion = { text -> vm.send(text) })
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

        InputBar(
            vm = vm,
            text = input,
            onText = { input = it },
            images = images,
            onImages = { images = it },
            editing = editingId != null,
            onCancelEdit = { editingId = null; input = "" },
            onSend = {
                val id = editingId
                if (id != null) {
                    vm.editMessage(id, input)
                    editingId = null
                } else {
                    vm.send(input, images)
                }
                input = ""
                images = emptyList()
            },
        )
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

        IconButton(onClick = { vm.newChat() }) { Icon(Icons.Default.EditNote, "Neuer Chat") }
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
                if (c.vision == true) Text("versteht Bilder", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun EmptyState(onSuggestion: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Mint),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp)) }
        Spacer(Modifier.height(16.dp))
        Text("Wobei kann ich helfen?", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            suggestions.chunked(2).forEach { row ->
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
                    UserMessage(m, canEdit = !vm.generating, onEdit = { onEdit(m) })
                } else {
                    AssistantMessage(vm, m, isLast = m.id == lastAnswerId)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserMessage(m: ChatMessage, canEdit: Boolean, onEdit: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (m.imagePaths.isNotEmpty()) {
            // Ein Bild groß, mehrere als waagerecht wischbare Reihe
            val side = if (m.imagePaths.size == 1) 180.dp else 120.dp
            Row(
                Modifier.padding(bottom = 6.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                m.imagePaths.forEach { path ->
                    AsyncImage(
                        model = File(path),
                        contentDescription = "Angehängtes Bild",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(side).clip(RoundedCornerShape(14.dp)),
                    )
                }
            }
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
    Column(Modifier.fillMaxWidth()) {
        when {
            streaming && text.isEmpty() -> TypingDots()
            m.isError -> Text(text, color = MaterialTheme.colorScheme.error, fontSize = 15.sp)
            else -> MarkdownText(text)
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
    editing: Boolean,
    onCancelEdit: () -> Unit,
    onSend: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val maxImages = vm.maxImages
    val free = (maxImages - images.size).coerceAtLeast(0)
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
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) onText(if (text.isBlank()) spoken else "$text $spoken")
    }
    val vision = vm.visionAvailable

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
                                .clickable { pickMany.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.Add, "Weiteres Bild") }
                    }
                    if (maxImages > 1) {
                        Text("${images.size}/$maxImages", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                // Bild anhängen – nur wenn das Modell Bilder versteht
                IconButton(
                    onClick = {
                        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        when {
                            !vision -> vm.toast = "Dieses Modell versteht keine Bilder."
                            maxImages <= 1 -> pickOne.launch(request)
                            free == 0 -> vm.toast = "Höchstens $maxImages Bilder pro Nachricht."
                            else -> pickMany.launch(request)
                        }
                    },
                    enabled = !editing,
                ) {
                    Icon(
                        if (vision) Icons.Default.Image else Icons.Default.Add,
                        "Bild anhängen",
                        tint = if (vision) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
                Box(Modifier.weight(1f).padding(vertical = 12.dp, horizontal = 2.dp)) {
                    if (text.isEmpty()) Text("Frag Denkwerk …", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                    BasicTextField(
                        value = text,
                        onValueChange = onText,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                        cursorBrush = SolidColor(Mint),
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                when {
                    vm.generating -> RoundButton(Icons.Default.Stop, "Stopp", filled = true) { vm.stop() }
                    text.isBlank() && images.isEmpty() -> IconButton(onClick = {
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
