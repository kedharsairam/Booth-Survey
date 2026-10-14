package com.kraft.voterlist.ui

import com.kraft.voterlist.data.dropSlotToOrderIndex
import com.kraft.voterlist.data.Booth
import com.kraft.voterlist.data.Family
import com.kraft.voterlist.data.Voter
import com.kraft.voterlist.data.slotToFinalIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which index a drag means, and which index it lands at.
 *
 * [FamilyOrderTest] already pins [Booth.movingFamilyTo] and [slotToFinalIndex] one case at
 * a time. This goes after them from the other end: rather than checking a handful of
 * hand-picked slots, it works out what the layout *means* by a slot and then checks every
 * one of them, on lists of every length up to eight.
 *
 * That matters because the two functions meet nowhere in production code they can both be
 * watched at once. `slotToFinalIndex` lives in the data layer and `movingFamilyTo` is
 * called from a gesture handler with an integer the finger produced. Either one can be
 * right and the pair wrong, and the pair is what decides where a household is walked to on
 * the printed booklet.
 *
 * The layout a drag sees still contains the card being dragged. So "slot 2" means "drop
 * here", where here is read against a list that includes the moving card -- and the card
 * has to come out in the position that means, not the position that reads alike.
 */
class WalkingOrderTest {

    private fun boothOf(vararg ids: String) = Booth(
        id = "b1",
        boothNo = "108",
        families = ids.mapIndexed { i, id -> Family(id, i) },
    )

    private fun order(b: Booth) = b.families.sortedBy { it.order }.map { it.id }

    /**
     * What the layout means by [slot], worked out independently of the production code.
     *
     * Drop the card so that it lands immediately before whatever was showing at that slot,
     * or at the end if the finger went past the last one. Building it from the list rather
     * than from the arithmetic is the point: a model written the same way round as the code
     * it checks cannot catch anything.
     */
    private fun expectedByLayout(ids: List<String>, from: Int, slot: Int): List<String> {
        val out = ids.toMutableList()
        val card = out.removeAt(from)
        // In the list-without-the-card, the thing that was showing at `slot` sits here.
        val at = if (slot >= ids.size) out.size else slot - if (slot > from) 1 else 0
        out.add(at.coerceIn(0, out.size), card)
        return out
    }

    /**
     * Every slot, every card, every list length up to eight.
     *
     * One test rather than a family of them, because the property is the same for all of
     * them and eight cards is more combinations than any hand-written case would reach.
     */
    @Test
    fun `every slot the layout can report lands the card where the layout says`() {
        for (n in 1..8) {
            val ids = (0 until n).map { "f$it" }
            val b = boothOf(*ids.toTypedArray())
            for (from in 0 until n) {
                // Slots run 0..n inclusive: the layout has n+1 positions to drop between,
                // and slot n is "past the last card".
                for (slot in 0..n) {
                    val finalIndex = slotToFinalIndex(slot, from)
                    val after = order(b.movingFamilyTo(ids[from], finalIndex))
                    assertEquals(
                        "n=$n dragging ${ids[from]} from $from, finger on slot $slot " +
                            "(final index $finalIndex)",
                        expectedByLayout(ids, from, slot),
                        after,
                    )
                }
            }
        }
    }

    /**
     * The dragged card counts itself among the slots, and nothing else may.
     *
     * The failure this exists to catch is silent and is exactly what the arithmetic fixes:
     * without the correction, a downward drag of one row asks for the index the card already
     * holds -- so the card refuses to move for a row of travel and then jumps two on the
     * next one. A surveyor arranging two hundred households would learn to distrust the
     * gesture and go back to tapping arrows one at a time.
     */
    @Test
    fun `a slot past the dragged card means one row further along than it reads`() {
        for (n in 1..8) {
            val ids = (0 until n).map { "f$it" }
            for (from in 0 until n) {
                for (slot in (from + 1)..n) {
                    assertEquals(
                        "slot $slot with the card at $from",
                        slot - 1,
                        slotToFinalIndex(slot, from),
                    )
                }
                for (slot in 0..from) {
                    assertEquals(
                        "a slot at or before the card already is the final index",
                        slot,
                        slotToFinalIndex(slot, from),
                    )
                }
            }
        }
    }

    /**
     * A card dropped on its own place stays on its own place.
     *
     * Hovering a card does not move it -- the move waits for the finger to commit past the
     * midpoint -- and that is the reason the layout holds a moving card at all. If this ever
     * fails, a card will swap out from under a finger that has only just arrived.
     */
    @Test
    fun `a card dropped on its own slot does not move`() {
        val ids = listOf("a", "b", "c", "d", "e")
        val b = boothOf(*ids.toTypedArray())
        for (from in ids.indices) {
            val after = b.movingFamilyTo(ids[from], slotToFinalIndex(from, from))
            assertEquals("from $from", ids, order(after))
        }
    }

    /**
     * One row of downward travel swaps with the next card. Two rows swap with the one after.
     *
     * The stickiness this replaces was: one row of travel did nothing, two jumped two. Both
     * halves are asserted because the fix for one is a one-character change that breaks the
     * other, and that is the whole reason this file exists.
     */
    @Test
    fun `downward travel moves exactly as far as the finger went`() {
        val ids = listOf("a", "b", "c", "d", "e")
        val b = boothOf(*ids.toTypedArray())
        val from = 1

        // Slot from + 1 is still the card's own, so hovering its neighbour's top half does
        // not move it -- the commit is at the midpoint.
        assertEquals(ids, order(b.movingFamilyTo("b", slotToFinalIndex(from + 1, from))))
        // Past c's midpoint: one place down, not zero.
        assertEquals(
            listOf("a", "c", "b", "d", "e"),
            order(b.movingFamilyTo("b", slotToFinalIndex(from + 2, from))),
        )
        // Past d's midpoint: two places down. Not one, and not three -- a drop reads as a
        // slot, so an off-by-one here is a whole row of the printed booklet out of place.
        assertEquals(
            listOf("a", "c", "d", "b", "e"),
            order(b.movingFamilyTo("b", slotToFinalIndex(from + 3, from))),
        )
        // Past e's midpoint: the bottom.
        assertEquals(
            listOf("a", "c", "d", "e", "b"),
            order(b.movingFamilyTo("b", slotToFinalIndex(from + 4, from))),
        )
    }

    /**
     * Nothing may be lost, duplicated or left sharing a position, however it is dragged.
     *
     * The clean run matters as much as the set: a tie in `order` is what makes the printed
     * booklet's family order depend on the sort's idea of ties rather than on the
     * surveyor's arrangement.
     */
    @Test
    fun `a drag never loses a card and always leaves a clean run`() {
        for (n in 1..8) {
            val ids = (0 until n).map { "f$it" }
            val b = boothOf(*ids.toTypedArray())
            for (from in 0 until n) {
                for (slot in 0..n) {
                    val after = b.movingFamilyTo(ids[from], slotToFinalIndex(slot, from))
                    assertEquals("set after $from to $slot", ids.toSet(), after.families.map { it.id }.toSet())
                    assertEquals(
                        "clean run after $from to $slot",
                        (0 until n).toList(),
                        after.families.sortedBy { it.order }.map { it.order },
                    )
                }
            }
        }
    }

    // --- the order does not depend on luck ---------------------------------

    /**
     * Two households that cannot be told apart still come out in the same order every time.
     *
     * The print order falls back through the door number and then the lowest serial, so a
     * tie on all three is only reachable from a hand-written file -- but "only reachable
     * from a hand-written file" is not the same as "cannot happen", and a walking order
     * that reshuffles itself between two launches would put the street in a different
     * sequence than the one the surveyor arranged.
     *
     * What settles it is that the sort is stable and the input is the stored list, whose
     * order is an array in the file and so survives a save and reload unchanged. Both halves
     * are asserted: the same booth gives the same answer twice, and it gives it again after
     * a trip through the file it is written to.
     */
    @Test
    fun `a tie the sort cannot break still prints the same way every time`() {
        // Same position, same door, same lowest serial. Only the stored order differs.
        val b = Booth(
            id = "b1",
            boothNo = "108",
            families = listOf(Family("second", 0), Family("first", 0)),
            voters = listOf(
                Voter(id = "a", serial = 5, name = "A", house = "10-1-1", familyId = "second", familyOrder = 0),
                Voter(id = "b", serial = 5, name = "B", house = "10-1-1", familyId = "first", familyOrder = 0),
            ),
        )
        val once = b.familiesWithMembers.map { it.family.id }
        assertEquals("the same booth, asked twice", once, b.familiesWithMembers.map { it.family.id })

        val reloaded = com.kraft.voterlist.data.boothFromJsonOf(com.kraft.voterlist.data.boothJsonOf(b))
        assertEquals(
            "and again after being written to the file and read back",
            once,
            reloaded!!.familiesWithMembers.map { it.family.id },
        )
    }

    // --- the index the drag is handed is one of the caller's choosing ---------

    /**
     * **A drag under a family search moves a household to the wrong place in the walking
     * order.** This is a defect, and it is the worst one in the app.
     *
     * The families list renders [UiState.visibleFamilies] -- the search result -- and hands
     * the drag `itemCount = families.size`, the size of that filtered list. The index the
     * finger produces is therefore an index *into the filtered list*. It arrives at
     * [Booth.movingFamilyTo], which indexes *every* family.
     *
     * So the moment a search narrows the list, the two numberings stop meaning the same
     * thing and a drag puts the household somewhere the surveyor never asked for and
     * cannot see. Below: ten households, a search matching only the eighth. The card is the
     * only one on screen, so every position on that screen is position 0 -- and position 0
     * of the whole walking order is the first household printed.
     *
     * There is no visible sign. The filtered list looks exactly as it did before the drag,
     * so the printed booklet comes out in an order he never arranged and the app says
     * nothing. The search is written to the session file and restored on the next launch,
     * so it is not even gone by tomorrow.
     *
     * The fix is to resolve the target through the same list the finger was on: pass the
     * *family* to move to and have the ViewModel find its index among the whole list, or
     * have the drag ask for a neighbouring card's id rather than an index.
     */
    @Test
    fun `a drag under a family search lands in the right place in the whole order`() {
        val families = (0 until 10).map { Family("f$it", it) }
        val voters = (0 until 10).map { i ->
            Voter(
                id = "v$i", serial = i + 1, name = "PERSON $i",
                house = "10-1-$i", familyId = "f$i", familyOrder = 0,
            )
        }
        val walking = Booth(id = "b1", boothNo = "108", voters = voters, families = families)

        // The search the Families tab binds to s.familyQuery, holding "PERSON 7".
        val shown = UiState(booth = walking, familyQuery = "PERSON 7")
        assertEquals("one card on screen", listOf("f7"), shown.visibleFamilies.map { it.family.id })

        // The index the drag hands over: the card's own place in what is on screen.
        val shownIndex = shown.visibleFamilies.indexOfFirst { it.family.id == "f7" }
        assertEquals(0, shownIndex)

        val fullOrder = walking.families.sortedBy { it.order }.map { it.id }
        val toFull = dropSlotToOrderIndex(shown.visibleFamilies.map { it.family.id }, "f7", shownIndex, fullOrder)

        // Dropping the only visible card on itself must not move it to the head of the
        // street. It stays where it is, and the printed booklet walks the same order it
        // walked yesterday.
        val after = order(walking.movingFamilyTo("f7", toFull))
        assertEquals(
            "the household stays in its place in the whole order",
            listOf("f0", "f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9"),
            after,
        )
    }

    /**
     * The translation, proven over every combination rather than one example.
     *
     * The bug was an index from one list applied to another, which is exactly the kind of
     * defect that hides in the case nobody tried. So every card is dropped at every slot of
     * every filter of every list length up to eight, and the result is checked against a
     * model built from the definition rather than from the code: the card must end up in
     * front of whichever household was sitting at that slot, in the whole order.
     */
    @Test
    fun `every drop on every filtered view lands where the finger pointed`() {
        for (n in 1..8) {
            val full = (0 until n).map { "f$it" }
            for (filterAt in 0 until n) {
                val card = "f$filterAt"
                val shown = listOf(card)
                for (slot in 0..n) {
                    val idx = dropSlotToOrderIndex(shown, card, slot, full)

                    // Model built from the definition, not from the code: the card goes in
                    // front of whatever sat at that slot, or just after the last visible
                    // card when dropped past the end -- and stays put when nothing else is
                    // on screen to sit against.
                    val others = shown.filter { it != card }
                    val expectedIdx = when {
                        others.isEmpty() -> full.indexOf(card)
                        slot < others.size -> full.indexOf(others[slot])
                        else -> full.indexOf(others.last()) + 1
                    }
                    assertEquals("idx n=$n filter=$card slot=$slot", expectedIdx, idx)

                    // And the real thing it exists for: apply it and check the order.
                    val booth = Booth(
                        id = "b", boothNo = "1",
                        families = full.mapIndexed { i, id -> Family(id, i) },
                    )
                    val want = full.filter { it != card }.toMutableList().apply { add(idx, card) }
                    assertEquals(
                        "order n=$n filter=$card slot=$slot",
                        want,
                        order(booth.movingFamilyTo(card, idx)),
                    )
                }
            }
        }
    }
}
