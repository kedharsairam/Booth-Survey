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
/**
 * Card outlines and field borders.
 *
 * Was #C2CCD2, which measures 1.55:1 against Paper and 1.63:1 against Card -- well under
 * the 3:1 that WCAG asks of any boundary doing real work, and under it in a way that only
 * shows up outside. A card whose edge has faded into the background is no longer a card;
 * on a bright day the family list stops being a list of separate households and becomes one
 * block of text with gaps in it.
 *
 * #78868E measures 3.55:1 on Paper, 3.75:1 on Card and 3.21:1 on the muted fill chips sit
 * on, so it clears the bar on every surface this app actually draws it against. It is
 * darker than the brief would suggest indoors, and that is the trade being made on purpose:
 * the indoor cost is a slightly heavier line, the outdoor cost of the old one was not being
 * able to see where a household ended.
 */
private val Line = Color(0xFF78868E)
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
            fontSize = KraftTypeScale.Title3, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
        ),
        titleMedium = titleMedium.copy(
            fontSize = KraftTypeScale.Headline, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
        ),
        titleSmall = titleSmall.copy(
            fontSize = KraftTypeScale.Subheadline, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
        ),
        bodyLarge = bodyLarge.copy(fontSize = KraftTypeScale.Body, lineHeight = 23.sp),
        bodyMedium = bodyMedium.copy(fontSize = KraftTypeScale.Subheadline, lineHeight = 20.sp),
        bodySmall = bodySmall.copy(fontSize = KraftTypeScale.Footnote, lineHeight = 17.sp),
        labelLarge = labelLarge.copy(fontSize = KraftTypeScale.Subheadline, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.copy(fontSize = KraftTypeScale.Footnote, lineHeight = 16.sp),
        labelSmall = labelSmall.copy(fontSize = KraftTypeScale.Caption2, lineHeight = 14.sp, letterSpacing = 0.4.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(KraftRadius.Small),
    small = RoundedCornerShape(KraftRadius.Small),
    medium = RoundedCornerShape(KraftRadius.Medium),
    large = RoundedCornerShape(KraftRadius.Hero),
    extraLarge = RoundedCornerShape(KraftRadius.Modal),
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
    // Dividers were Color(0xFFD9E0E4) and measured 1.26:1 on Paper. That is fine on a desk
    // and gone in daylight: at a brightness where the body text is still comfortably
    // readable, the line that tells him one person's row has ended and the next has begun
    // has already disappeared, and a list of names becomes a wall. WCAG asks 3:1 for any
    // boundary that carries meaning, and a row divider is exactly that. This clears it and
    // stays lighter than [Line] so the card outline still reads as the stronger edge.
    outlineVariant = Color(0xFF849198),
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
    // Same reason as the light scheme's divider. #333E43 on #141A1D is 1.34:1 -- the row
    // edges were effectively absent in dark too.
    outlineVariant = Color(0xFF5A676D),
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
