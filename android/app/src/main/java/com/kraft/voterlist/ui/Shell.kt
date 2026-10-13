package com.kraft.voterlist.ui

import androidx.compose.material.icons.filled.Undo

import androidx.compose.material3.AlertDialog

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

import androidx.compose.foundation.layout.heightIn

import androidx.compose.foundation.clickable

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import com.kraft.ui.tokens.KraftSpacing

/** The four places in the app. A bottom bar rather than a drawer: all one tap away. */
enum class Tab(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    VOTERS("Voters", Icons.Default.List),
    FAMILIES("Families", Icons.Default.Groups),
    SETTINGS("Settings", Icons.Default.Settings),
}

/**
 * The app shell.
 *
 * One screen at a time with a bottom bar. There is no deep stack to get lost in: from anywhere
 * you reach any of the four without going back through where you have been, which is what
 * matters one-handed while holding a phone up to somebody at a door.
 *
 * **The booth selector sits in the app bar of every tab, not just Home.** Standing at a door
 * reading the Voters list while the selector only appeared on the Home screen is exactly how
 * notes end up on the wrong household. Which booth you are looking at is part of where you are,
 * so it is always on screen, and it is stamped on every export.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurveyApp(
    vm: SurveyViewModel,
    busy: Boolean,
    onPickImportFile: () -> Unit,
    onBackup: () -> Unit,
    onPickBackupFile: () -> Unit,
    pendingRestore: Triple<String, String, Int>?,
    onApplyRestore: () -> Unit,
    onCancelRestore: () -> Unit,
    onExportXlsx: (Boolean) -> Unit,
    onExportPdf: (Boolean) -> Unit,
) {
    val s by vm.state.collectAsState()
    // He comes back to where he was. The tab is read from the saved session rather than
    // remembered through a rotation, because the thing worth remembering is not "the app
    // was on Voters" -- it is "he was on Voters, filtered to one street, and the phone
    // went into his pocket".
    // The tab lives in the state, not in a local. It used to be a remember(), which took
    // its value once, on the first frame -- and the first frame is before the booth has
    // loaded, when the tab is still its default. The saved tab arrived a moment later and
    // was never looked at, so the app opened on Home every time and the session file was
    // written correctly and then ignored.
    val tab = runCatching { Tab.valueOf(s.tab) }.getOrDefault(Tab.HOME)
    fun goTo(t: Tab) = vm.setTab(t.name)
    var sheetOpen by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    // The lists' scroll positions live here, not in the screens: opening a voter swaps
    // the whole screen out of composition, which would drop a screen-held state and put
    // you back at the top on return. The shell never leaves, so the position survives
    // viewing, editing, marking done -- everything but switching booths.
    val voterListState = rememberLazyListState()
    val familyListState = rememberLazyListState()
    // Which booth each position was earned in. This is what tells "switched booths"
    // (reset to top) apart from "came back from a voter" (hold still): the reset effect
    // below must compare against something that survives navigation, and nothing inside
    // the screens does.
    /**
     * A back press that would throw something away, waiting to be answered.
     *
     * Three things used to vanish on a back gesture with nothing said: a half-filled new
     * voter, a brand-new family that had no one in it yet, and a half-filled import. All
     * three are a few minutes of typing at a doorstep, and a reflexive back swipe is the
     * single most common way to leave a screen by accident.
     */
    var discard by remember { mutableStateOf<Discard?>(null) }

    /**
     * How much room the bottom bar takes, captured out of the scaffold.
     *
     * The toast is a sibling of the scaffold's content column, not a child of it, so it
     * aligned to the bottom of the window rather than the bottom of the usable area --
     * behind an 80dp navigation bar, which put the Undo button somewhere no finger
     * reaches. It needs the bar's height from outside the column that knows it.
     */
    var bottomBarInset by remember { androidx.compose.runtime.mutableStateOf(0.dp) }

    var voterListBooth by remember { mutableStateOf<String?>(null) }
    var familyListBooth by remember { mutableStateOf<String?>(null) }

    KraftTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                when {
                    s.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    importing -> ImportScreen(
                        vm, s, onPickImportFile,
                        onClose = { importing = false },
                        // The work continues on the list, not on the file prompt. Closing
                        // here also publishes the counts to the toast via finishImport.
                        onImported = { vm.finishImport(); importing = false; goTo(Tab.VOTERS) },
                    )

                    s.openVoterId != null || s.creatingVoter -> VoterEditScreen(vm, s)

                    s.openFamilyId != null -> FamilyEditScreen(vm, s)

                    else -> Scaffold(
                        containerColor = MaterialTheme.colorScheme.background,
                        topBar = {
                            BoothBar(
                                s, vm, tab, busy,
                                onExport = { sheetOpen = true },
                                onOpenFilters = { filterOpen = true },
                            )
                        },
                        bottomBar = {
                            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                                Tab.entries.forEach { t ->
                                    NavigationBarItem(
                                        selected = tab == t,
                                        onClick = { goTo(t) },
                                        // null, not the title: NavigationBarItem merges its
                                        // subtree, so describing the icon here made a screen
                                        // reader announce "Home, home, tab".
                                        icon = { Icon(t.icon, null) },
                                        label = {
                                            // One line, and an ellipsis rather than a cut.
                                            // At a large font these were being sliced mid-glyph
                                            // -- "Famili", "Settin" -- which is worse than a
                                            // shortened word, because the cut word is not a word.
                                            // One line with an ellipsis. Two lines were
                                            // tried and were worse: a word that does not fit
                                            // a tab at a very large font breaks mid-word
                                            // either way, and "Famili / es" is less readable
                                            // than "Famil...".
                                            Text(
                                                t.title,
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center,
                                            )
                                        },
                                    )
                                }
                            }
                        },
                    ) { pad ->
                        bottomBarInset = pad.calculateBottomPadding()
                        Column(
                            Modifier.padding(pad).fillMaxSize(),
                        ) {
                            // Under the bar, not in it. TopAppBar has a fixed height, so a
                            // second line of title is clipped rather than accommodated: at a
                            // 1.5x font "Voters · 1130 people" was sliced through the middle,
                            // and in landscape the bar had no height to spare at all. Its own
                            // row gets whatever height the text needs, at any size or rotation.
                            // The bar on the Voters tab is a search field, so the booth name
                            // it displaces lives here instead: name first, then the count.
                            // Losing which booth is on screen is how notes end up on the
                            // wrong household.
                            val subtitle = when (tab) {
                                Tab.HOME -> null
                                // Settings borrows the Families phrasing: the bar says which
                                // booth's data these settings act on, which is the question
                                // that matters before touching an import or a shared list.
                                Tab.SETTINGS ->
                                    if (!s.hasBooth) "No voters yet"
                                    else s.booth.displayTitle
                                Tab.VOTERS ->
                                    if (!s.hasBooth) "No voters yet"
                                    else if (s.visible.size == s.totalCount) "${s.booth.displayTitle} · ${s.totalCount} people"
                                    else "${s.booth.displayTitle} · ${s.visible.size} of ${s.totalCount} people"
                                Tab.FAMILIES ->
                                    if (!s.hasBooth) "No voters yet"
                                    else if (s.familyQuery.isBlank()) "${s.booth.displayTitle} · ${s.familyCount} made · ${s.unassignedCount} alone"
                                    else "${s.booth.displayTitle} · ${s.visibleFamilies.size} of ${s.familyCount} families"
                            }
                            if (subtitle != null) {
                                Text(
                                    if (tab == Tab.VOTERS) subtitle else "${tab.title} · $subtitle",
                                    Modifier.padding(
                                        start = Space.lg,
                                        end = Space.lg,
                                        bottom = Space.sm,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // A weighted Box, so each screen gets the height that is left
                            // under the subtitle rather than the whole window -- and gets a
                            // *bounded* height, which is what lets the list inside it use
                            // weight and scroll. Directly in the Column a screen asking for
                            // fillMaxSize laid its rows out past the bottom and, believing it
                            // had the room, never scrolled: in landscape the families list
                            // showed one family and half of them were unreachable.
                            Box(Modifier.weight(1f)) {
                            when (tab) {
                                Tab.HOME -> HomeScreen(vm, s)
                                Tab.VOTERS -> VoterListScreen(
                                    vm, s, voterListState, voterListBooth,
                                    onBoothSeen = { voterListBooth = it },
                                    onImport = { importing = true; onPickImportFile() },
                                )
                                Tab.FAMILIES -> FamilyListScreen(vm, s, familyListState, familyListBooth) { familyListBooth = it }
                                Tab.SETTINGS -> SettingsScreen(
                                    vm, s,
                                    // Choosing "import" opens the sheet AND the file picker, so
                                    // one tap starts the job rather than landing on a screen
                                    // that does nothing by itself.
                                    onImport = { importing = true; onPickImportFile() },
                                    onBackup = onBackup,
                                    onRestore = onPickBackupFile,
                                )
                            }
                            }
                        }
                    }
                }

                if (sheetOpen) {
                    ExportSheet(vm, s, busy, onExportXlsx, onExportPdf) { sheetOpen = false }
                }
                if (filterOpen) {
                    FilterSheet(s, vm) { filterOpen = false }
                }
                discard?.let { d ->
                    AlertDialog(
                        onDismissRequest = { discard = null },
                        title = { Text(d.title) },
                        text = { Text(d.body) },
                        confirmButton = {
                            TextButton(onClick = { discard = null; d.onGo() }) {
                                Text("Go back", color = Status.pending)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { discard = null }) { Text("Stay here") }
                        },
                    )
                }
                pendingRestore?.let { (_, label, count) ->
                    AlertDialog(
                        onDismissRequest = onCancelRestore,
                        title = { Text("Put $label back?") },
                        text = {
                            Text(
                                "This file has $count ${if (count == 1) "person" else "people"} " +
                                    "in it. Putting it back replaces what is on the phone now, " +
                                    "including which houses you have been to. What is there " +
                                    "now is saved first, so it is not lost.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = onApplyRestore) {
                                Text("Put it back", color = Status.pending)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = onCancelRestore) { Text("Keep what is here") }
                        },
                    )
                }
                MessageToast(
                    // Lift clear of the bottom bar. The toast is a sibling of the scaffold
                    // content column rather than a child of it, so it was aligning to the
                    // bottom of the *window* -- behind the navigation bar, which is 80dp
                    // tall. The Undo button landed underneath it and could not be pressed
                    // at all, and the message itself was half-hidden on a bar with a
                    // translucent scrim over the content behind it.
                    bottomInset = bottomBarInset,
                    message = s.message,
                    undoWhat = s.undo?.takeIf { it.inToast }?.what,
                    onUndo = { vm.undoLast() },
                    onClear = { vm.clearMessage() },
                )
            }
        }
    }

    // Back walks inwards rather than out: the picker, the sheet, an open editor, then a tab
    // other than Home. Only leaving from Home actually exits, which is what Android users expect
    // and what stops a reflexive swipe from ending a survey.
    // Anything the session covers, changed, gets written down. It covers the filters as
    // well as the tab, because the filter is the part he would not think to put back.
    // `tab` is in the keys, not just read inside. Without it the effect only re-ran when a
    // *filter* moved, so tapping between tabs saved nothing and the app came back on
    // whatever the last filter change had left behind.
    LaunchedEffect(
        tab, s.query, s.familyQuery, s.filter, s.casteFilter,
        s.partyFilter, s.addressFilter, s.notInFileOnly,
    ) {
        vm.rememberSession()
    }

    // Once the booth has loaded, and only then: an automatic backup taken before the roll
    // is read would copy an empty booth over a real one.
    LaunchedEffect(s.booth.id, s.loading) {
        if (!s.loading && s.hasBooth) vm.autoBackupIfDue()
    }

    BackHandler(enabled = importing && s.importStep !is ImportStep.NeedsDetails) { importing = false }
    BackHandler(enabled = importing && s.importStep is ImportStep.NeedsDetails) {
        discard = Discard(
            "Throw away this import?",
            "You have not finished choosing which booth these ${(s.importStep as ImportStep.NeedsDetails).rows.size} " +
                "people belong to, and the file will have to be picked again from the start. " +
                "Nothing has been added to your lists.",
        ) { importing = false }
    }
    BackHandler(enabled = s.pickingMember) { vm.closePicker() }
    BackHandler(enabled = !importing && !s.pickingMember && sheetOpen) { sheetOpen = false }
    BackHandler(enabled = !importing && !s.pickingMember && !sheetOpen && filterOpen) {
        filterOpen = false
    }
    BackHandler(enabled = !importing && !sheetOpen && (s.openVoterId != null || s.creatingVoter)) {
        val d = s.draft
        // Only a draft can be lost: an open voter that was already saved has nothing
        // unsaved to lose. A draft with real typing in it deserves one question.
        val typed = d != null && (d.name.isNotBlank() || d.house.isNotBlank() || d.phone.isNotBlank())
        if (!s.creatingVoter || !typed) {
            vm.closeVoter()
        } else {
            discard = Discard(
                "Throw away what you typed?",
                "This person has not been added to the list yet. Going back now loses the " +
                    "${if (d.name.isNotBlank()) "name ${d.name}" else "details"} you have typed, " +
                    "and they would have to be written again at the door.",
            ) { vm.closeVoter() }
        }
    }
    BackHandler(
        enabled = !importing && !s.pickingMember && !sheetOpen && s.openVoterId == null &&
            !s.creatingVoter && s.openFamilyId != null,
    ) {
        // closeFamily deletes a family that was made but never filled. That is the right
        // behaviour on a deliberate exit and the wrong one on a stray back swipe, so a
        // family with nobody in it asks first.
        val empty = s.openFamilyId != null &&
            s.booth.families.any { it.id == s.openFamilyId } &&
            s.booth.voters.none { it.familyId == s.openFamilyId }
        if (!empty) {
            vm.closeFamily()
        } else {
            discard = Discard(
                "Delete this empty family?",
                "Nobody has been added to it yet, so going back now removes it. If you meant " +
                    "to come back and fill it in, stay here and add the people first.",
            ) { vm.closeFamily() }
        }
    }
    BackHandler(
        enabled = !importing && !s.pickingMember && !sheetOpen && s.openVoterId == null &&
            !s.creatingVoter && s.openFamilyId == null && tab != Tab.HOME,
    ) { goTo(Tab.HOME) }
}

/** A back press that would lose something, and the answer to give when they do. */
private data class Discard(val title: String, val body: String, val onGo: () -> Unit)

/**
 * The app bar, carrying which booth is open.
 *
 * Shows the booth everywhere so it cannot be forgotten, and offers the others from the same
 * place. With one booth it is just a title; with several it becomes a switcher, and the count
 * of people in each is on the row so picking the wrong one is visible rather than silent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoothBar(
    s: UiState,
    vm: SurveyViewModel,
    tab: Tab,
    busy: Boolean,
    onExport: () -> Unit,
    onOpenFilters: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val many = s.index.booths.size > 1

    androidx.compose.material3.TopAppBar(
        title = {
            // The Voters tab is a search field and nothing else: no title, no toggle. The
            // booth name it displaces moves to the subtitle line under the bar, so the
            // "which booth am I looking at" answer is still on screen.
            // The Families tab is the same deal: a search field, with the booth name in
            // the subtitle. One search per list, each keeping to its own tab.
            if (tab == Tab.VOTERS || tab == Tab.FAMILIES) {
                val query = if (tab == Tab.VOTERS) s.query else s.familyQuery
                val onQuery: (String) -> Unit = if (tab == Tab.VOTERS) vm::setQuery else vm::setFamilyQuery
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    // A field sitting in the bar, not the bar itself: breathing room at the
                    // end and a shorter stance than the bar's full height. The pill is drawn
                    // by hand around a BasicTextField because M3 TextField enforces a 56dp
                    // minimum height internally, and any shorter stance imposed from outside
                    // clips the line instead of fitting it.
                    modifier = Modifier.fillMaxWidth()
                        .height(KraftSpacing.Spacing48)
                        .padding(end = Space.sm),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    decorationBox = { inner ->
                        Row(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                    MaterialTheme.shapes.large,
                                )
                                .padding(horizontal = Space.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Search,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(Space.sm))
                            Box(Modifier.weight(1f)) {
                                if (query.isEmpty()) {
                                    Text(
                                        if (tab == Tab.VOTERS) "Search anyone" else "Search families",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                inner()
                            }
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { onQuery("") }) {
                                    Icon(Icons.Default.Close, "Clear search")
                                }
                            }
                        }
                    },
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (s.hasBooth) s.booth.displayTitle else "Booth Survey",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (many) {
                    // Labelled, so it is reachable by name -- to a screen reader, and to
                    // anything else driving the UI. An unlabelled chevron next to a heading
                    // is a control nobody can find.
                    //
                    // A TextButton with no text brings M3's defaults with it: 64dp of content
                    // padding either side and a minimum width. A 20dp chevron therefore sat
                    // inside a 128dp button and appeared shoved out to the right of the booth
                    // name rather than beside it. The padding is what the button is for, and
                    // here it is padding around nothing.
                    Box {
                        TextButton(
                            onClick = { open = true },
                            contentPadding = PaddingValues(
                                horizontal = Space.sm,
                                vertical = Space.xs,
                            ),
                            modifier = Modifier.size(BoothMetrics.TouchTarget),
                        ) {
                            Icon(
                                Icons.Default.ArrowDropDown,
                                "Switch booth",
                                Modifier.size(KraftSpacing.Spacing20),
                            )
                        }
                        // Anchored *inside* the button's own Box, so it opens under the
                        // chevron. Rendered after the bar instead, it had no anchor to
                        // position against, picked the nearest free space, and hung off the
                        // left edge of the screen covering the booth title it was describing.
                        DropdownMenu(
                            expanded = open,
                            onDismissRequest = { open = false },
                            modifier = Modifier.widthIn(min = KraftSpacing.ScreenEdge * 9),
                        ) {
                            s.index.booths.forEach { e ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                e.label.ifBlank { "Booth ${e.boothNo}" },
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                            Text(
                                                buildString {
                                                    if (e.mandal.isNotBlank()) append("${e.mandal}  ")
                                                    append("${e.size} people")
                                                    append("  ${e.done} visited")
                                                    if (e.notInFile > 0) append("  ${e.notInFile} not in file")
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    onClick = { vm.selectBooth(e.id); open = false },
                                )
                            }
                        }
                    }
                }
                }
            }
        },
        actions = {
            // No early return here: leaving this lambda by any path but the end breaks
            // recomposition scoping, and the bar silently stops updating -- a badge that
            // stays on after its filter is cleared, with the count right there in state.
            if (tab == Tab.VOTERS) {
                // Narrowing by text and refining by caste at once is legitimate, so the
                // filter is the Voters tab's only action. Exporting or adding from that
                // tab would crowd the field; both live on the other tabs.
                IconButton(onClick = onOpenFilters) {
                    BadgedBox(
                        badge = {
                            if (s.filterCount > 0) Badge { Text("${s.filterCount}") }
                        },
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            "Filter voters",
                            tint = if (s.filterCount > 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                // A count, not a button. It was a button wired to nothing, which is worse
                // than not having it: it looks like somewhere to go and goes nowhere.
            // A save glyph with a name, not a share glyph. It does not share anything: it
            // writes a file somewhere the user picks, and a share arrow for that is exactly
            // the sort of borrowed-feeling detail that makes an app read as a sample.
            //
            // The drawn label was dropped on purpose. At a 1.5x font in landscape it squeezed
            // the booth name down to "Booth 1…" -- the one piece of text that must never be
            // ambiguous. The sheet it opens is titled "Export Booth 108", so nothing is left
            // guessing, and the name is there for a screen reader.
            // The undo that outlived the app.
            //
            // Immediately after an action the toast carries Undo, and that is the normal
            // case. This is the other one: the app was killed -- which Android does to a
            // phone that has been out all day -- and came back with the undo still held.
            // Nothing on screen referred to it, and the next thing he did would have
            // cleared it, so a safety net that had survived was gone anyway.
            //
            // Only while there is no message, which is exactly the restored case. The toast
            // owns it the rest of the time.
            if (s.undo != null && s.message == null) {
                IconButton(onClick = { vm.undoLast() }) {
                    Icon(Icons.Default.Undo, "Put back: ${s.undo.what}")
                }
            }
            IconButton(onClick = onExport, enabled = !busy && s.hasBooth) {
                Icon(Icons.Default.Save, "Print the list", Modifier.size(KraftSpacing.Spacing20))
            }
            Spacer(Modifier.width(Space.xs))
            }
        },
        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
    )

}

/**
 * A message, and a way to take the last thing back.
 *
 * It used to be a line of text that vanished after 2600ms with no button. Three problems, all
 * of them real for the person this app is for: an import summary runs to six clauses and
 * cannot be read in three seconds; a message repeated word for word was dropped entirely,
 * because equal values are conflated by the state flow, so a second batch of "3 people
 * removed" showed nothing at all; and nothing that changed the roll could be taken back.
 *
 * It still goes by itself, because somebody working down a street should not have to dismiss
 * anything. It just stays long enough to read, and can be read again by tapping it.
 */
@Composable
private fun BoxScope.MessageToast(
    bottomInset: androidx.compose.ui.unit.Dp,
    message: String?,
    undoWhat: String?,
    onUndo: () -> Unit,
    onClear: () -> Unit,
) {
    // Keyed on the message, so a repeated one restarts the clock rather than being ignored.
    LaunchedEffect(message) {
        if (message != null) {
            // Longer when there is something to take back: reading a button is slower than
            // reading a line, and a hurried tap on Undo is worse than no Undo at all.
            delay(if (undoWhat != null) 9000 else 6000)
            onClear()
        }
    }
    if (message == null) return
    Surface(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(
                start = KraftSpacing.Spacing16,
                end = KraftSpacing.Spacing16,
                bottom = KraftSpacing.Spacing16 + bottomInset,
            )
            // Every "12 marked visited", "3 people removed", "Saved" goes through here.
            // Without this it is announced by nobody, so every action in the app happens in
            // silence to a screen-reader user.
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.inverseSurface,
        shadowElevation = KraftSpacing.Spacing6,
    ) {
        Row(
            Modifier
                .clickable(onClick = onClear)
                .heightIn(min = BoothMetrics.TouchTarget)
                .padding(start = Space.lg, end = if (undoWhat != null) Space.xs else Space.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                Modifier.weight(1f).padding(vertical = Space.md),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
            )
            if (undoWhat != null) {
                TextButton(
                    onClick = { onUndo(); onClear() },
                    modifier = Modifier.heightIn(min = BoothMetrics.TouchTarget),
                ) {
                    Text(
                        "Undo",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.inversePrimary,
                    )
                }
            }
        }
    }
}
