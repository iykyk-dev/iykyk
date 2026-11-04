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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import app.iykyk.client.core.ExpiryReminder
import app.iykyk.client.core.ProfileStore
import app.iykyk.client.core.Profiles
import app.iykyk.client.core.ServerInfo
import app.iykyk.client.core.SubscriptionInfo
import app.iykyk.client.core.daysLeft
import app.iykyk.client.core.renewUrl
import app.iykyk.client.tv.TvShare
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
    onToggle: () -> Unit,
    onPasteLink: () -> Unit,
    onRefresh: () -> Unit,
    onSelectServer: (Int) -> Unit,
    onPing: () -> Unit,
    onSortChange: (Boolean) -> Unit,
    onScanQr: () -> Unit,
    onSendToTv: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onImportText: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var showLinkInput by remember { mutableStateOf(false) }
    var showReceive by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(true) }

    Surface(color = IykykColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TopRow(
                onOpenSettings = onOpenSettings,
                onPaste = onPasteLink,
                onScanQr = onScanQr,
                onManual = { showLinkInput = true },
                onReceive = { showReceive = true },
            )
            Spacer(Modifier.height(28.dp))
            ConnectButton(status = status, enabled = profiles.hasServers, onClick = onToggle)
            Spacer(Modifier.height(16.dp))
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
            Spacer(Modifier.height(28.dp))

            if (profiles.hasServers) {
                SubscriptionCard(
                    profiles = profiles,
                    expanded = expanded,
                    onExpandChange = { expanded = it },
                    refreshing = refreshing,
                    pings = pings,
                    pinging = pinging,
                    sortByPing = sortByPing,
                    onRefresh = onRefresh,
                    onPing = onPing,
                    onSortChange = onSortChange,
                    onSendToTv = onSendToTv,
                    onSelectServer = onSelectServer,
                    onOpenUrl = onOpenUrl,
                )
            } else {
                EmptyCard(
                    onPaste = onPasteLink,
                    onScanQr = onScanQr,
                    onManual = { showLinkInput = true },
                    onReceive = { showReceive = true },
                )
            }
            Spacer(Modifier.height(16.dp))
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
    onSendToTv: () -> Unit,
    onSelectServer: (Int) -> Unit,
    onOpenUrl: (String) -> Unit,
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
                            onClick = { menu = false; onSendToTv() },
                        )
                        MenuItem(
                            stringResource(R.string.menu_sort_ping),
                            trailing = if (sortByPing) "✓" else null,
                        ) { menu = false; onSortChange(!sortByPing) }
                    }
                }
            }

            if (sub != null) SubscriptionDetails(sub, profiles.servers.size, onOpenUrl)

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

@Composable
private fun SubscriptionDetails(sub: SubscriptionInfo, serverCount: Int, onOpenUrl: (String) -> Unit) {
    val daysLeft = sub.daysLeft()
    val used = sub.upload + sub.download
    val fraction = if (sub.total > 0) (used.toFloat() / sub.total).coerceIn(0f, 1f) else 0f
    Column(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatBytes(used) + " / " + (if (sub.total > 0) formatBytes(sub.total) else "∞"),
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
        if (daysLeft != null && daysLeft <= ExpiryReminder.REMIND_DAYS) {
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
        if (sub.announce.isNotBlank()) {
            Text(text = sub.announce, color = IykykColors.Warning, style = MaterialTheme.typography.bodySmall)
        }
        val showRenew = daysLeft != null && daysLeft <= ExpiryReminder.REMIND_DAYS && sub.renewUrl.isNotBlank()
        if (showRenew || sub.supportUrl.isNotBlank() || sub.webPageUrl.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (showRenew) {
                    LinkButton(stringResource(R.string.action_renew), primary = true, modifier = Modifier.weight(1f)) {
                        onOpenUrl(sub.renewUrl)
                    }
                }
                if (sub.supportUrl.isNotBlank()) {
                    LinkButton(stringResource(R.string.action_support), modifier = Modifier.weight(1f)) {
                        onOpenUrl(sub.supportUrl)
                    }
                }
                if (sub.webPageUrl.isNotBlank() && !showRenew) {
                    LinkButton(stringResource(R.string.action_website), modifier = Modifier.weight(1f)) {
                        onOpenUrl(sub.webPageUrl)
                    }
                }
            }
        }
        val parts = buildList {
            if (sub.updatedAt > 0) add(stringResource(R.string.sub_updated, dateTime(sub.updatedAt)))
            add(stringResource(R.string.sub_auto_update, sub.updateHours.takeIf { it > 0 } ?: 12))
            add(pluralStringResource(R.plurals.servers_count, serverCount, serverCount))
        }
        Text(
            text = parts.joinToString(" · "),
            color = IykykColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
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

@Composable
private fun EmptyCard(
    onPaste: () -> Unit,
    onScanQr: () -> Unit,
    onManual: () -> Unit,
    onReceive: () -> Unit,
) {
    Surface(color = IykykColors.Surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.empty_title), color = IykykColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(
                text = stringResource(if (ProfileStore.locked) R.string.paste_hint_locked else R.string.paste_hint),
                color = IykykColors.Muted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onPaste,
                colors = ButtonDefaults.buttonColors(containerColor = IykykColors.Accent, contentColor = IykykColors.OnAccent),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(if (ProfileStore.locked) R.string.action_paste_subscription else R.string.action_paste_link),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LinkButton(stringResource(R.string.action_scan_qr), modifier = Modifier.weight(1f), onClick = onScanQr)
                LinkButton(stringResource(R.string.menu_manual), modifier = Modifier.weight(1f), onClick = onManual)
            }
            // On a TV: no camera, no clipboard, typing with a remote is painful.
            TextButton(onClick = onReceive) {
                Text(stringResource(R.string.menu_receive_tv), color = IykykColors.Accent)
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
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.tv_receive_text),
                        color = IykykColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
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
            onToggle = {},
            onPasteLink = {},
            onRefresh = {},
            onSelectServer = {},
            onPing = {},
            onSortChange = {},
            onScanQr = {},
            onSendToTv = {},
            onOpenUrl = {},
            onImportText = {},
            onOpenSettings = {},
        )
    }
}
