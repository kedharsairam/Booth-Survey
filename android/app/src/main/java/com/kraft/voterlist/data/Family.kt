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
        val byFamily = voters.filter { it.familyId != null }.groupBy { it.familyId!! }
        families.mapNotNull { f ->
            val members = byFamily[f.id]?.sortedBy { it.familyOrder } ?: return@mapNotNull null
            FamilyWithMembers(f, members)
        }.sortedWith(compareBy({ it.family.order }, { it.sortKey }, { it.lowestSerial }))
    }

    /** Everyone not in a family, in roll order. */
    val unassigned: List<Voter> by lazy { voters.filter { it.familyId == null }.sortedBy { it.serial } }

    fun asSurvey(lists: Lists = Lists()) = Survey(this, lists)

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
    fun withoutVoters(ids: Set<String>): Booth =
        copy(voters = voters.filterNot { it.id in ids })

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
     * Moves the given voters into [familyId], appended after the members already there in
     * roll order. Movers keep nothing of their old position: like [SurveyViewModel.addMember]
     * they go to the end, and the surveyor arranges from there.
     */
    fun withFamily(ids: Set<String>, familyId: String): Booth {
        var order = voters.count { it.familyId == familyId }
        val moving = voters.filter { it.id in ids }.sortedBy { it.serial }.map { it.id }.toSet()
        return copy(voters = voters.map {
            if (it.id in moving) it.copy(familyId = familyId, familyOrder = order++) else it
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

    /**
     * Whether the whole household has been knocked at, for the booklet's pending-only run.
     *
     * The same question [Booth.forPendingOnly] asks, asked per household rather than per
     * person, because the booklet keeps a house whole: a house with anyone left in it
     * prints. Without a shared definition the two exports disagree about who "still to
     * visit" means, which is exactly the bug this replaced.
     */
    fun isFinished(): Boolean = members.all { it.visited }

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
    val subCastes: List<String> = emptyList(),
    val addresses: List<String> = emptyList(),
    val parties: List<String> = emptyList(),
) {
    fun forKind(kind: Kind): List<String> = when (kind) {
        Kind.CASTE -> castes
        Kind.SUBCASTE -> subCastes
        Kind.ADDRESS -> addresses
        Kind.PARTY -> parties
    }

    fun with(kind: Kind, values: List<String>): Lists = when (kind) {
        Kind.CASTE -> copy(castes = values)
        Kind.SUBCASTE -> copy(subCastes = values)
        Kind.ADDRESS -> copy(addresses = values)
        Kind.PARTY -> copy(parties = values)
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
        /** The seven categories the roll itself uses, so the dropdown works on the first house. */
        val DEFAULT_CASTES = listOf("BC-A", "BC-B", "BC-C", "BC-D", "SC", "ST", "OC")
    }
}

/**
 * Splits a house number into digit runs and separators so it can be compared by value.
 *
 * Door numbers here are strings like `10-1-5`, `10-1-12`, `93-2-2/3`. Compared as text they
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

/** Case- and whitespace-tolerant containment, so "10-1-3" finds "96-3-3/5". */
fun String.matchesLoose(needle: String): Boolean =
    trim().replace(" ", "").contains(needle.trim().replace(" ", ""), ignoreCase = true)
