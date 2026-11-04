package app.iykyk.client.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.iykyk.client.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class InstalledApp(val packageName: String, val label: String, val icon: ImageBitmap?)

/** Apps with a launcher icon (needs no special permission thanks to <queries> in the manifest). */
private fun loadApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != context.packageName }
        .map { info ->
            InstalledApp(
                packageName = info.packageName,
                label = info.loadLabel(pm).toString(),
                icon = runCatching { info.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull(),
            )
        }
        .sortedBy { it.label.lowercase() }
}

@Composable
fun AppPickerScreen(
    selected: Set<String>,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(context) }
    }

    Surface(color = IykykColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            TopBar(stringResource(R.string.apps_choose), onBack)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.apps_search)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = IykykColors.Accent,
                    focusedTextColor = IykykColors.Text,
                    unfocusedTextColor = IykykColors.Text,
                    cursorColor = IykykColors.Accent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )
            val list = apps
            if (list == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = IykykColors.Accent)
                }
                return@Column
            }
            // Selected apps first, then the rest, both filtered by the search text.
            val shown = list
                .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
                .sortedByDescending { it.packageName in selected }
            LazyColumn {
                items(shown, key = { it.packageName }) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(app.packageName) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (app.icon != null) {
                            Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(36.dp))
                        } else {
                            Spacer(Modifier.size(36.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.label, color = IykykColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                app.packageName,
                                color = IykykColors.Muted,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Checkbox(
                            checked = app.packageName in selected,
                            onCheckedChange = { onToggle(app.packageName) },
                            colors = CheckboxDefaults.colors(checkedColor = IykykColors.Accent),
                        )
                    }
                }
            }
        }
    }
}
