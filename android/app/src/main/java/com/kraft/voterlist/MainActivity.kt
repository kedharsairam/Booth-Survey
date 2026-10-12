package com.kraft.voterlist

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.kraft.voterlist.data.PrintOrder
import com.kraft.voterlist.data.Survey
import com.kraft.voterlist.export.PdfExporter
import com.kraft.voterlist.export.XlsxExporter
import com.kraft.voterlist.ui.SurveyViewModel
import com.kraft.voterlist.ui.SurveyApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * The app.
 *
 * No permissions are declared and none are requested, and none are needed:
 *
 *   - the roll ships inside the APK and is edited in the app's own internal storage, which
 *     needs no permission on any Android version
 *   - an export is written through the system file picker, which grants access to the one
 *     file the user picked and nothing else
 *   - there is no network permission because nothing here talks to a network
 *
 * That is a deliberate design constraint rather than a lucky outcome. The app is used on a
 * street with no signal and no laptop nearby, and a permission prompt during a survey is a
 * prompt somebody waves through without reading.
 */
class MainActivity : ComponentActivity() {

    private companion object { const val TAG = "VoterSurvey" }

    private var pending: ((OutputStream) -> Unit)? = null

    /**
     * The system document picker. Using it rather than writing to a fixed path is what
     * removes the storage permission requirement: the grant is scoped to the single URI the
     * user selected.
     */
    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            val write = pending
            pending = null
            Log.i(TAG, "picker returned ${if (uri == null) "nothing" else uri.lastPathSegment}; pending=${write != null}")
            if (uri == null || write == null) return@registerForActivityResult
            lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            // Counted rather than flushed-and-ignored: flush() returns Unit,
                            // so reporting it produced the log line "wrote kotlin.Unit bytes".
                            val counting = object : java.io.FilterOutputStream(out) {
                                var count = 0L
                                override fun write(b: Int) { out.write(b); count++ }
                                override fun write(b: ByteArray, off: Int, len: Int) {
                                    out.write(b, off, len); count += len
                                }
                            }
                            write(counting)
                            counting.flush()
                            Log.i(TAG, "wrote ${uri.lastPathSegment}, ${counting.count} bytes")
                        } ?: error("could not open the chosen file")
                    }
                }.onSuccess {
                    // Named, and told where it went. "Saved" alone does not tell a surveyor
                    // whether to look in Downloads or somewhere else, after an afternoon's work.
                    vm.say(
                        "Saved. Look for the file in the folder you chose — " +
                            "Downloads is usually it.",
                    )
                }.onFailure {
                    Log.e(TAG, "export failed", it)
                    // Plain words. The exception's own message is a class name and a file
                    // path -- "java.io.FileNotFoundException: /storage/emulated/0/..."
                    // -- which tells him nothing about what to do differently.
                    vm.say(
                        "Could not save the file. This usually means that folder is not " +
                            "writable. Try choosing Downloads, and check there is free space.",
                    )
                }
            }
        }

    /**
     * The system file picker for reading the survey sheet.
     *
     * `OpenDocument` rather than anything that scans storage, because it needs **no
     * permission**: the system hands back a grant scoped to the one file that was chosen, and
     * nothing else on the phone is reachable through it. Asking for a storage permission to
     * do this would put a permission prompt in an app whose whole argument is that it needs
     * none, in front of somebody standing on a doorstep.
     *
     * Several types are offered because a lot of "Excel" files in the wild are really CSV or
     * have no useful type recorded, and refusing them would be a pointless dead end.
     */
    private val pickImport =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.onFilePicked(uri)
        }

    private val vm: SurveyViewModel by lazy { SurveyViewModel(application) }

    /**
     * Exports are named after the booth rather than the date, so two booths exported on the
     * same day do not fight over one filename in the Downloads folder.
     */
    private fun boothFileName(): String {
        val no = vm.activeBooth().boothNo.trim()
        return if (no.isEmpty()) "voters" else "booth-$no"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            var busy by remember { mutableStateOf(false) }
            val s by vm.state.collectAsState()

            fun handOver(mime: String, suggested: String, write: (OutputStream) -> Unit) {
                busy = true
                pending = write
                createDocument.launch(suggested)
                // The picker is a separate screen; the write itself reports its own outcome.
                busy = false
            }

            SurveyApp(
                vm = vm,
                busy = busy,
                onPickImportFile = { pickImport.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "text/csv", "text/comma-separated-values", "application/octet-stream")) },
                onExportXlsx = { onlyPending ->
                    handOver(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "survey-${boothFileName()}.xlsx",
                    ) { out ->
                        val booth = vm.activeBooth()
                        XlsxExporter.write(
                            out,
                            // !visited, not !isDone. One checkbox drove two files and they
                            // did not agree: the spreadsheet dropped anyone visited or
                            // skipped, while the booklet kept a whole household if anybody
                            // in it was unfinished -- so he ticked one box, got two files,
                            // and the people were not the same people. Both now mean "not
                            // knocked at yet", which is also what the export sheet's count
                            // has said since skipped stopped counting as finished.
                            if (onlyPending) booth.copy(voters = booth.voters.filter { !it.visited })
                            else booth,
                            vm.activeLists(),
                        )
                    }
                },
                // The booklet: the MLA's own form, laid out from the template's own
                // measurements. The pending filter is applied to the blocks rather than to
                // the voters, so a household keeps printing whole -- dropping the house
                // because one of its four people is done would quietly shorten the day.
                onExportPdf = { onlyPending ->
                    handOver(
                        "application/pdf",
                        "booklet-${boothFileName()}.pdf",
                    ) { out ->
                        val booth = vm.activeBooth()
                        PdfExporter.write(
                            out,
                            PrintOrder.blocks(Survey(booth, vm.activeLists())),
                            PdfExporter.Options(
                                mandal = booth.mandal,
                                townWard = booth.townWard,
                                boothNo = booth.boothNo,
                                onlyPending = onlyPending,
                            ),
                        )
                    }
                },
            )
        }
    }
}
