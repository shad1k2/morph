package io.morph.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun HomeScreen(
    theme: ShellTheme,
    devices: List<NearbyDevice>,
    onDeviceTap: (NearbyDevice) -> Unit,
    onSendFiles: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {

        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "MORPH",
                    color = theme.text,
                    fontFamily = theme.headline ?: FontFamily.Default,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    letterSpacing = 1.6.sp,
                )
                MonoLabel("DROP/1 · LOCAL ONLY", theme.dim, size = 8)
            }
            MonoLabel(
                "SETTINGS",
                theme.dim,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, theme.border, RoundedCornerShape(50))
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                size = 9,
                tracking = 0.8f,
            )
            Spacer(Modifier.width(8.dp))
            BrandOval(theme)
        }

        Spacer(Modifier.height(26.dp))

        Text(
            if (theme.uppercaseHeadline) "DROP ANYTHING" else "Drop anything.",
            color = theme.text,
            fontFamily = theme.headline ?: FontFamily.Default,
            fontWeight = FontWeight.Bold,
            fontSize = 37.sp,
            lineHeight = 40.sp,
            letterSpacing = theme.headlineTracking.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "One tap. Files move phone to phone — no cloud, no cable, no account.",
            color = theme.dim,
            fontFamily = theme.body ?: FontFamily.Default,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )

        Spacer(Modifier.height(6.dp))
        Radar(theme, devices)
        MonoLabel(
            "VISIBLE TO ${devices.size} NEARBY DEVICES",
            theme.dim,
            modifier = Modifier.fillMaxWidth(),
            size = 8,
            tracking = 1.2f,
        )

        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoLabel("NEARBY", theme.dim, size = 9, tracking = 1.4f)
            Spacer(Modifier.width(10.dp))
            Hairline(theme, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            devices.forEach { device ->
                DeviceRow(theme, device) { onDeviceTap(device) }
            }
        }

        Spacer(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryAction("SEND FILES", theme, Modifier.weight(1f)) { onSendFiles() }
            SecondaryAction("RECEIVE", theme, Modifier.weight(0.62f)) { }
        }
        Spacer(Modifier.height(10.dp))
        MonoLabel(
            "NFC TAP · BLE CONTROL · WI-FI DIRECT DATA",
            theme.dim,
            modifier = Modifier.fillMaxWidth(),
            size = 8,
            tracking = 1f,
        )
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun Radar(theme: ShellTheme, devices: List<NearbyDevice>) {
    val transition = rememberInfiniteTransition(label = "radar")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing)),
        label = "spin",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.86f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1700), RepeatMode.Reverse),
        label = "pulse",
    )

    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val origin = center
            val radius = size.minDimension / 2f - 8.dp.toPx()

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(theme.accent.copy(alpha = 0.20f), Color.Transparent),
                    center = origin,
                    radius = radius,
                ),
                radius = radius,
                center = origin,
            )

            repeat(3) { index ->
                drawCircle(
                    color = theme.text.copy(alpha = 0.10f),
                    radius = radius * (index + 1) / 3f,
                    center = origin,
                    style = Stroke(1.dp.toPx()),
                )
            }

            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(Color.Transparent, theme.accent.copy(alpha = 0.30f)),
                    center = origin,
                ),
                startAngle = spin - 90f,
                sweepAngle = 60f,
                useCenter = true,
            )

            val radians = Math.toRadians(spin.toDouble() - 90.0)
            drawLine(
                color = theme.accent.copy(alpha = 0.55f),
                start = origin,
                end = Offset(
                    origin.x + cos(radians).toFloat() * radius,
                    origin.y + sin(radians).toFloat() * radius,
                ),
                strokeWidth = 1.5.dp.toPx(),
            )

            devices.take(3).forEachIndexed { index, device ->
                val angle = Math.toRadians((-90 + index * 120).toDouble())
                val ring = radius * 0.72f
                val point = Offset(
                    origin.x + cos(angle).toFloat() * ring,
                    origin.y + sin(angle).toFloat() * ring,
                )
                val peer = ThemeRegistry.get(device.shell)
                drawCircle(peer.brandBg.copy(alpha = 0.22f), 21.dp.toPx(), point)
                drawCircle(peer.brandBg, 9.dp.toPx(), point)
            }
        }

        Box(
            Modifier
                .size((76 * pulse).dp)
                .clip(RoundedCornerShape(50))
                .background(theme.brandBg)
                .border(1.dp, theme.text.copy(alpha = 0.22f), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "ME",
                color = theme.brandInk,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
            )
        }
    }
}

@Composable
private fun DeviceRow(theme: ShellTheme, device: NearbyDevice, onClick: () -> Unit) {
    val shape = RoundedCornerShape(theme.radius * 0.55f)
    Row(
        Modifier
            .fillMaxWidth()
            .shellSurface(theme, shape)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val peer = ThemeRegistry.get(device.shell)
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(peer.brandBg.copy(alpha = if (theme.isDark) 0.22f else 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                deviceInitial(device.name),
                color = peer.brandBg,
                fontFamily = theme.headline ?: FontFamily.Default,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                device.name,
                color = theme.text,
                fontFamily = theme.body ?: FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
            Text(
                "${peer.name} · ${device.distanceMeters} m",
                color = theme.dim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
        SignalBars(device.signal, theme)
        Spacer(Modifier.width(10.dp))
        Text(
            "DROP",
            color = theme.accent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = 0.8.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .border(1.dp, theme.accent.copy(alpha = 0.45f), RoundedCornerShape(50))
                .padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}
