package com.kraft.voterlist.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the sheet.
 *
 * Two things are worth pinning here. The reader has to find the four columns **by name**, not
 * by position, because a sheet whose columns happen to line up today is a sheet that imports
 * the wrong values tomorrow without a word of complaint. And it has to read a file written by
 * whichever program the volunteer happened to have, which is why the shared-string and
 * inline-string forms are both handled.
 *
 * The fixtures below are built by hand rather than by the app's own writer, so the reader is
 * being tested against the shape of a file it did not produce. Round-tripping through
 * XlsxExporter would pass even if both were wrong in the same way.
 */
class XlsxReaderTest {

    private fun sheet(
        cells: List<List<String?>>,
        useSharedStrings: Boolean = true,
        sheetFile: String = "xl/worksheets/sheet1.xml",
    ): ByteArray {
        val shared = cells.flatten().filterNotNull().distinct()
        val strings = if (useSharedStrings) {
            "<sst count=\"${shared.size}\">" + shared.joinToString("") {
                "<si><t>${esc(it)}</t></si>"
            } + "</sst>"
        } else {
            ""
        }
        val body = cells.mapIndexed { r, row ->
            val cs = row.mapIndexedNotNull { c, v ->
                if (v == null) return@mapIndexedNotNull null
                val ref = "${colName(c)}${r + 1}"
                if (useSharedStrings) {
                    "<c r=\"$ref\" t=\"s\"><v>${shared.indexOf(v)}</v></c>"
                } else {
                    "<c r=\"$ref\" t=\"inlineStr\"><is><t>${esc(v)}</t></is></c>"
                }
            }.joinToString("")
            "<row r=\"${r + 1}\">$cs</row>"
        }.joinToString("")
        val worksheet = "<?xml version=\"1.0\"?><worksheet><sheetData>$body</sheetData></worksheet>"
        val workbook = "<?xml version=\"1.0\"?><workbook><sheets><sheet name=\"Voters\" " +
            "sheetId=\"1\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
            "r:id=\"rId1\"/></sheets></workbook>"
        val rels = "<?xml version=\"1.0\"?><Relationships><Relationship Id=\"rId1\" " +
            "Target=\"$sheetFile\"/></Relationships>"
        return zip(
            "xl/workbook.xml" to workbook,
            "xl/_rels/workbook.xml.rels" to rels,
            "xl/sharedStrings.xml" to strings,
            sheetFile to worksheet,
        )
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun colName(i: Int): String = ('A' + i).toString()

    private fun zip(vararg parts: Pair<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            for ((name, body) in parts) {
                if (body.isEmpty()) continue
                z.putNextEntry(java.util.zip.ZipEntry(name))
                z.write(body.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray) =
        XlsxReader.read(java.io.ByteArrayInputStream(bytes))

    // --- the four columns ---------------------------------------------------

    @Test
    fun `reads the four columns in the order given`() {
        val r = read(
            sheet(
                listOf(
                    listOf("serial", "voter id", "name", "house number"),
                    listOf("1", "ZZZ7696263", "HEMA SRI", "96-3-3/5"),
                    listOf("2", "ZZZ7628720", "SAI LAVANYA", "94-2-2/4"),
                )
            )
        )
        assertTrue(r.missingColumns.isEmpty())
        assertEquals(2, r.rows.size)
        assertEquals(1, r.rows[0].serial)
        assertEquals("ZZZ7696263", r.rows[0].voterId)
        assertEquals("HEMA SRI", r.rows[0].name)
        assertEquals("96-3-3/5", r.rows[0].house)
        assertEquals("SAI LAVANYA", r.rows[1].name)
    }

    @Test
    fun `columns are found by name and not by position`() {
        // The same four fields in a different order, with two extra columns in between. A reader
        // that took A, B, C, D would put the roll page number where the serial belongs and
        // produce a sheet that looks imported and is entirely wrong.
        val r = read(
            sheet(
                listOf(
                    listOf("name", "serial", "voter_id", "remarks", "house_no"),
                    listOf("HEMA SRI", "7", "ZZZ7696263", "x", "96-3-3/5"),
                )
            )
        )
        assertTrue(r.missingColumns.isEmpty())
        assertEquals(1, r.rows.size)
        assertEquals(7, r.rows[0].serial)
        assertEquals("ZZZ7696263", r.rows[0].voterId)
        assertEquals("HEMA SRI", r.rows[0].name)
        assertEquals("96-3-3/5", r.rows[0].house)
    }

    @Test
    fun `header spelling is forgiven but not the fields`() {
        // Case, spaces, underscores and a missing space are all the same column.
        val r = read(
            sheet(
                listOf(
                    listOf("Serial", "VOTER_ID", "Name", "Door No."),
                    listOf("1", "ZZZ7696263", "A", "10-1-1"),
                )
            )
        )
        assertTrue(r.missingColumns.isEmpty())
        assertEquals("ZZZ7696263", r.rows[0].voterId)
        assertEquals("10-1-1", r.rows[0].house)
    }

    @Test
    fun `a missing column is named rather than silently filled`() {
        val r = read(
            sheet(
                listOf(
                    listOf("serial", "name", "house number"),
                    listOf("1", "A", "10-1-1"),
                )
            )
        )
        assertEquals(listOf("voter id"), r.missingColumns)
        assertTrue("must not be usable", !r.usable)
        assertTrue("nothing loaded", r.rows.isEmpty())
    }

    // --- shapes of file -----------------------------------------------------

    @Test
    fun `inline strings are read as well as shared ones`() {
        // OnlyOffice and LibreOffice both write inline strings rather than a shared table.
        val r = read(
            sheet(
                listOf(
                    listOf("serial", "voter id", "name", "house number"),
                    listOf("1", "ZZZ7696263", "HEMA SRI", "96-3-3/5"),
                ),
                useSharedStrings = false,
            )
        )
        assertTrue(r.missingColumns.isEmpty())
        assertEquals("ZZZ7696263", r.rows[0].voterId)
    }

    @Test
    fun `a second sheet file is found through the workbook relationships`() {
        // Excel does not reorder sheets on save but LibreOffice and OnlyOffice do, so the
        // worksheet is resolved rather than assumed to be sheet1.
        val r = read(
            sheet(
                listOf(
                    listOf("serial", "voter id", "name", "house number"),
                    listOf("1", "ZZZ7696263", "HEMA SRI", "96-3-3/5"),
                ),
                sheetFile = "xl/worksheets/sheet2.xml",
            )
        )
        assertTrue(r.missingColumns.isEmpty())
        assertEquals(1, r.rows.size)
    }

    @Test
    fun `a numeric serial stored as a number is read`() {
        // Written as <v>1</v> with no type attribute, which is what a real spreadsheet does
        // for a whole number.
        val worksheet = "<?xml version=\"1.0\"?><worksheet><sheetData>" +
            "<row r=\"1\">" +
            "<c r=\"A1\" t=\"inlineStr\"><is><t>serial</t></is></c>" +
            "<c r=\"B1\" t=\"inlineStr\"><is><t>voter id</t></is></c>" +
            "<c r=\"C1\" t=\"inlineStr\"><is><t>name</t></is></c>" +
            "<c r=\"D1\" t=\"inlineStr\"><is><t>house number</t></is></c>" +
            "</row><row r=\"2\">" +
            "<c r=\"A2\"><v>42</v></c>" +
            "<c r=\"B2\" t=\"inlineStr\"><is><t>ZZZ7696263</t></is></c>" +
            "<c r=\"C2\" t=\"inlineStr\"><is><t>HEMA SRI</t></is></c>" +
            "<c r=\"D2\" t=\"inlineStr\"><is><t>96-3-3/5</t></is></c>" +
            "</row></sheetData></worksheet>"
        val bytes = zip(
            "xl/workbook.xml" to "<?xml version=\"1.0\"?><workbook><sheets><sheet name=\"Voters\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
                "r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<?xml version=\"1.0\"?><Relationships>" +
                "<Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
            "xl/worksheets/sheet1.xml" to worksheet,
        )
        val r = read(bytes)
        assertTrue(r.missingColumns.isEmpty())
        assertEquals(42, r.rows[0].serial)
    }

    // --- what it reports ----------------------------------------------------

    @Test
    fun `blanks and unreadable serials are counted for the summary`() {
        // The import tells you what looked wrong rather than loading it quietly, because a
        // sheet that silently lost three names is a sheet you will not notice until the
        // booklet is printed.
        val r = read(
            sheet(
                listOf(
                    listOf("serial", "voter id", "name", "house number"),
                    listOf("1", "W1", "HEMA", "10-1-1"),
                    listOf("oops", "W2", "SAI", "10-1-2"),
                    listOf("3", "W3", "", ""),
                )
            )
        )
        assertEquals(3, r.rows.size)
        assertEquals(1, r.unreadableSerial)
        assertEquals(1, r.blankName)
        assertEquals(1, r.blankHouse)
    }

    @Test
    fun `a row with nothing in it is padding rather than a person`() {
        val r = read(
            sheet(
                listOf(
                    listOf("serial", "voter id", "name", "house number"),
                    listOf("1", "W1", "HEMA", "10-1-1"),
                    listOf("", "", "", ""),
                    listOf("2", "W2", "SAI", "10-1-2"),
                )
            )
        )
        assertEquals(2, r.rows.size)
    }

    @Test
    fun `an empty sheet is refused rather than loaded as nobody`() {
        val r = read(sheet(listOf(listOf("serial", "voter id", "name", "house number"))))
        assertEquals(0, r.rows.size)
        assertTrue(!r.usable)
    }
}
