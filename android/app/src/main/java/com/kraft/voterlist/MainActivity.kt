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
import java.time.LocalDate

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
                    vm.say("Saved")
                }.onFailure {
                    Log.e(TAG, "export failed", it)
                    vm.say("Could not write the file: ${it.message}")
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
                onExportTemplate = {
                    handOver(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "voters-template.xlsx",
                    ) { out -> XlsxExporter.writeTemplate(out) }
                },
                onExportPdf = { onlyPending ->
                    handOver("application/pdf", "booklet-${LocalDate.now()}.pdf") { out ->
                        // The booth and its print order are read here, off the UI thread,
                        // rather than out of Compose state: the export runs after the picker
                        // closes, by which time the composition may be gone.
                        val booth = vm.activeBooth()
                        val only = if (onlyPending) booth.copy(
                            voters = booth.voters.filter { !it.isDone },
                            families = booth.families.filter { f ->
                                booth.voters.any { it.familyId == f.id && !it.isDone }
                            },
                        ) else booth
                        PdfExporter.write(
                            out,
                            PrintOrder.blocks(Survey(only, vm.activeLists())),
                            PdfExporter.Options(
                                mandal = booth.mandal,
                                townWard = booth.townWard,
                                boothNo = booth.boothNo,
                            ),
                        )
                    }
                },
                onExportXlsx = { onlyPending ->
                    handOver(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "survey-${boothFileName()}.xlsx",
                    ) { out ->
                        val booth = vm.activeBooth()
                        XlsxExporter.write(
                            out,
                            if (onlyPending) booth.copy(voters = booth.voters.filter { !it.isDone })
                            else booth,
                            vm.activeLists(),
                        )
                    }
                },
            )
        }
    }
}
