package de.davscha.denkwerk.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import de.davscha.denkwerk.data.SavedPrompt

/** Prompt-Bibliothek: gespeicherte Prompts, im Chat mit „/name“ abrufbar. */
@Composable
fun PromptsScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
            Text("Prompt-Bibliothek", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
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
                Text(
                    "Tippe im Chat „/“ und den Namen – der Prompt landet im Eingabefeld. Eigene Nachrichten kannst du per langem Druck „Als Prompt speichern“.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { vm.editingPrompt = SavedPrompt() }, colors = ButtonDefaults.buttonColors(containerColor = Mint)) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Prompt hinzufügen")
                }
                vm.prompts.forEach { p ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable { vm.editingPrompt = p }
                            .padding(14.dp),
                    ) {
                        Text("/${p.title}", fontWeight = FontWeight.SemiBold, color = Mint)
                        Text(
                            p.text.trim(),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    vm.editingPrompt?.let { p -> PromptDialog(vm, p) }
}

@Composable
private fun PromptDialog(vm: AppViewModel, p: SavedPrompt) {
    var title by remember(p.id) { mutableStateOf(p.title) }
    var text by remember(p.id) { mutableStateOf(p.text) }
    val exists = vm.prompts.any { it.id == p.id }
    AlertDialog(
        onDismissRequest = { vm.editingPrompt = null },
        title = { Text(if (exists) "Prompt bearbeiten" else "Neuer Prompt") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Name (für /name)") },
                    prefix = { Text("/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Prompt-Text") },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.savePrompt(p.copy(title = title, text = text)) },
                enabled = text.isNotBlank(),
            ) { Text("Speichern") }
        },
        dismissButton = {
            Row {
                if (exists) TextButton(onClick = { vm.deletePrompt(p.id) }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { vm.editingPrompt = null }) { Text("Abbrechen") }
            }
        },
    )
}
