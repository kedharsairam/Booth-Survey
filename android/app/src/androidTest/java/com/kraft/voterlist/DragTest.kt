package com.kraft.voterlist

import android.view.ViewConfiguration
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.advanceEventTime
import androidx.compose.ui.test.onNodeWithText
import com.kraft.voterlist.ui.FamilyEditScreen
import com.kraft.voterlist.ui.KraftTheme
import com.kraft.voterlist.ui.SurveyViewModel
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Reordering inside a family, driven finger-for-finger.
 *
 * adb cannot hold-then-move, so the gesture path is unprovable from a shell: the arrows
 * prove the move logic, but nothing proves a finger reaches it. Both routes are tested here,
 * because they are now genuinely different gestures -- holding a row raises the sheet, and
 * reordering is the arrows or the sheet's own move actions. A test that only drove the old
 * grip drag would pass against a sheet that never opens.
 *
 * Synthetic people only ("SYNTH", TEST ids): the test APK keeps its own storage, but a
 * real roll must never be anywhere near a test regardless.
 */
class DragTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Ids this test created, and the one family it made. Held on the instance so teardown
     * can remove exactly these and nothing else.
     */
    private val created = mutableListOf<String>()
    private var familyId: String? = null

    /**
     * This test's own store, in a temporary directory.
     *
     * Everything below is allowed to clear the store, because the store is this folder and
     * not the app's. Previously the ViewModel was built from the app's context, so "clear
     * the store to start clean" meant clearing the surveyor's real booth off their phone.
     */
    private val storeDir: java.io.File by lazy {
        java.io.File(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .targetContext.cacheDir,
            "dragtest-${System.nanoTime()}",
        ).apply { mkdirs() }
    }

    private companion object {
        /** Far above any real roll's serials, so a synthetic can never collide with one. */
        const val SYNTH_BASE = 9_900_000
    }

    private var lastVm: SurveyViewModel? = null

    @After
    fun tearDown() {
        // Not best-effort: leaving synthetics behind would corrupt the next run's counts.
        lastVm?.let { cleanUp(it) }
    }

    @Test
    fun holding_a_member_row_raises_its_sheet() {
        val vm = preparedVm()
        compose.setContent {
            KraftTheme {
                val s by vm.state.collectAsState()
                FamilyEditScreen(vm, s)
            }
        }
        val fid = vm.state.value.booth.families.first().id
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("member-row-${synthIdOf(vm, fid, 2)}").fetchSemanticsNode(); true }
                .getOrDefault(false)
        }

        // The sheet must be up while the finger is STILL DOWN -- that was the bug, when it
        // only appeared on release, because the gesture loop woke on events and a still
        // finger sends none. Asserting after the release cannot tell those two apart.
        val row = compose.onNodeWithTag("member-row-${synthIdOf(vm, fid, 2)}")
        row.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(ViewConfiguration.getLongPressTimeout() + 200L)
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithText("Take out of this family").fetchSemanticsNode(); true }
                .getOrDefault(false)
        }
        row.performTouchInput { up() }

        // And the hold must not have reordered anything: it was a menu, not a drag.
        assertEquals(
            listOf(SYNTH_BASE, SYNTH_BASE + 1, SYNTH_BASE + 2),
            vm.state.value.membersOf(fid).map { it.serial },
        )
    }

    @Test
    fun the_sheet_moves_a_member_one_place() {
        val vm = preparedVm()
        compose.setContent {
            KraftTheme {
                val s by vm.state.collectAsState()
                FamilyEditScreen(vm, s)
            }
        }
        val fid = vm.state.value.booth.families.first().id
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("member-row-${synthIdOf(vm, fid, 0)}").fetchSemanticsNode(); true }
                .getOrDefault(false)
        }

        // Hold to raise the sheet -- a tap opens the voter, which is a different screen
        // entirely and would leave this test asserting against the wrong one.
        val row = compose.onNodeWithTag("member-row-${synthIdOf(vm, fid, 0)}")
        row.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(ViewConfiguration.getLongPressTimeout() + 200L)
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithText("Move later").fetchSemanticsNode(); true }
                .getOrDefault(false)
        }
        compose.onNodeWithText("Move later").performClick()

        compose.waitUntil(10_000) {
            vm.state.value.membersOf(fid).map { it.serial } ==
                listOf(SYNTH_BASE + 1, SYNTH_BASE, SYNTH_BASE + 2)
        }
        assertEquals(
            listOf(SYNTH_BASE + 1, SYNTH_BASE, SYNTH_BASE + 2),
            vm.state.value.membersOf(fid).map { it.serial },
        )
    }

    /**
     * Three synthetic people in one family, on screen.
     *
     * Nothing is deleted to get here, and nothing is deleted at the end except what this
     * test created. An earlier version opened by deleting every family and voter in the
     * store, and because it built its ViewModel from the target context that store was the
     * surveyor's real roll -- it deleted a booth off a real phone. The instrumented test
     * APK turns out to have no Application of its own, so it cannot be given private
     * storage; refusing to delete anything it did not create is the guarantee that actually
     * holds, and it holds regardless of whose context the test runs in.
     *
     * Each write is awaited: the booth is captured synchronously and written
     * asynchronously, so firing three without waiting leaves all three reading the same
     * store.
     */
    private fun preparedVm(): SurveyViewModel {
        val vm = SurveyViewModel(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            storeRoot = storeDir,
        )
        lastVm = vm
        compose.waitUntil(10_000) { !vm.state.value.loading }

        created.clear()
        repeat(3) { i ->
            val serial = SYNTH_BASE + i
            vm.startCreateVoter()
            vm.setField("name", "SYNTH $serial")
            vm.setField("serial", serial.toString())
            vm.setField("voterId", "TESTID$serial")
            vm.confirmCreateVoter()
            compose.waitUntil(10_000) {
                vm.state.value.booth.voters.any { it.serial == serial }
            }
            created += vm.state.value.booth.voters.first { it.serial == serial }.id
        }

        // Captured before, compared after. "size > size - 1" is true even when the store
        // is empty, so it passed without a family ever being made -- and the next line then
        // dereferenced the empty list.
        val familiesBefore = vm.state.value.booth.families.size
        vm.createFamily()
        compose.waitUntil(10_000) {
            vm.state.value.booth.families.size == familiesBefore + 1
        }
        val fid = (vm.state.value.booth.families - olderFamilies(vm, familiesBefore).toSet())
            .maxByOrNull { it.order }!!.id
        familyId = fid
        created.forEachIndexed { i, v ->
            vm.addMember(fid, v)
            compose.waitUntil(10_000) { vm.state.value.membersOf(fid).size == i + 1 }
        }
        vm.openFamily(fid)
        return vm
    }

    /** The families that existed before this test made one, so only ours is removed. */
    private fun olderFamilies(vm: SurveyViewModel, count: Int) =
        vm.state.value.booth.families.take(count)

    /**
     * Removes exactly what [preparedVm] created, by id. Anything the surveyor entered stays.
     */
    private fun cleanUp(vm: SurveyViewModel) {
        familyId?.let { id ->
            if (vm.state.value.booth.families.any { it.id == id }) {
                vm.deleteFamily(id)
                compose.waitUntil(10_000) { vm.state.value.booth.families.none { it.id == id } }
            }
        }
        created.forEach { v ->
            if (vm.state.value.booth.voters.any { it.id == v }) {
                vm.deleteVoter(v)
                compose.waitUntil(10_000) { vm.state.value.booth.voters.none { it.id == v } }
            }
        }
        created.clear()
    }

    /** The id of the nth synthetic person, counting from zero, by its SYNTH_BASE serial. */
    private fun synthIdOf(vm: SurveyViewModel, familyId: String, index: Int): String =
        vm.state.value.membersOf(familyId).first { it.serial == SYNTH_BASE + index }.id
}
