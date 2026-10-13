package com.kraft.voterlist.ui

import androidx.compose.material.icons.filled.Undo

import androidx.compose.material.icons.filled.MoreVert

import androidx.compose.ui.semantics.Role

import androidx.compose.foundation.selection.toggleable

import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import com.kraft.voterlist.data.FamilyWithMembers
import com.kraft.voterlist.data.NATURAL_HOUSE_ORDER
import com.kraft.voterlist.data.Voter
import com.kraft.ui.tokens.KraftSpacing

/**
 * The families that exist, in the order they will print.
 *
 * That order is the surveyor's walking order — not the roll's and not the door numbers'.
 * Set it by holding a card and dragging (fast) or with the arrows on the card (precise):
 * showing it here rather than in creation order means the screen and the spreadsheet cannot
 * disagree about what comes next. The number on each card is the walking position, display
 * only, and is never exported: the sheet carries the member order, and this number is just
 * where the card stands in it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FamilyListScreen(
    vm: SurveyViewModel,
    s: UiState,
    listState: LazyListState,
    lastBoothId: String?,
    onBoothSeen: (String) -> Unit,
) {
    val families = s.visibleFamilies
    // Ticking swaps the floating pill and what a tap does, like the voters list.
    // Reordering stays available underneath: ticking and arranging are different
    // jobs and there is no reason to take one away for the other.
    val selecting = s.selectedFamilyIds.isNotEmpty()
    if (!s.hasBooth) {
        EmptyTab("No voters list yet", "Import one from Settings and the families you make will appear here.")
        return
    }
    if (s.families.isEmpty()) {
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
    val drag = rememberDragReorder()
    var actOpen by remember { mutableStateOf(false) }
    var confirmingBulk by remember { mutableStateOf(false) }
    // Same deal as the voters list: a new booth starts at the top, a return holds still.
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
    val focus = LocalFocusManager.current
    // The search field lives in the bar; scrolling the list hands focus back so a
    // keyboard left up does not cover half the cards.
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) focus.clearFocus()
    }
    Box(
        Modifier.fillMaxSize().pointerInput(focus) {
            detectTapGestures(onTap = { focus.clearFocus() })
        },
    ) {
    // The list runs full-bleed to the bottom bar and the pills float over it, like
    // the voters list: cards must scroll all the way down, and the bar underneath
    // never moves for either pill. The bottom padding lets the last card scroll
    // clear of the floating button.
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = Space.lg + KraftSpacing.Spacing64),
    ) {
        itemsIndexed(families, key = { _, fwm -> fwm.family.id }) { index, fwm ->
            val dragging = drag.draggingId == fwm.family.id
            val ticked = fwm.family.id in s.selectedFamilyIds
            FamilyCard(
                // animateItem: every other card slides aside to make the room this one just
                // vacated. Without it a reorder is a teleport, which is the difference
                // between "the list changed" and "I moved that card".
                modifier = Modifier.animateItem(),
                fwm = fwm,
                index = index,
                total = families.size,
                dragging = dragging,
                selecting = selecting,
                ticked = ticked,
                listState = listState,
                drag = drag,
                onMove = vm::moveFamily,
                onOpen = { vm.openFamily(fwm.family.id) },
                onToggle = { vm.toggleFamilySelect(fwm.family.id) },
            )
        }
            if (families.isEmpty()) {
                item {
                    // A search that matches nothing is an answer, not a failure -- but
                    // only if it says what was searched. The voters list does the same.
                    Column(Modifier.padding(Space.lg)) {
                        Text(
                            "No families match that search.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Space.sm))
                        TextButton(onClick = { vm.setFamilyQuery("") }) { Text("Clear search") }
                    }
                }
            }
        }
        // No container reserved for them: the pills float over the list, like the
        // voters list. Ticking swaps which set shows; the list underneath never
        // moves for any of them.
        if (selecting) {
            // Same overlay, ticking variant: the count and a way into the sheet.
            ExtendedFloatingActionButton(
                onClick = { actOpen = true },
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(horizontal = Space.lg, vertical = Space.md),
                icon = { Icon(Icons.Default.Tune, null) },
                text = { Text("${s.selectedFamilyCount} ticked") },
            )
        } else {
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
                // Only when there is anyone to put in one: with no voters the empty
                // state's own button is the way in, and a second one here would
                // fight it.
                if (s.totalCount > 0) {
                    ExtendedFloatingActionButton(
                        onClick = { vm.createFamily() },
                        icon = { Icon(Icons.Default.GroupAdd, null) },
                        text = { Text("New family") },
                    )
                }
            }
        }
    }

    if (actOpen) {
        FamilyActSheet(
            s, vm,
            onClose = { actOpen = false },
            onDeleteRequest = { actOpen = false; confirmingBulk = true },
        )
    }

    if (confirmingBulk) {
        val n = s.selectedFamilyCount
        AlertDialog(
            onDismissRequest = { confirmingBulk = false },
            title = { Text("Delete $n ${if (n == 1) "family" else "families"}?") },
            text = {
                Text(
                    "They come off the walking order for good. Their people stay on the list " +
                        "and go back to being on their own.\n\nThis cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteFamilies(); confirmingBulk = false }) {
                    Text("Delete", color = Status.bad)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingBulk = false }) { Text("Keep") }
            },
        )
    }
}

/**
 * A line height in dp, font scale included, which is what a type scale actually means.
 *
 * Spelled out rather than imported, because a card that clips the name when the system
 * font size is raised is worse than a card that grows with it. Multiplying by the font
 * scale only: a TextUnit is already scaled, and multiplying by `density` as well triples
 * every line -- which is exactly what it did, and exactly why the first build measured
 * 190dp instead of 82dp.
 */
private fun TextUnit.dpAt(density: Density): Dp =
    (value * density.fontScale).dp

/**
 * One family, one size, in every state.
 *
 * The height is settled here, before anything inside it is measured: two lines for the
 * name and one for the counts. That is the whole trick. A card that changes height when
 * you tick it, when a status chip appears, or when a name wraps to a second line reads
 * as the app flinching under your thumb, and on a survey screen you tap fast and often.
 *
 * Both line heights come off the type scale rather than being typed in, so raising the
 * system font size grows the card instead of clipping the name -- the one case where a
 * fixed height has to give way, and giving way is correct there.
 */
@Composable
private fun FamilyCard(
    modifier: Modifier = Modifier,
    fwm: FamilyWithMembers,
    index: Int,
    total: Int,
    dragging: Boolean,
    selecting: Boolean,
    ticked: Boolean,
    listState: LazyListState,
    drag: DragReorder,
    onMove: (String, Int) -> Unit,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    val density = LocalDensity.current
    // Two lines for the name: one carries most families, the second is there for the long
    // ones, and reserving it is what makes every card the same height.
    val nameLine = MaterialTheme.typography.titleSmall.lineHeight.dpAt(density) * 2
    // StatusChip pads its label by Space.xs above and below, so this is exactly as tall
    // as a chip -- the counts line can hold one without growing to make room.
    //
    // bodySmall, not labelSmall: the chips were raised from 11sp to 13sp because they carry
    // the state of the person ("done", "skipped", "not in file") and 11sp was the smallest
    // text in the app. Booking the line by the old size clipped the bottom off every chip on
    // this screen, which is the worst possible place to lose "not in file".
    val countsLine = MaterialTheme.typography.bodySmall.lineHeight.dpAt(density) +
        Space.xs * 2
    val cardHeight = Space.sm * 2 + nameLine + Space.xs + countsLine
    val doors = fwm.members.mapNotNull { it.house.trim().takeIf(String::isNotEmpty) }

    // Three signals together, because one alone gets missed on a phone held at arm's
    // length in sunlight: the card grows a shade, its outline warms to the accent, and
    // zIndex lifts it clear of the cards it passes -- without that last one it slides
    // *under* its neighbours, which is what makes a drag feel broken rather than plain.
    // The growth is a graphics layer, so the card keeps measuring exactly 82dp and the
    // drag step never shifts under the finger mid-gesture.
    val lift by animateFloatAsState(
        targetValue = if (dragging) 1.02f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "cardLift",
    )
    val outline by animateColorAsState(
        targetValue = if (dragging) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
        label = "cardOutline",
    )
    // The card answers the finger before the drag even starts: it settles back a shade on
    // touch-down, so a press aimed at the card feels heard rather than inert, then springs
    // up and glows when the press has actually become a lift. Without a handle to press,
    // this is the only signal that the card is a live drag surface at all.
    val pressed = drag.pressedId == fwm.family.id
    val pressScale by animateFloatAsState(
        targetValue = when {
            dragging -> 1f
            pressed -> 0.985f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "cardPress",
    )
    // Where the arrows ended up, measured rather than assumed. Dragging is the fast way to
    // reorder and the arrows are the precise one, so both live on the card -- which means
    // the card has to know where its own buttons are, so that a hold landing on an arrow
    // does not also read as a hold on the card. Row-local coordinates, the same frame the
    // gesture reports its touch in.
    var arrowBox by remember { mutableStateOf<Rect?>(null) }
    val onArrow: (Offset) -> Boolean = { p -> arrowBox?.contains(p) == true }

    // Outlined, not elevated: this screen is read outdoors, where shadows wash out and a
    // ledger line around each card survives the sun.
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = Space.xs)
            .height(cardHeight)
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                scaleX = lift * pressScale
                scaleY = lift * pressScale
            }
            .then(drag.trackHeight(fwm.family.id))
            // Reports touch-down on the card itself, now that the card is the handle.
            .then(drag.pressable(fwm.family.id))
            .combinedClickable(
                role = Role.Button,
                onClick = {
                    // A hold that [cardHandle] has already answered as a selection must not
                    // also open the card: Compose reports the tap on release regardless, and
                    // one gesture cannot both select and open.
                    if (!drag.pressAnswered) {
                        if (selecting) onToggle() else onOpen()
                    }
                },
                // No onLongClick on purpose. It used to be the selection gesture, but the
                // long press now belongs to the drag, and one press cannot mean both: with
                // it here, every hold fired this *and* [cardHandle], toggling the card twice
                // and landing on no selection at all. Selection is answered by [cardHandle]
                // alone, which is the only place that can tell a hold-that-selects from a
                // hold-that-drags. Leaving it out also means a tap still opens the card.
                onLongClick = null,
            )
            .then(
                drag.cardHandle(
                    id = fwm.family.id,
                    list = listState,
                    onStep = { by -> onMove(fwm.family.id, by) },
                    // Not onSelect: the hold arms the drag and must not also tick. Ticking
                    // has its own control on the card now, so there is no reason for one
                    // gesture to mean two things.
                    onSelect = {},
                    isControl = onArrow,
                )
            )
            // One summary for the card, and the actions it hides offered back by name.
            //
            // Merging folded the card and its two move buttons into a single node: the
            // buttons' labels overwrote the summary, and their click actions were dropped, so
            // a screen-reader user could open a family but could never tick one or move one
            // -- the whole tab was read-only. The summary is set outright so the label is not
            // overwritten, and the actions are re-offered as custom actions, which is how a
            // merged control passes on what it swallowed.
            .clearAndSetSemantics {
                contentDescription = buildString {
                    append(fwm.label.ifBlank { "Unnamed family" })
                    append(". Family ${index + 1} of $total")
                    append(". ${fwm.size} ${if (fwm.size == 1) "person" else "people"}")
                    if (doors.isNotEmpty()) append(". Door ${doors.minWith(NATURAL_HOUSE_ORDER)}")
                    append(". ${fwm.done} of ${fwm.size} visited")
                    if (fwm.complete) append(". Done")
                    if (selecting) append(if (ticked) ". Ticked" else ". Not ticked")
                }
                customActions = buildList {
                    if (!selecting) {
                        add(
                            CustomAccessibilityAction(
                                if (ticked) "Untick this family" else "Tick this family",
                            ) { onToggle(); true },
                        )
                    }
                    if (index > 0) {
                        add(
                            CustomAccessibilityAction("Move earlier in the printed order") {
                                onMove(fwm.family.id, -1); true
                            },
                        )
                    }
                    if (index < total - 1) {
                        add(
                            CustomAccessibilityAction("Move later in the printed order") {
                                onMove(fwm.family.id, 1); true
                            },
                        )
                    }
                }
            },
        colors = CardDefaults.cardColors(
            containerColor = if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.surface,
        ),
        // The accent outline is the cheapest lift signal there is: no shadow to wash out
        // in sunlight, and it costs one animated colour rather than a whole draw pass.
        border = BorderStroke(1.dp, outline), // @kraft-lint-ignore spacing.no-raw-dp — a hairline outline; the thickness is the point, and 2dp reads as a border
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(horizontal = Space.md, vertical = Space.sm)) {
            Row(
                // nameLine already holds both lines; doubling it again here would ask
                // for 80dp of header inside an 82dp card and clip the counts line.
                Modifier.height(nameLine),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The walking position, big enough to read at arm's length. Display only:
                // the export carries the member order, never this number.
                Text(
                    (index + 1).toString(),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.width(serialColumnWidth()),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(Space.sm))
                Text(
                    fwm.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // The precise path, on the card itself. Dragging is fast but moves a card a
                // whole row at a time, and holding a phone at a stranger's door one-handed
                // is not the moment for that; one tap, one place, no precision needed. Two
                // 48dp slots inside a fixed-height row, so they never change the card's size
                // -- the clamps keep them the row's height rather than letting them stretch
                // it, which is what made these cards balloon in the first place.
                Row(
                    Modifier.onGloballyPositioned {
                        val p = it.positionInParent()
                        arrowBox = Rect(p, p + Offset(it.size.width.toFloat(), it.size.height.toFloat()))
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { onMove(fwm.family.id, -1) },
                        enabled = index > 0,
                        modifier = Modifier.size(BoothMetrics.TouchTarget),
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, "Move ${fwm.label} earlier")
                    }
                    IconButton(
                        onClick = { onMove(fwm.family.id, 1) },
                        enabled = index < total - 1,
                        modifier = Modifier.size(BoothMetrics.TouchTarget),
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, "Move ${fwm.label} later")
                    }
                }
            }
            Spacer(Modifier.height(Space.xs))
            Row(
                Modifier.height(countsLine),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildString {
                        append("${fwm.size} ")
                        append(if (fwm.size == 1) "person" else "people")
                        if (doors.isNotEmpty()) append("  ${doors.minWith(NATURAL_HOUSE_ORDER)}")
                        append("  ${fwm.done}/${fwm.size} visited")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!selecting) {
                    if (fwm.complete) StatusChip("done", Status.done)
                    if (fwm.size == 0) StatusChip("empty", Status.pending)
                }
                // The tick box docks in a slot that is the same size whether it holds a
                // tick or nothing at all, exactly as on the voters list. A reserved slot
                // cannot resize the row it sits in, which a checkbox appearing in flow
                // would. The chips step aside for it while ticking, the way the voters
                // list's do: the counts line needs the width more than it needs the chip.
                // Always drawn, always a target.
                //
                // It appeared only while selecting, and the only way to start selecting was
                // to hold a card down -- which, because the hold also armed the drag, ticked
                // the family AND could reorder it in one gesture. The printed walking order
                // is the thing this app exists to produce, and that is not something to
                // change by accident.
                //
                // So the circle is always here and the hold no longer ticks. Holding now
                // only ever drags, which is the one thing a hold should mean.
                Spacer(Modifier.width(Space.xs))
                Box(
                    modifier = Modifier
                        .size(BoothMetrics.TouchTarget)
                        .toggleable(
                            value = ticked,
                            role = Role.Checkbox,
                            onValueChange = { onToggle() },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (ticked) Icons.Default.CheckBox
                        else Icons.Default.CheckBoxOutlineBlank,
                        null,
                        tint = if (ticked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(KraftSpacing.Spacing24),
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
 * The order is set by dragging a row (fast) or with the arrows (precise). The arrows stay
 * because
 * this screen is used one-handed, outdoors, often with the phone held out at a stranger's
 * door -- and because a screen reader cannot drag at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyEditScreen(vm: SurveyViewModel, s: UiState) {
    val id = s.openFamilyId ?: return
    val family = s.families.firstOrNull { it.id == id } ?: return
    val members = s.membersOf(id)
    val label = s.familyNamed(id)
    // Display only, never exported: where this card stands in the walking order.
    val position = s.familiesWithMembers.indexOfFirst { it.family.id == id }
    var confirmingDelete by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Voter?>(null) }
    // Who the long-press menu is about. Null until a row is held.
    var menuFor by remember { mutableStateOf<Voter?>(null) }
    val listState = rememberLazyListState()
    val drag = rememberDragReorder()
    val scope = rememberCoroutineScope()
    // A different family starts at its own top.
    LaunchedEffect(id) {
        if (listState.firstVisibleItemIndex != 0) listState.scrollToItem(0)
    }

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
                            buildString {
                                append("${members.size} ")
                                append(if (members.size == 1) "person" else "people")
                                if (s.familiesWithMembers.size > 1 && position >= 0) {
                                    append("  ·  family ${position + 1} of ${s.familiesWithMembers.size}")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    // The family's own place in the walking order. Kept here as well as on
                    // the card because this is the screen where the member order is being
                    // arranged, and being told "this is family 3 of 9" while standing in
                    // the list makes the two orders obviously the same thing.
                    IconButton(
                        onClick = { vm.moveFamily(id, -1) },
                        enabled = position > 0,
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, "Move earlier in walking order")
                    }
                    IconButton(
                        onClick = { vm.moveFamily(id, 1) },
                        enabled = position >= 0 && position < s.familiesWithMembers.size - 1,
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, "Move later in walking order")
                    }
                    // Undo for a move, kept quiet on purpose.
                    //
                    // Reordering is done with arrows and with hold-and-drag, both of which
                    // fire again and again. A toast on each one would be noise that buried
                    // the one that mattered, so moves record their undo without saying
                    // anything -- and this is where it is offered, on the screen whose order
                    // it changed. The printed walking order is the artefact this app exists
                    // to produce, so a drag that lands wrong has to be repairable without
                    // four more taps.
                    if (s.undo?.inToast == false) {
                        IconButton(onClick = { vm.undoLast() }) {
                            Icon(Icons.Default.Undo, "Undo the last move")
                        }
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
                    "Nobody here yet. Whoever is first names the family."
                } else {
                    // Says what the list below is *for*, once, instead of repeating the name
                    // already in bold on the first row directly underneath it.
                    "Whoever is first names the family, and prints first."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Space.lg))
            Text("Printed in this order", style = MaterialTheme.typography.titleSmall)
            // The two ways to change it, said out loud and given its own line. Holding a
            // row to drag it is not something anyone discovers by accident, and a screen
            // that expects an unwritten gesture fails quietly at a doorstep. Beside the
            // heading there is not room for both: it truncated one and butted them together.
            Text(
                // Short enough to sit on one line: the longer phrasing wrapped to two and
                // pushed the list down the screen for a sentence that only has to name the
                // controls.
                //
                // It used to say "Hold for actions", because holding was the only way to
                // reach them -- including the one action on this screen that undoes work.
                // Each row now has a three-dot button for that, and telling a 60-year-old
                // to hold a row still to find it is asking for the thing that made the
                // gesture unreliable in the first place.
                "⋯ for actions  ·  arrows to move",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.xs))

            LazyColumn(Modifier.weight(1f), state = listState) {
                items(members, key = { it.id }) { m ->
                    Column(Modifier.animateItem().fillMaxWidth()) {
                        MemberRow(
                            m = m,
                            position = members.indexOf(m) + 1,
                            total = members.size,
                            first = m.id == members.firstOrNull()?.id,
                            last = m.id == members.lastOrNull()?.id,
                            dragging = drag.draggingId == m.id,
                            pressed = drag.pressedId == m.id,
                            track = drag.trackHeight(m.id),
                            listState = listState,
                            drag = drag,
                            onHold = { menuFor = m },
                            onStep = { by -> vm.moveMember(id, m.id, by) },
                            onOpen = { vm.openVoter(m.id) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
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
            // A gap, and an inset. "Add someone" and "Delete this family" were two
            // full-width buttons eight points apart at the bottom of the screen, where a
            // thumb lands, with the destructive one second. That is the shape of an accident.
            Spacer(Modifier.height(Space.xl))
            TextButton(
                onClick = { confirmingDelete = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.md),
            ) {
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

    menuFor?.let { who ->
        val at = members.indexOfFirst { it.id == who.id }
        MemberActSheet(
            m = who,
            first = at == 0,
            last = at == members.size - 1,
            onMove = { by ->
                vm.moveMember(id, who.id, by)
                menuFor = null
            },
            onOpen = {
                menuFor = null
                vm.openVoter(who.id)
            },
            onRemoveRequest = {
                menuFor = null
                removing = who
            },
            onClose = { menuFor = null },
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
    modifier: Modifier = Modifier,
    m: Voter,
    position: Int,
    total: Int,
    first: Boolean,
    last: Boolean,
    dragging: Boolean,
    pressed: Boolean,
    track: Modifier,
    listState: LazyListState,
    drag: DragReorder,
    onHold: () -> Unit,
    onStep: (Int) -> Unit,
    onOpen: () -> Unit,
) {
    // The row's arrow buttons and its actions button, measured as they land. Set here and
    // read by the row's own gesture handler, which has to know that a hold landing on one of
    // them is not a hold on the row -- a button swallows the tap but not the hold, so without
    // this one press would both nudge the row a place and open the menu.
    var arrowBox by remember { mutableStateOf<Rect?>(null) }
    val onArrow: (Offset) -> Boolean = { p -> arrowBox?.contains(p) == true }

    val density = LocalDensity.current
    val nameLine = MaterialTheme.typography.titleSmall.lineHeight.dpAt(density)
    val detailLine = MaterialTheme.typography.bodySmall.lineHeight.dpAt(density)
    // One name line, not two. Reserving two made every row the height of a two-line name
    // and left a hole the size of a whole line above the divider on the rows that do not
    // need it -- which on this screen is most of them, since names are short. Uniform does
    // not mean padded: a row sized to what it holds is uniform too, and it reads as a row
    // rather than a card with something missing.
    // Room for the move arrows, not just for the text. They are 48dp tall; the row was
    // sized from two lines of text alone, so they were squeezed to about 36 -- under the
    // platform minimum, on the control a shaky finger most needs to be able to hit.
    val rowHeight = maxOf(Space.sm * 2 + nameLine + detailLine, BoothMetrics.TouchTarget)

    // Whether the three buttons sit beside the name or on a line of their own.
    //
    // This row grew a third button -- the one that opens the member's actions, which had
    // none -- and the name is what the row is for. On a 360dp phone, three 48dp buttons
    // plus the position number leave the name about 128dp, which truncates a Telugu name
    // even at the default font and leaves two characters at a 1.5x one. So on a phone the
    // buttons get their own line and the name takes the width back; a wider screen keeps
    // them inline, where there is room for both.
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val stacked = screenWidthDp < 420 || density.fontScale >= 1.3f

    // Same three signals as the family cards, for the same reason: grow, outline, and lift
    // clear with zIndex -- without that last one the row slides *under* the one it is
    // passing, which reads as broken rather than plain.
    val lift by animateFloatAsState(
        targetValue = if (dragging) 1.02f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "memberLift",
    )
    val outline by animateColorAsState(
        targetValue = if (dragging) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outline,
        label = "memberOutline",
    )
    // The row settles back a shade on touch-down, so the hold that will become either a
    // drag or a menu is felt before it is decided.
    val pressScale by animateFloatAsState(
        targetValue = when {
            dragging -> 1f
            pressed -> 0.985f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "memberPress",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            // A stacked row carries a second line of buttons and must be free to grow.
            .then(if (stacked) Modifier else Modifier.height(rowHeight))
            .testTag("member-row-${m.id}")
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                scaleX = lift * pressScale
                scaleY = lift * pressScale
            }
            .background(MaterialTheme.colorScheme.surface)
            // The tap lives here, on the row's own node, and not on the name inside it.
            // Compose delivers pointer events to children before parents, so a clickable
            // nested inside would fire its tap before the gesture handler below had decided
            // the press was a hold -- and every hold would open the voter *and* raise the
            // menu. Same node, same order, as the family cards.
            .combinedClickable(
                role = Role.Button,
                onClick = {
                    if (!drag.pressAnswered) onOpen()
                },
                onLongClick = null,
            )
            // The whole row is the drag surface, exactly as on the family cards: hold it
            // to pick it up, drag to reorder. The arrows are the precise path and they
            // measure themselves so a hold on them is left alone.
            .then(
                drag.cardHandle(
                    id = m.id,
                    list = listState,
                    onStep = onStep,
                    // The sheet rises the moment the hold matures -- finger still down,
                    // which is the whole point of a hold. Waiting for the release made the
                    // press feel like it had done nothing, then made the menu appear the
                    // instant the finger came up.
                    //
                    // Being up before the finger moves also means it is up before a drag
                    // could begin, so a member row offers *one* meaning per gesture: hold
                    // for the sheet, arrows (and the sheet's own move actions) to reorder.
                    // The family cards, which have nothing to put in a sheet, keep the
                    // other half of the gesture -- hold then drag to reorder.
                    onArmed = onHold,
                    // Already answered by onArmed above; nothing left to do on release.
                    onSelect = {},
                    isControl = onArrow,
                )
            )
            .then(drag.pressable(m.id))
            .then(track),
    ) {
        // Measured so a hold landing on it is left alone rather than also nudging the
        // row a place. See onArrow.
        val buttons: @Composable () -> Unit = {
            Row(
                Modifier.onGloballyPositioned {
                    val p = it.positionInParent()
                    arrowBox = Rect(
                        p,
                        p + Offset(it.size.width.toFloat(), it.size.height.toFloat()),
                    )
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { onStep(-1) },
                    enabled = !first,
                    modifier = Modifier.size(BoothMetrics.TouchTarget),
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, "Move ${m.name} earlier")
                }
                IconButton(
                    onClick = { onStep(1) },
                    enabled = !last,
                    modifier = Modifier.size(BoothMetrics.TouchTarget),
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, "Move ${m.name} later")
                }
            // The one action in the app that had no button at all.
            //
            // "Take out of this family" existed only inside the sheet that a long press
            // opens. The arrows cover move earlier and move later, and opening the voter
            // covers Open -- so taking somebody out of a household, which changes who
            // the printed form says lives there, could not be done without holding a row
            // still enough for half a second to survive as a long press.
                IconButton(
                    onClick = onHold,
                    modifier = Modifier.size(BoothMetrics.TouchTarget),
                ) {
                    Icon(Icons.Default.MoreVert, "More actions for ${m.name}")
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                // The weight is what makes this row take the leftover height of a
                // fixed-height column. A stacked row is not fixed-height, so there is no
                // leftover to take and the weighted row measured zero -- the name and the
                // serial vanished and only the buttons were left. So it is sized by its
                // content instead, which is what it needs when it is the whole row.
                .then(if (stacked) Modifier else Modifier.weight(1f))
                // Vertical as well as horizontal, and this is the whole of the "no space at
                // the top" complaint: the row's height budgets Space.sm above and below, but
                // only the horizontal padding was ever applied, so all of it pooled at the
                // bottom and the name sat flush against the top edge.
                .padding(horizontal = Space.md, vertical = Space.sm),
            // Top, not centre: a name wrapping to two lines then keeps its detail line
            // level with the row below instead of riding up out of alignment.
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                position.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(serialColumnWidth()),
            )
            Spacer(Modifier.width(Space.sm))
            Column(
                Modifier
                    .weight(1f)
                    // One announcement instead of four. Left separate, a screen reader walks
                    // "JAYRAM", "109", "91-5-5/3", "bc" as four things and the listener has to
                    // hold them in their head to work out who this is.
                    .semantics(mergeDescendants = true) {
                        contentDescription = buildString {
                            append(m.name.ifBlank { "No name" })
                            append(". Serial ${m.serial}")
                            if (m.house.isNotBlank()) append(". Door ${m.house}")
                            if (m.caste.isNotBlank()) append(". ${m.caste}")
                            // The position number beside the name is outside this node, so it
                            // was never announced. This screen's whole purpose is the order
                            // things print in, and a screen-reader user could reorder members
                            // without ever being told where they had put them.
                            append(". Position $position of $total")
                            if (first) append(". First in this family, so it is named after them")
                        }
                    },
            ) {
                Text(
                    m.name.ifBlank { "(no name)" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (first) FontWeight.Bold else FontWeight.Normal,
                    // One line, because the row is one line tall now. Telugu surnames and
                    // long names still get room: the detail line underneath is what a
                    // long name needs, not a second line of name.
                    maxLines = 1,
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Beside the name at the normal size, on a line of its own when the text is
            // large. Either way the same three controls, the same 48dp, the same actions.
            if (!stacked) buttons()
        }
        if (stacked) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) { buttons() }
        }
        // The outline is the accent while dragging and invisible otherwise, drawn as a
        // border rather than a shadow: this screen gets read outdoors too, and a shadow
        // is the first thing the sun eats.
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp) // @kraft-lint-ignore spacing.no-raw-dp — a hairline rule between rows
                .background(outline.copy(alpha = if (dragging) 1f else 0f)),
        )
    }
}

/**
 * What a held row offers: the things a row cannot show as buttons.
 *
 * "Take out of this family" lives here rather than on the row because it is the one action
 * on this screen that undoes work, and it was sitting one tap away from "move down" -- two
 * identical-looking icons, one arrow and one no-undo. A sheet costs a tap and buys the
 * separation. The moves are here too, not because the arrows are wrong but because a menu
 * that lists what a row can do is the honest place to find out what a row can do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberActSheet(
    m: Voter,
    first: Boolean,
    last: Boolean,
    onMove: (Int) -> Unit,
    onOpen: () -> Unit,
    onRemoveRequest: () -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(
                m.name.ifBlank { "(no name)" },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.md),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FamilyActionRow(Icons.Default.Person, "Open", "Their details") { onOpen() }
            FamilyActionRow(
                Icons.Default.KeyboardArrowUp,
                "Move earlier",
                "One place up the printed order",
                enabled = !first,
            ) { onMove(-1) }
            FamilyActionRow(
                Icons.Default.KeyboardArrowDown,
                "Move later",
                "One place down the printed order",
                enabled = !last,
            ) { onMove(1) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FamilyActionRow(
                Icons.Default.PersonRemove,
                "Take out of this family",
                "They stay on the voters list, on their own",
                danger = true,
            ) { onRemoveRequest() }
            Spacer(Modifier.height(Space.xl))
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

    // Not an AlertDialog. This panel is a search over 1200-odd people, and AlertDialog is
    // built for a question with two answers: it reserves a fixed title row, a fixed
    // button row, and whatever height the content happens to want. What is left over the
    // list is dead space, and it was the widest thing on the screen -- a panel mostly made
    // of nothing, with a Cancel button under it that only existed because the dialog
    // required one. So: own the surface, put the close where a close belongs, and give the
    // list the height that is actually left.
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f)
                .imePadding(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = KraftSpacing.Spacing6,
        ) {
            Column(Modifier.padding(horizontal = Space.lg)) {
                Spacer(Modifier.height(Space.lg))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Who lives at this house?",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    // Close, top right, as a plain cross. It was a Cancel button in a row of
                    // its own at the bottom, taking a whole band of the panel to say "go
                    // back", when closing is the same gesture as the cross at the top-left
                    // of every other screen in this app.
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.Default.Close,
                            "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(Space.xs))
                // The scope, so an empty search reads as everything rather than nothing.
                // Anyone already in this family is not listed at all.
                Text(
                    when {
                        // "0 people outside this family" over an empty box reads as a
                        // failure. It is the opposite: everybody is already placed.
                        candidates.isEmpty() && query.isBlank() ->
                            "Everybody in this booth is already in a family."
                        query.isBlank() -> "${candidates.size} " +
                            (if (candidates.size == 1) "person is" else "people are") +
                            " not in a family yet"
                        candidates.isEmpty() -> "Nobody here matches \"$query\"."
                        else -> "${candidates.size} ${if (candidates.size == 1) "match" else "matches"}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.sm))
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
                                Icon(Icons.Default.Close, "Clear search")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                Spacer(Modifier.height(Space.sm))
                // Weight, not a fixed height: the list takes what the panel has left over,
                // so the panel is full at every screen size and with any keyboard state.
                LazyColumn(Modifier.weight(1f)) {
                    items(candidates.take(150), key = { it.id }) { v ->
                        val elsewhere = s.familyNamed(v.familyId)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { onPick(v.id) }
                                .padding(vertical = Space.sm)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = buildString {
                                        append(v.name.ifBlank { "No name" })
                                        append(". Serial ${v.serial}")
                                        if (v.voterId.isNotBlank()) append(". Voter ID ${v.voterId}")
                                        if (v.house.isNotBlank()) append(". Door ${v.house}")
                                        if (elsewhere.isNotEmpty()) {
                                            append(". Currently in $elsewhere, picking moves them here")
                                        }
                                    }
                                },
                            // Top, like the family rows and the member rows: a long name wraps
                            // and centring would push its details out of line with the row below.
                            verticalAlignment = Alignment.Top,
                        ) {
                            // The roll serial in its own column, as the voters list has it.
                            // It was "sr 7" buried at the start of the detail line, where the
                            // eye reads it as part of the name's details rather than as the
                            // number it is -- and picking somebody out of a door is exactly
                            // the moment you are cross-checking the roll for that number.
                            Text(
                                v.serial.toString(),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.width(serialColumnWidth()),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.width(Space.sm))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        v.name.ifBlank { "(no name)" },
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    // Done or skipped travels with the name: picking someone
                                    // already visited into a family is usually a mistake.
                                    StatusPill(v)
                                }
                                // Voter ID then door number, and nothing else: those are the
                                // two things said out loud at a doorstep to check you have
                                // the right household. The roll serial is already its own
                                // column, and repeating it here only crowded the line.
                                Text(
                                    buildString {
                                        if (v.voterId.isNotBlank()) {
                                            append(v.voterId)
                                            if (v.house.isNotBlank()) append("  ${v.house}")
                                        } else if (v.house.isNotBlank()) {
                                            append(v.house)
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (elsewhere.isNotEmpty()) Status.pending
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (elsewhere.isNotEmpty()) {
                                    Text(
                                        "in $elsewhere",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Status.pending,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
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
                            .clickable(role = Role.Button) { onCreate(query) }
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
                Spacer(Modifier.height(Space.lg))
            }
        }
    }
}

/**
 * What ticking several families can do, in one place.
 *
 * Mirrors the voters' action sheet: one row per verb, the destructive one last
 * and confirmed by count. Moving families into families is nonsense, so unlike
 * the voters' sheet there is no picker page -- visited, to visit, skipped,
 * untick, delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FamilyActSheet(
    s: UiState,
    vm: SurveyViewModel,
    onClose: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onClose) {
        // Scrollable, like the voters' sheet: a short screen must never park the
        // destructive row below the fold.
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${s.selectedFamilyCount} ticked",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.selectAllFamiliesVisible() }) {
                    Text("All ${s.visibleFamilies.size}")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            FamilyActionRow(Icons.Default.Close, "Untick all", null) {
                vm.clearFamilySelection()
                onClose()
            }
            FamilyActionRow(Icons.Default.DeleteOutline, "Delete", "Families go, people stay", danger = true) {
                onDeleteRequest()
            }
            Spacer(Modifier.height(Space.xl))
        }
    }
}

/** One full-width action: icon, label, and what it does, so nothing needs guessing. */
@Composable
private fun FamilyActionRow(
    icon: ImageVector,
    label: String,
    sub: String?,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        danger -> Status.bad
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            tint = tint,
            modifier = Modifier.size(KraftSpacing.Spacing24),
        )
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    danger -> Status.bad
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) {
                Text(
                    sub,
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
