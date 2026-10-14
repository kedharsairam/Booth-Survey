package com.kraft.voterlist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

/**
 * One row of a split bar: a name, a bar, and the two numbers that bar stands for.
 *
 * This is the whole chart vocabulary in this app. There is no pie, no donut and no legend,
 * and that is deliberate rather than a limitation:
 *
 *  - **A bar with the numbers written on it needs no decoding.** A pie at this size is a
 *    set of wedges he has to compare by area, on a phone, outdoors, at sixty. A bar with
 *    "34 of 180" beside it says the same thing without the work.
 *  - **The numbers are never colour-only.** The done and the left are both written out,
 *    so the bar is a shape that agrees with words rather than a colour that has to be
 *    decoded against a key.
 *  - **Every row is the same width**, so the bars can be compared down the list. That is
 *    the entire point of a chart and the reason these are not three differently sized cards.
 *
 * The bar's two parts are the visited and the not-yet-visited of the same group, so the
 * reader always sees the question they care about -- how much of *this* group is done --
 * rather than a share of a total they have to reconstruct.
 */
@Composable
fun SplitBarRow(
    label: String,
    done: Int,
    total: Int,
    accent: Color,
    /** Sub-castes sit indented and quieter under the caste they belong to. */
    nested: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val left = (total - done).coerceAtLeast(0)
    val fraction = if (total <= 0) 0f else done.toFloat() / total.toFloat()
    val spoken = when {
        total == 0 -> "none recorded"
        done == total && total > 0 -> "all $done done"
        else -> "$done of $total done"
    }
    Row(
        modifier
            .fillMaxWidth()
            // One announcement for the row rather than five: the label, two numbers and two
            // coloured boxes are otherwise six separate stops to walk through.
            .clearAndSetSemantics { contentDescription = "$label, $spoken, $left left" }
            .padding(vertical = Space.xs, horizontal = if (nested) Space.lg else Space.none),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = if (nested) {
                MaterialTheme.typography.bodyMedium
            } else {
                MaterialTheme.typography.titleSmall
            },
            color = if (nested) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Spacer(Modifier.width(Space.sm))
        Text(
            spoken,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.width(Space.sm))
        // Fixed width so every bar in the chart is the same length and can be compared
        // down the list. Without it a bar is as wide as its own label and the chart says
        // nothing.
        SplitBar(
            fraction, left > 0, accent,
            if (nested) BoothMetrics.Chart.BarHeightNested else BoothMetrics.Chart.BarHeight,
        )
    }
}

/**
 * The bar itself: a track, a filled portion, and the remainder in a muted colour.
 *
 * Drawn rather than composed out of a Row with weights, so the filled part is a single
 * rounded shape that can be a fraction of a fraction -- "2 of 180" is a sliver, and at
 * 2dp it still has to be visible as teal rather than disappearing.
 */
@Composable
private fun SplitBar(
    fraction: Float,
    hasRemainder: Boolean,
    accent: Color,
    height: androidx.compose.ui.unit.Dp,
) {
    Box(
        Modifier
            .width(BoothMetrics.Chart.BarWidth)
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .height(height)
                    .clip(RoundedCornerShape(height / 2))
                    .background(accent),
            )
        }
        if (hasRemainder && fraction >= 0.98f) {
            // 100% done still needs the left end to read as an edge rather than as a
            // missing piece, so the track's own colour is what shows through.
            Spacer(Modifier.size(0.dp))
        }
    }
}

/**
 * A titled block of split bars.
 *
 * The heading carries the question rather than the subject -- "Caste", yes, but with the
 * count of people who have one recorded beside it, because a chart of the ward that
 * silently covers forty people and says nothing about the other twelve hundred is worse
 * than no chart.
 */
@Composable
fun SplitChart(
    title: String,
    subtitle: String,
    rows: List<SplitRow>,
    accent: Color,
    emptyMessage: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(Space.lg)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Space.xs))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.md))
            if (rows.isEmpty()) {
                Text(
                    emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                rows.forEachIndexed { i, r ->
                    if (i > 0) Spacer(Modifier.height(Space.xs))
                    SplitBarRow(
                        label = r.label,
                        done = r.done,
                        total = r.total,
                        accent = accent,
                        nested = r.nested,
                    )
                }
            }
        }
    }
}

/** One line in a [SplitChart]. */
data class SplitRow(
    val label: String,
    val done: Int,
    val total: Int,
    val nested: Boolean = false,
)

/**
 * Counts a group out of the booth, by whatever the field holds.
 *
 * Two numbers come back and the difference matters. `recorded` is how many people have
 * anything in this field at all -- zero on the day the roll is imported, because the roll
 * carries no caste or party. `done` is how many of those have been spoken to. A chart built
 * on only the second would read as "nobody is BC" on a ward that is mostly BC.
 */
fun splitCounts(
    voters: List<com.kraft.voterlist.data.Voter>,
    value: (com.kraft.voterlist.data.Voter) -> String,
): List<Pair<String, Pair<Int, Int>>> {
    val total = LinkedHashMap<String, Int>()
    val done = LinkedHashMap<String, Int>()
    for (v in voters) {
        val key = value(v).trim()
        if (key.isEmpty()) continue
        total[key] = (total[key] ?: 0) + 1
        if (v.isDone) done[key] = (done[key] ?: 0) + 1
    }
    // Biggest group first: it is the one he is most likely to be inside, and a fixed
    // order would make two charts of the same ward look different.
    return total.keys.sortedWith(
        compareByDescending<String> { total[it] ?: 0 }.thenBy { it },
    ).map { it to ((done[it] ?: 0) to (total[it] ?: 0)) }
}
