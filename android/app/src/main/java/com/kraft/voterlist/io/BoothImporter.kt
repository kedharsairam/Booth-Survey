package com.kraft.voterlist.io

import com.kraft.voterlist.data.Booth
import com.kraft.voterlist.data.Voter

/**
 * Turning a sheet into a booth.
 *
 * Kept as plain functions over plain data, with no Android in it, because this is the code that
 * can lose a day of work. It is the part most worth being able to test on a desktop.
 *
 * **Matching.** A row in the sheet is matched to an existing record by **voter ID first,
 * serial second**. Voter ID is the identity key, so it is the only join that survives the
 * finalized list renumbering everybody -- joining on serial after a renumber attaches one
 * person's phone number to a different person, which is worse than starting again. Serial is
 * the fallback for rows where the ID is blank on either side. Name is never used: it repeats
 * twenty-four times in a single booth's roll.
 *
 * **Who wins.** The sheet supplies four fields, and on those four the sheet wins. Everything
 * the surveyor typed -- phone, address, caste, sub caste, party, visited, skipped, and the
 * family arrangement -- is left exactly as it was.
 *
 * **One exception, deliberately.** A blank cell in the sheet does not erase a value already
 * held. A partial or filtered export would otherwise quietly delete names and door numbers
 * that were read off the roll, and "the sheet is the truth" is not worth losing survey work
 * over a blank cell. The import says how many values it kept for this reason.
 *
 * **Anyone in the booth but not in the sheet is kept and flagged**, not deleted. Deleting is
 * not recoverable here, and a finalized roll that has quietly dropped somebody should not
 * quietly delete your work on them -- but they do get marked, because if the roll struck them
 * off as dead or moved away, the sheet would otherwise still send you to their door.
 */
object BoothImporter {

    data class Meta(
        val boothNo: String,
        val mandal: String = "",
        val townWard: String = "",
        val label: String = "",
    )

    data class Outcome(
        val booth: Booth,
        val added: Int = 0,
        val updated: Int = 0,
        val unchanged: Int = 0,
        val keptButNotInFile: Int = 0,
        val blanksKept: Int = 0,
    )

    /** A brand new booth from a sheet. */
    fun create(meta: Meta, rows: List<XlsxReader.Row>, now: Long): Outcome {
        val boothNo = meta.boothNo.trim()
        val booth = Booth(
            boothNo = boothNo,
            mandal = meta.mandal.trim(),
            townWard = meta.townWard.trim(),
            label = meta.label.trim().ifBlank { "Booth $boothNo" },
            voters = rows.map { row ->
                Voter(serial = row.serial, voterId = row.voterId, name = row.name, house = row.house)
            },
            importedAt = now,
        )
        return Outcome(booth = booth, added = rows.size)
    }

    /**
     * Folds a sheet into an existing booth.
     *
     * One existing record is claimed by at most one sheet row. Without that, a sheet containing
     * the same voter ID twice -- which happens, twice in this booth's own roll -- would fold
     * both rows onto a single record and lose one of them.
     */
    fun merge(existing: Booth, meta: Meta, rows: List<XlsxReader.Row>, now: Long): Outcome {
        val byVoterId = HashMap<String, MutableList<Voter>>()
        val bySerial = HashMap<Int, MutableList<Voter>>()
        for (v in existing.voters) {
            val id = v.voterId.trim().lowercase()
            if (id.isNotEmpty()) byVoterId.getOrPut(id) { mutableListOf() }.add(v)
            if (v.serial > 0) bySerial.getOrPut(v.serial) { mutableListOf() }.add(v)
        }

        val claimed = HashSet<String>()
        val result = ArrayList<Voter>(existing.voters.size + rows.size)
        var added = 0
        var updated = 0
        var unchanged = 0
        var blanksKept = 0

        for (row in rows) {
            val key = row.voterId.trim().lowercase()
            val match = if (key.isNotEmpty()) {
                byVoterId[key]?.firstOrNull { it.id !in claimed }
                    ?: bySerial[row.serial]?.firstOrNull { it.id !in claimed && it.serial > 0 }
            } else {
                bySerial[row.serial]?.firstOrNull { it.id !in claimed && it.serial > 0 }
            }

            if (match == null) {
                result += Voter(
                    serial = row.serial,
                    voterId = row.voterId,
                    name = row.name,
                    house = row.house,
                )
                added++
                continue
            }

            claimed += match.id
            val name = row.name.ifBlank { match.name }
            val house = row.house.ifBlank { match.house }
            val voterId = row.voterId.ifBlank { match.voterId }
            // Counted on what the cell held and what was kept, by value. The old test was
            // `name !== row.name`, and `!==` on a String asks whether the two are the same
            // *object*, not whether they are the same word. When both were blank -- which is
            // the interned empty literal on both sides -- the identity test said "equal" and
            // the blank was not counted, so the number shown to him under-counted the cells
            // this exception saved. Said plainly: sheet blank, we have something, kept it.
            if (row.name.isBlank() && name.isNotBlank()) blanksKept++
            if (row.house.isBlank() && house.isNotBlank()) blanksKept++

            val serial = if (row.serial > 0) row.serial else match.serial
            val merged = match.copy(
                serial = serial,
                voterId = voterId,
                name = name,
                house = house,
                notInLatestFile = false,
            )
            result += merged
            val changed = merged.serial != match.serial ||
                merged.voterId != match.voterId ||
                merged.name != match.name ||
                merged.house != match.house
            if (changed) updated++ else unchanged++
        }

        // Everyone the sheet did not mention, kept and marked.
        var kept = 0
        for (v in existing.voters) {
            if (v.id in claimed) continue
            result += v.copy(notInLatestFile = true)
            kept++
        }

        val outcome = Outcome(
            booth = existing.copy(
                boothNo = meta.boothNo.trim().ifBlank { existing.boothNo },
                mandal = meta.mandal.trim().ifBlank { existing.mandal },
                townWard = meta.townWard.trim().ifBlank { existing.townWard },
                label = meta.label.trim().ifBlank { existing.label },
                voters = result,
                importedAt = now,
            ),
            added = added,
            updated = updated,
            unchanged = unchanged,
            keptButNotInFile = kept,
            blanksKept = blanksKept,
        )
        return outcome
    }
}
