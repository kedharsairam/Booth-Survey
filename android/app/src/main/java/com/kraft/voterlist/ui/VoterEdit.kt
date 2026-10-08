package com.kraft.voterlist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kraft.voterlist.data.Lists
import com.kraft.ui.tokens.KraftSpacing

/**
 * One voter. Every field editable, including the serial and the voter ID.
 *
 * Nothing is locked, because the roll this was built from is a draft and the finalized list
 * may renumber people. Editing a number is two seconds; redoing an extraction is an afternoon.
 *
 * The same screen creates and edits, with one difference: a new voter is a **draft** that does
 * not exist until it is confirmed. Opening the form creates nothing, and backing out of it
 * leaves nothing behind. Creating on open would leave a nameless row in the roll every time
 * somebody opened the form and changed their mind, and those rows are invisible in a list of
 * 1129 and impossible to clear in bulk.
 *
 * There is no note field. It was in an early version and was never used: a free-text box on
 * every one of 1129 cards is a box nobody fills in, and an empty box on a printed form is a
 * box somebody reads as a heading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoterEditScreen(vm: SurveyViewModel, s: UiState) {
    val v = s.editing ?: return
    var confirmingDelete by remember { mutableStateOf(false) }
    val family = s.familyNamed(v.familyId)

    Scaffold(
        // The primary action on the create form is pinned rather than sitting at the bottom of
        // the scroll. The form is taller than any phone, and an "Add this person" you have to
        // scroll to find is an action nobody finds; worse, it made the confirmation that
        // everything else now depends on the easiest button in the app to miss.
        bottomBar = {
            if (s.creatingVoter) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp, // @kraft-lint-ignore spacing.no-raw-dp, spacing.rhythm — card tonal elevation, no shared token
                    // Lifted above the keyboard. A Scaffold does not do this by default, and a
                    // pinned action sitting behind the IME is worse than one at the bottom of a
                    // scroll: it looks present and cannot be pressed.
                    modifier = Modifier.imePadding(),
                ) {
                    Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
                        Button(
                            onClick = { vm.confirmCreateVoter() },
                            modifier = Modifier.fillMaxWidth().height(BoothMetrics.FieldHeight),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(Space.sm))
                            Text("Add this person")
                        }
                        Text(
                            "Nothing is added until you press this. Going back leaves the list " +
                                "untouched.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Space.xs),
                        )
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.closeVoter() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            if (s.creatingVoter) "New voter" else v.name.ifBlank { "(no name)" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        Text(
                            when {
                                s.creatingVoter -> "Not added yet"
                                family.isNotEmpty() -> family
                                else -> "Not in a family"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.lg),
        ) {
            if (!s.creatingVoter && v.serialIsDuplicated(s.voters)) {
                Warning(
                    "Another voter also has serial ${v.serial}. Serials should be unique, but a " +
                        "duplicate is allowed -- fix it when you have the final list."
                )
            }
            if (!s.creatingVoter && v.notInLatestFile) {
                Warning(
                    "${v.name.ifBlank { "This person" }} is not in the latest sheet imported for " +
                        "this booth. They are still on the list, because deleting is worse than " +
                        "being wrong."
                )
                TextButton(onClick = { vm.keepAnyway(v.id) }) { Text("Keep them anyway") }
            }
            if (!s.creatingVoter && v.houseIsSuspect) {
                Warning(
                    "\"${v.house}\" does not look like a door number in this ward. Nothing depends " +
                        "on it any more, but it will sort oddly in the booklet."
                )
            }

            Spacer(Modifier.height(Space.sm))
            Field("Serial", v.serial.takeIf { it != 0 }?.toString() ?: "", vm, "serial", KeyboardType.Number)
            Field("Full name", v.name, vm, "name")
            Field("Voter ID", v.voterId, vm, "voterId")
            Field("Door number", v.house, vm, "house")

            ListField("Current address", v.address, vm, "address", Lists.Kind.ADDRESS, s.lists.addresses)
            ListField("Caste", v.caste, vm, "caste", Lists.Kind.CASTE, s.lists.castes)
            Field("Sub caste", v.subCaste, vm, "subCaste")
            ListField("Party", v.party, vm, "party", Lists.Kind.PARTY, s.lists.parties)

            Field("Phone number", v.phone, vm, "phone", KeyboardType.Phone)

            Spacer(Modifier.height(Space.lg))
            if (!s.creatingVoter) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.md), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { vm.toggleVisited() },
                        modifier = Modifier.weight(1f).height(BoothMetrics.FieldHeight),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(if (v.visited) "Visited" else "Mark visited")
                    }
                    OutlinedButton(
                        onClick = { vm.toggleSkipped() },
                        modifier = Modifier.weight(1f).height(BoothMetrics.FieldHeight),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(if (v.skipped) "Skipped" else "Skip")
                    }
                }
                if (v.skipped) {
                    Spacer(Modifier.height(Space.sm))
                    Text(
                        "Skipped still needs coming back to.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Status.pending,
                    )
                }
                Spacer(Modifier.height(Space.xl))
                TextButton(
                    onClick = { confirmingDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Remove this voter", color = Status.bad)
                }
            }
            Spacer(Modifier.height(Space.xxl))
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Remove ${v.name.ifBlank { "this voter" }}?") },
            text = {
                Text(
                    "They come off the list for good. Their family, if they are in one, keeps " +
                        "whoever else is in it.\n\nThis cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteVoter(v.id); confirmingDelete = false }) {
                    Text("Remove", color = Status.bad)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun Warning(text: String) {
    Card(
        Modifier.fillMaxWidth().padding(top = Space.sm),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text,
            Modifier.padding(Space.md),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    vm: SurveyViewModel,
    field: String,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { vm.setField(field, it) },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(top = Space.md),
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        textStyle = MaterialTheme.typography.bodyLarge,
    )
}

/**
 * A field backed by one of the managed lists, which also accepts a value not on the list.
 *
 * Typing a new one adds it, so the vocabulary grows from what is actually used. That is the
 * opposite of making somebody curate a list before they can start work.
 */
@Composable
private fun ListField(
    label: String,
    value: String,
    vm: SurveyViewModel,
    field: String,
    kind: Lists.Kind,
    options: List<String>,
) {
    var open by remember { mutableStateOf(false) }

    Box(Modifier.padding(top = Space.md)) {
        OutlinedTextField(
            value = value,
            onValueChange = { vm.setField(field, it) },
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            textStyle = MaterialTheme.typography.bodyLarge,
            // The arrow lives *inside* the field. Sitting beside it, it took 48dp off the field's
            // width, so every dropdown on the form came out narrower than the plain fields
            // above it and the whole form looked misaligned. This way one kind of field draws
            // one width.
            //
            // Still a plain Box with a DropdownMenu, not ExposedDropdownMenuBox. That widget is
            // built around a text field as its anchor and moves focus to it when the menu opens;
            // anchored to an IconButton its FocusRequester was never initialised and it threw
            // "FocusRequester is not initialized", which killed the app the first time anybody
            // tapped the arrow.
            trailingIcon = {
                IconButton(onClick = { open = true }) {
                    Icon(
                        Icons.Default.ArrowDropDown,
                        "Choose $label",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            },
        )
        run {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    if (options.isEmpty()) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Nothing on this list yet",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            onClick = { open = false },
                        )
                    }
                    options.forEach { o ->
                        DropdownMenuItem(
                            text = { Text(o, style = MaterialTheme.typography.bodyLarge) },
                            onClick = {
                                vm.setField(field, o)
                                vm.learnValue(kind, o)
                                open = false
                            },
                        )
                    }
                }
            }
    }
}
