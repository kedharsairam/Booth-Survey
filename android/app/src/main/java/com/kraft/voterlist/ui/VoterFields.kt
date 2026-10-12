package com.kraft.voterlist.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row as Row2

/**
 * The fields on the voter form, in the order they are written and in the order the keyboard's
 * Next walks them.
 *
 * One list used twice: a person's details are entered in this order every time, and the
 * keyboard landing somewhere else is what makes a long form feel broken.
 */
val VOTER_FIELD_ORDER = listOf(
    "serial", "name", "voterId", "house", "address",
    "caste", "subCaste", "party", "phone",
)

/**
 * A group of fields with hairlines between them.
 *
 * The form used to be ten outlined boxes, each its own island with a label floating out of the
 * top edge. Ten of those is a generic text-field dump: three screens, and an empty one shows
 * its label sliced in half by its own outline. Grouping the fields into a card per section
 * puts them in one block, and the card's own edges and rules replace the boxes.
 */
@Composable
fun FormCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(top = Space.sm),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
        ),
    ) {
        Column(Modifier.padding(horizontal = Space.md), content = content)
    }
}

/** The hairline between two fields in a [FormCard]. */
@Composable
fun Rule() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** The small muted caption above a value. Always visible, never floating. */
@Composable
private fun FieldLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

/**
 * No border, no fill: the card around these fields already draws the boundary, and a second
 * box inside it is what made the form heavy.
 */
@Composable
private fun bareColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
    disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
    errorContainerColor = androidx.compose.ui.graphics.Color.Transparent,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
    disabledBorderColor = androidx.compose.ui.graphics.Color.Transparent,
    errorBorderColor = androidx.compose.ui.graphics.Color.Transparent,
    cursorColor = MaterialTheme.colorScheme.primary,
)

/**
 * The hint an empty field shows.
 *
 * Without it an empty dropdown rendered its own label in the same grey its text takes, so a
 * blank Caste read as a caste called "Caste" -- and the same on all four of them.
 */
@Composable
private fun EmptyHint() {
    Text(
        "Not set",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
    )
}

/**
 * One field: caption above, value under it, sitting in the card with its neighbours.
 *
 * An OutlinedTextField with its border and fill made transparent, not a BasicTextField. A
 * BasicTextField is the obvious choice for a borderless field and it took focus on this form
 * -- the accessibility tree reported the Full name field focused -- but no keystroke ever
 * reached it and no cursor was drawn. The transparent OutlinedTextField behaves like every
 * other field in the app, and looks like a row.
 */
@Composable
fun RowField(
    label: String,
    value: String,
    vm: SurveyViewModel,
    field: String,
    keyboard: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    scroller: FieldScroller? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Space.sm),
    ) {
        FieldLabel(label)
        OutlinedTextField(
            value = value,
            onValueChange = { vm.setField(field, it) },
            placeholder = { if (value.isEmpty()) EmptyHint() },
            modifier = Modifier
                .fillMaxWidth()
                .then(scroller?.field(field) ?: Modifier),
            singleLine = true,
            shape = MaterialTheme.shapes.extraSmall,
            colors = bareColors(),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onNext = { scroller?.focusNextAfter(field, VOTER_FIELD_ORDER) },
                onDone = { scroller?.focus(field) },
            ),
            textStyle = MaterialTheme.typography.bodyLarge,
        )
    }
}

/**
 * A field backed by one of the managed lists, which also accepts a value not on the list.
 *
 * Typing a new one adds it, so the vocabulary grows from what is actually used. That is the
 * opposite of making somebody curate a list before they can start work.
 *
 * The chevron sits beside the value rather than as a trailing icon inside it, because the
 * field is now borderless and a trailing icon would sit in the middle of the row with
 * nothing to belong to.
 */
@Composable
fun ListRow(
    label: String,
    value: String,
    vm: SurveyViewModel,
    field: String,
    kind: com.kraft.voterlist.data.Lists.Kind,
    options: List<String>,
    imeAction: ImeAction = ImeAction.Next,
    scroller: FieldScroller? = null,
) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Space.sm),
    ) {
        Row2(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            FieldLabel(label)
            OutlinedTextField(
                value = value,
                onValueChange = { vm.setField(field, it) },
                placeholder = { if (value.isEmpty()) EmptyHint() },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(scroller?.field(field) ?: Modifier),
                singleLine = true,
                shape = MaterialTheme.shapes.extraSmall,
                colors = bareColors(),
                keyboardOptions = KeyboardOptions(imeAction = imeAction),
                keyboardActions = KeyboardActions(
                    onNext = { scroller?.focusNextAfter(field, VOTER_FIELD_ORDER) },
                    onDone = { scroller?.focus(field) },
                ),
                textStyle = MaterialTheme.typography.bodyLarge,
            )
          }
            IconButton(onClick = { open = true }) {
                Icon(
                    Icons.Default.ArrowDropDown,
                    "Choose $label",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (options.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "Nothing on this list yet",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    onClick = { open = false },
                )
            }
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(o, style = MaterialTheme.typography.bodyLarge) },
                    onClick = {
                        vm.setField(field, o)
                        vm.learnValue(kind, o)
                        open = false
                    },
                )
            }
        }
    }
}