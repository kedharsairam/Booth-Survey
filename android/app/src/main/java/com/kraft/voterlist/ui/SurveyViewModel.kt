package com.kraft.voterlist.ui

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
    val filter: Filter = Filter.ALL,
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
) {
    enum class Filter { ALL, REMAINING, DONE }

    // A booth is identified by its booth number. Testing the id instead would be true
    // for the empty placeholder, which is created with a fresh id, and the "nothing
    // imported yet" screen would never appear.
    val hasBooth: Boolean get() = booth.boothNo.isNotBlank()
    val voters: List<Voter> get() = booth.voters
    val families: List<Family> get() = booth.families

    val totalCount: Int get() = voters.size
    val doneCount: Int get() = voters.count { it.isDone }
    val remainingCount: Int get() = totalCount - doneCount
    val familyCount: Int get() = families.size
    val unassignedCount: Int get() = voters.count { it.familyId == null }
    val notInFileCount: Int get() = voters.count { it.notInLatestFile }

    val familiesWithMembers: List<FamilyWithMembers> get() = booth.familiesWithMembers

    /** Members of a family in the order the surveyor arranged them. */
    fun membersOf(familyId: String): List<Voter> =
        voters.filter { it.familyId == familyId }.sortedBy { it.familyOrder }

    fun familyNamed(familyId: String?): String =
        if (familyId == null) "" else familiesWithMembers.firstOrNull { it.family.id == familyId }?.label ?: ""

    private fun asSurvey() = Survey(booth, lists)

    val printOrder: List<PrintOrder.Block> get() = PrintOrder.blocks(asSurvey())

    /** The voter being edited: the saved one, or the unsaved draft. */
    val editing: Voter? get() = draft ?: voters.firstOrNull { it.id == openVoterId }

    /**
     * The voter list in serial order.
     *
     * Serial, not door number, on purpose. The booklet is sorted by door number so a street is
     * walked in one direction, but the app is the thing that has to be trusted when the paper
     * is wrong -- and the door number is the field the extraction got wrong most often. Serial
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
}

class SurveyViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)
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
            _state.value = _state.value.copy(index = index, lists = lists, booth = booth, loading = false)
        }
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
            done = booth.done,
            notInFile = booth.notInFile,
            updatedAt = booth.importedAt,
        )
        val others = index.booths.filterNot { it.id == booth.id }
        return index.copy(activeId = booth.id, booths = others + entry)
    }

    private suspend fun saveBooth(booth: Booth) {
        store.saveBooth(booth)
        val index = indexFor(booth, _state.value.index)
        store.saveIndex(index)
        _state.value = _state.value.copy(index = index, booth = booth)
    }

    // --- booths -------------------------------------------------------------

    fun selectBooth(id: String) {
        if (_state.value.booth.id == id) return
        viewModelScope.launch {
            val booth = store.loadBooth(id)
            val index = _state.value.index.copy(activeId = id)
            store.saveIndex(index)
            _state.value = _state.value.copy(index = index, booth = booth, query = "")
        }
    }

    fun renameBooth(label: String) {
        val booth = _state.value.booth
        if (label.isBlank()) return
        viewModelScope.launch { saveBooth(booth.copy(label = label.trim())) }
    }

    /** Forgets a booth and everything in it. Only offered when it is empty. */
    fun forgetBooth() {
        val booth = _state.value.booth
        if (booth.size > 0) {
            say("This booth still has ${booth.size} people in it. Empty it first.")
            return
        }
        viewModelScope.launch {
            store.deleteBooth(booth.id)
            val index = _state.value.index.copy(
                activeId = null,
                booths = _state.value.index.booths.filterNot { it.id == booth.id },
            )
            store.saveIndex(index)
            _state.value = _state.value.copy(
                index = index,
                booth = Booth(boothNo = ""),
                message = "${booth.label} removed.",
            )
        }
    }

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
                    "The sheet is missing: ${r.missingColumns.joinToString(", ")}.\n\n" +
                        "It needs four columns -- serial, voter id, name, house number -- in any " +
                        "order. Export the blank template from Settings to get them."
                )
                !r.usable -> fail("No rows were found in that sheet.")
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
        append("${r.rows.size} rows read.")
        if (r.blankName > 0) append(" ${r.blankName} with no name.")
        if (r.blankHouse > 0) append(" ${r.blankHouse} with no door number.")
        if (r.unreadableSerial > 0) {
            append(" ${r.unreadableSerial} with a serial that is not a number.")
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
            // Drop the "needs details" payload so a back swipe cannot re-enter a stale sheet.
            viewModelScope.launch {
                kotlinx.coroutines.delay(2600)
                if (_state.value.importStep is ImportStep.Done) {
                    _state.value = _state.value.copy(importStep = ImportStep.Idle, message = null)
                }
            }
        }
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
    fun setFilter(f: UiState.Filter) { _state.value = _state.value.copy(filter = f) }

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
        val booth = _state.value.booth
        val i = booth.voters.indexOfFirst { it.id == id }
        if (i < 0) return
        val voters = booth.voters.toMutableList()
        voters[i] = f(voters[i])
        viewModelScope.launch { saveBooth(booth.copy(voters = voters)) }
    }

    fun setField(field: String, value: String) {
        // A value typed into a list-backed field is remembered when the editor closes, not on
        // every keystroke. Learning it immediately would push a write per character.
        if (!_state.value.creatingVoter) kindOf(field)?.let { pendingLearn[it] = value.trim() }
        editEditing { v ->
            when (field) {
                "serial" -> v.copy(serial = value.trim().toIntOrNull() ?: v.serial)
                "voterId" -> v.copy(voterId = value)
                "name" -> v.copy(name = value)
                "house" -> v.copy(house = value)
                "phone" -> v.copy(phone = value.filter { it.isDigit() }.take(10))
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
        "party" -> Lists.Kind.PARTY
        else -> null
    }

    fun toggleVisited() = editEditing { it.copy(visited = !it.visited, skipped = false) }
    fun toggleSkipped() = editEditing { it.copy(skipped = !it.skipped, visited = false) }

    /** Clears the "not in the latest file" mark once you have decided they should stay. */
    fun keepAnyway(id: String) = replaceVoter(id) { it.copy(notInLatestFile = false) }

    /**
     * Opens an empty form for a new voter. Nothing is created yet.
     *
     * The serial is offered as one past the highest in use, because a person met at a door is
     * nearly always somebody who ought to be in the roll, sitting just past the end of it.
     */
    fun startCreateVoter() {
        val suggested = (_state.value.booth.voters.maxOfOrNull { it.serial } ?: 0) + 1
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
            saveBooth(booth.copy(voters = booth.voters.filterNot { it.id == id }))
            if (_state.value.openVoterId == id) closeVoter()
            publish("${v.name.ifBlank { "Voter" }} removed.")
        }
    }

    // --- families -----------------------------------------------------------

    fun createFamily() {
        val id = Voter.newId()
        val booth = _state.value.booth
        viewModelScope.launch {
            saveBooth(booth.copy(families = booth.families + Family(id)))
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
        viewModelScope.launch { saveBooth(booth.copy(voters = voters)) }
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
            if (it.familyId != familyId) it else it.copy(familyOrder = order[it.id] ?: it.familyOrder)
        }
        viewModelScope.launch { saveBooth(booth.copy(voters = voters)) }
    }

    /** Moves a member one place up or down. Arrows rather than dragging: this is used one-handed. */
    fun moveMember(familyId: String, voterId: String, by: Int) {
        val booth = _state.value.booth
        val members = booth.voters.filter { it.familyId == familyId }.sortedBy { it.familyOrder }
        val from = members.indexOfFirst { it.id == voterId }
        val to = from + by
        if (from < 0 || to < 0 || to >= members.size) return
        val reordered = members.toMutableList()
        reordered.add(to, reordered.removeAt(from))
        val order = reordered.map { it.id }.withIndex().associate { (i, id) -> id to i }
        val voters = booth.voters.map {
            if (it.familyId != familyId) it else it.copy(familyOrder = order[it.id] ?: it.familyOrder)
        }
        viewModelScope.launch { saveBooth(booth.copy(voters = voters)) }
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
        val cleared = if (kind == Lists.Kind.CASTE) {
            // Caste is also a per-voter value, so dropping it from the list has to clear it on
            // anyone holding it. Leaving a recorded caste the list no longer offers prints
            // something the dropdown can no longer produce.
            booth.copy(voters = booth.voters.map {
                if (it.caste.equals(value, true)) it.copy(caste = "") else it
            })
        } else {
            booth
        }
        viewModelScope.launch {
            saveBooth(cleared)
            commitLists(lists.with(kind, lists.forKind(kind).filterNot { it.equals(value, true) }))
        }
    }

    fun countWith(kind: Lists.Kind, value: String): Int = _state.value.voters.count {
        when (kind) {
            Lists.Kind.CASTE -> it.caste.equals(value, true)
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

    fun say(m: String) { _state.value = _state.value.copy(message = m) }
    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}
