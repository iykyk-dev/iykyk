package app.iykyk.client.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.iykyk.client.core.Profiles
import app.iykyk.client.core.ServerInfo
import app.iykyk.client.core.SubscriptionInfo
import app.iykyk.client.vpn.VpnStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(
    status: VpnStatus,
    profiles: Profiles,
    error: String?,
    refreshing: Boolean,
    onToggle: () -> Unit,
    onPasteLink: () -> Unit,
    onRefresh: () -> Unit,
    onSelectServer: (Int) -> Unit,
) {
    var showServers by remember { mutableStateOf(false) }
    val server = profiles.current

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
            Spacer(Modifier.height(20.dp))
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

            profiles.subscription?.let {
                SubscriptionCard(it, refreshing = refreshing, onRefresh = onRefresh)
                Spacer(Modifier.height(12.dp))
            }
            ServerCard(
                server = server,
                canChange = profiles.servers.size > 1,
                onClick = { if (profiles.servers.size > 1) showServers = true },
            )
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
                border = BorderStroke(1.dp, IykykColors.Accent.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = IykykColors.Accent),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_paste_link))
            }
        }
    }

    if (showServers) {
        ServerPicker(
            servers = profiles.servers,
            selected = profiles.selected,
            onSelect = {
                showServers = false
                onSelectServer(it)
            },
            onDismiss = { showServers = false },
        )
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
        modifier = Modifier.size(180.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_tile),
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(64.dp),
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
private fun SubscriptionCard(sub: SubscriptionInfo, refreshing: Boolean, onRefresh: () -> Unit) {
    Surface(
        color = IykykColors.Surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = sub.title.ifBlank { stringResource(R.string.subscription_label) },
                    color = IykykColors.Text,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRefresh, enabled = !refreshing) {
                    Text(
                        stringResource(if (refreshing) R.string.refreshing else R.string.action_refresh),
                        color = IykykColors.Accent,
                    )
                }
            }
            Column(
                modifier = Modifier.padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val used = formatBytes(sub.upload + sub.download)
                InfoRow(
                    stringResource(R.string.traffic_label),
                    if (sub.total > 0) {
                        stringResource(R.string.traffic_used_of, used, formatBytes(sub.total))
                    } else {
                        stringResource(R.string.traffic_unlimited, used)
                    },
                )
                InfoRow(stringResource(R.string.expire_label), expireText(sub.expire))
                if (sub.announce.isNotBlank()) {
                    Text(
                        text = sub.announce,
                        color = IykykColors.Warning,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun expireText(expire: Long): String {
    if (expire <= 0) return stringResource(R.string.expire_never)
    val date = SimpleDateFormat("d MMMM yyyy", Locale.forLanguageTag("ru")).format(Date(expire * 1000))
    return if (expire * 1000 < System.currentTimeMillis()) {
        stringResource(R.string.expire_expired, date)
    } else {
        date
    }
}

private fun formatBytes(bytes: Long): String {
    val units = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes ${units[0]}" else String.format(Locale.forLanguageTag("ru"), "%.1f %s", value, units[unit])
}

@Composable
private fun ServerCard(server: ServerInfo?, canChange: Boolean, onClick: () -> Unit) {
    Surface(
        color = IykykColors.Surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canChange, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.server_label), color = IykykColors.Muted, style = MaterialTheme.typography.bodySmall)
                Text(
                    text = server?.name ?: stringResource(R.string.server_none),
                    color = IykykColors.Text,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (server != null) {
                    Text(
                        text = protocolTitle(server.protocol),
                        color = IykykColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (canChange) {
                Text(
                    text = stringResource(R.string.action_change_server) + " ›",
                    color = IykykColors.Accent,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerPicker(
    servers: List<ServerInfo>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = IykykColors.Surface,
    ) {
        Text(
            text = stringResource(R.string.servers_title),
            color = IykykColors.Text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(modifier = Modifier.navigationBarsPadding()) {
            itemsIndexed(servers) { index, item ->
                val isSelected = index == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(index) }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            color = if (isSelected) IykykColors.Accent else IykykColors.Text,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = protocolTitle(item.protocol),
                            color = IykykColors.Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (isSelected) {
                        Text("✓", color = IykykColors.Accent, fontSize = 18.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
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
            profiles = Profiles(
                subscription = SubscriptionInfo(
                    url = "https://example.com/sub",
                    title = "Для своих",
                    upload = 1L shl 30,
                    download = 5L shl 30,
                    total = 100L shl 30,
                    expire = 1_893_456_000,
                    supportUrl = "",
                    announce = "",
                    updatedAt = 0,
                ),
                servers = listOf(ServerInfo("🇳🇱 Нидерланды", "vless", "nl1.example.com:443")),
            ),
            error = null,
            refreshing = false,
            onToggle = {},
            onPasteLink = {},
            onRefresh = {},
            onSelectServer = {},
        )
    }
}
