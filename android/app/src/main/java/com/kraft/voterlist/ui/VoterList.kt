package com.kraft.voterlist.ui

import androidx.compose.ui.semantics.Role

import androidx.compose.foundation.selection.toggleable

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kraft.ui.tokens.KraftSpacing
import com.kraft.voterlist.data.Voter
import kotlinx.coroutines.launch

/**
 * The width reserved for the serial number: room for four digits at whatever size the app
 * is set to.
 *
 * A fixed 48dp fitted four digits only at the default font size. From about 1.5x upwards it
 * showed "31..." instead of 312 -- on the one number a surveyor reads out loud at a door,
 * which is the worst possible place to lose a digit. Sizing from the font scale keeps every
 * digit visible and costs no more room than the text needs at the default size.
 */
@Composable
fun serialColumnWidth(): Dp {
    val digits = MaterialTheme.typography.titleLarge.lineHeight.value * 0.62f * 4
    return (digits * LocalDensity.current.fontScale).dp
        // The upper bound is a ceiling on a measured text column, not a spacing step: at
        // a very large font a four-digit serial needs more than the rhythm allows, and
        // ellipsising it would show the wrong number.
        .coerceIn(BoothMetrics.TouchTarget, 110.dp) // @kraft-lint-ignore spacing.no-raw-dp, spacing.rhythm — a measured text ceiling, not a spacing step
}

/**
 * Every voter, in serial order. Nothing above the list: search lives in the top bar and
 * the filters live in a sheet, so the body is voters all the way down.
 *
 * The serial leads every row: it is the one value the extraction got perfectly, and on a
 * doorstep "serial 312" is how a person is found in the roll. Tapping a row opens the voter;
 * holding one starts ticking, and the bar that appears works on all the ticked rows at once
 * -- visited, back to to-visit, skipped, moved to a family, or removed. The per-row delete
 * button is gone on purpose: one destructive control with a count on it ("Remove 6 people?")
 * is harder to fire by accident than 1216 of them.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VoterListScreen(
    vm: SurveyViewModel,
    s: UiState,
    listState: LazyListState,
    lastBoothId: String?,
    onBoothSeen: (String) -> Unit,
    onImport: () -> Unit,
) {
    var confirmingBulk by remember { mutableStateOf(false) }
    var actOpen by remember { mutableStateOf(false) }
    val selecting = s.selectedCount > 0
    val focus = LocalFocusManager.current
    // A new booth starts at the top; coming back from a voter holds still. Told apart
    // by lastBoothId, which lives above this screen because nothing in it survives
    // leaving it.
    LaunchedEffect(s.booth.id) {
        if (lastBoothId != null && lastBoothId != s.booth.id &&
            listState.firstVisibleItemIndex != 0
        ) {
            listState.scrollToItem(0)
        }
        onBoothSeen(s.booth.id)
    }
    val scope = rememberCoroutineScope()
    // Appears once the list has moved a few rows down: a long roll should not need a
    // long drag back up.
    val showJumpTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 2 }
    }

    // Scrolling the list or tapping empty space hands focus back: a cursor blinking
    // in the bar while the list moves is a keyboard that has overstayed, and it covers
    // the pill that long-pressing is about to raise.
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) focus.clearFocus()
    }

    Column(
        Modifier.fillMaxSize().pointerInput(focus) {
            detectTapGestures(onTap = { focus.clearFocus() })
        },
    ) {
        // The list runs full-bleed to the bottom bar and the add floats over it, rather
        // than the list yielding a permanent strip to a button row: rows must be able to
        // scroll all the way down, not stop above a reserved band. The bottom padding
        // lets the last row scroll clear of the floating button.
        Box(Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = Space.lg + KraftSpacing.Spacing64),
            ) {
                items(s.visible, key = { it.id }) { v ->
                    VoterRow(
                        v = v,
                        familyLabel = s.familyNamed(v.familyId),
                        duplicated = v.voterIdIsDuplicated(s.voters),
                        serialDuplicated = v.serialIsDuplicated(s.voters),
                        notInFile = v.notInLatestFile,
                        selecting = selecting,
                        ticked = v.id in s.selectedIds,
                        onOpen = { vm.openVoter(v.id) },
                        onToggle = { vm.toggleSelect(v.id) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (s.visible.isEmpty()) {
                    item { EmptyList(s, vm, onImport) }
                }
            }
            // No container reserved for it: both pills float over the list. Ticking swaps
            // which one shows; the list underneath never moves for either.
            if (!selecting) {
                Column(
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .padding(horizontal = Space.lg, vertical = Space.md),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    if (showJumpTop) {
                        SmallFloatingActionButton(
                            onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        ) {
                            Icon(Icons.Default.ArrowUpward, "Back to the top")
                        }
                    }
                    ExtendedFloatingActionButton(
                        onClick = { vm.startCreateVoter() },
                        icon = { Icon(Icons.Default.PersonAdd, null) },
                        text = { Text("New voter") },
                    )
                }
            } else {
                // Same overlay, ticking variant: the count and a way into the sheet.
                ExtendedFloatingActionButton(
                    onClick = { actOpen = true },
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .padding(horizontal = Space.lg, vertical = Space.md),
                    icon = { Icon(Icons.Default.Tune, null) },
                    text = { Text("${s.selectedCount} ticked") },
                )
            }
        }
    }

    if (actOpen) {
        ActSheet(
            s, vm,
            onClose = { actOpen = false },
            onDeleteRequest = { actOpen = false; confirmingBulk = true },
        )
    }

    if (confirmingBulk) {
        val n = s.selectedCount
        AlertDialog(
            onDismissRequest = { confirmingBulk = false },
            title = { Text("Remove $n ${if (n == 1) "person" else "people"}?") },
            text = {
                Text(
                    "They come off the list for good. Families they were in keep whoever " +
                        "else is in them.\n\nThis cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteSelected(); confirmingBulk = false }) {
                    Text("Remove", color = Status.bad)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingBulk = false }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun EmptyList(s: UiState, vm: SurveyViewModel, onImport: () -> Unit) {
    Column(Modifier.padding(Space.lg)) {
        // Says which of the things came up empty. "Nothing matches" on a filtered list
        // reads as a failure rather than an answer.
        val because = when {
            s.query.isNotBlank() || s.hasExtraFilters -> "Nobody matches these filters."
            s.filter == UiState.Filter.DONE -> "Nobody has been visited yet."
            s.filter == UiState.Filter.REMAINING -> "Everyone in this booth has been visited."
            else -> "This booth has nobody in it yet."
        }
        Text(
            because,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (s.query.isNotBlank() || s.hasExtraFilters) {
            Spacer(Modifier.height(Space.sm))
            TextButton(onClick = { vm.clearListFilters() }) { Text("Clear search and filters") }
        } else if (s.totalCount == 0) {
            // A genuinely empty booth, not a filter that matched nothing: the way
            // forward is the import sheet, so it is offered here rather than leaving
            // the screen a dead end.
            Spacer(Modifier.height(Space.md))
            Button(onClick = onImport) { Text("Import voters list") }
            Spacer(Modifier.height(Space.sm))
            Text(
                "A spreadsheet with four columns: serial, voter id, name, house number.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoterRow(
    v: Voter,
    familyLabel: String,
    duplicated: Boolean,
    serialDuplicated: Boolean,
    notInFile: Boolean,
    selecting: Boolean,
    ticked: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    // Long press is now only a shortcut: the circle at the row's end does the same thing,
    // visibly. The row itself still opens the voter, and still toggles once the "N ticked"
    // bar is up, so nothing that worked before has moved.
    Box(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                // A role, so a screen reader says "button" rather than leaving it as an
                // untyped clickable. There were thirteen of those in the app and none of
                // them appear in TalkBack's list of buttons, which is how a screen-reader
                // user plans a tap without reading the screen.
                role = Role.Button,
                onClick = { if (selecting) onToggle() else onOpen() },
                onLongClickLabel = "Tick this person",
                onLongClick = { onToggle() },
            )
            .padding(start = Space.lg, end = Space.sm, top = Space.sm, bottom = Space.sm),
        contentAlignment = Alignment.CenterEnd,
    ) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The serial, big enough to read at arm's length. Finding "serial 312" in the roll
        // is the most common thing done with this screen, so it gets the most ink.
        // The width is the font-scaled line height, not a fixed 48dp. Serials run to four
        // digits and he reads this one out at the door: in a fixed box it was showing "31..."
        // from about 1.5x font size onwards, so the number he says was the wrong number.
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
        Column(
            Modifier
                .weight(1f)
                // One announcement for the whole row. Unmerged, a screen reader reads the
                // name, then the details, then each chip separately -- stops to answer
                // "who is this and have I been".
                .semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append(v.name.ifBlank { "No name" })
                        // Not repeated here. The serial is its own node to the left of
                        // this one, so saying it in both places made a screen reader walk
                        // "312", then "JAYRAM, serial 312, door 91-5-5/3" -- and on a
                        // 1216-row roll, finding serial 312 is the whole job.
                        //
                        if (v.voterId.isNotBlank()) append(". Voter ID ${v.voterId}")
                        if (v.house.isNotBlank()) append(". Door ${v.house}")
                        if (familyLabel.isNotEmpty()) append(". In $familyLabel")
                        if (v.caste.isNotBlank()) append(". ${v.caste}")
                        if (v.party.isNotBlank()) append(". ${v.party}")
                        if (v.visited) append(". Visited")
                        if (v.skipped) append(". Skipped")
                        if (duplicated) append(". This voter ID appears twice")
                        if (serialDuplicated) append(". This serial appears twice")
                        if (notInFile) append(". Not in the latest file")
                        if (selecting) append(if (ticked) ". Ticked" else ". Not ticked")
                    }
                },
        ) {
            // The name takes the row; every mark docks right. A pill floating right after
            // a short name is a status that wanders per row -- done must sit in the same
            // place on every row to be scannable. In selection mode the marks step aside
            // for the checkbox instead of squeezing beside it: adding 48dp of box to a
            // row that keeps everything else reflows the details onto another line and
            // the whole list jumps taller the moment ticking starts.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    v.name.ifBlank { "(no name)" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!selecting) {
                    StatusPill(v)
                    // A repeated voter ID means two records for what may be one person, which
                    // is the check that matters. A repeated serial is worth seeing too, but in
                    // practice serials are unique and this almost never fires.
                    if (duplicated) {
                        Spacer(Modifier.width(Space.sm))
                        StatusChip("duplicate ID", Status.bad)
                    } else if (serialDuplicated) {
                        Spacer(Modifier.width(Space.sm))
                        StatusChip("same serial", Status.pending)
                    }
                    if (notInFile) {
                        Spacer(Modifier.width(Space.sm))
                        StatusChip("not in file", Status.pending)
                    }
                    // Last in the row, on the far right: in a family, said with an icon
                    // rather than words. The household itself lives on the Families tab and
                    // on the voter screen, not in a third line here.
                    Spacer(Modifier.width(Space.sm))
                    Box(
                        modifier = Modifier.size(KraftSpacing.Spacing20),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (familyLabel.isNotEmpty()) {
                            Icon(
                                Icons.Default.Group,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Text(
                buildString {
                    if (v.voterId.isNotBlank()) append(v.voterId)
                    if (v.house.isNotBlank()) {
                        if (isNotEmpty()) append("  ·  ")
                        append(v.house)
                    }
                    if (v.phone.isNotBlank()) {
                        if (isNotEmpty()) append("  ·  ")
                        append(v.phone)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val context = buildString {
                if (v.caste.isNotBlank()) {
                    append(v.caste)
                }
                if (v.party.isNotBlank()) {
                    if (isNotEmpty()) append("  ·  ")
                    append(v.party)
                }
            }
            if (context.isNotEmpty()) {
                Text(
                    context,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Always there, and always a target.
        //
        // It used to appear only in selection mode, and the only way into selection mode was
        // holding a row -- a gesture with no button anywhere, described only in a code
        // comment. Holding also had to stay within 14dp of travel for half a second, which
        // a hand with a tremor does not reliably do, and when it fails the gesture degrades
        // into a scroll. The consequence was that every bulk action in the app was
        // unreachable for the person it is for.
        //
        // So the circle is now part of every row, always visible, and tapping it ticks. The
        // mode still exists -- it drives the "N ticked" bar -- but it is now something you
        // arrive at rather than something you have to discover.
        Spacer(Modifier.width(Space.xs))
        Box(
            Modifier
                .size(BoothMetrics.TouchTarget)
                .toggleable(
                    value = ticked,
                    role = Role.Checkbox,
                    onValueChange = { onToggle() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (ticked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                null,
                tint = if (ticked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(KraftSpacing.Spacing24),
            )
        }
    }
}
}