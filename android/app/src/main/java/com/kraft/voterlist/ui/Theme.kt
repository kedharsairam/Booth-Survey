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

/**
 * The app's design language.
 *
 * Three things drove the choices, and they are all the same thing: this is read outdoors, on a
 * cheap phone, by somebody who is holding a phone up to a stranger at a door.
 *
 * **Contrast over subtlety.** Every text colour here clears 4.5:1 against the surface it sits
 * on. A grey that looks elegant on a desk disappears in sunlight. The status colours are dark
 * enough to read rather than bright enough to look like a toy.
 *
 * **Size over density.** Body text starts at 16sp and the roster rows are roomy. A crowded
 * screen is a screen somebody misreads, and a misread voter ID is a survey that goes to the
 * wrong house.
 *
 * **One accent, used sparingly.** The teal marks what is actionable. Green, amber and red mean
 * exactly one thing each and appear nowhere else, so a colour on screen is information rather
 * than decoration.
 *
 * Spacing is on an 8px rhythm with a 4px half-step, per the Kraft standards. Nothing is placed
 * at a distance that is not in [Space], which is what makes two screens built separately still
 * look like one app.
 */

private val Ink = Color(0xFF14191C)
private val Teal = Color(0xFF0B5566)
private val TealDark = Color(0xFF6FD3E8)
private val Paper = Color(0xFFF7F9FA)
private val Card = Color(0xFFFFFFFF)
private val Muted = Color(0xFFE9EEF1)
private val Line = Color(0xFFC2CCD2)
private val MutedInk = Color(0xFF4C5A61)

/** Status colours, kept apart from the Material roles because they mean one thing each. */
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
    val xs: androidx.compose.ui.unit.Dp = 4.dp,
    val sm: androidx.compose.ui.unit.Dp = 8.dp,
    val md: androidx.compose.ui.unit.Dp = 12.dp,
    val lg: androidx.compose.ui.unit.Dp = 16.dp,
    val xl: androidx.compose.ui.unit.Dp = 24.dp,
    val xxl: androidx.compose.ui.unit.Dp = 32.dp,
    val huge: androidx.compose.ui.unit.Dp = 48.dp,
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
    val xs: androidx.compose.ui.unit.Dp @Composable get() = LocalSpace.current.xs
    val sm: androidx.compose.ui.unit.Dp @Composable get() = LocalSpace.current.sm
    val md: androidx.compose.ui.unit.Dp @Composable get() = LocalSpace.current.md
    val lg: androidx.compose.ui.unit.Dp @Composable get() = LocalSpace.current.lg
    val xl: androidx.compose.ui.unit.Dp @Composable get() = LocalSpace.current.xl
    val xxl: androidx.compose.ui.unit.Dp @Composable get() = LocalSpace.current.xxl
    val huge: androidx.compose.ui.unit.Dp @Composable get() = LocalSpace.current.huge
}

/**
 * The type scale.
 *
 * Line height is set explicitly rather than left to the platform default, because the default
 * is tuned for Latin body copy at 14sp and this app runs larger text with longer Telugu
 * surnames in it.
 */
private val AppType = Typography().run {
    copy(
        displaySmall = displaySmall.copy(
            fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-1).sp,
        ),
        headlineSmall = headlineSmall.copy(
            fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold,
        ),
        titleLarge = titleLarge.copy(
            fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
        ),
        titleMedium = titleMedium.copy(
            fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
        ),
        titleSmall = titleSmall.copy(
            fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
        ),
        bodyLarge = bodyLarge.copy(fontSize = 17.sp, lineHeight = 23.sp),
        bodyMedium = bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
        bodySmall = bodySmall.copy(fontSize = 13.sp, lineHeight = 17.sp),
        labelLarge = labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.copy(fontSize = 13.sp, lineHeight = 16.sp),
        labelSmall = labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val LightScheme = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE9F1),
    onPrimaryContainer = Color(0xFF05323C),
    secondary = Color(0xFF4A6572),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE7EC),
    onSecondaryContainer = Color(0xFF1B2C34),
    tertiary = Color(0xFF6B5B2E),
    onTertiary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Card,
    onSurface = Ink,
    surfaceVariant = Muted,
    onSurfaceVariant = MutedInk,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFCFD),
    surfaceContainer = Paper,
    surfaceContainerHigh = Color(0xFFEFF3F5),
    outline = Line,
    outlineVariant = Color(0xFFD9E0E4),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFCE8E6),
    onErrorContainer = Color(0xFF5F1512),
    scrim = Color(0xFF000000),
)

private val DarkScheme = darkColorScheme(
    primary = TealDark,
    onPrimary = Color(0xFF00323D),
    primaryContainer = Color(0xFF0B4A57),
    onPrimaryContainer = Color(0xFFCDE9F1),
    secondary = Color(0xFFB4CBD5),
    onSecondary = Color(0xFF1E343D),
    background = Color(0xFF0E1214),
    onBackground = Color(0xFFE3E8EA),
    surface = Color(0xFF141A1D),
    onSurface = Color(0xFFE3E8EA),
    surfaceVariant = Color(0xFF232C30),
    onSurfaceVariant = Color(0xFFB9C4CA),
    outline = Color(0xFF6C7A81),
    outlineVariant = Color(0xFF333E43),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun KraftTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Dark mode is honoured rather than forced off. Somebody walking a street at dusk gets a
    // screen they can actually read, and nobody has to remember a setting.
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
            typography = AppType,
            shapes = AppShapes,
            content = content,
        )
    }
}
