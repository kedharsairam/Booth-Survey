package com.kraft.voterlist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kraft.ui.tokens.KraftRadius
import com.kraft.ui.tokens.KraftSpacing

/**
 * Manga panel — the container everything sits in.
 *
 * Thick black border, white fill. Nothing floats without an edge.
 */
@Composable
fun MangaPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MangaWhite),
        shape = RoundedCornerShape(KraftRadius.Standard),
    ) {
        content()
    }
}

/**
 * Manga button — filled green, black border, white text.
 */
@Composable
fun MangaButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null,
    label: String,
) {
    val colors = ButtonDefaults.buttonColors(
        containerColor = if (enabled) MangaGreen else MangaBorder.copy(alpha = 0.2f),
        contentColor = Color.White,
        disabledContainerColor = MangaBorder.copy(alpha = 0.15f),
        disabledContentColor = MangaBorder.copy(alpha = 0.4f),
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = MangaTouchTarget),
        colors = colors,
        shape = RoundedCornerShape(KraftRadius.Standard),
        contentPadding = PaddingValues(horizontal = KraftSpacing.Spacing16, vertical = KraftSpacing.Spacing12),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KraftSpacing.Spacing8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                icon()
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
            )
        }
    }
}

/**
 * Manga field — text field with a thick black border.
 */
@Composable
fun MangaField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    maxLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { if (label != null) Text(label) else null },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = singleLine,
        maxLines = maxLines,
        shape = RoundedCornerShape(KraftRadius.Standard),
    )
}

/**
 * Manga chip — small filled surface for status words.
 */
@Composable
fun MangaChip(
    label: String,
    color: Color = MangaBorder,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = color,
        shape = RoundedCornerShape(KraftRadius.Small),
        modifier = modifier,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(
                horizontal = KraftSpacing.Spacing8,
                vertical = KraftSpacing.Spacing4,
            ),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Manga touch target — the minimum tappable area, one value everywhere.
 */
val MangaTouchTarget = KraftSpacing.TouchTarget
