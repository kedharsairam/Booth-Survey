package com.kraft.voterlist.export

import com.kraft.voterlist.data.Booth
import com.kraft.voterlist.data.Lists
import com.kraft.voterlist.data.Survey
import com.kraft.voterlist.data.Voter
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes the survey as a real .xlsx.
 *
 * Written by hand against java.util.zip rather than pulled in as a library, because an
 * .xlsx is only a zip of a few XML parts and the whole point of this app is that it holds
 * no third-party code, no permissions and no network.
 *
 * Every cell is written as an inline string except the three numeric ones, and that is the
 * entire point of this file. A CSV cannot achieve it: a house number like "98-3-3/2" is
 * text a spreadsheet is entitled to reinterpret as a date, and OnlyOffice, LibreOffice
 * and Excel all will. Quoting does not prevent it, and a leading apostrophe becomes part
 * of the value. Storing the cell as a text type in the file removes the guesswork
 * altogether. Every identifier-looking value is written as text for the same reason: a
 * door number like "98-3-3/2" and a voter ID like "ZZZ9818485" are both things a spreadsheet
 * is entitled to reinterpret, and both are join keys once this sheet is merged with anything
 * else.
 *
 * The family columns carry the arrangement the surveyor made, so the sheet and the printed
 * booklet can be checked against each other rather than trusted separately.
 */
object XlsxExporter {

    /** One column: a heading, and how to get a voter's value for it. */
    private class Cell(val get: (Voter) -> String)

    private fun col(name: String, get: (Voter) -> String) = name to Cell(get)

    /**
     * The columns, in the order they are printed on the booklet so the two can be read side
     * by side.
     *
     * Built per export rather than held as a constant because two columns need the family,
     * and the family is a property of the survey rather than of the voter.
     */
    private fun columns(families: Map<String, String>, order: Map<String, Int>) = listOf(
        col("Serial") { it.serial.toString() },
        col("Voter ID") { it.voterId },
        col("Full Name") { it.name },
        col("Door no.") { it.house },
        col("Family") { families[it.id].orEmpty() },
        col("In family order") { order[it.id]?.toString() ?: "" },
        col("Phone Number") { it.phone },
        col("Current Address") { it.address },
        col("Caste") { it.caste },
        col("Sub Caste") { it.subCaste },
        col("Party") { it.party },
        col("Visited") { if (it.visited) "Yes" else "" },
        col("Skipped") { if (it.skipped) "Yes" else "" },
    )

    /** The only columns stored as numbers, so the sheet sorts and filters as expected. */
    private val NUMERIC = setOf("Serial")

    private val WIDTHS = mapOf(
        "Voter ID" to 13, "Full Name" to 40, "Door no." to 14, "Current Address" to 22,
        "Sub Caste" to 18, "Family" to 22,
    )

    fun write(out: OutputStream, booth: Booth, lists: Lists) {
        val survey = Survey(booth, lists)
        // Family name and position within it, resolved once rather than per cell.
        //
        // Keyed by VOTER id, not by family id: the column is looked up once per voter, and
        // keying it by family id silently produced an empty cell on every row. The label is
        // worked out here rather than read off the record, for the same reason the app derives
        // it -- a stored label can fall out of step with who is first.
        val labels = survey.familiesWithMembers.associate { it.family.id to it.label }
        val families = booth.voters
            .mapNotNull { v -> v.familyId?.let { fid -> v.id to labels[fid].orEmpty() } }
            .toMap()
        val order = booth.voters.filter { it.familyId != null }.associate { it.id to it.familyOrder + 1 }

        val cols = columns(families, order)
        val rows = listOf(cols.map { it.first }) + booth.voters.map { v -> cols.map { it.second.get(v) } }

        ZipOutputStream(out).use { zip ->
            zip.put("[Content_Types].xml", contentTypes())
            zip.put("_rels/.rels", rels())
            zip.put("xl/workbook.xml", workbook())
            zip.put("xl/_rels/workbook.xml.rels", workbookRels())
            zip.put("xl/worksheets/sheet1.xml", sheet(rows, cols.map { it.first }))
        }
    }

    private fun ZipOutputStream.put(name: String, body: String) {
        putNextEntry(ZipEntry(name))
        write(body.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun esc(s: String): String = buildString(s.length) {
        for (ch in s) when {
            ch == '&' -> append("&amp;")
            ch == '<' -> append("&lt;")
            ch == '>' -> append("&gt;")
            ch == '"' -> append("&quot;")
            ch == '\'' -> append("&apos;")
            // A literal control character is not representable in XML 1.0 and Excel
            // rejects the file outright, so they go rather than being passed through.
            ch.code < 0x20 -> Unit
            else -> append(ch)
        }
    }

    private fun colName(index: Int): String {
        var n = index
        val s = StringBuilder()
        while (n >= 0) {
            s.insert(0, ('A' + (n % 26)))
            n = n / 26 - 1
        }
        return s.toString()
    }

    private fun contentTypes() =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>""".trimIndent()

    private fun rels() =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""".trimIndent()

    private fun workbook() =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="survey" sheetId="1" r:id="rId1"/></sheets>
</workbook>""".trimIndent()

    private fun workbookRels() =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>""".trimIndent()

    private fun sheet(rows: List<List<String>>, labels: List<String>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        // Header frozen: 1,129 rows is a long scroll, and losing the header is how the
        // wrong column gets filled in.
        append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        append("<cols>")
        labels.forEachIndexed { i, label ->
            val w = WIDTHS[label] ?: maxOf(9, minOf(20, label.length + 3))
            append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""")
        }
        append("</cols><sheetData>")
        rows.forEachIndexed { r, row ->
            val n = r + 1
            append("""<row r="$n">""")
            row.forEachIndexed { c, value ->
                val ref = "${colName(c)}$n"
                val numeric = r > 0 && labels[c] in NUMERIC
                if (value.isEmpty()) {
                    append("""<c r="$ref"/>""")
                } else if (numeric) {
                    append("""<c r="$ref"><v>${esc(value)}</v></c>""")
                } else {
                    append("""<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${esc(value)}</t></is></c>""")
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    /**
     * The blank template, for somebody starting a list from their own roll.
     *
     * Four columns and the four headings the importer looks for, in the order the field names
     * read most naturally. This is the whole point of the app being generic: a volunteer
     * without a sheet exports one of these, fills it from their roll, and imports it. No
     * template means the four columns have to be described in a message and reproduced from
     * memory, which is how a sheet ends up with `Sr no.` in one column and `sr_no` in another.
     */
    fun writeTemplate(out: OutputStream) {
        val rows = listOf(
            listOf("serial", "voter id", "name", "house number"),
            listOf("1", "WQQ0000000", "EXAMPLE NAME", "10-1-1"),
        )
        ZipOutputStream(out).use { zip ->
            zip.put("[Content_Types].xml", contentTypes())
            zip.put("_rels/.rels", rels())
            zip.put("xl/workbook.xml", workbook())
            zip.put("xl/_rels/workbook.xml.rels", workbookRels())
            zip.put("xl/worksheets/sheet1.xml", sheet(rows, rows.first()))
        }
    }
}
