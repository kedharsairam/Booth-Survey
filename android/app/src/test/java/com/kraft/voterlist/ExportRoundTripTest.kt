package com.kraft.voterlist

import com.kraft.voterlist.data.Booth
import com.kraft.voterlist.data.Family
import com.kraft.voterlist.data.Lists
import com.kraft.voterlist.data.PrintOrder
import com.kraft.voterlist.data.Survey
import com.kraft.voterlist.data.Voter
import com.kraft.voterlist.export.PdfExporter
import com.kraft.voterlist.export.XlsxExporter
import com.kraft.voterlist.io.BoothImporter
import com.kraft.voterlist.io.XlsxReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The whole pipeline, once, end to end.
 *
 * Every other test in this suite is a piece: the reader against a hand-built sheet, the
 * importer against hand-built rows, the print order against a hand-built booth, the writer
 * against a hand-built booth. A piece can all be right and the artefact still be wrong,
 * because the joins between them are exactly where this app loses things -- a booth the
 * importer built in sheet order, a print order that reads `familiesWithMembers`, a
 * spreadsheet that walks `PrintOrder.blocks` and calls it a day.
 *
 * So this one test takes a sheet nobody wrote for the purpose, reads it the way the app
 * reads it, works on it the way a surveyor works on it, exports it both ways, and then
 * reads both files back and says exactly who is in them and in what order.
 *
 * The sheet is built by hand rather than by [XlsxExporter], following [XlsxReaderTest]'s
 * own reasoning: round-tripping through the app's own writer would pass even if the reader
 * and the writer were wrong in the same way.
 */
class ExportRoundTripTest {

    // --- a sheet, in the shape an electoral office issues ---------------------

    /**
     * Six people over three houses, written with the serials deliberately out of order.
     *
     * Out of order because a real part-wise roll runs 1, 101, 201 and a corrected one comes
     * back in whatever order somebody's spreadsheet decided on. Nothing in the app sorts the
     * roll on the way in, so this is where a sheet that arrives jumbled either gets caught
     * or quietly prints the street in the wrong sequence.
     */
    private fun sheet(): ByteArray {
        val head = listOf("serial", "voter id", "name", "house number")
        val body = listOf(
            listOf("3", "WQQ0000003", "GOVARDHAN", "10-1-2"),
            listOf("1", "WQQ0000001", "HEMA LATHA", "10-1-1"),
            listOf("2", "WQQ0000002", "SAI KIRAN", "10-1-1"),
            listOf("6", "WQQ0000006", "RAJU", "10-2-3"),
            listOf("5", "WQQ0000005", "LAKSHMI", "10-2-2"),
            listOf("4", "WQQ0000004", "VENKAT", "10-2-1"),
        )
        val rows = (listOf(head) + body).mapIndexed { r, cells ->
            val cs = cells.mapIndexed { c, v ->
                """<c r="${colName(c)}${r + 1}" t="inlineStr"><is><t xml:space="preserve">$v</t></is></c>"""
            }.joinToString("")
            """<row r="${r + 1}">$cs</row>"""
        }.joinToString("")
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("xl/workbook.xml"))
            z.write(
                ("""<?xml version="1.0"?><workbook><sheets><sheet name="Voters" """ +
                    """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
                    """r:id="rId1"/></sheets></workbook>""").toByteArray()
            )
            z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("xl/_rels/workbook.xml.rels"))
            z.write(
                ("""<?xml version="1.0"?><Relationships><Relationship Id="rId1" """ +
                    """Target="worksheets/sheet1.xml"/></Relationships>""").toByteArray()
            )
            z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("xl/worksheets/sheet1.xml"))
            z.write("""<?xml version="1.0"?><worksheet><sheetData>$rows</sheetData></worksheet>""".toByteArray())
            z.closeEntry()
        }
        return out.toByteArray()
    }

    private fun colName(i: Int) = ('A' + i).toString()

    /** Reads the sheet the way the app does, into the rows the importer takes. */
    private fun readSheet(): List<XlsxReader.Row> {
        val r = XlsxReader.read(ByteArrayInputStream(sheet()))
        assertTrue("the sheet must be readable: ${r.missingColumns}", r.usable)
        assertEquals("nothing wrong with the sheet", 0, r.unreadableSerial)
        assertEquals(0, r.blankName)
        assertEquals(0, r.blankHouse)
        return r.rows
    }

    private val meta = BoothImporter.Meta("108", "Palakollu", "4")

    /**
     * A day of work on top of the imported roll.
     *
     * Two households made at two doors and arranged by hand, one person left out on purpose
     * because a surveyor's roll always has somebody he has not got to yet, one house marked
     * visited, one person marked skipped, and the families arranged *against* the roll --
     * which is the whole point of the walking order, so a test that arranged them to match
     * would prove nothing.
     */
    private fun worked(): Booth {
        val imported = BoothImporter.create(meta, readSheet(), 1L).booth
        val bySerial = imported.voters.associateBy { it.serial }

        // Two houses made at two doors, in the order they were made, which is creation
        // order and not the order they are walked.
        val withHouses = imported.copy(
            families = listOf(Family("f1", 0), Family("f2", 1)),
        )
        // House 10-1 is HEMA LATHA and SAI KIRAN, grandmother first. House 10-2 holds the
        // three at the next door.
        val grouped = withHouses
            .withFamily(setOf(bySerial.getValue(1).id, bySerial.getValue(2).id), "f1")
            .withFamily(
                setOf(bySerial.getValue(3).id, bySerial.getValue(4).id, bySerial.getValue(5).id),
                "f2",
            )
            // withFamily hands out the positions in roll order, so house 10-2 opens as
            // 3, 4, 5 whatever order the sheet listed them in. The order inside the house is
            // then the surveyor's to make, and this one puts the fourth person first.
            //
            // That expectation used to be written as 3, 4, 5 -- the arrangement the
            // pipeline produced *before* withFamily sorted, so the move cancelled out and
            // the test passed while the sheet order was still winning. The move is the
            // point of these three lines; 4, 3, 5 is what it does.
            .movingMember("f2", bySerial.getValue(4).id, -1)
            // The surveyor walked 10-2 first because that is where he started.
            .movingFamilyTo("f2", 0)

        return grouped
            .withVisitState(setOf(bySerial.getValue(1).id), visited = true, skipped = false)
            .withVisitState(setOf(bySerial.getValue(2).id), visited = false, skipped = true)
    }

    // --- the spreadsheet -----------------------------------------------------

    /**
     * The exported sheet, read back as a grid of strings.
     *
     * Read back rather than pattern-matched, because the point is to find out what a person
     * opening the file would see -- not to confirm that some substring the test already
     * knows about is present.
     */
    private fun grid(booth: Booth): List<List<String>> {
        val bytes = ByteArrayOutputStream().also { XlsxExporter.write(it, booth, Lists()) }.toByteArray()
        val xml = zipPart(bytes, "xl/worksheets/sheet1.xml")
        return Regex("""<row [^>]*>(.*?)</row>""").findAll(xml).map { row ->
            Regex("""<c r="[A-Z]+\d+"[^>]*?(?:/>|>(.*?)</c>)""").findAll(row.groupValues[1]).map { cell ->
                val body = cell.groupValues[1]
                when {
                    body.isEmpty() -> ""
                    else -> unescape(
                        Regex("""<t[^>]*>(.*?)</t>""").find(body)?.groupValues?.get(1)
                            ?: Regex("""<v>(.*?)</v>""").find(body)?.groupValues?.get(1)
                            ?: ""
                    )
                }
            }.toList()
        }.toList()
    }

    private fun unescape(s: String) = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** One exported row, keyed by its column heading. */
    private fun exported(booth: Booth): List<Map<String, String>> {
        val rows = grid(booth)
        val head = rows.first()
        return rows.drop(1).map { r -> head.zip(r).toMap() }
    }

    private fun serialsOf(rows: List<Map<String, String>>) = rows.map { it.getValue("Serial").toInt() }


    // --- the tests -----------------------------------------------------------

    /**
     * Six people in, six people out, in the walking order, with the right marks.
     *
     * The walking order is asserted as what the surveyor arranged, not as the roll's: the
     * arranged order puts serial 3 first, which is the opposite of the roll's, and a round
     * trip that only checked "everybody is present" would pass with the order silently
     * reset to the roll's -- which is the defect this whole feature exists to prevent.
     */
    @Test
    fun `the spreadsheet holds exactly the arranged people in the arranged order`() {
        val rows = exported(worked())

        assertEquals(
            "families in walking order with members arranged, then whoever is in no family",
            listOf(4, 3, 5, 1, 2, 6),
            serialsOf(rows),
        )
        assertEquals(
            // The family is named after whoever the surveyor put first, and this fixture
            // moves VENKAT (serial 4) above GOVARDHAN (serial 3) on purpose. So VENKAT, not
            // the lower serial: "first in the house" means first in the arranged order, which
            // is what a family label has always meant. This used to read GOVARDHAN because
            // the move was cancelled out by the pipeline handing out positions in sheet
            // order instead of roll order.
            "the family column is derived from whoever is first in the house",
            listOf(
                "VENKAT family",
                "VENKAT family",
                "VENKAT family",
                "HEMA LATHA family",
                "HEMA LATHA family",
                "", // serial 6: nobody has made a family for this door yet
            ),
            rows.map { it.getValue("Family") },
        )
        assertEquals(
            "the marks the surveyor made must be the marks in the file",
            listOf("", "", "", "Yes", "", ""),
            rows.map { it["Visited"].orEmpty() },
        )
        assertEquals(
            "a skipped house is still a house nobody called at",
            listOf("", "", "", "", "Yes", ""),
            rows.map { it["Skipped"].orEmpty() },
        )
    }

    /**
     * "In family order" is the column the office reads the arrangement off.
     *
     * It has to restart at 1 for each household, because that is what makes it comparable
     * with the booklet's "Family sr.no." -- the two are there so the office can check the
     * app against the surveyor's arrangement. A column that ran 1,2,3,4,5,6 across a booth
     * of households would say nothing.
     */
    @Test
    fun `the arrangement column restarts at one for each household`() {
        assertEquals(
            listOf("1", "2", "3", "1", "2", ""),
            exported(worked()).map { it["In family order"].orEmpty() },
        )
    }

    /**
     * The door number is the one the surveyor knocks on, and it is the field the
     * extraction got wrong most often.
     *
     * Asserted here rather than left to XlsxExporter's own test: the value has to survive
     * import *and* the walk to the file, and a round trip is the only place both are
     * exercised at once.
     */
    @Test
    fun `door numbers and voter ids come out of the round trip exactly as the sheet held them`() {
        val rows = exported(worked()).associateBy { it.getValue("Serial").toInt() }
        assertEquals("10-1-1", rows.getValue(1)["Door no."])
        assertEquals("10-2-2", rows.getValue(5)["Door no."])
        assertEquals("WQQ0000004", rows.getValue(4)["Voter ID"])
    }

    /**
     * A sheet that arrives jumbled prints in serial order anyway.
     *
     * The sheet was handed over with the serials out of sequence on purpose. Nothing in the
     * import sorts the roll, so this passes only because the print order asks the roll's own
     * order from the people who are in no family yet.
     */
    @Test
    fun `a sheet whose serials are out of order still prints the solos in roll order`() {
        // Before any family is made, every person is their own block and the only ordering
        // nobody had to choose is the roll's -- so the jumbled sheet must still come out
        // in serial order. This is the state the app is in for the hour between importing
        // and making the first household, and a jumbled walk across the ward that hour is
        // exactly what the serial-order rule exists to prevent.
        val fresh = BoothImporter.create(meta, readSheet(), 1L).booth
        val blocks = PrintOrder.blocks(Survey(fresh, Lists()))

        assertTrue("everybody is their own block until a family is made", blocks.all { it.isSolo })
        assertEquals(
            "the sheet handed over with the serials jumbled, printed in roll order",
            listOf(1, 2, 3, 4, 5, 6),
            blocks.map { it.members.first().serial },
        )
        assertEquals(listOf(1, 2, 3, 4, 5, 6), serialsOf(exported(fresh)))
    }

    // --- both exports together ------------------------------------------------

    /**
     * One checkbox, two files, one answer.
     *
     * The export sheet offers "people still to visit only" and feeds it to the spreadsheet
     * and the booklet alike. When the two read that question separately they printed
     * different streets, and nothing on screen said so. This asserts the whole chain: the
     * booth is filtered by [Booth.forPendingOnly], the spreadsheet drops the visited, and
     * the booklet's block filter drops the household only when it is *wholly* done.
     */
    @Test
    fun `still-to-visit leaves the same people in both files`() {
        val worked = worked()
        val pending = worked.forPendingOnly()

        assertEquals(
            listOf(4, 3, 5, 2, 6),
            serialsOf(exported(pending)),
        )

        val pages = PdfExporter.layout(PrintOrder.blocks(Survey(pending)), true).first
        val printed = pages.flatMap { page -> page.flatMap { r -> (r.from until r.to).map { r.block.members[it].serial } } }
        assertEquals(
            "the booklet must describe the same street as the spreadsheet",
            listOf(4, 3, 5, 2, 6),
            printed,
        )
    }

    /**
     * A partly-worked house stays whole in the booklet.
     *
     * This is the promise that makes a stapled booklet worth stapling. HEMA LATHA has been
     * called at and SAI KIRAN has not, so their door is one visit with one form still blank
     * on it -- not a finished household and not a house to be struck off.
     *
     * Asserted on the booth as it stands rather than on the pending slice, because the
     * pending slice has by definition lost the visited half. What matters is that the full
     * export carries both people under one household and the household is not "finished".
     */
    @Test
    fun `a house with anyone left in it is printed whole`() {
        val block = PrintOrder.blocks(Survey(worked())).first { it.familyId == "f1" }

        assertEquals(
            "one visited and one not is still one household with two people in it",
            listOf(1, 2),
            block.members.map { it.serial },
        )
        assertTrue("one of two knocked at is not a finished house", !block.isFinished())

        val rows = exported(worked()).filter { it["Family"] == "HEMA LATHA family" }
        assertEquals("both members, and nobody else", listOf("1", "2"), rows.map { it["Serial"] })
    }

    /**
     * The booklet's own order, read off the pages rather than off the blocks.
     *
     * The layout is the last thing between the app's ordering and the paper, and it is the
     * only stage that can drop, repeat or split a person. Asserted against the arranged
     * order, in full, including the household that runs over a page break.
     */
    @Test
    fun `the booklet prints the arranged people once each, in order`() {
        val pages = PdfExporter.layout(PrintOrder.blocks(Survey(worked())), false).first
        val printed = pages.flatMap { page -> page.flatMap { r -> (r.from until r.to).map { r.block.members[it].serial } } }

        assertEquals(
            "everybody, once, in the order the surveyor arranged",
            listOf(4, 3, 5, 1, 2, 6),
            printed,
        )
        assertEquals(
            "no page may hold a person twice",
            printed.size,
            printed.toSet().size,
        )
    }

    /**
     * Page breaks do not reshuffle the households, and no household is torn in two.
     *
     * A round trip of six people fits on one sheet, so this uses a booth big enough to run
     * over the fifteen-row page: six households of three. What it is checking is the last
     * stage, the layout, because that is the only place left where the order can still be
     * changed -- a block could come out in a different order on paper than it had in the
     * app, or a member could appear on a different sheet from the rest of the household,
     * and both would be invisible from inside the app.
     *
     * Each household's members are contiguous and in the surveyor's order, and the
     * households come out in the walking order.
     */
    @Test
    fun `page breaks keep the households whole and in the walking order`() {
        val big = (0 until 6).flatMap { h ->
            (1..3).map { m ->
                Voter(
                    id = "h${h}m$m",
                    serial = h * 3 + m,
                    name = "HOUSE${h} PERSON$m",
                    house = "10-1-$h",
                    familyId = "fam$h",
                    familyOrder = m - 1,
                )
            }
        }.let { voters ->
            Booth(
                id = "b1",
                boothNo = "108",
                voters = voters,
                families = (0 until 6).map { Family("fam$it", it) },
            )
        }.movingFamilyTo("fam5", 0)

        val pages = PdfExporter.layout(PrintOrder.blocks(Survey(big, Lists())), false).first
        val onPaper = pages.flatMap { page ->
            page.flatMap { row -> (row.from until row.to).map { row.block.members[it] } }
        }

        assertTrue("eighteen people must run over a fifteen-row page", pages.size >= 2)
        assertEquals("everybody, once", 18, onPaper.size)
        assertEquals(
            "the households come out in the walking order the surveyor set",
            big.familiesWithMembers.map { it.family.id },
            pages.flatMap { page -> page.map { it.block.familyId } }.distinct(),
        )

        // Each household's people are together and in order, wherever the break fell.
        val byFamily = onPaper.groupBy { it.familyId }
        for (fwm in big.familiesWithMembers) {
            val got = byFamily[fwm.family.id].orEmpty()
            assertEquals(
                "household ${fwm.family.id} keeps its three people",
                fwm.members.map { it.id },
                got.map { it.id },
            )
        }
    }

    // --- the names -----------------------------------------------------------

    /**
     * Telugu text and a very long name survive the whole pipeline.
     *
     * Both were chosen for the same reason. Telugu is what most of the names on this ward's
     * roll are written in, so a reader or a writer that mangled non-Latin text would lose
     * the name of most of the ward. The long one is the other end: the app makes no promises
     * about length, so the pipeline has to carry whatever it is given rather than truncate
     * a name half-way and print a different person.
     */
    @Test
    fun `non-latin text and a very long name come through unchanged`() {
        val telugu = "శ్రీమతి వెంకటేశ్వర్లవ"
        val long = "VENKATARAMAIAH UDAYA BHUPATHI PATEL MANUKONDA RANGAREDDY"
        val rows = listOf(
            XlsxReader.Row(serial = 1, voterId = "WQQ0000001", name = telugu, house = "10-1-1"),
            XlsxReader.Row(serial = 2, voterId = "WQQ0000002", name = long, house = "10-1-2"),
        )
        val booth = BoothImporter.create(meta, rows, 1L).booth

        val out = exported(booth)
        assertEquals(telugu, out[0]["Full Name"])
        assertEquals(long, out[1]["Full Name"])
        assertEquals("nothing may be trimmed on the way", listOf(telugu, long), booth.voters.map { it.name })
    }

    // --- idempotence ---------------------------------------------------------

    /**
     * Importing the same sheet a second time changes nothing a person can see.
     *
     * The surveyor's progress is the thing that must not move: who has been called at, who
     * has been skipped, who has been put in which house and in what order. Re-import is the
     * routine way a booth gets refreshed when the electoral office sends a corrected roll,
     * and this is the run where a surveyor has already done a morning's work.
     */
    @Test
    fun `re-importing the sheet keeps the marks, the houses and the order`() {
        val first = worked()
        val again = BoothImporter.merge(first, meta, readSheet(), 2L).booth

        assertEquals("nobody may be added or lost", first.voters.size, again.voters.size)
        assertTrue("nobody may be marked as struck off by the sheet they came from",
            again.voters.none { it.notInLatestFile })

        assertEquals(
            "the marks the surveyor made",
            first.voters.map { it.id to (it.visited to it.skipped) }.toSet(),
            again.voters.map { it.id to (it.visited to it.skipped) }.toSet(),
        )
        assertEquals(
            "the households, in the arranged order",
            first.families.sortedBy { it.order }.map { it.id },
            again.families.sortedBy { it.order }.map { it.id },
        )
        assertEquals(
            "and the arrangement inside each of them",
            first.voters.map { it.id to (it.familyId to it.familyOrder) }.toSet(),
            again.voters.map { it.id to (it.familyId to it.familyOrder) }.toSet(),
        )
        assertEquals(
            "the printed booklet must come out the same both times",
            serialsOf(exported(first)),
            serialsOf(exported(again)),
        )
    }

    private fun zipPart(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name == name) return zip.readBytes().toString(Charsets.UTF_8)
                e = zip.nextEntry
            }
        }
        throw AssertionError("no part called $name")
    }
}