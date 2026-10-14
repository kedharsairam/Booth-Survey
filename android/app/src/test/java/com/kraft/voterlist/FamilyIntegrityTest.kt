package com.kraft.voterlist

import com.kraft.voterlist.data.Booth
import com.kraft.voterlist.data.Family
import com.kraft.voterlist.data.Lists
import com.kraft.voterlist.data.PrintOrder
import com.kraft.voterlist.data.Survey
import com.kraft.voterlist.data.Voter
import com.kraft.voterlist.export.PdfExporter
import com.kraft.voterlist.io.BoothImporter
import com.kraft.voterlist.io.XlsxReader
import com.kraft.voterlist.ui.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Households: who is in them, who has been taken out, and what the numbers say afterwards.
 *
 * A household is the unit the booklet prints as one visit, so the things worth proving here
 * are that nobody can be in two of them, that nobody who has left one is still shown in it,
 * and that the "3 of 4 done" on the card and the "1,216 people" on Home keep counting the
 * people who are actually there.
 *
 * Most of this is derived rather than stored, which is why it is safe: a family holds no
 * list of members and no count of them, so there is nothing for the two copies to drift
 * apart over. These tests are what make that a proved thing rather than an assumption --
 * and they are also how two ways of building a household that do not do what their own
 * comments say were found.
 */
class FamilyIntegrityTest {

    private var n = 0

    private fun voter(
        serial: Int = ++n,
        family: String? = null,
        order: Int = 0,
        visited: Boolean = false,
        skipped: Boolean = false,
        house: String = "10-1-1",
        name: String = "PERSON $serial",
    ) = Voter(
        id = "id$serial", serial = serial, name = name, house = house,
        familyId = family, familyOrder = order, visited = visited, skipped = skipped,
    )

    private fun booth(voters: List<Voter>, families: List<Family> = emptyList()) =
        Booth(id = "b1", boothNo = "108", voters = voters, families = families)

    // --- nobody in two households --------------------------------------------

    /**
     * A person belongs to exactly one household, and saying so costs nothing.
     *
     * Families hold no members list -- membership is the `familyId` on the voter -- so there
     * is no second copy to disagree with the first. This asserts the property from both
     * sides: what the app shows and what the print order actually walks.
     */
    @Test
    fun `a person is in one household and is printed once`() {
        val b = booth(
            listOf(
                voter(1, family = "f1", order = 0),
                voter(2, family = "f1", order = 1),
                voter(3, family = "f2", order = 0),
            ),
            listOf(Family("f1", 0), Family("f2", 1)),
        )

        val printed = PrintOrder.blocks(Survey(b, Lists()))
            .flatMap { it.members }.map { it.id }.sorted()

        assertEquals("each person printed exactly once", listOf("id1", "id2", "id3"), printed)
        assertEquals(2, b.familiesWithMembers.size)
        assertEquals(
            "everybody is accounted for, families or not",
            b.voters.map { it.id }.toSet(),
            (b.familiesWithMembers.flatMap { it.members } + b.unassigned).map { it.id }.toSet(),
        )
    }

    /**
     * Moving somebody from one household to another leaves them in one, not none.
     *
     * The bulk action is allowed to move a person rather than refuse them, because a
     * household built slightly wrong and corrected a week later is the commonest thing that
     * happens. The household they left stands with whoever is left in it.
     */
    @Test
    fun `moving somebody between households leaves them in exactly one`() {
        val b = booth(
            listOf(
                voter(1, family = "f1", order = 0),
                voter(2, family = "f1", order = 1),
                voter(3, family = "f2", order = 0),
            ),
            listOf(Family("f1", 0), Family("f2", 1)),
        )

        val after = b.withFamily(setOf("id1"), "f2")
        assertEquals("f2", after.voters.first { it.id == "id1" }.familyId)
        assertEquals(1, after.familiesWithMembers.first { it.family.id == "f1" }.size)
        assertEquals(2, after.familiesWithMembers.first { it.family.id == "f2" }.size)
        assertEquals(
            "and still printed once each",
            3,
            PrintOrder.blocks(Survey(after, Lists())).flatMap { it.members }.size,
        )
    }

    // --- who has been taken out ----------------------------------------------

    /**
     * Deleting somebody takes them out of the household's count as well as out of the roll.
     *
     * Both numbers on the card are worked out from the members that are there, so a deleted
     * person cannot leave a hole behind in "M of N done" -- which would be a household that
     * says it is two-thirds done when there is nobody left to finish.
     */
    @Test
    fun `deleting a member takes them out of the household's count`() {
        val b = booth(
            listOf(
                voter(1, family = "f1", order = 0, visited = true),
                voter(2, family = "f1", order = 1, visited = true),
                voter(3, family = "f1", order = 2),
            ),
            listOf(Family("f1", 0)),
        )
        val fwm = b.familiesWithMembers.single()
        assertEquals("3 people, 2 done", "3/2", "${fwm.size}/${fwm.done}")

        val after = b.withoutVoters(setOf("id2")).pruned()
        val now = after.familiesWithMembers.single()
        assertEquals("the household is smaller", 2, now.size)
        assertEquals("and so is the number done", 1, now.done)
        assertTrue("so it is not complete", !now.complete)
        assertFalse("and nobody deleted is still in it", now.members.any { it.id == "id2" })
    }

    /**
     * Taking the last person out of a household removes the household, not just the person.
     *
     * An empty household is printed nowhere and listed nowhere, so leaving one behind is a
     * thing that accumulates invisibly over a week of surveying.
     */
    @Test
    fun `a household emptied of its last member is gone, not left standing empty`() {
        val b = booth(
            listOf(voter(1, family = "f1"), voter(2, family = "f2")),
            listOf(Family("f1", 0), Family("f2", 1)),
        )

        val after = b.withoutVoters(setOf("id1")).pruned()
        assertEquals(listOf("f2"), after.families.map { it.id })
        assertEquals("the person is gone, and nobody is left on their own", 0, after.unassigned.size)

        // And the emptied household prints as nothing at all rather than as a blank one.
        val blocks = PrintOrder.blocks(Survey(after, Lists()))
        assertEquals("one household, one block", 1, blocks.size)
        assertEquals("the survivor, and nobody else", listOf("id2"), blocks.flatMap { it.members }.map { it.id })
        assertEquals(1, after.voters.size)
    }

    // --- how a household is built --------------------------------------------

    /**
     * A household built from a sheet whose serials are out of order comes out in roll
     * order, whoever typed the ids in and whatever order the spreadsheet listed them.
     *
     * [Booth.withFamily] used to sort its movers into a set and then never read that set:
     * the positions were handed out by walking the booth's voter list, which is in the
     * order the sheet arrived. A part-wise roll runs 1, 101, 201, so list order and roll
     * order are genuinely different things. The first person in that sequence names the
     * household and prints first, so three people taken in together at one door came out
     * under a name belonging to whoever happened to sit earliest in the spreadsheet.
     *
     * The test that used to cover this passed only because its own fixture happened to be
     * in serial order already, which is what made it look covered.
     */
    @Test
    fun `a household built from a jumbled sheet comes out in roll order`() {
        // The voter list in the order the sheet listed them: 7, 3, 5, 1. A part-wise roll
        // arrives like this, so list order and roll order are genuinely different things.
        val b = booth(listOf(voter(7), voter(3), voter(5), voter(1)))

        val after = b.withFamily(setOf("id7", "id3", "id5", "id1"), "f1")

        assertEquals(
            "the household is arranged in roll order whatever order the sheet listed",
            listOf(1, 3, 5, 7),
            after.voters.filter { it.familyId == "f1" }
                .sortedBy { it.familyOrder }.map { it.serial },
        )
    }


        @Test
    fun `a household never reports more done than it holds`() {
        var b = booth(
            listOf(
                voter(1, family = "f1", visited = true),
                voter(2, family = "f1"),
                voter(3, family = "f1"),
                voter(4, family = "f2", skipped = true),
            ),
            listOf(Family("f1", 0), Family("f2", 1)),
        )
        val states = listOf(
            b,
            b.withoutVoters(setOf("id2")).pruned(),
            b.withoutVoters(setOf("id3")).pruned(),
            b.withoutVoters(setOf("id1")).pruned(),
            b.withoutVoters(setOf("id1")).pruned().withVisitState(setOf("id4"), visited = true, skipped = false),
        )

        for (state in states) {
            for (fwm in state.familiesWithMembers) {
                assertTrue(
                    "done (${fwm.done}) must never exceed size (${fwm.size})",
                    fwm.done <= fwm.size,
                )
                assertTrue("a household with nobody in it is not complete", !fwm.complete || fwm.size > 0)
            }
            val s = UiState(booth = state)
            assertEquals(
                "Home's counts have to add up to the roll",
                s.totalCount,
                s.doneCount + s.remainingCount,
            )
            assertTrue("nothing done can exceed the roll", s.doneCount <= s.totalCount)
        }
    }

    /**
     * Home's headline follows the roll as it actually is, through the three things that
     * change it.
     *
     * Deleting somebody, a sheet arriving that no longer mentions them, and the last person
     * walking out of a household. All three leave the count of people correct, which is the
     * number on the front of the app and the one a surveyor reads first each morning.
     */
    @Test
    fun `the headline still counts the roll after a delete, a re-import and an emptied household`() {
        val started = BoothImporter.create(
            BoothImporter.Meta("108"),
            listOf(
                XlsxReader.Row(1, "W1", "HEMA", "10-1-1"),
                XlsxReader.Row(2, "W2", "SAI", "10-1-1"),
                XlsxReader.Row(3, "W3", "RAJU", "10-1-2"),
            ),
            1L,
        ).booth.copy(families = listOf(Family("f1", 0), Family("f2", 1)))

        val marked = started
            .withFamily(setOf(started.voters[0].id), "f1")
            .withFamily(setOf(started.voters[2].id), "f2")
            .withVisitState(setOf(started.voters[0].id), visited = true, skipped = false)

        assertEquals(3, UiState(booth = marked).totalCount)
        assertEquals(2, UiState(booth = marked).remainingCount)

        // A corrected sheet that no longer lists one of them: kept and marked, never lost.
        val reimported = BoothImporter.merge(
            marked, BoothImporter.Meta("108"),
            listOf(XlsxReader.Row(1, "W1", "HEMA", "10-1-1"), XlsxReader.Row(3, "W3", "RAJU", "10-1-2")),
            2L,
        ).booth
        assertEquals("kept, not deleted", 3, UiState(booth = reimported).totalCount)
        assertEquals("and marked so he can see them", 1, reimported.notInFile)

        // Deleted at the door.
        val deleted = reimported.withoutVoters(setOf(marked.voters[1].id)).pruned()
        assertEquals(2, UiState(booth = deleted).totalCount)
        assertEquals("the household kept standing with the one left", 2, deleted.families.size)

        // And the last person out of the second household.
        val emptied = deleted.withoutVoters(setOf(marked.voters[2].id)).pruned()
        assertEquals(1, UiState(booth = emptied).totalCount)
        assertEquals("one household left", 1, emptied.families.size)
        assertEquals("and one person alone", 0, emptied.unassigned.size)
        assertEquals(
            "what prints is what the roll holds",
            1,
            PrintOrder.blocks(Survey(emptied, Lists())).flatMap { it.members }.size,
        )
    }

    /**
     * The printed booklet holds every person in the roll, once.
     *
     * The most basic promise the artefact makes, asserted over the whole lifecycle rather
     * than on a fresh booth: after marking, grouping, reordering and deleting, the pages
     * together must account for exactly the people who are there. A family arrangement
     * that quietly drops somebody is indistinguishable, on paper, from a house that was
     * never visited.
     */
    @Test
    fun `the booklet accounts for exactly the people on the roll`() {
        val b = booth(
            listOf(
                voter(1, family = "f1", order = 0, visited = true),
                voter(2, family = "f1", order = 1),
                voter(3, family = "f2", order = 0, skipped = true),
                voter(4, family = "f2", order = 1),
                voter(5, family = "f2", order = 2),
                voter(6, house = "10-2-3"),
            ),
            listOf(Family("f1", 0), Family("f2", 1)),
        )
            .movingFamilyTo("f2", 0)

        val pages = PdfExporter.layout(PrintOrder.blocks(Survey(b, Lists())), false).first
        val printed = pages.flatMap { page ->
            page.flatMap { row -> (row.from until row.to).map { row.block.members[it].id } }
        }

        assertEquals("everybody on the roll", b.voters.map { it.id }.toSet(), printed.toSet())
        assertEquals("and nobody twice", b.voters.size, printed.size)
        assertEquals(
            "in the order the surveyor arranged",
            listOf("id3", "id4", "id5", "id1", "id2", "id6"),
            printed,
        )
    }

    /**
     * **Deleting somebody from the middle of a household leaves a gap in its printed
     * positions.** This is a defect, and it is small, but it is in the file the electoral
     * office receives.
     *
     * The booklet numbers a member by their place in the block -- 1, 2, 3, whatever -- so
     * the booklet is always right. The spreadsheet's "In family order" column writes the
     * stored `familyOrder` plus one, and `Booth.withoutVoters` closes no gap: take the
     * second of four out and the survivors keep 0 and 2, so the column reads 1 and 3 for a
     * two-person household with no member 2 at all.
     *
     * The column exists so the office can check the arrangement against the surveyor's
     * booklet, so a gap there is exactly the kind of small wrong that costs somebody a
     * phone call. `SurveyViewModel.removeMember` does close the gap; the delete path does
     * not.
     */
    @Test
    fun `deleting from the middle of a household closes the gap it leaves`() {
        val b = booth(
            listOf(
                voter(1, family = "f1", order = 0),
                voter(2, family = "f1", order = 1),
                voter(3, family = "f1", order = 2),
            ),
            listOf(Family("f1", 0)),
        )

        val after = b.withoutVoters(setOf("id2")).pruned()
        assertEquals(
            "the survivors close up: no hole where the deleted one was",
            listOf(0, 1),
            after.familiesWithMembers.single().members.sortedBy { it.familyOrder }.map { it.familyOrder },
        )

        // What that becomes in the two files. The booklet counts the block, so it was always
        // right. The spreadsheet adds one to the stored position and used to read 1 and 3 for
        // a two-person household with no member 2 -- in the column whose whole purpose is
        // checking the app against the arrangement the app itself prints.
        val booklet = PrintOrder.blocks(Survey(after, Lists())).single()
        assertEquals(
            "the booklet numbers the block",
            listOf(1, 2),
            booklet.members.indices.map { it + 1 },
        )
        assertEquals(
            "and the spreadsheet's arrangement column agrees with it",
            listOf(1, 2),
            after.familiesWithMembers.single().members.sortedBy { it.familyOrder }.map { it.familyOrder + 1 },
        )
    }

    /**
     * A person whose household is missing from the file still prints.
     *
     * **This is a hazard, not a demonstrated path through the UI.** Membership is a
     * `familyId` on the voter and a separate list of households, so a file where the two
     * disagree -- an older backup written before households were stored, a hand-edited file,
     * a partially restored one -- leaves people pointing at a household that is not there.
     * `PrintOrder.blocks` walks the *households* and then the *unassigned*, so those people
     * are in neither, and they disappear from both exports while Home still counts them.
     *
     * That is the same failure `Store.stringOrNull` documents having already caused once,
     * when a JSON null read as the string "null" put 1,129 people into a household called
     * "null" that did not exist and every one of them vanished from the sheet while the app
     * still showed the right number. The parser was fixed; the assumption was not defended.
     *
     * The cheap guard is for [Booth.familiesWithMembers] or [PrintOrder.blocks] to treat an
     * unknown `familyId` as unassigned, or for `parseBooth` to drop household ids nothing
     * points at.
     */
    @Test
    fun `a person pointing at a household that is not there still prints`() {
        val b = booth(
            listOf(
                voter(1, family = "f1"),
                voter(2, family = "gone"),
                voter(3),
            ),
            listOf(Family("f1", 0)),
        )

        val printed = PrintOrder.blocks(Survey(b, Lists())).flatMap { it.members }.map { it.id }

        // A household id nothing points at is a corrupt save, not a reason for a person to
        // vanish. They come back as someone in no household, which is what they are.
        assertEquals(
            // id1 is in the household that exists. id2 (attached to a household that is
            // not in the file) and id3 (in no household at all) are both printed, in roll
            // order among themselves, because both are now simply people in no household.
            "the person attached to a household that does not exist still prints",
            listOf("id1", "id2", "id3"),
            printed,
        )
        assertEquals("and Home agrees with the sheet", 3, UiState(booth = b).totalCount)    }
}