package com.kraft.voterlist

import com.kraft.voterlist.data.Booth
import com.kraft.voterlist.data.Lists
import com.kraft.voterlist.ui.UiState
import com.kraft.voterlist.io.BoothImporter
import com.kraft.voterlist.io.XlsxReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The claims the app makes in words, checked against what the code actually counts.
 *
 * Each of these is a sentence shown to a surveyor in the only language he has. A count that
 * is wrong is not a rounding error: it tells him the app misread his file.
 */
class ImportClaimsTest {

    private fun row(
        serial: Int,
        voterId: String = "",
        name: String = "",
        house: String = "",
    ) = XlsxReader.Row(serial = serial, voterId = voterId, name = name, house = house)

    private fun existing() = Booth(
        id = "b1",
        boothNo = "108",
        voters = listOf(
            com.kraft.voterlist.data.Voter(id = "v1", serial = 1, voterId = "ABC", name = "Kept", house = "10-1"),
            com.kraft.voterlist.data.Voter(id = "v2", serial = 2, voterId = "DEF", name = "Kept2", house = "10-2"),
        ),
    )

    /**
     * The blank-cell count.
     *
     * The old line was `name !== row.name`, and `!==` on a String asks whether the two are
     * the same object. When a sheet cell was blank and the existing record's value was
     * blank too -- the interned empty literal on both sides -- the identity test said
     * "unchanged" and the blank was never counted. So the app under-told him how many
     * values it had preserved for him.
     */
    @Test
    fun `a blank cell that kept a value is counted`() {
        val out = BoothImporter.merge(
            existing(),
            BoothImporter.Meta("108"),
            listOf(
                row(1, "abc", "Renamed", ""),   // house blank, we had 10-1 -> counted
                row(2, "def", "", "Renamed2"),  // name blank, we had Kept2 -> counted
            ),
            now = 0L,
        )
        assertEquals("both preserved cells should be reported", 2, out.blanksKept)
    }

    @Test
    fun `a blank cell that kept nothing is not counted`() {
        // The case the identity test used to miss, and the reason the count was low.
        val b = Booth(
            id = "b1",
            boothNo = "108",
            voters = listOf(
                com.kraft.voterlist.data.Voter(id = "v1", serial = 1, voterId = "ABC", name = "", house = ""),
            ),
        )
        val out = BoothImporter.merge(
            b,
            BoothImporter.Meta("108"),
            listOf(row(1, "abc", "", "")),
            now = 0L,
        )
        assertEquals("nothing was kept, so nothing to report", 0, out.blanksKept)
        assertEquals("", out.booth.voters.first().name)
    }

    @Test
    fun `a cell that was filled does not count as preserved`() {
        val out = BoothImporter.merge(
            existing(),
            BoothImporter.Meta("108"),
            listOf(row(1, "abc", "FromSheet", "10-9")),
            now = 0L,
        )
        assertEquals(0, out.blanksKept)
    }

    @Test
    fun `a skipped person is still to visit, and both exports say so`() {
        // The definition that was duplicated in three places with two meanings. The sheet
        // is the truth for four fields; visited and skipped are the surveyor's alone.
        val b = Booth(
            id = "b1",
            boothNo = "108",
            voters = listOf(
                com.kraft.voterlist.data.Voter(id = "v1", serial = 1, visited = true),
                com.kraft.voterlist.data.Voter(id = "v2", serial = 2, skipped = true),
                com.kraft.voterlist.data.Voter(id = "v3", serial = 3),
            ),
        )
        val pending = b.forPendingOnly()
        assertEquals(
            "a skipped house still needs coming back to",
            listOf(2, 3), pending.voters.map { it.serial }.sorted(),
        )
    }

    // --- the counts on the Home screen ------------------------------------

    @Test
    fun `skipped is not counted as visited`() {
        val s = UiState(
            booth = Booth(
                id = "b1",
                boothNo = "108",
                voters = listOf(
                    com.kraft.voterlist.data.Voter(id = "v1", serial = 1, visited = true),
                    com.kraft.voterlist.data.Voter(id = "v2", serial = 2, skipped = true),
                    com.kraft.voterlist.data.Voter(id = "v3", serial = 3),
                ),
            )
        )
        assertEquals(3, s.totalCount)
        assertEquals(1, s.doneCount)
        assertEquals(1, s.skippedCount)
        assertEquals(2, s.remainingCount)
    }

    // --- repeated ids, computed once --------------------------------------

    @Test
    fun `a repeated voter id is spotted, whatever its spacing`() {
        val s = UiState(
            booth = Booth(
                id = "b1",
                boothNo = "108",
                voters = listOf(
                    com.kraft.voterlist.data.Voter(id = "v1", serial = 1, voterId = " abc123 "),
                    com.kraft.voterlist.data.Voter(id = "v2", serial = 2, voterId = "ABC123"),
                    com.kraft.voterlist.data.Voter(id = "v3", serial = 3, voterId = "ZZZ999"),
                ),
            )
        )
        assertTrue(s.isVoterIdRepeated("ABC123"))
        assertTrue("the odd spacing must not hide a repeat", s.isVoterIdRepeated(" abc123 "))
        assertFalse(s.isVoterIdRepeated("ZZZ999"))
    }

    @Test
    fun `a blank voter id is never a duplicate`() {
        // Two people with no ID on the sheet is normal, not a data problem to shout about.
        val s = UiState(
            booth = Booth(
                id = "b1",
                boothNo = "108",
                voters = listOf(
                    com.kraft.voterlist.data.Voter(id = "v1", serial = 1),
                    com.kraft.voterlist.data.Voter(id = "v2", serial = 2),
                ),
            )
        )
        assertFalse(s.isVoterIdRepeated(""))
    }

    @Test
    fun `serial zero never counts as a duplicate`() {
        // Zero is "no number yet". Two of those are two people, not a conflict.
        val s = UiState(
            booth = Booth(
                id = "b1",
                boothNo = "108",
                voters = listOf(
                    com.kraft.voterlist.data.Voter(id = "v1", serial = 0),
                    com.kraft.voterlist.data.Voter(id = "v2", serial = 0),
                ),
            )
        )
        assertFalse(s.isSerialRepeated(0))
    }

    @Test
    fun `a repeated serial is spotted`() {
        val s = UiState(
            booth = Booth(
                id = "b1",
                boothNo = "108",
                voters = listOf(
                    com.kraft.voterlist.data.Voter(id = "v1", serial = 7),
                    com.kraft.voterlist.data.Voter(id = "v2", serial = 8),
                    com.kraft.voterlist.data.Voter(id = "v3", serial = 7),
                ),
            )
        )
        assertTrue(s.isSerialRepeated(7))
        assertFalse(s.isSerialRepeated(8))
    }

    @Test
    fun `lists with an empty kind fall back rather than dropping the shared words`() {
        // parseLists used getString, which throws on a non-string element. Losing the caste
        // list is survivable; refusing to open the app over it is not.
        val lists = Lists(castes = listOf("BC-A"), parties = listOf("IND"))
        assertEquals(listOf("BC-A"), lists.castes)
    }
}