package com.kraft.voterlist.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * What happens with the ticked rows, as a sheet rather than a bar.
 *
 * A bar has to fit five labeled actions, a count, select-all and close in one strip --
 * which is how the list ended up with a scrolling row that hid Remove, then a slab of
 * pills. A sheet gives every action a full row with room to say what it does, matching
 * the filter sheet's language: same bottom sheet, same rows, same Close. Selecting
 * itself stays chrome-free -- checkboxes only, list untouched -- with one pill to open
 * this.
 *
 * "Move to family" swaps the sheet body to the family list instead of opening a second
 * dialog on top: one surface from tick to done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActSheet(s: UiState, vm: SurveyViewModel, onClose: () -> Unit, onDeleteRequest: () -> Unit) {
    var pickingFamily by remember { mutableStateOf(false) }
    // A destructive confirmation raised from inside the sheet.
    var asking by remember { mutableStateOf<Confirm?>(null) }
    fun confirm(title: String, body: String, confirm: String, onConfirm: () -> Unit) {
        asking = Confirm(title, body, confirm, onConfirm)
    }

    ModalBottomSheet(onDismissRequest = onClose) {
        // Scrollable, so a sixth row never parks the destructive one below the fold on a
        // short screen: the sheet bounds this column, which is what makes scrolling it
        // legal (an unbounded parent would crash, as the Home search once proved).
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pickingFamily) {
                    IconButton(onClick = { pickingFamily = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to actions")
                    }
                }
                Text(
                    if (pickingFamily) "Move ${s.selectedCount} to a family"
                    else "${s.selectedCount} ticked",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (!pickingFamily) {
                    TextButton(onClick = { vm.selectAllVisible() }) {
                        Text("All ${s.visible.size}")
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (pickingFamily) {
                FamilyPage(s, onPick = {
                    vm.assignSelectedToFamily(it)
                    onClose()
                }, onNew = {
                    vm.moveSelectedToNewFamily()
                    onClose()
                })
            } else {
                ActionRow(Icons.Default.Check, "Mark visited", null) {
                    vm.markSelectedVisited()
                    onClose()
                }
                // These two erase marks he cannot get back. A day of knocks is gone in one tap
                // otherwise, and there was nothing but a small grey caption to warn him.
                ActionRow(Icons.AutoMirrored.Filled.Undo, "Mark to visit", "Clears visited and skipped") {
                    val n = s.selectedCount
                    confirm(
                        if (n == 1) "Clear the mark on 1 person?" else "Clear the marks on $n people?",
                        "This clears both the visited tick and the skip on every person " +
                            "you ticked, putting them all back on the list. There is no way " +
                            "to undo it.",
                        "Clear them",
                    ) {
                        vm.markSelectedPending()
                        onClose()
                    }
                }
                ActionRow(Icons.Default.Schedule, "Skip", "Still needs coming back to") {
                    val n = s.selectedCount
                    confirm(
                        if (n == 1) "Skip 1 person?" else "Skip $n people?",
                        "They stop counting as visited. Anyone you ticked who had " +
                            "already been visited loses that mark, and it is not saved " +
                            "anywhere else.",
                        "Skip them",
                    ) {
                        vm.markSelectedSkipped()
                        onClose()
                    }
                }
                ActionRow(Icons.Default.GroupAdd, "Move to family", "An existing one, or a new one") {
                    pickingFamily = true
                }
                ActionRow(Icons.Default.Close, "Untick all", null) {
                    vm.clearSelection()
                    onClose()
                }
                // Space before the destructive row, and it carries the only red thing in the
                // sheet. It used to sit one hairline under "Untick all", which is the shape
                // where a shaky thumb goes looking for something.
                Spacer(Modifier.height(Space.lg))
                ActionRow(Icons.Default.DeleteOutline, "Remove", "Off the list for good", danger = true) {
                    onDeleteRequest()
                }
            }
            Spacer(Modifier.height(Space.xl))
        }
    }

    asking?.let { c ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(c.title) },
            text = { Text(c.body) },
            confirmButton = {
                TextButton(onClick = { asking = null; c.onConfirm() }) {
                    Text(c.confirm, color = Status.bad)
                }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Keep them") } },
        )
    }
}

/** A question asked before something is erased. */
private data class Confirm(
    val title: String,
    val body: String,
    val confirm: String,
    val onConfirm: () -> Unit,
)

/** One full-width action: icon, label, and what it does, so nothing needs guessing. */
@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    caption: String?,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            // 48dp minimum: a row with no caption is only as tall as its text, which came to
            // 40dp -- under the platform minimum, and this is the panel where a day's work is
            // marked and removed.
            .heightIn(min = BoothMetrics.TouchTarget)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = Space.lg, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            tint = if (danger) Status.bad else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (danger) Status.bad else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (caption != null) {
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    HorizontalDivider(
        Modifier.padding(horizontal = Space.lg),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** The family list, inside the same sheet: existing families plus making a new one. */
@Composable
private fun FamilyPage(s: UiState, onPick: (String) -> Unit, onNew: () -> Unit) {
    // A plain Column, not a LazyColumn: this page sits inside the sheet's scrolling
    // column, and a scrolling list in an unbounded parent measures against infinity and
    // crashes the moment it composes -- the same trap as the Home search. Family counts
    // are small; laziness buys nothing here.
    Column(Modifier.fillMaxWidth()) {
        ActionRow(Icons.Default.GroupAdd, "New family", "Made of the ticked voters") {
            onNew()
        }
        s.familiesWithMembers.forEach { fwm ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { onPick(fwm.family.id) }
                    .padding(horizontal = Space.lg, vertical = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        fwm.label,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${fwm.size} ${if (fwm.size == 1) "person" else "people"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(
                Modifier.padding(horizontal = Space.lg),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        // This used to be a blank panel: "New family" at the top, then nothing at all. He
        // had ticked some people, chosen "Move to family", and been shown an empty screen
        // with no explanation and no way to tell whether it had failed.
        if (s.familiesWithMembers.isEmpty()) {
            Text(
                "You have not made any families yet. Make one here, and the ticked people " +
                    "go into it. Or take them off the list and add them later.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.md),
            )
        }
    }
}
