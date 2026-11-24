package com.kraft.voterlist.ui

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kraft.ui.tokens.KraftSpacing
import com.kraft.voterlist.export.PdfExporter

/**
 * Offers the export.
 *
 * It goes through the system file picker, so the app never needs storage permission: the
 * grant covers the single file the user chose. The user is asked where it goes rather than
 * being told, because a survey sheet that lands somewhere nobody can find it is work
 * repeated.
 *
 * "Remaining only" is offered because the common case at the end of a day's walking is to
 * want a fresh sheet of the houses still to do, not a reprint of everything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    vm: SurveyViewModel,
    s: UiState,
    busy: Boolean,
    onExportXlsx: (Boolean, Boolean) -> Unit,
    onExportPdf: (Boolean, Boolean, Int) -> Unit,
    onClose: () -> Unit,
) {
    var onlyPending by remember { mutableStateOf(false) }
    var onlyFamilies by remember { mutableStateOf(false) }
    // Kept as text so the field can be empty or half-typed while he edits it. The number is
    // parsed on every change and the export button stays off until it is a whole number in
    // range -- a half-typed value must never reach a government document.
    var perBookletText by remember {
        mutableStateOf(PdfExporter.DEFAULT_VOTERS_PER_BOOKLET.toString())
    }
    val perBooklet = perBookletText.toIntOrNull()
    val perBookletError = when {
        perBooklet == null -> "Enter a whole number."
        perBooklet !in PdfExporter.MIN_VOTERS_PER_BOOKLET..PdfExporter.MAX_VOTERS_PER_BOOKLET ->
            "Use a number from ${PdfExporter.MIN_VOTERS_PER_BOOKLET} to ${PdfExporter.MAX_VOTERS_PER_BOOKLET}."
        else -> null
    }
    val pending = s.totalCount - s.doneCount

    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(KraftSpacing.Spacing20).fillMaxWidth()) {
            // Named, because the app can hold several booths and this writes a file. "Export"
            // on its own leaves you wondering which booth the file is for -- and getting
            // that wrong means writing the wrong street's sheet. "Print" rather than
            // "Export", because the word he is looking for on a stack of paper is the one
            // that says what the file is for.
            Text(
                if (s.hasBooth) "Print the list for ${s.booth.displayTitle}" else "Print the list",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                // What to do and then what he is looking at. "Choose where to save it" was
                // correct and useless on its own: the list of places that opens is the
                // phone's own and means nothing to somebody who has not seen it.
                "Press the button, then pick a place to save it. The places you see are " +
                    "the phone's own -- Downloads, or an app you already have.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(KraftSpacing.Spacing16))

            // The whole row is the control, and it is labelled. It used to be a bare
            // Checkbox followed by two lines of text: a screen reader reached an unnamed
            // "checkbox", then the words beside it as separate stops, and tapping the words
            // did nothing. This way the row is the checkbox and the label is its name.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = BoothMetrics.TouchTarget)
                    .toggleable(
                        value = onlyPending,
                        role = androidx.compose.ui.semantics.Role.Checkbox,
                        onValueChange = { onlyPending = it },
                    )
                    .semantics(mergeDescendants = true) {},
            ) {
                Checkbox(checked = onlyPending, onCheckedChange = null)
                Column(Modifier.padding(start = Space.md)) {
                    Text("People still to visit only", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "$pending of ${s.totalCount} still to do",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(KraftSpacing.Spacing12))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = BoothMetrics.TouchTarget)
                    .toggleable(
                        value = onlyFamilies,
                        role = androidx.compose.ui.semantics.Role.Checkbox,
                        onValueChange = { onlyFamilies = it },
                    )
                    .semantics(mergeDescendants = true) {},
            ) {
                Checkbox(checked = onlyFamilies, onCheckedChange = null)
                Column(Modifier.padding(start = Space.md)) {
                    Text("Families only", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Only people who are in a family. Anyone not in a family stays out.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(KraftSpacing.Spacing12))

            // The page count is not a setting buried in code. He decides at the point of
            // printing how many people go in each booklet, and the pages follow from it:
            // fifteen names to a page, so 50 means four pages and 150 means ten.
            Text("Voters in each booklet", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(KraftSpacing.Spacing6))
            OutlinedTextField(
                value = perBookletText,
                onValueChange = { perBookletText = it },
                singleLine = true,
                isError = perBookletError != null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(KraftSpacing.Spacing6))
            Text(
                when {
                    perBookletError != null -> perBookletError
                    perBooklet != null ->
                        "That makes ${PdfExporter.pagesPerBooklet(perBooklet)} pages in each booklet."
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (perBookletError == null) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
            )

            Spacer(Modifier.height(KraftSpacing.Spacing12))

            // Filled, because this is the one that goes to the printer: the booklet is the
            // artefact the survey exists to produce, and the spreadsheet is what you open
            // afterwards to check it. Both offered, because they answer different questions
            // and picking only one would be the app deciding that for him.
            Button(
                onClick = { perBooklet?.let { onExportPdf(onlyPending, onlyFamilies, it) }; onClose() },
                enabled = !busy && perBookletError == null && perBooklet != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.PictureAsPdf, null)
                Spacer(Modifier.width(KraftSpacing.Spacing8))
                Text("Booklet for printing", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(KraftSpacing.Spacing6))
            Text(
                // "A4 landscape", "the template" and "the MLA's own form" were three
                // unexplained names in one sentence, none of which told him what lands on
                // the paper. The thing he wants to know is that it lines up with the form
                // he is already writing on.
                "Fifteen names fit on a page. The boxes are the same as the printed form " +
                    "you already have, so either one can be filled in.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(KraftSpacing.Spacing12))
            OutlinedButton(
                onClick = { onExportXlsx(onlyPending, onlyFamilies); onClose() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.TableChart, null)
                Spacer(Modifier.width(KraftSpacing.Spacing8))
                Text("Excel list (for the office)", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(KraftSpacing.Spacing6))
            Text(
                // Was an explanation of why the writer sets a column to text. That is the
                // app's business. What is his is that nothing he typed comes out of the
                // office file changed, which is what the sentence underneath was trying to
                // say and never did.
                // Was "with names and door numbers kept exactly as you typed them", which
                // is false: an import takes those two back from the sheet, because they are
                // exactly what an import is for. The other two screens say it both ways.
                // This is the honest version -- everything he filled in at the door is
                // carried through, and the two fields the office sheet owns are refreshed.
                "Every answer you have entered is carried through. Names and door numbers " +
                    "come from the latest sheet, the way the import works.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(KraftSpacing.Spacing24))
        }
    }
}