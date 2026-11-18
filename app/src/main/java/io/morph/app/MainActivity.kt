package io.morph.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.morph.core.ShellId

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MorphPreview() }
    }
}

/** Pop-up style preview: brand oval in the top corner + themed drop card. */
@Composable
fun MorphPreview() {
    var shell by remember { mutableStateOf(ShellDetector.detect()) }
    val theme = ThemeRegistry.get(shell)

    Column(
        Modifier
            .fillMaxSize()
            .background(theme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Box(Modifier.fillMaxWidth().height(72.dp)) {
            Text(
                "MORPH / DROP PREVIEW",
                modifier = Modifier.align(Alignment.TopStart).padding(20.dp),
                color = theme.dim,
                fontFamily = theme.headline ?: FontFamily.Default,
                fontSize = 11.sp,
            )
            BrandOval(theme, Modifier.align(Alignment.TopEnd).padding(16.dp))
        }

        DropCard(theme)
        Spacer(Modifier.height(20.dp))
        ThemeSwitcher(shell) { shell = it }
    }
}

/** Oval brand badge pinned to the top corner of the pop-up. */
@Composable
private fun BrandOval(theme: ShellTheme, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50.dp)
    val background = if (theme.glass) theme.surface else theme.accent
    val foreground = if (theme.glass) theme.text else theme.onAccent

    Box(
        modifier
            .clip(shape)
            .background(background)
            .border(1.dp, theme.border, shape)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        if (theme.shell == ShellId.NOTHING_OS) Color(0xFFD71921) else theme.text,
                        RoundedCornerShape(50.dp),
                    )
            )
            Spacer(Modifier.width(8.dp))
            Text(
                theme.brand,
                color = foreground,
                fontFamily = theme.headline,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun DropCard(theme: ShellTheme) {
    val shape = RoundedCornerShape(theme.radius)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(shape)
            .background(theme.surface)
            .border(1.dp, theme.border, shape)
            .padding(24.dp)
    ) {
        Text(
            "DROP READY",
            color = theme.accent,
            fontFamily = theme.headline ?: theme.body,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Incoming transfer · 2 files · 3.8 MB",
            color = theme.dim,
            fontFamily = theme.body,
            fontSize = 12.sp,
        )

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(theme.accent.copy(alpha = 0.18f))
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text("photo.jpg", color = theme.text, fontFamily = theme.body, fontSize = 15.sp)
                Text("2.4 MB · JPEG", color = theme.dim, fontFamily = theme.body, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("ACCEPT", theme, filled = true, modifier = Modifier.weight(1f))
            ActionButton("DECLINE", theme, filled = false, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    theme: ShellTheme,
    filled: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(if (theme.shell == ShellId.ONE_UI) 50.dp else theme.radius)
    Box(
        modifier
            .clip(shape)
            .background(if (filled) theme.accent else Color.Transparent)
            .border(1.dp, theme.accent, shape)
            .clickable { }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (filled) theme.onAccent else theme.accent,
            fontFamily = theme.body,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun ThemeSwitcher(current: ShellId, onSelect: (ShellId) -> Unit) {
    val scroll = rememberScrollState()
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 20.dp)) {
        ThemeRegistry.all.forEach { theme ->
            val selected = theme.shell == current
            val shape = RoundedCornerShape(50.dp)
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .clip(shape)
                    .background(if (selected) theme.accent else Color.Transparent)
                    .border(1.dp, if (selected) theme.accent else theme.border, shape)
                    .clickable { onSelect(theme.shell) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    theme.name,
                    color = if (selected) theme.onAccent else theme.dim,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
