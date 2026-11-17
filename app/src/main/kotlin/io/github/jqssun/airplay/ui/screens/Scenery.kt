package io.github.jqssun.airplay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DesktopMac
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.TabletMac
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.DeviceKind
import io.github.jqssun.airplay.deviceKind
import io.github.jqssun.airplay.ui.design.ChipKind
import io.github.jqssun.airplay.ui.design.StatusChip
import io.github.jqssun.airplay.ui.design.Wordmark
import io.github.jqssun.airplay.ui.theme.Atv

// Screen-level pieces shared by several screens (the prototypes draw them inline).

fun deviceIcon(model: String): ImageVector = when (deviceKind(model)) {
    DeviceKind.PHONE -> Icons.Rounded.PhoneIphone
    DeviceKind.TABLET -> Icons.Rounded.TabletMac
    DeviceKind.LAPTOP -> Icons.Rounded.Laptop
    DeviceKind.DESKTOP -> Icons.Rounded.DesktopMac
    DeviceKind.OTHER -> Icons.Rounded.Devices
}

/** Wordmark on the left, status chip on the right (home and no-network screens). */
@Composable
fun TopBar(chipText: String, chipKind: ChipKind, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Wordmark()
        StatusChip(chipText, chipKind, Modifier.padding(start = Atv.Space4).widthIn(max = 480.dp))
    }
}

/** 48dp rounded tile holding a device icon (request card, device rows). */
@Composable
fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    background: Color = Atv.Pasillo,
    tint: Color = Atv.Palomita,
) {
    Box(
        modifier = modifier.size(48.dp).clip(Atv.ShapeTile).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/** A dot of the night sky: top-left corner at ([x], [y]) on the stage, [size] dp across. */
@Immutable
class Star(val x: Int, val y: Int, val size: Float, val color: Color, val alpha: Float)

/** Draws [stars] over the whole stage, behind everything else. */
@Composable
fun Starfield(stars: List<Star>, modifier: Modifier = Modifier) {
    Spacer(
        modifier.fillMaxSize().drawBehind {
            stars.forEach { s ->
                val r = s.size.dp.toPx() / 2
                drawCircle(s.color, r, Offset(s.x.dp.toPx() + r, s.y.dp.toPx() + r), alpha = s.alpha)
            }
        }
    )
}
