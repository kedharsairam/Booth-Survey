package com.kraft.voterlist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Importing a booth, as its own screen.
 *
 * Four steps in this order and no other, because each one exists to stop the next from
 * destroying something:
 *
 *   1. **Pick the file**, through the system picker. No permission is involved -- the grant
 *      covers the one file you chose and nothing else.
 *   2. **Read it and say what is in it.** Row count, how many have no name, how many have no
 *      door number. Nothing is written yet, so a wrong file costs a report and nothing more.
 *   3. **Ask which booth it is.** Mandal, town or ward, booth number. Not in the sheet, printed
 *      on every booklet page, and the thing that decides whether this import creates a booth or
 *      updates one.
 *   4. **Confirm, naming the consequence.** If the booth is already here, the prompt says how
 *      many people are in it, how many you have visited, and exactly what an update does and
 *      does not touch.
 *
 * Re-importing the same roll is the case this is built around. It changes nothing: the sheet's
 * four fields are already what is held, so the result reads "0 added, 0 changed, 1129 already
 * matched" and no harm is possible.
 */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun ImportScreen(
    vm: SurveyViewModel,
    s: UiState,
    onPickFile: () -> Unit,
    onClose: () -> Unit,
) {
    when (val step = s.importStep) {
        ImportStep.Reading -> Column(
            Modifier.fillMaxSize().padding(Space.xl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(Space.lg))
            Text("Reading the sheet", style = MaterialTheme.typography.titleMedium)
        }

        // Reached when the file picker is cancelled. It keeps the app bar, so there is a
        // visible way out; without it this is a screen with a button on it and no way to
        // leave but the system back gesture, which is not discoverable.
        ImportStep.Idle -> Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel")
                        }
                    },
                    title = { Text("Import voters list", style = MaterialTheme.typography.titleMedium) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
        ) { pad ->
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .padding(Space.xl),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Choose the spreadsheet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Space.sm))
                Text(
                    "It needs four columns: serial, voter id, name, house number.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.xl))
                Button(onClick = onPickFile) { Text("Choose a file") }
            }
        }

        is ImportStep.Failed -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Could not import that") },
            text = { Text(step.reason) },
            confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        )

        is ImportStep.Done -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Done") },
            text = { Text(step.message) },
            confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        )

        is ImportStep.NeedsDetails -> DetailsStep(vm, s, step, onPickFile, onClose)
    }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
private fun DetailsStep(
    vm: SurveyViewModel,
    s: UiState,
    step: ImportStep.NeedsDetails,
    onPickFile: () -> Unit,
    onClose: () -> Unit,
) {
    val remembered = remember { vm.lastImport() }
    var boothNo by remember { mutableStateOf("") }
    var mandal by remember { mutableStateOf(remembered.second) }
    var ward by remember { mutableStateOf(remembered.third) }
    var label by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }

    val clash = remember(boothNo, s.index) {
        s.index.booths.firstOrNull { it.boothNo.equals(boothNo.trim(), ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel")
                    }
                },
                title = { Text("Import voters list", style = MaterialTheme.typography.titleMedium) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.lg),
        ) {
            Note("What is in the file", step.summary, warn = false)
            Spacer(Modifier.height(Space.lg))

            Text("Which booth is it?", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Space.xs))
            Text(
                "These are not in the sheet. They are printed on every booklet page, and the " +
                    "booth number is what tells booths apart in this app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.md))

            OutlinedTextField(
                value = boothNo,
                onValueChange = { boothNo = it },
                label = { Text("Booth number") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                textStyle = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(Space.md))
            OutlinedTextField(
                value = mandal,
                onValueChange = { mandal = it },
                label = { Text("Mandal") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                textStyle = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(Space.md))
            OutlinedTextField(
                value = ward,
                onValueChange = { ward = it },
                label = { Text("Town / ward") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                textStyle = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(Space.md))
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Name to show (optional)") },
                placeholder = { Text("Booth ${boothNo.ifBlank { "108" }}") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                textStyle = MaterialTheme.typography.bodyLarge,
            )

            clash?.let { entry ->
                Spacer(Modifier.height(Space.lg))
                Note(
                    "Booth ${entry.boothNo} is already here",
                    "${entry.size} people, ${entry.done} visited. Importing will update the " +
                        "serial, voter ID, name and door number from this file. Nothing you have " +
                        "entered is touched.\n\n" +
                        if (entry.notInFile > 0) {
                            "${entry.notInFile} ${if (entry.notInFile == 1) "person is" else "people are"} " +
                                "already marked as not in the last file."
                        } else {
                            "Importing the same roll again changes nothing."
                        },
                    warn = true,
                )
            }

            Spacer(Modifier.height(Space.lg))
            TextButton(onClick = onPickFile) { Text("Pick a different file") }

            Spacer(Modifier.height(Space.lg))
            Button(
                onClick = {
                    if (clash != null) confirming = true
                    else vm.confirmImport(boothNo, mandal, ward, label)
                },
                enabled = boothNo.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(if (clash != null) "Update Booth ${clash.boothNo}" else "Import")
            }
            Spacer(Modifier.height(Space.xxl))
        }
    }

    if (confirming && clash != null) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Update Booth ${clash.boothNo}?") },
            text = {
                Text(
                    "${clash.size} people are in this booth and ${clash.done} have been visited.\n\n" +
                        "The serial, voter ID, name and door number are updated from the file. " +
                        "Phone numbers, caste, addresses, families and who has been visited are " +
                        "not touched.\n\nAnyone in the app but not in the file stays, marked so " +
                        "you can see them."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.confirmImport(boothNo, mandal, ward, label)
                    confirming = false
                }) { Text("Update") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

/** A titled note. [warn] picks the accent; true means look at this before you continue. */
@Composable
private fun Note(title: String, body: String, warn: Boolean) {
    Surface(
        color = if (warn) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val on = if (warn) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSurface
        Column(Modifier.padding(Space.lg)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = on)
            Spacer(Modifier.height(Space.xs))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = if (warn) on else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
