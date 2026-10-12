package com.kraft.voterlist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imeNestedScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
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
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun VoterEditScreen(vm: SurveyViewModel, s: UiState) {
    val v = s.editing ?: return
    var confirmingDelete by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val scroll = rememberScrollState()
    val fields = rememberFieldScroller(scroll)
    // The order fields are written in, used by the keyboard's Next.
    val putAway = { focus.clearFocus(); Unit }
    val family = s.familyNamed(v.familyId)
    val serialDuplicated = !s.creatingVoter && v.serialIsDuplicated(s.voters)
    val idDuplicated = !s.creatingVoter && v.voterIdIsDuplicated(s.voters)

    Scaffold(
        // The primary action on the create form is pinned rather than sitting at the bottom of
        // the scroll. The form is taller than any phone, and an "Add this person" you have to
        // scroll to find is an action nobody finds; worse, it made the confirmation that
        // everything else now depends on the easiest button in the app to miss.
        bottomBar = {
            if (s.creatingVoter) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = KraftSpacing.Spacing2,
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
                // One line only. Who this is lives in the hero header below, which shows the
                // serial the way the list rows do; the bar is just a way back.
                title = {
                    Text(
                        if (s.creatingVoter) "New voter" else "Edit voter",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                },
                // Removing somebody is rare, and it is not undone. It used to be a red
                // full-width button at the foot of the form, which is the one place a
                // scrolling thumb lands by accident -- and it asked to be confirmed, which
                // is what you do for a button people should not hit carelessly. Here it is
                // chosen on purpose, and still confirmed.
                actions = {
                    if (!s.creatingVoter) {
                        IconButton(onClick = { confirmingDelete = true }) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                "Remove this voter",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { pad ->
        // The keyboard's padding goes on the OUTER column, not on the scroller.
        //
        // On the scroller it pads the content instead of shrinking the viewport, so the
        // scrollable still believes it runs the full height of the screen -- and a field
        // asking to be brought into view is told it already is. Phone number, the last field
        // and the one most likely to be covered, stayed behind the keyboard with its cursor
        // invisible. With the padding outside, the viewport genuinely ends above the keyboard
        // and the scroll the field asks for actually brings it into sight.
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = Space.lg),
        ) {
          Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .imeNestedScroll(),
          ) {
            // The serial, big enough to read at arm's length, then the name with the status
            // docked right -- the same arrangement as the list rows, so the person found as
            // "serial 312" is recognisably the same person when their form opens.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Space.md)
                    // One announcement for the whole header, like the list rows. Unmerged, a
                    // screen reader walks the serial, the name and the status as separate
                    // stops to answer "who is this and have I been".
                    .semantics(mergeDescendants = true) {
                        contentDescription = buildString {
                            append(v.name.ifBlank { "No name" })
                            append(". Serial ${v.serial}")
                            if (v.voterId.isNotBlank()) append(". Voter ID ${v.voterId}")
                            if (v.house.isNotBlank()) append(". Door ${v.house}")
                            if (!s.creatingVoter && family.isNotEmpty()) append(". In $family")
                            if (v.caste.isNotBlank()) append(". ${v.caste}")
                            if (v.party.isNotBlank()) append(". ${v.party}")
                            if (v.visited) append(". Visited")
                            if (v.skipped) append(". Skipped")
                            if (idDuplicated) append(". This voter ID appears twice")
                            if (serialDuplicated) append(". This serial appears twice")
                            if (!s.creatingVoter && v.notInLatestFile) {
                                append(". Not in the latest file")
                            }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    v.serial.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (v.isDone) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.width(serialColumnWidth()),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(Space.sm))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            v.name.ifBlank { "(no name)" },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        StatusPill(v)
                    }
                    Text(
                        when {
                            s.creatingVoter -> "Not added yet"
                            family.isNotEmpty() -> family
                            else -> "Not in a family"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (!s.creatingVoter && serialDuplicated) {
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
                        "on it any more."
                )
            }

            SectionTitle("Identity")
            FormCard {
                RowField("Serial", v.serial.takeIf { it != 0 }?.toString() ?: "", vm, "serial",
                    keyboard = KeyboardType.Number, imeAction = ImeAction.Next, scroller = fields)
                Rule()
                RowField("Full name", v.name, vm, "name", imeAction = ImeAction.Next, scroller = fields)
                Rule()
                RowField("Voter ID", v.voterId, vm, "voterId", imeAction = ImeAction.Next, scroller = fields)
            }

            SectionTitle("House")
            FormCard {
                RowField("Door number", v.house, vm, "house", imeAction = ImeAction.Next, scroller = fields)
                Rule()
                ListRow("Current address", v.address, vm, "address", Lists.Kind.ADDRESS,
                    s.lists.addresses, imeAction = ImeAction.Next, scroller = fields)
            }

            SectionTitle("Survey")
            FormCard {
                ListRow("Caste", v.caste, vm, "caste", Lists.Kind.CASTE, s.lists.castes,
                    imeAction = ImeAction.Next, scroller = fields)
                Rule()
                ListRow("Sub caste", v.subCaste, vm, "subCaste", Lists.Kind.SUBCASTE,
                    s.lists.subCastes, imeAction = ImeAction.Next, scroller = fields)
                Rule()
                ListRow("Party", v.party, vm, "party", Lists.Kind.PARTY, s.lists.parties,
                    imeAction = ImeAction.Next, scroller = fields)
                Rule()
                RowField("Phone number", v.phone, vm, "phone", keyboard = KeyboardType.Phone,
                    imeAction = ImeAction.Done, scroller = fields)
            }

            if (!s.creatingVoter) {
                SectionTitle("Status")
                FormCard {
                    StatusChoice(v, vm)
                }
                if (v.skipped) {
                    Text(
                        "Skipped still needs coming back to.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Status.pending,
                        modifier = Modifier.padding(start = Space.lg, top = Space.sm),
                    )
                }
            }

            Spacer(Modifier.height(Space.xxl))
          }
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
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        // Marked as a heading. A heading *style* carries no accessibility meaning, so without
        // this the app had no headings at all and a screen reader's heading list was empty:
        // there was no way to jump to "Survey" without swiping through every field.
        modifier = Modifier
            .padding(top = Space.xl)
            .semantics { heading() },
    )
}

@Composable
private fun Warning(text: String) {
    Card(
        Modifier.fillMaxWidth().padding(top = Space.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text,
            Modifier.padding(Space.lg),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
