package com.kraft.voterlist.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kraft.voterlist.data.Lists
import com.kraft.ui.tokens.KraftSpacing

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
fun SettingsScreen(vm: SurveyViewModel, s: UiState, onImport: () -> Unit) {
    val scroll = rememberScrollState()
    val fields = rememberFieldScroller(scroll)
    // One list open at a time. The three vocabularies used to sit fully open one under
    // the other, which pushed everything else off the screen for content that is almost
    // never edited -- the lists fill themselves from what is typed on voters.
    var expanded: Lists.Kind? by remember { mutableStateOf(null) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .imePadding()
            // Clearance for the floating tab bar. Without it the last line of this screen
            // sat flush against the bar with no gap at all, which read as "there is more
            // below" on a screen that had ended.
            .padding(horizontal = Space.lg)
            .padding(bottom = Space.xxl + KraftSpacing.Spacing64),
        ) {
            // One card about the roll, not a card per button. "Export blank template" had a
            // card of its own holding one outline button and 100dp of nothing, which gave a
            // rarely-used action the same visual weight as the one people open Settings for.
            BoothCard(vm, s, onImport, fields)
            Spacer(Modifier.height(Space.xl))

            Section(
                "Shared lists",
                "The choices offered in the dropdown on every voter, shared by every booth. " +
                    "Type a value on a voter and it is added here by itself.",
            )
            Spacer(Modifier.height(Space.md))

            Lists.Kind.entries.forEach { kind ->
                ListEditor(
                    vm, s, kind, fields,
                    expanded = expanded == kind,
                    onToggle = { expanded = if (expanded == kind) null else kind },
                )
                Spacer(Modifier.height(Space.sm))
            }

            Spacer(Modifier.height(Space.md))
            Text(
                "Booth Survey works fully offline. Nothing on this phone is uploaded anywhere.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

/**
 * A section heading and, under it, what the section is for.
 *
 * Both parts, because a bare heading next to a paragraph of the same weight leaves the
 * reader working out which words are the label and which are the explanation -- and a
 * screen whose labels are the smallest text on it does not read as organised.
 */
@Composable
private fun Section(title: String, blurb: String? = null) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (blurb != null) {
        Spacer(Modifier.height(Space.xs))
        Text(
            blurb,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ListEditor(
    vm: SurveyViewModel,
    s: UiState,
    kind: Lists.Kind,
    fields: FieldScroller,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<String?>(null) }
    val values = s.lists.forKind(kind)

    OutlinedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(Space.lg)) {
            Row(
                // The row is the control, so the row is the size of a target. It was two
                // lines of text and nothing else -- 39dp, under the 48dp floor -- which is
                // below what a fingertip is asked to hit anywhere else in this app.
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = BoothMetrics.TouchTarget)
                    .clickable(role = Role.Button, onClick = onToggle)
                    // Open or closed is a state, and a state belongs on the control. The
                    // arrow below carried it as words, which a screen reader reads once but
                    // cannot report as a change and cannot be asked about afterwards.
                    .semantics {
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(kind.title, style = MaterialTheme.typography.titleMedium)
                    // Collapsed, a count alone says nothing you can act on: "7 values"
                    // could be seven correct entries or seven typos. Showing the first few
                    // means the row tells you whether this list needs tidying before you
                    // spend a tap opening it.
                    Text(
                        when {
                            values.isEmpty() -> "Empty"
                            // Comma-separated, not spaced dots. "BC-A · BC-B · BC-C +4"
                            // spread three short codes across half the row on two spaces
                            // either side of a middot, and the values are what a person
                            // is scanning for, so they are packed together and read as one
                            // list rather than three separate items.
                            values.size <= 3 -> values.joinToString(", ")
                            else -> values.take(3).joinToString(", ") + " +${values.size - 3}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    // Silent: the row above now announces "Expanded" / "Collapsed" as a
                    // state, and a second "Collapse Caste" on the same control is the arrow
                    // saying the one thing twice.
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!expanded) return@Column

            Spacer(Modifier.height(Space.sm))
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
                // Each value on one line, tight. They were separated by a divider and
                // Space.sm top and bottom, which turned a list of three short codes into a
                // column that filled the screen — on a screen that had to be scrolled to
                // reach the next section. The value is the content; the padding is not.
                values.forEachIndexed { i, value ->
                    val used = vm.countWith(kind, value)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = Space.xs),
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
                        IconButton(onClick = { removing = value }, modifier = Modifier.size(BoothMetrics.TouchTarget)) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                "Remove $value",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(KraftSpacing.Spacing20),
                            )
                        }
                    }
                    if (i != values.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                        modifier = Modifier.size(KraftSpacing.Spacing48),
                    ) {
                        // Named after the list, not just the action. Four of these rows sit
                        // on this screen, and four buttons that all read "Add" leave a
                        // screen-reader user with nothing to tell them apart.
                        Icon(
                            Icons.Default.Check,
                            "Add to ${kind.title.lowercase()}",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // A gap between the two, which is what this pair did not have. They are
                    // the same size, the same height and the same colour weight, one
                    // confirming and one calling off -- the exact pair a hand with a tremor
                    // crosses when it means one and lands on the other.
                    Spacer(Modifier.width(Space.md))
                    IconButton(
                        onClick = { adding = false; text = "" },
                        modifier = Modifier.size(KraftSpacing.Spacing48),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            "Cancel adding to ${kind.title.lowercase()}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                TextButton(onClick = { adding = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(KraftSpacing.Spacing16))
                    Spacer(Modifier.width(Space.xs))
                    Text("Add one", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    removing?.let { value ->
        val used = vm.countWith(kind, value)
        // One sentence per kind, because the four kinds do not do the same thing. Caste and
        // sub caste are also a column on each voter and come off it when they leave the
        // list; address and party stay on the voter. Telling him the same thing for all
        // four is how a dialog ends up reassuring him about the one case that bites.
        val body = if (used == 0) {
            "It will no longer be offered in the dropdown. Nobody has it on their form " +
                "yet, so nothing else changes."
        } else {
            val voters = if (used == 1) "1 voter" else "$used voters"
            when (kind) {
                Lists.Kind.CASTE ->
                    "It will no longer be offered in the dropdown, and it will be cleared " +
                        "from the $voters who already have it."
                Lists.Kind.SUBCASTE ->
                    "It will no longer be offered in the dropdown, and it will be cleared " +
                        "from the $voters who already have it.\n\nSub caste is cleared from " +
                        "those voters. Their caste and all their other answers stay."
                Lists.Kind.ADDRESS, Lists.Kind.PARTY ->
                    "It will no longer be offered in the dropdown. The $voters who already " +
                        "have it keep it on their form."
            }
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
/**
 * The booth this app is working on, and the two things you can do to it.
 *
 * Import is here rather than on Home because it is a setup job, not a daily one, and because
 * it is the one action in the app that can add or change a lot of records at once. It belongs
 * somewhere you go deliberately.
 *
 * Outlined, like every other card in the app: this screen gets read in the sun as much as the
 * voter list does, and a shadow is the first thing daylight eats. The two actions share one
 * card because they are the same subject -- your roll -- and were separate only to keep an
 * outline button from looking like the equal of the filled one.
 */
@Composable
private fun BoothCard(
    vm: SurveyViewModel,
    s: UiState,
    onImport: () -> Unit,
    fields: FieldScroller,
) {
    OutlinedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(Space.lg)) {
            Text("Your roll", style = MaterialTheme.typography.titleMedium)
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
                Spacer(Modifier.height(Space.md))
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

            // No booklet button here. It was added when the PDF came back, on the reasoning
            // that printing is the reason the survey exists and should be one tap away --
            // and that put a second route to the same file one screen from the bar's export
            // icon, which is worse than either. One way to export, and it is the export
            // sheet: the booklet and the spreadsheet are both there, both with the
            // "still to visit only" filter, and the filename is named after the booth.
        }
    }
}
