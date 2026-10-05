package com.kraft.voterlist.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.kraft.voterlist.data.PrintOrder
import com.kraft.voterlist.data.Voter
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * Writes the survey booklet: the MLA's form, A4 landscape, on folded and stapled sheets.
 *
 * The geometry is the form's own, measured from the template rather than chosen. Nineteen
 * leaf columns totalling 265.01 mm across a 297 mm page, with 12.7 mm margins, is what the
 * document already declares -- so what is printed here is what the form describes, and a
 * change to the form is a change to these numbers rather than a redesign.
 *
 * Page geometry, in the units PdfDocument uses (72 per inch):
 *   A4 landscape          297 x 210 mm  ->  841.89 x 595.28 pt
 *   margin                12.7 mm       ->  36.00 pt
 *   heading row 1         9.81 mm
 *   heading row 2         14.82 mm
 *   data row              9.82 mm
 *   15 data rows a page, 4 pages a booklet, 60 people a booklet
 *
 * The phone number is drawn as ten separate boxes because that is what the form has: ten
 * single-digit cells, so a number cannot be written across a cell boundary and read
 * ambiguously later.
 *
 * Uses only android.graphics.pdf.PdfDocument. No library, and no storage permission --
 * the caller supplies the stream, which the system hands us after the user has picked a
 * destination.
 */
object PdfExporter {

    private const val MM = 72f / 25.4f

    private val PAGE_W = 297f * MM
    private val PAGE_H = 210f * MM
    private val MARGIN = 12.7f * MM

    // Leaf column widths in millimetres, in the form's order: family serial, door number,
    // full name, voter list serial, voter id, ten phone digits, current address, caste,
    // sub caste, and "others".
    private val COL_MM = listOf(
        12.51f, 20.00f, 67.50f, 12.49f, 35.01f,
        4.99f, 4.99f, 4.99f, 4.99f, 4.99f, 4.99f, 4.99f, 4.99f, 4.99f, 5.08f,
        24.98f, 10.00f, 20.00f, 12.51f,
    )
    private val COL_PT = COL_MM.map { it * MM }

    private const val PHONE_DIGITS = 10
    private val C_FAMILY = 0
    private val C_DOOR = 1
    private val C_NAME = 2
    private val C_SERIAL = 3
    private val C_VOTER = 4
    private val C_PHONE = 5
    private val C_ADDR = C_PHONE + PHONE_DIGITS
    private val C_CASTE = C_ADDR + 1
    private val C_SUBCASTE = C_ADDR + 2
    private val C_PARTY = C_ADDR + 3

    private val ROW1_H = 9.81f * MM
    private val ROW2_H = 14.82f * MM
    private val ROW_H = 9.82f * MM
    private const val ROWS_PER_PAGE = 15
    private const val PAGES_PER_BOOKLET = 4

    // Header values are per booth and supplied by the caller, never hardcoded. They come from
    // the booth the surveyor imported, because the app is not tied to one constituency and a
    // constant here would print the wrong ward on somebody else's booklet.

    private val HEADING_PT = 8f
    private val DATA_PT = 10f
    // Two columns cannot hold a typical value at 10 pt: measured against this form,
    // "KONDAPALLI" needs 23.3 mm in a 20.00 mm column, and a 13-character address needs
    // more than 24.98 mm. 8 pt is also what the template uses for its own headings, so
    // nothing on the page is set below it.
    private val ADDRESS_PT = 9f
    private val SUBCASTE_PT = 8f

    data class Options(
        val mandal: String = "",
        val townWard: String = "",
        val boothNo: String = "",
        val onlyPending: Boolean = false,
    )

    /**
     * One printed row: a block of voters, and the slice of it that fits on this page.
     *
     * A block larger than a page occupies several rows across consecutive pages, so the row
     * carries a range rather than a single voter. That is what stops a family being split:
     * the layout hands out whole ranges and never hands out a partial one.
     *
     * [familyNo] is the "Family sr.no." printed beside every member of the block. It numbers
     * families in print order and restarts at 1 in each booklet, so the number on the page
     * matches the page it is folded into.
     */
    data class Row(val block: PrintOrder.Block, val from: Int, val to: Int, val familyNo: Int)

    /**
     * Lays households out into pages without splitting a household.
     *
     * A household that will not fit in the rows left on the current page moves to the
     * next page whole, and the rows below it are left empty. Those gaps are reported,
     * because a page with three blank rows at the bottom is a page somebody will
     * otherwise think has been missed.
     *
     * The one exception is a household larger than a whole page. It cannot be printed
     * unsplit, so it runs on and continues overleaf, and that is the only case in which
     * a household appears on more than one page. Splitting a five-person house to avoid
     * a gap is the wrong trade; splitting a thirty-person house is forced by the paper.
     *
     * The earlier version of this took a partial slice whenever a household ran past the
     * bottom of the page, which split ordinary households routinely. The unit test missed
     * it because it checked that a household's rows were contiguous, which a split
     * household satisfies -- it checked for adjacency and called it wholeness.
     */
    fun layout(
        blocks: List<PrintOrder.Block>,
        onlyPending: Boolean,
    ): Pair<List<List<Row>>, List<String>> {
        val pages = mutableListOf<MutableList<Row>>()
        val notes = mutableListOf<String>()
        var page = mutableListOf<Row>()
        var used = 0

        fun flush() {
            pages += page
            page = mutableListOf()
            used = 0
        }

        for (b in blocks) {
            if (b.members.isEmpty()) continue
            // "Still to visit" skips a block only when every single member is done. A house
            // where one of four has been surveyed still has three people standing behind it,
            // and dropping it would quietly shorten the day's work.
            if (onlyPending && b.members.all { it.isDone }) continue
            val n = b.members.size

            if (n <= ROWS_PER_PAGE) {
                // Fits on a page of its own: never start it unless the whole of it fits.
                if (used + n > ROWS_PER_PAGE) flush()
                page += Row(b, 0, n, 0)
                used += n
            } else {
                // Larger than a page. Unavoidably continued overleaf.
                notes += "${b.label.ifBlank { "A family" }} has $n members, more than a page " +
                    "of $ROWS_PER_PAGE, so it runs on"
                var at = 0
                while (at < n) {
                    if (used == ROWS_PER_PAGE) flush()
                    val take = minOf(n - at, ROWS_PER_PAGE - used)
                    page += Row(b, at, at + take, 0)
                    at += take
                    used += take
                }
            }
        }
        if (page.isNotEmpty()) {
            notes += "last page: ${ROWS_PER_PAGE - used} row(s) unused"
            flush()
        }

        // Number the families in print order, restarting each booklet. Done after layout
        // rather than during it because a family split over a page break must keep one
        // number, and only the finished page list knows where the booklet boundaries fall.
        var counter = 0
        val numbers = HashMap<String, Int>()
        for ((pageIndex, rows) in pages.withIndex()) {
            if (pageIndex % PAGES_PER_BOOKLET == 0) {
                counter = 0
                numbers.clear()
            }
            for (i in rows.indices) {
                val key = rows[i].block.familyId ?: "solo:${rows[i].block.members.first().id}"
                val n = numbers.getOrPut(key) { ++counter }
                rows[i] = rows[i].copy(familyNo = n)
            }
        }

        for ((i, p) in pages.withIndex()) {
            val unused = ROWS_PER_PAGE - printedRows(p)
            if (unused in 1 until ROWS_PER_PAGE) {
                notes += "page ${i + 1}: $unused row(s) unused \u2014 a family was moved whole to the next page"
            }
        }
        return pages to notes
    }

    /** Printed rows on a page. A Row may cover several. */
    fun printedRows(page: List<Row>): Int = page.sumOf { it.to - it.from }

    fun write(out: OutputStream, blocks: List<PrintOrder.Block>, opts: Options = Options()) {
        val (pages, _) = layout(blocks, opts.onlyPending)
        android.util.Log.i("VoterSurvey", "booklet: ${blocks.size} blocks -> ${pages.size} pages")
        val doc = PdfDocument()

        val line = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.7f
            color = Color.BLACK
        }
        val border = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.1f
            color = Color.BLACK
        }
        val text = Paint().apply { color = Color.BLACK; textSize = DATA_PT }
        val head = Paint().apply {
            color = Color.BLACK
            textSize = HEADING_PT
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val foot = Paint().apply { color = Color.GRAY; textSize = 7f }

        val tableW = COL_PT.sum()
        val tableX = MARGIN

        pages.forEachIndexed { pageIndex, rows ->
            val info = PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), pageIndex + 1).create()
            val page = doc.startPage(info)
            val c = page.canvas

            // Row 1: label, value, label, value, label, value.
            //
            // Tracked by leaf-column INDEX, never by a running point offset. The two are not
            // interchangeable: indexing the column list by the accumulated width in points
            // asks for column 35 on the second heading, because the first column is 35.47
            // points wide. That is an out-of-bounds crash on every export.
            var leaf = 0
            val r1 = listOf(
                "Mandal" to 2, opts.mandal to 1,
                "Town / Ward" to 2, opts.townWard to PHONE_DIGITS,
                "Booth No." to 1, opts.boothNo to 3,
            )
            for ((label, span) in r1) {
                c.drawText(label, tableX + leafWidth(leaf) + 3f, MARGIN + ROW1_H * 0.66f, head)
                leaf += span
            }

            // Row 2: the ten headings, phone spanning its ten digits.
            val y2 = MARGIN + ROW1_H
            val heads = listOf(
                "Family sr.no." to 1, "Door no." to 1, "Full Name" to 1,
                "Voter List sr.no." to 1, "Voter ID no." to 1,
                "Phone Number" to PHONE_DIGITS, "Current Address" to 1,
                // The last heading prints as "Others", which is what the MLA's own form says there.
                // The app still calls the field Party, because that is what it is -- this is
                // only the heading on the paper, matched to the form it has to sit beside.
                "Caste" to 1, "Sub Caste" to 1, "Others" to 1,
            )
            leaf = 0
            for ((label, span) in heads) {
                val w = COL_PT.subList(leaf, leaf + span).sum()
                drawCentred(c, label, tableX + leafWidth(leaf), w, y2 + ROW2_H * 0.62f, head, w)
                leaf += span
            }

            // Data rows.
            var y = y2 + ROW2_H
            for (row in rows) {
                for (i in row.from until row.to) {
                    // The family serial is the same for every member of a block, which is the
                    // point of it: two rows carrying the same number are the same household.
                    drawRow(c, row.block.members[i], row.familyNo.toString(), tableX, y, ROW_H, text)
                    y += ROW_H
                }
            }

            // Rules. The table is drawn one line per leaf edge, which is what makes the
            // phone column read as ten separate cells.
            drawRules(c, tableX, MARGIN, y, line, border)
            c.drawLine(tableX - 8f, y, tableX - 8f, MARGIN, border)

            val booklet = pageIndex / PAGES_PER_BOOKLET + 1
            val inBooklet = pageIndex % PAGES_PER_BOOKLET + 1
            val booklets = (pages.size + PAGES_PER_BOOKLET - 1) / PAGES_PER_BOOKLET
            c.drawText(
                "booklet $booklet of $booklets — page $inBooklet of $PAGES_PER_BOOKLET",
                tableX, PAGE_H - MARGIN + 12f, foot,
            )

            doc.finishPage(page)
        }

        doc.writeTo(out)
        doc.close()
    }

    /** Family serial is the member's position within its household, restarting at 1. */
    private fun drawRow(
        c: Canvas, v: Voter, familyLabel: String,
        x0: Float, y: Float, h: Float, text: Paint,
    ) {
        var x = x0
        fun cell(col: Int, value: String, pt: Float, centred: Boolean) {
            text.textSize = pt
            val w = COL_PT[col]
            if (centred) {
                drawCentred(c, value, x, w, y + h * 0.72f, text, w)
            } else {
                c.drawText(value, x + 2.5f, y + h * 0.72f, text)
            }
            x += w
        }
        cell(C_FAMILY, familyLabel, DATA_PT, true)
        cell(C_DOOR, v.house, DATA_PT, true)
        cell(C_NAME, v.name, DATA_PT, false)
        cell(C_SERIAL, v.serial.toString(), DATA_PT, true)
        cell(C_VOTER, v.voterId, DATA_PT, true)
        // One digit per cell, so a number cannot straddle a boundary.
        val digits = v.phone.filter { it.isDigit() }.take(PHONE_DIGITS).padEnd(PHONE_DIGITS)
        for (i in 0 until PHONE_DIGITS) cell(C_PHONE + i, digits[i].toString(), DATA_PT, true)
        cell(C_ADDR, v.address, ADDRESS_PT, true)
        cell(C_CASTE, v.caste, DATA_PT, true)
        cell(C_SUBCASTE, v.subCaste, SUBCASTE_PT, true)
        cell(C_PARTY, v.party, DATA_PT, true)
    }

    /** Offset of a leaf column from the table's left edge, in points. */
    private fun leafWidth(leaf: Int): Float = COL_PT.subList(0, leaf).sum()

    private fun drawCentred(c: Canvas, s: String, x: Float, w: Float, baseline: Float, p: Paint, limit: Float) {
        if (s.isEmpty()) return
        val avail = limit - 5f
        if (p.measureText(s) > avail) {
            // Shrink rather than wrap. A name broken across two lines inside a 9.8 mm row
            // spills into the next row, which is worse than a slightly smaller name.
            var size = p.textSize
            while (size > 5.5f && p.measureText(s) > avail) {
                size -= 0.25f
                p.textSize = size
            }
        }
        c.drawText(s, x + (w - p.measureText(s)) / 2f, baseline, p)
        p.textSize = DATA_PT
    }

    private fun drawRules(c: Canvas, x0: Float, top: Float, bottom: Float, line: Paint, border: Paint) {
        var y = top
        c.drawLine(x0, y, x0 + COL_PT.sum(), y, border)
        y += ROW1_H
        c.drawLine(x0, y, x0 + COL_PT.sum(), y, line)
        y += ROW2_H
        c.drawLine(x0, y, x0 + COL_PT.sum(), y, border)
        var x = x0
        for (w in COL_PT) {
            c.drawLine(x, top, x, bottom, line)
            x += w
        }
        x = x0 + COL_PT.sum()
        c.drawLine(x, top, x, bottom, border)
        c.drawLine(x0, bottom, x0 + COL_PT.sum(), bottom, border)
    }
}