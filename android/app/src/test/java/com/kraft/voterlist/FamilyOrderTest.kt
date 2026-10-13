package com.kraft.voterlist.ui

import com.kraft.voterlist.data.Booth
import com.kraft.voterlist.data.Family
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Walking-order reindexing, and the bounds that decide when an arrow is live.
 *
 * Both are pure arithmetic on an ordered list, which is the whole reason the arrows on a
 * card can be trusted: `moveFamily` is the same call the drag makes, so a card dragged
 * three rows and a card tapped once have to land in the same place. Getting the ends
 * wrong is what silently reorders a booth on export, so the boundary cases are the point
 * of these tests rather than the middle of the range.
 *
 * **These call the production function.** That is the change. Every test here used to call
 * `move`, a helper in this file that was a line-for-line copy of the arithmetic inside
 * SurveyViewModel.moveFamily -- so all nine passed with the real thing deleted, and nothing
 * in the suite ever called `moveFamily` at all. The arithmetic now lives in
 * `Booth.movingFamily`, in the data layer, where both the arrows and the drag go through
 * it and where this file can reach it. Reintroducing the bug the old copy could not see
 * makes these fail.
 */
class FamilyOrderTest {

    private fun family(id: String, order: Int) = Family(id = id, order = order)

    private fun booth(vararg ids: String) = Booth(
        boothNo = "1",
        // The ids given are the intended walking order, so `order` is assigned to match.
        // They are *stored* reversed, because a booth imported from a spreadsheet arrives
        // with its families in whatever order the sheet had them and only `order` decides
        // the sequence -- reading position off the list is the bug this guards against.
        families = ids.mapIndexed { i, id -> family(id, i) }.reversed(),
    )

    /**
     * The walking order after one step of [by], read back off the moved booth.
     *
     * Runs the real code and then reads the result back out of it, so these tests cannot
     * pass unless the production function really produced that order.
     */
    private fun move(b: Booth, id: String, by: Int): List<String> =
        b.movingFamily(id, by).families.sortedBy { it.order }.map { it.id }

    @Test
    fun `one place down swaps with the next card`() {
        val b = booth("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), move(b, "a", 1))
    }

    @Test
    fun `one place up swaps with the previous card`() {
        val b = booth("a", "b", "c")
        // "c" sits at index 2, so one place up is index 1: it trades with "b", not with "a".
        // One arrow is one place -- the card moves as far as the finger says, no further.
        assertEquals(listOf("a", "c", "b"), move(b, "c", -1))
    }

    @Test
    fun `a step past the end changes nothing`() {
        val b = booth("a", "b", "c")
        assertEquals(listOf("a", "b", "c"), move(b, "c", 1))
    }

    @Test
    fun `a step past the start changes nothing`() {
        val b = booth("a", "b", "c")
        assertEquals(listOf("a", "b", "c"), move(b, "a", -1))
    }

    @Test
    fun `an unknown id is ignored rather than throwing`() {
        val b = booth("a", "b")
        assertEquals(listOf("a", "b"), move(b, "ghost", 1))
    }

    @Test
    fun `repeated taps walk a card to the end`() {
        var b = booth("a", "b", "c", "d")
        // What three taps on the down arrow do, one place each time.
        var ids = move(b, "a", 1).toMutableList()
        ids = move(b.copy(families = ids.map { family(it, ids.indexOf(it)) }), "a", 1).toMutableList()
        ids = move(b.copy(families = ids.map { family(it, ids.indexOf(it)) }), "a", 1).toMutableList()
        assertEquals(listOf("b", "c", "d", "a"), ids)
    }

    /**
     * The two facts the card's arrows are drawn from. If these disagree with the guards in
     * [move], the arrow is live on a card that cannot move -- a control that lies.
     */
    @Test
    fun `arrow availability matches what a tap would actually do`() {
        val ordered = listOf("a", "b", "c")
        ordered.forEachIndexed { index, id ->
            assertEquals(
                "up arrow on $id", index > 0, move(booth(*ordered.toTypedArray()), id, -1) != ordered,
            )
            assertEquals(
                "down arrow on $id",
                index < ordered.size - 1,
                move(booth(*ordered.toTypedArray()), id, 1) != ordered,
            )
        }
    }

    @Test
    fun `a single card has no live arrow`() {
        val only = listOf("a")
        assertEquals(false, move(booth("a"), "a", -1) != only)
        assertEquals(false, move(booth("a"), "a", 1) != only)
    }

    @Test
    fun `every step preserves the set, so no card can be lost`() {
        val b = booth("a", "b", "c", "d", "e")
        var ids = b.families.sortedBy { it.order }.map { it.id }
        for (round in 0 until 6) {
            val target = ids[round % ids.size]
            val moved = move(b.copy(families = ids.map { family(it, ids.indexOf(it)) }), target, 1)
            assertEquals("round $round lost or gained a card", ids.toSet(), moved.toSet())
            ids = moved
        }
        assertTrue("the set must be unchanged", ids.toSet() == b.families.map { it.id }.toSet())
    }
}
