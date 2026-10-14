package com.kraft.voterlist.ui

import androidx.compose.material.icons.automirrored.filled.Undo

import androidx.compose.material.icons.filled.MoreVert

import androidx.compose.ui.semantics.Role

import androidx.compose.foundation.selection.toggleable

import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.style.TextAlign
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
import com.kraft.ui.motion.rememberReduceMotion
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
    // The card numbers run 1..n, so the column is set by how many families there are.
    val familyNumbers = remember(s.families.size) { (1..s.families.size.coerceAtLeast(1)).toList() }
    if (!s.hasBooth) {
        EmptyTab("No voters list yet", "Import one from Settings and the families you make will appear here.")
        return
    }
    if (s.families.isEmpty()) {
        // EmptyTab, so this sits where every other empty state in the app sits -- at the
        // top, at the same inset. It was the one centred on this screen, which is what
        // made the tabs look like they belonged to different apps.
        EmptyTab(
            title = "No families yet",
            body = "A family is a group of people you decide belong together. Nothing in " +
                "the app guesses it from the door number, because a door number in a roll " +
                "does not say who lives where.\n\nUse New family when you are at a house: " +
                "pick the people who live there, put them in the order you want them " +
                "printed, and the family takes the name of whoever is first.",
        ) {
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
                onMoveTo = vm::moveFamilyTo,
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
    /** Where the arrows go: one place at a time. */
    onMove: (String, Int) -> Unit,

    /** Where a drag puts it: an absolute slot in the list. */
    onMoveTo: (String, Int) -> Unit,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    // The card numbers run 1..total, so the column is set by how many families there are --
    // which is what stops the number and the name being separated by room reserved for a
    // serial four digits long, on a card where the number is never more than two.
    val familyNumbers = remember(total) { (1..total.coerceAtLeast(1)).toList() }
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

    // The door numbers, for the spoken description below. Not on the card itself any more --
    // the detail line has to fit "N people, M of N done" beside the arrows without losing an
    // end to an ellipsis, and the door is one tap away on the family screen. Spoken, it costs
    // nothing at all, so it stays in what a screen reader says.
    val doors = fwm.members.mapNotNull { it.house.trim().takeIf(String::isNotEmpty) }

    // Three signals together, because one alone gets missed on a phone held at arm's
    // length in sunlight: the card grows a shade, its outline warms to the accent, and
    // zIndex lifts it clear of the cards it passes -- without that last one it slides
    // *under* its neighbours, which is what makes a drag feel broken rather than plain.
    // The growth is a graphics layer, so the card keeps measuring exactly 82dp and the
    // drag step never shifts under the finger mid-gesture.
    //
    // reduceMotion comes from kraft-foundation, which reads the same two things Android
    // does: Animator duration scale 0, and the accessibility "Remove animations" setting.
    // These three animations were the only ones in the app and none of them asked. Compose
    // does not read that setting for you -- there is no ANIMATOR_DURATION_SCALE reference
    // anywhere in the compose-ui artifact -- so with motion reduced, a surveyor who has
    // turned it on because movement makes them dizzy still got a card springing and
    // glowing under their finger.
    val reduceMotion = rememberReduceMotion()
    val lift by animateFloatAsState(
        targetValue = if (dragging) 1.02f else 1f,
        animationSpec = if (reduceMotion) {
            snap()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )
        },
        label = "cardLift",
    )
    val outline by animateColorAsState(
        targetValue = if (dragging) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = if (reduceMotion) snap() else tween(220),
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
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = Spring.DampingRatioMediumBouncy),
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
            // A floor, not a ceiling. Fixed height clipped the detail line at a large
            // system font: the text wrapped to a second line and the card cut it off at the
            // bottom, which is how "0 of 3 done" turned into "0 of ..." anyway. At 1.0x
            // this is exactly the card's natural height and nothing moves.
            .heightIn(min = cardHeight)
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
                    // The whole list length, so a drop past either end clamps instead of
                    // being asked for a slot that is not there.
                    itemCount = total,
                    // The destination, not a step. This is what makes a ward with two hundred
                    // families on the screen reorderable at all -- the old drag moved one
                    // place per row-height of travel, so a card could only crawl.
                    onMoveTo = { to -> onMoveTo(fwm.family.id, to) },
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
        border = BorderStroke(BoothMetrics.Chrome.Hairline, outline),
        shape = MaterialTheme.shapes.medium,
    ) {
        // Even on every side. It was Space.md across and Space.sm down, which made the
        // card look pinched top and bottom and pushed the two text lines toward each other
        // while the controls sat in a band of their own.
        Column(Modifier.padding(Space.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TwoLineRow(
                    number = (index + 1).toString(),
                    name = fwm.label.ifBlank { "Unnamed family" },
                    // How many people, and how far through them he is. The door number used
                    // to sit between them and pushed "0 of 3 visited" off the end of the
                    // line once the arrows took their share of it -- a card whose detail
                    // ended in an ellipsis was hiding the one number on it that changes as
                    // he works. The door is still on the family screen and in search; on a
                    // card, how many are left is what he is reading for.
                    detail = buildString {
                        append(fwm.size)
                        append(if (fwm.size == 1) " person  " else " people  ")
                        append(fwm.done)
                        append(" of ${fwm.size} done")
                    },
                    numberWidth = numberColumnWidth(familyNumbers),
                    lineTwoTrailing = {
                        // Move up / move down, back on the card.
                        //
                        // I took these off without being asked, which left hold-and-drag as
                        // the only way to reorder for anyone who can see the screen. That is
                        // the wrong trade: dragging a card across a list while holding the
                        // phone in one hand at somebody's door is a far harder gesture than
                        // two taps, and it is a gesture with no visible target before it
                        // starts. Drag stays, because it is the right tool for rearranging
                        // several families at once; the arrows stay too, because moving one
                        // family one place is the common case and should not require it.
                        RowIcon(
                            Icons.Default.KeyboardArrowUp,
                            "Move ${fwm.label.ifBlank { "this family" }} earlier",
                            enabled = index > 0,
                            onClick = { onMove(fwm.family.id, -1) },
                        )
                        RowIcon(
                            Icons.Default.KeyboardArrowDown,
                            "Move ${fwm.label.ifBlank { "this family" }} later",
                            enabled = index < total - 1,
                            onClick = { onMove(fwm.family.id, 1) },
                        )
                    },
                    trailing = {
                        // One control, at the far right, on the centre line between the two
                        // rows -- so the right-hand edge of every card is the same and the
                        // name never has to work around it.
                        Box(
                            modifier = Modifier
                                .size(BoothMetrics.TouchTarget)
                                .onGloballyPositioned {
                                    val p = it.positionInParent()
                                    arrowBox = Rect(
                                        p,
                                        p + Offset(it.size.width.toFloat(), it.size.height.toFloat()),
                                    )
                                }
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
                    },
                )
            }
            // The chips sit below rather than inside line two: there is not room beside the
            // numbers for a word like "done" and the checkbox both, and a chip squeezed to
            // two characters says less than no chip.
            if (!selecting && (fwm.complete || fwm.size == 0)) {
                Spacer(Modifier.height(Space.sm))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    if (fwm.complete) StatusChip("done", Status.done)
                    if (fwm.size == 0) StatusChip("empty", Status.pending)
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
    // Who the actions sheet is about. Null until a row's three-dot button is tapped -- and
    // until a row is held, because the hold is still the fast way in for anybody who already
    // knows it. It now means nothing else, which is what makes it reliable.
    var menuFor by remember { mutableStateOf<Voter?>(null) }
    val listState = rememberLazyListState()
    // The positions run 1..members.size, so the column is set by the size of the house.
    val memberPositions = remember(members.size) { (1..members.size.coerceAtLeast(1)).toList() }
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
                            Icon(Icons.AutoMirrored.Filled.Undo, "Undo the last move")
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
            // How to change it, said out loud and given its own line. An unwritten gesture
            // fails quietly at a doorstep.
            //
            // It used to say "Hold for actions", because holding was the only way to reach
            // them -- including the one action on this screen that undoes work. Each row now
            // has a three-dot button for that. It then also said a row could be *held and
            // dragged* to reorder it, which was doing two jobs at once: the same hold meant
            // "show me the menu" or "pick this up", and a family has four people in it, so
            // the drag could never have been the faster of the two. The arrows stay; the
            // drag inside a family is gone, and the long-press gesture with it.
            Text(
                "⋯ for actions  ·  ↑ ↓ to move",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.xs))

            LazyColumn(Modifier.weight(1f), state = listState) {
                items(members, key = { it.id }) { m ->
                    Column(Modifier.fillMaxWidth()) {
                        MemberRow(
                            m = m,
                            position = members.indexOf(m) + 1,
                            total = members.size,
                            first = m.id == members.firstOrNull()?.id,
                            last = m.id == members.lastOrNull()?.id,
                            onHold = { menuFor = m },
                            onStep = { by -> vm.moveMember(id, m.id, by) },
                            onOpen = { vm.openVoter(m.id) },
                            positionNumbers = memberPositions,
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

/**
 * The card's two lines and its one control, laid out the same way on both family screens.
 *
 * **Line one is the number and the name.** That is the pair a surveyor reads -- which
 * household, and how far down the list they are walking. **Line two is everything else**:
 * how many people, which door, how many visited. **The control is at the far right**, clear
 * of the text on both lines, so nothing is ever competing for the name's width and the
 * right-hand edge is the same line on every row.
 *
 * The spacing is even on all four sides rather than tight. It used to be Space.md across and
 * Space.sm down, with the controls sitting inside the first line so the text had to work
 * around them -- which is why the number and the name ended up separated by whatever was
 * left over, and why that gap grew as the numbers got longer.
 *
 * The number column is sized to the widest number actually on screen, so "1 2 3" sits close
 * to its name and "1218 1219 1220" does not leave a hole.
 */
@Composable
private fun TwoLineRow(
    number: String,
    name: String,
    detail: String,
    numberWidth: Dp,
    emphasised: Boolean = false,
    /** Controls at the end of the detail line, sharing it rather than taking a line. */
    lineTwoTrailing: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    number,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(numberWidth),
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(Space.md))
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (emphasised) FontWeight.Bold else FontWeight.Normal,
                    // Two lines. The card already budgets two -- nameLine above is
                    // titleSmall.lineHeight * 2 -- and the Text was still capped at one, so
                    // the space was being held and then thrown away on a Telugu name that
                    // most cards carry. A household name is what he is looking for at the
                    // door; "క్రీను శ్రీ సాయి రెడ్డి కొం..." is not that name.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(Space.xs))
                // The detail and whatever controls the caller puts beside it share one row.
                // Detail text is small and short -- "4 people 90-2-2/2 2 of 4 visited" -- so
                // there is roughly 230dp spare next to it, which is room for two 48dp targets.
                // Giving the controls a line of their own instead was measurably worse: a
                // second row of icons with nothing beside it, about 48dp of empty card per
                // family, on a list of ten.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // Two lines, not one. The arrows beside it are a fixed 96dp while
                        // this text scales with the system font, so at 1.3x -- a size a
                        // sixty-year-old with presbyopia is likely to be running -- "3 people
                        // 0 of 3 done" was cut to "3 people 0 of ...". The count is the one
                        // number on this card that changes as he works, and it was the part
                        // that vanished. At 1.0x this still fits on one line and looks
                        // unchanged; past that it wraps instead of disappearing.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (lineTwoTrailing != null) {
                        Spacer(Modifier.width(Space.sm))
                        lineTwoTrailing()
                    }
                }
            } else if (lineTwoTrailing != null) {
                Spacer(Modifier.height(Space.xs))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) { lineTwoTrailing() }
            }
        }
        Spacer(Modifier.width(Space.md))
        trailing()
    }
}
/**
 * A row's icon control: exactly one 48dp target, no more.
 *
 * Material's `IconButton` brings its own minimum-interactive sizing and lays itself out
 * wider than the `size()` handed to it -- measured at 96dp on a 1.5-density screen, three
 * of which is 288dp of a 720dp row. That is what was truncating every name on this screen to
 * two characters, and it cannot be argued with from the call site.
 *
 * A Box with the size set outright and the tap on it is the whole of it: the target is
 * exactly what it says, the icon is centred in it, and the 48dp is guaranteed rather than
 * hoped for.
 */
@Composable
private fun RowIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(BoothMetrics.TouchTarget)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
            // Named, and not only for a screen reader: three unlabelled chevrons in a row is
            // three controls that a glance cannot tell apart either.
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            null,
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(KraftSpacing.Spacing24),
        )
    }
}

/**
 * One person in a household, and the order they print in.
 *
 * **No drag here, and that is a decision rather than a gap.** A hold used to mean two
 * things on this row at once: pick it up to move it, or open its actions. They were told
 * apart by whether the finger then travelled, which made the menu unreliable exactly when it
 * mattered -- "take this person out of the house" is the one action on this screen that
 * undoes work, and it was behind a gesture that a shaky thumb got wrong.
 *
 * A household is four people. Moving one past another is a tap of an arrow, and two arrows
 * are honest: both are visible, both are 48dp, neither has to be discovered. The long-press
 * now means one thing only, and that thing is the menu. Long-press-to-drag stays where it
 * earns its keep: the family list, where there are hundreds of cards and one drag has to
 * carry one across the screen.
 *
 * The row itself is a plain list row rather than a card: a name, a detail line, and the
 * controls. It was drawing a surface, a scale animation on press and a drag outline that
 * could no longer happen -- three layers of decoration for something that is a line in a
 * list of four.
 */
@Composable
private fun MemberRow(
    modifier: Modifier = Modifier,
    m: Voter,
    position: Int,
    total: Int,
    first: Boolean,
    last: Boolean,
    onHold: () -> Unit,
    onStep: (Int) -> Unit,
    onOpen: () -> Unit,
    /** How many people are in this house, so the position column fits the numbers present. */
    positionNumbers: List<Int>,
) {
    val density = LocalDensity.current
    val nameLine = MaterialTheme.typography.titleSmall.lineHeight.dpAt(density)
    val detailLine = MaterialTheme.typography.bodySmall.lineHeight.dpAt(density)
    // Sized from what the row holds: one name line and one detail line, never two. Reserving
    // two made every row the height of a two-line name and left a hole above the rule on the
    // rows that do not need one, which on this screen is most of them.
    //
    // The floor is the buttons' 48dp, not the text's height. The row used to be sized from
    // the text alone and the arrows were squeezed to about 36 -- under the platform minimum,
    // on the control a shaky finger most needs to hit.
    val rowHeight = maxOf(Space.sm * 2 + nameLine + detailLine, BoothMetrics.TouchTarget)

    /**
     * Where the buttons sit.
     *
     * Inline when the screen can afford it, on their own line when it cannot. Three 48dp
     * targets are 144dp: on a 360dp phone that is two fifths of the width, and a Telugu name
     * at a readable size does not fit in what is left -- every row came out ellipsised to two
     * characters, which is worse than a taller row. Below 420dp, or at a large font, they
     * take a line of their own and the name has the whole width back.
     *
     * This was briefly loosened to 340dp on the reasoning that the buttons were oversized.
     * They were not: Material's IconButton lays itself out wider than the size handed to it,
     * which is why [RowIcon] exists. With the controls at a true 48dp the width they need is
     * known, and on a 360dp screen the answer is still a second line.
     */
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val stacked = screenWidthDp < 420 || density.fontScale >= 1.5f


    // Pressing does not scale or tint the row any more: the menu appears under the finger
    // while it is still down, so the press has already answered itself. An animation on top
    // of that is a second signal saying the same thing, slightly late.
    val controls: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(
                Icons.Default.KeyboardArrowUp,
                "Move ${m.name} earlier",
                enabled = !first,
                onClick = { onStep(-1) },
            )
            RowIcon(
                Icons.Default.KeyboardArrowDown,
                "Move ${m.name} later",
                enabled = !last,
                onClick = { onStep(1) },
            )
            // The one action in the app that had no button at all: "take out of this family",
            // which changes who the printed form says lives at that house. It used to exist
            // only inside the sheet a long press opened, which is asking for the gesture to
            // work perfectly, first time, on the one action that cannot be undone by tapping
            // it again.
            RowIcon(
                Icons.Default.MoreVert,
                "More actions for ${m.name}",
                enabled = true,
                onClick = onHold,
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // A stacked row carries a second line of buttons and must be free to grow.
            .then(if (stacked) Modifier else Modifier.height(rowHeight))
            .testTag("member-row-${m.id}")
            // One press, one meaning: open the voter. The hold is the menu, and it is
            // attached below rather than through the drag gesture, because there is no drag
            // here for it to be confused with.
            .combinedClickable(
                role = Role.Button,
                onClick = onOpen,
                onLongClick = onHold,
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Two lines, number and name above, everything else below -- the same shape as
            // the family cards. The controls sit at the end of the *second* line rather than
            // in a band of their own.
            //
            // They used to be on a line to themselves, which on a 360dp phone put three
            // 48dp targets across an otherwise empty row: a band of controls under the text
            // with nothing beside it, and a name squeezed into whatever the controls did not
            // take. On line two they share the row with the detail text instead -- there is
            // about 180dp of detail line spare and 144dp of controls, so both fit and the
            // name above gets the whole width, never truncated.
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        position.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .width(numberColumnWidth(positionNumbers))
                            .semantics { contentDescription = "Position $position of $total" },
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(Space.md))
                    Text(
                        m.name.ifBlank { "(no name)" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (first) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(Space.xs))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        buildString {
                            append("sr ${m.serial}")
                            if (m.house.isNotBlank()) append("  ${m.house}")
                            if (m.caste.isNotBlank()) append("  ${m.caste}")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // Two lines for the same reason as the family card: three fixed
                        // 48dp controls sit beside this, so at a large system font the
                        // serial -- the thing being read out at the door -- was the part
                        // that got cut.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(Space.sm))
                    controls()
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                                modifier = Modifier.width(numberColumnWidth(listOf(v.serial))),
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.width(Space.md))
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
