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
 */
data class Family(val id: String)

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

    /** What the booklet header prints, taken from this booth rather than from a constant. */
    val displayTitle: String get() = label.ifBlank { "Booth $boothNo" }

    /** Families with their members in the surveyor's arranged order. */
    val familiesWithMembers: List<FamilyWithMembers> by lazy {
        val byFamily = voters.filter { it.familyId != null }.groupBy { it.familyId!! }
        families.mapNotNull { f ->
            val members = byFamily[f.id]?.sortedBy { it.familyOrder } ?: return@mapNotNull null
            FamilyWithMembers(f, members)
        }.sortedWith(compareBy({ it.sortKey }, { it.lowestSerial }))
    }

    /** Everyone not in a family, in roll order. */
    val unassigned: List<Voter> by lazy { voters.filter { it.familyId == null }.sortedBy { it.serial } }

    fun asSurvey(lists: Lists = Lists()) = Survey(this, lists)
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
     * Sort key for the booklet: the lowest door number among the members.
     *
     * A family does not have to sit at one door number -- people move, families split, the
     * roll disagrees with itself -- and when that happens the lowest one leads, so the family
     * prints where someone looking for that street expects to find it.
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
    val addresses: List<String> = emptyList(),
    val parties: List<String> = emptyList(),
) {
    fun forKind(kind: Kind): List<String> = when (kind) {
        Kind.CASTE -> castes
        Kind.ADDRESS -> addresses
        Kind.PARTY -> parties
    }

    fun with(kind: Kind, values: List<String>): Lists = when (kind) {
        Kind.CASTE -> copy(castes = values)
        Kind.ADDRESS -> copy(addresses = values)
        Kind.PARTY -> copy(parties = values)
    }

    enum class Kind(val title: String, val hint: String) {
        CASTE("Caste", "The categories this constituency uses. Everybody is in exactly one."),
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
