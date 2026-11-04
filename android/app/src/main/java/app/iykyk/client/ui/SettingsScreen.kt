package app.iykyk.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iykyk.client.R
import app.iykyk.client.core.AppMode
import app.iykyk.client.core.AppSettings
import app.iykyk.client.core.ThemeMode

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
) {
    Surface(color = IykykColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            TopBar(stringResource(R.string.settings_title), onBack)
            Spacer(Modifier.height(8.dp))

            SectionTitle(stringResource(R.string.settings_appearance))
            Card {
                ChoiceRow(
                    title = stringResource(R.string.theme_title),
                    value = settings.themeMode.name,
                    choices = listOf(
                        ThemeMode.SYSTEM.name to stringResource(R.string.theme_system),
                        ThemeMode.LIGHT.name to stringResource(R.string.theme_light),
                        ThemeMode.DARK.name to stringResource(R.string.theme_dark),
                    ),
                    allowCustom = false,
                    onChange = { onThemeChange(ThemeMode.valueOf(it)) },
                )
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_connection))
            Card {
                Column {
                    SwitchRow(
                        title = stringResource(R.string.auto_launch_title),
                        subtitle = stringResource(R.string.auto_launch_subtitle),
                        checked = settings.autoConnectOnLaunch,
                        onChange = onAutoLaunchChange,
                    )
                    SwitchRow(
                        title = stringResource(R.string.auto_boot_title),
                        subtitle = stringResource(R.string.auto_boot_subtitle),
                        checked = settings.autoConnectOnBoot,
                        onChange = onAutoBootChange,
                    )
                    SwitchRow(
                        title = stringResource(R.string.reminders_title),
                        subtitle = stringResource(R.string.reminders_subtitle),
                        checked = settings.expiryReminders,
                        onChange = onRemindersChange,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenVpnSettings)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.killswitch_title), color = IykykColors.Text, fontWeight = FontWeight.Medium)
                            Text(
                                stringResource(R.string.killswitch_subtitle),
                                color = IykykColors.Muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("›", color = IykykColors.Muted, fontSize = 22.sp)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_routing))
            Card {
                Column {
                    SwitchRow(
                        title = stringResource(R.string.bypass_title),
                        subtitle = stringResource(R.string.bypass_subtitle),
                        checked = settings.bypassRussia,
                        onChange = onBypassChange,
                    )
                    SwitchRow(
                        title = stringResource(R.string.fragment_title),
                        subtitle = stringResource(R.string.fragment_subtitle),
                        checked = settings.fragment,
                        onChange = onFragmentChange,
                    )
                    SwitchRow(
                        title = stringResource(R.string.prefer_xray_title),
                        subtitle = stringResource(R.string.prefer_xray_subtitle),
                        checked = settings.preferXray,
                        onChange = onPreferXrayChange,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_dns))
            Card {
                Column {
                    ChoiceRow(
                        title = stringResource(R.string.remote_dns_title),
                        value = settings.remoteDns,
                        choices = listOf(
                            "cloudflare" to stringResource(R.string.dns_cloudflare),
                            "google" to stringResource(R.string.dns_google),
                            "quad9" to stringResource(R.string.dns_quad9),
                            "adguard" to stringResource(R.string.dns_adguard),
                        ),
                        allowCustom = true,
                        onChange = onRemoteDnsChange,
                    )
                    ChoiceRow(
                        title = stringResource(R.string.direct_dns_title),
                        value = settings.directDns,
                        choices = listOf(
                            "yandex" to stringResource(R.string.dns_yandex),
                            "cloudflare" to stringResource(R.string.dns_cloudflare),
                            "google" to stringResource(R.string.dns_google),
                        ),
                        allowCustom = false,
                        onChange = onDirectDnsChange,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_apps))
            Card {
                Column {
                    for ((mode, label) in listOf(
                        AppMode.ALL to R.string.apps_mode_all,
                        AppMode.ONLY_SELECTED to R.string.apps_mode_only,
                        AppMode.EXCEPT_SELECTED to R.string.apps_mode_except,
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onChooseApps)
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.apps_choose),
                                color = IykykColors.Accent,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stringResource(R.string.apps_selected, settings.apps.size) + " ›",
                                color = IykykColors.Muted,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_reconnect_hint),
                color = IykykColors.Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_about))
            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.settings_versions), color = IykykColors.Text, fontWeight = FontWeight.Medium)
                    Text(versions, color = IykykColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
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
        Text("›", color = IykykColors.Muted, fontSize = 22.sp)
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
                            Text(stringResource(R.string.dns_custom), color = IykykColors.Accent)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) {
                    Text(stringResource(R.string.action_cancel), color = IykykColors.Muted)
                }
            },
        )
    }
    if (customInput) {
        LinkInputDialog(
            title = stringResource(R.string.dns_custom),
            hint = stringResource(R.string.dns_custom_hint),
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
internal fun TopBar(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) {
            Text("‹ " + stringResource(R.string.action_back), color = IykykColors.Accent)
        }
        Spacer(Modifier.width(8.dp))
        Text(title, color = IykykColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
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
