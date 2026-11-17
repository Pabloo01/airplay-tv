package io.github.jqssun.airplay.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.ui.design.ChipKind
import io.github.jqssun.airplay.ui.design.Eyebrow
import io.github.jqssun.airplay.ui.design.FloorShadow
import io.github.jqssun.airplay.ui.design.PillButton
import io.github.jqssun.airplay.ui.design.SpeechBubble
import io.github.jqssun.airplay.ui.design.Stage
import io.github.jqssun.airplay.ui.design.Pepe
import io.github.jqssun.airplay.ui.design.PepeMood
import io.github.jqssun.airplay.ui.design.Wall
import io.github.jqssun.airplay.ui.requestFocusUntilLanded
import io.github.jqssun.airplay.ui.theme.Atv
import io.github.jqssun.airplay.ui.theme.AtvType
import java.net.Inet4Address
import java.net.NetworkInterface

private val lanTransports = intArrayOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_ETHERNET)

private fun ConnectivityManager.hasLan(except: Network? = null): Boolean = allNetworks.any { n ->
    n != except && getNetworkCapabilities(n)?.let { caps -> lanTransports.any(caps::hasTransport) } == true
}

@Composable
fun rememberLanAvailable(): Boolean {
    val context = LocalContext.current
    val cm = remember(context) { context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    var available by remember { mutableStateOf(cm.hasLan()) }
    DisposableEffect(cm) {
        val request = NetworkRequest.Builder().apply { lanTransports.forEach(::addTransportType) }.build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { available = true }
            override fun onLost(network: Network) { available = cm.hasLan(except = network) }
        }
        cm.registerNetworkCallback(request, callback)
        onDispose { cm.unregisterNetworkCallback(callback) }
    }
    return available
}

fun localIpv4(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress
}.getOrNull()

// ---------- 10 · no Wi-Fi ----------

@Composable
fun NoNetworkScreen(onOpenNetworkSettings: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocusUntilLanded() }
    val chip = stringResource(R.string.chip_no_network)
    val bubble = stringResource(R.string.no_network_bubble)
    val copy: @Composable ColumnScope.() -> Unit = {
        Eyebrow(stringResource(R.string.no_network_eyebrow))
        Text(stringResource(R.string.no_network_title), style = AtvType.Title)
        Text(stringResource(R.string.no_network_desc), style = AtvType.Body, color = Atv.InkMuted)
        PillButton(
            stringResource(R.string.btn_network_settings),
            icon = Icons.Rounded.Wifi,
            onClick = onOpenNetworkSettings,
            modifier = Modifier.padding(top = 10.dp).focusRequester(focus),
        )
    }
    Wall {
        Stage(
            narrow = {
                TopBar(chip, ChipKind.NEUTRAL)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp), content = copy)
                SpeechBubble(bubble)
                Pepe(PepeMood.BUSCANDO, size = 180.dp)
            },
        ) {
            TopBar(chip, ChipKind.NEUTRAL, Modifier.padding(horizontal = Atv.SafeX).padding(top = Atv.SafeY))
            Column(
                modifier = Modifier.offset(Atv.SafeX, 120.dp).width(470.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = copy,
            )
            SpeechBubble(bubble, Modifier.offset(590.dp, 104.dp))
            FloorShadow(Modifier.offset(600.dp, 420.dp).size(280.dp, 48.dp))
            Pepe(PepeMood.BUSCANDO, Modifier.offset(610.dp, 160.dp), size = 280.dp)
        }
    }
}
