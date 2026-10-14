package com.kraft.voterlist.ui
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.text.BasicText

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    val scroll = rememberScrollState()
    val focus = LocalFocusManager.current
    // Same as the voters list: scrolling or tapping away hands focus back, so the keyboard
    // never outstays the search that raised it.
    LaunchedEffect(scroll.isScrollInProgress) {
        if (scroll.isScrollInProgress) focus.clearFocus()
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .pointerInput(focus) { detectTapGestures(onTap = { focus.clearFocus() }) }
            .padding(horizontal = Space.lg),
    ) {
        if (!s.hasBooth) {
            // Not its own scroller: a scrollable inside a scrollable throws on gesture
            // dispatch, and the parent already does this job. No fillMaxSize either --
            // inside a scrolling parent that hands the child an infinite height, which
            // is the other half of the same crash.
            NoBoothYet(Modifier)
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
                // A way out that is one tap. Without it the only route back to the day's
                // list was to select the whole line and delete it, which needs two hands on
                // a phone held in one. OutlinedTextField gives trailingIcon the default
                // 48dp button, which is left as it is -- a smaller X here is a miss.
                trailingIcon = {
                    if (s.query.isNotBlank()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Default.Close, "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
            )

            if (s.query.isNotBlank()) {
                SearchResults(s, vm)
            } else {
                Spacer(Modifier.height(Space.md))

                // The morning question -- where do I stand -- answered in one panel: how many
                // are left, how far along that is, and the four counts that make it up. Left
                // as bare text on the background that space read as a hole in the screen;
                // inside a card it reads as a panel, which is what it is.
                //
                // It must not fillMaxSize: this Column scrolls, so the height constraint
                // arriving here is infinite, and filling it made the card swallow the whole
                // screen -- "New family" and "New voter" ended up below the fold and
                // unreachable. weight(1f) is wrong for the same reason in the other
                // direction: in landscape "whatever is left over" was nothing.
                Card(
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
                            if (s.remainingCount == 0) "All visited"
                            else "${s.remainingCount} left to visit",
                            style = MaterialTheme.typography.headlineSmall,
                            color = if (s.remainingCount == 0) Status.done
                            else MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(Space.xs))
                        Text(
                            "${s.doneCount} of ${s.totalCount} visited" +
                                if (s.familyCount == 1) "  ·  1 family"
                                else "  ·  ${s.familyCount} families",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Skipped is not finished, and the headline above no longer hides it
                        // -- so say it here, where he reads before deciding to pack up.
                        if (s.skippedCount > 0) {
                            Text(
                                "${s.skippedCount} skipped — still to come back to",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Status.pending,
                            )
                        }
                        Spacer(Modifier.height(Space.md))
                        // A bar rather than a fourth number: progress reads at a glance, and
                        // the tiles below already carry the exact counts.
                        LinearProgressIndicator(
                            progress = { if (s.totalCount == 0) 0f else s.doneCount.toFloat() / s.totalCount },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(Space.lg))
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                            CountTile(
                                Modifier.weight(1f),
                                s.remainingCount.toString(),
                                "to visit",
                                if (s.remainingCount == 0) Status.done else Status.pending,
                            )
                            CountTile(
                                Modifier.weight(1f),
                                s.doneCount.toString(),
                                "visited",
                                MaterialTheme.colorScheme.onSurface,
                            )
                            CountTile(
                                Modifier.weight(1f),
                                s.unassignedCount.toString(),
                                // Short words: three tiles across a phone in landscape at a
                                // large font, and anything longer breaks mid-word.
                                "alone",
                                if (s.unassignedCount == 0) Status.done
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                if (s.notInFileCount > 0) {
                    Spacer(Modifier.height(Space.md))
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(Modifier.padding(Space.lg)) {
                            Text(
                                if (s.notInFileCount == 1) "1 person is not in the latest file"
                                else "${s.notInFileCount} people are not in the latest file",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(Modifier.height(Space.xs))
                            Text(
                                "They stay on the list. Find them under Voters with the " +
                                    "Not in file filter.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
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
    // Top-aligned and scrolled by the screen around it, like every other empty state in
    // the app now does. This one used to be the genuinely centred one -- it had no scroll
    // on it, so Arrangement.Center did what it says -- and that is why the same idea sat
    // high on two tabs and in the middle of another. See EmptyTab for why top wins.
    EmptyTab(
        title = "No voters list yet",
        body = "Import the spreadsheet for the booth you are working on and it will be " +
            "here. You can keep more than one booth in the app and switch between them " +
            "from the bar at the top.",
        modifier = modifier,
        scrollable = false,   // HomeScreen already scrolls.
        padding = PaddingValues(horizontal = 0.dp, vertical = Space.lg),       // ...and already pads by Space.lg.
    ) {
        Spacer(Modifier.height(Space.lg))
        Text(
            // It used to end "export the blank template from Settings" -- and there is no
            // such button, so it sent him looking for one that does not exist.
            "The sheet needs four columns: serial, voter id, name, house number. Any order.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.lg))
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
        // clearAndSetSemantics, not semantics(mergeDescendants = true): merging left the
        // two child Texts in the tree as stops of their own, so a screen reader said
        // "12 to visit" and then "12" and then "to visit". This sets the label once and
        // drops the children, which is what the card is for.
        modifier.clearAndSetSemantics { contentDescription = "$big $label" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(Space.md)) {
            // One line, and it steps down rather than wrapping. At a large font size a
            // four-digit count was breaking across two lines -- "121 / 5" -- in the tile
            // that answers the only question he opens the app to ask.
            AutoSizeNumber(big, accent)
            Spacer(Modifier.height(Space.xs))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Allowed to wrap. A tile is a card and can grow, and "to v..." at a large
                // font tells him less than "to" / "visit" over two lines.
            )
        }
    }
}

/**
 * A count that stays on one line and stays whole.
 *
 * Stepping the size down on the digit count was not enough: at a large font a four-digit
 * count still did not fit a third of the screen and was ellipsised to "1...", which is
 * worse than the line-wrap it replaced -- it is not a smaller number, it is a different
 * number. This measures the text and steps the size down until the whole of it fits, so
 * the day's remaining work is never abbreviated.
 */
@Composable
private fun AutoSizeNumber(big: String, accent: androidx.compose.ui.graphics.Color) {
    var size by remember { mutableFloatStateOf(26f) }
    BasicText(
        text = big,
        maxLines = 1,
        softWrap = false,
        onTextLayout = { r ->
            if (r.hasVisualOverflow && size > 12f) size -= 1f
        },
        modifier = Modifier.fillMaxWidth(),
        style = TextStyle(
            fontSize = size.sp,
            lineHeight = (size * 1.2f).sp,
            fontWeight = FontWeight.Bold,
            color = accent,
        ),
    )
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
    val hits = s.searchHits
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
    // A plain Column, not a LazyColumn: this already sits inside the screen's scrolling
    // Column, and a scrolling list inside an unbounded scroller measures against infinity
    // and crashes the app the moment a search matches anything. Sixty simple rows compose
    // cheaply enough that laziness buys nothing here.
    Column(Modifier.fillMaxWidth()) {
        hits.take(60).forEach { v -> SearchRow(v, s, vm) }
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
            // bodySmall, not labelSmall. At 11sp these were the smallest text in the app,
            // and they carry the one thing he is scanning a long list for. One step up, not
            // further: any larger and the chip stops fitting beside a name.
            style = MaterialTheme.typography.bodySmall,
            color = Status.onStatus,
            // One line, ellipsised. These sit in a row that measures itself from the name
            // beside them, and without this a long label such as "duplicate ID" wrapped and
            // made that one row taller than every other row on the screen.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
            // As the chip next to it: a warning he is meant to read, not a caption.
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = Status.onStatus,
        )
    }
}

/**
 * What a screen shows when there is nothing in it.
 *
 * **Top-aligned, deliberately, and the same on every screen.** It used to be
 * `Arrangement.Center`, which did nothing at all here: the modifier chain applies
 * `verticalScroll` before the arrangement, and inside a scrollable Column the height is
 * unbounded, so there is nothing to centre within. Meanwhile Home and the Voters list each
 * built their own, and Home's was genuinely centred because it had no scroll on it. So the
 * same idea -- an empty app -- sat at 13% of the screen on two tabs and at 48% on the third.
 *
 * Centred was the other candidate and it is the worse of the two. A centred block sits at a
 * different height depending on how long its own text is, so the position would keep moving
 * as the wording changed, and on Home -- four paragraphs of instructions -- centring pushes
 * the heading up under the app bar and clips it once he turns his font size up. Top-aligned
 * matches every other screen in the app, and matches where the eye already is.
 *
 * Scrollable, because at 1.5x font this is taller than the space it is given, and an
 * instruction he cannot scroll to is an instruction he does not get.
 */
@Composable
internal fun EmptyTab(
    title: String,
    body: String = "",
    modifier: Modifier = Modifier,
    /**
     * False when an enclosing screen already scrolls.
     *
     * A vertically scrollable Column measured with infinite height is a hard crash
     * ("Vertically scrollable component was measured with an infinity maximum height
     * constraints"), and nesting one inside another scroller is how you get it. Both
     * callers that pass false have a parent already doing this job: HomeScreen scrolls
     * its own Column, and the voters list draws this as a LazyColumn item.
     */
    scrollable: Boolean = true,
    /**
     * The block's own inset.
     *
     * Horizontal zero where an enclosing screen already pads. HomeScreen pads its scrolling
     * Column by [Space.lg] and then handed this the same inset again, so its empty state
     * sat 48px further in than the other two tabs -- visible as a jump when moving between
     * them, which is the whole thing this refactor is about. Its parent pads horizontally
     * only, so the vertical half is kept here.
     */
    padding: PaddingValues = PaddingValues(Space.lg),
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        modifier
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(padding),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        // Skipped rather than rendered blank: an empty paragraph and its gap read as a
        // heading with something missing under it.
        if (body.isNotBlank()) {
            Spacer(Modifier.height(Space.md))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        footer?.invoke(this)
    }
}
