package app.iykyk.client.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iykyk.client.R
import app.iykyk.client.core.ServerInfo
import app.iykyk.client.vpn.VpnStatus

@Composable
fun MainScreen(
    status: VpnStatus,
    server: ServerInfo?,
    error: String?,
    onToggle: () -> Unit,
    onPasteLink: () -> Unit,
) {
    Surface(color = IykykColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Header()
            Spacer(Modifier.weight(1f))
            ConnectButton(status = status, enabled = server != null, onClick = onToggle)
            Spacer(Modifier.height(24.dp))
            StatusLabel(status)
            if (error != null && status == VpnStatus.DISCONNECTED) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.error_prefix, error),
                    color = IykykColors.Error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.weight(1f))
            ServerCard(server)
            Spacer(Modifier.height(12.dp))
            if (server == null) {
                Text(
                    text = stringResource(R.string.paste_hint),
                    color = IykykColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(
                onClick = onPasteLink,
                enabled = !status.isActive,
                border = BorderStroke(1.dp, IykykColors.Accent.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = IykykColors.Accent),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_paste_link))
            }
        }
    }
}

@Composable
private fun Header() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.app_name),
            color = IykykColors.Text,
            fontSize = 32.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 6.sp,
        )
        Text(
            text = stringResource(R.string.tagline),
            color = IykykColors.Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ConnectButton(status: VpnStatus, enabled: Boolean, onClick: () -> Unit) {
    val busy = status == VpnStatus.CONNECTING || status == VpnStatus.DISCONNECTING
    val fill by animateColorAsState(
        targetValue = when (status) {
            VpnStatus.CONNECTED -> IykykColors.Accent
            VpnStatus.CONNECTING, VpnStatus.DISCONNECTING -> IykykColors.SurfaceHigh
            VpnStatus.DISCONNECTED -> IykykColors.Surface
        },
        animationSpec = tween(400),
        label = "buttonFill",
    )
    val iconTint by animateColorAsState(
        targetValue = when {
            status == VpnStatus.CONNECTED -> IykykColors.OnAccent
            enabled -> IykykColors.Accent
            else -> IykykColors.Muted
        },
        animationSpec = tween(400),
        label = "buttonIcon",
    )
    val label = stringResource(
        if (status.isActive) R.string.action_disconnect else R.string.action_connect,
    )

    Surface(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = CircleShape,
        color = fill,
        border = BorderStroke(2.dp, IykykColors.Accent.copy(alpha = if (enabled) 0.5f else 0.15f)),
        modifier = Modifier.size(200.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_tile),
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(72.dp),
            )
        }
    }
}

@Composable
private fun StatusLabel(status: VpnStatus) {
    val text = stringResource(
        when (status) {
            VpnStatus.DISCONNECTED -> R.string.status_disconnected
            VpnStatus.CONNECTING -> R.string.status_connecting
            VpnStatus.CONNECTED -> R.string.status_connected
            VpnStatus.DISCONNECTING -> R.string.status_disconnecting
        },
    )
    Text(
        text = text,
        color = if (status == VpnStatus.CONNECTED) IykykColors.Accent else IykykColors.Text,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ServerCard(server: ServerInfo?) {
    Surface(
        color = IykykColors.Surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoRow(stringResource(R.string.server_label), server?.name ?: stringResource(R.string.server_none))
            if (server != null) {
                InfoRow(stringResource(R.string.protocol_label), protocolTitle(server.protocol))
            }
        }
    }
}

private fun protocolTitle(protocol: String): String = when (protocol) {
    "vless" -> "VLESS"
    "trojan" -> "Trojan"
    "shadowsocks" -> "Shadowsocks"
    "hysteria2" -> "Hysteria2"
    else -> protocol
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = IykykColors.Muted)
        Spacer(Modifier.size(16.dp))
        Text(
            text = value,
            color = IykykColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

@Preview
@Composable
private fun MainScreenPreview() {
    IykykTheme {
        MainScreen(
            status = VpnStatus.CONNECTED,
            server = ServerInfo("🇳🇱 Нидерланды", "vless", "nl1.example.com:443"),
            error = null,
            onToggle = {},
            onPasteLink = {},
        )
    }
}
