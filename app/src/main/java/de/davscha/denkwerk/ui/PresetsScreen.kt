package de.davscha.denkwerk.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.davscha.denkwerk.data.Preset

/** Liste aller Assistenten – oder der Editor, wenn einer offen ist. */
@Composable
fun PresetsScreen(vm: AppViewModel) {
    val editing = vm.editingPreset
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
            Text(
                when {
                    editing == null -> "Assistenten"
                    vm.presets.any { it.id == editing.id } -> "Assistent bearbeiten"
                    else -> "Neuer Assistent"
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (editing == null) PresetList(vm) else PresetEditor(vm, editing)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun PresetList(vm: AppViewModel) {
    Text(
        "Eigene Assistenten mit fester Anweisung, Temperatur und Lieblingsmodell – wie „GPTs“ bei ChatGPT.",
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = { vm.editPreset(null) }, colors = ButtonDefaults.buttonColors(containerColor = Mint)) {
        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Assistent erstellen")
    }
    vm.presets.forEach { p ->
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable { vm.newChat(p.id) }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PresetAvatar(p.emoji, 44)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.SemiBold)
                if (p.description.isNotBlank()) {
                    Text(p.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (p.modelKey.isNotBlank()) {
                    Text("Modell: ${p.modelKey.substringAfter(':')}", fontSize = 11.sp, color = Mint)
                }
            }
            IconButton(onClick = { vm.newChat(p.id) }) { Icon(Icons.Default.ChatBubbleOutline, "Chat starten") }
            IconButton(onClick = { vm.editPreset(p) }) { Icon(Icons.Outlined.Edit, "Bearbeiten") }
        }
    }
}

/** Rundes Feld mit dem Emoji des Assistenten */
@Composable
fun PresetAvatar(emoji: String, sizeDp: Int) {
    Box(
        Modifier.size(sizeDp.dp).clip(RoundedCornerShape((sizeDp / 3).dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) { Text(emoji, fontSize = (sizeDp / 2).sp) }
}

private val emojiChoices = listOf("✨", "💻", "🎓", "🌍", "💡", "🎨", "📚", "🧠", "🎮", "🎬", "🎵", "📝", "🍳", "🐱", "🔬", "⚙️")

@Composable
private fun PresetEditor(vm: AppViewModel, initial: Preset) {
    // Neuer KI-Entwurf (presetDraftVersion) setzt alle Felder neu
    val draft = vm.presetDraftVersion
    var name by remember(initial.id, draft) { mutableStateOf(initial.name) }
    var emoji by remember(initial.id, draft) { mutableStateOf(initial.emoji) }
    var description by remember(initial.id, draft) { mutableStateOf(initial.description) }
    var prompt by remember(initial.id, draft) { mutableStateOf(initial.systemPrompt) }
    var temp by remember(initial.id, draft) { mutableFloatStateOf(initial.temperature) }
    var modelKey by remember(initial.id, draft) { mutableStateOf(initial.modelKey) }
    var starters by remember(initial.id, draft) { mutableStateOf(initial.starters.joinToString("\n")) }
    var notes by remember(initial.id) { mutableStateOf("") }
    var modelMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val exists = vm.presets.any { it.id == initial.id }

    // Mit KI-Hilfe: Stichpunkte rein, fertiger Assistent raus
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Mint.copy(alpha = 0.10f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("✨ Mit KI erstellen", fontWeight = FontWeight.SemiBold)
        Text(
            "Ein paar Stichpunkte reichen – das aktive Modell schreibt Name, Symbol, Anweisung und Starter. Danach kannst du alles anpassen.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            placeholder = { Text("z. B. Gitarrenlehrer, geduldig, Anfänger, Akkorde, kurze Übungen") },
            minLines = 2,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    // bisherige Eingaben behalten, falls die KI etwas nicht liefert
                    vm.editingPreset = initial.copy(
                        name = name, emoji = emoji, description = description, systemPrompt = prompt,
                        temperature = temp, modelKey = modelKey,
                        starters = starters.lines().filter { it.isNotBlank() },
                    )
                    vm.draftPresetWithAi(notes)
                },
                enabled = notes.isNotBlank() && !vm.presetAiBusy && !vm.generating,
                colors = ButtonDefaults.buttonColors(containerColor = Mint),
            ) { Text(if (vm.presetAiBusy) "Schreibe …" else "Entwurf erstellen") }
            if (vm.presetAiBusy) {
                Spacer(Modifier.width(10.dp))
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp), color = Mint)
            }
        }
    }

    // Symbol
    Text("Symbol", fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        emojiChoices.chunked(8).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { e ->
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(2.dp, if (e == emoji) Mint else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                            .clickable { emoji = e },
                        contentAlignment = Alignment.Center,
                    ) { Text(e, fontSize = 20.sp) }
                }
            }
        }
    }

    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        value = description,
        onValueChange = { description = it },
        label = { Text("Kurzbeschreibung") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = prompt,
        onValueChange = { prompt = it },
        label = { Text("Anweisung an die KI (System-Prompt)") },
        placeholder = { Text("z. B. Du bist ein geduldiger Gitarrenlehrer …") },
        minLines = 4,
        maxLines = 12,
        modifier = Modifier.fillMaxWidth(),
    )
    Text("Leer = Standard-Anweisung aus den Einstellungen", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Text("Kreativität (Temperatur): ${"%.2f".format(temp)}", fontSize = 13.sp)
    Slider(value = temp, onValueChange = { temp = it }, valueRange = 0f..1.5f)

    // Lieblingsmodell
    Text("Lieblingsmodell", fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Box {
        OutlinedButton(onClick = { modelMenu = true }) {
            Text(if (modelKey.isBlank()) "Keins – aktuelles Modell behalten" else modelKey.substringAfter(':'))
            Icon(Icons.Default.KeyboardArrowDown, null)
        }
        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
            DropdownMenuItem(text = { Text("Keins – aktuelles Modell behalten") }, onClick = { modelKey = ""; modelMenu = false })
            vm.modelChoices.forEach { c ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(c.title)
                            Text(c.subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { modelKey = "${c.source}:${c.id}"; modelMenu = false },
                )
            }
        }
    }

    OutlinedTextField(
        value = starters,
        onValueChange = { starters = it },
        label = { Text("Gesprächs-Starter (einer pro Zeile)") },
        minLines = 2,
        maxLines = 6,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = {
                vm.savePreset(
                    initial.copy(
                        name = name.trim().ifEmpty { "Assistent" },
                        emoji = emoji,
                        description = description.trim(),
                        systemPrompt = prompt.trim(),
                        temperature = temp,
                        modelKey = modelKey,
                        starters = starters.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(4),
                    )
                )
            },
            colors = ButtonDefaults.buttonColors(containerColor = Mint),
        ) { Text("Speichern") }
        TextButton(onClick = { vm.editingPreset = null }) { Text("Abbrechen") }
        Spacer(Modifier.weight(1f))
        if (exists) TextButton(onClick = { confirmDelete = true }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Assistent löschen?") },
            text = { Text("„$name“ wird gelöscht. Bestehende Chats bleiben erhalten und laufen mit der Standard-Anweisung weiter.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deletePreset(initial.id) }) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}
