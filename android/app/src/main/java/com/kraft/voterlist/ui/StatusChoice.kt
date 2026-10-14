package com.kraft.voterlist.ui

import com.kraft.ui.tokens.KraftSpacing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kraft.voterlist.data.Voter

/**
 * What has been done with this person, as one three-way choice.
 *
 * It replaces two bare text buttons and a status pill sitting in a row. Those read as two
 * separate offers with a third thing reporting the state, when in fact visited and skipped
 * are one decision with three answers -- and a person who has been to the door answers all
 * three questions in one tap, not two.
 *
 * The chosen segment is filled in the colour that answer means, so the state is legible
 * without reading a word: still to do is quiet, visited is green, skipped is amber.
 */
@Composable
fun StatusChoice(v: Voter, vm: SurveyViewModel) {
    // Tapping the other answer used to clear this one with nothing said. On a segment that
    // reads as "set the state", quietly destroying a visited mark made at a door is the worst
    // thing this screen can do, so it asks first.
    var asking by remember { mutableStateOf<String?>(null) }
    val pending = !v.visited && !v.skipped
    // No caption of its own: the section heading above the card already says "Status", and
    // saying it twice on one screen is the sort of repetition that makes a form look bolted
    // together.
    Column(Modifier.padding(vertical = Space.sm)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Segment("To do", pending, null, Modifier.weight(1f)) { vm.setPending() }
            Segment("Visited", v.visited, Status.done, Modifier.weight(1f)) {
                if (v.skipped) {
                    asking = "Visited"
                } else {
                    vm.toggleVisited()
                }
            }
            Segment("Skipped", v.skipped, Status.pending, Modifier.weight(1f)) {
                if (v.visited) {
                    asking = "Skipped"
                } else {
                    vm.toggleSkipped()
                }
            }
        }
    }
    asking?.let { to ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("Mark as $to?") },
            text = {
                Text(
                    "They are marked visited now. Choosing $to clears that mark, and it " +
                        "cannot be brought back."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    asking = null
                    if (to == "Visited") vm.toggleVisited() else vm.toggleSkipped()
                }) { Text("Mark as $to", color = Status.pending) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { asking = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Segment(
    label: String,
    chosen: Boolean,
    tint: Color?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    // "To do" is the absence of an outcome, so it is chosen quietly -- a neutral fill. The
    // two answers that mean something wear the colour that means them.
    val fill = when {
        chosen && tint != null -> tint
        chosen -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
        else -> Color.Transparent
    }
    val text = when {
        chosen && tint != null -> Status.onStatus
        chosen -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier
            .heightIn(min = BoothMetrics.TouchTarget)
            .clip(RoundedCornerShape(KraftSpacing.Spacing8))
            .background(fill)
            .clickable(role = Role.RadioButton, onClick = onClick)
            // One stop for a screen reader, saying what this is and whether it is chosen.
            .semantics {
                // No contentDescription: the Text below is already merged into this node, and
                // saying it twice made a screen reader announce "To do, to do".
                stateDescription = if (chosen) "Chosen" else "Not chosen"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = text,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = BoothMetrics.Chrome.CaptionInset),
        )
    }
}
