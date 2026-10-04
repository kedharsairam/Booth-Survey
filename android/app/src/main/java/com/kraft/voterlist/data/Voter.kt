package com.kraft.voterlist.data

/**
 * One voter.
 *
 * Four fields come from the sheet -- serial, voter ID, name and door number -- and the rest
 * is what the surveyor types at the door. All of them are editable, because the sheet the app
 * was seeded from is a draft: when the finalized list renumbers people, editing the number is
 * two seconds and redoing an extraction is an afternoon.
 *
 * What that costs is [serial] being a reliable key, so it is not one. [id] is what identifies a
 * record. It is never shown, because there is no use for it on a doorstep and a visible ID
 * invites somebody to edit it. It exists so that opening, editing, exporting and deleting a
 * voter all still mean one specific person once the serials have been changed or duplicated.
 */
data class Voter(
    /** Stable, hidden, unique. Generated once and never shown or edited. */
    val id: String = newId(),
    val serial: Int = 0,
    val voterId: String = "",
    val name: String = "",
    val house: String = "",
    val phone: String = "",
    val address: String = "",
    val caste: String = "",
    val subCaste: String = "",
    val party: String = "",
    val visited: Boolean = false,
    val skipped: Boolean = false,
    /** Null means not in any family. Only ever set by the surveyor's own hand. */
    val familyId: String? = null,
    /** Position within the family, 0 first. The order printed on the booklet. */
    val familyOrder: Int = 0,
    /**
     * In the app but absent from the most recent sheet for this booth.
     *
     * Kept rather than deleted, because deleting is not recoverable here and a finalized roll
     * that has quietly dropped somebody should not quietly delete your work on them. But it
     * has to be visible: if the roll struck them off as dead or moved away, the booklet would
     * otherwise still send you to their door.
     */
    val notInLatestFile: Boolean = false,
) {
    /** Done means visited and recorded. A skipped house is not done -- it still needs going back to. */
    val isDone: Boolean get() = visited || skipped

    /**
     * Whether the printed door number could not be read.
     *
     * This no longer groups anyone, so the cost of a bad value is only that the voter sorts
     * oddly in the booklet. It still gets marked rather than quietly coerced, because a wrong
     * door number is worth knowing about before you knock.
     */
    val houseIsSuspect: Boolean get() = house.isNotBlank() && !HOUSE_OK.matches(house)

    /** Shown wherever a serial appears, so two of them cannot pass unnoticed. */
    fun serialIsDuplicated(others: List<Voter>): Boolean =
        serial > 0 && others.count { it.serial == serial && it.id != id } > 0

    /**
     * The same voter ID on two records.
     *
     * This is the check that matters, not a duplicate serial. Serials are meant to be unique
     * and in practice are, so that flag almost never fires; but a voter ID is the identity key,
     * and two records carrying one are either the same person entered twice or a transcription
     * slip that would merge two households into one on the final spreadsheet.
     */
    fun voterIdIsDuplicated(others: List<Voter>): Boolean {
        val mine = voterId.trim()
        return mine.isNotEmpty() && others.count { it.voterId.trim() == mine && it.id != id } > 0
    }

    companion object {
        /**
         * Two groups of digits joined by dashes, with an optional "/1", "-C" or "A" suffix.
         *
         * Deliberately permissive. A house number that fails this is flagged, not rejected,
         * and a flag nobody can act on is noise: the real forms in this ward are known and
         * anything genuinely unreadable should still be recordable.
         */
        private val HOUSE_OK =
            Regex("""^\d{1,2}-\d{1,3}-\d{1,3}(\s*[/ -]?\s*(\d{1,2}|[A-Z]))?$""")

        fun newId(): String = java.util.UUID.randomUUID().toString()
    }
}
