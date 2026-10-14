package com.kraft.voterlist.io

import com.kraft.voterlist.data.Family
import com.kraft.voterlist.data.Booth
import com.kraft.voterlist.data.Voter
import com.kraft.voterlist.ui.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipOutputStream

/**
 * The edges of a sheet nobody has cleaned up.
 *
 * [BoothImporterTest] covers the cases that go wrong quietly when the sheet is *good* --
 * a renumbered roll, a blank cell, a repeated voter id. This covers the sheet itself being
 * untidy, which is the normal condition of a hand-made spreadsheet: rows in the wrong order,
 * two people carrying one number, a serial somebody typed with a comma in it, a zero-padded
 * code that is not a count.
 *
 * Each case is written as "what does the app do, and is that the right thing", because the
 * answer is not always yes. Where it is not, the test pins what actually happens and says
 * so in its name, because a defect nobody has written down is a defect somebody will
 * rediscover as a mystery.
 */
class ImportFidelityTest {

    private val meta = BoothImporter.Meta("108", "Palakollu", "4")

    private fun row(serial: Int, voterId: String = "W$serial", name: String = "PERSON $serial", house: String = "10-1-$serial") =
        XlsxReader.Row(serial, voterId, name, house)

    private fun booth(vararg v: Voter) = Booth(id = "b1", boothNo = "108", voters = v.toList())

    private fun voter(
        id: String,
        serial: Int,
        voterId: String = "W$serial",
        name: String = "PERSON $serial",
        house: String = "10-1-$serial",
    ) = Voter(id = id, serial = serial, voterId = voterId, name = name, house = house)

    // --- untidy but honest sheets -------------------------------------------

    /**
     * Two people carrying one serial number are two people.
     *
     * A roll can and does carry a repeated serial after a correction. The one thing it must
     * not do is quietly fold them into one record, which is what happens if a serial is
     * treated as an identity. Voter ID is the identity key and this checks the serial is
     * not being used as one.
     */
    @Test
    fun `two people with the same serial stay two people`() {
        val b = booth(voter("a", 7, "W1", "FIRST"), voter("b", 7, "W2", "SECOND"))
        val out = BoothImporter.merge(b, meta, listOf(row(7, "W1", "FIRST"), row(7, "W2", "SECOND")), 1L)

        assertEquals("a repeated serial must not collapse two records", 2, out.booth.voters.size)
        assertEquals(setOf("FIRST", "SECOND"), out.booth.voters.map { it.name }.toSet())
        // And the app says so rather than printing two identical numbers quietly.
        val s = UiState(booth = out.booth)
        assertTrue("a repeated serial has to be flagged where he will see it", s.isSerialRepeated(7))
    }

    /**
     * A repeated *voter id* is the clash that actually matters, and it is also flagged.
     *
     * Two records carrying one EPIC is either one person entered twice or a slip that will
     * merge two households on the final sheet. Serials repeat harmlessly; this does not.
     */
    @Test
    fun `two people with the same voter id stay two people and are flagged`() {
        val out = BoothImporter.merge(
            booth(voter("a", 1, "WQQ0000001", "FIRST"), voter("b", 2, "WQQ0000001", "SECOND")),
            meta,
            listOf(row(1, "WQQ0000001", "FIRST"), row(2, "WQQ0000001", "SECOND")),
            1L,
        )
        assertEquals(2, out.booth.voters.size)
        assertTrue(UiState(booth = out.booth).isVoterIdRepeated("WQQ0000001"))
    }

    /**
     * A sheet handed over in no particular order is stored in that order and still prints
     * in roll order.
     *
     * Both halves matter. The import deliberately does not sort what it stores -- sorting
     * would move a person's place relative to the family arrangement built on top of it --
     * but the print order must not inherit the sheet's disorder.
     */
    @Test
    fun `rows out of serial order are stored as they came and print in roll order`() {
        val rows = listOf(
            row(30, "W30", "THIRTIETH", "10-3-1"),
            row(10, "W10", "TENTH", "10-1-1"),
            row(20, "W20", "TWENTIETH", "10-2-1"),
        )
        val out = BoothImporter.create(meta, rows, 1L)

        assertEquals("the sheet's own order is kept", listOf(30, 10, 20), out.booth.voters.map { it.serial })
        assertEquals(
            "but nobody in a family yet prints in roll order",
            listOf(10, 20, 30),
            out.booth.unassigned.map { it.serial },
        )
    }

    /**
     * A blank name and a blank door number are counted and carried, not dropped.
     *
     * The person is still somebody who lives at that door and who somebody has to knock
     * on. The counts reach the surveyor as "N with no name", so the blank is reported once
     * and then worked with -- which is the difference between a known gap and a ghost.
     */
    @Test
    fun `blank names and blank door numbers are counted and the people are kept`() {
        val r = read(
            sheet(
                listOf("serial", "voter id", "name", "house number"),
                listOf("1", "W1", "HEMA", "10-1-1"),
                listOf("2", "W2", "", ""),
                listOf("3", "W3", "", "10-1-3"),
                listOf("4", "W4", "SAI", ""),
            ),
        )
        assertEquals("everybody is still a person", 4, r.rows.size)
        assertEquals(2, r.blankName)
        assertEquals(2, r.blankHouse)

        val booth = BoothImporter.create(meta, r.rows, 1L).booth
        assertEquals(
            "a blank door number does not merge two houses into one",
            4,
            com.kraft.voterlist.data.PrintOrder
                .blocks(com.kraft.voterlist.data.Survey(booth, com.kraft.voterlist.data.Lists())).size,
        )
    }

    /**
     * A serial that is not a number is counted, and the person is still imported.
     *
     * "1,024" with a comma, "N/A", "S.No" in the wrong column -- all of them read as a
     * serial of nothing and are reported in the summary. The person goes in with serial 0
     * ("no number yet") rather than being refused, because refusing them loses a household.
     */
    @Test
    fun `an unreadable serial is counted and the person is kept without a number`() {
        val r = read(
            sheet(
                listOf("serial", "voter id", "name", "house number"),
                listOf("1", "W1", "HEMA", "10-1-1"),
                listOf("1,024", "W2", "SAI", "10-1-2"),
                listOf("N/A", "W3", "RAJU", "10-1-3"),
            ),
        )
        assertEquals(3, r.rows.size)
        assertEquals("both are reported, not swallowed", 2, r.unreadableSerial)

        val booth = BoothImporter.create(meta, r.rows, 1L).booth
        assertEquals("nobody is lost over a typo in a cell", 3, booth.voters.size)
        assertEquals(listOf(1, 0, 0), booth.voters.map { it.serial })
        // Zero means "no number yet" and is never reported as a duplicate.
        assertTrue(!UiState(booth = booth).isSerialRepeated(0))
    }

    // --- numbers as text, numbers as numbers --------------------------------

    /**
     * The same serial written as text and as a number reads as the same number.
     *
     * Which one a cell happens to be depends on the program that saved the sheet, and both
     * are ordinary: Excel writes a whole number as a number, a spreadsheet built by hand
     * often keeps it as text. A reader that only understood one of them would import half
     * a booth with no serial numbers, which is a silent loss rather than a loud one.
     */
    @Test
    fun `a serial reads the same whether the cell stored it as text or as a number`() {
        val asText = read(
            sheet(
                listOf("serial", "voter id", "name", "house number"),
                listOf("1", "W1", "HEMA", "10-1-1"),
                listOf("42", "W2", "SAI", "10-1-2"),
                listOf("1204", "W3", "RAJU", "10-1-3"),
            ),
        )
        val asNumber = read(
            sheet(
                listOf("serial", "voter id", "name", "house number"),
                listOf("1", "W1", "HEMA", "10-1-1"),
                listOf("42", "W2", "SAI", "10-1-2"),
                listOf("1204", "W3", "RAJU", "10-1-3"),
                numericSerials = true,
            ),
        )
        assertEquals(listOf(1, 42, 1204), asText.rows.map { it.serial })
        assertEquals(listOf(1, 42, 1204), asNumber.rows.map { it.serial })
    }

    /**
     * A zero-padded serial arrives as the number it spells.
     *
     * The reader stores the serial as a whole number and the spreadsheet deliberately
     * writes it back as one so the office's sheet sorts. So "007" and "7" are the same
     * serial here, and a sheet that uses zero padding as a fixed-width code -- and the
     * app's own template invites people to type into it -- loses the padding.
     *
     * Pinned because it is a decision, not an accident: whoever wants padding preserved has
     * to change the serial to a string all the way through, and this test is what will tell
     * them they have.
     */
    @Test
    fun `a zero-padded serial loses its padding`() {
        val r = read(
            sheet(
                listOf("serial", "voter id", "name", "house number"),
                listOf("007", "W1", "HEMA", "10-1-1"),
                listOf("7", "W2", "SAI", "10-1-2"),
            ),
        )
        assertEquals(
            "007 and 7 are one serial, and the clash is visible rather than silent",
            listOf(7, 7),
            r.rows.map { it.serial },
        )
        assertTrue(UiState(booth = BoothImporter.create(meta, r.rows, 1L).booth).isSerialRepeated(7))
    }

    /**
     * Telugu text and a very long name come off the sheet whole.
     *
     * Nothing between the cell and the record may normalise, fold or trim: a name is the
     * only thing that tells the surveyor which person he is writing about, and most of this
     * ward's roll is not written in Latin script.
     */
    @Test
    fun `telugu text and a very long name survive the read and the import`() {
        val telugu = "శ్రీమతి వెంకటేశ్వర్లవ గౌతమ్"
        val long = "VENKATARAMAIAH UDAYA BHUPATHI PATEL MANUKONDA RANGAREDDY"
        val r = read(
            sheet(
                listOf("serial", "voter id", "name", "house number"),
                listOf("1", "W1", telugu, "10-1-1"),
                listOf("2", "W2", long, "10-1-2"),
            ),
        )
        assertEquals(listOf(telugu, long), r.rows.map { it.name })

        val booth = BoothImporter.create(meta, r.rows, 1L).booth
        assertEquals("and into the records, untrimmed", listOf(telugu, long), booth.voters.map { it.name })
    }

    // --- where the claim and the code part company ---------------------------

    /**
     * A row with no identity at all is imported twice by the second import of the same sheet.
     *
     * **This is a defect, and the test names it.** A row whose serial is unreadable *and*
     * whose EPIC cell is blank has nothing to match on. On the first import it becomes a
     * person. On the second -- the same sheet, the routine refresh when the office sends a
     * corrected roll -- there is still nothing to match on, so it is added a second time
     * and the original is flagged "not in the latest file".
     *
     * What the surveyor sees: the same name at the same door number twice, Home counting one
     * person too many, one copy of them badged as struck off the roll, and two rows for one
     * house in the printed booklet. The import screen promises "Importing the same roll again
     * changes nothing" (Import.kt) and this is the case where it does.
     *
     * The fix is not to guess: a row with no usable identity should either be refused with a
     * count, or keyed on the name and door number it does have so a repeat import recognises
     * it. What must not happen is a second copy.
     */
    @Test
    fun `a row with no voter id and no usable serial is recognised on a re-import`() {
        // "N/A" is an unreadable serial and the EPIC cell is empty: no key of its own.
        val rows = listOf(
            row(1, "W1", "HEMA", "10-1-1"),
            XlsxReader.Row(serial = 0, voterId = "", name = "SAI", house = "10-1-2"),
        )

        val first = BoothImporter.create(meta, rows, 1L).booth
        assertEquals("first time it is one person", 2, first.voters.size)

        val second = BoothImporter.merge(first, meta, rows, 2L)
        assertEquals(
            "importing the same roll again adds nobody",
            2,
            second.booth.voters.size,
        )
        assertEquals(
            "and nobody is badged as struck off the roll",
            0,
            second.keptButNotInFile,
        )
        assertEquals(
            "so the same name does not stand at the same door twice",
            listOf("10-1-2"),
            second.booth.voters.filter { it.name == "SAI" }.map { it.house },
        )
    }

    /**
     * The same sheet imported three times must land on the same roll, however few keys the
     * rows carry. A row with no voter ID and no usable serial has to be recognised by the
     * name and door number it does have, and the shape of the booth -- the people's marks,
     * their household, their place in it -- has to survive each round.
     */
    @Test
    fun `importing the same roll three times reaches the same booth`() {
        val rows = listOf(
            row(1, "W1", "HEMA", "10-1-1"),
            XlsxReader.Row(serial = 0, voterId = "", name = "SAI", house = "10-1-2"),
            XlsxReader.Row(serial = 0, voterId = "", name = "RAJU", house = "10-1-2"),
        )
        var booth = BoothImporter.create(meta, rows, 1L).booth
        // Give one of them a household and a mark, so the round trip has something to lose.
        val marked = booth.copy(
            voters = booth.voters.map {
                if (it.name == "SAI") it.copy(visited = true, familyId = "fam") else it
            },
            families = listOf(Family("fam", 0)),
        )
        booth = BoothImporter.merge(marked, meta, rows, 2L).booth
        booth = BoothImporter.merge(booth, meta, rows, 3L).booth

        assertEquals("still three people after three imports", 3, booth.voters.size)
        val sai = booth.voters.single { it.name == "SAI" }
        assertTrue("the mark survived", sai.visited)
        assertEquals("the household survived", "fam", sai.familyId)
        assertEquals("nobody was struck off", 0, booth.voters.count { it.notInLatestFile })
    }

    /**
     * A name or door number corrected at the door is put back to the sheet's wording by the
     * next import.
     *
     * **This is a defect, and it is a contradiction inside the app.** `Voter.kt` records that
     * all four sheet fields are editable precisely because the sheet is a draft and a
     * finalized list renumbers people -- so correcting a misspelt name or an OCR'd door
     * number is an intended thing to do. `BoothImporter` then takes the sheet's wording back
     * on every re-import, which is deliberate and documented there for the four fields.
     *
     * The problem is the words. `ExportSheet.kt` promises "Every answer you have entered,
     * with names and door numbers kept exactly as you typed them", and `Import.kt` says
     * "Nothing you have entered is touched" in the sentence immediately after promising
     * that name and door number will be updated from the file. All three cannot be true.
     *
     * The phone number, caste, address, party, family and the visited marks do survive, and
     * this asserts that too -- the guarantee is real, it is just stated three different ways
     * and one of the three is wrong.
     */
    @Test
    fun `a name corrected at the door is put back to the sheet on re-import`() {
        val corrected = booth(
            voter(
                "a", 1, "W1",
                name = "HEMA LATHA (corrected at the door)",
                house = "10-9-9 (corrected)",
            ).copy(phone = "9848012345", caste = "BC", visited = true, party = "TDP"),
        )

        val out = BoothImporter.merge(
            corrected, meta,
            listOf(XlsxReader.Row(1, "W1", "HEMA LATHA", "10-1-1")),
            1L,
        ).booth.voters.single()

        assertEquals(
            "** the sheet's wording wins on the two fields it supplies **",
            "HEMA LATHA",
            out.name,
        )
        assertEquals("** ...including the door number **", "10-1-1", out.house)

        // What does survive, which is what the guarantee should be stated as.
        assertEquals("9848012345", out.phone)
        assertEquals("BC", out.caste)
        assertEquals("TDP", out.party)
        assertTrue(out.visited)
    }

    // --- a minimal sheet, built here so the reader is not tested against itself

    /**
     * A sheet with the four column names and the given rows.
     *
     * Inline strings, written here rather than by the app's own exporter, following
     * [XlsxReaderTest]: a reader tested against a file the writer produced would pass even
     * if the two were wrong in the same way.
     */
    private fun sheet(header: List<String>, vararg rows: List<String>, numericSerials: Boolean = false): ByteArray {
        val all = listOf(header) + rows.toList()
        val body = all.mapIndexed { r, cells ->
            val cs = cells.mapIndexedNotNull { c, v ->
                val ref = "${'A' + c}${r + 1}"
                if (r > 0 && numericSerials && c == 0) {
                    """<c r="$ref"><v>$v</v></c>"""
                } else {
                    """<c r="$ref" t="inlineStr"><is><t xml:space="preserve">$v</t></is></c>"""
                }
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
            z.write(("""<?xml version="1.0"?><worksheet><sheetData>$body</sheetData></worksheet>""").toByteArray())
            z.closeEntry()
        }
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray) = XlsxReader.read(ByteArrayInputStream(bytes))
}