package app.iykyk.client.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iykyk.shared.resources.*
import app.iykyk.client.core.DeviceInfo
import app.iykyk.client.core.DevicesInfo
import app.iykyk.client.core.EXPIRY_REMIND_DAYS
import app.iykyk.client.core.Profiles
import app.iykyk.client.core.ServerInfo
import app.iykyk.client.core.SubscriptionInfo
import app.iykyk.client.core.SUPPORT_URL
import app.iykyk.client.core.UpdateState
import app.iykyk.client.core.daysLeft
import app.iykyk.client.tv.TvShare
import app.iykyk.client.vpn.TunnelState
import app.iykyk.client.vpn.VpnStatus
import kotlinx.coroutines.launch
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
    /** Official build: only the provider's subscriptions (wording of the hints). */
    locked: Boolean,
    clipboardLink: String?,
    tunnel: TunnelState?,
    connectedAt: Long,
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
    /** 0 = Подключение, 1 = Подписка (screenshots open the second tab directly). */
    initialTab: Int = 0,
    update: UpdateState = UpdateState.Idle,
    onUpdate: () -> Unit = {},
    /** Version + notes to show once after an update, or null. */
    whatsNew: Pair<String, String>? = null,
    /** Device id and its new name ("" removes it). */
    onRenameDevice: (String, String) -> Unit = { _, _ -> },
    onWhatsNewSeen: () -> Unit = {},
    /** First launch: what the app sends and keeps, before any subscription is added. */
    showDataNotice: Boolean = false,
    onDataNoticeAccepted: () -> Unit = {},
) {
    var updateHidden by remember { mutableStateOf(false) }
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
    var tab by rememberSaveable { mutableStateOf(initialTab) }

    Surface(color = IykykColors.Background, modifier = Modifier.fillMaxSize()) {
        if (!profiles.hasServers) {
            WelcomeScreen(
                canBuy = canBuy,
                locked = locked,
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
                            connectedAt = connectedAt,
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
                            onRenameDevice = onRenameDevice,
                            onSendReport = onSendReport,
                            onOpenSettings = onOpenSettings,
                        )
                    }
                    if (!updateHidden || update is UpdateState.Downloading) {
                        UpdateBanner(
                            state = update,
                            onUpdate = onUpdate,
                            onLater = { updateHidden = true },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
                NavigationBar(containerColor = IykykColors.Surface) {
                    TabItem(tab == 0, Res.drawable.ic_tile, Res.string.tab_connection) { tab = 0 }
                    TabItem(tab == 1, Res.drawable.ic_card, Res.string.tab_subscription) { tab = 1 }
                }
            }
        }
    }

    if (showDataNotice && !profiles.hasServers) {
        DataNoticeDialog(onAccept = onDataNoticeAccepted)
    }

    if (whatsNew != null) {
        WhatsNewDialog(whatsNew.first, whatsNew.second, onDismiss = onWhatsNewSeen)
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
            title = { Text(stringResource(Res.string.delete_title), color = IykykColors.Text) },
            text = { Text(stringResource(Res.string.delete_text), color = IykykColors.Muted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(Res.string.action_delete), color = IykykColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(Res.string.action_cancel), color = IykykColors.Muted)
                }
            },
        )
    }

    if (clipboardLink != null) {
        // Back from the bot with a subscription link copied: offer to add it.
        AlertDialog(
            onDismissRequest = onClipboardDismiss,
            containerColor = IykykColors.Surface,
            title = { Text(stringResource(Res.string.clipboard_found_title), color = IykykColors.Text) },
            text = {
                Text(
                    stringResource(Res.string.clipboard_found_text, clipboardLink.take(60) + if (clipboardLink.length > 60) "…" else ""),
                    color = IykykColors.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onClipboardDismiss()
                    onImportText(clipboardLink)
                }) { Text(stringResource(Res.string.action_add), color = IykykColors.Accent) }
            },
            dismissButton = {
                TextButton(onClick = onClipboardDismiss) {
                    Text(stringResource(Res.string.action_cancel), color = IykykColors.Muted)
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
    val qr = link
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = IykykColors.Surface,
        title = { Text(stringResource(Res.string.menu_other_device), color = IykykColors.Text) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (qr != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(8.dp),
                    ) {
                        QrCode(qr, modifier = Modifier.size(220.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(Res.string.other_device_text),
                    color = IykykColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCopyLink) {
                Text(stringResource(Res.string.menu_copy_link), color = IykykColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_close), color = IykykColors.Muted)
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
private fun RowScope.TabItem(selected: Boolean, icon: DrawableResource, label: StringResource, onClick: () -> Unit) {
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
    connectedAt: Long,
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
    Box(modifier = Modifier.fillMaxSize().then(topInsets)) {
    val scroll = rememberScrollState()
    // The map sits behind the connect button (8 + 48 top row + 28 + 80 dp to its
    // centre) and scrolls away with it.
    WorldMapBackground(
        serverName = profiles.current?.name,
        status = status,
        anchorY = 164.dp - with(androidx.compose.ui.platform.LocalDensity.current) { scroll.value.toDp() },
        modifier = Modifier.fillMaxSize(),
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
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
        Spacer(Modifier.height(24.dp))
        ConnectButton(status = status, enabled = profiles.hasServers, onClick = onToggle)
        Spacer(Modifier.height(16.dp))
        AnimatedVisibility(
            visible = status == VpnStatus.CONNECTED && (connectedAt > 0 || tunnel != null),
            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
            exit = fadeOut(tween(200)) + shrinkVertically(tween(200)),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (connectedAt > 0) SessionTimer(connectedAt)
                if (tunnel != null) {
                    if (connectedAt > 0) Spacer(Modifier.height(4.dp))
                    TunnelLine(tunnel, onCheck)
                }
            }
        }
        val failed = error != null && status == VpnStatus.DISCONNECTED
        if (failed) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.error_prefix, error ?: ""),
                color = IykykColors.Error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        if (failed || (status == VpnStatus.CONNECTED && tunnel == TunnelState.Failed)) {
            TextButton(onClick = onSendReport) {
                Text(stringResource(Res.string.action_send_report), color = IykykColors.Accent)
            }
        }
        Spacer(Modifier.height(24.dp))
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
                painterResource(Res.drawable.ic_settings),
                contentDescription = stringResource(Res.string.cd_settings),
                tint = IykykColors.Muted,
            )
        }
        Header(modifier = Modifier.align(Alignment.Center))
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            IconButton(onClick = { menu = true }) {
                Icon(
                    painterResource(Res.drawable.ic_add),
                    contentDescription = stringResource(Res.string.cd_add),
                    tint = IykykColors.Accent,
                )
            }
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                containerColor = IykykColors.Surface,
            ) {
                MenuItem(stringResource(Res.string.menu_paste), Res.drawable.ic_paste) { menu = false; onPaste() }
                MenuItem(stringResource(Res.string.menu_scan_qr), Res.drawable.ic_scan) { menu = false; onScanQr() }
                MenuItem(stringResource(Res.string.menu_manual), Res.drawable.ic_edit) { menu = false; onManual() }
                MenuItem(stringResource(Res.string.menu_receive_tv), Res.drawable.ic_phone) { menu = false; onReceive() }
            }
        }
    }
}

/** A compact menu row: 18 dp icon, 13 sp text, 40 dp tall (Material dense). */
@Composable
private fun MenuItem(
    text: String,
    icon: DrawableResource,
    trailing: String? = null,
    color: Color = IykykColors.Text,
    iconTint: Color = IykykColors.Accent,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text, color = color, fontSize = 13.sp) },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp)) },
        trailingIcon = if (trailing == null) null else {
            { Text(trailing, color = IykykColors.Accent, fontSize = 13.sp) }
        },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp),
        // Grows with a large system font instead of cutting the text.
        modifier = Modifier.heightIn(min = 40.dp),
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
                    painterResource(Res.drawable.ic_expand),
                    contentDescription = stringResource(Res.string.cd_expand),
                    tint = IykykColors.Muted,
                    modifier = Modifier.rotate(chevron),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = sub?.title?.ifBlank { null } ?: stringResource(
                        if (sub != null) Res.string.subscription_label else Res.string.servers_title,
                    ),
                    color = IykykColors.Text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (sub != null) {
                    HeaderAction(Res.drawable.ic_refresh, Res.string.cd_refresh, busy = refreshing, onClick = onRefresh)
                }
                HeaderAction(Res.drawable.ic_speed, Res.string.cd_ping, busy = pinging, onClick = onPing)
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            painterResource(Res.drawable.ic_more),
                            contentDescription = stringResource(Res.string.cd_more),
                            tint = IykykColors.Muted,
                        )
                    }
                    DropdownMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false },
                        containerColor = IykykColors.Surface,
                    ) {
                        MenuItem(stringResource(Res.string.menu_send_tv), Res.drawable.ic_tv) { menu = false; actions.onSendToTv() }
                        if (actions.canShare) {
                            MenuItem(stringResource(Res.string.menu_other_device), Res.drawable.ic_qr) { menu = false; actions.onShowQr() }
                            MenuItem(stringResource(Res.string.menu_copy_link), Res.drawable.ic_copy) { menu = false; actions.onCopyLink() }
                        }
                        MenuItem(
                            stringResource(Res.string.menu_sort_ping),
                            Res.drawable.ic_sort,
                            trailing = if (sortByPing) "✓" else null,
                        ) { menu = false; onSortChange(!sortByPing) }
                        HorizontalDivider(color = IykykColors.SurfaceHigh)
                        MenuItem(
                            stringResource(Res.string.menu_delete),
                            Res.drawable.ic_delete,
                            color = IykykColors.Error,
                            iconTint = IykykColors.Error,
                        ) { menu = false; actions.onDelete() }
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
private fun HeaderAction(icon: DrawableResource, description: StringResource, busy: Boolean, onClick: () -> Unit) {
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
        TunnelState.Checking -> stringResource(Res.string.tunnel_checking) to IykykColors.Muted
        is TunnelState.Ok -> stringResource(
            Res.string.tunnel_ok,
            countryLabel(tunnel.country),
            tunnel.ip,
            tunnel.latency,
        ) to IykykColors.Muted
        TunnelState.NoInternet -> stringResource(Res.string.tunnel_no_internet) to IykykColors.Warning
        TunnelState.Reconnecting -> stringResource(Res.string.tunnel_reconnecting) to IykykColors.Warning
        TunnelState.Failed -> stringResource(Res.string.tunnel_failed) to IykykColors.Error
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
                text = if (sub.expire > 0) stringResource(Res.string.expire_until, shortDate(sub.expire)) else stringResource(Res.string.expire_never),
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
    if (daysLeft == null || daysLeft > EXPIRY_REMIND_DAYS) return
    Text(
        text = when {
            daysLeft < 0 -> stringResource(Res.string.expiry_ended)
            daysLeft == 0 -> stringResource(Res.string.expiry_today)
            else -> pluralStringResource(Res.plurals.expiry_in_days, daysLeft, daysLeft)
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
    onRenameDevice: (String, String) -> Unit,
    onSendReport: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val sub = profiles.subscription
    var showDevices by remember { mutableStateOf(false) }
    LaunchedEffect(sub?.url, sub?.updatedAt) { onLoadDevices() }
    if (showDevices && devices != null) {
        DevicesDialog(devices, onDelete = onDeleteDevice, onRename = onRenameDevice, onDismiss = { showDevices = false })
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
                Icon(painterResource(Res.drawable.ic_settings), stringResource(Res.string.cd_settings), tint = IykykColors.Muted)
            }
            Text(
                stringResource(Res.string.tab_subscription),
                color = IykykColors.Text,
                style = MaterialTheme.typography.titleLarge,
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
                            sub.title.ifBlank { stringResource(Res.string.subscription_label) },
                            color = IykykColors.Text,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        StatusChip(daysLeft)
                    }
                    Text(
                        if (sub.expire > 0) stringResource(Res.string.sub_valid_until, longDate(sub.expire)) else stringResource(Res.string.sub_status_unlimited),
                        color = IykykColors.Text,
                    )
                    if (daysLeft != null && daysLeft >= 0) {
                        Text(
                            pluralStringResource(Res.plurals.sub_days_left, daysLeft, daysLeft),
                            color = if (daysLeft <= EXPIRY_REMIND_DAYS) IykykColors.Warning else IykykColors.Muted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(Res.string.sub_traffic), color = IykykColors.Muted, style = MaterialTheme.typography.bodySmall)
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
                                Text(stringResource(Res.string.devices_label), color = IykykColors.Muted, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    if (devices.limit > 0) {
                                        stringResource(Res.string.devices_of, devices.used, devices.limit)
                                    } else {
                                        stringResource(Res.string.devices_unlimited, devices.used)
                                    },
                                    color = if (devices.limit > 0 && devices.used >= devices.limit) IykykColors.Warning else IykykColors.Text,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Chevron()
                        }
                    }
                    if (sub.hwidLimitReached) {
                        Text(stringResource(Res.string.hwid_limit_reached), color = IykykColors.Warning, style = MaterialTheme.typography.bodySmall)
                    }
                    if (sub.announce.isNotBlank()) {
                        Text(sub.announce, color = IykykColors.Warning, style = MaterialTheme.typography.bodySmall)
                    }
                    if (canBuy) {
                        Spacer(Modifier.height(4.dp))
                        LinkButton(stringResource(Res.string.action_renew_bot), primary = true, modifier = Modifier.fillMaxWidth(), onClick = onOpenBot)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Surface(color = IykykColors.Surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column {
                if (sub != null) {
                    ActionRow(Res.drawable.ic_refresh, stringResource(Res.string.sub_refresh), busy = refreshing, onClick = onRefresh)
                }
                ActionRow(Res.drawable.ic_help, stringResource(Res.string.action_support)) { onOpenUrl(SUPPORT_URL) }
                if (sub?.webPageUrl?.isNotBlank() == true) {
                    ActionRow(Res.drawable.ic_globe, stringResource(Res.string.action_website)) { onOpenUrl(sub.webPageUrl) }
                }
                ActionRow(Res.drawable.ic_tv, stringResource(Res.string.menu_send_tv), onClick = actions.onSendToTv)
                if (actions.canShare) {
                    ActionRow(Res.drawable.ic_qr, stringResource(Res.string.menu_other_device), onClick = actions.onShowQr)
                    ActionRow(Res.drawable.ic_copy, stringResource(Res.string.menu_copy_link), onClick = actions.onCopyLink)
                }
                ActionRow(Res.drawable.ic_report, stringResource(Res.string.action_send_report), onClick = onSendReport)
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(color = IykykColors.Surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = actions.onDelete)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(Res.drawable.ic_delete), contentDescription = null, tint = IykykColors.Error)
                Spacer(Modifier.size(12.dp))
                Text(stringResource(Res.string.menu_delete), color = IykykColors.Error)
            }
        }

        if (sub != null) {
            Spacer(Modifier.height(12.dp))
            val parts = buildList {
                if (sub.updatedAt > 0) add(stringResource(Res.string.sub_updated, dateTime(sub.updatedAt)))
                add(stringResource(Res.string.sub_auto_update, sub.updateHours.takeIf { it > 0 } ?: 12))
                add(pluralStringResource(Res.plurals.servers_count, profiles.servers.size, profiles.servers.size))
            }
            Text(
                // Wraps only between the parts, never inside "6 серверов".
                parts.joinToString(" · ") { it.replace(' ', '\u00A0') },
                color = IykykColors.Muted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** The subscription's devices; any but this one can be removed to free a slot. */
@Composable
private fun DevicesDialog(
    devices: DevicesInfo,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirm by remember { mutableStateOf<DeviceInfo?>(null) }
    var naming by remember { mutableStateOf<DeviceInfo?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = IykykColors.Surface,
        title = {
            Column {
                Text(stringResource(Res.string.devices_title), color = IykykColors.Text)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (devices.limit > 0) {
                        stringResource(Res.string.devices_of, devices.used, devices.limit)
                    } else {
                        stringResource(Res.string.devices_unlimited, devices.used)
                    },
                    color = if (devices.limit > 0 && devices.used >= devices.limit) IykykColors.Warning else IykykColors.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // This device first, then the most recently added.
                val sorted = devices.devices.sortedWith(compareByDescending<DeviceInfo> { it.current }.thenByDescending { it.added })
                for (device in sorted) {
                    DeviceRow(device, onRemove = { confirm = device }, onName = { naming = device })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_close), color = IykykColors.Muted)
            }
        },
    )
    naming?.let { device ->
        DeviceNameDialog(
            initial = device.name,
            onSave = { name ->
                naming = null
                if (name != device.name) onRename(device.id, name)
            },
            onDismiss = { naming = null },
        )
    }
    confirm?.let { device ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = IykykColors.Surface,
            title = { Text(stringResource(Res.string.device_remove_title), color = IykykColors.Text) },
            text = {
                Text(
                    stringResource(Res.string.device_remove_text, device.name.ifBlank { device.model.ifBlank { device.platform } }),
                    color = IykykColors.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    onDelete(device.id)
                }) { Text(stringResource(Res.string.action_delete), color = IykykColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) {
                    Text(stringResource(Res.string.action_cancel), color = IykykColors.Muted)
                }
            },
        )
    }
}

/**
 * One device: a type icon, its name (or model) on one line, details under it,
 * delete on the right. Tapping the text gives the device a name.
 */
@Composable
private fun DeviceRow(device: DeviceInfo, onRemove: () -> Unit, onName: () -> Unit) {
    val kind = (device.platform + " " + device.model).lowercase()
    val icon = when {
        "tv" in kind -> Res.drawable.ic_tv
        listOf("windows", "mac", "linux", "desktop").any { it in kind } -> Res.drawable.ic_computer
        else -> Res.drawable.ic_phone
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (device.current) IykykColors.Accent.copy(alpha = 0.14f) else IykykColors.SurfaceHigh),
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = if (device.current) IykykColors.Accent else IykykColors.Muted,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onName),
        ) {
            val model = device.model.ifBlank { device.platform.ifBlank { "?" } }
            Text(
                device.name.ifBlank { model },
                color = IykykColors.Text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details = listOfNotNull(
                model.takeIf { device.name.isNotBlank() },
                listOf(device.platform, device.os).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null },
            ).joinToString(" · ")
            val activity = if (device.current) {
                stringResource(Res.string.device_current)
            } else {
                isoDate(device.lastSeen.ifBlank { device.added })?.let { stringResource(Res.string.device_last_seen, it) }
            }
            Text(details, color = IykykColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (activity != null) {
                Text(
                    activity,
                    color = if (device.current) IykykColors.Accent else IykykColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (device.current) FontWeight.Medium else null,
                )
            }
            if (device.name.isBlank()) {
                Text(
                    stringResource(Res.string.device_name_add),
                    color = IykykColors.Accent,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        if (!device.current) {
            IconButton(onClick = onRemove) {
                Icon(
                    painterResource(Res.drawable.ic_delete),
                    contentDescription = stringResource(Res.string.action_delete),
                    tint = IykykColors.Error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** "Мамин телефон": a name for a device, up to 40 characters; empty removes it. */
@Composable
private fun DeviceNameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = IykykColors.Surface,
        title = { Text(stringResource(Res.string.device_name_title), color = IykykColors.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.replace("\n", " ").take(40) },
                    singleLine = true,
                    placeholder = { Text(stringResource(Res.string.device_name_hint)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = IykykColors.Accent,
                        focusedTextColor = IykykColors.Text,
                        unfocusedTextColor = IykykColors.Text,
                        cursorColor = IykykColors.Accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(Res.string.device_name_note), color = IykykColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }) {
                Text(stringResource(Res.string.action_save), color = IykykColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel), color = IykykColors.Muted)
            }
        },
    )
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
        daysLeft == null -> stringResource(Res.string.sub_status_unlimited) to Color(0xFF3FA66B)
        daysLeft < 0 -> stringResource(Res.string.sub_status_expired) to IykykColors.Error
        daysLeft <= EXPIRY_REMIND_DAYS -> stringResource(Res.string.sub_status_ending) to IykykColors.Warning
        else -> stringResource(Res.string.sub_status_active) to Color(0xFF3FA66B)
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
private fun ActionRow(icon: DrawableResource?, text: String, busy: Boolean = false, onClick: () -> Unit) {
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
        Chevron()
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
        delay == -2 -> stringResource(Res.string.ping_udp) to IykykColors.Muted
        delay < 0 -> stringResource(Res.string.ping_failed) to IykykColors.Error
        delay < 150 -> stringResource(Res.string.ping_ms, delay) to Color(0xFF3FA66B)
        delay < 400 -> stringResource(Res.string.ping_ms, delay) to IykykColors.Warning
        else -> stringResource(Res.string.ping_ms, delay) to IykykColors.Error
    }
    val highlight by animateColorAsState(
        if (selected) IykykColors.Accent.copy(alpha = 0.14f) else IykykColors.Accent.copy(alpha = 0f),
        animationSpec = tween(250),
        label = "serverHighlight",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(highlight)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(IykykColors.SurfaceHigh),
        ) {
            // The flag fills a fixed circle: sized in dp, so a large system font cannot crop it.
            Text(flag ?: "🌐", fontSize = 20.dp.fixedSp(), maxLines = 1)
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
    locked: Boolean,
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
                Icon(painterResource(Res.drawable.ic_settings), stringResource(Res.string.cd_settings), tint = IykykColors.Muted)
            }
        }
        Spacer(Modifier.height(24.dp))
        Header()
        Spacer(Modifier.height(40.dp))
        Text(stringResource(Res.string.welcome_title), color = IykykColors.Text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(Res.string.welcome_text),
            color = IykykColors.Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (canBuy) {
            LinkButton(stringResource(Res.string.action_buy), primary = true, modifier = Modifier.fillMaxWidth(), onClick = onOpenBot)
            Spacer(Modifier.height(32.dp))
        }
        Text(stringResource(Res.string.welcome_have), color = IykykColors.Text, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(if (locked) Res.string.paste_hint_locked else Res.string.paste_hint),
            color = IykykColors.Muted,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            if (canBuy) {
                LinkButton(stringResource(Res.string.action_get_in_bot), modifier = Modifier.fillMaxWidth(), onClick = onOpenBot)
            }
            LinkButton(
                stringResource(if (locked) Res.string.action_paste_subscription else Res.string.action_paste_link),
                modifier = Modifier.fillMaxWidth(),
                onClick = onPaste,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                LinkButton(stringResource(Res.string.action_scan_qr), modifier = Modifier.weight(1f), onClick = onScanQr)
                LinkButton(stringResource(Res.string.welcome_manual), modifier = Modifier.weight(1f), onClick = onManual)
            }
        }
        // On a TV: no camera, no clipboard, typing with a remote is painful.
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onReceive) {
            Text(stringResource(Res.string.menu_receive_tv), color = IykykColors.Accent)
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
        icon = { Icon(painterResource(Res.drawable.ic_tv), contentDescription = null, tint = IykykColors.Accent) },
        title = { Text(stringResource(Res.string.menu_send_tv), color = IykykColors.Text) },
        text = {
            Column {
                Steps(
                    stringResource(Res.string.tv_send_step1),
                    stringResource(Res.string.tv_send_step2),
                    stringResource(Res.string.tv_send_step3),
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(Res.string.tv_same_wifi), color = IykykColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(
                onClick = onScan,
                colors = ButtonDefaults.buttonColors(containerColor = IykykColors.Accent, contentColor = IykykColors.OnAccent),
            ) { Text(stringResource(Res.string.tv_scan_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel), color = IykykColors.Muted)
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
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(IykykColors.Accent.copy(alpha = 0.15f)),
                ) {
                    Text("${index + 1}", color = IykykColors.Accent, fontSize = 13.dp.fixedSp(), fontWeight = FontWeight.Bold, maxLines = 1)
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
    val scope = rememberCoroutineScope()
    val receiver = remember {
        runCatching { TvShare.Receiver { text -> scope.launch { onReceived(text) } } }.getOrNull()
    }
    DisposableEffect(receiver) { onDispose { receiver?.close() } }
    val code = receiver?.code
    val qr = code
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = IykykColors.Surface,
        title = { Text(stringResource(Res.string.tv_receive_title), color = IykykColors.Text) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (qr != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(8.dp),
                    ) {
                        QrCode(qr, modifier = Modifier.size(220.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Steps(
                        stringResource(Res.string.tv_receive_step1),
                        stringResource(Res.string.tv_receive_step2),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = IykykColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(Res.string.tv_waiting), color = IykykColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(stringResource(Res.string.tv_receive_no_network), color = IykykColors.Error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel), color = IykykColors.Muted)
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
    title: String = stringResource(Res.string.enter_link_title),
    hint: String = stringResource(Res.string.enter_link_hint),
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
                Text(stringResource(Res.string.action_add), color = IykykColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel), color = IykykColors.Muted)
            }
        },
    )
}

@Composable
private fun Header(modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(Res.string.app_name),
            color = IykykColors.Text,
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 5.sp,
        )
        Text(
            text = stringResource(Res.string.tagline),
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
        if (status.isActive) Res.string.action_disconnect else Res.string.action_connect,
    )

    // Shrinks a little under the finger and springs back, like a physical button.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "buttonScale",
    )
    Surface(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = CircleShape,
        color = fill,
        border = BorderStroke(2.dp, IykykColors.Accent.copy(alpha = if (enabled) 0.5f else 0.15f)),
        interactionSource = interaction,
        modifier = Modifier.size(160.dp).graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(
                painter = painterResource(Res.drawable.ic_tile),
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.height(8.dp))
            Crossfade(targetState = status, animationSpec = tween(250), label = "statusText") { shown ->
            Text(
                text = stringResource(
                    when (shown) {
                        VpnStatus.DISCONNECTED -> Res.string.status_disconnected
                        VpnStatus.CONNECTING -> Res.string.status_connecting
                        VpnStatus.CONNECTED -> Res.string.status_connected
                        VpnStatus.DISCONNECTING -> Res.string.status_disconnecting
                    },
                ),
                color = iconTint,
                // Inside a fixed 160 dp circle: dp-sized and one line, so «Подключение…»
                // is never cut by the circle's edge with a large system font.
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.dp.fixedSp(), lineHeight = 20.dp.fixedSp()),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
            }
        }
    }
}

/** "00:12:34" since the session started; ticks every second while shown. */
@Composable
private fun SessionTimer(connectedAt: Long) {
    var now by remember { mutableStateOf((System.nanoTime() / 1_000_000)) }
    LaunchedEffect(connectedAt) {
        while (true) {
            now = (System.nanoTime() / 1_000_000)
            kotlinx.coroutines.delay(1_000L - now % 1_000L)
        }
    }
    val seconds = ((now - connectedAt) / 1000).coerceAtLeast(0)
    Text(
        text = String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60),
        color = IykykColors.Text,
        fontSize = 26.sp,
        fontWeight = FontWeight.SemiBold,
        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
        letterSpacing = 1.sp,
    )
}

@Composable
private fun LinkButton(text: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = IykykColors.Accent,
                contentColor = IykykColors.OnAccent,
            ),
        ) { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp),
            border = BorderStroke(1.dp, IykykColors.Muted.copy(alpha = 0.4f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = IykykColors.Text),
        ) { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge) }
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

/** A QR code drawn from zxing's bit matrix (works the same on Android and desktop). */
@Composable
internal fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        runCatching {
            com.google.zxing.qrcode.QRCodeWriter().encode(
                text,
                com.google.zxing.BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(com.google.zxing.EncodeHintType.MARGIN to 0),
            )
        }.getOrNull()
    } ?: return
    androidx.compose.foundation.Canvas(modifier) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = androidx.compose.ui.geometry.Offset(x * cell, y * cell),
                        size = androidx.compose.ui.geometry.Size(cell + 0.5f, cell + 0.5f),
                    )
                }
            }
        }
    }
}

/** "›" at the end of a row that opens something; one glyph everywhere. */
@Composable
internal fun Chevron() {
    Icon(
        painterResource(Res.drawable.ic_expand),
        contentDescription = null,
        tint = IykykColors.Muted,
        modifier = Modifier.size(20.dp).rotate(-90f),
    )
}

/** A text size that ignores the system font scale: for text inside fixed shapes. */
@Composable
private fun androidx.compose.ui.unit.Dp.fixedSp(): androidx.compose.ui.unit.TextUnit =
    with(androidx.compose.ui.platform.LocalDensity.current) { this@fixedSp.toSp() }
