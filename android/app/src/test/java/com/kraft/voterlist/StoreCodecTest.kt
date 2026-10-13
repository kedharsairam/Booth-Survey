package com.kraft.voterlist

import com.kraft.voterlist.data.Booth
import com.kraft.voterlist.data.Family
import com.kraft.voterlist.data.Lists
import com.kraft.voterlist.data.Voter
import com.kraft.voterlist.data.backupJsonOf
import com.kraft.voterlist.data.boothFromJsonOf
import com.kraft.voterlist.data.boothJsonOf
import com.kraft.voterlist.data.readBackupOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON codec, on the JVM, in milliseconds.
 *
 * These four functions used to be methods on Store, whose constructor takes an Android
 * Context. So the parser -- the one that once put 1,129 voters into a family called "null"
 * and made every one of them vanish from the export -- had no test at all, and could not
 * have one without a device. It is now a plain function and these are the tests that were
 * impossible before.
 *
 * The other thing these pin down is that a damaged file costs a row, not the app.
 */
class StoreCodecTest {

    private fun booth() = Booth(
        id = "b1",
        boothNo = "108",
        mandal = "M",
        townWard = "W",
        label = "Booth 98",
        voters = listOf(
            Voter(id = "v1", serial = 1, voterId = "ABC123", name = "One", house = "10-1", phone = "9876543210", visited = true),
            Voter(id = "v2", serial = 2, voterId = "", name = "Two", house = "10-2", caste = "BC-A", skipped = true),
            Voter(id = "v3", serial = 3, name = "Three", familyId = "f1", familyOrder = 1, notInLatestFile = true),
        ),
        families = listOf(Family("f1", 0), Family("f2", 1)),
        importedAt = 1_700_000_000_000L,
    )

    // --- round trip --------------------------------------------------------

    @Test
    fun `a booth survives being written and read back`() {
        val back = boothFromJsonOf(boothJsonOf(booth()))
        assertNotNull("the booth came back", back)
        val b = back!!
        assertEquals("108", b.boothNo)
        assertEquals("Booth 98", b.label)
        assertEquals(3, b.voters.size)
        assertEquals(1_700_000_000_000L, b.importedAt)
    }

    @Test
    fun `every hand-typed field survives the round trip`() {
        val b = boothFromJsonOf(boothJsonOf(booth()))!!
        val one = b.voters.first { it.id == "v1" }
        assertEquals("ABC123", one.voterId)
        assertEquals("9876543210", one.phone)
        assertTrue(one.visited)
        val two = b.voters.first { it.id == "v2" }
        assertEquals("BC-A", two.caste)
        assertTrue(two.skipped)
        val three = b.voters.first { it.id == "v3" }
        assertEquals("f1", three.familyId)
        assertEquals(1, three.familyOrder)
        assertTrue(three.notInLatestFile)
    }

    @Test
    fun `a voter with no family comes back with no family, and not one called null`() {
        // The bug this whole file exists for: JSONObject.optString returns the four-letter
        // string "null" for a JSON null, so a plain ifBlank read gave every unassigned
        // voter a familyId of "null" -- a family that does not exist, and a booklet that
        // printed none of them.
        val b = boothFromJsonOf(boothJsonOf(booth()))!!
        assertNull("a voter with no family must have none", b.voters.first { it.id == "v1" }.familyId)
        assertEquals(3, b.voters.size)
    }

    @Test
    fun `family order survives, not just the list`() {
        val b = boothFromJsonOf(boothJsonOf(booth()))!!
        assertEquals(listOf(0, 1), b.families.sortedBy { it.order }.map { it.order })
    }

    // --- damaged input ------------------------------------------------------

    @Test
    fun `an element of voters that is not an object is skipped, not fatal`() {
        // One bad row in the file holding the entire roll. getJSONObject would have thrown
        // JSONException out of loadBooth, out of the ViewModel's init, on every launch --
        // an app that cannot be opened at all, with nothing on screen and no way back in.
        val json = """
            {"id":"b1","boothNo":"1","voters":[
              {"id":"v1","serial":1,"name":"One"},
              "not an object",
              {"id":"v2","serial":2,"name":"Two"}
            ]}
        """.trimIndent()
        val b = boothFromJsonOf(json)
        assertNotNull("the file must still open", b)
        assertEquals(2, b!!.voters.size)
        assertEquals(listOf("One", "Two"), b.voters.map { it.name })
    }

    @Test
    fun `an element of families that is not an object is skipped too`() {
        val json = """
            {"id":"b1","boothNo":"1","voters":[],"families":[
              {"id":"f1","order":0}, 42, {"id":"f2","order":1}
            ]}
        """.trimIndent()
        val b = boothFromJsonOf(json)!!
        assertEquals(listOf("f1", "f2"), b.families.sortedBy { it.order }.map { it.id })
    }

    @Test
    fun `a voter with no id is given one rather than losing the row`() {
        val b = boothFromJsonOf("""{"id":"b1","voters":[{"serial":1,"name":"One"}]}""")!!
        assertEquals(1, b.voters.size)
        assertTrue("a minted id must not be blank", b.voters[0].id.isNotBlank())
    }

    @Test
    fun `text that is not JSON at all returns null instead of throwing`() {
        assertNull(boothFromJsonOf("this is not json"))
        assertNull(boothFromJsonOf(""))
    }

    @Test
    fun `a missing section reads as empty, not as an error`() {
        val b = boothFromJsonOf("""{"id":"b1"}""")!!
        assertEquals(0, b.voters.size)
        assertEquals(0, b.families.size)
    }

    // --- backups -----------------------------------------------------------

    @Test
    fun `a backup round trips the booth and the shared lists`() {
        val lists = Lists(castes = listOf("BC-A"), addresses = listOf("Main Road"), parties = listOf("IND"))
        val back = readBackupOf(backupJsonOf(booth(), lists))
        assertNotNull(back)
        assertEquals(3, back!!.booth.voters.size)
        assertEquals(listOf("BC-A"), back.lists.castes)
        assertEquals(listOf("Main Road"), back.lists.addresses)
        assertTrue("a backup records when it was taken", back.savedAt > 0)
    }

    @Test
    fun `a file that is not a backup is refused rather than half-restored`() {
        assertNull("a spreadsheet must not restore", readBackupOf("""{"kind":"something-else"}"""))
        assertNull(readBackupOf("""{"voters":[]}"""))
        assertNull(readBackupOf("not json"))
    }

    @Test
    fun `a backup with no booth inside is refused`() {
        // optString, not getString: getString on a missing key throws, and that exception
        // would have escaped the confirm-and-restore path with nothing on screen.
        assertNull(readBackupOf("""{"kind":"booth-survey-backup","version":3}"""))
    }

    // --- the reordering the app is judged on -------------------------------

    @Test
    fun `moving a family reindexes a clean run`() {
        val b = booth().movingFamily("f2", -1)
        assertEquals(listOf("f2", "f1"), b.families.sortedBy { it.order }.map { it.id })
        assertEquals(listOf(0, 1), b.families.sortedBy { it.order }.map { it.order })
    }

    @Test
    fun `moving a member keeps the household and its order intact`() {
        val b = booth().copy(
            voters = listOf(
                Voter(id = "a", serial = 1, familyId = "f1", familyOrder = 0),
                Voter(id = "b", serial = 2, familyId = "f1", familyOrder = 1),
                Voter(id = "c", serial = 3, familyId = "f1", familyOrder = 2),
            ),
        )
        val moved = b.movingMember("f1", "c", -1)
        assertEquals(listOf("a", "c", "b"), moved.voters.sortedBy { it.familyOrder }.map { it.id })
        assertEquals(listOf(0, 1, 2), moved.voters.sortedBy { it.familyOrder }.map { it.familyOrder })
    }

    @Test
    fun `a step past either end changes nothing`() {
        val b = booth()
        assertEquals(b.families, b.movingFamily("f1", -1).families)
        assertEquals(b.families, b.movingFamily("f2", 1).families)
    }

    @Test
    fun `a move never loses a card`() {
        var b = booth()
        repeat(6) { b = b.movingFamily(if (it % 2 == 0) "f1" else "f2", 1) }
        assertEquals(setOf("f1", "f2"), b.families.map { it.id }.toSet())
        assertFalse("no duplicate orders", b.families.map { it.order }.let { it.size != it.toSet().size })
    }
}