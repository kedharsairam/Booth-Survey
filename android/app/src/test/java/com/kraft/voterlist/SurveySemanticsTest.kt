package com.kraft.voterlist

import com.kraft.voterlist.data.Booth
import com.kraft.voterlist.data.Family
import com.kraft.voterlist.data.Lists
import com.kraft.voterlist.data.Survey
import com.kraft.voterlist.data.PrintOrder
import com.kraft.voterlist.data.Voter
import com.kraft.voterlist.ui.UiState
import com.kraft.voterlist.ui.Undo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "done" and "still to visit" mean, pinned down.
 *
 * These four decisions were all changed at once and none of them was under test, which is
 * the worst combination: each one is a few characters long, each one silently changes what
 * the app tells a surveyor about the rest of their day, and none of them fails loudly.
 *
 * The one that mattered most was [Booth.forPendingOnly]. The export sheet has a single
 * checkbox feeding two files, and the two read the question separately -- the spreadsheet
 * dropped anyone visited *or* skipped, the booklet kept any household with one person left
 * in it. Ticking one box produced two files containing different people. Both now ask
 * [Voter.visited] and nothing else, and the tests below exist so they cannot quietly stop
 * agreeing.
 */
class SurveySemanticsTest {

    private fun voter(serial: Int, visited: Boolean = false, skipped: Boolean = false, familyId: String? = null) =
        Voter(
            id = "v$serial",
            serial = serial,
            name = "Person $serial",
            visited = visited,
            skipped = skipped,
            familyId = familyId,
        )

    /** Four houses' worth: done, skipped, to do, to do. */
    private fun boothWithFourStates(): Booth = Booth(
        id = "booth-98",
        boothNo = "98",
        voters = listOf(
            voter(1, visited = true),
            voter(2, skipped = true),
            voter(3),
            voter(4),
        ),
    )

    // --- the Home headline -------------------------------------------------

    @Test
    fun `skipped is not counted as done`() {
        val s = UiState(booth = boothWithFourStates())

        assertEquals(
            "Only the knocked-at one is done. Counting the skipped one here is what let the " +
                "headline read All visited while a third of the ward was unvisited.",
            1,
            s.doneCount,
        )
    }

    @Test
    fun `skipped is reported on its own`() {
        val s = UiState(booth = boothWithFourStates())

        assertEquals(1, s.skippedCount)
    }

    @Test
    fun `remaining is everyone not knocked at, skipped included`() {
        val s = UiState(booth = boothWithFourStates())

        assertEquals(
            "A skipped house is one nobody has been to. Counting it as finished is the " +
                "whole bug this replaced.",
            3,
            s.remainingCount,
        )
    }

    @Test
    fun `remaining agrees with done and total`() {
        val s = UiState(booth = boothWithFourStates())

        assertEquals(s.totalCount, s.doneCount + s.remainingCount)
    }

    @Test
    fun `an untouched booth reports nothing done and everything left`() {
        val s = UiState(booth = Booth(boothNo = "108", voters = (1..1216).map { voter(it) }))

        assertEquals(0, s.doneCount)
        assertEquals(1216, s.remainingCount)
    }

    @Test
    fun `a fully worked booth is the only one that reads as all visited`() {
        val all = (1..10).map { voter(it, visited = true) }
        val s = UiState(booth = Booth(boothNo = "98", voters = all))

        assertEquals(0, s.remainingCount)

        // One house skipped is enough to make the headline lie again, which is the whole
        // point: this is the state the old code called finished.
        val oneSkipped = all.dropLast(1) + voter(10, skipped = true)
        val s2 = UiState(booth = Booth(boothNo = "98", voters = oneSkipped))

        assertEquals("One skipped house must keep the headline off zero.", 1, s2.remainingCount)
    }

    // --- what both exports agree on ----------------------------------------

    @Test
    fun `pending only drops the visited and keeps the skipped`() {
        val pending = boothWithFourStates().forPendingOnly()

        assertEquals(
            listOf("v2", "v3", "v4"),
            pending.voters.map { it.id },
        )
    }

    @Test
    fun `pending only is a no-op when nobody has been knocked at`() {
        val booth = Booth(boothNo = "98", voters = (1..5).map { voter(it) })

        assertEquals(5, booth.forPendingOnly().voters.size)
    }

    @Test
    fun `the booklet keeps a household with anyone left in it`() {
        val booth = Booth(
            boothNo = "98",
            voters = listOf(
                voter(1, visited = true, familyId = "f1"),
                voter(2, familyId = "f1"),
                voter(3, visited = true, familyId = "f2"),
                voter(4, visited = true, familyId = "f2"),
            ),
            families = listOf(Family(id = "f1"), Family(id = "f2")),
        )
        val blocks = PrintOrder.blocks(Survey(booth, Lists()))

        val f1 = blocks.first { it.familyId == "f1" }
        val f2 = blocks.first { it.familyId == "f2" }

        assertTrue("One of four done is not a finished house.", !f1.isFinished())
        assertTrue("All of a house done is finished.", f2.isFinished())
    }

    @Test
    fun `a skipped member still keeps the household in the booklet`() {
        // The cross-check that ties the two exporters together. The spreadsheet keeps this
        // person (they were not knocked at) and the booklet must therefore keep their house
        // too, or the two files describe different streets.
        val booth = Booth(
            boothNo = "98",
            voters = listOf(
                voter(1, visited = true, familyId = "f1"),
                voter(2, skipped = true, familyId = "f1"),
            ),
            families = listOf(Family(id = "f1")),
        )

        val block = PrintOrder.blocks(Survey(booth, Lists()))
            .first { it.familyId == "f1" }

        assertTrue(
            "A skipped person is still in the spreadsheet's pending list, so their " +
                "household must print in the booklet too.",
            !block.isFinished(),
        )
        assertTrue(
            "…and the block is kept because that person is in it.",
            block.members.any { !it.visited },
        )
    }

    // --- undo ---------------------------------------------------------------

    @Test
    fun `undo puts back the booth it was taken from`() {
        val before = boothWithFourStates()
        val after = before.forPendingOnly()
        val u = Undo("the marks on 4 you changed", before)

        assertEquals(before, u.targetFor(after))
    }

    @Test
    fun `undo refuses across booths`() {
        val u = Undo("the marks on 4 you changed", boothWithFourStates())
        val otherBooth = Booth(id = "booth-108", boothNo = "108")

        assertNull(
            "Restoring would put an old roll over a newer one, with nothing to show for it.",
            u.targetFor(otherBooth),
        )
    }

    @Test
    fun `undo is available again after the action that follows it`() {
        // Every save without an undo label clears the offer, so a stale Undo can never be
        // pressed against an action it does not describe.
        val before = boothWithFourStates()

        assertEquals(before, Undo("a", before).targetFor(before))
    }
}