package app.iykyk.client.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.iykyk.client.core.DiagCheck
import app.iykyk.client.core.DiagFix
import app.iykyk.client.core.DiagState
import app.iykyk.client.core.Diagnosis
import app.iykyk.shared.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * "Не работает?": the checks run one after another, each with a status mark,
 * then one proposed fix and the report button.
 */
@Composable
internal fun DiagnosisDialog(
    diagnosis: Diagnosis,
    onFix: (DiagFix) -> Unit,
    onReport: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = IykykColors.Surface,
        title = { Text(stringResource(Res.string.diag_title), color = IykykColors.Text, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                for (check in DiagCheck.entries) {
                    CheckRow(
                        title = stringResource(
                            when (check) {
                                DiagCheck.INTERNET -> Res.string.diag_internet
                                DiagCheck.SUBSCRIPTION -> Res.string.diag_subscription
                                DiagCheck.SERVERS -> Res.string.diag_servers
                                DiagCheck.TUNNEL -> Res.string.diag_tunnel
                            },
                        ),
                        state = diagnosis.states[check] ?: DiagState.WAITING,
                        detail = diagnosis.details[check].orEmpty(),
                    )
                }
                if (diagnosis.done) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(IykykColors.SurfaceHigh)
                            .padding(14.dp),
                    ) {
                        Text(fixText(diagnosis), color = IykykColors.Text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            val label = if (diagnosis.done) fixButton(diagnosis.fix) else null
            if (label != null) {
                Button(
                    onClick = { onFix(diagnosis.fix) },
                    colors = ButtonDefaults.buttonColors(containerColor = IykykColors.Accent, contentColor = IykykColors.OnAccent),
                    modifier = Modifier.heightIn(min = 44.dp),
                ) { Text(label, fontWeight = FontWeight.SemiBold) }
            } else {
                TextButton(onClick = onClose) { Text(stringResource(Res.string.action_close), color = IykykColors.Accent) }
            }
        },
        dismissButton = {
            if (diagnosis.done) {
                TextButton(onClick = onReport) {
                    Text(stringResource(Res.string.diag_report), color = IykykColors.Muted)
                }
            }
        },
    )
}

@Composable
private fun CheckRow(title: String, state: DiagState, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(top = 2.dp).size(20.dp)) {
            when (state) {
                DiagState.RUNNING -> CircularProgressIndicator(color = IykykColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                else -> StateMark(state, stateColor(state))
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = if (state == DiagState.WAITING) IykykColors.Muted else IykykColors.Text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (detail.isNotBlank()) {
                Text(detail, color = IykykColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** A drawn mark (not a font glyph, which sits off-centre in some fonts). */
@Composable
private fun StateMark(state: DiagState, color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        drawCircle(color.copy(alpha = if (state == DiagState.WAITING) 0.25f else 0.18f))
        val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (state) {
            DiagState.OK -> drawPath(
                Path().apply {
                    moveTo(w * 0.29f, w * 0.52f)
                    lineTo(w * 0.44f, w * 0.66f)
                    lineTo(w * 0.72f, w * 0.36f)
                },
                color, style = stroke,
            )
            DiagState.FAIL -> {
                drawLine(color, Offset(w * 0.34f, w * 0.34f), Offset(w * 0.66f, w * 0.66f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.66f, w * 0.34f), Offset(w * 0.34f, w * 0.66f), stroke.width, StrokeCap.Round)
            }
            DiagState.WARN -> {
                drawLine(color, Offset(w * 0.5f, w * 0.28f), Offset(w * 0.5f, w * 0.56f), stroke.width, StrokeCap.Round)
                drawCircle(color, radius = stroke.width * 0.6f, center = Offset(w * 0.5f, w * 0.72f))
            }
            DiagState.SKIPPED -> drawLine(color, Offset(w * 0.34f, w * 0.5f), Offset(w * 0.66f, w * 0.5f), stroke.width, StrokeCap.Round)
            else -> {}
        }
    }
}

@Composable
private fun stateColor(state: DiagState): Color = when (state) {
    DiagState.OK -> Color(0xFF3FA66B)
    DiagState.FAIL -> IykykColors.Error
    DiagState.WARN -> IykykColors.Warning
    else -> IykykColors.Muted
}

@Composable
private fun fixText(d: Diagnosis): String = when (d.fix) {
    DiagFix.NONE -> stringResource(Res.string.diag_fix_none)
    DiagFix.NO_INTERNET -> stringResource(Res.string.diag_fix_no_internet)
    DiagFix.DEVICE_LIMIT -> stringResource(Res.string.diag_fix_device_limit)
    DiagFix.RENEW -> stringResource(Res.string.diag_fix_renew)
    DiagFix.SWITCH_SERVER -> stringResource(Res.string.diag_fix_switch_server, d.serverName)
    DiagFix.SERVERS_DOWN -> stringResource(Res.string.diag_fix_servers_down)
    DiagFix.ENABLE_FRAGMENT -> stringResource(Res.string.diag_fix_fragment)
    DiagFix.SWITCH_ENGINE -> stringResource(Res.string.diag_fix_engine)
    DiagFix.CONNECT -> stringResource(Res.string.diag_fix_connect)
    DiagFix.RECONNECT -> stringResource(Res.string.diag_fix_reconnect)
}

@Composable
private fun fixButton(fix: DiagFix): String? = when (fix) {
    DiagFix.RENEW -> stringResource(Res.string.action_renew)
    DiagFix.SWITCH_SERVER -> stringResource(Res.string.diag_button_switch)
    DiagFix.ENABLE_FRAGMENT, DiagFix.SWITCH_ENGINE -> stringResource(Res.string.diag_button_apply)
    DiagFix.CONNECT -> stringResource(Res.string.diag_button_connect)
    DiagFix.RECONNECT -> stringResource(Res.string.diag_button_reconnect)
    else -> null
}
