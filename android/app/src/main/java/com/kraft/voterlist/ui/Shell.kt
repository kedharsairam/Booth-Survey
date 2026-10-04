package com.kraft.voterlist.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

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
    onExportPdf: (Boolean) -> Unit,
    onExportXlsx: (Boolean) -> Unit,
    onExportTemplate: () -> Unit,
) {
    val s by vm.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var sheetOpen by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    KraftTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                when {
                    s.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    importing -> ImportScreen(vm, s, onPickImportFile) { importing = false }

                    s.openVoterId != null || s.creatingVoter -> VoterEditScreen(vm, s)

                    s.openFamilyId != null -> FamilyEditScreen(vm, s)

                    else -> Scaffold(
                        containerColor = MaterialTheme.colorScheme.background,
                        topBar = { BoothBar(s, vm, tab, busy) { sheetOpen = true } },
                        bottomBar = {
                            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                                Tab.entries.forEach { t ->
                                    NavigationBarItem(
                                        selected = tab == t,
                                        onClick = { tab = t },
                                        icon = { Icon(t.icon, t.title) },
                                        label = {
                                            // One line. At a 1.5x font in landscape "Families"
                                            // was wrapping to "Familie / s" and "Settings" to
                                            // "Setting / s", which is worse than a smaller label.
                                            Text(
                                                t.title,
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 1,
                                            )
                                        },
                                    )
                                }
                            }
                        },
                    ) { pad ->
                        Column(
                            Modifier.padding(pad).fillMaxSize(),
                        ) {
                            // Under the bar, not in it. TopAppBar has a fixed height, so a
                            // second line of title is clipped rather than accommodated: at a
                            // 1.5x font "Voters · 1130 people" was sliced through the middle,
                            // and in landscape the bar had no height to spare at all. Its own
                            // row gets whatever height the text needs, at any size or rotation.
                            val subtitle = when (tab) {
                                Tab.HOME, Tab.SETTINGS -> null
                                Tab.VOTERS ->
                                    if (s.visible.size == s.totalCount) "${s.totalCount} people"
                                    else "${s.visible.size} of ${s.totalCount} people"
                                Tab.FAMILIES ->
                                    "${s.familyCount} made · ${s.unassignedCount} alone"
                            }
                            if (subtitle != null) {
                                Text(
                                    "${tab.title} · $subtitle",
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
                                Tab.VOTERS -> VoterListScreen(vm, s)
                                Tab.FAMILIES -> FamilyListScreen(vm, s)
                                Tab.SETTINGS -> SettingsScreen(
                                    vm, s,
                                    // Choosing "import" opens the sheet AND the file picker, so
                                    // one tap starts the job rather than landing on a screen
                                    // that does nothing by itself.
                                    onImport = { importing = true; onPickImportFile() },
                                    onExportTemplate = onExportTemplate,
                                )
                            }
                            }
                        }
                    }
                }

                if (sheetOpen) {
                    ExportSheet(vm, s, busy, onExportPdf, onExportXlsx) { sheetOpen = false }
                }
                MessageToast(s.message) { vm.clearMessage() }
            }
        }
    }

    // Back walks inwards rather than out: the picker, the sheet, an open editor, then a tab
    // other than Home. Only leaving from Home actually exits, which is what Android users expect
    // and what stops a reflexive swipe from ending a survey.
    BackHandler(enabled = importing) { importing = false }
    BackHandler(enabled = s.pickingMember) { vm.closePicker() }
    BackHandler(enabled = !importing && !s.pickingMember && sheetOpen) { sheetOpen = false }
    BackHandler(enabled = !importing && !sheetOpen && (s.openVoterId != null || s.creatingVoter)) {
        vm.closeVoter()
    }
    BackHandler(
        enabled = !importing && !s.pickingMember && !sheetOpen && s.openVoterId == null &&
            !s.creatingVoter && s.openFamilyId != null,
    ) { vm.closeFamily() }
    BackHandler(
        enabled = !importing && !s.pickingMember && !sheetOpen && s.openVoterId == null &&
            !s.creatingVoter && s.openFamilyId == null && tab != Tab.HOME,
    ) { tab = Tab.HOME }
}

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
) {
    var open by remember { mutableStateOf(false) }
    val many = s.index.booths.size > 1

    androidx.compose.material3.TopAppBar(
        title = {
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
                    TextButton(onClick = { open = true }) {
                        Icon(
                            Icons.Default.ArrowDropDown,
                            "Switch booth",
                            Modifier.size(20.dp),
                        )
                    }
                }
            }
        },
        actions = {
            // A count, not a button. It was a button wired to nothing, which is worse than
            // not having it: it looks like somewhere to go and goes nowhere.
            if (s.notInFileCount > 0) {
                StatusChip("${s.notInFileCount} not in file", Status.pending)
                Spacer(Modifier.width(Space.sm))
            }
            when (tab) {
                Tab.VOTERS -> IconButton(onClick = { vm.startCreateVoter() }) {
                    Icon(Icons.Default.Add, "New voter")
                }
                Tab.FAMILIES -> IconButton(
                    onClick = { vm.createFamily() },
                    enabled = s.totalCount > 0,
                ) {
                    Icon(Icons.Default.GroupAdd, "New family")
                }
                else -> Unit
            }
            // A save glyph with a name, not a share glyph. It does not share anything: it
            // writes a file somewhere the user picks, and a share arrow for that is exactly
            // the sort of borrowed-feeling detail that makes an app read as a sample.
            //
            // The drawn label was dropped on purpose. At a 1.5x font in landscape it squeezed
            // the booth name down to "Booth 1…" -- the one piece of text that must never be
            // ambiguous. The sheet it opens is titled "Export Booth 108", so nothing is left
            // guessing, and the name is there for a screen reader.
            IconButton(onClick = onExport, enabled = !busy && s.hasBooth) {
                Icon(Icons.Default.Save, "Export", Modifier.size(20.dp))
            }
            Spacer(Modifier.width(Space.xs))
        },
        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
    )

    if (open) {
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
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

/**
 * A message, shown once and then cleared rather than left to be read past.
 *
 * Somebody working down a street should not have to dismiss anything, so it needs no button
 * and it goes by itself.
 */
@Composable
private fun BoxScope.MessageToast(message: String?, onClear: () -> Unit) {
    LaunchedEffect(message) {
        if (message != null) {
            delay(2600)
            onClear()
        }
    }
    if (message == null) return
    Surface(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(16.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.inverseSurface,
        shadowElevation = 6.dp,
    ) {
        Text(
            message,
            Modifier.padding(horizontal = Space.lg, vertical = Space.md),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}
