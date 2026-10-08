package com.kraft.voterlist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.items
import com.kraft.voterlist.data.Voter
import com.kraft.ui.tokens.KraftSpacing

/**
 * Home: what the numbers are, and the two things you came to do.
 *
 * Only those. An earlier version also listed the next unassigned voters underneath, which
 * turned the screen into a second roll viewer and pushed the two buttons halfway down a
 * scrolling column. Search is one field away and the Voters tab is one tap away, so the list
 * was a third way of doing something the other two already do.
 *
 * The headline number is deliberately not the voter count. It has not changed all day and says
 * nothing about the work. It is how many are still not in a family, because that is the work.
 */
@Composable
fun HomeScreen(vm: SurveyViewModel, s: UiState) {
    // No Scaffold of its own. The shell already puts an app bar above every tab, carrying
    // which booth is open; a second one here meant two headings stacked on top of each other
    // saying different things, and a strip of the screen spent on a bar nobody reads.
    // Scrolls, because the content is taller than the screen in landscape -- where the app bar
    // and the nav bar leave about 250dp, against roughly 375dp of panel, tiles and buttons. It
    // had lost this modifier in an earlier edit, so in landscape everything below the big
    // number was simply clipped and New family / New voter could not be reached at all.
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.lg),
    ) {
        if (!s.hasBooth) {
            // Not its own scroller: a scrollable inside a scrollable throws on gesture
            // dispatch, and the parent already does this job.
            NoBoothYet(Modifier.fillMaxSize())
            return@Column
        }
        run {
            OutlinedTextField(
                value = s.query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(top = Space.md),
                placeholder = { Text("Search anyone", style = MaterialTheme.typography.bodyLarge) },
                leadingIcon = {
                    Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
            )

            if (s.query.isNotBlank()) {
                SearchResults(s, vm)
            } else {
                Spacer(Modifier.height(Space.md))

                // One card holding the number and the tiles, taking the space between them
                // and the actions. Left as bare text on the background that space read as a
                // hole in the screen; inside a card it reads as a panel, which is what it is.
                Card(
                    // Wraps its content, with a floor so the panel is never a thin sliver.
                    //
                    // It must not fillMaxSize: this Column scrolls, so the height constraint
                    // arriving here is infinite, and filling it made the card swallow the whole
                    // screen -- "New family" and "New voter" ended up below the fold and
                    // unreachable. weight(1f) is wrong for the same reason in the other
                    // direction: in landscape "whatever is left over" was nothing.
                    Modifier.fillMaxWidth().heightIn(min = BoothMetrics.HomeCardMin),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(
                        Modifier.padding(Space.lg),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        // The number that matters, given the room to be read across the street.
                        Text(
                            s.unassignedCount.toString(),
                            style = MaterialTheme.typography.displaySmall,
                            color = if (s.unassignedCount == 0) Status.done
                            else MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            if (s.unassignedCount == 1) "person not yet in a family"
                            else "people not yet in a family",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(Modifier.height(Space.lg))
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                            CountTile(
                                Modifier.weight(1f),
                                s.familyCount.toString(),
                                if (s.familyCount == 1) "family" else "families",
                                MaterialTheme.colorScheme.onSurface,
                            )
                            CountTile(
                                Modifier.weight(1f),
                                s.remainingCount.toString(),
                                // "to visit" rather than "houses left": three tiles across a
                                // phone in landscape at a large font, and "houses left" was
                                // breaking into "house / s left" while the number beside it
                                // said 1129.
                                "to visit",
                                if (s.remainingCount == 0) Status.done else Status.pending,
                            )
                            CountTile(
                                Modifier.weight(1f),
                                s.totalCount.toString(),
                                "voters",
                                MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(Space.md))
                ActionButton(
                    label = "New family",
                    caption = "Group the people at one house",
                    icon = { Icon(Icons.Default.GroupAdd, null, Modifier.size(KraftSpacing.Spacing20)) },
                    onClick = { vm.createFamily() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Space.md))
                ActionButton(
                    label = "New voter",
                    caption = "Someone who is not in the list",
                    icon = { Icon(Icons.Default.PersonAdd, null, Modifier.size(KraftSpacing.Spacing20)) },
                    onClick = { vm.startCreateVoter() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Space.xl))
            }
        }
    }
}

/**
 * The first screen, before any sheet has been imported.
 *
 * An honest empty state rather than an error or a blank list. The app deliberately ships with
 * no roll inside it -- a list of named voters with their IDs and homes has no business being
 * baked into an APK that anyone can install -- so the first thing it has to do is ask for one.
 */
@Composable
private fun NoBoothYet(modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(Space.xl),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No voters list yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(Space.md))
        Text(
            "Import the spreadsheet for the booth you are working on and it will be here. " +
                "You can keep more than one booth in the app and switch between them from the " +
                "bar at the top.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.lg))
        Text(
            "The sheet needs four columns: serial, voter id, name, house number. Any order. " +
                "If you do not have one yet, export the blank template from Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.xl))
        Text(
            "Nothing is loaded until you import it. The app carries no voter data of its own.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.xxl))
        Text(
            "Go to Settings, then Import voters list.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(Space.xxl))
    }
}

@Composable
internal fun CountTile(
    modifier: Modifier,
    big: String,
    label: String,
    accent: androidx.compose.ui.graphics.Color,
) {
    Card(
        modifier.semantics(mergeDescendants = true) { contentDescription = "$big $label" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(Space.md)) {
            Text(big, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = accent)
            Spacer(Modifier.height(Space.xs))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // One line. "families" was breaking into "familie / s" at a large font, which
                // is worse than a slightly smaller word.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ActionButton(
    label: String,
    caption: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = KraftSpacing.Spacing64),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(KraftSpacing.Spacing40), contentAlignment = Alignment.Center) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onPrimaryContainer,
                ) { icon() }
            }
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun SearchResults(s: UiState, vm: SurveyViewModel) {
    val hits = s.visible
    Spacer(Modifier.height(Space.md))
    Text(
        when (hits.size) {
            0 -> "Nobody matches that"
            1 -> "1 person"
            else -> "${hits.size} people"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Space.sm))
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
        items(hits.take(60), key = { it.id }) { v -> SearchRow(v, s, vm) }
    }
}

@Composable
private fun SearchRow(v: Voter, s: UiState, vm: SurveyViewModel) {
    val family = s.familyNamed(v.familyId)
    Surface(
        onClick = { vm.openVoter(v.id) },
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(vertical = Space.md), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    v.name.ifBlank { "(no name)" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append("sr ${v.serial}")
                        if (v.voterId.isNotBlank()) append("  ${v.voterId}")
                        if (v.house.isNotBlank()) append("  ${v.house}")
                        if (family.isNotEmpty()) append("  $family")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (v.notInLatestFile) StatusChip("not in file", Status.pending)
            Spacer(Modifier.width(Space.sm))
            StatusPill(v)
        }
    }
}

@Composable
internal fun StatusPill(v: Voter) {
    // Nothing at all for the ordinary case. On a list this long, every row saying "left" made
    // 1129 grey chips that nobody reads, and buried the two that did mean something. What is
    // worth a mark is what is unusual: done, skipped, a clash, or missing from the file.
    val (label, colour) = when {
        v.visited -> "done" to Status.done
        v.skipped -> "skipped" to Status.pending
        else -> return
    }
    Spacer(Modifier.width(Space.sm))
    StatusChip(label, colour)
}

/** A small filled surface for a status word, so they all look the same wherever they appear. */
@Composable
internal fun StatusChip(label: String, colour: androidx.compose.ui.graphics.Color) {
    Surface(color = colour, shape = MaterialTheme.shapes.extraSmall) {
        Text(
            label,
            Modifier.padding(horizontal = Space.sm, vertical = KraftSpacing.Spacing4),
            style = MaterialTheme.typography.labelSmall,
            color = Status.onStatus,
        )
    }
}

/**
 * Marks a serial that another voter also has.
 *
 * Serials are meant to be unique but are editable, and a duplicate is invisible until the
 * finalized spreadsheet arrives and two rows turn out to be the same person. Flagged rather
 * than blocked, because the fix is a correction, not a rejection.
 */
@Composable
internal fun DupBadge() {
    Surface(color = Status.bad, shape = MaterialTheme.shapes.extraSmall) {
        Text(
            "DUPLICATE",
            Modifier.padding(horizontal = Space.xs + KraftSpacing.BorderWidth, vertical = KraftSpacing.Spacing2),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Status.onStatus,
        )
    }
}

/** What a tab shows before anything has been imported. */
@Composable
internal fun EmptyTab(title: String, body: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(Space.xl)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(Space.md))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
