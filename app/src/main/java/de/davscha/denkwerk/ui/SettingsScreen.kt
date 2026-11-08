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
