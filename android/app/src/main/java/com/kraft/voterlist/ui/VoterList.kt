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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kraft.ui.tokens.KraftSpacing
import com.kraft.voterlist.data.Voter
import kotlinx.coroutines.launch

/**
 * The width reserved for a number that leads a row: the widest one actually present.
 *
 * This was always four digits' worth, whatever the list held. A booth whose serials run to
 * 1221 looked right; a family of four, numbered 1 to 3, had a column three times wider than
 * its contents and the name pushed away from the number by a gap that meant nothing. Worse,
 * the same 4-digit rule was borrowed onto the family card -- where the number is a position
 * in a list of households, one or two digits -- so on the screen where a name most needs
 * the room, the number was reserving for a serial that does not exist.
 *
 * So it is measured from the numbers in front of it. The widest number in the visible list
 * sets the column, every row's number still lines up, and a short list gets a short column.
 *
 * The floor is two digits' worth, so a list that currently shows 1..9 does not have its
 * column jump about as he scrolls; the ceiling is four digits, which is the longest serial
 * the electoral roll uses.
 */
@Composable
fun numberColumnWidth(numbers: List<Int>): Dp {
    val widest = numbers.maxOfOrNull { it.coerceAtLeast(0).toString().length } ?: 1
    // Exactly the widest number present, and no floor above one digit. Rounding a list of
    // single-digit numbers up to a two-digit column is what put the gap back between the
    // number and the name: the column was half empty on every row, which reads as a mistake
    // however deliberate it was meant to be.
    //
    // The whole list is passed in rather than the visible slice, so the column does not
    // change width as he scrolls -- a column that resizes under the finger while he is
    // reading down it is worse than one that is slightly too wide.
    val digits = widest.coerceIn(1, 4)
    val line = MaterialTheme.typography.titleLarge.lineHeight.value
    return (line * 0.62f * digits * LocalDensity.current.fontScale).dp
        .coerceIn(16.dp, 96.dp) // @kraft-lint-ignore spacing.no-raw-dp, spacing.rhythm — a measured text column, not a spacing step
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
                // Room for the floating button, which sits over the list rather than in it.
        //
        // 80dp was not enough. The button is 56dp tall with a 16dp margin, and the list
        // kept the last row's *controls* -- the tick box, and on a household the arrows --
        // under it: measured on the device, the bottom row's checkbox came out 24px wide
        // against the usual 144px, because the button was sitting on it. The number here
        // is the button plus its margins plus a hand's width, so the last row can be
        // scrolled clear and then actually ticked.
        contentPadding = PaddingValues(bottom = Space.lg + KraftSpacing.Spacing64 + Space.xxl),
            ) {
                items(s.visible, key = { it.id }) { v ->
                    VoterRow(
                        v = v,
                        familyLabel = s.familyNamed(v.familyId),
                        duplicated = s.isVoterIdRepeated(v.voterId),
                        serialDuplicated = s.isSerialRepeated(v.serial),
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
    // Says which of the things came up empty. "Nothing matches" on a filtered list
    // reads as a failure rather than an answer.
    val because = when {
        s.query.isNotBlank() || s.hasExtraFilters -> "Nobody matches these filters."
        s.filter == UiState.Filter.DONE -> "Nobody has been visited yet."
        s.filter == UiState.Filter.REMAINING -> "Everyone in this booth has been visited."
        else -> "This booth has nobody in it yet."
    }
    // Same top-aligned block as every other empty state, at the same inset. This one had
    // its own padding and no heading, so an empty list started further left and higher
    // than the empty app did on the tab next to it.
    EmptyTab(title = because, body = "", scrollable = false) {
        if (s.query.isNotBlank() || s.hasExtraFilters) {
            Spacer(Modifier.height(Space.xs))
            TextButton(onClick = { vm.clearListFilters() }) { Text("Clear search and filters") }
        } else if (s.totalCount == 0) {
            // A genuinely empty booth, not a filter that matched nothing: the way
            // forward is the import sheet, so it is offered here rather than leaving
            // the screen a dead end.
            Spacer(Modifier.height(Space.sm))
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
        // Two lines, the same shape as the family cards and the household rows: the serial
        // and the name on the first line, everything else under them.
        //
        // The serial used to be a column to the left of the whole block, which put it beside
        // the *middle* of a three-line row rather than beside the name. On a roll where the
        // job is "find serial 312", a number floating at the side of the row is one more
        // thing to work out -- it belongs to the name, so it now sits with it.
        Column(
            Modifier
                .weight(1f)
                // One announcement for the whole row. Unmerged, a screen reader reads the
                // name, then the details, then each chip separately -- stops to answer
                // "who is this and have I been".
                .semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append("Serial ${v.serial}.  ")
                        append(v.name.ifBlank { "No name" })
                        if (v.voterId.isNotBlank()) append(". Voter ID ${v.voterId}")
                        if (v.house.isNotBlank()) append(". Door ${v.house}")
                        if (familyLabel.isNotEmpty()) append(". In $familyLabel")
                        if (v.caste.isNotBlank()) append(". ${v.caste}")
                        if (v.party.isNotBlank()) append(". ${v.party}")
                        if (v.visited) append(". Visited")
                        if (v.skipped) append(". Skipped")
                    }
                },
        ) {
            // Line one: the serial and the name. The serial is right-aligned in a column
            // sized to the widest number on screen, so a one-digit serial ends against its
            // name rather than sitting at the far end of an empty four-digit column.
            Row(verticalAlignment = Alignment.CenterVertically) {
                // No fixed-width column here, and that is the difference between this list and
                // the family screens.
                //
                // There the numbers are all the same width -- 1 to 4, or 1 to 6 -- so a
                // column lines them up and costs nothing. Here they run 1 to 1224, so any
                // column wide enough for 1224 leaves a one-digit serial adrift in the middle
                // of the row while the line under it starts at the margin. Right-aligning
                // fixed the gap and made the offset obvious: the number no longer sat on
                // the left edge with everything else, it sat where the eye reads it as
                // belonging to whatever is beside it.
                //
                // So the number is simply the first thing on the line, sized to itself, with
                // a gap after it. Numbers no longer form a perfect vertical column on this
                // screen; the alternative was a column that is wrong for most rows, and his
                // eye is not here to compare columns -- it is here to match a name to a
                // serial at a door.
                Text(
                    v.serial.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (v.isDone) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(Space.sm))
                Text(
                    v.name.ifBlank { "(no name)" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // The marks dock right, on this same line. They were on a line of their
                // own below the name -- a row holding an icon and a checkbox with nothing
                // beside them, which is a third line per person on a 1216-row roll and
                // pushed the caste onto a fourth. Here they sit in the same place on every
                // row, so "done" is scannable straight down the column, and the detail line
                // below gets the full width to itself.
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