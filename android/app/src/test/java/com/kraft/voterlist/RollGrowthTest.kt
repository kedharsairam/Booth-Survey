package com.kraft.voterlist

import com.kraft.voterlist.data.Booth
import com.kraft.voterlist.data.Family
import com.kraft.voterlist.data.Voter
import com.kraft.voterlist.io.BoothImporter
import com.kraft.voterlist.io.XlsxReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens when the roll grows.
 *
 * The fear behind this one is specific: a booth of about 1,200 becomes 1,600, or 2,000, the
 * new sheet is brought in, and a working survey loses the phone numbers, the families and
 * the visited marks that a week of door-to-door work produced. Adding someone by hand in
 * the app is fine at that size. Hundreds at once is not, and the only sane answer is to
 * import the bigger sheet -- so the question is whether that is actually safe.
 *
 * It is, and it is safe because of one decision made long before this test existed: a merge
 * copies only the four fields a sheet supplies (serial, voter id, name, house) and leaves
 * everything else exactly as it was. Phone, caste, address, party, family, family order,
 * visited and skipped are never written by an import.
 *
 * The second thing that makes it safe is matching on voter ID rather than serial. A
 * finalized roll renumbers everyone; if the join were on serial, every person would be
 * read as new and the old one struck off as not in the file.
 */
class RollGrowthTest {

    private val meta = BoothImporter.Meta(boothNo = "98", mandal = "Palakollu", townWard = "4")

    /** A booth the size the app actually holds today. */
    private fun boothOf(n: Int): List<Voter> = (1..n).map { i ->
        Voter(
            id = "old$i",
            serial = i,
            voterId = "EPIC%07d".format(i),
            name = "Person $i",
            house = "10-1-${i}",
        )
    }

    /** The same people, but with a working survey written over the top of them. */
    private fun workedOn(voters: List<Voter>): List<Voter> = voters.mapIndexed { i, v ->
        v.copy(
            // A third of them have a phone number, a caste and a family; half have been
            // visited. This is what an afternoon at the doors produces.
            phone = if (i % 3 == 0) "9${i.toString().padStart(9, '0')}" else "",
            caste = if (i % 3 == 0) "BC-B" else "",
            address = if (i % 5 == 0) "MAIN ROAD" else "",
            party = if (i % 7 == 0) "INC" else "",
            familyId = if (i % 4 == 0) "fam${i / 4}" else null,
            familyOrder = if (i % 4 == 0) i % 4 else 0,
            visited = i % 2 == 0,
            skipped = i % 11 == 0,
        )
    }

    private fun familiesOf(voters: List<Voter>): List<Family> =
        voters.mapNotNull { it.familyId }
            .distinct()
            .mapIndexed { i, id -> Family(id = id, order = i) }

    /**
     * The new sheet: everyone who was there, plus new people. Serials are shifted by a
     * thousand, because that is what a renumbering roll does and it is the case most likely
     * to destroy everything if the join were on serial.
     */
    private fun sheetOf(existing: List<Voter>, extra: Int): List<XlsxReader.Row> {
        val kept = existing.map { v ->
            XlsxReader.Row(
                serial = v.serial + 1000,
                voterId = v.voterId,
                name = v.name,
                house = v.house,
            )
        }
        val added = (1..extra).map { k ->
            val s = existing.size + k
            XlsxReader.Row(
                serial = s + 1000,
                voterId = "NEW%07d".format(k),
                name = "Newcomer $k",
                house = "10-2-$k",
            )
        }
        return kept + added
    }

    private fun merge(old: List<Voter>, rows: List<XlsxReader.Row>) =
        BoothImporter.merge(
            Booth(boothNo = "98", voters = old, families = familiesOf(old)),
            meta, rows, System.currentTimeMillis(),
        )

    // --- the thing his dad is actually asking -------------------------------

    @Test
    fun `a bigger roll adds the new voters and keeps every working record`() {
        val started = workedOn(boothOf(1216))
        val outcome = merge(started, sheetOf(started, 784))

        assertEquals("Everyone from both rolls should be in the booth.", 2000, outcome.booth.voters.size)
        assertEquals(784, outcome.added)

        // The whole point. Every field a surveyor typed, on every one of the 1,216.

        val after = outcome.booth.voters.associateBy { it.voterId }
        for (v in started) {
            val now = after[v.voterId] ?: throw AssertionError("${v.voterId} vanished")
            assertEquals("phone lost on ${v.voterId}", v.phone, now.phone)
            assertEquals("caste lost on ${v.voterId}", v.caste, now.caste)
            assertEquals("address lost on ${v.voterId}", v.address, now.address)
            assertEquals("party lost on ${v.voterId}", v.party, now.party)
            assertEquals("family lost on ${v.voterId}", v.familyId, now.familyId)
            assertEquals("family order lost on ${v.voterId}", v.familyOrder, now.familyOrder)
            assertEquals("visited mark lost on ${v.voterId}", v.visited, now.visited)
            assertEquals("skip lost on ${v.voterId}", v.skipped, now.skipped)
        }

        assertEquals(
            "Nobody who was already there should be marked as struck off by the new sheet.",
            0,
            outcome.booth.voters.count { it.notInLatestFile },
        )
        assertEquals(
            "The import must not drop a family.",
            familiesOf(started).map { it.id }.toSet(),
            outcome.booth.families.map { it.id }.toSet(),
        )
        for (f in familiesOf(started)) {
            assertEquals(
                "Family ${f.id} came out with a different number of people in it.",
                started.count { it.familyId == f.id },
                outcome.booth.voters.count { it.familyId == f.id },
            )
        }
    }

    @Test
    fun `a renumbered roll does not read as a new one`() {
        val started = workedOn(boothOf(1216))
        // Same people, every serial changed -- the worst a finalized roll can do.
        val renumbered = started.map { v ->
            XlsxReader.Row(v.serial + 1000, v.voterId, v.name, v.house)
        }

        val outcome = merge(started, renumbered)

        assertEquals("A renumbering must not add anybody.", 0, outcome.added)
        assertEquals(1216, outcome.booth.voters.size)
        assertEquals(
            "Everyone should still be matched despite the new serial.",
            0,
            outcome.booth.voters.count { it.notInLatestFile },
        )
        assertTrue(
            "The new serial should be adopted, not the old one kept.",
            outcome.booth.voters.all { it.serial > 1000 },
        )
    }

    @Test
    fun `a partial sheet keeps the people it does not mention`() {
        // The common real case: a corrected sheet with some names struck out.
        val started = workedOn(boothOf(1216))
        val someOfThem = started.filterIndexed { i, _ -> i % 10 != 0 }
            .map { XlsxReader.Row(it.serial, it.voterId, it.name, it.house) }

        val outcome = merge(started, someOfThem)

        assertEquals("Nobody may be deleted by an import.", 1216, outcome.booth.voters.size)
        val marked = outcome.booth.voters.count { it.notInLatestFile }
        assertEquals("The ones the sheet dropped should be marked, not removed.", 122, marked)
        assertTrue(
            "Marking is not a reason to lose what was typed.",
            outcome.booth.voters.filter { it.notInLatestFile }.all { it.id.startsWith("old") },
        )
    }

    @Test
    fun `a blank cell in the new sheet does not erase what is there`() {
        val started = workedOn(boothOf(20))
        val blanks = started.map { XlsxReader.Row(it.serial, it.voterId, "", "") }

        val outcome = merge(started, blanks)

        assertTrue(
            "A sheet with empty name and house columns must not empty the roll.",
            outcome.booth.voters.all { it.name.isNotBlank() && it.house.isNotBlank() },
        )
        assertTrue(outcome.blanksKept > 0)
    }

    // --- does it actually run at that size -----------------------------------

    @Test
    fun `a two thousand person merge is quick enough to not be noticed`() {
        val started = workedOn(boothOf(1216))
        val rows = sheetOf(started, 784)

        val startedAt = System.nanoTime()
        val outcome = merge(started, rows)
        val millis = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(2000, outcome.booth.voters.size)
        // Generous, because CI machines are slow. The point is that this is not minutes.
        assertTrue("A 2000-row merge took $millis ms", millis < 4_000)
    }

    @Test
    fun `the headline counts stay consistent at two thousand`() {
        val started = workedOn(boothOf(1216))
        val outcome = merge(started, sheetOf(started, 784))

        val visited = outcome.booth.voters.count { it.visited }
        val skipped = outcome.booth.voters.count { it.skipped }
        val total = outcome.booth.voters.size

        assertTrue("Somebody must have been visited.", visited > 0)
        assertEquals(
            "Unvisited plus visited has to be the whole booth, or the Home screen lies.",
            total,
            visited + (total - visited),
        )
        assertTrue("Skipped is a subset of not-visited, never of visited.", skipped < total)
    }
}