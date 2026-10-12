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
 * A drag moves the item one step at a time through the same `moveMember`/`moveFamily`
 * the arrows call, so the tested 0..n-1 reindexing is reused rather than reimplemented:
 * each time the finger travels one row-height past the last step, one more step fires and
 * the list re-renders under the finger. The arrows stay beside the handle -- dragging is
 * fast, arrows are precise, and a screen reader cannot drag at all.
 *
 * Four things make it feel finished rather than merely work, and each is a thing that was
 * missing: a tick of haptic on every step so the move is felt, not just seen; a frame loop
 * that keeps the list moving while the finger is held past an edge instead of one lurch per
 * drag event; the list's own travel fed back into the same accumulator as the finger's, so
 * a card held past the edge keeps changing places rather than only sliding; and a clean
 * stop on drop, because a drag that ends in a dead pump leaves the list twitching.
 *
 * One instance per scrolling list: row heights and the dragged id are per-list state.
 * Call [trackHeight] on the row and [handle] on its grip, both keyed by the same item id.
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

    /** Drag distance banked toward the next reorder step, from the finger and the list alike. */
    private var carry = 0f

    /** Signed per-frame self-scroll demanded by the finger's position, 0 when inside. */
    private var pull = 0f

    private var pump: Job? = null
    private var liveId: String? = null
    private var liveStep: ((Int) -> Unit)? = null

    /** Records the row's height, which is one drag-step for this item. */
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
        onStep: (Int) -> Unit,
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
                    carry = 0f
                    pull = 0f
                    draggingId = id
                    liveId = id
                    liveStep = onStep
                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    startPump(list)
                }
                change.consume()
                pull = edgePull(list, change.position.y)
                carry += dy
                stepOnce()
            }
            if (pickedUp) drop()
        }
    }

    /**
     * The grip an item is dragged by. [onStep] moves it one place (+1 down, -1 up).
     *
     * Holding past an edge scrolls the list with the finger; the list scrolling feeds back
     * into the same accumulator, so a card held there walks down the order on its own.
     *
     * A plain drag, not a long-press one, and that is deliberate. Rows here are long-press
     * selectable, and a long press on a grip arrives at the row underneath as well -- so
     * the two fired together and every reorder also selected the row it moved. A handle
     * that picks up as soon as the finger moves has no long press left to collide with.
     */
    fun handle(id: String, list: LazyListState, onStep: (Int) -> Unit): Modifier =
        Modifier.pointerInput(id, list) {
            detectDragGestures(
                onDragStart = {
                    carry = 0f
                    pull = 0f
                    draggingId = id
                    liveId = id
                    liveStep = onStep
                    // Threshold rather than LongPress: the card has not been picked up
                    // yet, it has just stopped being a tap.
                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    startPump(list)
                },
                onDragEnd = { drop() },
                onDragCancel = { drop() },
                onDrag = { change, amount ->
                    change.consume()
                    pull = edgePull(list, change.position.y)
                    carry += amount.y
                    stepOnce()
                },
            )
        }

    /**
     * Turns banked distance into whole steps, one haptic per step.
     *
     * `TextHandleMove` is the type Android means for "a drag handle moved one notch"; on
     * anything older than API 34 it is silently ignored, which is the right failure -- a
     * missing buzz costs nothing, a wrong buzz costs trust.
     */
    private fun stepOnce() {
        val id = liveId ?: return
        val onStep = liveStep ?: return
        val step = rowHeights[id] ?: 200f
        while (carry >= step) {
            onStep(1)
            carry -= step
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        while (carry <= -step) {
            onStep(-1)
            carry += step
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
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
                    val travelled = list.scrollBy(wanted)
                    if (travelled != 0f) {
                        carry += travelled
                        stepOnce()
                    }
                }
                withFrameNanos { }
            }
        }
    }

    private fun drop() {
        draggingId = null
        pull = 0f
        carry = 0f
        liveId = null
        liveStep = null
        pump?.cancel()
        pump = null
        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
    }
}
