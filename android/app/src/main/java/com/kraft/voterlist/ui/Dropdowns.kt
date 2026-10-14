package com.kraft.voterlist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kraft.ui.tokens.KraftSpacing

/**
 * The one dropdown this app has.
 *
 * Every menu in the app goes through here, so a caste picked on the voter screen behaves
 * identically to a booth picked from the app bar.
 *
 * Five things it gets right that Material's stock menu does not, each of which was
 * something he could see:
 *
 *  1. **The value can be put back to nothing.** Once a caste is set the only way to change
 *     it was to pick a different one -- a wrong pick had to be worked around, not undone,
 *     and the only other answer was deleting the voter. There is now a *Not set* row at
 *     the top, and it is the first thing in the menu, so it does not have to be hunted for.
 *  2. **It is the width of the field it belongs to**, so its edges line up with the edges
 *     of the box that opened it. Anchored to a 48dp chevron at the far right, it opened
 *     leftwards and sat in the middle of the screen with its edges matching nothing.
 *  3. **One surface, one colour.** It used to have a tinted header band over a differently
 *     tinted body, which read as two unrelated objects rather than one menu.
 *  4. **No header.** It said "Caste" directly underneath a field labelled "Caste". The
 *     header had been there to identify the menu when it covered the field it came from;
 *     at this size it never does, so it was saying the same word twice, ten centimetres
 *     apart.
 *  5. **Rows 56dp and the label 18sp**, not the platform's 48dp and 16sp, and the tick sits
 *     in a fixed column at the far right so it forms a scannable line down the list rather
 *     than floating beside whichever label happens to be longest.
 *
 * The list is capped and scrolls, so a long party list cannot grow into a screen and hide
 * the field it came from.
 */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
            .fillMaxWidth()
            // A menu taller than this stops being a choice from a field and starts being a
            // screen, and the field it was opened from is no longer visible behind it.
            .heightIn(max = BoothMetrics.Dropdown.MaxMenuHeight),
        // The platform's own surface is switched off rather than restyled. It sits a few
        // dp outside whatever content it wraps, so tinting it left two strips of a
        // different colour above the first row and below the last -- one menu that looked
        // like it was made of three pieces.
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        tonalElevation = 0.dp,
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = BoothMetrics.Dropdown.TonalElevation,
            shadowElevation = BoothMetrics.Dropdown.ShadowElevation,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // selectableGroup so a screen reader announces the list as one set of
                    // options with a position, rather than as unrelated rows.
                    .selectableGroup()
                    .heightIn(max = BoothMetrics.Dropdown.MaxListHeight)
                    .verticalScroll(rememberScrollState()),
            ) { content() }
        }
    }
}

/**
 * One choice.
 *
 * 56dp rather than the platform's 48dp, and 18sp rather than 16sp: both are at the low
 * end of what stays comfortable past sixty, and a list he has to read carefully off a
 * printed roll is exactly the case where the extra 8dp and 2sp pay for themselves.
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
                if (label.isBlank()) "(none)" else label,
                style = MaterialTheme.typography.titleMedium,
                // The current value is the one line on this screen that must not be missed,
                // so it is the one that carries weight.
                fontWeight = if (selected) {
                    androidx.compose.ui.text.font.FontWeight.Bold
                } else {
                    androidx.compose.ui.text.font.FontWeight.Normal
                },
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.outline
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
        // A fixed column at the far right, so the ticks line up down the list and the
        // labels do not shift sideways as the eye moves. On a short label in a wide menu
        // the gap is wide, and the tick used to read as belonging to nothing.
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
 * The row that puts a value back to nothing.
 *
 * Above the choices and behind a rule, because it is a different kind of act: the others
 * set something, this one unsets it. Without it a mis-tap at a door is only repairable by
 * picking a different caste or by deleting the voter.
 */
@Composable
fun DropdownClear(
    label: String = "Not set",
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = BoothMetrics.Dropdown.ChoiceHeight)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            // Not "onSurfaceVariant" grey: this is the row that undoes a mistake, and it
            // should not look like the disabled notes underneath it.
            color = if (enabled) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.outline
            },
        )
    }
}

/**
 * A menu that is open but has nothing to offer.
 *
 * Says which of the two reasons applies, because they need different actions: an empty
 * list is something to fix on the lists screen, a field that depends on another is
 * something to fix by scrolling up. "Nothing here" tells him neither.
 */
@Composable
fun DropdownNote(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = BoothMetrics.Dropdown.ChoiceHeight)
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A rule between the part that unsets and the part that chooses. */
@Composable
fun DropdownDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Vertical space above a menu, so it does not sit flush against the field's underline. */
@Composable
fun DropdownGap() {
    Spacer(Modifier.height(BoothMetrics.Dropdown.GapAbove))
}
