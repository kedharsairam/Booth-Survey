package com.kraft.voterlist.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kraft.voterlist.data.Lists

/**
 * The three vocabularies, and nothing else.
 *
 * There is no "restore the bundled roll" here, and no roll details either. The first was a
 * button whose worst outcome was somebody tapping through a confirmation and losing a day of
 * work; the second was a filename and a count, which is the build's business and not
 * something a surveyor standing at a door has any use for.
 *
 * Updating the roll happens outside the app: export the survey, and the new roll is merged
 * into the seed and shipped as a new APK.
 *
 * The lists also fill themselves from use, so this screen is for tidying up and for getting
 * ahead -- not a gate to pass before the first house of the morning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SurveyViewModel, s: UiState, onImport: () -> Unit, onExportTemplate: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.lg),
        ) {
            BoothCard(vm, s, onImport, onExportTemplate)
            Spacer(Modifier.height(Space.md))

            // Heading, then the sentence, then the three lists. It used to sit at the very top
            // of the screen introducing lists that were nowhere near it, which read as though
            // it were talking about the import card underneath.
            Text("Shared lists", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Space.xs))
            Text(
                "The choices offered in the dropdown on every voter. They are shared by every " +
                    "booth. Type a value on a voter and it is added here by itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.md))

            Lists.Kind.entries.forEach { kind ->
                ListEditor(vm, s, kind)
                Spacer(Modifier.height(Space.md))
            }
            Spacer(Modifier.height(Space.xl))
    }
}

@Composable
private fun ListEditor(vm: SurveyViewModel, s: UiState, kind: Lists.Kind) {
    var adding by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<String?>(null) }
    val values = s.lists.forKind(kind)

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(Space.lg)) {
            Text(kind.title, style = MaterialTheme.typography.titleMedium)
            Text(
                kind.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Space.md))

            if (values.isEmpty()) {
                Surface(
                    Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        "Nothing here yet. Type one on a voter and it will appear.",
                        Modifier.padding(Space.md),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                values.forEach { value ->
                    val used = vm.countWith(kind, value)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = Space.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(value, style = MaterialTheme.typography.bodyLarge)
                            if (used > 0) {
                                Text(
                                    "on $used ${if (used == 1) "voter" else "voters"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { removing = value }, modifier = Modifier.size(48.dp)) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                "Remove $value",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(Space.sm))
            if (adding) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Add to ${kind.title.lowercase()}") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        textStyle = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.width(Space.sm))
                    IconButton(
                        onClick = { vm.addListValue(kind, text); text = ""; adding = false },
                        enabled = text.isNotBlank(),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Default.Check, "Add", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(
                        onClick = { adding = false; text = "" },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Default.Close, "Cancel", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                TextButton(onClick = { adding = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.xs))
                    Text("Add one", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    removing?.let { value ->
        val used = vm.countWith(kind, value)
        val body = if (used == 0) {
            "It will no longer be offered in the dropdown. Nobody has it yet."
        } else {
            val tail = if (kind == Lists.Kind.CASTE) {
                ""
            } else {
                "\n\nA value already recorded on a voter is kept for address and party. Only " +
                    "caste is cleared, because caste is the one the roll itself defines."
            }
            "It will no longer be offered in the dropdown, and it will be cleared from $used " +
                "${if (used == 1) "voter" else "voters"} who already have it." + tail
        }
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove $value?") },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = { vm.removeListValue(kind, value); removing = null }) {
                    Text("Remove", color = Status.bad)
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text("Keep") }
            },
        )
    }
}

/**
 * The booth this app is working on, and the two things you can do to it.
 *
 * Import is here rather than on Home because it is a setup job, not a daily one, and because
 * it is the one action in the app that can add or change a lot of records at once. It belongs
 * somewhere you go deliberately.
 */
@Composable
private fun BoothCard(vm: SurveyViewModel, s: UiState, onImport: () -> Unit, onExportTemplate: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(Space.lg)) {
            Text("Voters list", style = MaterialTheme.typography.titleMedium)
            Text(
                if (s.hasBooth) {
                    "${s.booth.size} people in ${s.booth.displayTitle}" +
                        if (s.booth.mandal.isNotBlank()) ", ${s.booth.mandal}" else ""
                } else {
                    "Nothing imported yet."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.md))

            if (s.hasBooth) {
                OutlinedTextField(
                    value = s.booth.label,
                    onValueChange = { vm.renameBooth(it) },
                    label = { Text("Name shown in the app") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    textStyle = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(Space.sm))
            }

            // Filled, not tonal: this is the one thing somebody opening Settings to start
            // work actually came for. Tonal put it at the same visual weight as the outline
            // button under it, which said "these are two equals" and is not true.
            Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FileUpload, null)
                Spacer(Modifier.width(Space.sm))
                Text("Import voters list")
            }
            Spacer(Modifier.height(Space.xs))
            Text(
                "A spreadsheet with four columns: serial, voter id, name, house number. " +
                    "Importing a booth that is already here updates it without touching " +
                    "anything you have entered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Space.md))
            OutlinedButton(onClick = { onExportTemplate() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.TableChart, null)
                Spacer(Modifier.width(Space.sm))
                Text("Export blank template")
            }
            Spacer(Modifier.height(Space.xs))
            Text(
                "Four empty columns with the right headings, for somebody starting a list " +
                    "from their own roll.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
