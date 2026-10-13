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
 * The print order and the spreadsheet.
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
    fun `solos print in roll order`() {
        // Nobody arranged them — there is no family to arrange — so the roll's own order
        // is what is left. Serial order is stable under reimport; house order would need
        // choosing, and nobody chose it.
        val order = blocks(
            listOf(
                voter(5, "10-1-5"),
                voter(2, "10-1-12"),
                voter(4, "93-2-2/3"),
                voter(1, "95-6-6/1"),
                voter(3, "96-3-3/5"),
            )
        ).map { it.members.first().serial }
        assertEquals(listOf(1, 2, 3, 4, 5), order)
    }

    @Test
    fun `families print in the arranged order, not the door order`() {
        // The street is walked the way he arranged it, not the way the roll numbers it.
        // f1 sits at the far door but walks first because he put it first.
        val v = listOf(
            voter(1, "11-9-9").copy(familyId = "f1", familyOrder = 0),
            voter(2, "10-1-1").copy(familyId = "f2", familyOrder = 0),
        )
        val b = blocks(v, listOf(Family("f1", 0), Family("f2", 1)))
        assertEquals(listOf("f1", "f2"), b.map { it.familyId })
    }

    @Test
    fun `equal order falls back to doors, then roll`() {
        // A clean 0..n-1 run never ties, but files written by hand can. The old rule —
        // doors, then roll — survives as the tiebreak rather than the order.
        val v = listOf(
            voter(1, "10-1-1").copy(familyId = "f2", familyOrder = 0),
            voter(2, "10-1-1").copy(familyId = "f1", familyOrder = 0),
        )
        assertEquals(listOf("f2", "f1"), blocks(v, listOf(Family("f1", 0), Family("f2", 0))).map { it.familyId })
    }

    @Test
    fun `the surveyor's arrangement is the order within a family`() {
        val v = listOf(
            voter(1, "10-1-1").copy(familyId = "f1", familyOrder = 3),
            voter(2, "10-1-1").copy(familyId = "f1", familyOrder = 0),
            voter(3, "10-1-1").copy(familyId = "f1", familyOrder = 2),
            voter(4, "10-1-1").copy(familyId = "f1", familyOrder = 1),
        )
        assertEquals(listOf(2, 4, 3, 1), blocks(v, listOf(Family("f1", 0)))[0].members.map { it.serial })
    }

    @Test
    fun `a family is named after whoever is first and follows a change`() {
        // The name is worked out from the members, not stored. A stored label has to be kept in
        // step with the arrangement by hand, and the day somebody reorders the household and
        // forgets is the day the app says one thing and the sheet another with nothing to
        // show for it.
        val b = booth(
            listOf(
                voter(1, "10-1-1").copy(familyId = "f1", familyOrder = 0),
                voter(2, "10-1-1").copy(familyId = "f1", familyOrder = 1),
            ),
            listOf(Family("f1", 0)),
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
    fun `anyone not in a family prints after every family, in roll order`() {
        // Dad builds a family for everyone, so solos should not occur. When one leaks
        // through — a fresh import before the evening's arranging — it still has to print
        // somewhere sensible rather than nowhere. After the families, in roll order: the
        // roll is the only ordering nobody had to choose.
        val v = listOf(
            voter(9, "10-9-9"),
            voter(1, "10-1-1").copy(familyId = "f1", familyOrder = 0),
            voter(5, "10-5-5"),
        )
        val b = blocks(v, listOf(Family("f1", 0)))
        assertEquals(listOf("f1", null, null), b.map { it.familyId })
        assertEquals(listOf(5, 9), b.filter { it.isSolo }.map { it.members.first().serial })
    }

    @Test
    fun `a blank door number still prints and never merges`() {
        val b = blocks(
            listOf(
                Voter(id = "a", serial = 1, name = "A", house = ""),
                Voter(id = "b", serial = 2, name = "B", house = ""),
                Voter(id = "c", serial = 3, name = "C", house = "10-1-1"),
            )
        )
        assertEquals(listOf(1, 2, 3), b.map { it.members.first().serial })
        // Two unreadable numbers are two separate blocks. Merging them would invent a
        // household out of two unrelated houses.
        assertEquals(3, b.size)
    }


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
                Voter(id = "a", serial = 1, name = "A", house = "93-4-4/1", voterId = "ZZZ9818485"),
                Voter(id = "b", serial = 2, name = "B", house = "10-1-5", voterId = "ABC1234567"),
            )
        )
        val bytes = java.io.ByteArrayOutputStream()
            .also { out -> XlsxExporter.write(out, b, Lists()) }.toByteArray()
        val sheet = zipPart(bytes, "xl/worksheets/sheet1.xml")
        for (value in listOf("93-4-4/1", "10-1-5", "ZZZ9818485", "ABC1234567")) {
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
    fun `spreadsheet rows follow the arranged family order`() {
        // The sheet is the work list: families in walking order with members arranged,
        // then solos. A sheet in roll order would send him back across the street.
        val b = booth(
            listOf(
                voter(9, "10-9-9").copy(familyId = "f1", familyOrder = 0),
                voter(1, "10-1-1").copy(familyId = "f2", familyOrder = 0),
                voter(5, "10-5-5"),
            ),
            listOf(Family("f1", 1), Family("f2", 0)),
        )
        val bytes = java.io.ByteArrayOutputStream().also { out -> XlsxExporter.write(out, b, Lists()) }.toByteArray()
        val sheet = zipPart(bytes, "xl/worksheets/sheet1.xml")
        // Serial 1 (f2, arranged first) before serial 9 (f1), solo serial 5 last.
        val serials = Regex("<c r=\"A\\d+\"><v>(\\d+)</v></c>").findAll(sheet).map { it.groupValues[1] }.toList()
        assertEquals(listOf("1", "9", "5"), serials)
    }

    @Test
    fun `spreadsheet with only pending members keeps the family`() {
        // A family where everyone is done drops out of a remaining-only export; a family
        // with one person still behind a door stays, whole.
        val b = booth(
            listOf(
                voter(1, "10-1-1").copy(familyId = "f1", familyOrder = 0, visited = true),
                voter(2, "10-1-1").copy(familyId = "f1", familyOrder = 1),
                voter(3, "10-1-2").copy(familyId = "f2", familyOrder = 0, visited = true),
                voter(4, "10-1-2").copy(familyId = "f2", familyOrder = 1, skipped = true),
            ),
            listOf(Family("f1", 0), Family("f2", 1)),
        )
        // The shared definition, called. This line used to write its own filter,
        // `filter { !it.isDone }`, which is not the same thing: isDone counts a *skipped*
        // person as finished, so the copy quietly answered a different question from the one
        // both exports actually use. It happened to agree on this fixture, which is exactly
        // how a test like that survives a real regression. ForPendingOnly is `!visited`, and
        // f2's member 4 here is skipped -- so the copy dropped them and the real one keeps
        // the household whole, which is what the booklet has to do.
        val pending = b.forPendingOnly()
        val bytes = java.io.ByteArrayOutputStream().also { out -> XlsxExporter.write(out, pending, Lists()) }.toByteArray()
        val sheet = zipPart(bytes, "xl/worksheets/sheet1.xml")
        assertTrue("f1 lost its pending member", sheet.contains(">2<"))
        assertTrue(
            "a skipped member is still to visit: the household must stay whole",
            sheet.contains(">4<"),
        )
        assertTrue("the fully visited f2 member must not come back", !sheet.contains(">3<"))
    }

    @Test
    fun `spreadsheet carries the family and the arrangement`() {
        // The sheet and the family screen have to agree, or there is no way to check either.
        val b = booth(
            listOf(
                Voter(id = "a", serial = 1, name = "GRANDMA", familyId = "f1", familyOrder = 0),
                Voter(id = "b", serial = 2, name = "FATHER", familyId = "f1", familyOrder = 1),
            ),
            listOf(Family("f1", 0)),
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
    // --- booklet pages -------------------------------------------------------

    private fun houses(sizes: List<Int>) = Survey(
        booth = Booth(
            boothNo = "108", mandal = "Palakollu", townWard = "Palakollu",
            voters = sizes.flatMapIndexed { h, count ->
                (1..count).map { m ->
                    voter(serial = h * 1000 + m, house = "10-$h", name = "P$h.$m")
                        .copy(familyId = "f$h", familyOrder = m - 1)
                }
            },
            families = sizes.indices.map { Family(id = "f$it", order = it) },
        ),
    )

    private fun pagesOf(sizes: List<Int>) = PdfExporter.layout(PrintOrder.blocks(houses(sizes)), false).first

    /** The "Family sr.no." printed against each voter, in print order. */
    private fun serialsOf(sizes: List<Int>) =
        pagesOf(sizes).flatMap { p -> p.flatMap { r -> (r.from until r.to).map { it + 1 } } }

    /**
     * "Family sr.no." is the member's place in their own household.
     *
     * Four in the first house print 1, 2, 3, 4 and three in the second start again at 1. The
     * earlier version numbered households, so the first four all carried a 1 and the next
     * three a 2 -- a tally of doors rather than the member's place in the house.
     */
    @Test
    fun `family serial counts members within each house`() {
        assertEquals(
            listOf(1, 2, 3, 4, 1, 2, 3, 1, 2),
            serialsOf(listOf(4, 3, 2)),
        )
    }

    /**
     * Pages 1 to 3 fill; only the booklet's fourth page protects a household.
     *
     * Fifteen houses of four is sixty people, exactly one booklet, and those households do
     * not divide into pages of fifteen. Refusing to split on every page would leave the
     * booklet short, so a house runs on across pages 1 to 3.
     */
    @Test
    fun `households run on across the first three pages of a booklet`() {
        val pages = pagesOf(List(15) { 4 })
        assertEquals("sixty people, four pages", 4, pages.size)
        pages.forEachIndexed { i, p ->
            assertEquals("page ${i + 1} is full", 15, PdfExporter.printedRows(p))
        }
    }

    /**
     * A household runs on between pages of the same booklet, even onto its last page.
     *
     * Forty-two people fill pages 1 and 2 and leave three rows on page 3. A four-person house
     * will not fit those three, and page 3 is not the booklet's last page, so it runs on --
     * three members on page 3 and the fourth on page 4. Both are in the same stapled booklet,
     * so nothing has been torn across two books. This is what keeps the booklet at sixty.
     */
    @Test
    fun `a house runs on inside a booklet rather than leaving rows empty`() {
        val pages = pagesOf(listOf(42, 4))
        assertEquals("42 then 4 people is one booklet", 4, pages.size)
        assertEquals("page 3 fills rather than giving up three rows", 15, PdfExporter.printedRows(pages[2]))
        assertEquals("the fourth member is on page 4", 1, PdfExporter.printedRows(pages[3]))
    }

    /**
     * A household is never split across two booklets.
     *
     * This is the case the rule exists for: fifty-seven people leave three rows on page 4 --
     * the booklet's last page -- and a four-person house arrives. Printing three of them there
     * and one in the next stapled book is what the earlier version did, and it puts a single
     * house in two booklets, which is the one thing a stapled booklet exists to prevent.
     */
    @Test
    fun `a house is never split across two booklets`() {
        val pages = pagesOf(listOf(57, 4))
        assertEquals(
            "57 people need four pages", 57, pages.take(4).sumOf { PdfExporter.printedRows(it) },
        )
        assertEquals("page 4 gives up its three spare rows", 12, PdfExporter.printedRows(pages[3]))
        assertEquals("the next booklet starts the whole house", 4, PdfExporter.printedRows(pages[4]))
    }

    /**
     * A household larger than a page still runs on, because the paper allows nothing else.
     *
     * This is the one case where splitting is forced rather than chosen.
     */
    @Test
    fun `a house larger than a page runs on`() {
        val pages = pagesOf(listOf(30))
        assertEquals("30 people is two pages", 2, pages.size)
        assertEquals(15, PdfExporter.printedRows(pages[0]))
        assertEquals(15, PdfExporter.printedRows(pages[1]))
    }

    @Test
    fun `a fifteen person page holds exactly fifteen`() {
        val pages = pagesOf(List(15) { 1 })
        assertEquals(1, pages.size)
        assertEquals(15, PdfExporter.printedRows(pages[0]))
    }

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
