package com.kraft.voterlist.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kraft.voterlist.data.Voter
import com.kraft.ui.tokens.KraftSpacing

/**
 * Every voter, in serial order.
 *
 * Serial and not door number, which is the opposite of the booklet. See [UiState.visible] for
 * why: the app is the thing that has to be trusted when the paper is wrong, and serial is the
 * one field the extraction got perfectly.
 *
 * Each row carries its own delete button. Deleting used to mean opening the voter and
 * scrolling to the bottom of a long form to find it, which is a long way to go to discover
 * the row is already gone -- and it put the destructive action at the far end of a scroll
 * where a stray tap cannot reach it but a determined mis-scroll can.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoterListScreen(vm: SurveyViewModel, s: UiState) {
    var confirming by remember { mutableStateOf<Voter?>(null) }

    // The app bar lives in the shell, so this is just the body, filling the bounded box the
    // shell gave it.
    Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = s.query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm),
                placeholder = { Text("Search anyone") },
                leadingIcon = {
                    Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingIcon = {
                    if (s.query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Default.Close, "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
            )
            // Scrolls sideways rather than squeezing. Three chips in a fixed-width row meant
            // that at a 1.5x font "Done 1" was being pressed down to "Do", which is a filter
            // that no longer says what it filters.
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Space.lg),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                UiState.Filter.entries.forEach { f ->
                    val label = when (f) {
                        UiState.Filter.ALL -> "All ${s.totalCount}"
                        UiState.Filter.REMAINING -> "Left ${s.remainingCount}"
                        UiState.Filter.DONE -> "Done ${s.doneCount}"
                    }
                    FilterChip(
                        selected = s.filter == f,
                        onClick = { vm.setFilter(f) },
                        // "Done 1" was wrapping to "Do / ne / 1" at a large font.
                        label = { Text(label, maxLines = 1) },
                    )
                }
            }
            Spacer(Modifier.height(Space.sm))
            // weight, not fillMaxSize. The search field and the filter chips above already
            // use part of the screen; asking for the whole height put the list's rows past the
            // bottom edge, and since it believed it had the full height it never scrolled.
            LazyColumn(Modifier.weight(1f)) {
                items(s.visible, key = { it.id }) { v ->
                    VoterRow(
                        v = v,
                        familyLabel = s.familyNamed(v.familyId),
                        duplicated = v.voterIdIsDuplicated(s.voters),
                        serialDuplicated = v.serialIsDuplicated(s.voters),
                        notInFile = v.notInLatestFile,
                        onOpen = { vm.openVoter(v.id) },
                        onDelete = { confirming = v },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (s.visible.isEmpty()) {
                    item {
                        // Says which of the three things came up empty. "Nothing matches" on a
                        // filtered list reads as a failure rather than an answer.
                        val because = when {
                            s.query.isNotBlank() -> "No name, voter ID, door number or serial matches " +
                                "\"${s.query}\"."
                            s.filter == UiState.Filter.DONE -> "Nobody has been visited yet."
                            s.filter == UiState.Filter.REMAINING ->
                                "Everyone in this booth has been visited."
                            else -> "This booth has nobody in it yet."
                        }
                        Text(
                            because,
                            Modifier.padding(Space.lg),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

    confirming?.let { v ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Remove ${v.name.ifBlank { "this voter" }}?") },
            text = {
                Text(
                    "They come off the list for good. Their family, if they are in one, keeps " +
                        "whoever else is in it.\n\nThis cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteVoter(v.id); confirming = null }) {
                    Text("Remove", color = Status.bad)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun VoterRow(
    v: Voter,
    familyLabel: String,
    duplicated: Boolean,
    serialDuplicated: Boolean,
    notInFile: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .rowClickable(onOpen)
            .padding(start = Space.lg, end = Space.sm, top = Space.sm, bottom = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                // One announcement for the whole row. Unmerged, a screen reader reads the name,
                // then the details, then each chip separately -- six stops to answer "who is
                // this and have I been".
                .semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append(v.name.ifBlank { "No name" })
                        append(". Serial ${v.serial}")
                        if (v.voterId.isNotBlank()) append(". Voter ID ${v.voterId}")
                        if (v.house.isNotBlank()) append(". Door ${v.house}")
                        if (familyLabel.isNotEmpty()) append(". In $familyLabel")
                        if (v.visited) append(". Visited")
                        if (v.skipped) append(". Skipped")
                        if (duplicated) append(". This voter ID appears twice")
                        if (serialDuplicated) append(". This serial appears twice")
                        if (notInFile) append(". Not in the latest file")
                    }
                },
        ) {
            Text(
                v.name.ifBlank { "(no name)" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The badges share the line with the details rather than taking one of their own.
            // On a list of 1129, a row that is three lines tall is a row you can only read six
            // of at a time, and the extra line was almost always empty.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        append("sr ${v.serial}")
                        if (v.voterId.isNotBlank()) append("  ${v.voterId}")
                        if (v.house.isNotBlank()) append("  ${v.house}")
                        // Without the trailing " family". On this line it arrives after a
                        // serial, a voter ID and a door number, and it was the word being cut
                        // off -- every row read "...  JAYR…". A reader who needs to know it is
                        // a family already sees it on the Families tab, and the full label is
                        // on the voter screen.
                        if (familyLabel.isNotEmpty()) {
                            append("  ")
                            append(
                                familyLabel.removeSuffix(" family")
                                    .removeSuffix(" household")
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                StatusPill(v)
                // A repeated voter ID means two records for what may be one person, which is
                // the check that matters. A repeated serial is worth seeing too, but in
                // practice serials are unique and this almost never fires.
                if (duplicated) {
                    Spacer(Modifier.width(Space.xs))
                    DupBadge()
                } else if (serialDuplicated) {
                    Spacer(Modifier.width(Space.xs))
                    StatusChip("same serial", Status.pending)
                }
                if (notInFile) {
                    Spacer(Modifier.width(Space.xs))
                    StatusChip("not in file", Status.pending)
                }
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.DeleteOutline,
                "Remove ${v.name.ifBlank { "voter" }}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(KraftSpacing.Spacing20),
            )
        }
    }
}

/** Clickable without the ripple stretching over the delete button, which made both look tappable. */
private fun Modifier.rowClickable(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
