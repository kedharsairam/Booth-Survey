package com.kraft.voterlist.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Drag-to-reorder for the family screens, with no library.
 *
 * **A drag now puts the card where it is dropped.** It used to move one place per row-height
 * of finger travel, which is fine for a family of four and unusable for a ward with two
 * hundred families on the list: getting a card from tenth-from-top to eighth-from-bottom
 * meant dragging most of the screen twice. The finger's position is turned into a target
 * slot and the card goes there, with the rest of the list animating aside. The arrows stay
 * for precision and because a screen reader cannot drag at all.
 *
 * Three things make it feel finished rather than merely work: a tick of haptic when the
 * target changes so the move is felt and not only seen; a frame loop that keeps the list
 * moving while the finger is held past an edge instead of one lurch per drag event; and a
 * clean stop on drop, because a drag that ends in a dead pump leaves the list twitching.
 *
 * One instance per scrolling list. Call [trackHeight] on the row and [cardHandle] on the
 * card, both keyed by the same item id.
 */
@Composable
fun rememberDragReorder(): DragReorder {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    return remember(scope, haptics, density) { DragReorder(scope, haptics, density) }
}

@Stable
class DragReorder internal constructor(
    private val scope: CoroutineScope,
    private val haptics: HapticFeedback,
    density: Density,
) {
    /** How close to the edge the finger must get before the list starts moving itself. */
    private val bandPx = with(density) { 56.dp.toPx() }

    /** Ceiling on that self-scroll, per frame. Live enough to keep up, slow enough to aim. */
    private val maxScrollPx = with(density) { 18.dp.toPx() }

    /**
     * How far a held finger must travel before the hold becomes a drag rather than a
     * selection. Deliberately wider than the system touch slop: a shaky thumb resting on a
     * card should finish as a selection, and only a real pull should lift it.
     */
    private val pickUpSlopPx = with(density) { 14.dp.toPx() }

    /** The item under the finger, or null. Rows read this to draw themselves dragged. */
    var draggingId by mutableStateOf<String?>(null)
        private set

    /**
     * The item whose grip a finger has landed on but not yet picked up, or null.
     *
     * The long press that starts a drag takes its full ~500ms, and until it fires nothing
     * at all happens on the grip -- which reads as a dead control rather than a slow one.
     * Rows read this to answer the touch the instant it lands.
     */
    var pressedId by mutableStateOf<String?>(null)
        private set

    /**
     * True once this gesture has been answered as a long press, so the tap underneath it
     * knows to stand down.
     *
     * The card is both long-pressable and clickable, and Compose reports the tap when the
     * finger lifts even after a long press -- so a hold that selects would also open the
     * card. Two gestures cannot both own one press; this is how the loser finds out.
     */
    var pressAnswered by mutableStateOf(false)
        private set

    /**
     * Reports press-down on a grip, separately from [handle].
     *
     * Both sit on the same node and neither consumes the other's gesture: this one only
     * watches when the last finger leaves, so sliding off the grip and back on does not
     * make the press state flicker.
     */
    fun pressable(id: String): Modifier = Modifier.pointerInput(id) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            pressedId = id
            var stillDown = true
            while (stillDown) {
                stillDown = awaitPointerEvent().changes.any { it.pressed }
            }
            pressedId = null
        }
    }

    private val rowHeights = mutableMapOf<String, Float>()

    /** Finger position within the dragged card, refreshed as the finger moves. */
    private var fingerLocalY = 0f

    /** Signed per-frame self-scroll demanded by the finger's position, 0 when inside. */
    private var pull = 0f

    private var pump: Job? = null
    private var liveId: String? = null

    /** The list being reordered, kept so a target can be worked out without the caller. */
    private var liveList: LazyListState? = null

    /** How many items the list holds, so a target past either end can be clamped. */
    private var liveCount = 0

    /** The slot last asked for, so the same one is not asked for twice. */
    private var liveTarget: Int? = null
    private var liveMoveTo: ((Int) -> Unit)? = null

    /** Records the row's height, used only to centre the finger's target calculation. */
    fun trackHeight(id: String): Modifier =
        Modifier.onGloballyPositioned { rowHeights[id] = it.size.height.toFloat() }

    /**
     * The whole-row gesture: press and hold to pick the row up, drag to move it.
     *
     * Not an immediate drag like [handle], because the row *is* the list -- a row that
     * reordered on touch-slop would swallow every scroll gesture made on it, and these rows
     * cover the screen, so the list could not be scrolled at all. The long press is what
     * separates the two: flick to scroll, press and hold to pick up.
     *
     * And not `detectDragGesturesAfterLongPress`, which cannot say what the press was for:
     * it fires on the long press itself, which is too early to know whether the finger was
     * about to move a card or to open a menu. Maturity is decided here, on a timer, and
     * handed to [onArmed] while the finger is still down -- so the caller can put something
     * on screen at the moment the press becomes deliberate. An immature press is left
     * entirely alone, so taps and scrolls still reach the clickable and the list.
     *
     * Two shapes of caller, because a row can only mean one thing per gesture:
     *  - the family cards pass [onSelect] as [onArmed], so hold ticks immediately and hold
     *    then drag reorders;
     *  - member rows pass a sheet as [onArmed] and leave [onSelect] empty, because the
     *    sheet is on screen before a drag could begin and the two would fight over the
     *    same press.
     */
    fun cardHandle(
        id: String,
        list: LazyListState,
        itemCount: Int,
        onMoveTo: (Int) -> Unit,
        onSelect: () -> Unit,
        /**
         * True for touches that landed on the row's own buttons rather than on the row.
         *
         * Those buttons consume the tap, but not the *hold*: a long press starting on an
         * arrow still arrives here, and without this it would both nudge the card a place
         * and select it. Answered by real measured bounds, not a guessed offset, so it
         * stays right whatever padding and scale the card is carrying. [Offset] is the
         * touch in the row's own coordinates, the same frame the bounds are measured in.
         */
        isControl: (Offset) -> Boolean = { false },
        /**
         * Called the moment the press matures, while the finger is still down.
         *
         * A context menu that appears only after you let go is a menu you wait for, which
         * is the wrong shape for the whole gesture: the hold should *become* the menu. The
         * caller is expected to leave it cancellable -- if the finger then travels far
         * enough to be a drag, that is a different intent and this sheet is dismissed
         * rather than left hanging over the list.
         */
        onArmed: () -> Unit = {},
    ): Modifier = Modifier.pointerInput(id, list) {
        val heldFor = viewConfiguration.longPressTimeoutMillis
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (isControl(down.position)) return@awaitEachGesture
            val heldFrom = down.uptimeMillis
            pressAnswered = false
            var armedHere = false

            var lastY = down.position.y
            var travel = 0f
            var pickedUp = false

            // Maturity is a *timer*, not something to notice on the next event. A finger
            // held perfectly still produces no pointer events at all, so a handler that
            // waits for one to check the clock does not check it until the finger moves or
            // lifts -- which is exactly the complaint that the menu only appeared on
            // release. Racing the timeout against the event stream makes a still hold
            // mature on its own.
            //
            // Returns false if the press is given up on first: moved past slop while still
            // young (that is a flick to scroll, not ours), or lifted early (a tap).
            val stillWaiting = withTimeoutOrNull(heldFor) {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                        ?: return@withTimeoutOrNull false
                    if (!change.pressed) return@withTimeoutOrNull false
                    val dy = change.position.y - lastY
                    lastY = change.position.y
                    travel += dy
                    if (abs(travel) >= pickUpSlopPx) return@withTimeoutOrNull false
                }
                @Suppress("UNREACHABLE_CODE")
                true
            }
            // Timed out rather than returning: the press matured, finger still down.
            if (stillWaiting == null) {
                armedHere = true
                pressAnswered = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onArmed()
                // The card path answers a hold by selecting it, and that must not wait for
                // the release -- the same reason the sheet does not. Selecting on release
                // made the tick land a beat after the finger came up, which is a dropped
                // tap as far as the thumb is concerned.
                onSelect()
            }

            while (true) {
                // One event at a time, with the default timeout. Compose delivers transient
                // events mid-drag whose tracked change reads as not-pressed, so a naive
                // `if (!change.pressed) break` ends the drag after the first move and the
                // row only ever twitches.
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val dy = change.position.y - lastY
                lastY = change.position.y
                travel += dy

                if (!change.pressed) break
                if (!pickedUp) {
                    // Armed and now past slop: the hold has become a drag. The haptic is
                    // what tells the thumb which of the two it just turned into.
                    if (abs(travel) < pickUpSlopPx) continue
                    pickedUp = true
                    pressAnswered = true
                    fingerLocalY = 0f
                    pull = 0f
                    draggingId = id
                    liveId = id
                    liveList = list
                    liveCount = itemCount
                    liveTarget = null
                    liveMoveTo = onMoveTo
                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    startPump(list)
                }
                change.consume()
                pull = edgePull(list, change.position.y)
                fingerLocalY += dy
                reposition()
            }
            if (pickedUp) drop()
        }
    }

    /**
     * Turns the finger's position into a slot, and asks for it once per change.
     *
     * The half of the item the finger is past decides which of the two slots it means: above
     * the middle it goes *before* that card, below it *after*. Without that half it feels
     * sticky, because a card will not move at all until your thumb is past its centre --
     * which, on a tall card, is most of the drag.
     *
     * `TextHandleMove` is the type Android means for "a drag handle moved one notch"; on
     * anything older than API 34 it is silently ignored, which is the right failure -- a
     * missing buzz costs nothing, a wrong buzz costs trust.
     */
    private fun reposition() {
        val list = liveList ?: return
        val move = liveMoveTo ?: return
        val id = liveId ?: return
        val visible = list.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return

        // The dragged card's own offset in the list, found by key rather than by index:
        // the index moves every time the card is reordered, and the key does not.
        val mine = visible.firstOrNull { it.key == id }
        val fingerY = (mine?.offset ?: visible.first().offset).toFloat() + fingerLocalY

        // Above or below the middle of the card under the finger decides which of the two
        // slots it means: above the middle it goes *before* that card, below it *after*.
        //
        // The midpoint, not the edge, is the threshold on purpose. A card is therefore not
        // swapped out from under a finger that has only just arrived on its neighbour -- the
        // move happens when the finger commits to being past it. The cost is that clearing
        // one card takes rather more than one card-height of travel; the point of the rework
        // is that the finger can then travel any distance in one movement rather than only
        // one card-height at a time.
        val mineIndex = visible.firstOrNull { it.key == id }?.index
        var slot: Int? = null
        for (v in visible) {
            val top = v.offset.toFloat()
            if (fingerY < top) break
            if (fingerY < top + v.size) {
                val mid = top + v.size / 2f
                slot = if (fingerY > mid) v.index + 1 else v.index
                break
            }
        }
        // Past the last visible card, or before the first: the ends of the list.
        if (slot == null) {
            slot = if (fingerY < visible.first().offset) {
                visible.first().index
            } else {
                visible.last().index + 1
            }
        }

        // **The dragged card is itself one of the slots being counted.** The layout above
        // still includes it, so "go after the third card" is a slot of 3 even when the card
        // being dragged is the second one -- and moving it to index 3 would put it back
        // where it already was. Subtracting one when the slot is past its own index is what
        // turns the slot into the final position the move needs.
        val slotValue = slot
        val target = if (mineIndex != null) {
            com.kraft.voterlist.data.slotToFinalIndex(slotValue, mineIndex)
        } else {
            slotValue
        }

        val clamped = target.coerceIn(0, maxOf(liveCount - 1, 0))
        if (clamped == liveTarget) return
        liveTarget = clamped
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        move(clamped)
    }

    /** How hard the finger is pulling past an edge, as a signed per-frame scroll. */
    private fun edgePull(list: LazyListState, y: Float): Float {
        val info = list.layoutInfo
        if (info.viewportEndOffset <= info.viewportStartOffset) return 0f
        val top = info.viewportStartOffset + bandPx
        val bottom = info.viewportEndOffset - bandPx
        return when {
            y < top -> -maxScrollPx * ((top - y) / bandPx).coerceIn(0f, 1f)
            y > bottom -> maxScrollPx * ((y - bottom) / bandPx).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    /**
     * One frame loop for the whole drag, started at pickup.
     *
     * A drag event only arrives when the finger moves, so scrolling off the raw event is
     * what made a held finger stop dead at the edge. Here the loop keeps running between
     * events, and the distance the list actually travelled is banked as drag: to a finger
     * resting still, a list moving under it is the same as the finger moving.
     */
    private fun startPump(list: LazyListState) {
        if (pump?.isActive == true) return
        pump = scope.launch {
            while (isActive && draggingId != null) {
                val wanted = pull
                if (wanted != 0f) {
                    if (list.scrollBy(wanted) != 0f) {
                        // The list moving under a still finger is the same as the finger
                        // moving, so the target is worked out again from scratch. That is
                        // what lets a card held past the edge keep changing places instead
                        // of only sliding.
                        reposition()
                    }
                }
                withFrameNanos { }
            }
        }
    }

    private fun drop() {
        draggingId = null
        pull = 0f
        fingerLocalY = 0f
        liveId = null
        liveList = null
        liveCount = 0
        liveTarget = null
        liveMoveTo = null
        pump?.cancel()
        pump = null
        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
    }
}
