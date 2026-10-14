package com.kraft.voterlist.data

/**
 * A family: a group of people somebody has decided belong together.
 *
 * This exists only because the surveyor created it while standing at a door. Nothing infers
 * one, and nothing in the electoral roll can imply one -- see [Voter.house].
 *
 * There is no name field, on purpose. A family is called after whoever stands first in it, so
 * "JAYARAM KAMISETTI family", and it is worked out from the members rather than stored. A
 * stored name would need keeping in step with the arrangement by hand: put the grandmother
 * first on Monday and the label still says whoever was first on Sunday, and the two disagree
 * with nothing to say so.
 *
 * Members are not stored here either. They are the voters whose `familyId` matches, so a
 * person cannot be in two families, and deleting a family is a matter of clearing the field on
 * its members rather than reconciling two copies of a list that has drifted apart.
 *
 * [order] is the family's position in the surveyor's walking order, 0 first. Set with the
 * same up and down arrows as members, for the same one-handed reason. A family created
 * later goes last; moving one closes the gap like members do, so the run stays a clean
 * 0..n-1. The spreadsheet prints in this order, and the family screen
 * shows it, so the three cannot disagree.
 */
data class Family(val id: String, val order: Int = -1)

/**
 * One booth's voters and families.
 *
 * A booth is the unit the electoral roll is filed under, and it is the unit this app holds.
 * Keeping booths apart rather than merging them into one list is the whole point: covering a
 * second booth must not put a question mark against a day of work on the first, and the only
 * way to guarantee that is to never put them in the same place.
 *
 * [label] is editable and defaults to "Booth <number>". Booth numbers are unique within an
 * assembly constituency but not across one, and two booths both called 108 in the same app
 * are indistinguishable when you are trying to work out which list you are holding.
 */
data class Booth(
    val id: String = Voter.newId(),
    val boothNo: String,
    val mandal: String = "",
    val townWard: String = "",
    val label: String = "Booth $boothNo",
    val voters: List<Voter> = emptyList(),
    val families: List<Family> = emptyList(),
    /** When the sheet was last read, for showing on the booth card. */
    val importedAt: Long = 0L,
) {
    val size: Int get() = voters.size
    val done: Int get() = voters.count { it.isDone }
    val notInFile: Int get() = voters.count { it.notInLatestFile }

    /** The booth title shown in the app, taken from this booth rather than from a constant. */
    val displayTitle: String get() = label.ifBlank { "Booth $boothNo" }

    /** Families with their members in the surveyor's arranged order. */
    val familiesWithMembers: List<FamilyWithMembers> by lazy {
        val known = families.map { it.id }.toSet()
        // A household that is not in `families` has nowhere to be printed. Treating the
        // voter as unassigned puts them back on the sheet rather than losing them, which
        // is the failure that once took 1,129 people off a booklet while Home still counted
        // 1,129. A dangling id is a corrupt save, not a reason for a person to vanish.
        val byFamily = voters.filter { it.familyId != null && it.familyId in known }
            .groupBy { it.familyId!! }
        families.mapNotNull { f ->
            val members = byFamily[f.id]?.sortedBy { it.familyOrder } ?: return@mapNotNull null
            FamilyWithMembers(f, members)
        }.sortedWith(compareBy({ it.family.order }, { it.sortKey }, { it.lowestSerial }))
    }

    /**
     * Everyone not in a family that exists, in roll order.
     *
     * "Not in a family" has to mean "in no household the file knows about", not merely "has a
     * null familyId". A voter pointing at a household that is not in `families` -- a corrupt
     * save, an old backup written before households were stored, a partial restore -- was in
     * neither this list nor [familiesWithMembers], so they printed nowhere at all while Home
     * still counted them. That is the same shape as the time 1,129 people vanished off a
     * booklet while the app kept saying 1,129: the parser was fixed, the assumption was not.
     */
    val unassigned: List<Voter> by lazy {
        val known = families.map { it.id }.toSet()
        voters.filter { it.familyId == null || it.familyId !in known }.sortedBy { it.serial }
    }

    // asSurvey() used to sit here and had no callers. The two exports each build a Survey
    // directly where they need one, which is the clearer place for it.

    /**
     * Moves one member of a family one place up or down, keeping familyOrder a clean
     * 0..n-1 run. The booth comes back unchanged if there is nothing to do.
     *
     * Out here, as a plain function, rather than only inside the ViewModel. The dragging and
     * the arrows used to be the only place this existed, so FamilyOrderTest had to
     * reimplement it line for line -- and all nine of its assertions would have passed with
     * the real one deleted. A test that copies the code it claims to guard guards nothing.
     * The drag still drives this; it just does not own it.
     */
    fun movingMember(familyId: String, voterId: String, by: Int): Booth {
        val members = voters.filter { it.familyId == familyId }.sortedBy { it.familyOrder }
        val from = members.indexOfFirst { it.id == voterId }
        val to = from + by
        if (from < 0 || to < 0 || to >= members.size) return this
        val reordered = members.toMutableList()
        reordered.add(to, reordered.removeAt(from))
        val order = reordered.map { it.id }.withIndex().associate { (i, id) -> id to i }
        return copy(
            voters = voters.map {
                if (it.familyId != familyId) it
                else it.copy(familyOrder = order[it.id] ?: it.familyOrder)
            }
        )
    }

    /**
     * Moves one family to an absolute position in the walking order.
     *
     * The arrows move one place at a time, which is right for a list of families but
     * hopeless for one with hundreds of them -- the old drag only ever stepped a single
     * place per row-height of finger travel, so getting a card from tenth from the top to
     * eighth from the bottom meant dragging across most of the screen twice. This takes the
     * slot the finger is actually over, so one drag puts it where it was dropped.
     *
     * [toIndex] is the position it should end up at, not the one it is travelling past.
     * Out of range is clamped rather than refused, because the drop lands a finger's width
     * outside the card half the time and a reorder that silently does nothing is worse than
     * one that lands at the end.
     */
    fun movingFamilyTo(familyId: String, toIndex: Int): Booth {
        val ordered = families.sortedBy { it.order }
        val from = ordered.indexOfFirst { it.id == familyId }
        if (from < 0) return this
        val to = toIndex.coerceIn(0, ordered.size)
        if (from == to) return this
        val reordered = ordered.toMutableList()
        // Removed first, then inserted at the target, so the target is the final index
        // rather than an index that has shifted underneath it.
        val item = reordered.removeAt(from)
        reordered.add(to.coerceIn(0, reordered.size), item)
        val order = reordered.map { it.id }.withIndex().associate { (i, id) -> id to i }
        return copy(families = families.map { it.copy(order = order[it.id] ?: it.order) })
    }

    /**
     * Moves one family one place up or down in the walking order, reassigning a clean
     * 0..n-1 run. The printed order is the whole point of the app, so this had a test -- and
     * the test was testing a copy.
     */
    fun movingFamily(familyId: String, by: Int): Booth {
        val ordered = families.sortedBy { it.order }
        val from = ordered.indexOfFirst { it.id == familyId }
        val to = from + by
        if (from < 0 || to < 0 || to >= ordered.size) return this
        val reordered = ordered.toMutableList()
        reordered.add(to, reordered.removeAt(from))
        val order = reordered.map { it.id }.withIndex().associate { (i, id) -> id to i }
        return copy(families = families.map { it.copy(order = order[it.id] ?: it.order) })
    }

    /**
     * Drops families nobody is in. Bulk moves and deletes can empty one, and an empty family
     * is invisible everywhere -- never printed, never listed -- with no way to notice it
     * accumulating. Same reason closing an emptied family drops it.
     */
    fun pruned(): Booth =
        if (families.all { f -> voters.any { it.familyId == f.id } }) this
        else copy(families = families.filter { f -> voters.any { it.familyId == f.id } })

    /**
     * Drops the given voters. Families they were in stay standing with whoever is left;
     * deleting one member is never a reason to lose the household.
     */
    fun withoutVoters(ids: Set<String>): Booth {
        val kept = voters.filterNot { it.id in ids }
        // Close the gaps. Deleting the second of three left the third sitting at position 2,
        // so the spreadsheet's "In family order" column read 1 and 3 for a two-person
        // household with no member 2 -- the booklet numbered it 1 and 2, and the column
        // meant for checking the app against the arrangement disagreed with it.
        val byFamily = kept.filter { it.familyId != null }.groupBy { it.familyId!! }
        val renumbered = byFamily
            .mapValues { (_, ms) -> ms.sortedBy { it.familyOrder }.map { it.id } }
            .flatMap { (_, ordered) -> ordered.mapIndexed { i, id -> id to i } }
            .toMap()
        return copy(
            voters = kept.map { v ->
                renumbered[v.id]?.let { if (it == v.familyOrder) v else v.copy(familyOrder = it) } ?: v
            }
        )
    }

    /** Sets the visited/skipped state on the given voters, leaving everything else alone. */
    fun withVisitState(ids: Set<String>, visited: Boolean, skipped: Boolean): Booth =
        copy(voters = voters.map {
            if (it.id in ids) it.copy(visited = visited, skipped = skipped) else it
        })

    /**
     * The people still to come back to: everyone not yet knocked at.
     *
     * Skipped counts as still to visit. The app says so in two places -- under the status
     * control, and on the bulk Skip action -- and it is the honest reading: a skipped house
     * is one nobody has been to. That is also what the Home headline counts now, because
     * counting skipped as finished is what let it say "All visited" over forty unvisited
     * houses.
     *
     * One function, used by both exports on purpose. They were reading the question
     * separately -- the spreadsheet dropped anyone visited *or* skipped, the booklet kept a
     * whole household if anybody in it was unfinished -- so one checkbox in the export sheet
     * produced two files containing different people, with nothing saying so. There is now
     * nothing to disagree about.
     */
    fun forPendingOnly(): Booth = copy(voters = voters.filter { !it.visited })

    /**
     * A cheap summary of what is in here, for noticing that the booth has changed.
     *
     * The automatic backup compares this rather than the whole booth, so it does not have
     * to read a megabyte of JSON on every launch. It is deliberately shallow -- people,
     * their serials and their marks -- so that a typo being fixed counts as a change, and
     * a list being merely re-saved does not.
     */
    fun fingerprint(): String = buildString {
        append(boothNo).append('|').append(mandal).append('|').append(families.size).append('|')
        for (v in voters) {
            append(v.id).append(',').append(v.serial).append(',')
            if (v.visited) append('V')
            if (v.skipped) append('S')
            append(v.familyId ?: "-").append('.').append(v.familyOrder).append(';')
        }
    }


    /**
     * Moves the given voters into [familyId], appended after the members already there in
     * roll order. Movers keep nothing of their old position: like [SurveyViewModel.addMember]
     * they go to the end, and the surveyor arranges from there.
     */
    fun withFamily(ids: Set<String>, familyId: String): Booth {
        val next = voters.count { it.familyId == familyId }
        // Sorted into a list, not a set, and the list is what gets walked.
        //
        // It used to be `...map { it.id }.toSet()`, and then the positions were handed out
        // by walking `voters` -- which is in sheet order. The sort was computed, discarded,
        // and did nothing. Three people taken into a household together came out in
        // whatever order the spreadsheet happened to list them, so on a part-wise roll
        // running 1, 101, 201 the order was decided by whoever sat earliest in the file.
        // Assigning by walking the sorted list means the order the surveyor sees when
        // choosing them is the order they get.
        val moving = voters.filter { it.id in ids }.sortedBy { it.serial }
        var order = next
        val positions = moving.associate { it.id to order++ }
        return copy(voters = voters.map { v ->
            val at = positions[v.id]
            if (at != null) v.copy(familyId = familyId, familyOrder = at) else v
        })
    }
}

/**
 * A family and its members, already in the order the surveyor arranged them.
 */
data class FamilyWithMembers(
    val family: Family,
    val members: List<Voter>,
) {
    val size: Int get() = members.size
    val done: Int get() = members.count { it.isDone }
    val complete: Boolean get() = size > 0 && done == size
    val lowestSerial: Int get() = members.minOfOrNull { it.serial } ?: 0

    // isFinished() used to live here, byte-identical to PrintOrder.Block.isFinished() --
    // and the copy here was the one the tests exercised while production used the other.
    // So the suite was proving a function nothing shipped, and the two could drift apart
    // without a test noticing. PrintOrder.Block.isFinished() is the single definition;
    // SurveySemanticsTest now calls it.

    /** Whoever is first, which is who the family is named after. */
    val head: Voter? get() = members.firstOrNull { it.name.isNotBlank() } ?: members.firstOrNull()

    /**
     * The family's name, worked out rather than stored.
     *
     * Named after the first person with a name in it, so a family whose first member has not
     * been named yet falls through to the second. With nobody in it there is nothing to call
     * it, so it says so.
     */
    val label: String
        get() = head?.name?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it family" } ?: "Empty family"

    /**
     * Tiebreak when two families share a position: the lowest door number among the members.
     *
     * A family does not have to sit at one door number -- people move, families split, the
     * roll disagrees with itself -- and a clean 0..n-1 run never ties, so this only settles
     * files written by hand. Also shown on the family card, so the street is visible at a
     * glance.
     */
    val sortKey: String
        get() = members.mapNotNull { it.house.trim().takeIf(String::isNotEmpty) }
            .minWithOrNull(NATURAL_HOUSE_ORDER) ?: ""
}

/**
 * One booth's data plus the app-wide vocabulary, which is what the print order is worked out
 * against.
 */
data class Survey(
    val booth: Booth,
    val lists: Lists = Lists(),
) {
    val familiesWithMembers: List<FamilyWithMembers> get() = booth.familiesWithMembers
    val unassigned: List<Voter> get() = booth.unassigned
}

/**
 * The three dropdown vocabularies, shared by every booth.
 *
 * Shared rather than per-booth because caste is the same seven categories for a whole
 * constituency and party is the same handful everywhere, while area names overlap freely
 * between neighbouring booths. Re-entering them per booth would be work for no gain, and the
 * only cost of sharing is the occasional dropdown offering an area that is not in this booth.
 *
 * Address holds an area name only. The roll has no address data, the form gives one line, and
 * "KONDAPALLI" is something a surveyor can pick in one tap whereas a full address is not
 * something anyone should retype per person.
 */
data class Lists(
    val castes: List<String> = DEFAULT_CASTES,
    /**
     * Sub castes, grouped under the caste they belong to.
     *
     * Was a flat list, which made "choose OC, see only OC's" impossible -- the dropdown
     * offered every sub caste in the ward whichever caste was on the form, so the two
     * fields could contradict each other and nothing said so. A map keyed by caste means
     * the list the dropdown offers follows the field above it.
     *
     * A key with an empty list is kept rather than dropped: it is a caste he has opened and
     * may be about to type into.
     */
    val subCastes: Map<String, List<String>> = emptyMap(),
    val addresses: List<String> = DEFAULT_ADDRESSES,
    val parties: List<String> = DEFAULT_PARTIES,
) {
    /** The three vocabularies that are a plain list. Sub caste is not one: it has a parent. */
    fun forKind(kind: Kind): List<String> = when (kind) {
        Kind.CASTE -> castes
        Kind.ADDRESS -> addresses
        Kind.PARTY -> parties
        // Every sub caste on file, flattened. Only used where there is no caste to narrow
        // by; everywhere else goes through subCasteNamesFor.
        Kind.SUBCASTE -> subCastes.values.flatten().distinct()
    }

    fun with(kind: Kind, values: List<String>): Lists = when (kind) {
        Kind.CASTE -> copy(castes = values)
        Kind.ADDRESS -> copy(addresses = values)
        Kind.PARTY -> copy(parties = values)
        Kind.SUBCASTE -> withSubCaste(SubCaste.UNPARENTED, values)
    }

    /**
     * The sub castes belonging to [caste], matched without regard to case or spacing.
     *
     * A caste with a group of its own uses exactly that, so anything he removes stays
     * removed. A caste with no group gets the built-in list for it -- that is what makes the
     * dropdown useful on the first house of the first day, instead of needing to be typed in
     * before anybody can be surveyed.
     *
     * The exact key lookup this replaced was not enough twice over: a group typed as "oc"
     * sits under a caste list saying "OC", so the editor looked up a key that was not there
     * and showed an empty panel while its values sat in the file under the other spelling.
     */
    fun subCasteGroup(caste: String): List<String> {
        val key = caste.trim()
        val stored = subCastes.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
        if (stored != null) return stored.value.sortedSubCasteNames()
        return DEFAULT_SUBCASTES.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
            ?.value.orEmpty()
    }

    /**
     * The sub castes offered for a given caste.
     *
     * Three cases, and the distinction matters:
     *  - no caste at all, so nothing to narrow by: the unparented bucket, which is where a
     *    sub caste typed before its caste went
     *  - a caste the app knows, whether or not it has a group of its own: that group, or the
     *    built-in list for it. SC/ST is known and has neither, so it offers nothing -- which
     *    is right, and is not the same as not knowing it
     *  - a caste the app has never heard of: its own group if it has one, plus the
     *    unparented one, because there is nothing else that could apply
     *
     * The middle case used to fall through to the third, so OC offered his father's seven
     * *and* every sub caste ever typed before the grouping existed, which is not a list
     * anybody can read.
     */
    fun subCasteNamesFor(caste: String?): List<String> {
        val key = caste?.trim().orEmpty()
        if (key.isEmpty()) return subCasteGroup(SubCaste.UNPARENTED)
        val known = subCastes.keys.any { it.equals(key, ignoreCase = true) } ||
            DEFAULT_SUBCASTES.keys.any { it.equals(key, ignoreCase = true) } ||
            castes.any { it.equals(key, ignoreCase = true) }
        if (known) return subCasteGroup(key)
        return subCasteGroup(key) + subCasteGroup(SubCaste.UNPARENTED)
    }

    /**
     * Every caste that needs a sub caste editor of its own, once each.
     *
     * This is the castes on file plus any group a sub caste list was typed under, and the
     * two overlap: "OC" is a caste, and once one sub caste has been typed under it, "OC" is
     * also a key in the sub caste map. They were simply concatenated, so the Settings screen
     * drew two "Sub caste · OC" panels for the same caste.
     *
     * Unparented is left out. It is where a sub caste typed before its caste goes, not a
     * caste anybody can pick on the form, and giving it a panel would invite him to file
     * values under something the voter form could never offer.
     */
    fun castesWithSubCastes(): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>(castes.size)
        for (c in castes + subCastes.keys) {
            if (c == SubCaste.UNPARENTED) continue
            if (seen.add(c.trim().lowercase())) out += c
        }
        return out
    }

    /**
     * Sets the sub castes for a caste, replacing whatever spelling of that key was there.
     *
     * Without the case-insensitive removal this could leave "OC" and "oc" as two separate
     * groups -- the same duplicate panel again, reached by typing rather than by loading.
     *
     * An empty list keeps the key rather than dropping it, and that is not cosmetic. An
     * absent group means "not customised, use the built-in list"; an empty one means "he
     * removed them all". Dropping the key on empty made the second behave like the first,
     * so emptying a caste in Settings quietly refilled itself from the defaults on the next
     * launch. Sorted on the way in as well, so what is stored is what will be shown.
     */
    fun withSubCaste(caste: String, names: List<String>): Lists {
        val key = caste.trim()
        val rest = subCastes.filterKeys { !it.equals(key, ignoreCase = true) }
        return copy(subCastes = rest + (key to names.sortedSubCasteNames()))
    }

    /**
     * The four vocabularies, one per free-text column the form gives.
     *
     * Sub caste is here because the form has its own column for it, so a person has two
     * things to say and a shared list for only one of them leaves him typing the other at
     * every door. The order is the form's own column order: caste, sub caste, address,
     * party.
     */
    enum class Kind(val title: String, val hint: String) {
        CASTE("Caste", "The categories this constituency uses. Everybody is in exactly one."),
        SUBCASTE("Sub caste", "A finer group inside the caste. Its own column on the form."),
        ADDRESS("Current address", "An area name, not a full address. One line on the form."),
        PARTY("Party", "A short fixed set is quicker than typing."),
    }

    companion object {
        /**
         * The three categories this ward actually works in.
         *
         * Was seven: BC-A through BC-D, SC, ST and OC -- which is the fine-grained split
         * used on the electoral roll itself. For door-to-door work that is noise: a
         * surveyor is asking "which community", and BC-A versus BC-B is not a question he
         * asks at a door, only a question the final spreadsheet asks.
         *
         * **SC and ST are one entry here because that is how the ward is being worked.**
         * They are separate categories on the roll, so a sheet handed to the electoral
         * office with "SC/ST" in it has to be split by hand afterwards. If that is not
         * wanted, make them two entries here and nothing else changes.
         */
        val DEFAULT_CASTES = listOf("OC", "BC", "SC/ST")

        /**
         * The parties worth one tap. "Unknown" is in the list on purpose: at the door, not
         * knowing or not wanting to say is an answer, and making him type it at every house
         * that declines is how you end up with no party recorded at all.
         */
        val DEFAULT_PARTIES = listOf("TDP", "JSP", "BJP", "YSRCP", "Unknown")

        /**
         * The sub castes, as his father gave them.
         *
         * Grouped by caste because the dropdown can only offer a sub caste that belongs to
         * the caste above it -- a single flat list was what let an OC be recorded against a
         * BC-A sub caste with nothing on screen saying so.
         *
         * **No SC/ST entry, because there are none.** The roll has ST households in it and
         * this ward's survey does not ask for their sub caste; an empty group is left for
         * one rather than inventing values. The panel is still offered in Settings so that
         * adding one later needs no code change.
         *
         * Alphabetic, as asked. Note "Others" therefore sits in the middle rather than at
         * the end -- a catch-all at the bottom is the other convention and is a one-line
         * change if it is preferred.
         */
        val DEFAULT_SUBCASTES: Map<String, List<String>> = mapOf(
            "OC" to listOf(
                "Aarya Vaysya", "Brahmin", "Kapu", "Kshatriya", "Muslim", "Others", "Reddy",
            ),
            "BC" to listOf(
                "Devanga", "Kammari", "Karnibathulu", "Naya Bhrammana", "Others",
                "Padmasali", "Pallilu", "Rajika", "Segidi", "Vaddilu", "Visva Bhrammana",
            ),
        )

        /**
         * The areas he works, as his father gave them.
         *
         * An area name and not a full address: the roll has no address data, the form gives
         * one line, and "Kodugattu" is something a surveyor can pick in one tap whereas a
         * full address is not something anyone should retype per person.
         */
        val DEFAULT_ADDRESSES = listOf(
            "Journalist Colony", "Jetty Tank Road", "Kodugattu", "Others", "Pallapu Street",
        )
    }
}

/**
 * Turns "go after the third card" into the index that card should end up at.
 *
 * A drag works out where the finger is as a *slot* in a layout that still contains the card
 * being dragged: "before the fourth card", "after the second". Those two numberings differ
 * exactly when the slot is past the dragged card's own position, because the layout counts
 * that card while the result must not.
 *
 * Without the adjustment, dragging a card downward by one row asks for the index it already
 * holds -- so the drag does nothing for a row's worth of travel and then jumps two rows on
 * the next one, which is the exact stickiness the rework was meant to remove.
 *
 * Free-standing so it can be tested. It is two lines of arithmetic with a surprising answer,
 * which is the worst kind of code to leave inline in a gesture handler where no test can
 * reach it.
 */
fun slotToFinalIndex(slot: Int, from: Int): Int = if (slot > from) slot - 1 else slot

/**
 * Translate a drop position on the *shown* list into a position in the whole walking order.
 *
 * The families screen draws a search result, so the slots a finger can drop onto are indexes
 * into what is visible, not into the booth. Handing one straight to [Booth.movingFamilyTo]
 * -- which indexes the whole list -- silently reorders the printed booklet: search ten
 * households down for one house, drag it to the top of the screen, and it lands at position
 * zero of the entire order, becoming the first street on the walk. The screen looks
 * unchanged either way, because the filter hides the difference, and the search text is
 * remembered so it is still wrong tomorrow.
 *
 * Resolved by identity instead of by arithmetic. `slot` is a final index into the shown list
 * once the dragged card is out of the way -- see [slotToFinalIndex] -- so the household
 * sitting at that slot is the one the card belongs in front of. Looking that household up in
 * the full order gives the right index no matter how many rows the filter is hiding. A slot
 * past the end of what is showing means the end of the whole order, which is also what a
 * card dropped at the bottom of an unfiltered screen means.
 *
 * @param shown the family ids on screen, in order, the dragged card included.
 * @param familyId the card being dragged.
 * @param slot where it was dropped, in the shown list.
 * @param fullOrder every family id in walking order.
 * @return the index to hand to [Booth.movingFamilyTo].
 */
fun dropSlotToOrderIndex(
    shown: List<String>,
    familyId: String,
    slot: Int,
    fullOrder: List<String>,
): Int {
    val others = shown.filter { it != familyId }
    val here = fullOrder.indexOf(familyId)
    // Only the dragged card is on screen. There is no neighbour to sit against, so every
    // slot means the same thing -- leave it where it is -- and guessing "the end" would
    // walk that household to the far side of the ward because someone searched for it.
    if (others.isEmpty()) return if (here < 0) fullOrder.size else here
    // Dropped short of the end: in front of whichever household was sitting at that slot.
    // Dropped past the end of what is showing: just after the last one that is showing.
    // "Past the end" must not mean the end of the ward -- the finger never went near the
    // other two hundred households, and the card should not either.
    val occupant = others.getOrNull(slot)
    val anchor = occupant ?: others.last()
    val at = fullOrder.indexOf(anchor)
    // A household that is no longer in the full order means the two lists disagree; ending
    // up at the end is recoverable by undo, whereas a wrong index in the middle is not.
    if (at < 0) return fullOrder.size
    return if (occupant != null) at else at + 1
}

/** Names for the sub caste grouping, so the unparented bucket is not a magic string. */
object SubCaste {
    /** A sub caste with no caste above it: typed before the form was filled in. */
    const val UNPARENTED = "—"
}

/**
 * Sub castes in the order they should be offered: alphabetic, ignoring case.
 *
 * Sorted here rather than at the two places that show them, so the dropdown and the Settings
 * editor cannot disagree -- and so anything typed into a group lands in the right place
 * without either caller having to remember.
 *
 * Case-insensitive so "Vadde" and "vaddilu" sort together rather than all the capitals
 * first, which reads as two lists.
 */
internal fun List<String>.sortedSubCasteNames(): List<String> =
    sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.trim() })

/**
 * Splits a house number into digit runs and separators so it can be compared by value.
 *
 * Door numbers here are strings like `10-1-5`, `10-1-12`, `90-4-4/4`. Compared as text they
 * sort `10-1-12` before `10-1-5`, because '1' sorts before '5'. That puts a household in the
 * wrong place on the page and, worse, does it silently.
 */
fun naturalKey(s: String): LongArray {
    val out = ArrayList<Long>(4)
    val digits = StringBuilder()
    for (ch in s) {
        if (ch.isDigit()) {
            digits.append(ch)
        } else if (digits.isNotEmpty()) {
            out += digits.toString().toLongOrNull() ?: 0L
            digits.setLength(0)
            out += ch.code.toLong()
        } else {
            out += ch.code.toLong()
        }
    }
    if (digits.isNotEmpty()) out += digits.toString().toLongOrNull() ?: 0L
    return out.toLongArray()
}

/** Orders house numbers by value. A LongArray is not Comparable, so this is written out. */
fun compareNatural(a: String, b: String): Int {
    val ka = naturalKey(a)
    val kb = naturalKey(b)
    val n = minOf(ka.size, kb.size)
    for (i in 0 until n) {
        val c = ka[i].compareTo(kb[i])
        if (c != 0) return c
    }
    return ka.size.compareTo(kb.size)
}

val NATURAL_HOUSE_ORDER: Comparator<String> = Comparator { a, b -> compareNatural(a, b) }

/** Case- and whitespace-tolerant containment, so "10-1-3" finds "90-2-2/2". */
fun String.matchesLoose(needle: String): Boolean =
    trim().replace(" ", "").contains(needle.trim().replace(" ", ""), ignoreCase = true)
