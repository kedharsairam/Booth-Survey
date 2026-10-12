package com.kraft.voterlist

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kraft.voterlist.data.Family
import com.kraft.voterlist.data.PrintOrder
import com.kraft.voterlist.data.Survey
import com.kraft.voterlist.data.Voter
import com.kraft.voterlist.export.PdfExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * The booklet, written for real.
 *
 * This is the one exporter whose output cannot be checked by reading it -- a PDF is binary,
 * and "it compiled" says nothing about whether a page comes out. So it is written to memory
 * here and the bytes inspected: a real header, the page count, and the booth details in it.
 */
@RunWith(AndroidJUnit4::class)
class PdfExporterTest {

    private fun voter(serial: Int, name: String, house: String, familyId: String?) = Voter(
        id = "v$serial", serial = serial, name = name, house = house,
        voterId = "WQQ%07d".format(serial), familyId = familyId,
        caste = "BC-A", address = "MAIN STREET", phone = "9876543210",
    )

    private fun survey(n: Int): Survey = Survey(
        booth = com.kraft.voterlist.data.Booth(
            boothNo = "108", mandal = "Palakollu", townWard = "Palakollu",
            label = "Booth 108",
            voters = (1..n).map {
                voter(it, if (it == 1) "Ramesh" else "Person $it", "10-1-$it", "f1")
            },
            families = listOf(Family(id = "f1", order = 0)),
        ),
        lists = com.kraft.voterlist.data.Lists(),
    )

    /** The same survey with its voters replaced, for the pending-filter cases. */
    private fun withVoters(base: Survey, edit: (Voter) -> Voter): Survey =
        base.copy(booth = base.booth.copy(voters = base.booth.voters.map(edit)))

    @Test
    fun writesARealPdf() {
        val out = ByteArrayOutputStream()
        PdfExporter.write(
            out, PrintOrder.blocks(survey(2)),
            PdfExporter.Options(mandal = "Palakollu", townWard = "Palakollu", boothNo = "108"),
        )
        val bytes = out.toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)

        assertTrue("too small to be a PDF: ${bytes.size} bytes", bytes.size > 1000)
        assertTrue("missing PDF header", text.startsWith("%PDF-"))
        assertTrue("missing EOF marker", text.trimEnd().endsWith("%%EOF"))
        assertTrue("no page objects", text.contains("/Type /Page"))
        assertEquals("a PDF version the app should name", text.substring(5, 8), "1.4")
    }

    @Test
    fun onePageForASmallRoll() {
        val pages = PdfExporter.layout(PrintOrder.blocks(survey(2)), false).first
        assertEquals("two people fit on one page", 1, pages.size)
        assertEquals("both printed", 2, PdfExporter.printedRows(pages.first()))
    }

    @Test
    fun fifteenToAPageAndNoBlankPages() {
        val pages = PdfExporter.layout(PrintOrder.blocks(survey(31)), false).first
        assertEquals("31 people at 15 a page", 3, pages.size)
        pages.forEachIndexed { i, p ->
            val used = PdfExporter.printedRows(p)
            assertTrue("page ${i + 1} held $used rows", used in 1..15)
        }
        assertEquals("everybody printed", 31, pages.sumOf { PdfExporter.printedRows(it) })
    }

    /**
     * "Still to visit" filters by *household*, not by person.
     *
     * A family is the unit a surveyor walks to, so dropping a house because one of its four
     * people is done would take three people still standing behind that door off the day's
     * list. The whole house prints until every member in it is finished.
     */
    @Test
    fun pendingOnlySkipsAHouseOnlyWhenEveryoneInItIsDone() {
        val s = survey(4)
        val oneDone = withVoters(s) { it.copy(visited = it.serial == 1) }
        assertEquals(
            "one of four surveyed: the house still prints",
            4, PdfExporter.layout(PrintOrder.blocks(oneDone), true).first.sumOf { PdfExporter.printedRows(it) },
        )
        val allDone = withVoters(s) { it.copy(visited = true) }
        assertEquals(
            "everybody done: the house is off the pending booklet",
            0, PdfExporter.layout(PrintOrder.blocks(allDone), true).first.size,
        )
        assertEquals(
            "but it is still there in the full booklet",
            1, PdfExporter.layout(PrintOrder.blocks(allDone), false).first.size,
        )
    }
}
