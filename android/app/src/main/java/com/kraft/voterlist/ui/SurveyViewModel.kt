package com.kraft.voterlist.ui

import com.kraft.voterlist.io.BackupFiles

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kraft.voterlist.data.Booth
import com.kraft.voterlist.data.Family
import com.kraft.voterlist.data.FamilyWithMembers
import com.kraft.voterlist.data.Lists
import com.kraft.voterlist.data.PrintOrder
import com.kraft.voterlist.data.Store
import com.kraft.voterlist.data.Survey
import com.kraft.voterlist.data.Voter
import com.kraft.voterlist.data.matchesLoose
import com.kraft.voterlist.io.BoothImporter
import com.kraft.voterlist.io.XlsxReader
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where an import has got to. */
sealed interface ImportStep {
    data object Idle : ImportStep
    data object Reading : ImportStep
    data class NeedsDetails(val rows: List<XlsxReader.Row>, val summary: String, val existing: Store.Index.Entry?) : ImportStep
    data class Failed(val reason: String) : ImportStep
    data class Done(val message: String) : ImportStep
}

/**
 * One step back.
 *
 * Holds the booth exactly as it was before the last change that could be regretted, plus a
 * word for the button. This app had no undo anywhere, which made every mistake cost four
 * arrow taps to repair -- and a misplaced family in the walking order is the artefact the
 * whole survey exists to produce, so that is not a recoverable mistake at a doorstep.
 *
 * It is a whole-booth snapshot rather than a diff because the changes are all whole-object
 * rewrites anyway ([Booth] is immutable), and one reference costs nothing until the next
 * change replaces it.
 */
data class Undo(
    val what: String,
    val before: Booth,
    /** When the action happened, so a stale one can be dropped rather than offered. */
    val at: Long = System.currentTimeMillis(),
    /**
     * True when the action also said something, so the toast can carry the button.
     *
     * False for the reorder steps: those fire once per arrow tap and once per step of a
     * drag, and a toast that reappears on every one of them is noise. They get a quieter
     * Undo on the family screen instead, where the thing being reordered is on display.
     */
    val inToast: Boolean = true,
) {
    /**
     * The booth to put back, or null if putting it back would be wrong.
     *
     * Undo holds a snapshot of a booth that was on screen at the time. If the booth has
     * since been switched, or re-imported under the same screen, restoring would resurrect
     * an old roll over a newer one -- silently overwriting whatever happened in between.
     * So the guard lives here rather than inline, where a test can hold it still.
     */
    fun targetFor(current: Booth): Booth? = if (before.id == current.id) before else null

    /**
     * Whether this is still worth offering.
     *
     * Now that undo survives the app closing, it can survive overnight too -- and putting
     * back yesterday's "12 marked visited" at the start of today would be a disaster
     * rather than a rescue. A day's work cannot be undone by a button that is offering to
     * undo one action. Past this it is dropped.
     */
    fun isFresh(now: Long): Boolean = now - at < MAX_AGE_MS

    private companion object {
        /** Twelve hours: long enough to cover being interrupted, short enough to be safe. */
        const val MAX_AGE_MS = 12L * 60 * 60 * 1000
    }
}

/**
 * What the screens read.
 *
 * The active booth is part of this, and deliberately visible everywhere rather than only on
 * Home: standing at a door reading the Voters list while the selector says a different booth is
 * exactly how notes end up on the wrong household.
 */
data class UiState(
    val loading: Boolean = true,
    val index: Store.Index = Store.Index(),
    val booth: Booth = Booth(boothNo = ""),
    val lists: Lists = Lists(),
    val query: String = "",
    /**
     * The tab he was last on. Home otherwise, but every launch used to drop him there
     * whatever he was doing, which is the screen that answers the least and interrupts
     * the most.
     */
    val tab: String = "HOME",
    /**
     * Family search text. Separate from the voter query so switching tabs never
     * carries a filter into the other list.
     */
    val familyQuery: String = "",
    val filter: Filter = Filter.ALL,
    /**
     * Caste, party and address match exactly (case-insensitive); blank means no limit. All
     * of these combine with the status filter and the search, so "left + BC-A" is a real
     * question the list can answer.
     */
    val casteFilter: String = "",
    val partyFilter: String = "",
    val addressFilter: String = "",
    val notInFileOnly: Boolean = false,
    /**
     * Ids ticked in selection mode. They survive filter changes -- someone who tapped four
     * rows, searched, and tapped two more has six selected, not two -- and are pruned to
     * voters who still exist whenever a bulk action runs. Cleared on booth switch (ids from
     * another booth must never leak into this one's actions) and after every bulk action.
     */
    val selectedIds: Set<String> = emptySet(),
    /**
     * Ids ticked in family selection mode. Same contract as selectedIds: survives
     * filter changes, pruned to families that still exist whenever a bulk action
     * runs, cleared on booth switch and after every bulk action.
     */
    val selectedFamilyIds: Set<String> = emptySet(),
    val openVoterId: String? = null,
    val openFamilyId: String? = null,
    /**
     * True while a brand new voter is being filled in.
     *
     * The record does not exist yet. Opening "New voter" shows an empty form that is not
     * attached to anything; it becomes a voter only when confirmed, and backing out leaves
     * nothing behind. Creating on open would leave a nameless row in the roll every time
     * somebody changed their mind, and those rows are invisible in a list of 1129.
     */
    val creatingVoter: Boolean = false,
    val draft: Voter? = null,
    /**
     * Whether the add-someone picker is up.
     *
     * Held here rather than in the family screen's own state. It used to be local, and back
     * could not dismiss the dialog: the shell's back handler closed the family underneath it,
     * tearing the composable down while its dialog window was still on screen, leaving a picker
     * nobody could get rid of. Dialog state that back has to dismiss cannot live in a screen
     * that back can also close.
     */
    val pickingMember: Boolean = false,
    val importStep: ImportStep = ImportStep.Idle,
    val message: String? = null,
    /**
     * Set by anything worth taking back, cleared by the next change. `null` means the toast
     * has no Undo on it, which is how the toast knows whether to show the button.
     */
    val undo: Undo? = null,
) {
    enum class Filter {
        ALL, REMAINING, DONE;

        companion object {
            /** By name, for a session file written by an earlier or later build. */
            val byName: Map<String, Filter> = entries.associateBy { it.name }
        }
    }

    /** True when anything beyond the status narrows the list. */
    val hasExtraFilters: Boolean get() =
        casteFilter.isNotEmpty() || partyFilter.isNotEmpty() || addressFilter.isNotEmpty() || notInFileOnly

    /** How many filter dimensions are on, for the badge on the filter button. */
    val filterCount: Int get() =
        (if (filter != Filter.ALL) 1 else 0) +
            (if (casteFilter.isNotEmpty()) 1 else 0) +
            (if (partyFilter.isNotEmpty()) 1 else 0) +
            (if (addressFilter.isNotEmpty()) 1 else 0) +
            (if (notInFileOnly) 1 else 0)

    /** How many of the list's voters are ticked, for the selection bar. */
    val selectedCount: Int get() = selectedIds.size

    /** How many families are ticked, for the selection bar. */
    val selectedFamilyCount: Int get() = selectedFamilyIds.size

    // A booth is identified by its booth number. Testing the id instead would be true
    // for the empty placeholder, which is created with a fresh id, and the "nothing
    // imported yet" screen would never appear.
    val hasBooth: Boolean get() = booth.boothNo.isNotBlank()
    val voters: List<Voter> get() = booth.voters
    val families: List<Family> get() = booth.families

    val totalCount: Int get() = voters.size

    /**
     * People actually knocked at, which is what "visited" means to him.
     *
     * This counted [Voter.isDone] -- visited *or* skipped -- and that made the headline lie.
     * Forty skipped houses still need coming back to, which the app says twice itself, but
     * counting them as finished turned the Home screen into "All visited" with a full progress
     * bar while a third of the ward was unvisited. A surveyor packs up on that screen.
     */
    val doneCount: Int get() = voters.count { it.visited }

    /** Skipped, which is not the same as finished. Shown beside the headline. */
    val skippedCount: Int get() = voters.count { it.skipped }

    val remainingCount: Int get() = totalCount - doneCount
    val familyCount: Int get() = families.size
    val unassignedCount: Int get() = voters.count { it.familyId == null }
    val notInFileCount: Int get() = voters.count { it.notInLatestFile }

    val familiesWithMembers: List<FamilyWithMembers> get() = booth.familiesWithMembers

    /**
     * Voter IDs and serials that appear more than once, worked out once for the whole booth.
     *
     * Each row used to ask `v.voterIdIsDuplicated(s.voters)` and `v.serialIsDuplicated(
     * s.voters)`, and both of those scan the entire list. That is two full passes over 1,200
     * people per visible row, per recomposition -- about 100,000 comparisons for every
     * frame of scrolling, on the main thread, on the phone this is meant to run on.
     *
     * One pass here produces two sets, and answering a row is then a set lookup. It is also
     * domain logic that a JVM test can reach, which the per-row version never could.
     */
    private val repeatedVoterIds: Set<String> by lazy {
        // Trimmed and lower-cased, which is how BoothImporter matches voter IDs when it
        // folds a sheet in. They disagreed: a sheet carrying "abc123" against a record
        // holding "ABC123" would be merged as the same person and not flagged as a clash,
        // so the app would combine two records quietly and then report nothing. The EPIC
        // numbers on the official roll are upper case and transcription slips are not, so
        // the same value in either case is the same identity.
        voters.map { it.voterId.trim().lowercase() }.filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys
    }

    private val repeatedSerials: Set<Int> by lazy {
        voters.filter { it.serial > 0 }.groupingBy { it.serial }.eachCount()
            .filterValues { it > 1 }.keys
    }

    fun isVoterIdRepeated(voterId: String): Boolean {
        val key = voterId.trim().lowercase()
        return key.isNotEmpty() && key in repeatedVoterIds
    }

    fun isSerialRepeated(serial: Int): Boolean =
        serial > 0 && serial in repeatedSerials

    /**
     * Families matching the family search, in walking order. Matches the label
     * or any member's name, so finding a house works from either end.
     */
    val visibleFamilies: List<FamilyWithMembers>
        get() {
            val q = familyQuery.trim()
            if (q.isEmpty()) return familiesWithMembers
            return familiesWithMembers.filter { fwm ->
                fwm.label.matchesLoose(q) || fwm.members.any { it.name.matchesLoose(q) }
            }
        }

    /** Members of a family in the order the surveyor arranged them. */
    fun membersOf(familyId: String): List<Voter> =
        voters.filter { it.familyId == familyId }.sortedBy { it.familyOrder }

    fun familyNamed(familyId: String?): String =
        if (familyId == null) "" else familiesWithMembers.firstOrNull { it.family.id == familyId }?.label ?: ""

    // asSurvey() and printOrder used to sit here. Nothing called either: exports build
    // their own Survey at the moment they run, and the booklet's block order comes from
    // PrintOrder.blocks where the export needs it. Dead state on a state object is not
    // free -- it is a second thing to keep correct.

    /** The voter being edited: the saved one, or the unsaved draft. */
    val editing: Voter? get() = draft ?: voters.firstOrNull { it.id == openVoterId }

    /**
     * The voter list in serial order.
     *
     * Serial, not door number, on purpose. The app is the thing that has to be trusted when the
     * sheet is wrong -- and the door number is the field the extraction got wrong most often. Serial
     * is the one value that came off the roll perfectly, so it is what the app orders by.
     */
    val visible: List<Voter>
        get() {
            val q = query.trim()
            return voters.asSequence()
                .filter { v ->
                    when (filter) {
                        Filter.ALL -> true
                        Filter.REMAINING -> !v.isDone
                        Filter.DONE -> v.isDone
                    }
                }
                .filter { v -> casteFilter.isEmpty() || v.caste.equals(casteFilter, ignoreCase = true) }
                .filter { v -> partyFilter.isEmpty() || v.party.equals(partyFilter, ignoreCase = true) }
                .filter { v -> addressFilter.isEmpty() || v.address.equals(addressFilter, ignoreCase = true) }
                .filter { v -> !notInFileOnly || v.notInLatestFile }
                .filter { v -> q.isEmpty() || matches(v, q) }
                .sortedBy { it.serial }
                .toList()
        }

    private fun matches(v: Voter, q: String): Boolean =
        v.name.matchesLoose(q) ||
            v.voterId.matchesLoose(q) ||
            v.house.matchesLoose(q) ||
            v.serial.toString() == q ||
            v.phone.matchesLoose(q) ||
            v.address.matchesLoose(q) ||
            v.caste.matchesLoose(q) ||
            v.party.matchesLoose(q)

    /**
     * Text search across the whole booth, ignoring the list's filters. Home's search promises
     * "anyone", and a family filter left on from the Voters tab must not silently break that
     * promise.
     */
    val searchHits: List<Voter>
        get() {
            val q = query.trim()
            if (q.isEmpty()) return emptyList()
            return voters.asSequence().filter { matches(it, q) }.sortedBy { it.serial }.toList()
        }
}

class SurveyViewModel @JvmOverloads constructor(
    app: Application,
    /**
     * Where the store lives. Null is the app's own storage; a test passes a temporary
     * directory so it cannot read or write a surveyor's real booths.
     */
    storeRoot: java.io.File? = null,
) : AndroidViewModel(app) {

    private val store = Store(app, storeRoot)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Remembered so a routine re-import is a couple of taps rather than typing three fields,
     * and kept on disk because the point is to save the effort *next* time -- an in-memory
     * value is gone by tomorrow morning, which is exactly when the next import happens.
     */
    private var lastBoothNo = ""
    private var lastMandal = ""
    private var lastWard = ""

    private suspend fun loadRemembered() {
        val f = java.io.File(getApplication<Application>().filesDir, "last-booth.txt")
        if (!f.exists()) return
        val parts = runCatching { f.readText().split("\t") }.getOrNull() ?: return
        if (parts.size >= 3) {
            lastBoothNo = parts[0]
            lastMandal = parts[1]
            lastWard = parts[2]
        }
    }

    private fun persistRemembered() {
        val f = java.io.File(getApplication<Application>().filesDir, "last-booth.txt")
        runCatching { f.writeText("$lastBoothNo\t$lastMandal\t$lastWard") }
    }

    init {
        viewModelScope.launch {
            val index = store.loadIndex()
            val lists = store.loadLists()
            loadRemembered()
            val booth = index.activeId?.let { store.loadBooth(it) } ?: Booth(boothNo = "")
            // If a booth file would not parse, say so at once and name the booth. Otherwise
            // the app shows "No voters list yet" and the next import overwrites what is left.
            // Pick the session back up before the first frame, so he lands where he was.
            val session = store.loadSession()?.let { runCatching { JSONObject(it) }.getOrNull() }
            val lost = store.unreadableBooth
            _state.value = _state.value.copy(
                index = index,
                lists = lists,
                booth = booth,
                loading = false,
                undo = store.loadUndo()?.let { undoFromJson(it) }
                    ?.takeIf { it.isFresh(System.currentTimeMillis()) },
                tab = session?.optString("tab")?.takeIf { it in KNOWN_TABS } ?: "HOME",
                query = session?.optString("query").orEmpty(),
                familyQuery = session?.optString("familyQuery").orEmpty(),
                filter = UiState.Filter.byName[session?.optString("filter") ?: ""] ?: UiState.Filter.ALL,
                casteFilter = session?.optString("casteFilter").orEmpty(),
                partyFilter = session?.optString("partyFilter").orEmpty(),
                addressFilter = session?.optString("addressFilter").orEmpty(),
                notInFileOnly = session?.optBoolean("notInFileOnly") ?: false,
                message = lost?.let { unreadableMessage(it.second) },
            )
        }
    }

    /**
     * Writes the session down, debounced.
     *
     * Debounced because a search fires on every keystroke and this file is a handful of
     * lines; without it, typing a name would write it ten times.
     */
    fun rememberSession() {
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            delay(700)
            val s = _state.value
            store.saveSession(
                JSONObject().apply {
                    put("tab", s.tab)
                    put("query", s.query)
                    put("familyQuery", s.familyQuery)
                    put("filter", s.filter.name)
                    put("casteFilter", s.casteFilter)
                    put("partyFilter", s.partyFilter)
                    put("addressFilter", s.addressFilter)
                    put("notInFileOnly", s.notInFileOnly)
                }.toString()
            )
        }
    }

    private var sessionJob: Job? = null

    private companion object {
        /** Tab names this build knows. A session from a future build is ignored, not obeyed. */
        val KNOWN_TABS = setOf("HOME", "VOTERS", "FAMILIES", "SETTINGS")

        /** Six hours: several rounds of street work, not a file per mark. */
        const val AUTO_MIN_GAP_MS = 6L * 60 * 60 * 1000
    }

    private fun publish(message: String? = null) {
        _state.value = _state.value.copy(message = message ?: _state.value.message)
    }

    /** Rewrites the index entry for the active booth from its current contents. */
    private fun indexFor(booth: Booth, index: Store.Index): Store.Index {
        val entry = Store.Index.Entry(
            id = booth.id,
            boothNo = booth.boothNo,
            mandal = booth.mandal,
            townWard = booth.townWard,
            label = booth.label,
            size = booth.size,
            // Visited, not done. The booth switcher prints this as "N visited", and
            // Booth.done counts a skipped person as done -- so a morning spent skipping
            // rather than calling made the selector claim visits that had not happened.
            // Home's headline has always counted visited only; these now agree.
            done = booth.voters.count { it.visited },
            notInFile = booth.notInFile,
            updatedAt = booth.importedAt,
        )
        val others = index.booths.filterNot { it.id == booth.id }
        return index.copy(activeId = booth.id, booths = others + entry)
    }

    /**
     * Writes the booth, and remembers what it replaced if [undoWhat] says this was a change
     * worth taking back.
     *
     * The snapshot is taken from the state, which still holds the old booth at this point --
     * every caller reads it into a local first and only then builds the new one. Anything
     * saved without [undoWhat] clears the undo, so a stale one can never be offered against
     * an action it does not describe.
     */
    private suspend fun saveBooth(booth: Booth, undoWhat: String? = null, undoInToast: Boolean = true): Boolean {
        if (!store.saveBooth(booth)) {
            // The write is checked, and a failure is not passed off as a save. A full disk
            // used to throw out of this and take the app down with it; now the screen keeps
            // showing what is on disk and he is told, in words, what happened.
            say("Could not save. The phone may be out of space. Nothing has been changed.")
            return false
        }
        val index = indexFor(booth, _state.value.index)
        store.saveIndex(index)
        val before = _state.value.booth
        val undo = undoWhat?.let { Undo(it, before, System.currentTimeMillis(), undoInToast) }
        _state.value = _state.value.copy(index = index, booth = booth, undo = undo)
        // Only when there is something to remember. A booth save with no undo label must
        // also clear whatever was on offer, or a stale Undo could be pressed against an
        // action it does not describe.
        if (undo != null) store.saveUndo(undoJson(undo)) else store.clearUndo()
        return true
    }

    /** The undo as it is written down, so it can be written and read by the same code. */
    private fun undoJson(u: Undo): String = JSONObject().apply {
        put("what", u.what)
        put("at", u.at)
        put("inToast", u.inToast)
        put("booth", store.boothJson(u.before))
    }.toString()

    private fun undoFromJson(text: String): Undo? = runCatching {
        val o = JSONObject(text)
        val booth = store.boothFromJson(o.getString("booth")) ?: return null
        Undo(
            what = o.getString("what"),
            before = booth,
            at = o.getLong("at"),
            inToast = o.optBoolean("inToast", true),
        )
    }.getOrNull()

    /**
     * Puts the last regrettable change back, once.
     *
     * Restores the whole booth and clears the undo in the same step, so it cannot be pressed
     * twice for one action. If the booth has been re-imported or switched since the change
     * was made there is nothing sensible to go back to, so it refuses rather than resurrect
     * an old roll over a newer one.
     */
    fun undoLast() {
        val u = _state.value.undo ?: return
        if (u.targetFor(_state.value.booth) == null) {
            say("That is no longer the booth on screen, so it cannot be put back.")
            return
        }
        viewModelScope.launch {
            store.clearUndo()
            saveBooth(u.before)
            _state.value = _state.value.copy(
                undo = null,
                message = "Put back: ${u.what}.",
            )
        }
    }

    /** True when there is something to take back and the family screen should offer it. */
    val canUndoQuietly: Boolean get() = _state.value.undo?.inToast == false

    // --- booths -------------------------------------------------------------

    fun selectBooth(id: String) {
        if (_state.value.booth.id == id) return
        viewModelScope.launch {
            val booth = store.loadBooth(id)
            val index = _state.value.index.copy(activeId = id)
            store.saveIndex(index)
            // Ids and family filters belong to the booth they were picked in. Carrying
            // either across would tick strangers or filter this booth by that one's
            // families, both silently.
            _state.value = _state.value.copy(
                index = index, booth = booth, query = "",
                casteFilter = "", partyFilter = "", addressFilter = "", notInFileOnly = false,
                selectedIds = emptySet(), familyQuery = "", selectedFamilyIds = emptySet(),
                // The same warning as on launch. It used to be read once in init and never
                // again, so switching away from a corrupt booth and back showed "No voters
                // list yet" while the selector still advertised its population -- which
                // looks exactly like a phone that had never been used, and is the state the
                // next import overwrites.
                message = store.unreadableBooth?.takeIf { it.first == id }?.let {
                    unreadableMessage(it.second)
                },
            )
        }
    }

    /** The one sentence he gets when a booth file would not read. */
    private fun unreadableMessage(keptAs: String): String =
        "The saved list could not be read and was set aside as $keptAs. " +
            "Nothing was deleted. Do not import over it until you have checked the file."

    /**
     * Renames the booth from the Settings field.
     *
     * Published immediately and written later. This field is bound to state, so with the
     * old order -- write, then publish -- Compose had no state to render while the disk write
     * was in flight and reverted the characters. One whole booth serialised per letter was
     * the other half of it. The same mistake, already fixed and documented on replaceVoter
     * above, in a second place.
     */
    fun renameBooth(label: String) {
        val trimmed = label.trim()
        if (trimmed.isBlank()) return
        _state.value = _state.value.copy(booth = _state.value.booth.copy(label = trimmed))
        persistSoon()
    }

    // --- dead ---------------------------------------------------------------
    //
    // Wiping a booth was reachable at some point and then the button went away. The code
    // stayed, and it is the most dangerous function in the app: it deletes a booth's file
    // outright, with no confirmation and no backup, and its only guard is a toast. Nothing
    // calls it, and keeping it means the next person to wire up a Settings button can make
    // that mistake by accident. It is removed rather than kept warm.

    // --- import -------------------------------------------------------------

    /** Opens the file picker. The reading state begins once a file actually arrives. */
    fun beginImport() { _state.value = _state.value.copy(importStep = ImportStep.Idle) }

    fun cancelImport() { _state.value = _state.value.copy(importStep = ImportStep.Idle) }

    /**
     * Reads the chosen sheet and reports what is in it.
     *
     * Nothing is written at this point. Reading and committing are separate steps so a bad file
     * -- wrong columns, an empty sheet, a file that is not a spreadsheet at all -- costs a
     * report and nothing else.
     */
    fun onFilePicked(uri: Uri) {
        _state.value = _state.value.copy(importStep = ImportStep.Reading)
        // Deliberately nothing else: no file, no side effects.
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.use { XlsxReader.read(it) }
                }
            }
            val r = result.getOrNull()
            when {
                r == null -> fail("That file could not be opened. If it is in Drive or another " +
                    "app, download it to the phone first and try again.")
                !r.usable && r.missingColumns.isNotEmpty() -> fail(
                    "The file is missing: ${r.missingColumns.joinToString(", ")}.\n\n" +
                        "It needs four labels at the top -- serial, voter id, name, house " +
                        "number -- in any order. Add them in Excel or Google Sheets, save " +
                        "the file, then choose it here."
                )
                !r.usable -> fail("That file has nobody in it.")
                else -> _state.value = _state.value.copy(
                    importStep = ImportStep.NeedsDetails(r.rows, summarise(r), null),
                )
            }
        }
    }

    private fun fail(reason: String) {
        _state.value = _state.value.copy(importStep = ImportStep.Failed(reason))
    }

    private fun summarise(r: XlsxReader.Result): String = buildString {
        append("The file has ${r.rows.size} people in it.")
        if (r.blankName > 0) append(" ${r.blankName} with no name.")
        if (r.blankHouse > 0) append(" ${r.blankHouse} with no door number.")
        if (r.unreadableSerial > 0) {
            append(" ${r.unreadableSerial} with a serial number that is not a number.")
        }
        if (r.rows.any { !it.hasSerial }) append(" Some serials were blank.")
    }

    /**
     * Commits the import against the booth number given.
     *
     * An existing booth with that number is updated rather than replaced, and a file that is
     * simply the same roll again changes nothing. That is the failsafe: importing booth 108 by
     * mistake cannot destroy booth 108.
     */
    fun confirmImport(boothNo: String, mandal: String, townWard: String, label: String) {
        val step = _state.value.importStep
        if (step !is ImportStep.NeedsDetails) return
        val no = boothNo.trim()
        if (no.isEmpty()) {
            say("A booth number is needed. It is how booths are told apart.")
            return
        }
        val existing = _state.value.index.booths.firstOrNull { it.boothNo.equals(no, ignoreCase = true) }
        val meta = BoothImporter.Meta(no, mandal, townWard, label)
        lastBoothNo = no
        lastMandal = mandal.trim()
        lastWard = townWard.trim()
        persistRemembered()

        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                if (existing == null) {
                    BoothImporter.create(meta, step.rows, System.currentTimeMillis())
                } else {
                    val booth = store.loadBooth(existing.id)
                    BoothImporter.merge(booth, meta, step.rows, System.currentTimeMillis())
                }
            }
            store.saveBooth(outcome.booth)
            val index = indexFor(outcome.booth, _state.value.index)
            store.saveIndex(index)
            val activate = existing == null || _state.value.booth.id == outcome.booth.id
            _state.value = _state.value.copy(
                index = index,
                booth = if (activate) outcome.booth else _state.value.booth,
                importStep = ImportStep.Done(describe(outcome, existing != null)),
            )
        }
    }

    /**
     * Dismisses the import result and lands where the work continues.
     *
     * The result stays on screen until it is acknowledged: the earlier version cleared it on
     * a timer, which dropped the user back onto the file prompt with no booth open and no
     * visible outcome -- the "it is still asking for the file" dead end. The counts move to
     * the toast, which clears itself, so nothing here needs a timer.
     */
    fun finishImport() {
        val step = _state.value.importStep
        _state.value = _state.value.copy(
            importStep = ImportStep.Idle,
            message = (step as? ImportStep.Done)?.message,
        )
    }

    private fun describe(o: BoothImporter.Outcome, wasUpdate: Boolean): String = buildString {
        append(if (wasUpdate) "Updated " else "Imported ")
        append("${o.booth.label}: ")
        if (o.added > 0) append("${o.added} added. ")
        if (o.updated > 0) append("${o.updated} changed. ")
        if (o.unchanged > 0) append("${o.unchanged} already matched. ")
        if (o.keptButNotInFile > 0) {
            append("${o.keptButNotInFile} kept but marked as not in the file. ")
        }
        if (o.blanksKept > 0) {
            append("${o.blanksKept} blank cells did not overwrite what you had. ")
        }
    }.trim()

    // --- navigation ---------------------------------------------------------

    fun setQuery(q: String) { _state.value = _state.value.copy(query = q) }

    fun setTab(name: String) { _state.value = _state.value.copy(tab = name) }
    fun setFamilyQuery(q: String) { _state.value = _state.value.copy(familyQuery = q) }
    fun setFilter(f: UiState.Filter) { _state.value = _state.value.copy(filter = f) }
    fun setCasteFilter(v: String) { _state.value = _state.value.copy(casteFilter = v) }
    fun setPartyFilter(v: String) { _state.value = _state.value.copy(partyFilter = v) }
    fun setAddressFilter(v: String) { _state.value = _state.value.copy(addressFilter = v) }
    fun toggleNotInFileOnly() {
        _state.value = _state.value.copy(notInFileOnly = !_state.value.notInFileOnly)
    }

    /** Clears the search and every filter, but leaves the ticked rows ticked. */
    fun clearListFilters() {
        _state.value = _state.value.copy(
            query = "", filter = UiState.Filter.ALL,
            casteFilter = "", partyFilter = "", addressFilter = "", notInFileOnly = false,
        )
    }

    // --- selection ----------------------------------------------------------

    fun toggleSelect(id: String) {
        val sel = _state.value.selectedIds
        _state.value = _state.value.copy(
            selectedIds = if (id in sel) sel - id else sel + id,
        )
    }

    /** Ticks everything the list currently shows. Starts from, not replaces, the set. */
    fun selectAllVisible() {
        val s = _state.value
        _state.value = s.copy(selectedIds = s.selectedIds + s.visible.map { it.id })
    }

    fun clearSelection() {
        if (_state.value.selectedIds.isNotEmpty()) {
            _state.value = _state.value.copy(selectedIds = emptySet())
        }
    }

    fun toggleFamilySelect(id: String) {
        val sel = _state.value.selectedFamilyIds
        _state.value = _state.value.copy(
            selectedFamilyIds = if (id in sel) sel - id else sel + id,
        )
    }

    fun clearFamilySelection() {
        if (_state.value.selectedFamilyIds.isNotEmpty()) {
            _state.value = _state.value.copy(selectedFamilyIds = emptySet())
        }
    }

    /** Ticks every family the list currently shows. Starts from, not replaces, the set. */
    fun selectAllFamiliesVisible() {
        val s = _state.value
        _state.value = s.copy(selectedFamilyIds = s.selectedFamilyIds + s.visibleFamilies.map { it.family.id })
    }

    /** The ticked families that still exist. */
    private fun liveFamilySelection(): Set<String> {
        val ids = _state.value.booth.families.map { it.id }.toSet()
        return _state.value.selectedFamilyIds.intersect(ids)
    }

    private fun familyWord(n: Int) = if (n == 1) "family" else "families"

    /** The ticked rows that still exist. Ids of deleted voters never act again. */
    private fun liveSelection(): Set<String> {
        val ids = _state.value.booth.voters.map { it.id }.toSet()
        return _state.value.selectedIds.intersect(ids)
    }

    private fun personWord(n: Int) = if (n == 1) "person" else "people"

    fun deleteSelected() {
        val ids = liveSelection()
        if (ids.isEmpty()) {
            say("Nobody is ticked.")
            return
        }
        val booth = _state.value.booth
        viewModelScope.launch {
            saveBooth(booth.withoutVoters(ids).pruned(), undoWhat = "putting back the ${ids.size} you removed")
            if (_state.value.openVoterId in ids) closeVoter()
            _state.value = _state.value.copy(
                selectedIds = emptySet(),
                message = "${ids.size} ${personWord(ids.size)} removed.",
            )
        }
    }

    fun markSelectedVisited() = markSelected(visited = true, skipped = false, what = "visited")

    fun markSelectedPending() = markSelected(visited = false, skipped = false, what = "still to visit")

    fun markSelectedSkipped() = markSelected(visited = false, skipped = true, what = "skipped")

    private fun markSelected(visited: Boolean, skipped: Boolean, what: String) {
        val ids = liveSelection()
        if (ids.isEmpty()) {
            say("Nobody is ticked.")
            return
        }
        val booth = _state.value.booth
        viewModelScope.launch {
            saveBooth(booth.withVisitState(ids, visited, skipped), undoWhat = "the marks on ${ids.size} you changed")
            _state.value = _state.value.copy(
                selectedIds = emptySet(),
                message = "${ids.size} ${personWord(ids.size)} marked $what.",
            )
        }
    }

    /**
     * Moves the ticked rows into [familyId]. Movers go to the end of the family, like a
     * single add does, and anyone already in another family is moved rather than refused.
     */
    fun assignSelectedToFamily(familyId: String) {
        val ids = liveSelection()
        if (ids.isEmpty()) {
            say("Nobody is ticked.")
            return
        }
        val booth = _state.value.booth
        val label = booth.familiesWithMembers.firstOrNull { it.family.id == familyId }?.label
            ?: "the family"
        viewModelScope.launch {
            saveBooth(booth.withFamily(ids, familyId).pruned(), undoWhat = "putting ${ids.size} back where they were")
            _state.value = _state.value.copy(
                selectedIds = emptySet(),
                message = "${ids.size} ${personWord(ids.size)} moved to $label.",
            )
        }
    }

    /** Makes a new family out of the ticked rows, last in the walking order. */
    fun moveSelectedToNewFamily() {
        val ids = liveSelection()
        if (ids.isEmpty()) {
            say("Nobody is ticked.")
            return
        }
        val booth = _state.value.booth
        val id = Voter.newId()
        val order = (booth.families.maxOfOrNull { it.order } ?: -1) + 1
        // Serial order, so the new family reads the way the roll reads.
        val moving = booth.voters.filter { it.id in ids }.sortedBy { it.serial }
        val position = moving.map { it.id }.withIndex().associate { (i, mid) -> mid to i }
        val voters = booth.voters.map {
            if (it.id in ids) it.copy(familyId = id, familyOrder = position[it.id] ?: 0) else it
        }
        viewModelScope.launch {
            saveBooth(booth.copy(voters = voters, families = booth.families + Family(id, order)).pruned(),
                undoWhat = "removing the new family")
            _state.value = _state.value.copy(
                selectedIds = emptySet(),
                message = "New family made with ${ids.size} ${personWord(ids.size)}.",
            )
        }
    }

    fun deleteFamilies() {
        val ids = liveFamilySelection()
        if (ids.isEmpty()) {
            say("No families are ticked.")
            return
        }
        val booth = _state.value.booth
        val n = ids.size
        // Same promise as deleting one: the grouping goes, the people stay.
        val voters = booth.voters.map {
            if (it.familyId in ids) it.copy(familyId = null, familyOrder = 0) else it
        }
        viewModelScope.launch {
            saveBooth(
                booth.copy(
                    voters = voters,
                    families = booth.families.filterNot { it.id in ids },
                ),
                undoWhat = "putting back the $n you deleted",
            )
            if (_state.value.openFamilyId in ids) closeFamily()
            _state.value = _state.value.copy(
                selectedFamilyIds = emptySet(),
                message = "$n ${familyWord(n)} deleted. Their people stay on the list.",
            )
        }
    }

    fun openVoter(id: String) {
        _state.value = _state.value.copy(openVoterId = id, creatingVoter = false, draft = null)
    }

    fun closeVoter() {
        // A draft simply stops existing. Nothing was written, so nothing needs undoing.
        _state.value = _state.value.copy(openVoterId = null, creatingVoter = false, draft = null)
    }

    fun openFamily(id: String?) {
        _state.value = _state.value.copy(
            openFamilyId = id, creatingVoter = false, draft = null, pickingMember = false,
        )
    }

    /**
     * Closes the family editor, dropping the family if nobody ended up in it.
     *
     * "New family" creates the family immediately so there is something to add to. If somebody
     * then changes their mind, that empty family used to sit in the booth forever -- invisible,
     * because a family with no members is not printed and not listed, so there was no way to
     * notice them accumulating over a week of surveying.
     */
    fun closeFamily() {
        val s = _state.value
        val id = s.openFamilyId
        if (id != null) {
            val booth = s.booth
            val stillEmpty = booth.families.any { it.id == id } &&
                booth.voters.none { it.familyId == id }
            if (stillEmpty) {
                viewModelScope.launch {
                    saveBooth(booth.copy(families = booth.families.filterNot { it.id == id }))
                }
            }
        }
        _state.value = s.copy(openFamilyId = null, pickingMember = false)
    }

    fun openPicker() { _state.value = _state.value.copy(pickingMember = true) }
    fun closePicker() { _state.value = _state.value.copy(pickingMember = false) }

    // --- editing ------------------------------------------------------------

    private fun editEditing(f: (Voter) -> Voter) {
        val s = _state.value
        if (s.creatingVoter) {
            val d = s.draft ?: return
            _state.value = s.copy(draft = f(d))
            return
        }
        val id = s.openVoterId ?: return
        replaceVoter(id, f)
    }

    private fun replaceVoter(id: String, f: (Voter) -> Voter) {
        val s = _state.value
        val i = s.booth.voters.indexOfFirst { it.id == id }
        if (i < 0) return
        val voters = s.booth.voters.toMutableList()
        voters[i] = f(voters[i])
        // Published before it is written, not after.
        //
        // This used to write the booth to disk first and publish from inside that write. A
        // keystroke therefore did not change `value` until the file had been written, and a
        // controlled text field whose `value` does not match what is being typed reverts the
        // keystroke. On the voter form that meant exactly one character per focus: the first
        // landed once the write finished, and every one after it was thrown away, so "TDP"
        // came out as "T". A new voter never saw it -- the draft path publishes in place.
        _state.value = s.copy(booth = s.booth.copy(voters = voters))
        persistSoon()
    }

    /**
     * The write behind an edit, held back until typing pauses.
     *
     * Publishing is immediate (above) so the field keeps every character; the disk write is
     * what waits, so a name typed at speed is one write rather than one per letter. Cancelling
     * the previous one before its delay has elapsed means a superseded write is abandoned
     * before it starts, never halfway through a file.
     */
    private fun persistSoon() {
        pendingSave?.cancel()
        pendingSave = viewModelScope.launch {
            delay(saveSettleMs)
            val s = _state.value
            store.saveBooth(s.booth)
            val index = indexFor(s.booth, s.index)
            store.saveIndex(index)
            // Read the current state rather than the one captured above: anything typed
            // while this was writing belongs in front of the index.
            _state.value = _state.value.copy(index = index)
        }
    }

    private var pendingSave: Job? = null

    fun setField(field: String, value: String) {
        // A value typed into a list-backed field is remembered when the editor closes, not on
        // every keystroke. Learning it immediately would push a write per character.
        if (!_state.value.creatingVoter) kindOf(field)?.let { pendingLearn[it] = value.trim() }
        editEditing { v ->
            when (field) {
                // Silently refusing to take a letter, with no word, was the old behaviour:
                // type "12a" and it shows "12", as though the a had never been pressed, and
                // there is no way to tell a rejected keystroke from a dropped one.
                "serial" -> {
                    val trimmed = value.trim()
                    val n = trimmed.toIntOrNull()
                    // Zero is allowed and means "no number yet", which the roll has for
                    // people added at a door. Negative was not: it stored, it sorted above
                    // serial 1, it printed as -5 in the booklet's serial column and in the
                    // spreadsheet, and duplicate detection ignores anything at or below zero,
                    // so it could be entered twice and both copies would be kept.
                    if (n != null && n < 0) say("A serial cannot be negative. Leave it blank if there is no number.")
                    if (n == null && trimmed.isNotEmpty() && trimmed.any { it.isDigit() }) {
                        say("A serial is a whole number, like 312.")
                    }
                    if (n != null && n >= 0) v.copy(serial = n) else v
                }
                "voterId" -> v.copy(voterId = value)
                "name" -> v.copy(name = value)
                "house" -> v.copy(house = value)
                "phone" -> {
                    // Ditto: a leading +, an 0, or an eleventh digit used to vanish without
                    // a word. Now it says so once, rather than storing half a number.
                    val kept = value.filter { it.isDigit() }
                    if (kept != value) {
                        say(
                            when {
                                kept.length >= 10 -> "A phone number here stops at 10 digits."
                                else -> "A phone number here is digits only."
                            }
                        )
                    }
                    v.copy(phone = kept.take(10))
                }
                "address" -> v.copy(address = value)
                "caste" -> v.copy(caste = value)
                "subCaste" -> v.copy(subCaste = value)
                "party" -> v.copy(party = value)
                else -> v
            }
        }
    }

    private fun kindOf(field: String): Lists.Kind? = when (field) {
        "address" -> Lists.Kind.ADDRESS
        "caste" -> Lists.Kind.CASTE
        // Missing here means a sub caste typed into the form never reaches the shared list,
        // so it is offered to nobody but this one person. The list exists; this was the gap.
        "subCaste" -> Lists.Kind.SUBCASTE
        "party" -> Lists.Kind.PARTY
        else -> null
    }

    fun toggleVisited() = editEditing { it.copy(visited = !it.visited, skipped = false) }
    fun toggleSkipped() = editEditing { it.copy(skipped = !it.skipped, visited = false) }

    /**
     * Neither visited nor skipped: back on the list.
     *
     * The third answer to a three-way question, which the two toggles cannot express --
     * clearing a skip needs a way that is not "mark visited".
     */
    fun setPending() = editEditing { it.copy(visited = false, skipped = false) }

    /** Clears the "not in the latest file" mark once you have decided they should stay. */
    fun keepAnyway(id: String) = replaceVoter(id) { it.copy(notInLatestFile = false) }

    /**
     * Opens an empty form for a new voter. Nothing is created yet.
     *
     * The serial is offered as one past the highest in use, because a person met at a door is
     * nearly always somebody who ought to be in the roll, sitting just past the end of it.
     */
    fun startCreateVoter() {
        val highest = _state.value.booth.voters.maxOfOrNull { it.serial } ?: 0
        // + 1 on Int.MAX_VALUE wraps to Int.MIN_VALUE, which was then stored on confirm
        // and printed as a negative serial. A roll at the very top of Int is not a real
        // thing, but offering a number that cannot exist is worse than saying so.
        val suggested = if (highest >= Int.MAX_VALUE) 0 else highest + 1
        if (suggested == 0 && highest > 0) {
            say("That list already has the highest serial a number can be. Type the number you want.")
        }
        _state.value = _state.value.copy(
            openVoterId = null, creatingVoter = true, draft = Voter(serial = suggested),
        )
    }

    /**
     * Turns the draft into a real voter.
     *
     * Refuses an empty form rather than adding a row with nothing in it: a nameless entry in
     * the roll cannot be surveyed, cannot be found and cannot be removed by whoever ends up
     * with the final spreadsheet.
     */
    fun confirmCreateVoter() {
        val d = _state.value.draft ?: run { closeVoter(); return }
        if (d.name.isBlank() && d.voterId.isBlank() && d.house.isBlank()) {
            say("Fill in at least a name before adding this person.")
            return
        }
        flushPendingLearn()
        val booth = _state.value.booth
        viewModelScope.launch {
            saveBooth(booth.copy(voters = booth.voters + d))
            _state.value = _state.value.copy(
                openVoterId = d.id, creatingVoter = false, draft = null,
                message = "${d.name} added.",
            )
        }
    }

    fun deleteVoter(id: String) {
        val booth = _state.value.booth
        val v = booth.voters.firstOrNull { it.id == id } ?: return
        // The person goes; the family they were in stays standing. Losing one member is not a
        // reason to lose the rest of the household.
        viewModelScope.launch {
            saveBooth(booth.withoutVoters(setOf(id)).pruned(), undoWhat = "putting back ${v.name}")
            if (_state.value.openVoterId == id) closeVoter()
            publish("${v.name.ifBlank { "Voter" }} removed.")
        }
    }

    // --- families -----------------------------------------------------------

    fun createFamily() {
        val id = Voter.newId()
        val booth = _state.value.booth
        // After everyone else: a family created later is visited later, until arranged.
        val order = (booth.families.maxOfOrNull { it.order } ?: -1) + 1
        viewModelScope.launch {
            saveBooth(booth.copy(families = booth.families + Family(id, order)), undoWhat = "removing the new family")
            openFamily(id)
        }
    }

    fun deleteFamily(id: String) {
        val booth = _state.value.booth
        val members = booth.voters.count { it.familyId == id }
        // The people stay. Deleting a family is a statement about the grouping, not about the
        // roll, and losing four people because a grouping was wrong would be unforgivable.
        val voters = booth.voters.map {
            if (it.familyId == id) it.copy(familyId = null, familyOrder = 0) else it
        }
        viewModelScope.launch {
            saveBooth(booth.copy(voters = voters, families = booth.families.filterNot { it.id == id }))
            if (_state.value.openFamilyId == id) closeFamily()
            val n = members
            publish("Family removed. Its $n ${if (n == 1) "person is" else "people are"} back in the unassigned list.")
        }
    }

    /**
     * Adds a voter to a family.
     *
     * A voter already in another family is moved rather than refused, because the commonest
     * cause is a family built slightly wrong and corrected a week later, and the surveyor
     * knows which way it should go.
     */
    /**
     * Creates a person who is not in the roll and puts them in the family.
     *
     * This is the case that actually comes up at a door: somebody answers, they are not on the
     * list, and there is no way to add them from where you are standing. Before this the only
     * route was to back out of the family, find them on the Voters tab, then come back -- three
     * screens and a search, standing on a doorstep, for something the roll genuinely does not
     * cover yet.
     *
     * The serial offered is one past the highest in use, on the same reasoning as everywhere
     * else: a person met at a door is nearly always somebody who ought to have been in the roll.
     */
    fun createAndAddMember(familyId: String, name: String) {
        val booth = _state.value.booth
        val suggested = (booth.voters.maxOfOrNull { it.serial } ?: 0) + 1
        val order = booth.voters.count { it.familyId == familyId }
        val v = Voter(serial = suggested, name = name.trim(), familyId = familyId, familyOrder = order)
        viewModelScope.launch {
            saveBooth(booth.copy(voters = booth.voters + v))
            // Straight back to the family, which now contains them. Opening the new voter
            // instead would put the picker and the family both in the back stack for no reason:
            // the form they came from is not one anybody asked to see.
            _state.value = _state.value.copy(
                pickingMember = false,
                message = "${v.name} added to the family.",
            )
        }
    }

    fun addMember(familyId: String, voterId: String) {
        val booth = _state.value.booth
        val order = booth.voters.count { it.familyId == familyId }
        val voters = booth.voters.map {
            when {
                it.id != voterId -> it
                it.familyId == familyId -> it
                else -> it.copy(familyId = familyId, familyOrder = order)
            }
        }
        viewModelScope.launch { saveBooth(booth.copy(voters = voters).pruned()) }
    }

    fun removeMember(familyId: String, voterId: String) {
        val booth = _state.value.booth
        val remaining = booth.voters
            .filter { it.familyId == familyId }
            .sortedBy { it.familyOrder }
            .filterNot { it.id == voterId }
        // Closing the gap keeps familyOrder a clean 0..n-1 run. Leaving holes would still sort
        // correctly today and produce absurd orderings the first time somebody moved a member.
        val order = remaining.map { it.id }.withIndex().associate { (i, id) -> id to i }
        val voters = booth.voters.map {
            when {
                // The one leaving goes back to being unassigned, like a deleted family's
                // members do. Without this line the confirm dialog agrees and nothing
                // happens: they keep the family id and stay put.
                it.id == voterId -> it.copy(familyId = null, familyOrder = 0)
                it.familyId != familyId -> it
                else -> it.copy(familyOrder = order[it.id] ?: it.familyOrder)
            }
        }
        val who = booth.voters.firstOrNull { it.id == voterId }?.name.orEmpty()
        viewModelScope.launch {
            saveBooth(booth.copy(voters = voters).pruned(), undoWhat = "putting $who back")
        }
    }

    /** Moves a member one place up or down, from the arrows or one step of a drag. */
    fun moveMember(familyId: String, voterId: String, by: Int) {
        val booth = _state.value.booth
        val moved = booth.movingMember(familyId, voterId, by)
        if (moved === booth) return
        // Quiet: an arrow tap is not an announcement, but a drag that ends up wrong still
        // has to be repairable without four more taps.
        viewModelScope.launch { saveBooth(moved, undoWhat = "the move", undoInToast = false) }
    }

    /**
     * Moves a family one place up or down in the walking order, from the arrows or one
     * step of a drag. Reassigns the clean run like members do, so the order is always
     * 0..n-1.
     */
    fun moveFamily(familyId: String, by: Int) {
        val booth = _state.value.booth
        val moved = booth.movingFamily(familyId, by)
        if (moved === booth) return
        // The printed walking order is the artefact this app exists to produce, so a drag
        // that lands wrong must be repairable. No toast: a toast on every arrow tap, and on
        // every step of a drag, is noise that hides the one that mattered.
        viewModelScope.launch { saveBooth(moved, undoWhat = "the move", undoInToast = false) }
    }

    // --- managed lists ------------------------------------------------------

    /**
     * Records a value the surveyor typed so it is offered next time.
     *
     * Learning from use matters more than it sounds: an address list that starts empty is an
     * empty dropdown on the first house, and a list that has to be curated before work can
     * start is a list that will not be.
     */
    fun learnValue(kind: Lists.Kind, value: String) {
        val v = value.trim()
        if (v.isEmpty()) return
        val current = _state.value.lists.forKind(kind)
        if (current.any { it.equals(v, ignoreCase = true) }) return
        viewModelScope.launch { commitLists(_state.value.lists.with(kind, current + v)) }
    }

    /** How long editing pauses before the booth is written, in milliseconds. */
    private val saveSettleMs = 250L

    private val pendingLearn = mutableMapOf<Lists.Kind, String>()

    private fun flushPendingLearn() {
        val pending = pendingLearn.toMap()
        pendingLearn.clear()
        if (pending.isEmpty()) return
        val lists = pending.entries.fold(_state.value.lists) { acc, (kind, value) ->
            val current = acc.forKind(kind)
            if (value.isEmpty() || current.any { it.equals(value, ignoreCase = true) }) acc
            else acc.with(kind, current + value)
        }
        viewModelScope.launch { commitLists(lists) }
    }

    private suspend fun commitLists(lists: Lists) {
        store.saveLists(lists)
        _state.value = _state.value.copy(lists = lists)
    }

    fun addListValue(kind: Lists.Kind, value: String) {
        val v = value.trim()
        if (v.isEmpty()) return
        val current = _state.value.lists.forKind(kind)
        if (current.any { it.equals(v, ignoreCase = true) }) {
            say("$v is already on the list"); return
        }
        viewModelScope.launch { commitLists(_state.value.lists.with(kind, current + v)) }
    }

    fun removeListValue(kind: Lists.Kind, value: String) {
        val lists = _state.value.lists
        val booth = _state.value.booth
        // Caste and sub caste are also per-voter values, so dropping one from its list has
        // to clear it on anyone holding it. Leaving a recorded value the list no longer
        // offers prints something the dropdown can no longer produce. Party and address are
        // left alone: the form keeps them, and the app says so in the confirmation.
        val cleared = when (kind) {
            Lists.Kind.CASTE -> booth.copy(voters = booth.voters.map {
                if (it.caste.equals(value, true)) it.copy(caste = "") else it
            })
            Lists.Kind.SUBCASTE -> booth.copy(voters = booth.voters.map {
                if (it.subCaste.equals(value, true)) it.copy(subCaste = "") else it
            })
            else -> booth
        }
        viewModelScope.launch {
            saveBooth(cleared)
            commitLists(lists.with(kind, lists.forKind(kind).filterNot { it.equals(value, true) }))
        }
    }

    fun countWith(kind: Lists.Kind, value: String): Int = _state.value.voters.count {
        when (kind) {
            Lists.Kind.CASTE -> it.caste.equals(value, true)
            Lists.Kind.SUBCASTE -> it.subCaste.equals(value, true)
            Lists.Kind.ADDRESS -> it.address.equals(value, true)
            Lists.Kind.PARTY -> it.party.equals(value, true)
        }
    }

    /** Remembers what to prefill next time, so a routine re-import is three taps, not five. */
    fun rememberImportDetails(boothNo: String, mandal: String, townWard: String) {
        lastBoothNo = boothNo
        lastMandal = mandal
        lastWard = townWard
    }

    fun lastImport(): Triple<String, String, String> = Triple(lastBoothNo, lastMandal, lastWard)

    /**
     * A snapshot of the active booth, for exports.
     *
     * Exports run after the file picker closes, when the composition is no longer around to
     * read from, so they are handed the data rather than given a way to look it up.
     */
    fun activeBooth(): Booth = _state.value.booth

    /** The shared vocabulary, for exports that run after the composition has gone. */
    fun activeLists(): Lists = _state.value.lists

    // --- backup ------------------------------------------------------------

    /**
     * The manual one: writes a backup and hands the bytes back for the save dialog.
     *
     * Returns the text rather than saving it, because where it goes is chosen by the
     * system picker and only the caller knows what it was called.
     */
    fun buildBackup(): String = store.backupJson(_state.value.booth, _state.value.lists)

    /** The name the save dialog offers. Booth, then the moment, for the same reason exports are dated. */
    fun backupFileName(): String {
        val no = _state.value.booth.boothNo.trim()
        val who = if (no.isEmpty()) "booth" else "booth-$no"
        val now = java.util.Date()
        val fmt = java.text.SimpleDateFormat("ddMMM-HHmm", java.util.Locale.getDefault())
        return "$who-backup-${fmt.format(now)}.json"
    }

    /**
     * Reads a backup and describes it, without applying it.
     *
     * Returns null when the file is not one of ours -- a spreadsheet he picked by mistake
     * should say so rather than half-restore. Otherwise the booth's name and how many people
     * are in it, which is what he needs in front of him before agreeing to replace a day of
     * work with an older copy.
     */
    fun inspectBackup(text: String, then: (Pair<String, Int>?) -> Unit) {
        val b = store.readBackup(text)
        then(b?.let { it.booth.displayTitle to it.booth.voters.size })
    }

    /** Puts a backup back, keeping the booth it replaces. Refuses anything not a backup. */
    fun restoreBackup(text: String): String? {
        val b = store.readBackup(text) ?: return "That file is not a Booth Survey backup."
        val booth = b.booth
        val current = _state.value.booth
        // Whatever it replaces is written out first, under its own name. A restore can
        // destroy a week of work, so it must never be the only copy of anything.
        val replacedJson = current
            .takeIf { it.id == booth.id && it.voters.isNotEmpty() }
            ?.let { store.boothJson(it) }
        viewModelScope.launch {
            if (replacedJson != null) {
                // Checked, and acted on. This result used to be dropped, so the dialog's
                // promise -- "what is there now is saved first, so it is not lost" -- was
                // made and then the save below destroyed it anyway. A restore can put a
                // week of work back a week, and the one guard standing between him and an
                // unrecoverable afternoon has to be real.
                val kept = store.keepCopy(
                    "replaced-${booth.id}-${System.currentTimeMillis()}.json", replacedJson
                )
                if (!kept) {
                    _state.value = _state.value.copy(
                        message = "Could not save a copy of what is there now, so nothing " +
                            "has been changed. The phone may be out of space.",
                    )
                    return@launch
                }
            }
            store.saveBooth(booth)
            store.saveLists(b.lists)
            // Through indexFor, not a second hand-built entry. restore used to build its own,
            // and the two had already drifted on what "done" meant -- one counted visited and
            // skipped, the other visited alone -- so the same booth showed a different number
            // in the selector after a restore than before it. One builder now.
            val index = indexFor(booth, _state.value.index)
            store.saveIndex(index)
            _state.value = _state.value.copy(
                index = index,
                booth = booth,
                lists = b.lists,
                undo = null,
                openVoterId = null,
                openFamilyId = null,
                message = "${booth.displayTitle} restored. ${booth.voters.size} people in it.",
            )
            store.clearUndo()
        }
        return null
    }

    /**
     * The automatic one.
     *
     * Written to Downloads/Booth Survey/, so it lands somewhere he can see and copy off
     * without being told how. It only runs when two things are true: the booth is actually
     * different from the last automatic backup, and either that was on an earlier day or
     * it is more than [AUTO_MIN_GAP_MS] ago. Without the first, simply opening the app
     * would write files; without the second, a morning's work would write one per change.
     *
     * Deliberately not a background service. A surveyor opening the app and the app keeping
     * a copy is enough, and a service would cost battery on a phone that has to last a
     * day outdoors.
     */
    private val lastAutoAt: Long get() = store.lastAutoAt()
    private val lastAutoFingerprint: String get() = store.lastAutoFingerprint()

    fun autoBackupIfDue(now: Long = System.currentTimeMillis()) {
        val booth = _state.value.booth
        if (!_state.value.hasBooth) return
        val fingerprint = booth.fingerprint()
        if (fingerprint == lastAutoFingerprint) return
        val due = lastAutoAt == 0L ||
            now - lastAutoAt > AUTO_MIN_GAP_MS ||
            !sameDay(now, lastAutoAt)
        if (!due) return
        // Snapshotted here, on the main thread, before anything suspends. buildBackup reads
        // state, and state can change while the file is being written.
        val json = store.backupJson(booth, _state.value.lists)
        val name = backupFileName()
        viewModelScope.launch {
            // Off the main thread, which viewModelScope is not. This builds a
            // several-hundred-kilobyte string and then does four ContentResolver round
            // trips -- insert, open, query, up to twenty deletes -- all of it in the frame
            // he is trying to tap something in. On the phone this build is aimed at that is
            // a visible pause, and an ANR if the disk is slow.
            val written = withContext(Dispatchers.IO) { BackupFiles.write(json, name) }
            if (written) {
                store.noteAutoBackup(now, fingerprint)
                publish("Saved a backup by itself.")
            }
            // If it did not write, nothing is said and nothing is recorded: the fingerprint
            // stays unrecorded, so the next launch tries again rather than believing a day
            // of marks has been kept.
        }
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val f = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
        return f.format(java.util.Date(a)) == f.format(java.util.Date(b))
    }

    /** A message with no action behind it, so there is nothing to take back. */
    fun say(m: String) { _state.value = _state.value.copy(message = m, undo = null) }
    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}
