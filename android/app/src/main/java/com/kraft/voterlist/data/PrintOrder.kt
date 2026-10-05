package com.kraft.voterlist.data

/**
 * The order the booklet prints in.
 *
 * Door number leads, and the surveyor's arrangement within a family settles everything else.
 * A family is placed at the *lowest* house number among its members, because families do not
 * always sit at one address -- people move, households split, the roll disagrees with itself
 * -- and when that happens the lowest number is where someone looking for that street will
 * look first.
 *
 * Anyone not yet in a family prints as a family of one, and is numbered like any other. The
 * alternative, leaving their family column blank, would mean the numbered families drift out
 * of step with the paper already in his hand; numbering everybody means the booklet is a
 * complete work list from the first reprint of the first morning, before a single family has
 * been created.
 *
 * This lives in one place because two things have to agree: the PDF and the on-screen family
 * list. When they drift, the app says one number and the paper says another, which is the
 * specific confusion this ordering exists to prevent.
 */
object PrintOrder {

    /** A run of voters printed together: one family, or one person if they are in none. */
    data class Block(
        val familyId: String?,
        val members: List<Voter>,
        /** Derived, never stored. See [FamilyWithMembers.label]. */
        val label: String,
    ) {
        val isSolo: Boolean get() = familyId == null
        val size: Int get() = members.size
    }

    /**
     * Builds the blocks in print order.
     *
     * Ties on house number fall back to the lowest electoral serial in the block, which is
     * the only ordering available that nobody has to choose by hand.
     */
    fun blocks(survey: Survey): List<Block> {
        val familyBlocks = survey.familiesWithMembers.map { fwm ->
            Block(fwm.family.id, fwm.members, fwm.label)
        }
        val soloBlocks = survey.unassigned.map { v ->
            Block(null, listOf(v), "")
        }
        return (familyBlocks + soloBlocks).sortedWith(BLOCK_ORDER)
    }

    private val BLOCK_ORDER = Comparator<Block> { a, b ->
        // A blank house number sorts last: it is a number that could not be read, and an
        // unreadable number should not push a household to the front of the ward.
        val ka = keyOf(a)
        val kb = keyOf(b)
        val byHouse = compareNaturalKeys(ka.first, kb.first)
        if (byHouse != 0) return@Comparator byHouse
        ka.second.compareTo(kb.second)
    }

    private fun keyOf(b: Block): Pair<LongArray, Int> {
        val house = b.members.mapNotNull { it.house.trim().takeIf(String::isNotEmpty) }
            .minWithOrNull(NATURAL_HOUSE_ORDER)
        val lowest: Int = b.members.minOfOrNull { it.serial } ?: 0
        val key: LongArray = if (house == null) LongArray(1) { BLANK } else naturalKey(house)
        return key to lowest
    }

    /** Sorts after every real house number, however long. */
    private const val BLANK = Long.MAX_VALUE

    private fun compareNaturalKeys(a: LongArray, b: LongArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }
}
