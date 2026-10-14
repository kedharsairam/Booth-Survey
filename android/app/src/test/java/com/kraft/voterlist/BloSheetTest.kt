package com.kraft.voterlist

import com.kraft.voterlist.io.XlsxReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The sheet an electoral office actually hands out.
 *
 * Built from its shape rather than from a copy of it, because the file itself is a list of
 * named voters and that has no business being committed to a repository. The shape is what
 * matters, and it has three things a tidy list does not:
 *
 *  - a title row above the column names, so the names are not on row 1
 *  - **two** columns that look like a serial: "S.No" down the left, which is only a row
 *    counter, and "Sl No" further in, which is the number the voter is actually known by
 *  - a repeated header partway down, from being printed as pages
 *
 * Getting the second one wrong is the dangerous one. The row counter and the real serial
 * run close together, so picking the wrong column produces a sheet that looks right and
 * gives every person the wrong number -- and serial is what the printed booklet and the
 * whole walking order are built on.
 */
class BloSheetTest {

    private fun cell(ref: String, value: String, numeric: Boolean = false) =
        if (numeric) """<c r="$ref"><v>$value</v></c>"""
        else """<c t="inlineStr" r="$ref"><is><t xml:space="preserve">$value</t></is></c>"""

    private fun sheet(rows: List<String>): ByteArray {
        val body = rows.joinToString("")
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<sheetData>$body</sheetData></worksheet>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml")); z.write(xml.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("[Content_Types].xml"))
            z.write("""<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/></Types>""".toByteArray())
            z.closeEntry()
        }
        return out.toByteArray()
    }

    private fun row(n: Int, cells: List<Pair<String, String>>, numeric: List<String> = emptyList()) =
        "<row r=\"$n\">" +
            cells.joinToString("") { (col, v) -> cell("$col$n", v, col in numeric) } +
            "</row>"

    /** The real header, with the title above it and the extra columns left in place. */
    private fun bloSheet(people: List<Triple<Int, String, String>>): ByteArray {
        val rows = mutableListOf(
            row(1, listOf("A" to "VOTER LIST PART-WISE REPORT - 57 - Palacole")),
            row(2, listOf(
                "A" to "S.No", "B" to "AC No &amp; Name", "C" to "Part No", "D" to "Sl No",
                "E" to "House No", "F" to "Voter Name", "G" to "Gender",
                "H" to "Relation Name", "I" to "Age", "J" to "Status", "K" to "EPIC No",
            )),
        )
        // The row counter and the real serial are deliberately far apart, as they are in a
        // real roll where some parts are shorter than others.
        var n = 3
        people.forEachIndexed { i, (serial, epic, house) ->
            rows += row(
                n,
                listOf(
                    "A" to (i + 1).toString(), "B" to "57 - Palacole", "C" to "98",
                    "D" to serial.toString(), "E" to house,
                    "F" to "PERSON $serial", "G" to "F", "H" to "RELATIVE $serial",
                    "I" to "45", "J" to "N", "K" to epic,
                ),
                numeric = listOf("A", "C", "D", "I"),
            )
            n++
        }
        // Printed as pages, so the names come round again partway down.
        if (people.size > 3) {
            rows += row(n, listOf("A" to "S.No", "D" to "Sl No", "E" to "House No",
                "F" to "Voter Name", "K" to "EPIC No"))
        }
        return sheet(rows)
    }

    @Test
    fun `the column names are found under the title row`() {
        val xlsx = bloSheet(listOf(Triple(1, "WQQ0000001", "10-1-1"), Triple(2, "WQQ0000002", "10-1-2")))
        val r = XlsxReader.read(xlsx.inputStream())

        assertTrue("The sheet should be readable at all.", r.usable)
        assertTrue("Nothing should be reported missing.", r.missingColumns.isEmpty())
        assertEquals(2, r.rows.size)
    }

    @Test
    fun `the serial comes from Sl No and not the S No row counter`() {
        // Row counter runs 1,2,3. Real serials run 1,101,201 -- nothing like each other.
        val xlsx = bloSheet(listOf(
            Triple(1, "WQQ0000001", "10-1-1"),
            Triple(101, "WQQ0000002", "10-1-2"),
            Triple(201, "WQQ0000003", "10-1-3"),
        ))
        val r = XlsxReader.read(xlsx.inputStream())

        assertEquals(
            listOf(1, 101, 201),
            r.rows.map { it.serial },
        )
    }

    @Test
    fun `the four fields come off the right columns`() {
        val xlsx = bloSheet(listOf(
            Triple(7, "TEST0001", "90-2-2/2"),
            Triple(9, "TEST0002", "90-3-3/3"),
        ))
        val r = XlsxReader.read(xlsx.inputStream())

        assertEquals(listOf("TEST0001", "TEST0002"), r.rows.map { it.voterId })
        assertEquals(listOf("90-2-2/2", "90-3-3/3"), r.rows.map { it.house })
        assertEquals(listOf("PERSON 7", "PERSON 9"), r.rows.map { it.name })
    }

    @Test
    fun `a repeated header partway down is not imported as a person`() {
        val xlsx = bloSheet((1..6).map { Triple(it * 10, "WQQ%07d".format(it), "10-1-$it") })
        val r = XlsxReader.read(xlsx.inputStream())

        assertEquals("Six people, not six and a ghost.", 6, r.rows.size)
        assertTrue(
            "No row may be the column names.",
            r.rows.none { it.name == "Voter Name" || it.voterId == "EPIC No" },
        )
    }

    @Test
    fun `the extra columns are ignored rather than guessed at`() {
        // Gender, relation, age, status, part number and constituency are all present and
        // none of them is imported, because there is nowhere in the app to put them.
        val xlsx = bloSheet(listOf(Triple(4, "WQQ0000004", "10-1-4")))
        val r = XlsxReader.read(xlsx.inputStream())

        val v = r.rows.single()
        assertEquals(4, v.serial)
        assertEquals("WQQ0000004", v.voterId)
        assertEquals("10-1-4", v.house)
        assertEquals("PERSON 4", v.name)
    }

    @Test
    fun `a plain sheet with the names on row one still works`() {
        // The change that made the title-row sheet work must not have broken the tidy one.
        val xlsx = sheet(listOf(
            // The column letters on the left, the names they hold on the right.
            row(1, listOf("A" to "Serial", "B" to "Voter ID", "C" to "Name", "D" to "House Number")),
            row(2, listOf("A" to "5", "B" to "EPIC1", "C" to "A", "D" to "1-1"), numeric = listOf("A")),
        ))
        val r = XlsxReader.read(xlsx.inputStream())

        assertTrue("usable should be true, missing=" + r.missingColumns, r.usable)
        assertEquals(1, r.rows.size)
        assertEquals(5, r.rows.single().serial)
        assertEquals("EPIC1", r.rows.single().voterId)
    }
}
