package com.kraft.voterlist

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kraft.voterlist.data.Booth
import com.kraft.voterlist.data.Family
import com.kraft.voterlist.data.Lists
import com.kraft.voterlist.data.PrintOrder
import com.kraft.voterlist.data.Survey
import com.kraft.voterlist.data.Voter
import com.kraft.voterlist.export.PdfExporter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor

/**
 * Writes a real booklet to a file the shell can pull, so the layout can be *looked at*.
 *
 * The other PDF tests assert on bytes. Bytes cannot tell you the phone number is running
 * into the next column, or that a Telugu name has been shrunk to nothing, or that a heading
 * sits on top of its own rule. Those are the failures that matter and none of them are
 * visible from a byte count.
 */
@RunWith(AndroidJUnit4::class)
class PdfRenderTest {

    @Test
    fun writesABookletForInspection() {
        // Deliberately awkward: long Telugu names, a long address, a ten-digit phone, and
        // every column filled. Comfortable data hides exactly the problems worth seeing.
        val names = listOf(
            "పరీక్ష కుటుంబం 2", "కునిపల్లి పరీక్ష కుటుంబం 3", "మంజూర్ పరీక్ష కుటుంబం 1",
            "ఎం. వెంకటాక్షమ్మ", "బి. రాజేష్వర్ రెడ్డి", "కె. లక్ష్మీ దేవి",
            "పి. సాయి కుమార్", "ఎస్. రమణమ్మ", "జి. పరీక్ష కుటుంబం 5",
            "ఆర్. పరీక్ష కుటుంబం 7", "ఎమ్. సుధారమ్మ", "టి. నారాయణమ్మ",
            "వి. శ్రీకుమార్", "ఎస్. లక్ష్మీ దేవి", "ఆర్. సమిత",
            "కె. పరీక్ష కుటుంబం 6", "బి. స్వాతి", "ఎం. అనూష",
            "ఎస్. వినodu", "వి. మోహన్", "టి. రాజేష్",
            "జి. కవిత", "ఎమ్. పరీక్ష కుటుంబం 8", "ఆర్. దీపా",
            "కె. హరి", "బి. పరీక్ష కుటుంబం 7", "ఎస్. నిర్మల",
            "వి. శివ", "ఎమ్. రాజు", "టి. సిరి",
        )

        // Real households of different sizes, so the family serial and the page-break rule
        // can both be read off the render. One household of thirty crosses a page; a
        // four-person house lands with three rows left on a booklet's last page.
        val sizes = listOf(30, 4, 3, 5, 2, 6, 4, 4, 3, 5, 2, 4, 6, 3, 4, 5, 2)
        var serial = 0
        val voters = sizes.flatMapIndexed { h, count ->
            (1..count).map { m ->
                serial++
                Voter(
                    id = "p$serial", serial = serial, name = names[serial % names.size],
                    house = "10-$h", voterId = "WQQ%07d".format(serial),
                    phone = "9876543%03d".format(serial % 1000),
                    address = "KONDAPALLI", caste = "BC-A", subCaste = "MANGALA",
                    party = "TDP", familyId = "f$h", familyOrder = m - 1,
                )
            }
        }
        val survey = Survey(
            booth = Booth(
                boothNo = "108", mandal = "Palakollu", townWard = "Palakollu",
                label = "Booth 108", voters = voters,
                families = sizes.indices.map { Family(id = "f$it", order = it) },
            ),
            lists = Lists(),
        )

        // Written through MediaStore, the same way a real export is: scoped storage means
        // a raw path under /sdcard is refused, and the app has no storage permission to
        // ask for. MediaStore grants the app access to the entry it just created, and the
        // file lands in Download where a shell can pull it.
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "booklet-render.pdf")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(
                android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_DOWNLOADS,
            )
        }
        val resolver = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        val out = File("/sdcard/Download/booklet-render.pdf").apply { delete() }
        resolver.openOutputStream(uri)!!.use { os ->
            PdfExporter.write(
                os, PrintOrder.blocks(survey),
                PdfExporter.Options(
                    mandal = "Palakollu", townWard = "Palakollu", boothNo = "108",
                    onlyFamilies = false,
                ),
            )
        }
        println("PDF_RENDER_URI=$uri")
    }

    /**
     * The page count follows the number chosen at print time, in the real PDF rather than
     * in the layout data. Fifty one-person households at fifty to a booklet is four pages,
     * not eight. The file is left in Download so the footer can be read off the paper too.
     */
    @Test
    fun writesAFourPageBookletWhenFiftyIsChosen() {
        val voters = (1..50).map { i ->
            Voter(
                id = "f$i", serial = i, name = "TEST PERSON $i", house = "10-1-$i",
                voterId = "WQQ%07d".format(i), phone = "9876543210",
                address = "MAIN STREET", familyId = "f$i", familyOrder = 0,
            )
        }
        val survey = Survey(
            booth = Booth(
                boothNo = "108", mandal = "Palakollu", townWard = "Palakollu",
                label = "Booth 108", voters = voters,
                families = (1..50).map { Family(id = "f$it", order = it - 1) },
            ),
            lists = Lists(),
        )
        val name = "booklet-50.pdf"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(
                android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_DOWNLOADS,
            )
        }
        val resolver = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { os ->
            PdfExporter.write(
                os, PrintOrder.blocks(survey),
                PdfExporter.Options(
                    mandal = "Palakollu", townWard = "Palakollu", boothNo = "108",
                    votersPerBooklet = 50,
                ),
            )
        }
        val file = File("/sdcard/Download/$name")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                assertEquals("fifty voters make four pages", 4, renderer.pageCount)
            }
        }
        println("PDF_FIFTY_URI=$uri")
    }
}
