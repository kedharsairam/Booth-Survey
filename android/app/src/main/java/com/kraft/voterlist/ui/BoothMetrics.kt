package com.kraft.voterlist.ui

import androidx.compose.ui.unit.dp

/**
 * The dimensions that belong to this booth and to no other.
 *
 * Spacing, radius, type and touch targets come from kraft-foundation and are shared across the
 * portfolio. The values here are survey-instrument dimensions — field heights, list heights,
 * card minimums — that would be wrong in any other app, which is the same test that decides
 * an app's accent belongs to the app and not to the foundation.
 *
 * FieldHeight is shared by five input fields, which is the whole reason it is declared
 * rather than rounded: 52dp is a touch target with room for a label, and moving five
 * fields for the sake of a number is the opposite of a move-only migration.
 */
object BoothMetrics {

    /** Input field height, shared by voter edit, family, and import screens. */
    val FieldHeight = 52.dp

    /** Family list height: the scroll region for a booth's families. */
    val FamilyListHeight = 340.dp

    /** Home card minimum: keeps its shape while booth data loads. */
    val HomeCardMin = 152.dp
}
