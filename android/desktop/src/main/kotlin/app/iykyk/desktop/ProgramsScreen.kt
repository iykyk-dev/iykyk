package app.iykyk.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.iykyk.client.ui.IykykColors
import app.iykyk.client.ui.TopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** A program to pick: its file name (what the core matches) and where it lives. */
private data class Program(val name: String, val path: String)

/**
 * "Which programs go through the VPN" on Windows: the running programs with a
 * window-level presence (not system services), plus any .exe chosen by hand.
 * Programs are matched by file name, e.g. "Telegram.exe".
 */
@Composable
fun ProgramsScreen(selected: Set<String>, onToggle: (String) -> Unit, onBack: () -> Unit) {
    var programs by remember { mutableStateOf<List<Program>>(emptyList()) }
    LaunchedEffect(Unit) { programs = withContext(Dispatchers.IO) { runningPrograms() } }
    // Chosen programs that are not running now still appear, at the top.
    val shown = remember(programs, selected) {
        val running = programs.map { it.name.lowercase() }.toSet()
        selected.filter { it.lowercase() !in running }.map { Program(it, "") } + programs
    }

    Surface(color = IykykColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            TopBar("Программы", onBack)
            Text(
                "Отмеченные программы идут через VPN или мимо него, в зависимости от режима в настройках. " +
                    "Нужной программы нет в списке? Запустите её или добавьте файл .exe.",
                color = IykykColors.Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            OutlinedButton(
                onClick = { pickExe()?.let(onToggle) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Добавить программу…", color = IykykColors.Text) }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                items(shown, key = { it.name.lowercase() + it.path }) { program ->
                    val checked = selected.any { it.equals(program.name, ignoreCase = true) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(program.name) }
                            .padding(vertical = 4.dp),
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { onToggle(program.name) },
                            colors = CheckboxDefaults.colors(checkedColor = IykykColors.Accent),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(program.name, color = IykykColors.Text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (program.path.isNotEmpty()) {
                                Text(program.path, color = IykykColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Programs running for this user, without Windows' own processes. */
private fun runningPrograms(): List<Program> {
    val windows = (System.getenv("SystemRoot") ?: "C:\\Windows").lowercase()
    val self = ProcessHandle.current().info().command().orElse("")
    return ProcessHandle.allProcesses()
        .toList()
        .mapNotNull { it.info().command().orElse(null) }
        .filter { it.endsWith(".exe", ignoreCase = true) && !it.lowercase().startsWith(windows) && it != self }
        .distinctBy { File(it).name.lowercase() }
        .map { Program(File(it).name, it) }
        .filterNot { it.name.equals("iykyk-service.exe", ignoreCase = true) || it.name.equals("IYKYK.exe", ignoreCase = true) }
        .sortedBy { it.name.lowercase() }
}

private fun pickExe(): String? {
    val dialog = FileDialog(null as Frame?, "Выберите программу", FileDialog.LOAD).apply {
        setFilenameFilter { _, name -> name.endsWith(".exe", ignoreCase = true) }
        file = "*.exe"
        isVisible = true
    }
    return dialog.file?.takeIf { it.endsWith(".exe", ignoreCase = true) }
}
