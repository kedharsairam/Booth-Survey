package com.kraft.voterlist.io

import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.util.zip.ZipInputStream
import org.xml.sax.InputSource

/**
 * Reads the survey sheet, and only what is needed to read it.
 *
 * An .xlsx is a zip of a few XML parts. That is the same shape the app already writes one in,
 * so this reads it with `java.util.zip` and the platform's own XML parser rather than pulling
 * in Apache POI, which would add ten megabytes to an APK whose whole argument is that it is
 * small, needs nothing, and talks to nobody.
 *
 * The four columns are found by **header name, not position**. Whoever is importing may have
 * built their sheet in a different order, and a sheet whose columns happen to line up today is
 * a sheet that imports the wrong values tomorrow without a word of complaint.
 *
 * Headers are matched loosely -- case, spaces and underscores are ignored -- because "Voter ID",
 * "voter_id" and "voterid" are the same column written three ways by three people, and failing
 * an import over a capital letter would be absurd. Each field accepts a couple of obvious
 * synonyms and nothing more: a volunteer is told which four columns to provide, and guessing at
 * a wider set of names would let a typo import as something plausible and wrong.
 *
 * The XML is parsed as XML, not as JSON. They look alike and are not the same: `<si><t>x</t></si>`
 * is not valid JSON, and handing a worksheet to a JSON parser throws on the very first tag.
 */
object XlsxReader {

    /** One row of the sheet, already reduced to the fields the app cares about. */
    data class Row(
        val serial: Int,
        val voterId: String,
        val name: String,
        val house: String,
    ) {
        val hasSerial: Boolean get() = serial > 0
    }

    /** What came out of the file, and what looked wrong on the way. */
    data class Result(
        val rows: List<Row>,
        val missingColumns: List<String>,
        val blankName: Int,
        val blankHouse: Int,
        val unreadableSerial: Int,
    ) {
        val usable: Boolean get() = rows.isNotEmpty() && missingColumns.isEmpty()
    }

    private val SERIAL = listOf("serial", "serialno", "serialnumber", "slno")
    private val VOTER_ID = listOf("voterid", "voteridno", "voteridnumber", "id", "epicno", "epic")
    private val NAME = listOf("name", "fullname", "nameofvoter", "nameofelector", "votername")
    private val HOUSE = listOf("housenumber", "house", "houseno", "doorno", "door", "doornumber")

    /**
     * How far down to look for the column names.
     *
     * Enough for a title, a blank line and a header, which is what a report from an
     * electoral office looks like. Not so far that a stray "name" inside the data is
     * mistaken for the header.
     */
    private const val HEADER_SEARCH_ROWS = 10

    /** Column names, as a repeated header row would spell them. */
    private val REPEATED_HEADER_WORDS = setOf(
        "sno", "slno", "serial", "serialno", "epicno", "epic", "voterid",
        "houseno", "house", "housenumber", "name", "votername", "fullname",
    )

    private val REQUIRED = listOf(
        "serial" to SERIAL,
        "voter id" to VOTER_ID,
        "name" to NAME,
        "house number" to HOUSE,
    )

    fun normaliseHeader(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * How many of the four column names a row states, and where each one is.
     *
     * The first column claiming a name wins, so a sheet carrying two columns that both look
     * like a serial -- which this one does: "S.No" down the left as a row counter, and
     * "Sl No" further in as the number the voter is actually known by -- takes the one the
     * roll means. "sno" is not a spelling of serial; "slno" is.
     */
    private fun columnsIn(row: Map<Int, String>): Map<String, Int> {
        val found = LinkedHashMap<String, Int>()
        for ((index, text) in row.toSortedMap()) {
            val key = normaliseHeader(text)
            if (key.isEmpty()) continue
            for ((field, spellings) in REQUIRED) {
                if (spellings.contains(key) && !found.containsKey(field)) found[field] = index
            }
        }
        return found
    }

    fun read(input: InputStream): Result {
        val parts = unzip(input)
        val shared = parseSharedStrings(parts["xl/sharedStrings.xml"])
        // What goes in missingColumns here is printed after "The sheet is missing: ",
        // so each entry has to finish that sentence on its own. "a worksheet" and
        // "any data" were what stood there before, and both read as half a sentence
        // about something he cannot see in a file he cannot open.
        val sheetPath = firstSheetPath(parts)
            ?: return Result(emptyList(), listOf("any sheet at all"), 0, 0, 0)
        val sheetXml = parts[sheetPath] ?: return Result(emptyList(), listOf("any sheet at all"), 0, 0, 0)
        val grid = parseSheet(sheetXml, shared)
        // It opened as a sheet but holds nothing at all: no people, so not even a heading
        // row to find the four names in.
        if (grid.isEmpty()) return Result(emptyList(), listOf("any people listed"), 0, 0, 0)

        // The header is not necessarily the first row.
        //
        // It was assumed to be, and the sheet the electoral office actually hands out
        // breaks that: row 1 is the title "VOTER LIST PART-WISE REPORT - 57 - Palacole",
        // and the column names are on row 2. Read the first row, find nothing that looks
        // like a column name, and the whole import came back as "the file is missing:
        // serial, voter id, name, house number" -- which is true and useless, because the
        // file has all four.
        //
        // So the top rows are scanned for the one that names the most columns, and that is
        // the header. A plain list of names with no preamble still wins on row 1, so this
        // costs a sheet that already worked nothing.
        var headerRowIndex = -1
        var columnFor: Map<String, Int> = emptyMap()
        var bestScore = 0
        for (i in 0 until minOf(HEADER_SEARCH_ROWS, grid.size)) {
            val found = columnsIn(grid[i])
            if (found.size > bestScore) {
                bestScore = found.size
                headerRowIndex = i
                columnFor = found
            }
        }
        if (headerRowIndex < 0) {
            return Result(emptyList(), listOf("any people listed"), 0, 0, 0)
        }
        val missing = REQUIRED.map { it.first }.filterNot { columnFor.containsKey(it) }
        if (missing.isNotEmpty()) {
            // A partial read is never handed back. Rows parsed against columns that were not
            // found would silently have blanks in them, and a caller that checked only the row
            // count would import a sheet where the door numbers were all empty.
            return Result(emptyList(), missing, 0, 0, 0)
        }

        // Everything above the header is a title, not a person.
        val data = grid.subList(headerRowIndex + 1, grid.size)
        val rows = ArrayList<Row>(data.size)
        var blankName = 0
        var blankHouse = 0
        var unreadableSerial = 0
        for (i in headerRowIndex + 1 until grid.size) {
            val cells = grid[i]
            fun at(field: String): String = columnFor[field]?.let { cells[it] }?.trim().orEmpty()

            // A sheet long enough to be printed repeats its column names partway down, and
            // those rows are not people. It reads as a person called "Voter Name" living at
            // "House No" with a voter id of "EPIC No", which is how a roll picks up a ghost.
            if (columnFor.values.all { c ->
                    normaliseHeader(cells[c].orEmpty()) in REPEATED_HEADER_WORDS
                }) continue

            val rawSerial = at("serial")
            val serial = rawSerial.toIntOrNull() ?: 0
            if (rawSerial.isNotEmpty() && serial == 0) unreadableSerial++
            val name = at("name")
            // The key is the REQUIRED field's label, "house number", not "house". Asking for
            // the wrong one returns nothing at all and silently loads a sheet where every door
            // number is blank, which looks like a data problem rather than a lookup that missed.
            val house = at("house number")
            // A row with nothing in any of the four is padding, not a person.
            if (rawSerial.isEmpty() && at("voter id").isEmpty() && name.isEmpty() && house.isEmpty()) continue
            if (name.isEmpty()) blankName++
            if (house.isEmpty()) blankHouse++
            rows += Row(serial, at("voter id"), name, house)
        }
        return Result(rows, missing, blankName, blankHouse, unreadableSerial)
    }

    private fun unzip(input: InputStream): Map<String, String> {
        val out = HashMap<String, String>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) out[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        return out
    }

    /**
     * Namespace-blind on purpose. The parts disagree about prefixes -- `r:id` in one, a default
     * namespace in another -- and matching on the literal tag name works across all of them
     * without hard-coding a prefix that the next program to save the file may change.
     */
    private fun parse(xml: String?): Element? {
        if (xml.isNullOrBlank()) return null
        return runCatching {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = false
            // These parts come off the user's own phone and contain nothing to fetch, but a
            // parser that will resolve external entities is a parser that can be made to.
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            f.isExpandEntityReferences = false
            f.newDocumentBuilder().parse(InputSource(xml.reader())).documentElement
        }.getOrNull()
    }

    private fun children(parent: Element, tag: String): List<Element> {
        val out = ArrayList<Element>()
        val kids = parent.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && n.nodeName.substringAfterLast(':') == tag) {
                out += n as Element
            }
        }
        return out
    }

    private fun first(parent: Element, tag: String): Element? = children(parent, tag).firstOrNull()

    private fun deepText(e: Element): String {
        // A string can be split across several runs when part of it is styled differently, which
        // is what OnlyOffice does to headers. Take the text of every descendant.
        val sb = StringBuilder()
        fun walk(n: Node) {
            if (n.nodeType == Node.TEXT_NODE) {
                sb.append(n.nodeValue)
                return
            }
            if (n.nodeType != Node.ELEMENT_NODE) return
            if (n.nodeName.substringAfterLast(':') == "r") {
                children(n as Element, "t").forEach { walk(it) }
                return
            }
            val kids = n.childNodes
            for (i in 0 until kids.length) walk(kids.item(i))
        }
        walk(e)
        return sb.toString()
    }

    /**
     * Resolves which sheet to read.
     *
     * Follows the workbook's own relationship rather than assuming `sheet1.xml`, because
     * whichever program wrote the file decides that name. Excel does not reorder sheets on
     * save, but LibreOffice and OnlyOffice both do, and a sheet that opens on the second tab
     * should still be the one that gets read.
     */
    private fun firstSheetPath(parts: Map<String, String>): String? {
        val workbook = parse(parts["xl/workbook.xml"]) ?: return "xl/worksheets/sheet1.xml".takeIf { parts.containsKey(it) }
        val sheet = children(first(workbook, "sheets") ?: workbook, "sheet").firstOrNull()
            ?: return "xl/worksheets/sheet1.xml".takeIf { parts.containsKey(it) }
        val relId = sequenceOf("r:id", "id")
            .map { sheet.getAttribute(it) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

        if (relId.isNotEmpty()) {
            val rels = parse(parts["xl/_rels/workbook.xml.rels"])
            if (rels != null) {
                for (r in children(rels, "Relationship")) {
                    if (r.getAttribute("Id") == relId) {
                        val target = r.getAttribute("Target").trimStart('/')
                        val resolved = if (target.startsWith("xl/")) target else "xl/$target"
                        if (parts.containsKey(resolved)) return resolved
                    }
                }
            }
        }
        return "xl/worksheets/sheet1.xml".takeIf { parts.containsKey(it) }
    }

    private fun parseSharedStrings(xml: String?): List<String> {
        val sst = parse(xml) ?: return emptyList()
        return children(sst, "si").map { deepText(it) }
    }

    /** Turns a worksheet into a list of rows, each an index-to-text map. */
    private fun parseSheet(xml: String, shared: List<String>): List<Map<Int, String>> {
        val sheet = parse(xml) ?: return emptyList()
        val data = first(sheet, "sheetData") ?: return emptyList()
        val rows = ArrayList<Map<Int, String>>()
        for (row in children(data, "row")) {
            val map = HashMap<Int, String>()
            for (cell in children(row, "c")) {
                val column = columnIndex(cell.getAttribute("r")) ?: continue
                map[column] = cellValue(cell, shared)
            }
            rows += map
        }
        return rows
    }

    private fun cellValue(cell: Element, shared: List<String>): String {
        val v = first(cell, "v")?.textContent.orEmpty()
        return when (cell.getAttribute("t")) {
            // Shared string: v is an index into sharedStrings.xml.
            "s" -> v.toIntOrNull()?.let { if (it in shared.indices) shared[it] else "" } ?: ""
            // Inline string: the text is in the cell itself.
            "inlineStr" -> first(cell, "is")?.let { deepText(it) }.orEmpty()
            // Cached formula result.
            "str" -> v
            // No type means a number. We deliberately do not reformat a numeric serial into a
            // date if the cell was formatted as one: guessing would invent data out of a
            // formatting hint.
            else -> v
        }
    }

    /** "AB12" is column 27. Returns null for a cell with no address. */
    private fun columnIndex(ref: String): Int? {
        var n = 0
        var seen = false
        for (ch in ref) {
            if (!ch.isLetter()) break
            seen = true
            n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return if (seen) n - 1 else null
    }
}
