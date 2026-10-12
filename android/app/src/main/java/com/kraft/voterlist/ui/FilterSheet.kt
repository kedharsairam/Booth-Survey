package com.kraft.voterlist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow

/**
 * All of the list's filters, laid out like a shop filter: categories down the left,
 * that category's choices on the right, CLEAR ALL up top and CANCEL plus SHOW down
 * below. Nothing applies until SHOW -- picking through castes must not thrash the
 * list behind the sheet with every tap -- and SHOW names the count it will show.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(s: UiState, vm: SurveyViewModel, onClose: () -> Unit) {
    // The managed vocabulary plus anything the roll already carries that the lists never
    // learned, so a value already on voters is always pickable.
    val castes = remember(s.voters, s.lists.castes) { words(s.voters.map { it.caste } + s.lists.castes) }
    val parties = remember(s.voters, s.lists.parties) { words(s.voters.map { it.party } + s.lists.parties) }
    val addresses = remember(s.voters, s.lists.addresses) { words(s.voters.map { it.address } + s.lists.addresses) }

    // Draft: the sheet edits copies, and SHOW commits them. Cancelling or backing out
    // throws the draft away, so browsing choices never filters anything by accident.
    var section by remember { mutableStateOf(FilterSection.STATUS) }
    var fStatus by remember { mutableStateOf(s.filter) }
    var fCaste by remember { mutableStateOf(s.casteFilter) }
    var fParty by remember { mutableStateOf(s.partyFilter) }
    var fAddress by remember { mutableStateOf(s.addressFilter) }
    var fNotInFile by remember { mutableStateOf(s.notInFileOnly) }

    val sections = remember(s.notInFileCount) {
        buildList {
            add(FilterSection.STATUS)
            add(FilterSection.CASTE)
            add(FilterSection.PARTY)
            add(FilterSection.ADDRESS)
            if (s.notInFileCount > 0) add(FilterSection.NOT_IN_FILE)
        }
    }
    // The rail can outlive its section (not-in-file empties to zero while open), so the
    // open one is derived, never defaulted by writing state mid-composition.
    val open = if (section in sections) section else sections.first()

    // What SHOW would show, worked out from a copy: the state class is pure, so the
    // count is honest without touching anything.
    val draftCount = remember(s.voters, fStatus, fCaste, fParty, fAddress, fNotInFile) {
        s.copy(
            filter = fStatus, casteFilter = fCaste, partyFilter = fParty,
            addressFilter = fAddress, notInFileOnly = fNotInFile,
        ).visible.size
    }
    val dirty = fStatus != s.filter || fCaste != s.casteFilter || fParty != s.partyFilter ||
        fAddress != s.addressFilter || fNotInFile != s.notInFileOnly

    ModalBottomSheet(
        onDismissRequest = onClose,
        // Open full, not half: the CANCEL and SHOW bar at the bottom is the point of the
        // sheet, and a half-open sheet parks it below the fold behind a swipe nobody
        // discovers. The content is capped well under full height, so nothing is lost.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // The bar stays pinned: the middle row is capped, so both panes scroll inside it
        // instead of pushing CANCEL and SHOW off the screen. An apply nobody can reach
        // without swiping is a filter nobody applies.
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Filters",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    fStatus = UiState.Filter.ALL
                    fCaste = ""
                    fParty = ""
                    fAddress = ""
                    fNotInFile = false
                }) { Text("Clear all") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.heightIn(max = BoothMetrics.FamilyListHeight)) {
                // The rail: one row per dimension, the open one highlighted.
                Column(
                    Modifier.fillMaxWidth(0.34f).fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                    sections.forEach { sec ->
                        val active = sec == open
                        val picked = when (sec) {
                            FilterSection.STATUS -> fStatus != UiState.Filter.ALL
                            FilterSection.CASTE -> fCaste.isNotEmpty()
                            FilterSection.PARTY -> fParty.isNotEmpty()
                            FilterSection.ADDRESS -> fAddress.isNotEmpty()
                            FilterSection.NOT_IN_FILE -> fNotInFile
                        }
                        // Teal text said which pane was open and nothing else did, so a man
                        // who cannot separate teal from grey had no way to tell. The open
                        // one now carries a filled block of its own, and selectable says
                        // "tab, selected" out loud. Two lines are allowed so the label is
                        // readable at a 1.5x font instead of cut off mid-word.
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = BoothMetrics.TouchTarget)
                                .selectable(selected = active, role = Role.Tab) { section = sec }
                                .background(
                                    if (active) MaterialTheme.colorScheme.secondaryContainer
                                    else Color.Transparent,
                                )
                                // The dot is silenced below, so its meaning is said here
                                // instead. No contentDescription: the label is merged into
                                // this node already and saying it twice makes a screen
                                // reader announce the name two times.
                                .semantics {
                                    stateDescription = if (picked) "Chosen here" else ""
                                }
                                .padding(
                                    horizontal = Space.lg,
                                    vertical = Space.md,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (picked) {
                                Text(
                                    "•",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    // A dot is a dot. Left inside the label it is read
                                    // out as "bullet" ahead of the name of the category.
                                    modifier = Modifier.clearAndSetSemantics { },
                                )
                                Spacer(Modifier.width(Space.xs))
                            }
                            Text(
                                sec.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (active) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // The choices for the open section, single-pick like the filter behind them.
                LazyColumn(Modifier.weight(1f)) {
                    when (open) {
                        FilterSection.STATUS -> {
                            item { OptionRow("All", "${s.totalCount}", fStatus == UiState.Filter.ALL) { fStatus = UiState.Filter.ALL } }
                            item { OptionRow("Left to visit", "${s.remainingCount}", fStatus == UiState.Filter.REMAINING) { fStatus = UiState.Filter.REMAINING } }
                            item { OptionRow("Visited", "${s.doneCount}", fStatus == UiState.Filter.DONE) { fStatus = UiState.Filter.DONE } }
                        }
                        FilterSection.CASTE -> vocabItems(castes, fCaste) { fCaste = it }
                        FilterSection.PARTY -> vocabItems(parties, fParty) { fParty = it }
                        FilterSection.ADDRESS -> vocabItems(addresses, fAddress) { fAddress = it }
                        FilterSection.NOT_IN_FILE -> {
                            item {
                                OptionRow(
                                    "Not in the latest file",
                                    "${s.notInFileCount}",
                                    fNotInFile,
                                ) { fNotInFile = !fNotInFile }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // The bar at the bottom threw the draft away and a button beside it kept it,
            // but both were called Close and Show, so a man who tapped the wrong one
            // believed a filter was on the list when none was. Say what each one does,
            // with the same count the button itself carries.
            Text(
                "Cancel throws away your choices. Show $draftCount puts them on the list.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm),
            )
            Row(
                Modifier.fillMaxWidth().padding(Space.lg),
                horizontalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                // heightIn, not height: a fixed 52dp slices the label in half at a 2x font.
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.weight(1f).heightIn(min = BoothMetrics.FieldHeight),
                    shape = MaterialTheme.shapes.medium,
                ) { Text("Cancel") }
                Button(
                    onClick = {
                        vm.setFilter(fStatus)
                        vm.setCasteFilter(fCaste)
                        vm.setPartyFilter(fParty)
                        vm.setAddressFilter(fAddress)
                        if (fNotInFile != s.notInFileOnly) vm.toggleNotInFileOnly()
                        onClose()
                    },
                    enabled = dirty,
                    modifier = Modifier.weight(1f).heightIn(min = BoothMetrics.FieldHeight),
                    shape = MaterialTheme.shapes.medium,
                ) { Text("Show $draftCount") }
            }
        }
    }
}

private enum class FilterSection(val title: String) {
    STATUS("Status"),
    CASTE("Caste"),
    PARTY("Party"),
    ADDRESS("Address"),
    NOT_IN_FILE("Not in file"),
}

private fun words(values: List<String>): List<String> =
    values.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()

private fun LazyListScope.vocabItems(
    options: List<String>,
    selected: String,
    onPick: (String) -> Unit,
) {
    if (options.isEmpty()) {
        item {
            Text(
                "Nothing here yet. Type one on a voter and it will appear.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Space.lg),
            )
        }
        return
    }
    item { OptionRow("Any", "", selected.isEmpty()) { onPick("") } }
    items(options, key = { it }) { o ->
        OptionRow(o, "", selected.equals(o, ignoreCase = true)) {
            onPick(if (selected.equals(o, ignoreCase = true)) "" else o)
        }
    }
}

/** One choice: tapping anywhere on the row picks it, like every other row in the app. */
@Composable
private fun OptionRow(label: String, count: String, picked: Boolean, onPick: () -> Unit) {
    // selectable, not clickable: clickable merged the row and its radio button into one
    // node, which TalkBack then read as a blank "double tap to activate" stop sitting in
    // front of the real control. The row itself now wears the radio role and the button
    // is only the picture of it. TouchTarget as a floor because the row was worked out
    // from the text and came to 47dp.
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = picked, role = Role.RadioButton, onClick = onPick)
            .heightIn(min = BoothMetrics.TouchTarget)
            .padding(horizontal = Space.lg, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = picked, onClick = null)
        Spacer(Modifier.width(Space.sm))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count.isNotEmpty()) {
            Text(
                count,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
