package com.kraft.voterlist.ui

import com.kraft.voterlist.data.Booth
import com.kraft.voterlist.data.Family
import com.kraft.voterlist.data.Voter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The voter list's filters and the bulk operations behind selection mode.
 *
 * Everything here is pure: filters are derived on [UiState], bulk edits are [Booth]
 * transforms, so no Android runtime is needed. The ViewModel only wraps these in
 * save-and-toast, which is why there is nothing view-model-shaped in this file.
 */
class UiFilterTest {

    private var n = 0

    private fun voter(
        serial: Int = ++n,
        family: String? = null,
        caste: String = "",
        party: String = "",
        address: String = "",
        visited: Boolean = false,
        skipped: Boolean = false,
        notInFile: Boolean = false,
    ) = Voter(
        id = "id$serial", serial = serial, name = "PERSON $serial", house = "10-1-$serial",
        familyId = family, caste = caste, party = party, address = address,
        visited = visited, skipped = skipped, notInLatestFile = notInFile,
    )

    private fun state(
        voters: List<Voter>,
        filter: UiState.Filter = UiState.Filter.ALL,
        caste: String = "",
        party: String = "",
        address: String = "",
        notInFileOnly: Boolean = false,
        query: String = "",
    ) = UiState(
        loading = false,
        booth = Booth(boothNo = "108", voters = voters),
        query = query, filter = filter,
        casteFilter = caste, partyFilter = party, addressFilter = address,
        notInFileOnly = notInFileOnly,
    )

    // --- caste, party, address, not-in-file --------------------------------------

    @Test
    fun `caste and party match exactly but case-insensitively`() {
        val voters = listOf(
            voter(1, caste = "BC-A", party = "TDP"),
            voter(2, caste = "bc-a", party = "YCP"),
            voter(3, caste = "SC", party = "TDP"),
        )
        assertEquals(listOf(1, 2), state(voters, caste = "BC-A").visible.map { it.serial })
        assertEquals(listOf(1, 3), state(voters, party = "tdp").visible.map { it.serial })
    }

    @Test
    fun `not in file only shows the marked`() {
        val s = state(
            listOf(voter(1), voter(2, notInFile = true), voter(3, notInFile = true)),
            notInFileOnly = true,
        )
        assertEquals(listOf(2, 3), s.visible.map { it.serial })
    }

    @Test
    fun `address matches exactly but case-insensitively`() {
        val voters = listOf(
            voter(1, address = "KONDAPALLI"),
            voter(2, address = "kondapalli"),
            voter(3, address = "MAIN ROAD"),
        )
        assertEquals(listOf(1, 2), state(voters, address = "kondapalli").visible.map { it.serial })
    }

    @Test
    fun `filters combine`() {
        // Left + BC-A + KONDAPALLI: the evening's actual question is answerable in one list.
        val voters = listOf(
            voter(1, caste = "BC-A", address = "KONDAPALLI", visited = true),
            voter(2, caste = "BC-A", address = "KONDAPALLI"),
            voter(3, caste = "BC-A", address = "MAIN ROAD"),
            voter(4, caste = "SC", address = "KONDAPALLI"),
        )
        val s = state(
            voters,
            filter = UiState.Filter.REMAINING, caste = "BC-A", address = "KONDAPALLI",
        )
        assertEquals(listOf(2), s.visible.map { it.serial })
    }

    @Test
    fun `no filters shows everyone in serial order`() {
        val s = state(listOf(voter(3), voter(1), voter(2)))
        assertEquals(listOf(1, 2, 3), s.visible.map { it.serial })
        assertTrue(!s.hasExtraFilters)
    }

    // --- bulk transforms -----------------------------------------------------

    @Test
    fun `dropping voters keeps the family standing`() {
        val b = Booth(
            boothNo = "108", families = listOf(Family("f1", 0)),
            voters = listOf(voter(1, family = "f1"), voter(2, family = "f1"), voter(3)),
        )
        val out = b.withoutVoters(setOf("id1"))
        assertEquals(listOf(2, 3), out.voters.map { it.serial })
        assertEquals(1, out.families.size)
    }

    @Test
    fun `visit state sets both flags together`() {
        val b = Booth(
            boothNo = "108",
            voters = listOf(voter(1, visited = true), voter(2, skipped = true), voter(3)),
        )
        val out = b.withVisitState(setOf("id2", "id3"), visited = true, skipped = false)
        assertEquals(listOf(true, true, true), out.voters.map { it.visited })
        assertTrue(out.voters.none { it.skipped })
    }

    @Test
    fun `moving to a family appends in roll order after the members`() {
        val b = Booth(
            boothNo = "108", families = listOf(Family("f1", 0)),
            voters = listOf(
                voter(9, family = "f1"),
                voter(1), voter(5),
            ),
        )
        val out = b.withFamily(setOf("id1", "id5"), "f1")
        val members = out.voters.filter { it.familyId == "f1" }.sortedBy { it.familyOrder }
        // The member already there stays first; the movers follow in serial order.
        assertEquals(listOf(9, 1, 5), members.map { it.serial })
        assertEquals(listOf(0, 1, 2), members.map { it.familyOrder })
    }

    @Test
    fun `pruning drops families nobody is in and keeps the rest`() {
        val b = Booth(
            boothNo = "108",
            families = listOf(Family("f1", 0), Family("f2", 1), Family("f3", 2)),
            voters = listOf(voter(1, family = "f1"), voter(2, family = "f3")),
        )
        val out = b.pruned()
        assertEquals(listOf("f1", "f3"), out.families.map { it.id })
        // Nothing to prune: the same booth comes back.
        assertTrue(out.families.size == 2)
    }

    @Test
    fun `moving the last member out prunes the emptied family`() {
        val b = Booth(
            boothNo = "108",
            families = listOf(Family("f1", 0), Family("f2", 1)),
            voters = listOf(voter(1, family = "f1"), voter(2)),
        )
        val out = b.withFamily(setOf("id1"), "f2").pruned()
        assertEquals(listOf("f2"), out.families.map { it.id })
        assertEquals("f2", out.voters.first { it.serial == 1 }.familyId)
    }

    @Test
    fun `deleting the last member prunes the family`() {
        val b = Booth(
            boothNo = "108",
            families = listOf(Family("f1", 0)),
            voters = listOf(voter(1, family = "f1"), voter(2)),
        )
        val out = b.withoutVoters(setOf("id1")).pruned()
        assertTrue(out.families.isEmpty())
        assertEquals(listOf(2), out.voters.map { it.serial })
    }
}
