package com.kraft.voterlist.ui

import com.kraft.voterlist.data.Booth
import com.kraft.voterlist.data.slotToFinalIndex
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

    /**
     * What a drag does, as opposed to an arrow.
     *
     * A drag is given the slot the finger is over and the card goes there in one go. It used
     * to move one place per row-height of travel, which is fine for a family of four and
     * unusable for the few hundred families a ward really has on the list.
     */
    private fun dragTo(b: Booth, id: String, to: Int): List<String> =
        b.movingFamilyTo(id, to).families.sortedBy { it.order }.map { it.id }

    /** The walking order of a booth, read back off it. */
    private fun walking(b: Booth): List<String> = b.families.sortedBy { it.order }.map { it.id }

    @Test
    fun `a drag puts the card where it was dropped`() {
        val b = booth("a", "b", "c", "d", "e")
        assertEquals(listOf("b", "c", "d", "e", "a"), dragTo(b, "a", 4))
        assertEquals(listOf("d", "a", "b", "c", "e"), dragTo(b, "d", 0))
        assertEquals(listOf("a", "d", "b", "c", "e"), dragTo(b, "d", 1))
    }

    @Test
    fun `a drag one place is the same as the arrow`() {
        // [Booth.movingFamilyTo] takes the *final* position, not a slot. So "one place up
        // from the third card" is index 1, which is what the arrows produce. Comparing it
        // against index 2 compares two different questions -- index 2 is where the card
        // already is.
        val b = booth("a", "b", "c", "d", "e")
        assertEquals(move(b, "a", 1), dragTo(b, "a", 1))
        assertEquals(move(b, "c", -1), dragTo(b, "c", 1))
        assertEquals(move(b, "c", 1), dragTo(b, "c", 3))
    }

    @Test
    fun `a drag past either end lands at that end`() {
        // A finger drops a card's width outside the row half the time. Refusing the move
        // there would look like the drag had let go of it.
        val b = booth("a", "b", "c")
        assertEquals("down past the end goes to the end", listOf("b", "c", "a"), dragTo(b, "a", 99))
        assertEquals("up past the top goes to the top", listOf("b", "a", "c"), dragTo(b, "b", -4))
    }

    @Test
    fun `a drag onto its own slot changes nothing`() {
        val b = booth("a", "b", "c")
        assertEquals(
            b.families.map { it.order }.sorted(),
            b.movingFamilyTo("b", 1).families.map { it.order }.sorted(),
        )
    }

    @Test
    fun `a drag keeps a clean run`() {
        val b = booth("a", "b", "c", "d", "e", "f").movingFamilyTo("a", 5)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), b.families.sortedBy { it.order }.map { it.order })
    }

    @Test
    fun `a drag never loses or invents a card`() {
        val start = booth("a", "b", "c", "d", "e", "f")
        for (from in listOf("a", "b", "c", "d", "e", "f")) {
            for (to in 0..6) {
                val after = start.movingFamilyTo(from, to)
                assertEquals(
                    "$from to $to lost or gained a card",
                    start.families.map { it.id }.toSet(),
                    after.families.map { it.id }.toSet(),
                )
            }
        }
    }

    @Test
    fun `an unknown card is ignored rather than throwing`() {
        val b = booth("a", "b")
        assertEquals(
            b.families.map { it.id }.sorted(),
            b.movingFamilyTo("ghost", 0).families.map { it.id }.toSortedSet().toList(),
        )
    }

    /**
     * The arithmetic the drag does on the way in.
     *
     * A slot comes out of a layout that still contains the card being dragged; the move
     * needs the final position. These agree except when the slot is past the card's own
     * index, and disagreeing there is what made a downward drag do nothing for one row and
     * then jump two.
     */
    @Test
    fun `a slot past the dragged card becomes one less`() {
        // Dragging card 1 down to "after card 3" (slot 4) should land at index 3.
        assertEquals(3, slotToFinalIndex(slot = 4, from = 1))
        // A slot before the card is already the final index.
        assertEquals(0, slotToFinalIndex(slot = 0, from = 2))
        assertEquals(1, slotToFinalIndex(slot = 1, from = 2))
        // Its own slot is its own index -- dragging within itself does nothing.
        assertEquals(2, slotToFinalIndex(slot = 2, from = 2))
    }

    @Test
    fun `swapping with the card below needs the finger past its midpoint`() {
        val b = booth("a", "b", "c", "d", "e")
        val from = 1

        // Finger over "c"'s top half means "before c" -- which is where b already is, so
        // nothing moves. This threshold is deliberate: a card changes place when the finger
        // commits to being past its neighbour, not while merely hovering over it, so a card
        // is never swapped out from under a finger that has only just arrived.
        val topHalf = from + 1
        assertEquals("still before c, so no move", from, slotToFinalIndex(topHalf, from))
        assertEquals(
            listOf("a", "b", "c", "d", "e"),
            dragTo(b, "b", slotToFinalIndex(topHalf, from)),
        )

        // Past its midpoint means "after c", and that is the swap.
        val bottomHalf = from + 2
        assertEquals(2, slotToFinalIndex(bottomHalf, from))
        assertEquals(listOf("a", "c", "b", "d", "e"), dragTo(b, "b", slotToFinalIndex(bottomHalf, from)))
    }

    @Test
    fun `swapping upward works the same way`() {
        val b = booth("a", "b", "c", "d", "e")
        val from = 3   // "d"

        // Over d's own slot is its own index: nothing moves.
        assertEquals(from, slotToFinalIndex(from, from))
        assertEquals(listOf("a", "b", "c", "d", "e"), dragTo(b, "d", slotToFinalIndex(from, from)))

        // Slot 2 is "after the card at index 1", which is between b and c.
        assertEquals(2, slotToFinalIndex(from - 1, from))
        assertEquals(listOf("a", "b", "d", "c", "e"), dragTo(b, "d", slotToFinalIndex(from - 1, from)))

        // Slot 1 is "before the card at index 1", which is before b.
        assertEquals(1, slotToFinalIndex(from - 2, from))
        assertEquals(listOf("a", "d", "b", "c", "e"), dragTo(b, "d", slotToFinalIndex(from - 2, from)))
    }

    /**
     * The contract [Booth.movingFamilyTo] is documented to keep, checked over the whole
     * square rather than at the four corners.
     *
     * The drag handler asks for a slot and the arrow asks for a step, and the two have to
     * agree or the same card ends up in two places depending on how it was moved. Both
     * existing halves are covered; this is the join between them, over every index there is,
     * because the one-place cases are exactly the ones that were wrong before the rework.
     */
    @Test
    fun `a drag of one place lands where the arrow for that place lands`() {
        val ids = listOf("a", "b", "c", "d", "e")
        val b = booth(*ids.toTypedArray())
        ids.forEachIndexed { from, id ->
            // Down one place: the finger has to be past the *next* card's midpoint, which is
            // slot from + 2, not from + 1.
            assertEquals(
                "drag down from index $from",
                move(b, id, 1),
                dragTo(b, id, slotToFinalIndex(from + 2, from)),
            )
            // Up one place: slot from - 1 is already a final index.
            assertEquals(
                "drag up from index $from",
                move(b, id, -1),
                dragTo(b, id, slotToFinalIndex(from - 1, from)),
            )
        }
    }

    /**
     * The card goes where it was told. Every index, not the ones somebody thought to try.
     */
    @Test
    fun `the dragged card ends up exactly at the slot it was given`() {
        val ids = listOf("a", "b", "c", "d", "e", "f")
        val b = booth(*ids.toTypedArray())
        for (id in ids) {
            for (to in ids.indices) {
                assertEquals("$id dropped on $to", id, dragTo(b, id, to)[to])
            }
        }
    }

    /**
     * ...and nothing else moves.
     *
     * A reorder that shuffles the cards it passed would be a silently wrong walking order,
     * which is the one thing this app exists not to produce. Comparing the list *minus* the
     * dragged card catches it: the others have to come out in the order they went in.
     */
    @Test
    fun `a drag leaves every card it passed in the order it was already in`() {
        val ids = listOf("a", "b", "c", "d", "e", "f")
        val b = booth(*ids.toTypedArray())
        for (id in ids) {
            for (to in ids.indices) {
                assertEquals(
                    "$id dropped on $to reordered the cards around it",
                    ids - id,
                    dragTo(b, id, to) - id,
                )
            }
        }
    }

    /**
     * A booth whose stored `order` is not a clean 0..n-1 run.
     *
     * This is what actually arrives: the walking order is carried on the families, and a
     * booth imported from a spreadsheet brings whatever numbers the sheet had. Reading
     * position off the *list* rather than off `order` is the bug this guards, and the
     * `booth()` helper above cannot catch it because it always hands over a clean run.
     */
    @Test
    fun `a booth whose stored order is not a clean run still reorders correctly`() {
        val b = Booth(
            boothNo = "1",
            families = listOf(
                family("a", 7), family("b", 3), family("c", 11), family("d", 5),
            ),
        )
        // Read by `order`, not by where each family happens to sit in the list.
        assertEquals(listOf("b", "d", "a", "c"), walking(b))

        val after = b.movingFamilyTo("a", 0)
        assertEquals(listOf("a", "b", "d", "c"), walking(after))
        // And it leaves a clean run behind, which is what the card numbers and the export read.
        assertEquals(listOf(0, 1, 2, 3), after.families.sortedBy { it.order }.map { it.order })

        // One arrow on the same booth, because the arrows go through the same data.
        assertEquals(listOf("b", "a", "d", "c"), walking(b.movingFamily("a", -1)))
    }

    /**
     * A booth that has lost a family, or never had one.
     *
     * The drag handler is handed `itemCount` from the list it is drawing, so an empty or
     * single-card list really does ask for a drop. Neither may throw, and a single card must
     * not be shuffled into a different position than the one it is in.
     */
    @Test
    fun `an empty booth and a single card are both left alone`() {
        val empty = Booth(boothNo = "1")
        assertEquals(empty, empty.movingFamilyTo("ghost", 0))
        assertEquals(empty, empty.movingFamilyTo("ghost", 7))

        val only = booth("a")
        assertEquals(listOf("a"), walking(only.movingFamilyTo("a", 0)))
        assertEquals(listOf("a"), walking(only.movingFamilyTo("a", 1)))
        assertEquals(listOf("a"), walking(only.movingFamilyTo("a", 99)))
    }
}
