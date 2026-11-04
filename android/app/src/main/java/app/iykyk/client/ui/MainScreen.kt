package app.iykyk.client.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iykyk.client.R
import app.iykyk.client.core.DeviceInfo
import app.iykyk.client.core.DevicesInfo
import app.iykyk.client.core.ExpiryReminder
import app.iykyk.client.core.ProfileStore
import app.iykyk.client.core.Profiles
import app.iykyk.client.core.ServerInfo
import app.iykyk.client.core.SubscriptionInfo
import app.iykyk.client.core.daysLeft
import app.iykyk.client.tv.TvShare
import app.iykyk.client.vpn.TunnelState
import app.iykyk.client.vpn.VpnStatus
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
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
    canBuy: Boolean,
    clipboardLink: String?,
    tunnel: TunnelState?,
    shareLink: String?,
    devices: DevicesInfo?,
    onToggle: () -> Unit,
    onPasteLink: () -> Unit,
    onRefresh: () -> Unit,
    onSelectServer: (Int) -> Unit,
    onPing: () -> Unit,
    onSortChange: (Boolean) -> Unit,
    onScanQr: () -> Unit,
    onSendToTv: () -> Unit,
    onOpenBot: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onImportText: (String) -> Unit,
    onClipboardDismiss: () -> Unit,
    onCheck: () -> Unit,
    onSendReport: () -> Unit,
    onCopyLink: () -> Unit,
    onDelete: () -> Unit,
    onLoadDevices: () -> Unit,
    onDeleteDevice: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var showLinkInput by remember { mutableStateOf(false) }
    var showShareQr by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showTvSend by remember { mutableStateOf(false) }
    val subscriptionActions = SubscriptionActions(
        canShare = shareLink != null,
        // Three short steps first, then the camera: the TV must already show its code.
        onSendToTv = { showTvSend = true },
        onShowQr = { showShareQr = true },
        onCopyLink = onCopyLink,
        onDelete = { confirmDelete = true },
    )
    var showReceive by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(true) }
    var tab by rememberSaveable { mutableStateOf(0) }

    Surface(color = IykykColors.Background, modifier = Modifier.fillMaxSize()) {
        if (!profiles.hasServers) {
            WelcomeScreen(
                canBuy = canBuy,
                onOpenBot = onOpenBot,
                onPaste = onPasteLink,
                onScanQr = onScanQr,
                onManual = { showLinkInput = true },
                onReceive = { showReceive = true },
                onOpenSettings = onOpenSettings,
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    if (tab == 0) {
                        ConnectionTab(
                            status = status,
                            profiles = profiles,
                            error = error,
                            tunnel = tunnel,
                            onCheck = onCheck,
                            onSendReport = onSendReport,
                            expanded = expanded,
                            onExpandChange = { expanded = it },
                            refreshing = refreshing,
                            pings = pings,
                            pinging = pinging,
                            sortByPing = sortByPing,
                            onToggle = onToggle,
                            onRefresh = onRefresh,
                            onPing = onPing,
                            onSortChange = onSortChange,
                            actions = subscriptionActions,
                            onSelectServer = onSelectServer,
                            onOpenSettings = onOpenSettings,
                            onPaste = onPasteLink,
                            onScanQr = onScanQr,
                            onManual = { showLinkInput = true },
                            onReceive = { showReceive = true },
                        )
                    } else {
                        SubscriptionTab(
                            profiles = profiles,
                            canBuy = canBuy,
                            refreshing = refreshing,
                            onRefresh = onRefresh,
                            onOpenBot = onOpenBot,
                            onOpenUrl = onOpenUrl,
                            actions = subscriptionActions,
                            devices = devices,
                            onLoadDevices = onLoadDevices,
                            onDeleteDevice = onDeleteDevice,
                            onSendReport = onSendReport,
                            onOpenSettings = onOpenSettings,
                        )
                    }
                }
                NavigationBar(containerColor = IykykColors.Surface) {
                    TabItem(tab == 0, R.drawable.ic_tile, R.string.tab_connection) { tab = 0 }
                    TabItem(tab == 1, R.drawable.ic_card, R.string.tab_subscription) { tab = 1 }
                }
            }
        }
    }

    if (showLinkInput) {
        LinkInputDialog(
            onConfirm = {
                showLinkInput = false
                onImportText(it)
            },
            onDismiss = { showLinkInput = false },
        )
    }

    if (showReceive) {
        TvReceiveDialog(
            onReceived = {
                showReceive = false
                onImportText(it)
            },
            onDismiss = { showReceive = false },
        )
    }

    if (showTvSend) {
        TvSendDialog(
            onScan = {
                showTvSend = false
                onSendToTv()
            },
            onDismiss = { showTvSend = false },
        )
    }

    if (showShareQr && shareLink != null) {
        ShareQrDialog(shareLink, onCopyLink = onCopyLink, onDismiss = { showShareQr = false })
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = IykykColors.Surface,
            title = { Text(stringResource(R.string.delete_title), color = IykykColors.Text) },
            text = { Text(stringResource(R.string.delete_text), color = IykykColors.Muted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.action_delete), color = IykykColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel), color = IykykColors.Muted)
                }
            },
        )
    }

    if (clipboardLink != null) {
        // Back from the bot with a subscription link copied: offer to add it.
        AlertDialog(
            onDismissRequest = onClipboardDismiss,
            containerColor = IykykColors.Surface,
            title = { Text(stringResource(R.string.clipboard_found_title), color = IykykColors.Text) },
            text = {
                Text(
                    stringResource(R.string.clipboard_found_text, clipboardLink.take(60) + if (clipboardLink.length > 60) "…" else ""),
                    color = IykykColors.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onClipboardDismiss()
                    onImportText(clipboardLink)
                }) { Text(stringResource(R.string.action_add), color = IykykColors.Accent) }
            },
            dismissButton = {
                TextButton(onClick = onClipboardDismiss) {
                    Text(stringResource(R.string.action_cancel), color = IykykColors.Muted)
                }
            },
        )
    }
}

/** What the ⋯ menu and the Подписка tab can do with the subscription. */
private class SubscriptionActions(
    val canShare: Boolean,
    val onSendToTv: () -> Unit,
    val onShowQr: () -> Unit,
    val onCopyLink: () -> Unit,
    val onDelete: () -> Unit,
)

/** A QR code with the subscription link, to add it on another device. */
@Composable
private fun ShareQrDialog(link: String, onCopyLink: () -> Unit, onDismiss: () -> Unit) {
    val qr = remember(link) {
        runCatching { BarcodeEncoder().encodeBitmap(link, BarcodeFormat.QR_CODE, 640, 640).asImageBitmap() }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = IykykColors.Surface,
        title = { Text(stringResource(R.string.menu_other_device), color = IykykColors.Text) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (qr != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(8.dp),
                    ) {
                        Image(bitmap = qr, contentDescription = null, modifier = Modifier.size(220.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.other_device_text),
                    color = IykykColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCopyLink) {
                Text(stringResource(R.string.menu_copy_link), color = IykykColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close), color = IykykColors.Muted)
            }
        },
    )
}

/** Status bar and side insets; the bottom is taken by the tab bar. */
private val topInsets: Modifier
    @Composable get() = Modifier.windowInsetsPadding(
        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    )

@Composable
private fun RowScope.TabItem(selected: Boolean, icon: Int, label: Int, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(painterResource(icon), contentDescription = null) },
        label = { Text(stringResource(label)) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = IykykColors.Accent,
            selectedTextColor = IykykColors.Accent,
            indicatorColor = IykykColors.Accent.copy(alpha = 0.14f),
            unselectedIconColor = IykykColors.Muted,
            unselectedTextColor = IykykColors.Muted,
        ),
    )
}

@Composable
private fun ConnectionTab(
    status: VpnStatus,
    profiles: Profiles,
    error: String?,
    tunnel: TunnelState?,
    onCheck: () -> Unit,
    onSendReport: () -> Unit,
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    refreshing: Boolean,
    pings: Map<String, Int>,
    pinging: Boolean,
    sortByPing: Boolean,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
    onPing: () -> Unit,
    onSortChange: (Boolean) -> Unit,
    actions: SubscriptionActions,
    onSelectServer: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onPaste: () -> Unit,
    onScanQr: () -> Unit,
    onManual: () -> Unit,
    onReceive: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(topInsets)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TopRow(
            onOpenSettings = onOpenSettings,
            onPaste = onPaste,
            onScanQr = onScanQr,
            onManual = onManual,
            onReceive = onReceive,
        )
        Spacer(Modifier.height(28.dp))
        ConnectButton(status = status, enabled = profiles.hasServers, onClick = onToggle)
        Spacer(Modifier.height(16.dp))
        StatusLabel(status)
        if (status == VpnStatus.CONNECTED && tunnel != null) {
            Spacer(Modifier.height(6.dp))
            TunnelLine(tunnel, onCheck)
        }
        val failed = error != null && status == VpnStatus.DISCONNECTED
        if (failed) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.error_prefix, error ?: ""),
                color = IykykColors.Error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        if (failed || (status == VpnStatus.CONNECTED && tunnel == TunnelState.Failed)) {
            TextButton(onClick = onSendReport) {
                Text(stringResource(R.string.action_send_report), color = IykykColors.Accent)
            }
        }
        Spacer(Modifier.height(28.dp))
        SubscriptionCard(
            profiles = profiles,
            expanded = expanded,
            onExpandChange = onExpandChange,
            refreshing = refreshing,
            pings = pings,
            pinging = pinging,
            sortByPing = sortByPing,
            onRefresh = onRefresh,
            onPing = onPing,
            onSortChange = onSortChange,
            actions = actions,
            onSelectServer = onSelectServer,
        )
        Spacer(Modifier.height(16.dp))
    }
}

/** ⚙ on the left, the name in the middle, + (add a subscription) on the right. */
@Composable
private fun TopRow(
    onOpenSettings: () -> Unit,
    onPaste: () -> Unit,
    onScanQr: () -> Unit,
    onManual: () -> Unit,
    onReceive: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(
                painterResource(R.drawable.ic_settings),
                contentDescription = stringResource(R.string.cd_settings),
                tint = IykykColors.Muted,
            )
        }
        Header(modifier = Modifier.align(Alignment.Center))
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            IconButton(onClick = { menu = true }) {
                Icon(
                    painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.cd_add),
                    tint = IykykColors.Accent,
                )
            }
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                containerColor = IykykColors.Surface,
            ) {
                MenuItem(stringResource(R.string.menu_paste)) { menu = false; onPaste() }
                MenuItem(stringResource(R.string.menu_scan_qr)) { menu = false; onScanQr() }
                MenuItem(stringResource(R.string.menu_manual)) { menu = false; onManual() }
                MenuItem(stringResource(R.string.menu_receive_tv)) { menu = false; onReceive() }
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, trailing: String? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = IykykColors.Text) },
        trailingIcon = if (trailing == null) null else {
            { Text(trailing, color = IykykColors.Accent) }
        },
        onClick = onClick,
    )
}

/**
 * The subscription and its servers in one card, as in Happ/INCY: a header with
 * collapse, refresh, ping and ⋯, the traffic bar, links, and the server list.
 */
@Composable
private fun SubscriptionCard(
    profiles: Profiles,
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    refreshing: Boolean,
    pings: Map<String, Int>,
    pinging: Boolean,
    sortByPing: Boolean,
    onRefresh: () -> Unit,
    onPing: () -> Unit,
    onSortChange: (Boolean) -> Unit,
    actions: SubscriptionActions,
    onSelectServer: (Int) -> Unit,
) {
    val sub = profiles.subscription
    var menu by remember { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    Surface(
        color = IykykColors.Surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandChange(!expanded) }
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_expand),
                    contentDescription = stringResource(R.string.cd_expand),
                    tint = IykykColors.Muted,
                    modifier = Modifier.rotate(chevron),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = sub?.title?.ifBlank { null } ?: stringResource(
                        if (sub != null) R.string.subscription_label else R.string.servers_title,
                    ),
                    color = IykykColors.Text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (sub != null) {
                    HeaderAction(R.drawable.ic_refresh, R.string.cd_refresh, busy = refreshing, onClick = onRefresh)
                }
                HeaderAction(R.drawable.ic_speed, R.string.cd_ping, busy = pinging, onClick = onPing)
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            painterResource(R.drawable.ic_more),
                            contentDescription = stringResource(R.string.cd_more),
                            tint = IykykColors.Muted,
                        )
                    }
                    DropdownMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false },
                        containerColor = IykykColors.Surface,
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_send_tv), color = IykykColors.Text) },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.ic_tv), contentDescription = null, tint = IykykColors.Accent)
                            },
                            onClick = { menu = false; actions.onSendToTv() },
                        )
                        if (actions.canShare) {
                            MenuItem(stringResource(R.string.menu_other_device)) { menu = false; actions.onShowQr() }
                            MenuItem(stringResource(R.string.menu_copy_link)) { menu = false; actions.onCopyLink() }
                        }
                        MenuItem(
                            stringResource(R.string.menu_sort_ping),
                            trailing = if (sortByPing) "✓" else null,
                        ) { menu = false; onSortChange(!sortByPing) }
                        HorizontalDivider(color = IykykColors.SurfaceHigh)
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_delete), color = IykykColors.Error) },
                            onClick = { menu = false; actions.onDelete() },
                        )
                    }
                }
            }

            if (sub != null) TrafficSummary(sub)

            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(color = IykykColors.SurfaceHigh)
                    ServerList(profiles, pings, sortByPing, onSelectServer)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun HeaderAction(icon: Int, description: Int, busy: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = !busy) {
        if (busy) {
            CircularProgressIndicator(color = IykykColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            Icon(painterResource(icon), contentDescription = stringResource(description), tint = IykykColors.Accent)
        }
    }
}

/** Under the status: exit country, IP and delay, or what is wrong. Tap to re-check. */
@Composable
private fun TunnelLine(tunnel: TunnelState, onCheck: () -> Unit) {
    val (text, color) = when (tunnel) {
        TunnelState.Checking -> stringResource(R.string.tunnel_checking) to IykykColors.Muted
        is TunnelState.Ok -> stringResource(
            R.string.tunnel_ok,
            countryLabel(tunnel.country),
            tunnel.ip,
            tunnel.latency,
        ) to IykykColors.Muted
        TunnelState.NoInternet -> stringResource(R.string.tunnel_no_internet) to IykykColors.Warning
        TunnelState.Reconnecting -> stringResource(R.string.tunnel_reconnecting) to IykykColors.Warning
        TunnelState.Failed -> stringResource(R.string.tunnel_failed) to IykykColors.Error
    }
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onCheck)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** "NL" → "🇳🇱 Нидерланды". */
private fun countryLabel(code: String): String {
    if (code.length != 2 || !code.all { it.isLetter() }) return code
    val upper = code.uppercase(Locale.ROOT)
    val flag = String(Character.toChars(0x1F1E6 + (upper[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (upper[1] - 'A')))
    val name = Locale("", upper).getDisplayCountry(Locale.forLanguageTag("ru")).ifBlank { upper }
    return "$flag $name"
}

/** The traffic bar and expiry date under the card header. */
@Composable
private fun TrafficSummary(sub: SubscriptionInfo) {
    val daysLeft = sub.daysLeft()
    Column(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TrafficBar(sub)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = trafficText(sub),
                color = IykykColors.Text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (sub.expire > 0) stringResource(R.string.expire_until, shortDate(sub.expire)) else stringResource(R.string.expire_never),
                color = if (daysLeft != null && daysLeft < 0) IykykColors.Error else IykykColors.Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        ExpiryWarning(daysLeft)
    }
}

@Composable
private fun TrafficBar(sub: SubscriptionInfo) {
    val used = sub.upload + sub.download
    val fraction = if (sub.total > 0) (used.toFloat() / sub.total).coerceIn(0f, 1f) else 0f
    LinearProgressIndicator(
        progress = { fraction },
        color = if (fraction > 0.9f) IykykColors.Warning else IykykColors.Accent,
        trackColor = IykykColors.SurfaceHigh,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape),
    )
}

private fun trafficText(sub: SubscriptionInfo): String =
    formatBytes(sub.upload + sub.download) + " / " + (if (sub.total > 0) formatBytes(sub.total) else "∞")

@Composable
private fun ExpiryWarning(daysLeft: Int?) {
    if (daysLeft == null || daysLeft > ExpiryReminder.REMIND_DAYS) return
    Text(
        text = when {
            daysLeft < 0 -> stringResource(R.string.expiry_ended)
            daysLeft == 0 -> stringResource(R.string.expiry_today)
            else -> pluralStringResource(R.plurals.expiry_in_days, daysLeft, daysLeft)
        },
        color = if (daysLeft < 0) IykykColors.Error else IykykColors.Warning,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
    )
}

/** The "Подписка" tab: status, dates, traffic, renew (in the bot), support, TV. */
@Composable
private fun SubscriptionTab(
    profiles: Profiles,
    canBuy: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenBot: () -> Unit,
    onOpenUrl: (String) -> Unit,
    actions: SubscriptionActions,
    devices: DevicesInfo?,
    onLoadDevices: () -> Unit,
    onDeleteDevice: (String) -> Unit,
    onSendReport: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val sub = profiles.subscription
    var showDevices by remember { mutableStateOf(false) }
    LaunchedEffect(sub?.url, sub?.updatedAt) { onLoadDevices() }
    if (showDevices && devices != null) {
        DevicesDialog(devices, onDelete = onDeleteDevice, onDismiss = { showDevices = false })
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(topInsets)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.cd_settings), tint = IykykColors.Muted)
            }
            Text(
                stringResource(R.string.tab_subscription),
                color = IykykColors.Text,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Spacer(Modifier.height(16.dp))

        if (sub != null) {
            val daysLeft = sub.daysLeft()
            Surface(color = IykykColors.Surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            sub.title.ifBlank { stringResource(R.string.subscription_label) },
                            color = IykykColors.Text,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        StatusChip(daysLeft)
                    }
                    Text(
                        if (sub.expire > 0) stringResource(R.string.sub_valid_until, longDate(sub.expire)) else stringResource(R.string.sub_status_unlimited),
                        color = IykykColors.Text,
                    )
                    if (daysLeft != null && daysLeft >= 0) {
                        Text(
                            pluralStringResource(R.plurals.sub_days_left, daysLeft, daysLeft),
                            color = if (daysLeft <= ExpiryReminder.REMIND_DAYS) IykykColors.Warning else IykykColors.Muted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.sub_traffic), color = IykykColors.Muted, style = MaterialTheme.typography.bodySmall)
                    TrafficBar(sub)
                    Text(trafficText(sub), color = IykykColors.Text, style = MaterialTheme.typography.bodyMedium)
                    if (devices != null) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showDevices = true }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.devices_label), color = IykykColors.Muted, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    if (devices.limit > 0) {
                                        stringResource(R.string.devices_of, devices.used, devices.limit)
                                    } else {
                                        stringResource(R.string.devices_unlimited, devices.used)
                                    },
                                    color = if (devices.limit > 0 && devices.used >= devices.limit) IykykColors.Warning else IykykColors.Text,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Text("›", color = IykykColors.Muted, fontSize = 22.sp)
                        }
                    }
                    if (sub.hwidLimitReached) {
                        Text(stringResource(R.string.hwid_limit_reached), color = IykykColors.Warning, style = MaterialTheme.typography.bodySmall)
                    }
                    if (sub.announce.isNotBlank()) {
                        Text(sub.announce, color = IykykColors.Warning, style = MaterialTheme.typography.bodySmall)
                    }
                    if (canBuy) {
                        Spacer(Modifier.height(4.dp))
                        LinkButton(stringResource(R.string.action_renew_bot), primary = true, modifier = Modifier.fillMaxWidth(), onClick = onOpenBot)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        Surface(color = IykykColors.Surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column {
                if (sub != null) {
                    ActionRow(R.drawable.ic_refresh, stringResource(R.string.sub_refresh), busy = refreshing, onClick = onRefresh)
                }
                if (sub?.supportUrl?.isNotBlank() == true) {
                    ActionRow(null, stringResource(R.string.action_support)) { onOpenUrl(sub.supportUrl) }
                }
                if (sub?.webPageUrl?.isNotBlank() == true) {
                    ActionRow(null, stringResource(R.string.action_website)) { onOpenUrl(sub.webPageUrl) }
                }
                ActionRow(R.drawable.ic_tv, stringResource(R.string.menu_send_tv), onClick = actions.onSendToTv)
                if (actions.canShare) {
                    ActionRow(null, stringResource(R.string.menu_other_device), onClick = actions.onShowQr)
                    ActionRow(null, stringResource(R.string.menu_copy_link), onClick = actions.onCopyLink)
                }
                ActionRow(null, stringResource(R.string.action_send_report), onClick = onSendReport)
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(color = IykykColors.Surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = actions.onDelete)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(stringResource(R.string.menu_delete), color = IykykColors.Error)
            }
        }

        if (sub != null) {
            Spacer(Modifier.height(12.dp))
            val parts = buildList {
                if (sub.updatedAt > 0) add(stringResource(R.string.sub_updated, dateTime(sub.updatedAt)))
                add(stringResource(R.string.sub_auto_update, sub.updateHours.takeIf { it > 0 } ?: 12))
                add(pluralStringResource(R.plurals.servers_count, profiles.servers.size, profiles.servers.size))
            }
            Text(
                parts.joinToString(" · "),
                color = IykykColors.Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** The subscription's devices; any but this one can be removed to free a slot. */
@Composable
private fun DevicesDialog(devices: DevicesInfo, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    var confirm by remember { mutableStateOf<DeviceInfo?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = IykykColors.Surface,
        title = {
            Text(
                stringResource(R.string.devices_title) + "  " + if (devices.limit > 0) {
                    stringResource(R.string.devices_of, devices.used, devices.limit)
                } else {
                    devices.used.toString()
                },
                color = IykykColors.Text,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                for (device in devices.devices) {
                    Row(modifier = Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                device.model.ifBlank { device.platform.ifBlank { "?" } } +
                                    if (device.current) " · " + stringResource(R.string.device_current) else "",
                                color = if (device.current) IykykColors.Accent else IykykColors.Text,
                                fontWeight = FontWeight.Medium,
                            )
                            val details = listOfNotNull(
                                listOf(device.platform, device.os).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null },
                                device.app.ifBlank { null },
                                isoDate(device.added)?.let { stringResource(R.string.device_added, it) },
                            ).joinToString(" · ")
                            Text(details, color = IykykColors.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        if (!device.current) {
                            TextButton(onClick = { confirm = device }) {
                                Text(stringResource(R.string.action_delete), color = IykykColors.Error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close), color = IykykColors.Muted)
            }
        },
    )
    confirm?.let { device ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = IykykColors.Surface,
            title = { Text(stringResource(R.string.device_remove_title), color = IykykColors.Text) },
            text = {
                Text(
                    stringResource(R.string.device_remove_text, device.model.ifBlank { device.platform }),
                    color = IykykColors.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    onDelete(device.id)
                }) { Text(stringResource(R.string.action_delete), color = IykykColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) {
                    Text(stringResource(R.string.action_cancel), color = IykykColors.Muted)
                }
            },
        )
    }
}

/** "2026-10-01T10:00:00Z" → "01.10.2026". */
private fun isoDate(text: String): String? {
    val match = Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(text) ?: return null
    val (y, m, d) = match.destructured
    return "$d.$m.$y"
}

@Composable
private fun StatusChip(daysLeft: Int?) {
    val (text, color) = when {
        daysLeft == null -> stringResource(R.string.sub_status_unlimited) to Color(0xFF3FA66B)
        daysLeft < 0 -> stringResource(R.string.sub_status_expired) to IykykColors.Error
        daysLeft <= ExpiryReminder.REMIND_DAYS -> stringResource(R.string.sub_status_ending) to IykykColors.Warning
        else -> stringResource(R.string.sub_status_active) to Color(0xFF3FA66B)
    }
    Surface(color = color.copy(alpha = 0.15f), shape = CircleShape) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ActionRow(icon: Int?, text: String, busy: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                busy -> CircularProgressIndicator(color = IykykColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                icon != null -> Icon(painterResource(icon), contentDescription = null, tint = IykykColors.Accent)
            }
        }
        Spacer(Modifier.size(12.dp))
        Text(text, color = IykykColors.Text, modifier = Modifier.weight(1f))
        Text("›", color = IykykColors.Muted, fontSize = 22.sp)
    }
}

@Composable
private fun ServerList(
    profiles: Profiles,
    pings: Map<String, Int>,
    sortByPing: Boolean,
    onSelect: (Int) -> Unit,
) {
    // Working servers by delay first, then untested, then unreachable.
    val order = if (!sortByPing) {
        profiles.servers.indices.toList()
    } else {
        profiles.servers.indices.sortedBy { i ->
            val p = pings[profiles.servers[i].link]
            when {
                p == null || p == -2 -> Int.MAX_VALUE - 1
                p < 0 -> Int.MAX_VALUE
                else -> p
            }
        }
    }
    val selected = if (profiles.selected in profiles.servers.indices) profiles.selected else 0
    for (index in order) {
        ServerRow(
            server = profiles.servers[index],
            delay = pings[profiles.servers[index].link],
            selected = index == selected,
            onClick = { onSelect(index) },
        )
    }
}

@Composable
private fun ServerRow(server: ServerInfo, delay: Int?, selected: Boolean, onClick: () -> Unit) {
    val (flag, name) = splitFlag(server.name)
    val (pingText, pingColor) = when {
        delay == null -> null to IykykColors.Muted
        delay == -2 -> stringResource(R.string.ping_udp) to IykykColors.Muted
        delay < 0 -> stringResource(R.string.ping_failed) to IykykColors.Error
        delay < 150 -> stringResource(R.string.ping_ms, delay) to Color(0xFF3FA66B)
        delay < 400 -> stringResource(R.string.ping_ms, delay) to IykykColors.Warning
        else -> stringResource(R.string.ping_ms, delay) to IykykColors.Error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) IykykColors.Accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(IykykColors.SurfaceHigh),
        ) {
            Text(flag ?: "🌐", fontSize = 20.sp)
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = if (selected) IykykColors.Accent else IykykColors.Text,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = server.details.ifBlank { protocolTitle(server.protocol) },
                color = IykykColors.Muted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (pingText != null) {
            Spacer(Modifier.size(8.dp))
            Text(pingText, color = pingColor, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
    }
}

/** "🇳🇱 Нидерланды" → ("🇳🇱", "Нидерланды"); the flag goes into the round badge. */
internal fun splitFlag(name: String): Pair<String?, String> {
    val text = name.trimStart()
    if (text.length >= 4) {
        val first = text.codePointAt(0)
        val second = text.codePointAt(2)
        if (first in 0x1F1E6..0x1F1FF && second in 0x1F1E6..0x1F1FF) {
            val rest = text.substring(4).trimStart(' ', '|', '-', '·')
            return text.substring(0, 4) to rest.ifBlank { text }
        }
    }
    return null to text
}

/** First launch, no subscription yet: buy one in the bot or add an existing one. */
@Composable
private fun WelcomeScreen(
    canBuy: Boolean,
    onOpenBot: () -> Unit,
    onPaste: () -> Unit,
    onScanQr: () -> Unit,
    onManual: () -> Unit,
    onReceive: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.cd_settings), tint = IykykColors.Muted)
            }
        }
        Spacer(Modifier.height(24.dp))
        Header()
        Spacer(Modifier.height(40.dp))
        Text(stringResource(R.string.welcome_title), color = IykykColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.welcome_text),
            color = IykykColors.Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        if (canBuy) {
            LinkButton(stringResource(R.string.action_buy), primary = true, modifier = Modifier.fillMaxWidth(), onClick = onOpenBot)
            Spacer(Modifier.height(32.dp))
        }
        Text(stringResource(R.string.welcome_have), color = IykykColors.Text, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(if (ProfileStore.locked) R.string.paste_hint_locked else R.string.paste_hint),
            color = IykykColors.Muted,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (canBuy) {
                LinkButton(stringResource(R.string.action_get_in_bot), modifier = Modifier.fillMaxWidth(), onClick = onOpenBot)
            }
            LinkButton(
                stringResource(if (ProfileStore.locked) R.string.action_paste_subscription else R.string.action_paste_link),
                modifier = Modifier.fillMaxWidth(),
                onClick = onPaste,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LinkButton(stringResource(R.string.action_scan_qr), modifier = Modifier.weight(1f), onClick = onScanQr)
                LinkButton(stringResource(R.string.menu_manual), modifier = Modifier.weight(1f), onClick = onManual)
            }
        }
        // On a TV: no camera, no clipboard, typing with a remote is painful.
        TextButton(onClick = onReceive) {
            Text(stringResource(R.string.menu_receive_tv), color = IykykColors.Accent)
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** The phone side of "Send to TV": what to do on the TV, then the camera. */
@Composable
private fun TvSendDialog(onScan: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = IykykColors.Surface,
        icon = { Icon(painterResource(R.drawable.ic_tv), contentDescription = null, tint = IykykColors.Accent) },
        title = { Text(stringResource(R.string.menu_send_tv), color = IykykColors.Text) },
        text = {
            Column {
                Steps(
                    stringResource(R.string.tv_send_step1),
                    stringResource(R.string.tv_send_step2),
                    stringResource(R.string.tv_send_step3),
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.tv_same_wifi), color = IykykColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(
                onClick = onScan,
                colors = ButtonDefaults.buttonColors(containerColor = IykykColors.Accent, contentColor = IykykColors.OnAccent),
            ) { Text(stringResource(R.string.tv_scan_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = IykykColors.Muted)
            }
        },
    )
}

/** Short numbered steps: a small orange number and one line of text each. */
@Composable
private fun Steps(vararg steps: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        steps.forEachIndexed { index, step ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(IykykColors.Accent.copy(alpha = 0.15f)),
                ) {
                    Text("${index + 1}", color = IykykColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.size(10.dp))
                Text(step, color = IykykColors.Text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** The TV side of "Send to TV": shows a QR code and waits for the phone. */
@Composable
private fun TvReceiveDialog(onReceived: (String) -> Unit, onDismiss: () -> Unit) {
    val receiver = remember { runCatching { TvShare.Receiver(onReceived) }.getOrNull() }
    DisposableEffect(receiver) { onDispose { receiver?.close() } }
    val code = receiver?.code
    val qr = remember(code) {
        code?.let { runCatching { BarcodeEncoder().encodeBitmap(it, BarcodeFormat.QR_CODE, 640, 640).asImageBitmap() }.getOrNull() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = IykykColors.Surface,
        title = { Text(stringResource(R.string.tv_receive_title), color = IykykColors.Text) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (qr != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(8.dp),
                    ) {
                        Image(bitmap = qr, contentDescription = null, modifier = Modifier.size(220.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Steps(
                        stringResource(R.string.tv_receive_step1),
                        stringResource(R.string.tv_receive_step2),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = IykykColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.tv_waiting), color = IykykColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(stringResource(R.string.tv_receive_no_network), color = IykykColors.Error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = IykykColors.Muted)
            }
        },
    )
}

private fun shortDate(unixSeconds: Long): String =
    SimpleDateFormat("dd.MM.yyyy", Locale.forLanguageTag("ru")).format(Date(unixSeconds * 1000))

private fun longDate(unixSeconds: Long): String =
    SimpleDateFormat("d MMMM yyyy", Locale.forLanguageTag("ru")).format(Date(unixSeconds * 1000))

private fun dateTime(millis: Long): String =
    SimpleDateFormat("dd.MM.yy HH:mm", Locale.forLanguageTag("ru")).format(Date(millis))

@Composable
internal fun LinkInputDialog(
    title: String = stringResource(R.string.enter_link_title),
    hint: String = stringResource(R.string.enter_link_hint),
    initial: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = IykykColors.Surface,
        title = { Text(title, color = IykykColors.Text) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(hint) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = IykykColors.Accent,
                    focusedTextColor = IykykColors.Text,
                    unfocusedTextColor = IykykColors.Text,
                    cursorColor = IykykColors.Accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.action_add), color = IykykColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = IykykColors.Muted)
            }
        },
    )
}

@Composable
private fun Header(modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.app_name),
            color = IykykColors.Text,
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 5.sp,
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
        modifier = Modifier.size(160.dp),
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
private fun LinkButton(text: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = IykykColors.Accent,
                contentColor = IykykColors.OnAccent,
            ),
        ) { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            border = BorderStroke(1.dp, IykykColors.Muted.copy(alpha = 0.4f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = IykykColors.Text),
        ) { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
                    supportUrl = "https://t.me/support",
                    announce = "",
                    webPageUrl = "https://example.com",
                    updatedAt = 1_760_000_000_000,
                ),
                servers = listOf(
                    ServerInfo("🇪🇺 АВТО | Европа", "xray-balancer", "", "a", "VLESS · gRPC · REALITY · Auto"),
                    ServerInfo("🇳🇱 Нидерланды", "vless", "nl1.example.com:443", "b", "VLESS · TCP · REALITY"),
                ),
            ),
            error = null,
            refreshing = false,
            pings = mapOf("b" to 54),
            pinging = false,
            sortByPing = false,
            canBuy = true,
            clipboardLink = null,
            tunnel = TunnelState.Ok("5.6.7.8", "NL", 120),
            shareLink = "https://example.com/sub",
            devices = null,
            onToggle = {},
            onPasteLink = {},
            onRefresh = {},
            onSelectServer = {},
            onPing = {},
            onSortChange = {},
            onScanQr = {},
            onSendToTv = {},
            onOpenBot = {},
            onOpenUrl = {},
            onImportText = {},
            onClipboardDismiss = {},
            onCheck = {},
            onSendReport = {},
            onCopyLink = {},
            onDelete = {},
            onLoadDevices = {},
            onDeleteDevice = {},
            onOpenSettings = {},
        )
    }
}
