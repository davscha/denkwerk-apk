package de.davscha.denkwerk.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import de.davscha.denkwerk.engine.ComfyClient
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.davscha.denkwerk.BuildConfigInfo
import de.davscha.denkwerk.data.Settings
import de.davscha.denkwerk.data.Source
import de.davscha.denkwerk.model.ModelInfo

@Composable
fun SettingsScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
            Text("Einstellungen", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DeviceModelsSection(vm)
                OllamaSection(vm)
                MemorySection(vm)
                ComfySection(vm)
                ToolsSection(vm)
                BehaviourSection(vm)
                SpeechSection(vm)
                AboutSection(vm)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Section(icon: ImageVector, title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Mint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        content()
    }
}

// ---------------------------------------------------------------------------
// Modelle auf dem Gerät
// ---------------------------------------------------------------------------

@Composable
private fun DeviceModelsSection(vm: AppViewModel) {
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }
    Section(Icons.Default.PhoneAndroid, "Modelle auf dem Gerät", "Gemma 4 über LiteRT-LM – läuft offline auf dem Grafikchip") {
        if (!vm.sharedStorage) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Modelle dauerhaft speichern", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "Mit „Zugriff auf alle Dateien“ landen Modelle und Chats im Ordner Denkwerk und bleiben nach einer " +
                        "Neuinstallation erhalten. Schon geladene Gemma-Modelle aus Character Fun werden dann mitbenutzt.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                    )
                }) { Text("Erlaubnis erteilen") }
            }
        }
        vm.internalModels.forEach { m -> DeviceModelRow(vm, m) }
        val importing = vm.importProgress
        if (importing != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp), color = Mint)
                Spacer(Modifier.width(8.dp))
                Text(importing, fontSize = 13.sp)
            }
        } else {
            OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) {
                Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Eigene .litertlm-Datei importieren")
            }
        }
    }
}

@Composable
private fun DeviceModelRow(vm: AppViewModel, m: ModelInfo) {
    val state = vm.downloads[m.id] ?: DownloadState.Missing
    val active = vm.isActive(Source.INTERNAL, m.id)
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(if (active) 2.dp else 1.dp, if (active) Mint else MaterialTheme.colorScheme.outline, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.title, fontWeight = FontWeight.Medium)
                Text("${m.description} · ${formatSize(m.sizeBytes)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (state) {
                DownloadState.Done -> {
                    if (active) {
                        Icon(Icons.Default.Check, "Aktiv", tint = Mint)
                    } else {
                        TextButton(onClick = { vm.select(ModelChoice(Source.INTERNAL, m.id, m.title, "", null)) }) { Text("Benutzen") }
                    }
                    if (m.id.startsWith("custom:")) {
                        IconButton(onClick = { vm.deleteModel(m) }) { Icon(Icons.Default.Delete, "Löschen", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                is DownloadState.Running -> TextButton(onClick = { vm.cancelDownload(m) }) { Text("Abbrechen") }
                else -> Button(
                    onClick = { vm.download(m) },
                    colors = ButtonDefaults.buttonColors(containerColor = Mint),
                ) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Laden")
                }
            }
        }
        when (state) {
            is DownloadState.Running -> {
                val p = if (state.total > 0) state.done.toFloat() / state.total else 0f
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = Mint)
                Text("${formatSize(state.done)} von ${formatSize(state.total)} – Bildschirm bleibt an", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is DownloadState.Failed -> Text(state.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            else -> {}
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "${bytes / 1024} KB"
}

// ---------------------------------------------------------------------------
// Ollama
// ---------------------------------------------------------------------------

@Composable
private fun OllamaSection(vm: AppViewModel) {
    var host by remember { mutableStateOf(vm.settings.ollamaHost) }
    var port by remember { mutableStateOf(vm.settings.ollamaPort.toString()) }
    Section(Icons.Default.Computer, "Ollama im Netzwerk", "Größere Modelle laufen auf deinem PC") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("IP-Adresse") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(110.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    vm.saveOllama(host, port.toIntOrNull() ?: 11434)
                    vm.testOllama()
                },
                enabled = !vm.ollamaBusy,
            ) { Text("Verbindung testen") }
            Spacer(Modifier.width(10.dp))
            if (vm.ollamaBusy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp), color = Mint)
        }
        // Kontextgröße: wie viel sich das Modell auf einmal merken kann (Bilder brauchen viel davon)
        var ctx by remember { mutableStateOf(vm.settings.ollamaContext) }
        Text("Kontext (Gedächtnis pro Anfrage)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(4096, 8192, 16384, 32768).forEach { size ->
                val selected = ctx == size
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) Mint else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { ctx = size; vm.settings.ollamaContext = size }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        "${size / 1024}k",
                        fontSize = 13.sp,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Text(
            "Zu wenig Kontext lässt Modelle bei langen Chats oder mehreren Bildern in Schleifen laufen. Mehr braucht mehr Grafikspeicher am PC.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (vm.ollamaStatus.isNotEmpty()) {
            Text(
                vm.ollamaStatus,
                fontSize = 12.sp,
                color = if (vm.ollamaStatus.startsWith("Verbunden")) Mint else MaterialTheme.colorScheme.error,
            )
        }
        vm.ollamaModels.forEach { m ->
            val active = vm.isActive(Source.OLLAMA, m.name)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) Mint.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { vm.select(ModelChoice(Source.OLLAMA, m.name, m.name, "", m.vision)) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                    Text(formatSize(m.sizeBytes), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (m.vision) {
                    Icon(Icons.Default.Visibility, "Versteht Bilder", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                if (active) Icon(Icons.Default.Check, "Aktiv", tint = Mint)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Gedächtnis
// ---------------------------------------------------------------------------

@Composable
private fun MemorySection(vm: AppViewModel) {
    var newFact by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    Section(Icons.Default.Psychology, "Gedächtnis", "Fakten über dich, die in jeden Chat mitgehen") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Gedächtnis benutzen", fontSize = 14.sp)
                Text(
                    "Sag im Chat „Merk dir, dass …“ – Modelle mit Tools merken sich Wichtiges auch selbst und können es mit „Vergiss …“ wieder löschen.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = vm.memoryEnabledState, onCheckedChange = { vm.setMemoryEnabled(it) })
        }
        if (vm.memory.isEmpty()) {
            Text("Noch nichts gemerkt.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        vm.memory.forEach { f ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (editing == f.id) {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        singleLine = false,
                        modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                    )
                    IconButton(onClick = { vm.updateMemory(f.id, editText); editing = null }) { Icon(Icons.Default.Check, "Speichern", tint = Mint) }
                } else {
                    Text(
                        f.text,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f).clickable { editing = f.id; editText = f.text }.padding(vertical = 10.dp),
                    )
                    IconButton(onClick = { vm.deleteMemory(f.id) }) {
                        Icon(Icons.Default.Delete, "Vergessen", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newFact,
                onValueChange = { newFact = it },
                placeholder = { Text("z. B. Hat einen Kater namens Felix") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { vm.addMemory(newFact); newFact = "" },
                enabled = newFact.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Mint),
            ) { Text("Merken") }
        }
    }
}

// ---------------------------------------------------------------------------
// Werkzeuge
// ---------------------------------------------------------------------------

@Composable
private fun ToolsSection(vm: AppViewModel) {
    var searx by remember { mutableStateOf(vm.settings.searxngUrl) }
    Section(Icons.Default.Build, "Werkzeuge", "Für Ollama-Modelle mit Tool-Unterstützung (z. B. qwen3, llama3.1)") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Werkzeuge benutzen", fontSize = 14.sp)
                Text(
                    "Websuche, Seite lesen, Bilder finden, Herunterladen (mit Rückfrage), Rechner, Uhrzeit",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = vm.toolsEnabledState, onCheckedChange = { vm.setToolsEnabled(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Nachdenken (Denk-Modelle wie qwen3)", fontSize = 14.sp)
                Text("An = gründlicher, die Gedanken erscheinen klein über der Antwort · Aus = deutlich schneller", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = vm.thinkingState, onCheckedChange = { vm.setThinking(it) })
        }
        // Websuche vor der Antwort (funktioniert mit jedem Modell, auch Gemma auf dem Gerät)
        Text("Websuche vor der Antwort", fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Text(
            "Denkwerk sucht selbst, bevor das Modell antwortet – kleine Modelle raten sonst gern aus dem Gedächtnis.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Modell entscheidet", "Bei Fragen", "Immer").forEachIndexed { i, label ->
                val selected = vm.webModeState == i
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) Mint else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { vm.setWebMode(i) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(label, fontSize = 13.sp, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        val ready = vm.engineStatus as? EngineStatus.Ready
        Text(
            when {
                ready == null -> "Kein Modell geladen."
                ready.tools -> "Das aktive Modell kann Werkzeuge benutzen ✓"
                else -> "Das aktive Modell kann keine Werkzeuge benutzen – in der Modell-Liste steht „Tools“ bei passenden Modellen."
            },
            fontSize = 12.sp,
            color = if (ready?.tools == true) Mint else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Abgesicherte Suche (wie bei DuckDuckGo)
        var safe by remember { mutableStateOf(vm.settings.safeSearch) }
        Text("Abgesicherte Suche", fontSize = 14.sp)
        Text("Anstößige (meist nicht jugendfreie) Inhalte in Web- und Bildersuche ausblenden", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Streng", "Moderat", "Aus").forEachIndexed { i, label ->
                val selected = safe == i
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) Mint else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { safe = i; vm.settings.safeSearch = i }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(label, fontSize = 13.sp, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        OutlinedTextField(
            value = searx,
            onValueChange = { searx = it; vm.settings.searxngUrl = it.trim() },
            label = { Text("Eigene SearXNG-Suche (optional)") },
            placeholder = { Text("http://192.168.178.41:8888") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Leer = DuckDuckGo (ohne PC, blockt aber gelegentlich). SearXNG braucht in der settings.yml das Format „json“.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Videosuche: Quellen filtern
        var showYt by remember { mutableStateOf(vm.settings.videoShowYoutube) }
        var showSocial by remember { mutableStateOf(vm.settings.videoShowSocial) }
        var blocked by remember { mutableStateOf(vm.settings.videoBlocked) }
        Text("Videosuche", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("YouTube-Treffer zeigen", fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = showYt, onCheckedChange = { showYt = it; vm.settings.videoShowYoutube = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Soziale Medien zeigen", fontSize = 13.sp)
                Text("TikTok, Instagram, Facebook, X, Reddit, Pinterest …", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = showSocial, onCheckedChange = { showSocial = it; vm.settings.videoShowSocial = it })
        }
        OutlinedTextField(
            value = blocked,
            onValueChange = { blocked = it; vm.settings.videoBlocked = it },
            label = { Text("Eigene Sperrliste") },
            placeholder = { Text("z. B. dailymotion.com, bitchute.com") },
            minLines = 1,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Im Chat geht auch „ohne YouTube“ oder „nur aus den Mediatheken“. Weitere Quellen: Mediatheken (ARD, ZDF, arte …), Internet Archive, PeerTube, Vimeo.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var sauce by remember { mutableStateOf(vm.settings.sauceNaoKey) }
        OutlinedTextField(
            value = sauce,
            onValueChange = { sauce = it; vm.settings.sauceNaoKey = it },
            label = { Text("SauceNAO-Schlüssel (optional)") },
            placeholder = { Text("für Anime-/Zeichnungs-Quellen") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Kostenlos auf saucenao.com → Account → api – damit findet Denkwerk Künstler, Pixiv- und Danbooru-Einträge zu Illustrationen.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Verhalten
// ---------------------------------------------------------------------------

@Composable
private fun BehaviourSection(vm: AppViewModel) {
    var prompt by remember { mutableStateOf(vm.settings.systemPrompt) }
    var temp by remember { mutableFloatStateOf(vm.settings.temperature) }
    Section(Icons.Default.Tune, "Verhalten", "Gilt ab der nächsten Nachricht") {
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it; vm.settings.systemPrompt = it },
            label = { Text("System-Prompt (Anweisung an die KI)") },
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { prompt = Settings.DEFAULT_SYSTEM_PROMPT; vm.settings.systemPrompt = prompt }) { Text("Standard wiederherstellen") }
        Text("Kreativität (Temperatur): ${"%.2f".format(temp)}", fontSize = 13.sp)
        Slider(value = temp, onValueChange = { temp = it }, onValueChangeFinished = { vm.settings.temperature = temp }, valueRange = 0f..1.5f)
        Text("Niedrig = sachlich und genau · Hoch = kreativ und abwechslungsreich", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SpeechSection(vm: AppViewModel) {
    var rate by remember { mutableFloatStateOf(vm.settings.speechRate) }
    var pitch by remember { mutableFloatStateOf(vm.settings.speechPitch) }
    Section(Icons.Default.RecordVoiceOver, "Vorlesen", "Android-Stimme für den Lautsprecher-Knopf") {
        Text("Tempo: ${"%.1f".format(rate)}×", fontSize = 13.sp)
        Slider(value = rate, onValueChange = { rate = it }, onValueChangeFinished = { vm.settings.speechRate = rate }, valueRange = 0.5f..2f)
        Text("Tonhöhe: ${"%.1f".format(pitch)}", fontSize = 13.sp)
        Slider(value = pitch, onValueChange = { pitch = it }, onValueChangeFinished = { vm.settings.speechPitch = pitch }, valueRange = 0.5f..2f)
    }
}

@Composable
private fun AboutSection(vm: AppViewModel) {
    Section(Icons.Default.Info, "Über Denkwerk", "Version ${BuildConfigInfo.versionName(LocalContext.current)}") {
        Text(
            if (vm.sharedStorage) "Chats und Modelle liegen im Ordner Denkwerk auf dem Gerät."
            else "Chats und Modelle liegen im App-Speicher (gehen bei Deinstallation verloren).",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


// ---------------------------------------------------------------------------
// Bilder malen (ComfyUI)
// ---------------------------------------------------------------------------

@Composable
private fun ComfySection(vm: AppViewModel) {
    var host by remember { mutableStateOf(vm.settings.comfyHost) }
    var port by remember { mutableStateOf(vm.settings.comfyPort.toString()) }
    val r = vm.recipe
    var aspectMenu by remember { mutableStateOf(false) }
    Section(Icons.Default.Brush, "Bilder malen (ComfyUI)", "MOSS Image auf dem PC – versteht Sätze, Wortgruppen und JSON") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = host, onValueChange = { host = it }, label = { Text("IP-Adresse") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(110.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { vm.saveComfy(host, port.toIntOrNull() ?: 8080); vm.refreshComfy() },
                enabled = !vm.comfyBusy,
            ) { Text("Verbinden & prüfen") }
            Spacer(Modifier.width(10.dp))
            if (vm.comfyBusy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp), color = Mint)
        }
        if (vm.comfyStatus.isNotEmpty()) {
            Text(vm.comfyStatus, fontSize = 12.sp, color = if (vm.comfyStatus.startsWith("Verbunden")) Mint else MaterialTheme.colorScheme.error)
        }
        Text(
            "Benutzt: ${r.unet} (diffusion_models), ${r.lora} (loras), ${r.clip} (text_encoders), ${r.vae} (vae)",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Schritte: ${r.steps}", fontSize = 13.sp)
        Slider(value = r.steps.toFloat(), onValueChange = { vm.updateRecipe(r.copy(steps = it.toInt())) }, valueRange = 1f..30f, steps = 28)
        Text("CFG: ${"%.1f".format(r.cfg)}", fontSize = 13.sp)
        Slider(value = r.cfg, onValueChange = { vm.updateRecipe(r.copy(cfg = (it * 2).toInt() / 2f)) }, valueRange = 1f..10f)

        // Seitenverhältnis und Megapixel (ResolutionSelector)
        Text("Seitenverhältnis", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
        Box {
            OutlinedButton(onClick = { aspectMenu = true }) {
                Text(r.aspect, maxLines = 1)
                Icon(Icons.Default.KeyboardArrowDown, null)
            }
            DropdownMenu(expanded = aspectMenu, onDismissRequest = { aspectMenu = false }) {
                vm.comfyAspects.forEach { a ->
                    DropdownMenuItem(text = { Text(a) }, onClick = { aspectMenu = false; vm.updateRecipe(r.copy(aspect = a)) })
                }
            }
        }
        Text("Megapixel: ${"%.1f".format(r.megapixels)}", fontSize = 13.sp)
        Slider(value = r.megapixels, onValueChange = { vm.updateRecipe(r.copy(megapixels = (it * 2).toInt() / 2f)) }, valueRange = 0.5f..4f, steps = 6)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = r.sampler, onValueChange = { vm.updateRecipe(r.copy(sampler = it.trim())) }, label = { Text("Sampler") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = r.scheduler, onValueChange = { vm.updateRecipe(r.copy(scheduler = it.trim())) }, label = { Text("Scheduler") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Prompt verbessern", fontSize = 14.sp)
                Text(
                    if (vm.promptEnhanceState) "Die KI schmückt deinen Wunsch aus (Details, Licht, Stil, Kamera)"
                    else "Die KI übersetzt deinen Wunsch nur wörtlich ins Englische",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = vm.promptEnhanceState, onCheckedChange = { vm.setPromptEnhance(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Prompt-Archiv benutzen", fontSize = 14.sp)
                Text(
                    "archive.json (${vm.archiveCount} Einträge) hat beim Malen Vorrang: Figuren, Haare, Outfits, Posen, Orte und Kameras " +
                        "per Name, ID oder Kategorie („Pin-up-Pose“, „draußen“); fehlende Haare und Kleidung werden ergänzt. " +
                        "Eigene Fassung: Denkwerk/archive.json.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = vm.archiveEnabledState, onCheckedChange = { vm.setArchiveEnabled(it) })
        }
        TextButton(onClick = { vm.reloadArchive() }) { Text("Archiv neu laden") }
        Text(
            "Tipp: „!male …“ schickt deinen Text unverändert an ComfyUI – ganz ohne KI dazwischen.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Vor jedem Bild wird das Ollama-Modell entladen, damit ComfyUI genug Grafikspeicher hat – es lädt bei der nächsten Frage von selbst wieder.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

