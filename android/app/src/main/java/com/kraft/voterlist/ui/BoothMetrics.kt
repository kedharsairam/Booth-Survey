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

        /** How far the menu's own surface is tinted up, and how far it is lifted off the
         * form behind it. A menu has to read as above the thing it covers, which is what
         * these two do; neither is spacing, which is why they are here and not in a token. */
        val TonalElevation = 3.dp
        val ShadowElevation = 8.dp
    }

    /**
     * The numbers that belong to this app's chrome rather than to any of its screens.
     *
     * Declared here rather than left as a literal at the call site with a waiver comment
     * beside it. The waiving worked in kraft-lint's plain output but not in the GitHub
     * format CI uses, so a correctly-waived line was failing the build; giving these a
     * name is better anyway, because "what is this 1dp for" is a question a reader should
     * not have to answer from a file 500 lines away.
     */
    object Chrome {
        /** A hairline border. The thickness is the point; 2dp reads as a border, 1dp as a
         * hairline, and neither is a spacing step between two elements. */
        val Hairline = 1.dp

        /** Inset on a status choice's caption, kept tight to its own control. */
        val CaptionInset = 4.dp

        /** Gap under a section caption, which sits closer to its own field than to the
         * section above it. */
        val CaptionGap = 2.dp

        /** Bounds of a measured text column: the narrowest a serial column may be, and the
         * widest a four-digit number needs before the name is squeezed. A function of the
         * text's own metrics, so it cannot sit on the 8px rhythm. */
        val MinNumberColumn = 16.dp
        val MaxNumberColumn = 96.dp
    }
}
