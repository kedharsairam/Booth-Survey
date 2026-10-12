package com.kraft.voterlist.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.kraft.voterlist.data.PrintOrder
import com.kraft.voterlist.data.Voter
import java.io.OutputStream

/**
 * Writes the survey booklet: the MLA's own form, A4 landscape, on folded and stapled sheets.
 *
 * The geometry here is not designed, it is copied. Every number below is read out of
 * `samples/tabletemplate/formtemplate.original.docx` -- the column grid from its `w:tblGrid`,
 * the row heights from its `w:trHeight`, the heading row's cell spans from its `w:gridSpan`,
 * and the margins from its `w:pgMar`. PdfDocument works in points at 72 per inch and Word
 * works in twips at 1440 per inch, so a twip is a twentieth of a point:
 *
 *   page          16838 x 11906 twips -> 841.89 x 595.28 pt   (A4 landscape)
 *   page margin    720 twips         ->  36.00 pt
 *   table indent   272 twips         ->  13.60 pt, so the table's left edge is 49.60 pt
 *   columns         709/1276/3969/709/1701, ten of 283, 1417/567/1134/709 twips
 *   heading row 1   556 twips, drawn at 28.30 pt
 *   heading row 2   840 twips, drawn at 42.50 pt
 *   data row        557 twips, drawn at 28.35 pt, fifteen of them
 *
 * Because the numbers come from the form rather than from taste, changing the form is a change
 * to this list and nothing else: if the MLA widens the "Door no." column, the number to edit
 * is the one the document already states.
 *
 * The first row is the form's own: "Mandal" over columns 1-2 with its value in column 3,
 * "Town / Ward" over columns 4-5 with its value in the ten phone columns, and "Booth No." over
 * column 16 with its value in the last three. Those six cells span 2+1+2+10+1+3 = 19, the
 * full width of the table. The values are the booth's own -- the mandal, town or ward and booth
 * number the surveyor gave when they imported the roll -- so a booklet prints the ward it
 * belongs to rather than a constant.
 *
 * Uses only android.graphics.pdf.PdfDocument. No library, and no storage permission -- the
 * caller supplies the stream, which the system hands us after the user has picked a
 * destination.
 */
object PdfExporter {

    // ---- the form's geometry, in points ------------------------------------------------

    private val PAGE_W = 841.89f
    private val PAGE_H = 595.28f
    private val MARGIN = 36.00f
    private val TABLE_X = MARGIN + 13.60f

    /**
     * The table does not start at the top margin.
     *
     * The page margin is 720 twips all round, but the form's table top rule sits 27pt below
     * the top of the sheet, so the table hangs from an origin of its own. Drawing from the
     * margin put every booklet 27pt higher on the page than the form it is filled in against,
     * which is 9.5mm of the fifteen rows sitting in the wrong place on the paper.
     */
    private val TABLE_Y = PAGE_H - 559.0f

    /** The form's nineteen leaf columns, in its order. */
    private val COL_PT = listOf(
        35.45f, 63.80f, 198.45f, 35.45f, 85.05f,
        14.15f, 14.15f, 14.15f, 14.15f, 14.15f, 14.15f, 14.15f, 14.15f, 14.15f, 14.15f,
        70.85f, 28.35f, 56.70f, 35.45f,
    )
    private val TABLE_W: Float = COL_PT.sum()

    // The row heights are the ones the form's own PDF actually draws, which are taller than the
    // `w:trHeight` the document declares: 556/840/557 twips are *minimums*, and the renderer
    // opened each row to fit its content. Measured off formtemplate.original.pdf the rows are
    // 28.30, 42.50 and 28.35 pt, which puts the table's bottom edge 63pt from the foot of the
    // sheet. Using the declared minimums instead left the table 8pt short of where the form
    // puts it, so a printed booklet sat 8pt higher than the paper it is filled in against.
    private val ROW1_H = 28.30f
    private val ROW2_H = 42.50f
    private val ROW_H = 28.35f
    private const val ROWS_PER_PAGE = 15
    private const val PAGES_PER_BOOKLET = 4

    // ---- the form's type sizes --------------------------------------------------------

    /** Row 1 is set at sz=24 half-points and row 2 at sz=20; both are bold in the template. */
    private const val ROW1_PT = 12f
    private const val HEAD_PT = 10f

    /**
     * Data rows carry no size of their own and would inherit the document's 11pt default, which
     * does not fit: "KONDAPALLI" measures 23.3 mm in a 22.51 mm column, and a 13-character
     * address overruns 24.99 mm. 10pt fits both, so the printed value is 10pt shrunk to fit
     * anything longer still. Nothing on the page is set below the heading size.
     */
    private const val DATA_PT = 10f

    private const val LINE_SPACING = 13.6f

    /**
     * The template's headings fill their cells edge to edge -- "Caste" in a 28.35pt column
     * and "Others" in a 35.45pt one -- so their padding is a hair, not the 5.4pt the table
     * style declares for the empty body cells. Data keeps a real margin, because a printed
     * value in a hand-filled box needs breathing room.
     */
    private const val HEAD_PAD = 0.5f
    private const val DATA_PAD = 2.5f

    /** The template's borders are w:sz="4", four eighths of a point, on every edge. */
    private const val BORDER_PT = 0.5f

    // ---- leaf columns -----------------------------------------------------------------

    private const val PHONE_DIGITS = 10
    private const val C_FAMILY = 0
    private const val C_DOOR = 1
    private const val C_NAME = 2
    private const val C_SERIAL = 3
    private const val C_VOTER = 4
    private const val C_PHONE = 5
    private const val C_ADDR = C_PHONE + PHONE_DIGITS
    private const val C_CASTE = C_ADDR + 1
    private const val C_SUBCASTE = C_ADDR + 2
    private const val C_PARTY = C_ADDR + 3

    private enum class Align { LEFT, CENTRE, RIGHT }

    data class Options(
        val mandal: String = "",
        val townWard: String = "",
        val boothNo: String = "",
        val onlyPending: Boolean = false,
    )

    /**
     * One printed row: a block of voters, and the slice of it that fits on this page.
     *
     * A block larger than the room left occupies several rows across consecutive pages, so
     * the row carries a range rather than a single voter.
     *
     * The "Family sr.no." printed beside a member is their place in their own household, which is
     * simply their index in the block: the first family reads 1, 2, 3, 4 and the next starts
     * again at 1. It is not stored on the row, because a row spans a *range* of members and
     * one number cannot be right for all of them -- that was the bug, printing 1 beside all
     * four members of the first house and 2 beside all three of the second.
     */
    data class Row(val block: PrintOrder.Block, val from: Int, val to: Int)

    /**
     * Lays households out into pages, fifteen rows a page.
     *
     * Pages 1 to 3 of a booklet fill straight through and a household runs on to the next
     * page when it does not fit. Only the booklet's fourth page protects a household: one
     * that will not fit in the rows remaining moves whole to the next booklet rather than
     * being torn across two stapled books.
     *
     * Splitting on every page would guarantee sixty a booklet, but it would also put half a
     * household on one sheet and half on another for every house that crossed a boundary,
     * which is the thing a stapled booklet exists to prevent. The gaps this leaves are
     * reported, because a page with three blank rows at the bottom is a page somebody will
     * otherwise think has been missed.
     *
     * The one exception is a household larger than a whole page. It cannot be printed
     * unsplit anywhere, so it runs on and continues overleaf.
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

        // The last page of a booklet. A family that will not fit in what is left of it moves
        // whole to the next booklet, because a stapled booklet is the unit that gets carried
        // and a house split across two of them is two trips to one door.
        //
        // On the other three pages nothing is protected. They have to fill: four pages of
        // fifteen is sixty, and a booklet that refuses to split anything lands on fifty-odd
        // and leaves the surveyor short on every single booklet.
        fun onBookletEnd() = pages.size % PAGES_PER_BOOKLET == PAGES_PER_BOOKLET - 1

        for (b in blocks) {
            if (b.members.isEmpty()) continue
            // "Still to visit" skips a block only when every single member is done. A house
            // where one of four has been surveyed still has three people standing behind it,
            // and dropping it would quietly shorten the day's work.
            // !visited, to match the spreadsheet. See the note on the xlsx filter: one
            // checkbox used to mean two different sets of people.
            if (onlyPending && b.members.all { it.visited }) continue

            var at = 0
            while (at < b.members.size) {
                val left = b.members.size - at
                val room = ROWS_PER_PAGE - used

                if (room == 0) {
                    flush()
                    continue
                }

                // Fits in what is left here: take it and stop.
                if (left <= room) {
                    page += Row(b, at, b.members.size)
                    used += left
                    break
                }

                // Larger than a page. The paper forces the split; there is nowhere to move it to.
                if (left > ROWS_PER_PAGE) {
                    notes += "${b.label.ifBlank { "A family" }} has $left members, more than a page " +
                        "of $ROWS_PER_PAGE, so it runs on"
                    page += Row(b, at, at + room)
                    at += room
                    flush()
                    continue
                }

                // Small enough to fit on a page of its own, but not in the room left here. At
                // the end of a booklet it moves whole; anywhere else it runs on to the next
                // page of this same booklet, which is what keeps the count at sixty.
                if (onBookletEnd()) {
                    notes += "${b.label.ifBlank { "A family" }} has $left members and only $room " +
                        "row(s) left on page ${pages.size + 1}, so it moves whole to the next booklet"
                    flush()
                    continue
                }

                page += Row(b, at, at + room)
                at += room
                flush()
            }
        }
        if (page.isNotEmpty()) {
            notes += "last page: ${ROWS_PER_PAGE - used} row(s) unused"
            flush()
        }

        for ((i, p) in pages.withIndex()) {
            val unused = ROWS_PER_PAGE - printedRows(p)
            if (unused in 1 until ROWS_PER_PAGE) {
                notes += "page ${i + 1}: $unused row(s) unused — a family was moved whole to the next booklet"
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

        val rule = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = BORDER_PT
            color = Color.BLACK
        }
        val body = Paint().apply { color = Color.BLACK; textSize = DATA_PT }
        val head = Paint().apply {
            color = Color.BLACK
            textSize = HEAD_PT
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val row0 = Paint().apply {
            color = Color.BLACK
            textSize = ROW1_PT
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val foot = Paint().apply { color = Color.GRAY; textSize = 7f }

        pages.forEachIndexed { pageIndex, rows ->
            val info = PdfDocument.PageInfo
                .Builder(PAGE_W.toInt(), PAGE_H.toInt(), pageIndex + 1).create()
            val page = doc.startPage(info)
            val c = page.canvas

            drawRow0(c, opts, row0)
            drawHeadings(c, head)

            var y = TABLE_Y + ROW1_H + ROW2_H
            for (row in rows) {
                for (i in row.from until row.to) {
                    // The member's place in their own household, restarting at 1 for the
                    // next one -- and still right when a house runs over a page break,
                    // because it counts from the start of the block, not of the page.
                    drawRow(c, row.block.members[i], (i + 1).toString(), y, body)
                    y += ROW_H
                }
            }

            drawRules(c, rule)

            val booklet = pageIndex / PAGES_PER_BOOKLET + 1
            val inBooklet = pageIndex % PAGES_PER_BOOKLET + 1
            val booklets = (pages.size + PAGES_PER_BOOKLET - 1) / PAGES_PER_BOOKLET
            c.drawText(
                "booklet $booklet of $booklets — page $inBooklet of $PAGES_PER_BOOKLET",
                TABLE_X, PAGE_H - MARGIN + 14f, foot,
            )

            doc.finishPage(page)
        }

        doc.writeTo(out)
        doc.close()
    }

    /**
     * The form's first row, cell for cell: label over the columns it labels, value in the
     * cells that follow, in the template's own alignments -- Mandal and Booth No. left,
     * Town / Ward centred, because that is what its `w:jc` says in each of those three cells.
     *
     * The three value cells are wide -- 198.45pt, 141.50pt and 120.50pt -- so a real mandal
     * and ward fit without shrinking.
     */
    private fun drawRow0(c: Canvas, opts: Options, p: Paint) {
        val top = TABLE_Y
        // (label, its first leaf column, how many columns it spans, value, how many the
        // value spans, alignment)
        //
        // Both spans are the template's own: 2+1, 2+10, 1+3. The value's span matters as much
        // as the label's -- sizing the value cell as "everything to the right of here" ran
        // Town / Ward's value across the rest of the table and centred it on top of
        // "Booth No.".
        val cells = listOf(
            Row0Cell("Mandal", 0, 2, opts.mandal, 1, Align.CENTRE),
            Row0Cell("Town / Ward", 3, 2, opts.townWard, PHONE_DIGITS, Align.CENTRE),
            Row0Cell("Booth No.", 15, 1, opts.boothNo, 3, Align.CENTRE),
        )
        for (q in cells) {
            drawCell(
                c, q.label, TABLE_X + offset(q.leaf), spanWidth(q.leaf, q.span), top, ROW1_H,
                p, ROW1_PT, Align.CENTRE,
            )
            val vx = q.leaf + q.span
            drawCell(
                c, q.value, TABLE_X + offset(vx), spanWidth(vx, q.valueSpan), top, ROW1_H,
                p, ROW1_PT, q.align,
            )
        }
    }

    /** label, its first leaf column, the label's span, value, the value's span, alignment. */
    private data class Row0Cell(
        val label: String,
        val leaf: Int,
        val span: Int,
        val value: String,
        val valueSpan: Int,
        val align: Align,
    )

    /**
     * The form's second row: ten headings, with "Phone Number" over its ten single-digit
     * columns exactly as the form merges them.
     *
     * The headings wrap on their own words rather than being set as single lines. That is not
     * a nicety: "Voter List sr.no." is 70pt of text in a 35.45pt column, so set on one line it
     * can only be made to fit by shrinking to about 5pt. Wrapped, it takes three lines in the
     * 42pt row the form gives it -- "Voter" / "List" / "sr.no." -- which is what the form
     * itself prints.
     */
    private fun drawHeadings(c: Canvas, p: Paint) {
        val top = TABLE_Y + ROW1_H
        val heads = listOf(
            "Family sr.no." to 1, "Door no." to 1, "Full Name" to 1,
            "Voter List sr.no." to 1, "Voter ID no." to 1,
            "Phone Number" to PHONE_DIGITS, "Current Address" to 1,
            // The last heading prints as "Others", which is what the MLA's own form says there.
            // The app still calls the field Party, because that is what it is -- this is only
            // the heading on the paper, matched to the form it has to sit beside.
            "Caste" to 1, "Sub Caste" to 1, "Others" to 1,
        )
        var leaf = 0
        for ((label, span) in heads) {
            val w = spanWidth(leaf, span)
            drawWrapped(c, label, TABLE_X + offset(leaf), w, top, ROW2_H, p, Align.CENTRE)
            leaf += span
        }
    }

    /**
     * One person's row.
     *
     * The cells are centred as the template centres them, with two exceptions, both of them
     * deliberate departures from the template because the surveyor asked for them.
     *
     * "Sub Caste" is the form's own: it carries no `w:jc`, so it prints flush left.
     *
     * "Full Name" is centred in the blank template because there is nothing in it yet. With
     * thirty Telugu names down the column, centring makes every name start at a different
     * point and the column reads as a ragged edge rather than a list. Names are set flush left
     * so they line up under the heading -- which stays centred, as the form has it, since a
     * heading labels the column and does not belong to the run of names beneath it.
     */
    private fun drawRow(c: Canvas, v: Voter, familyLabel: String, y: Float, p: Paint) {
        fun at(col: Int, value: String, align: Align = Align.CENTRE) {
            drawCell(c, value, TABLE_X + offset(col), COL_PT[col], y, ROW_H, p, DATA_PT, align)
        }
        at(C_FAMILY, familyLabel)
        at(C_DOOR, v.house)
        at(C_NAME, v.name, Align.LEFT)
        at(C_SERIAL, v.serial.toString())
        at(C_VOTER, v.voterId)
        // One digit per cell, so a number cannot straddle a boundary.
        val digits = v.phone.filter { it.isDigit() }.take(PHONE_DIGITS).padEnd(PHONE_DIGITS)
        for (i in 0 until PHONE_DIGITS) at(C_PHONE + i, digits[i].toString())
        at(C_ADDR, v.address)
        at(C_CASTE, v.caste)
        at(C_SUBCASTE, v.subCaste, Align.LEFT)
        at(C_PARTY, v.party)
    }

    /** Offset of a leaf column from the table's left edge, in points. */
    private fun offset(leaf: Int): Float = COL_PT.subList(0, leaf).sum()

    /** Width of [span] leaf columns starting at [leaf], in points. */
    private fun spanWidth(leaf: Int, span: Int): Float =
        COL_PT.subList(leaf, leaf + span).sum()

    /**
     * A single line of text, vertically centred in its cell and shrunk rather than spilled.
     *
     * Spilling is what put "Phone Number" on top of "Voter ID no." in the earlier version:
     * the text was wider than the columns it spanned and centring does not clip, so it
     * printed wherever it measured wide from. Anything that will not fit at its own size is
     * reduced until it does, down to [floorPt]; below that it is left to overrun, which only
     * happens for a value too long for the paper.
     */
    private fun drawCell(
        c: Canvas, value: String, x: Float, w: Float, top: Float, h: Float, p: Paint,
        size: Float, align: Align, floorPt: Float = 5.5f,
    ) {
        if (value.isEmpty() || w <= 0f) return
        p.textSize = size
        val avail = w - 2 * DATA_PAD
        fit(p, value, avail, size, floorPt)
        val fm = p.fontMetrics
        val baseline = top + h / 2f - (fm.ascent + fm.descent) / 2f
        c.drawText(value, alignX(p, value, x, w, align, DATA_PAD), baseline, p)
        p.textSize = size
    }

    /**
     * A heading, wrapped on its words and centred as a block in the row, which is how the
     * form sets them. If a single word is still too wide for its column -- "Family" in a
     * 35.45pt one -- the whole block shrinks together, so the lines stay the same size as
     * each other.
     */
    private fun drawWrapped(
        c: Canvas, value: String, x: Float, w: Float, top: Float, h: Float, p: Paint,
        align: Align,
    ) {
        if (value.isEmpty() || w <= 0f) return
        p.textSize = HEAD_PT
        val avail = w - 2 * HEAD_PAD
        // A heading that still will not fit is left at this size and allowed to reach its
        // neighbour rather than shrunk to illegibility: the template sets "Family" and
        // "Caste" to fill their columns exactly, and a 6pt "Family sr.no." is a worse
        // answer than a full-size one that touches the rule.
        val floorPt = 8f
        var size = HEAD_PT
        var lines = wrap(value, p, avail)
        while (size > floorPt && lines.any { p.measureText(it) > avail }) {
            size -= 0.25f
            p.textSize = size
            lines = wrap(value, p, avail)
        }
        val fm = p.fontMetrics
        // Centre the block of lines in the row.
        //
        // The block's height is the span between the first and last baselines plus the ink
        // above the first and below the last -- ascent-to-descent once, not once per line,
        // which would count the leading between lines twice. The first baseline then sits one
        // ascent *below* the block's top edge, and ascent is negative, so both terms subtract.
        //
        // Getting either sign wrong shifts the whole block: with the block added instead of
        // subtracted, and the ascent added, every heading sat 7pt above the middle of its row,
        // and the three-line "Voter / List / sr.no." hung below the rule and printed over the
        // first household. Measured against the form: the template centres a one-line heading
        // with 17.7pt above it and 17.3pt below.
        val block = (lines.size - 1) * LINE_SPACING + (fm.descent - fm.ascent)
        var baseline = top + (h - block) / 2f - fm.ascent
        for (line in lines) {
            c.drawText(line, alignX(p, line, x, w, align, HEAD_PAD), baseline, p)
            baseline += LINE_SPACING
        }
        p.textSize = HEAD_PT
    }

    /** Breaks a heading into lines no wider than [avail], on word boundaries. */
    private fun wrap(s: String, p: Paint, avail: Float): List<String> {
        val words = s.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val lines = mutableListOf(words[0])
        for (w in words.drop(1)) {
            val last = lines.last()
            if (p.measureText("$last $w") <= avail) lines[lines.size - 1] = "$last $w" else lines += w
        }
        return lines
    }

    /** Reduces [p] until [value] fits [avail], or the size reaches [floorPt]. */
    private fun fit(p: Paint, value: String, avail: Float, start: Float, floorPt: Float) {
        var size = start
        while (size > floorPt && p.measureText(value) > avail) {
            size -= 0.25f
            p.textSize = size
        }
    }

    private fun alignX(p: Paint, s: String, x: Float, w: Float, align: Align, pad: Float): Float =
        when (align) {
            Align.LEFT -> x + pad
            Align.RIGHT -> x + w - pad - p.measureText(s)
            Align.CENTRE -> x + (w - p.measureText(s)) / 2f
        }

    /**
     * The grid, always all fifteen data rows.
     *
     * The form is a pre-printed sheet of empty boxes, so a page holding three households still
     * carries fifteen rows: the surveyor writes the rest in by hand. Drawing the grid only as
     * far as the last household stopped would hand out a sheet that cannot be filled.
     *
     * The verticals are drawn one line per leaf edge, which is what makes the phone column
     * read as ten separate cells -- but only where the form actually has a rule. Each heading
     * row merges its cells, and a line drawn through a merged cell cuts it in two: the earlier
     * version ran every leaf edge from the table's top, which put a rule straight down the
     * middle of "Mandal" (a label spanning two columns) and sliced the town name and the
     * "Phone Number" heading into ten pieces.
     */
    private fun drawRules(c: Canvas, p: Paint) {
        val tableBottom = TABLE_Y + ROW1_H + ROW2_H + ROWS_PER_PAGE * ROW_H
        val headBottom = TABLE_Y + ROW1_H + ROW2_H
        val row0Bottom = TABLE_Y + ROW1_H

        // Row 1's cells, as leaf-column spans: the three labels, the three values, and the
        // table's own edges. Its internal rules are only at the ends of those six cells.
        val row0Edges = setOf(0, 2, 3, 5, 15, 16)

        var x = TABLE_X
        for (i in COL_PT.indices) {
            // Every edge reaches the bottom of the data rows.
            c.drawLine(x, headBottom, x, tableBottom, p)
            // And up through the heading rows only where the form draws a cell boundary.
            if (i in row0Edges) {
                c.drawLine(x, TABLE_Y, x, row0Bottom, p)
            }
            // Row 2 keeps every edge except those strictly inside the ten merged phone
            // columns. The merged cell's own two boundaries are kept: dropping them too
            // left "Phone Number" and the voter id column running together with no rule
            // between them.
            if (i <= C_PHONE || i >= C_ADDR) {
                c.drawLine(x, row0Bottom, x, headBottom, p)
            }
            x += COL_PT[i]
        }
        c.drawLine(x, TABLE_Y, x, tableBottom, p)

        c.drawLine(TABLE_X, TABLE_Y, x, TABLE_Y, p)
        c.drawLine(TABLE_X, TABLE_Y + ROW1_H, x, TABLE_Y + ROW1_H, p)
        c.drawLine(TABLE_X, headBottom, x, headBottom, p)
        var y = headBottom
        repeat(ROWS_PER_PAGE) {
            y += ROW_H
            c.drawLine(TABLE_X, y, x, y, p)
        }
    }
}