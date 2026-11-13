package com.kraft.voterlist.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kraft.ui.tokens.KraftTypeScale
import com.kraft.ui.tokens.KraftRadius
import com.kraft.ui.tokens.KraftSpacing

/**
 * Komi Store manga theme — thick panel borders, cream paper, green accent.
 *
 * Designed for a 70-year-old using this outdoors at a doorstep:
 *
 * **Boundaries first.** Every card, button, field and row gets a thick black
 * border so nothing floats without an edge. The old theme's hairlines vanish in
 * daylight; manga panels don't.
 *
 * **Cream paper, not stark white.** Background is #FDF6E3 — manga paper, easier
 * on aging eyes than pure white, still high contrast with black text and borders.
 *
 * **Green accent (#1B5E20).** Matches the app icon. Used only for actionable
 * elements — filled buttons, toggles on, the active tab. Nothing else is green,
 * so a green thing on screen means "tap this."
 *
 * **Bold typography.** Headings are heavy and large; body stays at 16sp minimum.
 * Text is black on cream, never gray on gray.
 *
 * Spacing stays on the 8px rhythm from Kraft standards.
 */

private val MangaPaper = Color(0xFFFDFFE3)   // background — cream like manga pages
private val MangaWhite = Color(0xFFFFFFFF)   // surface — panel fill
private val MangaInk = Color(0xFF14191C)     // borders + primary text — near black
private val MangaGreen = Color(0xFF1B5E20)   // accent — matches the app icon
private val MangaGreenSoft = Color(0xFFE8F5E9) // accent container — light green fill
private val MangaMuted = Color(0xFF4C5A61)   // secondary text — readable but quiet
private val MangaBorder = Color(0xFF14191C)  // panel outlines — thick, visible

/** Status colours — kept separate from Material roles, one meaning each. */
@Immutable
data class StatusColours(
    val done: Color,
    val pending: Color,
    val bad: Color,
    val onStatus: Color,
)

/** The 8px rhythm. Use these rather than typing a number at a call site. */
@Immutable
data class Spacing(
    val xs: androidx.compose.ui.unit.Dp = KraftSpacing.Spacing4,
    val sm: androidx.compose.ui.unit.Dp = KraftSpacing.Spacing8,
    val md: androidx.compose.ui.unit.Dp = KraftSpacing.Spacing12,
    val lg: androidx.compose.ui.unit.Dp = KraftSpacing.Spacing16,
    val xl: androidx.compose.ui.unit.Dp = KraftSpacing.Spacing24,
    val xxl: androidx.compose.ui.unit.Dp = KraftSpacing.Spacing32,
    val huge: androidx.compose.ui.unit.Dp = KraftSpacing.Spacing48,
)

val LocalStatus = staticCompositionLocalOf {
    StatusColours(
        done = Color(0xFF17663F),
        pending = Color(0xFF8A5A00),
        bad = Color(0xFFB3261E),
        onStatus = Color.White,
    )
}
val LocalSpace = staticCompositionLocalOf { Spacing() }

/** Shorthand so a screen reads `Status.done` rather than importing three top-level vals. */
object Status {
    val done: Color @Composable get() = LocalStatus.current.done
    val pending: Color @Composable get() = LocalStatus.current.pending
    val bad: Color @Composable get() = LocalStatus.current.bad
    val onStatus: Color @Composable get() = LocalStatus.current.onStatus
}

object Space {
    val none: androidx.compose.ui.unit.Dp @Composable get() = androidx.compose.ui.unit.Dp(0f)
    val xs: androidx.compose.ui.unit.Dp @Composable get() = LocalSpace.current.xs
    val sm: androidx.compose.ui.unit.Dp @Composable get() = LocalSpace.current.sm
    val md: androidx.compose.ui.unit.Dp @Composable get() = LocalSpace.current.md
    val lg: androidx.compose.ui.unit.Dp @Composable get() = LocalSpace.current.lg
    val xl: androidx.compose.ui.unit.Dp @Composable get() = LocalSpace.current.xl
    val xxl: androidx.compose.ui.unit.Dp @Composable get() = LocalSpace.current.xxl
    val huge: androidx.compose.ui.unit.Dp @Composable get() = LocalSpace.current.huge
}

/** Type scale — manga style: bold headings, readable body, no gray-on-gray. */
private val MangaType = Typography().run {
    copy(
        displaySmall = displaySmall.copy(
            fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-1).sp,
        ),
        headlineSmall = headlineSmall.copy(
            fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold,
        ),
        titleLarge = titleLarge.copy(
            fontSize = KraftTypeScale.Title3, lineHeight = 26.sp, fontWeight = FontWeight.Bold,
        ),
        titleMedium = titleMedium.copy(
            fontSize = KraftTypeScale.Headline, lineHeight = 22.sp, fontWeight = FontWeight.Bold,
        ),
        titleSmall = titleSmall.copy(
            fontSize = KraftTypeScale.Subheadline, lineHeight = 20.sp, fontWeight = FontWeight.Bold,
        ),
        bodyLarge = bodyLarge.copy(fontSize = KraftTypeScale.Body, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
        bodyMedium = bodyMedium.copy(fontSize = KraftTypeScale.Subheadline, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        bodySmall = bodySmall.copy(fontSize = KraftTypeScale.Footnote, lineHeight = 17.sp),
        labelLarge = labelLarge.copy(fontSize = KraftTypeScale.Subheadline, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
        labelMedium = labelMedium.copy(fontSize = KraftTypeScale.Footnote, lineHeight = 16.sp),
        labelSmall = labelSmall.copy(fontSize = KraftTypeScale.Caption2, lineHeight = 14.sp, letterSpacing = 0.4.sp),
    )
}

/** Panel shapes — slightly rounded corners for manga panels, never pill-shaped. */
private val MangaShapes = Shapes(
    extraSmall = RoundedCornerShape(KraftRadius.Small),
    small = RoundedCornerShape(KraftRadius.Standard),
    medium = RoundedCornerShape(KraftRadius.Medium),
    large = RoundedCornerShape(KraftRadius.Hero),
    extraLarge = RoundedCornerShape(KraftRadius.Modal),
)

private val LightScheme = lightColorScheme(
    primary = MangaGreen,
    onPrimary = Color.White,
    primaryContainer = MangaGreenSoft,
    onPrimaryContainer = MangaGreen,
    secondary = MangaInk,
    onSecondary = Color.White,
    secondaryContainer = MangaWhite,
    onSecondaryContainer = MangaInk,
    tertiary = MangaMuted,
    onTertiary = Color.White,
    background = MangaPaper,
    onBackground = MangaInk,
    surface = MangaWhite,
    onSurface = MangaInk,
    surfaceVariant = MangaWhite,
    onSurfaceVariant = MangaMuted,
    surfaceContainerLowest = MangaWhite,
    surfaceContainerLow = MangaWhite,
    surfaceContainer = MangaPaper,
    surfaceContainerHigh = MangaGreenSoft,
    outline = MangaBorder,
    outlineVariant = MangaBorder,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFCE8E6),
    onErrorContainer = Color(0xFF5F1512),
    scrim = Color(0xFF000000),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF4CAF50),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1B5E20),
    onPrimaryContainer = Color.White,
    secondary = MangaMuted,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2C3338),
    onSecondaryContainer = MangaPaper,
    tertiary = MangaMuted,
    onTertiary = Color.White,
    background = MangaInk,
    onBackground = MangaPaper,
    surface = Color(0xFF1E2326),
    onSurface = MangaPaper,
    surfaceVariant = Color(0xFF2C3338),
    onSurfaceVariant = MangaPaper,
    outline = MangaPaper,
    outlineVariant = MangaPaper,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun KraftTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Dark mode is honoured rather than forced off. Somebody walking a street at dusk gets
    // a screen they can actually read, and nobody has to remember a setting.
    @Composable
    fun status() = if (dark) {
        StatusColours(
            done = Color(0xFF6FD79B),
            pending = Color(0xFFE8B65A),
            bad = Color(0xFFFFB4AB),
            onStatus = Color(0xFF0E1214),
        )
    } else {
        LocalStatus.current
    }

    CompositionLocalProvider(LocalStatus provides status()) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = MangaType,
            shapes = MangaShapes,
            content = content,
        )
    }
}

/** Border width for manga panels — thicker than hairline so it survives daylight. */
val MangaBorderWidth = 2.dp

/** Panel elevation — subtle lift so panels read as separate objects on the page. */
val MangaPanelElevation = 2.dp
