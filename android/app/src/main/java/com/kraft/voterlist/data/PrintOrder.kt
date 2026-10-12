package com.kraft.voterlist.data

/**
 * The order the export prints in.
 *
 * Families print in the surveyor's arranged walking order, set with the arrows on the
 * family screen: the street is walked the way he arranged it, not the way the roll
 * numbers it. Inside a family, his member arrangement settles everything, as before.
 *
 * Anyone not yet in a family prints after every family, in roll order — the only ordering
 * nobody had to choose. Interleaving them by door number would mix two orderings — the
 * surveyor's and the roll's — into one nobody arranged. Dad builds a family for everyone,
 * even a single person, so in practice there are no solos; the fallback exists so a
 * straggler still prints somewhere sensible rather than nowhere.
 *
 * This lives in one place because two things have to agree: the spreadsheet and the
 * on-screen family list. When they drift, the app says one number and the sheet says
 * another, which is the specific confusion this ordering exists to prevent.
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

        /**
         * Whether everyone in this block has been knocked at.
         *
         * The booklet's answer to "still to visit", and it has to match
         * [Booth.forPendingOnly]'s. The two were written separately and disagreed -- the
         * spreadsheet dropped anyone visited or skipped, this kept any house with one
         * person left -- so one checkbox produced two different sets of people. Both now
         * ask the same question of [Voter.visited].
         */
        fun isFinished(): Boolean = members.all { it.visited }
    }

    /**
     * Builds the blocks in print order: families in the arranged order, then everyone
     * unassigned, house-sorted. Ties within the arranged order fall back to the lowest
     * door number, then the lowest electoral serial — the same rule as before, now only
     * reached when two families share a position, which a clean 0..n-1 run never has.
     */
    fun blocks(survey: Survey): List<Block> {
        val familyBlocks = survey.familiesWithMembers.map { fwm ->
            Block(fwm.family.id, fwm.members, fwm.label)
        }
        val soloBlocks = survey.unassigned.map { v ->
            Block(null, listOf(v), "")
        }
        return familyBlocks + soloBlocks
    }
}
