package com.kraft.voterlist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kraft.ui.tokens.KraftSpacing

/**
 * The one dropdown this app has.
 *
 * Material's `DropdownMenu` is a functioning popup and nothing more: default 48dp rows, 16sp
 * body text, no indication of what is currently chosen, no record of which field it belongs
 * to. For someone in their sixties picking a caste or a party off a printed roll in bright
 * sun, all four of those cost something. So the popup is ours.
 *
 * Four things it does that the stock one does not:
 *
 *  1. **It says which field it belongs to.** The menu is a floating window over a form. Once
 *     it is open the field under it is covered, and a list of five party names gives no clue
 *     whether it is party or caste. A pinned header carries the label with the list.
 *  2. **It shows the current value.** A tick on the selected row, so he can see where he is
 *     before he has picked, and can get back to where he was by looking rather than by memory.
 *  3. **Rows are 56dp, not 48, and the text is 18sp, not 16.** Not decoration. Both are at the
 *     low end of what stays comfortable past sixty with presbyopia, and a list he has to read
 *     carefully is exactly the case where the extra 8dp and 2sp pay for themselves.
 *  4. **It is capped and scrolls.** A list that grows to cover the screen hides the field it
 *     belongs to and the keyboard it may need to dismiss. Half the window, then it scrolls.
 *
 * Every menu in the app goes through here so that a caste picked on the voter screen behaves
 * identically to a booth picked from the app bar.
 */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    /** The field's own label, pinned at the top so an open menu still says what it is for. */
    title: String,
    modifier: Modifier = Modifier,
    /** Wider than the anchor on the booth switcher, which has two lines per item. */
    minWidth: Dp = BoothMetrics.Dropdown.MinWidth,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = minWidth),
        // The platform's own surface is switched off rather than restyled. It sits a few dp
        // outside whatever content it wraps, so tinting it left two strips of a different
        // colour showing above the header and below the last row -- one menu that looked
        // like it was made of three pieces. Transparent, the Surface below is the only thing
        // painted and the menu is one object with one edge.
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
    ) {
        // The menu paints its own surface rather than relying on the one the platform puts
        // around DropdownMenu. Left to the platform, this popup renders with a transparent
        // body and only the parts of it that draw their own background are visible -- the
        // header showed, every choice behind it did not, and the form underneath came through
        // the empty spaces. A menu you cannot see the edge of is not a menu.
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = BoothMetrics.Dropdown.TonalElevation,
            shadowElevation = BoothMetrics.Dropdown.ShadowElevation,
        ) {
            Column(Modifier.widthIn(min = minWidth)) {
                DropdownTitle(title)
                // selectableGroup so a screen reader announces the list as one set of options
                // with a position, rather than as unrelated rows.
                Column(
                    Modifier
                        .selectableGroup()
                        .heightIn(max = BoothMetrics.Dropdown.MaxListHeight)
                        .verticalScroll(rememberScrollState()),
                ) { content() }
            }
        }
    }
}

/** The pinned header. Never scrolls away, so the menu always says which field it came from. */
@Composable
private fun DropdownTitle(title: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * One choice.
 *
 * 56dp rather than the platform's 48dp, and 18sp rather than 16: see the note on
 * [AppDropdownMenu]. The tick is what tells him the current value, and it is drawn in the
 * primary colour rather than as a subtle grey so it survives being read at arm's length in
 * daylight.
 */
@Composable
fun DropdownChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    /** A second line: the booth switcher puts a place and a count under the name. */
    supporting: String? = null,
) {
    val text = if (label.isBlank()) "(none)" else label
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = BoothMetrics.Dropdown.ChoiceHeight)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                // The current value is the one line on this screen that must not be missed,
                // so it is the one that carries weight.
                fontWeight = if (selected) {
                    androidx.compose.ui.text.font.FontWeight.Bold
                } else {
                    androidx.compose.ui.text.font.FontWeight.Normal
                },
                color = when {
                    !enabled -> MaterialTheme.colorScheme.outline
                    selected -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (!supporting.isNullOrBlank()) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // The tick is reserved whether or not it is showing, so labels do not shift sideways
        // as the eye moves down a list that has one selected value in it.
        Spacer(Modifier.width(Space.sm))
        Box(Modifier.size(BoothMetrics.Dropdown.TickBox), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(KraftSpacing.Spacing24),
                )
            }
        }
    }
}

/**
 * A menu that is open but has nothing to offer.
 *
 * Says which of the two reasons applies, because they need different actions: an empty list is
 * something to fix on the lists screen, a field that depends on another is something to fix by
 * scrolling up. "Nothing here" tells him neither.
 */
@Composable
fun DropdownNote(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = BoothMetrics.Dropdown.ChoiceHeight)
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
