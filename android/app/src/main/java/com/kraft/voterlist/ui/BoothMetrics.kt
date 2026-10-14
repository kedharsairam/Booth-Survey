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

    /**
     * The smallest thing a fingertip is asked to hit.
     *
     * The shared token is 44dp, from Apple's guidance. Android's floor is 48dp, and passing
     * `Modifier.size(44.dp)` to a Material button overrides the 48dp minimum it would
     * otherwise supply -- so every call site was actively below the platform target rather
     * than merely not gaining from it. The user this app is for is over sixty, so the
     * platform's figure is the one that applies here.
     */
    val TouchTarget = 48.dp

    /** Input field height, shared by voter edit, family, and import screens. */
    val FieldHeight = 52.dp

    /** Capped scroll region: the member picker list and the filter sheet's panes. Tall enough
     * to choose from, short enough that the action below it stays on screen. */
    val FamilyListHeight = 340.dp

    /** Home card minimum: keeps its shape while booth data loads. */
    val HomeCardMin = 152.dp

    /**
     * Dropdown metrics. Component sizes, not spacing, so they live here rather than in the
     * foundation's token set: the numbers are wrong for any other app, exactly like the field
     * heights above.
     */
    object Dropdown {
        /** Narrowest a menu is allowed to be, so a two-word choice is never a sliver. */
        val MinWidth = 200.dp

        /** Cap on the scrolling list. Past this the menu stops being a choice from a field and
         * becomes a screen, and the field it was opened from disappears behind it. */
        val MaxListHeight = 300.dp

        /**
         * One choice's height.
         *
         * 56dp, not the 48dp the platform would give. The user is over sixty and reads these
         * lists off a printed roll in daylight; 48dp is the floor for a target you know where
         * to put, and this is a target you have to read first, aim at, and confirm.
         */
        val ChoiceHeight = 56.dp

        /** The tick's box. Reserved whether or not a tick is showing, so labels never shift
         * sideways as the eye moves down a list that has one selected value in it. */
        val TickBox = 24.dp
    }
}
