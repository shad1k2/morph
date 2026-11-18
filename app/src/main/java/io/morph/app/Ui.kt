package io.morph.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.morph.core.ShellId

/** Surface brush for a shell card. */
fun shellBrush(theme: ShellTheme): Brush = when (theme.cardStyle) {
    CardStyle.GLASS -> Brush.linearGradient(
        listOf(theme.surface.copy(alpha = 0.9f), theme.surfaceAlt.copy(alpha = 0.9f))
    )
    CardStyle.LAYERED -> Brush.verticalGradient(listOf(theme.surface, theme.surfaceAlt))
    else -> Brush.linearGradient(listOf(theme.surface, theme.surface))
}

/** Card modifier: elevation for light packs, clip + fill + hairline outline. */
fun Modifier.shellSurface(
    theme: ShellTheme,
    shape: Shape,
    elevated: Boolean = false,
): Modifier = this
    .then(
        if (elevated || theme.cardStyle == CardStyle.LIGHT) {
            Modifier.shadow(
                elevation = 18.dp,
                shape = shape,
                ambientColor = Color(0x1F000000),
                spotColor = Color(0x33000000),
            )
        } else if (theme.cardStyle == CardStyle.FLAT && theme.shell != ShellId.NOTHING_OS) {
            Modifier.shadow(2.dp, shape)
        } else {
            Modifier
        }
    )
    .clip(shape)
    .background(shellBrush(theme))
    .then(
        if (theme.border.alpha > 0f) Modifier.border(1.dp, theme.border, shape) else Modifier
    )

@Composable
fun BrandOval(
    theme: ShellTheme,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .shadow(12.dp, shape)
            .clip(shape)
            .background(theme.brandBg)
            .border(1.dp, Color(0x33FFFFFF), shape)
            .padding(
                horizontal = if (compact) 10.dp else 14.dp,
                vertical = if (compact) 5.dp else 7.dp,
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(if (compact) 5.dp else 7.dp)
                    .background(
                        if (theme.shell == ShellId.NOTHING_OS) Color(0xFFD71921)
                        else theme.brandInk.copy(alpha = 0.9f),
                        RoundedCornerShape(50),
                    )
            )
            Spacer(Modifier.width(if (compact) 5.dp else 7.dp))
            Text(
                theme.brandLabel,
                color = theme.brandInk,
                fontFamily = theme.headline ?: FontFamily.Default,
                fontWeight = FontWeight.Bold,
                fontSize = if (compact) 10.sp else 11.sp,
                letterSpacing = if (theme.shell == ShellId.NOTHING_OS) 0.4.sp else 0.2.sp,
            )
        }
    }
}

@Composable
fun MonoLabel(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Int = 9,
    tracking: Float = 1.1f,
) {
    Text(
        text,
        modifier = modifier,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = size.sp,
        letterSpacing = tracking.sp,
    )
}

@Composable
fun PrimaryAction(
    text: String,
    theme: ShellTheme,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = actionShape(theme)
    val brush = when {
        !enabled -> Brush.linearGradient(listOf(theme.dim.copy(alpha = 0.25f), theme.dim.copy(alpha = 0.25f)))
        theme.buttonStyle == ButtonStyle.GRADIENT -> Brush.horizontalGradient(listOf(theme.accent, theme.accentAlt))
        theme.shell == ShellId.NOTHING_OS -> Brush.linearGradient(listOf(theme.text, theme.text))
        else -> Brush.linearGradient(listOf(theme.accent, theme.accent))
    }
    val ink = when {
        !enabled -> theme.dim
        theme.shell == ShellId.NOTHING_OS -> Color(0xFF101012)
        theme.buttonStyle == ButtonStyle.GRADIENT -> theme.onAccent
        else -> theme.onAccent
    }

    Box(
        modifier
            .clip(shape)
            .background(brush)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = ink,
            fontFamily = theme.body ?: FontFamily.Default,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = if (theme.shell == ShellId.NOTHING_OS) 1.sp else 0.4.sp,
        )
    }
}

@Composable
fun SecondaryAction(
    text: String,
    theme: ShellTheme,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = actionShape(theme)
    val outline = if (theme.cardStyle == CardStyle.LIGHT) theme.accent.copy(alpha = 0.4f) else theme.border
    Box(
        modifier
            .clip(shape)
            .border(1.dp, outline, shape)
            .clickable { onClick() }
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = theme.text,
            fontFamily = theme.body ?: FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            letterSpacing = if (theme.shell == ShellId.NOTHING_OS) 0.8.sp else 0.3.sp,
        )
    }
}

fun actionShape(theme: ShellTheme): Shape = RoundedCornerShape(
    when (theme.buttonStyle) {
        ButtonStyle.PILL -> 50.dp
        ButtonStyle.ROUNDED -> 16.dp
        ButtonStyle.SQUARE -> 2.dp
        ButtonStyle.GRADIENT -> 18.dp
    }
)

@Composable
fun SignalBars(level: Int, theme: ShellTheme, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(4) { index ->
            val active = index < level
            Box(
                Modifier
                    .width(3.dp)
                    .height((4 + index * 3).dp)
                    .background(
                        if (active) theme.accent else theme.border.copy(alpha = 0.5f),
                        RoundedCornerShape(1.dp),
                    )
            )
        }
    }
}

@Composable
fun Hairline(theme: ShellTheme, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(theme.border.copy(alpha = 0.55f))
    )
}
