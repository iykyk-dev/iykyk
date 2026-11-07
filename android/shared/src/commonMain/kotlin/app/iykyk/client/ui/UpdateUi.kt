package app.iykyk.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.iykyk.client.core.UpdateState
import app.iykyk.shared.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** A floating card over the bottom of the screen: "IYKYK 1.1 is available · Update". */
@Composable
internal fun UpdateBanner(
    state: UpdateState,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val version = when (state) {
        is UpdateState.Available -> state.version
        is UpdateState.Downloading -> state.version
        else -> return
    }
    Surface(
        color = IykykColors.Surface,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(Res.drawable.ic_refresh),
                    contentDescription = null,
                    tint = IykykColors.Accent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    stringResource(Res.string.update_available, version),
                    color = IykykColors.Text,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
            }
            if (state is UpdateState.Downloading) {
                Spacer(Modifier.size(12.dp))
                val progress = state.progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        color = IykykColors.Accent,
                        trackColor = IykykColors.SurfaceHigh,
                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        color = IykykColors.Accent,
                        trackColor = IykykColors.SurfaceHigh,
                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                    )
                }
                Spacer(Modifier.size(8.dp))
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onLater, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(Res.string.update_later), color = IykykColors.Muted)
                    }
                    TextButton(onClick = onUpdate, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(Res.string.update_now), color = IykykColors.Accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Shown once after an update: what changed in this version. */
@Composable
internal fun WhatsNewDialog(version: String, notes: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = IykykColors.Surface,
        title = { Text(stringResource(Res.string.whats_new_title, version), color = IykykColors.Text) },
        text = {
            Text(
                notes,
                color = IykykColors.Text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_close), color = IykykColors.Accent)
            }
        },
    )
}

/** The status line under "Check for updates" in Settings. */
@Composable
internal fun updateStatusText(state: UpdateState): String = when (state) {
    UpdateState.Idle -> stringResource(Res.string.update_check_hint)
    UpdateState.Checking -> stringResource(Res.string.update_checking)
    UpdateState.UpToDate -> stringResource(Res.string.update_up_to_date)
    is UpdateState.Available -> stringResource(Res.string.update_available, state.version)
    is UpdateState.Downloading -> stringResource(Res.string.update_downloading)
    is UpdateState.Failed -> stringResource(Res.string.update_failed, state.message)
}
