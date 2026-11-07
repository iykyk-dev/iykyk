package app.iykyk.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.rotate
import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iykyk.shared.resources.*
import app.iykyk.client.core.AppMode
import app.iykyk.client.core.AppSettings
import app.iykyk.client.core.ThemeMode
import app.iykyk.client.core.AboutInfo
import app.iykyk.client.core.SOURCE_URL
import app.iykyk.client.core.UpdateState
import androidx.compose.ui.text.font.FontFamily

@Composable
fun SettingsScreen(
    settings: AppSettings,
    versions: String,
    onBack: () -> Unit,
    onBypassChange: (Boolean) -> Unit,
    onFragmentChange: (Boolean) -> Unit,
    onPreferXrayChange: (Boolean) -> Unit,
    onAutoLaunchChange: (Boolean) -> Unit,
    onAutoBootChange: (Boolean) -> Unit,
    onRemindersChange: (Boolean) -> Unit,
    onOpenVpnSettings: () -> Unit,
    onRemoteDnsChange: (String) -> Unit,
    onDirectDnsChange: (String) -> Unit,
    onAppModeChange: (AppMode) -> Unit,
    onChooseApps: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    /** Phone/TV-only settings: start on boot, reminders, kill switch, per-app routing. */
    mobile: Boolean = true,
    about: AboutInfo? = null,
    update: UpdateState = UpdateState.Idle,
    /** Null when this build does not update itself (debug, store builds). */
    onCheckUpdates: (() -> Unit)? = null,
    onOpenUrl: (String) -> Unit = {},
    /** Windows only: the "system proxy" mode switch. */
    onSystemProxyChange: ((Boolean) -> Unit)? = null,
    /** Phone only: put the installed bank and government apps into the exceptions. */
    onBankPreset: (() -> Unit)? = null,
) {
    Surface(color = IykykColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            TopBar(stringResource(Res.string.settings_title), onBack)
            Spacer(Modifier.height(8.dp))

            SectionTitle(stringResource(Res.string.settings_appearance))
            Card {
                ChoiceRow(
                    title = stringResource(Res.string.theme_title),
                    value = settings.themeMode.name,
                    choices = listOf(
                        ThemeMode.SYSTEM.name to stringResource(Res.string.theme_system),
                        ThemeMode.LIGHT.name to stringResource(Res.string.theme_light),
                        ThemeMode.DARK.name to stringResource(Res.string.theme_dark),
                    ),
                    allowCustom = false,
                    onChange = { onThemeChange(ThemeMode.valueOf(it)) },
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionTitle(stringResource(Res.string.settings_connection))
            Card {
                Column {
                    SwitchRow(
                        title = stringResource(Res.string.auto_launch_title),
                        subtitle = stringResource(Res.string.auto_launch_subtitle),
                        checked = settings.autoConnectOnLaunch,
                        onChange = onAutoLaunchChange,
                    )
                    SwitchRow(
                        title = stringResource(if (mobile) Res.string.auto_boot_title else Res.string.win_autostart_title),
                        subtitle = stringResource(if (mobile) Res.string.auto_boot_subtitle else Res.string.win_autostart_subtitle),
                        checked = settings.autoConnectOnBoot,
                        onChange = onAutoBootChange,
                    )
                    if (onSystemProxyChange != null) {
                        SwitchRow(
                            title = stringResource(Res.string.system_proxy_title),
                            subtitle = stringResource(Res.string.system_proxy_subtitle),
                            checked = settings.systemProxy,
                            onChange = onSystemProxyChange,
                        )
                    }
                    SwitchRow(
                        title = stringResource(Res.string.reminders_title),
                        subtitle = stringResource(Res.string.reminders_subtitle),
                        checked = settings.expiryReminders,
                        onChange = onRemindersChange,
                    )
                    if (mobile) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenVpnSettings)
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(Res.string.killswitch_title), color = IykykColors.Text, fontWeight = FontWeight.Medium)
                                Text(
                                    stringResource(Res.string.killswitch_subtitle),
                                    color = IykykColors.Muted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Chevron()
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionTitle(stringResource(Res.string.settings_routing))
            Card {
                Column {
                    SwitchRow(
                        title = stringResource(Res.string.bypass_title),
                        subtitle = stringResource(Res.string.bypass_subtitle),
                        checked = settings.bypassRussia,
                        onChange = onBypassChange,
                    )
                    SwitchRow(
                        title = stringResource(Res.string.fragment_title),
                        subtitle = stringResource(Res.string.fragment_subtitle),
                        checked = settings.fragment,
                        onChange = onFragmentChange,
                    )
                    SwitchRow(
                        title = stringResource(Res.string.prefer_xray_title),
                        subtitle = stringResource(Res.string.prefer_xray_subtitle),
                        checked = settings.preferXray,
                        onChange = onPreferXrayChange,
                    )
                }
            }

            run {
                Spacer(Modifier.height(24.dp))
                SectionTitle(stringResource(if (mobile) Res.string.settings_apps else Res.string.settings_programs))
                Card {
                    Column {
                        for ((mode, label) in listOf(
                            AppMode.ALL to Res.string.apps_mode_all,
                            AppMode.ONLY_SELECTED to Res.string.apps_mode_only,
                            AppMode.EXCEPT_SELECTED to Res.string.apps_mode_except,
                        )) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAppModeChange(mode) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = settings.appMode == mode,
                                    onClick = { onAppModeChange(mode) },
                                    colors = RadioButtonDefaults.colors(selectedColor = IykykColors.Accent),
                                )
                                Text(stringResource(label), color = IykykColors.Text)
                            }
                        }
                        if (settings.appMode != AppMode.ALL) {
                            Text(
                                stringResource(
                                    if (settings.appMode == AppMode.ONLY_SELECTED) Res.string.apps_hint_only else Res.string.apps_hint_except,
                                ),
                                color = IykykColors.Muted,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = onChooseApps)
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(Res.string.apps_choose),
                                    color = IykykColors.Accent,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    stringResource(Res.string.apps_selected, settings.apps.size) + " ›",
                                    color = IykykColors.Muted,
                                )
                            }
                        }
                        if (onBankPreset != null && settings.appMode != AppMode.ONLY_SELECTED) {
                            LinkRow(
                                title = stringResource(Res.string.preset_banks_title),
                                subtitle = stringResource(Res.string.preset_banks_subtitle),
                                onClick = onBankPreset,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(Res.string.settings_reconnect_hint),
                color = IykykColors.Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Spacer(Modifier.height(24.dp))
            SectionTitle(stringResource(Res.string.settings_about))
            Card {
                Column {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (about != null) stringResource(Res.string.about_version, about.version) else stringResource(Res.string.settings_versions),
                            color = IykykColors.Text,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(versions, color = IykykColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (onCheckUpdates != null) {
                        LinkRow(
                            title = stringResource(Res.string.update_check_title),
                            subtitle = updateStatusText(update),
                            onClick = onCheckUpdates,
                        )
                    }
                    LinkRow(
                        title = stringResource(Res.string.about_source),
                        subtitle = stringResource(Res.string.about_source_subtitle),
                        onClick = { onOpenUrl(SOURCE_URL) },
                    )
                    if (about != null && about.signature.isNotBlank()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(Res.string.about_signature), color = IykykColors.Text, fontWeight = FontWeight.Medium)
                            Text(
                                // 32 bytes as four lines of eight, so no line breaks mid-byte.
                                about.signature.split(":").chunked(8).joinToString("\n") { it.joinToString(":") },
                                color = IykykColors.Muted,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                            Text(stringResource(Res.string.about_signature_hint), color = IykykColors.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = IykykColors.Text, fontWeight = FontWeight.Medium)
            Text(subtitle, color = IykykColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Chevron()
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = IykykColors.Text, fontWeight = FontWeight.Medium)
            Text(subtitle, color = IykykColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = IykykColors.OnAccent,
                checkedTrackColor = IykykColors.Accent,
            ),
        )
    }
}

/** A setting with a few fixed values (and optionally a custom one), chosen in a dialog. */
@Composable
private fun ChoiceRow(
    title: String,
    value: String,
    choices: List<Pair<String, String>>,
    allowCustom: Boolean,
    onChange: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var customInput by remember { mutableStateOf(false) }
    val label = choices.firstOrNull { it.first == value }?.second ?: value
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = IykykColors.Text, fontWeight = FontWeight.Medium)
            Text(label, color = IykykColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Chevron()
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = IykykColors.Surface,
            title = { Text(title, color = IykykColors.Text) },
            text = {
                Column {
                    for ((key, name) in choices) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    open = false
                                    onChange(key)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = value == key,
                                onClick = {
                                    open = false
                                    onChange(key)
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = IykykColors.Accent),
                            )
                            Text(name, color = IykykColors.Text)
                        }
                    }
                    if (allowCustom) {
                        TextButton(onClick = {
                            open = false
                            customInput = true
                        }) {
                            Text(stringResource(Res.string.dns_custom), color = IykykColors.Accent)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) {
                    Text(stringResource(Res.string.action_cancel), color = IykykColors.Muted)
                }
            },
        )
    }
    if (customInput) {
        LinkInputDialog(
            title = stringResource(Res.string.dns_custom),
            hint = stringResource(Res.string.dns_custom_hint),
            initial = value.takeIf { it.startsWith("https://") }.orEmpty(),
            onConfirm = {
                customInput = false
                if (it.startsWith("https://")) onChange(it)
            },
            onDismiss = { customInput = false },
        )
    }
}

@Composable
fun TopBar(title: String, onBack: () -> Unit) {
    // Same layout as the main tabs: a 48 dp button on the left, the title centred.
    Box(modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(
                painterResource(Res.drawable.ic_expand),
                contentDescription = stringResource(Res.string.action_back),
                tint = IykykColors.Accent,
                modifier = Modifier.rotate(90f),
            )
        }
        Text(
            title,
            color = IykykColors.Text,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        color = IykykColors.Muted,
        style = MaterialTheme.typography.labelMedium,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(color = IykykColors.Surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        content()
    }
}
