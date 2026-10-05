package com.kraft.voterlist.export

import com.kraft.voterlist.data.Booth
import com.kraft.voterlist.data.Family
import com.kraft.voterlist.data.Lists
import com.kraft.voterlist.data.PrintOrder
import com.kraft.voterlist.data.Survey
import com.kraft.voterlist.data.Voter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The booklet and the spreadsheet.
 *
 * Three things here have been wrong at some point, and all three failed quietly: the print
 * order, the promise that a family is never split, and the promise that a door number stays
 * text in the spreadsheet.
 */
class ExportTest {

    private var n = 0

    private fun voter(
        serial: Int = ++n,
        house: String = "10-1-1",
        name: String = "PERSON $serial",
    ) = Voter(id = "id$serial", serial = serial, name = name, house = house)

    private fun booth(voters: List<Voter>, families: List<Family> = emptyList()) =
        Booth(boothNo = "108", mandal = "Palakollu", townWard = "4", voters = voters, families = families)

    private fun blocks(v: List<Voter>, f: List<Family> = emptyList()) =
        PrintOrder.blocks(Survey(booth(v, f)))

    // --- print order ---------------------------------------------------------

    @Test
    fun `door numbers order by value, not as text`() {
        // The whole reason there is a natural comparator. As text, "10-1-12" sorts before
        // "10-1-5" because '1' precedes '5', which puts a house on the wrong page.
        val order = blocks(
            listOf(
                voter(1, "10-1-5"),
                voter(2, "10-1-12"),
                voter(3, "93-2-2/3"),
                voter(4, "95-6-6/1"),
                voter(5, "96-3-3/5"),
            )
        ).map { it.members.first().house }
        assertEquals(listOf("96-3-3/5", "10-1-5", "10-1-12", "93-2-2/3", "95-6-6/1"), order)
    }

    @Test
    fun `a family prints at its lowest door number`() {
        val v = listOf(
            voter(1, "95-7-7/5").copy(familyId = "f1", familyOrder = 0),
            voter(2, "93-7-7/3").copy(familyId = "f1", familyOrder = 1),
            voter(3, "98-7-7/3").copy(familyId = "f1", familyOrder = 2),
            voter(4, "10-9-9"),
        )
        val b = blocks(v, listOf(Family("f1")))
        assertEquals("10-9-9", b[0].members.first().house)
        assertEquals("f1", b[1].familyId)
    }

    @Test
    fun `the surveyor's arrangement is the order within a family`() {
        val v = listOf(
            voter(1, "10-1-1").copy(familyId = "f1", familyOrder = 3),
            voter(2, "10-1-1").copy(familyId = "f1", familyOrder = 0),
            voter(3, "10-1-1").copy(familyId = "f1", familyOrder = 2),
            voter(4, "10-1-1").copy(familyId = "f1", familyOrder = 1),
        )
        assertEquals(listOf(2, 4, 3, 1), blocks(v, listOf(Family("f1")))[0].members.map { it.serial })
    }

    @Test
    fun `a family is named after whoever is first and follows a change`() {
        // The name is worked out from the members, not stored. A stored label has to be kept in
        // step with the arrangement by hand, and the day somebody reorders the household and
        // forgets is the day the app says one thing and the booklet another with nothing to
        // show for it.
        val b = booth(
            listOf(
                voter(1, "10-1-1").copy(familyId = "f1", familyOrder = 0),
                voter(2, "10-1-1").copy(familyId = "f1", familyOrder = 1),
            ),
            listOf(Family("f1")),
        )
        assertEquals("PERSON 1 family", Survey(b).familiesWithMembers[0].label)

        val reordered = b.copy(
            voters = b.voters.map {
                if (it.serial == 1) it.copy(familyOrder = 1) else it.copy(familyOrder = 0)
            },
        )
        assertEquals("PERSON 2 family", Survey(reordered).familiesWithMembers[0].label)
    }

    @Test
    fun `anyone not in a family prints as a numbered family of one`() {
        // Nobody has built a family yet on the morning of the first reprint, so the booklet
        // still has to be a complete work list rather than a blank one.
        val b = blocks((1..5).map { voter(it, "10-1-$it") })
        assertEquals(5, b.size)
        assertTrue(b.all { it.isSolo })
        val (pages, _) = PdfExporter.layout(b, onlyPending = false)
        assertEquals(listOf(1, 2, 3, 4, 5), pages.flatten().map { it.familyNo })
    }

    @Test
    fun `a blank door number sorts last and never merges`() {
        val b = blocks(
            listOf(
                Voter(id = "a", serial = 1, name = "A", house = ""),
                Voter(id = "b", serial = 2, name = "B", house = ""),
                Voter(id = "c", serial = 3, name = "C", house = "10-1-1"),
            )
        )
        assertEquals("10-1-1", b[0].members.first().house)
        // Two unreadable numbers are two separate blocks. Merging them would invent a
        // household out of two unrelated houses.
        assertEquals(3, b.size)
    }

    @Test
    fun `two families at the same door number fall back to roll order`() {
        val v = listOf(
            voter(1, "10-1-1").copy(familyId = "f2", familyOrder = 0),
            voter(2, "10-1-1").copy(familyId = "f1", familyOrder = 0),
        )
        assertEquals(listOf("f2", "f1"), blocks(v, listOf(Family("f1"), Family("f2"))).map { it.familyId })
    }

    // --- never split ---------------------------------------------------------

    @Test
    fun `no family is ever split across pages`() {
        val sizes = listOf(1, 2, 3, 4, 8, 14, 15, 7, 6, 5, 9, 11, 13)
        val voters = mutableListOf<Voter>()
        val families = mutableListOf<Family>()
        sizes.forEachIndexed { i, size ->
            val id = "f$i"
            families += Family(id)
            repeat(size) { k ->
                voters += voter(i * 100 + k, "10-1-$i").copy(familyId = id, familyOrder = k)
            }
        }
        val (pages, _) = PdfExporter.layout(blocks(voters, families), onlyPending = false)

        // A family occupying more than one page is a split family. Counting the pages it
        // appears on is the assertion that matters: an earlier version checked that a
        // household's rows were contiguous, which a split household satisfies perfectly, so it
        // passed while the exporter split every household that ran off the page.
        val pagesPer = mutableMapOf<String, MutableSet<Int>>()
        pages.forEachIndexed { p, rows ->
            assertTrue("page ${p + 1} overflows", printedRows(rows) <= 15)
            for (r in rows) pagesPer.getOrPut(r.block.familyId!!) { mutableSetOf() }.add(p)
        }
        for ((id, on) in pagesPer) {
            assertEquals("$id is split across pages ${on.sorted()}", 1, on.size)
        }
        assertEquals(families.size, pagesPer.size)
    }

    @Test
    fun `a family larger than a page is continued and reported`() {
        val f = Family("f1")
        val voters = (1..30).map { voter(it, "10-1-1").copy(familyId = "f1", familyOrder = it - 1) }
        val (pages, notes) = PdfExporter.layout(blocks(voters, listOf(f)), onlyPending = false)
        assertEquals(2, pages.size)
        assertEquals(15, printedRows(pages[0]))
        assertEquals(15, printedRows(pages[1]))
        assertTrue("the overflow is reported", notes.any { it.contains("more than a page") })
    }

    @Test
    fun `a family is moved whole rather than started at the bottom of a page`() {
        val families = (1..9).map { Family("f$it") }.toMutableList()
        val voters = mutableListOf<Voter>()
        families.forEachIndexed { i, f ->
            voters += voter(i + 1, "10-1-${i + 1}").copy(familyId = f.id, familyOrder = 0)
        }
        families += Family("tail")
        (10..17).forEach { k ->
            voters += voter(k, "10-2-9").copy(familyId = "tail", familyOrder = k - 10)
        }
        val (pages, _) = PdfExporter.layout(blocks(voters, families), onlyPending = false)

        assertEquals("six rows left unused on page one", 6, 15 - printedRows(pages[0]))
        assertTrue("no part of the tail on page one", pages[0].none { it.block.familyId == "tail" })
        assertEquals(8, pages[1].first { it.block.familyId == "tail" }.to)
    }

    @Test
    fun `family numbers restart in each booklet`() {
        val voters = (1..150).map { voter(it, "10-${(it / 15) + 1}-1") }
        val (pages, _) = PdfExporter.layout(blocks(voters), onlyPending = false)
        assertEquals(10, pages.size)
        assertEquals(1, pages[0].first().familyNo)
        assertEquals(16, pages[1].first().familyNo)
        assertEquals(46, pages[3].first().familyNo)
        assertEquals(1, pages[4].first().familyNo)
        assertEquals(1, pages[8].first().familyNo)
    }

    @Test
    fun `still to visit skips a family only when all of it is done`() {
        val f1 = Family("f1")
        val f2 = Family("f2")
        val voters = listOf(
            voter(1, "10-1-1").copy(familyId = "f1", familyOrder = 0, visited = true),
            voter(2, "10-1-1").copy(familyId = "f1", familyOrder = 1),
            voter(3, "10-1-2").copy(familyId = "f2", familyOrder = 0, visited = true),
            voter(4, "10-1-2").copy(familyId = "f2", familyOrder = 1, skipped = true),
        )
        val (pages, _) = PdfExporter.layout(blocks(voters, listOf(f1, f2)), onlyPending = true)
        // One of the four is still standing behind the door, so their family is still work.
        assertEquals(listOf("f1"), pages.flatten().map { it.block.familyId }.distinct())
    }

    /** Printed rows on a page. One Row can stand for several. */
    private fun printedRows(page: List<PdfExporter.Row>): Int = page.sumOf { it.to - it.from }

    // --- spreadsheet ---------------------------------------------------------

    @Test
    fun `spreadsheet is a readable xlsx with the expected row count`() {
        val bytes = java.io.ByteArrayOutputStream()
            .also { out -> XlsxExporter.write(out, booth((1..30).map { voter(it) }), Lists()) }
            .toByteArray()
        assertEquals(31, Regex("<row ").findAll(zipPart(bytes, "xl/worksheets/sheet1.xml")).count())
    }

    @Test
    fun `spreadsheet stores door numbers and voter ids as text`() {
        // A CSV cannot achieve this. A spreadsheet is entitled to read "98-3-3/2" as a date and
        // does; storing the cell as a text type in the file removes the guesswork.
        val b = booth(
            listOf(
                Voter(id = "a", serial = 1, name = "A", house = "93-4-4/1", voterId = "ZZZ7696263"),
                Voter(id = "b", serial = 2, name = "B", house = "10-1-5", voterId = "ABC1234567"),
            )
        )
        val bytes = java.io.ByteArrayOutputStream()
            .also { out -> XlsxExporter.write(out, b, Lists()) }.toByteArray()
        val sheet = zipPart(bytes, "xl/worksheets/sheet1.xml")
        for (value in listOf("93-4-4/1", "10-1-5", "ZZZ7696263", "ABC1234567")) {
            assertTrue(
                "$value is not stored as an inline string",
                sheet.contains(Regex("<c r=\"[A-Z]+\\d+\" t=\"inlineStr\"><is><t[^>]*>" + Regex.escape(value) + "</t>")),
            )
        }
    }

    @Test
    fun `spreadsheet keeps serial numeric so the sheet sorts`() {
        val bytes = java.io.ByteArrayOutputStream()
            .also { out -> XlsxExporter.write(out, booth((1..5).map { voter(it) }), Lists()) }.toByteArray()
        assertTrue(zipPart(bytes, "xl/worksheets/sheet1.xml").contains(Regex("<c r=\"A2\"><v>1</v></c>")))
    }

    @Test
    fun `spreadsheet carries the family and the arrangement`() {
        // The sheet and the booklet have to agree, or there is no way to check either.
        val b = booth(
            listOf(
                Voter(id = "a", serial = 1, name = "GRANDMA", familyId = "f1", familyOrder = 0),
                Voter(id = "b", serial = 2, name = "FATHER", familyId = "f1", familyOrder = 1),
            ),
            listOf(Family("f1")),
        )
        val bytes = java.io.ByteArrayOutputStream().also { out -> XlsxExporter.write(out, b, Lists()) }.toByteArray()
        val sheet = zipPart(bytes, "xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains(">GRANDMA family<"))
        assertTrue(sheet.contains(">GRANDMA<"))
        assertTrue(sheet.contains(">In family order<"))
    }

    @Test
    fun `the template carries exactly the four columns the importer looks for`() {
        // This is what makes the app usable by somebody else: a volunteer with no sheet
        // exports one of these, fills it from their own roll, and imports it.
        val bytes = java.io.ByteArrayOutputStream().also { XlsxExporter.writeTemplate(it) }.toByteArray()
        val sheet = zipPart(bytes, "xl/worksheets/sheet1.xml")
        for (h in listOf("serial", "voter id", "name", "house number")) {
            assertTrue("template is missing '$h'", sheet.contains(">$h<"))
        }
        assertEquals("one heading row and one example", 2, Regex("<row ").findAll(sheet).count())
    }

    @Test
    fun `the template the app writes is one the app can read back`() {
        // The round trip matters more than either half: a template the importer cannot read is
        // worse than no template, because the failure appears at the far end.
        val bytes = java.io.ByteArrayOutputStream().also { XlsxExporter.writeTemplate(it) }.toByteArray()
        val r = com.kraft.voterlist.io.XlsxReader.read(java.io.ByteArrayInputStream(bytes))
        assertTrue("columns not found: ${r.missingColumns}", r.missingColumns.isEmpty())
        assertEquals(1, r.rows.size)
        assertEquals("EXAMPLE NAME", r.rows[0].name)
    }

    /** Pulls one part out of the zip without a library, the same way the writer put it in. */
    private fun zipPart(bytes: ByteArray, name: String): String {
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name == name) return zip.readBytes().toString(Charsets.UTF_8)
                e = zip.nextEntry
            }
        }
        throw AssertionError("no part called $name")
    }
}
