package com.kraft.voterlist.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps the field you are typing in visible above the keyboard, and walks focus along the
 * form in the order the fields are written.
 *
 * Three attempts at this, all of which had to be measured on the phone rather than reasoned
 * about, because two of them looked correct and were not:
 *
 *  - `focus.moveFocus(FocusDirection.Down)` for the keyboard's Next. Guesses the next field
 *    by geometry, and on a form whose fields sit inside cards it lands on whatever is
 *    nearest.
 *
 *  - Recording each field's offset and animating the scroll to it, reading the offset with
 *    `positionInParent`. That is the field's place within its *immediate parent* -- a field
 *    inside a Card measures zero -- so focusing a dropdown scrolled the form to the top and
 *    left the field being typed into off screen entirely.
 *
 *  - `Modifier.imeNestedScroll()` on the column. It tells the scroll container how much room
 *    the keyboard is taking, which is necessary, but on its own it does not move the form
 *    when a field takes focus. Tapping Phone number -- the last field, the one most likely
 *    to be hidden -- left it completely behind the keyboard with the cursor invisible.
 *
 * What works is asking directly: a BringIntoViewRequester per field, asked to bring itself
 * in the moment it is focused. It walks up to the scroll container and scrolls by exactly
 * what is needed, whatever depth the field sits at.
 */
@Composable
fun rememberFieldScroller(
    scroll: ScrollState,
): FieldScroller {
    val scope = rememberCoroutineScope()
    return remember(scroll) { FieldScroller(scope) }
}

@Stable
class FieldScroller internal constructor(
    private val scope: CoroutineScope,
) {
    private val focusers = mutableMapOf<String, FocusRequester>()
    private val viewers = mutableMapOf<String, BringIntoViewRequester>()

    /**
     * Everything a field needs to take focus, bring itself above the keyboard, and hand focus
     * on. Applied to the field itself.
     */
    fun field(key: String): Modifier = Modifier
        .focusRequester(focusers.getOrPut(key) { FocusRequester() })
        .bringIntoViewRequester(viewers.getOrPut(key) { BringIntoViewRequester() })
        .onFocusChanged { state ->
            if (state.isFocused) {
                scope.launch { viewers[key]?.bringIntoView() }
            }
        }

    /** Moves focus to [key]. The keyboard follows it into view on its own. */
    fun focus(key: String) {
        focusers[key]?.let { runCatching { it.requestFocus() } }
    }

    /** Moves focus to the field after [current] in [order]; nothing if it is the last. */
    fun focusNextAfter(current: String, order: List<String>) {
        val at = order.indexOf(current)
        if (at in 0 until order.lastIndex) focus(order[at + 1])
    }
}