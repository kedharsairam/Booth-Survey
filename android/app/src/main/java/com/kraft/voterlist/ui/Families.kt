package com.kraft.voterlist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.kraft.voterlist.data.NATURAL_HOUSE_ORDER
import com.kraft.voterlist.data.Voter
import com.kraft.ui.tokens.KraftSpacing

/**
 * The families that exist, in the order they will print.
 *
 * That order is the booklet's: lowest door number first, then the surveyor's arrangement
 * inside each family. Showing it here rather than in creation order means the screen and the
 * paper cannot disagree about what comes next.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyListScreen(vm: SurveyViewModel, s: UiState) {
    val families = s.familiesWithMembers
    if (!s.hasBooth) {
        EmptyTab("No voters list yet", "Import one from Settings and the families you make will appear here.")
        return
    }
    if (families.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(Space.lg),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "No families yet",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(Space.md))
            Text(
                "A family is a group of people you decide belong together. Nothing in the " +
                    "app guesses it from the door number, because a door number in a roll " +
                    "does not say who lives where.\n\nUse New family when you are at a house: " +
                    "pick the people who live there, put them in the order you want them " +
                    "printed, and the family takes the name of whoever is first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.xl))
            FilledTonalButton(onClick = { vm.createFamily() }) {
                Icon(Icons.Default.GroupAdd, null)
                Spacer(Modifier.width(Space.sm))
                Text("New family")
            }
        }
        return
    }

    // weight, not fillMaxSize -- see the note on the voters list. In landscape this list was
    // showing one family and refusing to scroll, so the other one was unreachable.
    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = Space.xl),
    ) {
        items(families, key = { it.family.id }) { fwm ->
            Card(
                onClick = { vm.openFamily(fwm.family.id) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.xs),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(Modifier.padding(Space.lg)) {
                    Text(
                        fwm.label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(Space.xs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            buildString {
                                append("${fwm.size} ")
                                append(if (fwm.size == 1) "person" else "people")
                                val doors = fwm.members
                                    .mapNotNull { it.house.trim().takeIf(String::isNotEmpty) }
                                if (doors.isNotEmpty()) append("  ${doors.minWith(NATURAL_HOUSE_ORDER)}")
                                append("  ${fwm.done}/${fwm.size} visited")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (fwm.complete) StatusChip("done", Status.done)
                    }
                    Spacer(Modifier.height(Space.sm))
                    Text(
                        fwm.members.joinToString(", ") { it.name.ifBlank { "(no name)" } },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        }
    }

/**
 * One family: who is in it, in what order.
 *
 * There is no name field. The family is called after whoever is first in it, worked out from
 * the members every time it is drawn, so putting the grandmother first renames it
 * automatically and there is no stored label to fall out of step with the arrangement.
 *
 * The order is set with up and down arrows rather than by dragging. This screen is used
 * one-handed, outdoors, often with the phone held out at a stranger's door, and a drag gesture
 * that has to land inside a 48dp row is a poor way to put a grandmother before a father.
 * Two big targets always work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyEditScreen(vm: SurveyViewModel, s: UiState) {
    val id = s.openFamilyId ?: return
    val family = s.families.firstOrNull { it.id == id } ?: return
    val members = s.membersOf(id)
    val label = s.familyNamed(id)
    var confirmingDelete by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Voter?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.closeFamily() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                title = {
                    Column {
                        Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            "${members.size} ${if (members.size == 1) "person" else "people"}",
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
            Modifier.padding(pad).fillMaxSize().padding(horizontal = Space.lg),
        ) {
            Spacer(Modifier.height(Space.sm))
            Text(
                if (members.isEmpty()) {
                    "Nobody in this family yet. It takes its name from whoever is first."
                } else {
                    "Named after ${members.first { it.name.isNotBlank() }.name}, who is first."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Space.lg))
            Text("Printed in this order", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Space.xs))

            LazyColumn(Modifier.weight(1f)) {
                items(members, key = { it.id }) { m ->
                    MemberRow(
                        m = m,
                        position = members.indexOf(m) + 1,
                        first = m.id == members.firstOrNull()?.id,
                        last = m.id == members.lastOrNull()?.id,
                        onUp = { vm.moveMember(id, m.id, -1) },
                        onDown = { vm.moveMember(id, m.id, 1) },
                        onRemove = { removing = m },
                        onOpen = { vm.openVoter(m.id) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (members.isEmpty()) {
                    item {
                        Text(
                            "Add the people who live here.",
                            Modifier.padding(vertical = Space.lg),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            FilledTonalButton(
                onClick = { vm.openPicker() },
                modifier = Modifier.fillMaxWidth().height(BoothMetrics.FieldHeight),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(Space.sm))
                Text("Add someone")
            }
            Spacer(Modifier.height(Space.sm))
            TextButton(onClick = { confirmingDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Delete this family", color = Status.bad)
            }
            Text(
                "Deleting a family does not delete the people in it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Space.lg),
            )
        }
    }

    if (s.pickingMember) {
        MemberPicker(
            vm = vm,
            s = s,
            exclude = members.map { it.id }.toSet(),
            onPick = { vm.addMember(id, it); vm.closePicker() },
            onCreate = { vm.createAndAddMember(id, it) },
            onCancel = { vm.closePicker() },
        )
    }

    removing?.let { m ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Take ${m.name.ifBlank { "this person" }} out?") },
            text = {
                Text(
                    "They stay on the list and go back to being on their own. The family keeps " +
                        "whoever else is in it and takes a new name from whoever is left first."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.removeMember(id, m.id); removing = null }) {
                    Text("Take out")
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text("Keep") }
            },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete $label?") },
            text = {
                Text(
                    if (members.isEmpty()) {
                        "This family is empty, so nothing happens to the roll."
                    } else {
                        "The ${members.size} ${if (members.size == 1) "person" else "people"} in " +
                            "it stay on the list and go back to being individuals. Nothing about " +
                            "them is lost."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteFamily(id); confirmingDelete = false }) {
                    Text("Delete", color = Status.bad)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun MemberRow(
    m: Voter,
    position: Int,
    first: Boolean,
    last: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Space.sm),
        // Top, not centre. A long name wraps to two lines, and centring then pushed that row's
        // arrows down out of line with the row above it.
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            position.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(KraftSpacing.Spacing24),
        )
        Column(
            Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(vertical = Space.xs)
                // One announcement instead of four. Left separate, a screen reader walks
                // "JAYRAM", "109", "91-5-5/3", "bc" as four things and the listener has to
                // hold them in their head to work out who this is.
                .semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append(m.name.ifBlank { "No name" })
                        append(". Serial ${m.serial}")
                        if (m.house.isNotBlank()) append(". Door ${m.house}")
                        if (m.caste.isNotBlank()) append(". ${m.caste}")
                        if (first) append(". First in this family, so it is named after them")
                    }
                },
        ) {
            Text(
                m.name.ifBlank { "(no name)" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (first) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append("sr ${m.serial}")
                    if (m.house.isNotBlank()) append("  ${m.house}")
                    if (m.caste.isNotBlank()) append("  ${m.caste}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onUp, enabled = !first, modifier = Modifier.size(KraftSpacing.Spacing48)) {
            Icon(Icons.Default.KeyboardArrowUp, "Move ${m.name} up")
        }
        IconButton(onClick = onDown, enabled = !last, modifier = Modifier.size(KraftSpacing.Spacing48)) {
            Icon(Icons.Default.KeyboardArrowDown, "Move ${m.name} down")
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(KraftSpacing.Spacing48)) {
            Icon(
                Icons.Default.PersonRemove,
                "Take ${m.name} out of this family",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(KraftSpacing.Spacing20),
            )
        }
    }
}

/**
 * Adding people to a family: the contacts picker, but for voters.
 *
 * Shown over the family rather than as a dropdown, because at the start of a house you do not
 * know who is in it and you need to see names, door numbers and who is already spoken for, all
 * at once, while you ask who lives here.
 *
 * Anyone already in this family is hidden. Anyone in a different family is marked, because
 * picking them moves them here -- which is usually what you want when a family turns out to
 * have been built a week ago with the wrong person in it.
 */
@Composable
fun MemberPicker(
    vm: SurveyViewModel,
    s: UiState,
    exclude: Set<String>,
    onPick: (String) -> Unit,
    onCreate: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val candidates = remember(query, s.voters, exclude) {
        s.voters
            .filter { it.id !in exclude }
            .filter { v ->
                query.isBlank() ||
                    v.name.contains(query, true) ||
                    v.voterId.contains(query, true) ||
                    v.house.contains(query, true) ||
                    v.serial.toString() == query ||
                    v.phone.contains(query, true)
            }
            .sortedBy { it.serial }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Who lives at this house?") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, "Clear")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                Spacer(Modifier.height(Space.sm))
                LazyColumn(
                    Modifier.height(BoothMetrics.FamilyListHeight),
                    contentPadding = PaddingValues(bottom = Space.xs),
                ) {
                    items(candidates.take(150), key = { it.id }) { v ->
                        val elsewhere = s.familyNamed(v.familyId)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(v.id) }
                                .padding(vertical = Space.sm)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = buildString {
                                        append(v.name.ifBlank { "No name" })
                                        append(". Serial ${v.serial}")
                                        if (v.house.isNotBlank()) append(". Door ${v.house}")
                                        if (elsewhere.isNotEmpty()) {
                                            append(". Currently in $elsewhere, picking moves them here")
                                        }
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    v.name.ifBlank { "(no name)" },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    buildString {
                                        append("sr ${v.serial}")
                                        if (v.house.isNotBlank()) append("  ${v.house}")
                                        if (elsewhere.isNotEmpty()) append("  in $elsewhere")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (elsewhere.isNotEmpty()) Status.pending
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (v.serialIsDuplicated(s.voters)) DupBadge()
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                // Adding somebody who is not on the roll.
                //
                // Pinned below the list rather than sitting in it. Inside a fixed-height
                // scrolling list it was the eighth item and needed scrolling to reach -- which
                // is absurd, because the person most likely to want it is the one who just
                // searched a name and did not find them. It is an action, not a result, so it
                // belongs outside the results.
                if (query.isNotBlank()) {
                    val nextSerial = (s.booth.voters.maxOfOrNull { it.serial } ?: 0) + 1
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onCreate(query) }
                            .padding(vertical = Space.md)
                            .semantics(mergeDescendants = true) {
                                contentDescription =
                                    "Add $query to this family. They are not on this roll, and " +
                                        "will be given serial $nextSerial."
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.PersonAdd,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(Space.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Add \"$query\"",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "Not on this roll. Goes in as serial $nextSerial.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (candidates.size > 150) {
                    Text(
                        "${candidates.size - 150} more. Keep typing to narrow it down.",
                        Modifier.padding(top = Space.sm),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
