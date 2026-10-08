package com.kraft.voterlist.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kraft.ui.tokens.KraftSpacing
import com.kraft.ui.tokens.KraftTypeScale

/**
 * Offers the two exports.
 *
 * Both go through the system file picker, so the app never needs storage permission: the
 * grant covers the single file the user chose. The user is asked where it goes rather than
 * being told, because a survey sheet that lands somewhere nobody can find it is work
 * repeated.
 *
 * "Remaining only" is offered because the common case at the end of a day's walking is to
 * want a fresh booklet of the houses still to do, not a reprint of everything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    vm: SurveyViewModel,
    s: UiState,
    busy: Boolean,
    onExportPdf: (Boolean) -> Unit,
    onExportXlsx: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    var onlyPending by remember { mutableStateOf(false) }
    val pending = s.totalCount - s.doneCount

    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(KraftSpacing.Spacing20).fillMaxWidth()) {
            // Named, because the app can hold several booths and this writes a file. "Export"
            // on its own leaves you wondering which booth the 76 pages are for -- and getting
            // that wrong means printing the wrong street's booklet.
            Text(
                if (s.hasBooth) "Export ${s.booth.displayTitle}" else "Export",
                fontSize = KraftTypeScale.Body,
            )
            Text(
                "Choose where to save it.",
                fontSize = KraftTypeScale.Caption1, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(KraftSpacing.Spacing16))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = onlyPending, onCheckedChange = { onlyPending = it })
                Column {
                    Text("People still to visit only")
                    Text(
                        "$pending of ${s.totalCount} still to do",
                        fontSize = KraftTypeScale.Caption1, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(KraftSpacing.Spacing8))
            Button(
                onClick = { onExportPdf(onlyPending); onClose() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.PictureAsPdf, null)
                Spacer(Modifier.width(KraftSpacing.Spacing8))
                Text("Booklet (PDF, A4 landscape)")
            }
            Spacer(Modifier.height(KraftSpacing.Spacing6))
            Text(
                "The MLA's form, four pages to a booklet, folded and stapled.",
                fontSize = KraftTypeScale.Caption2, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(KraftSpacing.Spacing16))
            OutlinedButton(
                onClick = { onExportXlsx(onlyPending); onClose() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.TableChart, null)
                Spacer(Modifier.width(KraftSpacing.Spacing8))
                Text("Spreadsheet (Excel)")
            }
            Spacer(Modifier.height(KraftSpacing.Spacing6))
            Text(
                "Every field, with door numbers and names stored as text so a spreadsheet " +
                    "cannot reinterpret \"98-3-3/2\" as a date.",
                fontSize = KraftTypeScale.Caption2, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(KraftSpacing.Spacing24))
        }
    }
}