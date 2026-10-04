package com.kraft.voterlist.io

import com.kraft.voterlist.data.Booth
import com.kraft.voterlist.data.Voter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Folding a sheet into a booth.
 *
 * This is the code that can lose a day of work, so it is the part most worth being able to test
 * without a phone. The cases that matter are the ones where being wrong is expensive:
 *
 *   - re-importing the same roll must change nothing at all
 *   - the finalized list renumbering everybody must not shuffle one person's phone number onto
 *     another person
 *   - a partial sheet must not delete names and door numbers that were read off the roll
 *   - a sheet with the same voter ID twice must not collapse two people into one record
 */
class BoothImporterTest {

    private var n = 0
    private fun row(serial: Int, voterId: String = "W$serial", name: String = "PERSON $serial", house: String = "10-1-$serial") =
        XlsxReader.Row(serial, voterId, name, house)

    private fun existing(vararg v: Voter) = Booth(
        id = "b1", boothNo = "108", mandal = "Palakollu", townWard = "4",
        voters = v.toList(), families = emptyList(),
    )

    private fun voter(
        serial: Int = ++n,
        voterId: String = "W$serial",
        name: String = "PERSON $serial",
        house: String = "10-1-$serial",
        phone: String = "",
        visited: Boolean = false,
    ) = Voter(id = "id$serial", serial = serial, voterId = voterId, name = name, house = house, phone = phone, visited = visited)

    private fun meta(no: String = "108") = BoothImporter.Meta(no, "Palakollu", "4")

    // --- the same roll again ------------------------------------------------

    @Test
    fun `importing the same roll again changes nothing`() {
        val booth = existing(
            voter(1, "W1", "HEMA", "96-3-3/5", phone = "9848012345", visited = true),
            voter(2, "W2", "SAI", "94-2-2/4"),
        )
        val out = BoothImporter.merge(booth, meta(), listOf(row(1, "W1", "HEMA", "96-3-3/5"), row(2, "W2", "SAI", "94-2-2/4")), 100L)

        assertEquals(0, out.added)
        assertEquals(0, out.updated)
        assertEquals(2, out.unchanged)
        assertEquals(0, out.keptButNotInFile)
        // And the survey work is exactly as it was.
        assertEquals("9848012345", out.booth.voters[0].phone)
        assertTrue(out.booth.voters[0].visited)
    }

    @Test
    fun `the sheet wins on the four fields it supplies and nothing else`() {
        val booth = existing(voter(1, "W1", "OLD NAME", "10-1-1", phone = "9848012345", visited = true))
        val out = BoothImporter.merge(booth, meta(), listOf(row(1, "W1", "NEW NAME", "10-9-9")), 100L)

        assertEquals(1, out.updated)
        val v = out.booth.voters.single()
        assertEquals("NEW NAME", v.name)
        assertEquals("10-9-9", v.house)
        assertEquals("W1", v.voterId)
        // Untouched: the surveyor's own work.
        assertEquals("9848012345", v.phone)
        assertTrue(v.visited)
    }

    // --- the finalized list renumbers everybody ------------------------------

    @Test
    fun `renumbering everybody still matches by voter id`() {
        // This is the case the whole design turns on. The finalized list comes back with
        // different serials. Matching on serial would attach person 1's phone number to
        // whichever person now holds serial 1.
        val booth = existing(
            voter(1, "TST000010", "HEMA", "10-1-1", phone = "9848012345"),
            voter(2, "TST000003", "SAI", "10-1-2", phone = "9876543210"),
        )
        val out = BoothImporter.merge(
            booth, meta(),
            listOf(
                row(500, "TST000010", "HEMA", "10-1-1"),
                row(501, "TST000003", "SAI", "10-1-2"),
            ),
            100L,
        )
        assertEquals(0, out.added)
        assertEquals("HEMA got the right number", "9848012345", out.booth.voters.first { it.name == "HEMA" }.phone)
        assertEquals("SAI got the right number", "9876543210", out.booth.voters.first { it.name == "SAI" }.phone)
        assertEquals(500, out.booth.voters.first { it.name == "HEMA" }.serial)
    }

    @Test
    fun `a renamed person keeps their survey work`() {
        val booth = existing(voter(1, "W1", "OLD NAME", "10-1-1", phone = "9848012345"))
        val out = BoothImporter.merge(booth, meta(), listOf(row(1, "W1", "CORRECTED SPELLING", "10-1-1")), 100L)
        assertEquals("CORRECTED SPELLING", out.booth.voters.single().name)
        assertEquals("9848012345", out.booth.voters.single().phone)
    }

    // --- partial sheets -----------------------------------------------------

    @Test
    fun `a blank cell does not erase a value already held`() {
        // A partial or filtered export would otherwise quietly delete names and door numbers
        // that were read off the roll.
        val booth = existing(voter(1, "W1", "HEMA", "96-3-3/5"))
        val blank = XlsxReader.Row(1, "W1", "", "")
        val out = BoothImporter.merge(booth, meta(), listOf(blank), 100L)

        val v = out.booth.voters.single()
        assertEquals("HEMA", v.name)
        assertEquals("96-3-3/5", v.house)
        assertTrue("the import says it kept something", out.blanksKept >= 1)
    }

    @Test
    fun `someone in the app but not in the sheet is kept and marked`() {
        // Kept because deleting is not recoverable here. Marked because if the roll struck
        // them off as dead or moved away, the booklet would otherwise still send you to their
        // door.
        val booth = existing(
            voter(1, "W1", "HEMA", "10-1-1"),
            voter(2, "W2", "GONE FROM ROLL", "10-1-2"),
        )
        val out = BoothImporter.merge(booth, meta(), listOf(row(1, "W1", "HEMA", "10-1-1")), 100L)

        assertEquals(1, out.keptButNotInFile)
        assertEquals(2, out.booth.voters.size)
        assertTrue(out.booth.voters.first { it.serial == 2 }.notInLatestFile)
        assertTrue("the one in the file is not marked", !out.booth.voters.first { it.serial == 1 }.notInLatestFile)
    }

    // --- duplicates ---------------------------------------------------------

    @Test
    fun `the same voter id twice in a sheet does not collapse two people into one`() {
        // This happens twice in a real booth's roll. Without claiming one record at most once,
        // both rows fold onto the same voter and one person disappears.
        val booth = existing(voter(1, "W1", "FIRST", "10-1-1"), voter(2, "W1", "SECOND", "10-1-2"))
        val out = BoothImporter.merge(booth, meta(), listOf(row(1, "W1", "FIRST", "10-1-1"), row(2, "W1", "SECOND", "10-1-2")), 100L)

        assertEquals(2, out.booth.voters.size)
        assertEquals("nobody lost", 2, out.booth.voters.map { it.name }.toSet().size)
    }

    @Test
    fun `a new person in the sheet is added rather than matched to somebody`() {
        val booth = existing(voter(1, "W1", "HEMA", "10-1-1"))
        val out = BoothImporter.merge(booth, meta(), listOf(row(1, "W1", "HEMA", "10-1-1"), row(2, "W2", "NEW ARRIVAL", "10-1-2")), 100L)

        assertEquals(1, out.added)
        assertEquals(2, out.booth.voters.size)
        assertTrue(out.booth.voters.any { it.name == "NEW ARRIVAL" })
    }

    @Test
    fun `a row with a blank voter id falls back to the serial`() {
        val booth = existing(voter(1, "W1", "HEMA", "10-1-1", phone = "9848012345"))
        val out = BoothImporter.merge(booth, meta(), listOf(XlsxReader.Row(1, "", "HEMA", "10-1-9")), 100L)
        assertEquals(0, out.added)
        assertEquals(1, out.booth.voters.size)
        assertEquals("9848012345", out.booth.voters.single().phone)
        assertEquals("10-1-9", out.booth.voters.single().house)
    }

    // --- creating -----------------------------------------------------------

    @Test
    fun `a new booth is named after its number and keeps its details`() {
        val out = BoothImporter.create(BoothImporter.Meta("109", "Palakollu", "7", ""), listOf(row(1)), 100L)
        assertEquals("Booth 109", out.booth.label)
        assertEquals("Palakollu", out.booth.mandal)
        assertEquals("7", out.booth.townWard)
        assertEquals(1, out.added)
    }

    @Test
    fun `an empty booth label falls back rather than printing nothing`() {
        val out = BoothImporter.create(BoothImporter.Meta("109", "", "", "   "), listOf(row(1)), 100L)
        assertEquals("Booth 109", out.booth.label)
    }

    @Test
    fun `a merged booth keeps its label when the import does not give one`() {
        val booth = existing(voter(1))
        val out = BoothImporter.merge(booth, BoothImporter.Meta("108", "", "", ""), listOf(row(1)), 100L)
        assertEquals(booth.label, out.booth.label)
        assertEquals("Palakollu", out.booth.mandal)
    }

    @Test
    fun `imported records start unvisited with nothing on them`() {
        // Nothing may be inherited from the sheet except the four columns. A file that carried
        // a "visited" column would otherwise be able to mark people visited on your behalf.
        val out = BoothImporter.create(meta(), listOf(row(1, "W1", "HEMA", "10-1-1")), 100L)
        val v = out.booth.voters.single()
        assertTrue(!v.visited)
        assertTrue(!v.skipped)
        assertEquals("", v.phone)
        assertEquals(null, v.familyId)
        assertTrue(!v.notInLatestFile)
    }
}
