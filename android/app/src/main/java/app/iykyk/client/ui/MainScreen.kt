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
import androidx.compose.foundation.lazy.items
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
import app.iykyk.client.core.ProfileStore
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
    pings: Map<String, Int>,
    pinging: Boolean,
    sortByPing: Boolean,
    onToggle: () -> Unit,
    onPasteLink: () -> Unit,
    onRefresh: () -> Unit,
    onSelectServer: (Int) -> Unit,
    onPing: () -> Unit,
    onSortChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
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
            Box(modifier = Modifier.fillMaxWidth()) {
                Header(modifier = Modifier.align(Alignment.Center))
                TextButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.TopEnd)) {
                    Text("⚙", color = IykykColors.Muted, fontSize = 22.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            ConnectButton(status = status, enabled = profiles.hasServers, onClick = onToggle)
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
                profiles = profiles,
                canChange = profiles.servers.size > 1,
                onClick = { if (profiles.servers.size > 1) showServers = true },
            )
            Spacer(Modifier.height(12.dp))
            if (!profiles.hasServers) {
                Text(
                    text = stringResource(if (ProfileStore.locked) R.string.paste_hint_locked else R.string.paste_hint),
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
                Text(
                    stringResource(
                        if (ProfileStore.locked) R.string.action_paste_subscription else R.string.action_paste_link,
                    ),
                )
            }
        }
    }

    if (showServers) {
        ServerPicker(
            profiles = profiles,
            pings = pings,
            pinging = pinging,
            sortByPing = sortByPing,
            onPing = onPing,
            onSortChange = onSortChange,
            onSelect = {
                showServers = false
                onSelectServer(it)
            },
            onDismiss = { showServers = false },
        )
    }
}

@Composable
private fun Header(modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
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
private fun ServerCard(
    profiles: Profiles,
    canChange: Boolean,
    onClick: () -> Unit,
) {
    val server = profiles.current
    val title = server?.name ?: stringResource(R.string.server_none)
    val subtitle = server?.let { protocolTitle(it.protocol) }
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
                    text = title,
                    color = IykykColors.Text,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = IykykColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
    profiles: Profiles,
    pings: Map<String, Int>,
    pinging: Boolean,
    sortByPing: Boolean,
    onPing: () -> Unit,
    onSortChange: (Boolean) -> Unit,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = IykykColors.Surface,
    ) {
        Row(
            modifier = Modifier.padding(start = 24.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.servers_title),
                color = IykykColors.Text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onSortChange(!sortByPing) }) {
                Text(
                    stringResource(if (sortByPing) R.string.sort_by_ping else R.string.sort_by_order),
                    color = IykykColors.Muted,
                )
            }
            TextButton(onClick = onPing, enabled = !pinging) {
                Text(
                    stringResource(if (pinging) R.string.pinging else R.string.action_ping),
                    color = IykykColors.Accent,
                )
            }
        }
        LazyColumn(modifier = Modifier.navigationBarsPadding()) {
            // Working servers by delay first, then untested, then unreachable.
            val order = profiles.servers.indices.let { indices ->
                if (!sortByPing) {
                    indices.toList()
                } else {
                    indices.sortedBy { i ->
                        val p = pings[profiles.servers[i].link]
                        when {
                            p == null || p == -2 -> Int.MAX_VALUE - 1
                            p < 0 -> Int.MAX_VALUE
                            else -> p
                        }
                    }
                }
            }
            items(order, key = { it }) { index ->
                val item = profiles.servers[index]
                val delay = pings[item.link]
                val (text, color) = when {
                    delay == null -> null to IykykColors.Muted
                    delay == -2 -> stringResource(R.string.ping_udp) to IykykColors.Muted
                    delay < 0 -> stringResource(R.string.ping_failed) to IykykColors.Error
                    delay < 150 -> stringResource(R.string.ping_ms, delay) to IykykColors.Accent
                    delay < 400 -> stringResource(R.string.ping_ms, delay) to IykykColors.Warning
                    else -> stringResource(R.string.ping_ms, delay) to IykykColors.Error
                }
                PickerRow(
                    title = item.name,
                    subtitle = protocolTitle(item.protocol),
                    trailing = text,
                    trailingColor = color,
                    selected = index == profiles.selected,
                    onClick = { onSelect(index) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    trailing: String?,
    trailingColor: androidx.compose.ui.graphics.Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (selected) IykykColors.Accent else IykykColors.Text,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = IykykColors.Muted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.size(12.dp))
            Text(trailing, color = trailingColor, style = MaterialTheme.typography.bodySmall)
        }
        if (selected) {
            Spacer(Modifier.size(12.dp))
            Text("✓", color = IykykColors.Accent, fontSize = 18.sp)
        }
    }
}

private fun protocolTitle(protocol: String): String = when (protocol) {
    "vless" -> "VLESS"
    "vmess" -> "VMess"
    "trojan" -> "Trojan"
    "shadowsocks" -> "Shadowsocks"
    "hysteria2" -> "Hysteria2"
    "tuic" -> "TUIC"
    "anytls" -> "AnyTLS"
    "naive" -> "NaiveProxy"
    "wireguard" -> "WireGuard"
    "amneziawg" -> "AmneziaWG"
    "mieru" -> "Mieru"
    "socks" -> "SOCKS5"
    "http" -> "HTTP"
    "xray-json" -> "Xray"
    "xray-balancer" -> "Xray · автовыбор"
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
            pings = emptyMap(),
            pinging = false,
            sortByPing = false,
            onToggle = {},
            onPasteLink = {},
            onRefresh = {},
            onSelectServer = {},
            onPing = {},
            onSortChange = {},
            onOpenSettings = {},
        )
    }
}
