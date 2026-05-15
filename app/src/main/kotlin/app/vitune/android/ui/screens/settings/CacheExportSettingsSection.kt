package app.vitune.android.ui.screens.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import app.vitune.android.LocalPlayerServiceBinder
import app.vitune.android.preferences.DataPreferences
import app.vitune.android.service.CacheExportManager
import kotlinx.coroutines.launch

/**
 * CacheExportSettingsSection
 *
 * Drop this composable into your existing Settings screen.
 * In ViTune the settings screen is typically:
 *   app/src/main/kotlin/app/vitune/android/ui/screens/settings/OtherSettings.kt
 *   (or DataSettings.kt / CacheSettings.kt depending on your version)
 *
 * Find a good section (e.g. after the existing cache size settings) and call:
 *   CacheExportSettingsSection()
 */
@Composable
fun CacheExportSettingsSection() {
    val context = LocalContext.current
    val binder = LocalPlayerServiceBinder.current
    val scope = rememberCoroutineScope()

    var exportEnabled by remember { mutableStateOf(DataPreferences.cacheExportEnabled) }
    var exportPath by remember { mutableStateOf(DataPreferences.cacheExportPath) }
    var intervalMs by remember { mutableStateOf(DataPreferences.cacheExportIntervalMs) }
    var lastExportResult by remember { mutableStateOf<String?>(null) }
    var isExporting by remember { mutableStateOf(false) }

    // Folder picker launcher (uses SAF — works without root on all Android versions)
    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // Persist permission so we keep access across reboots
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            // Convert content URI to a readable path label for display
            val docFile = DocumentFile.fromTreeUri(context, uri)
            exportPath = uri.toString()
            DataPreferences.cacheExportPath = uri.toString()
        }
    }

    // Section header
    SettingsSectionHeader(title = "Cache Export")

    // Enable toggle
    SettingsToggle(
        title = "Auto-export cached songs",
        subtitle = "Automatically copy 100% cached songs to your Music folder",
        checked = exportEnabled,
        onCheckedChange = {
            exportEnabled = it
            DataPreferences.cacheExportEnabled = it
            val cache = binder?.cache
            if (it && cache != null) {
                CacheExportManager.start(context, cache)
            } else {
                CacheExportManager.stop()
            }
        }
    )

    if (exportEnabled) {
        Spacer(modifier = Modifier.height(8.dp))

        // Export folder picker
        SettingsEntry(
            title = "Export folder",
            subtitle = if (exportPath.isBlank()) "Music/ViTune/ (default)"
                       else exportPath.toDisplayPath(context),
            onClick = { folderPickerLauncher.launch(null) }
        )

        // Interval selector
        SettingsDropdown(
            title = "Check interval",
            subtitle = "How often to scan for newly cached songs",
            options = listOf(
                60_000L to "Every minute",
                300_000L to "Every 5 minutes",
                600_000L to "Every 10 minutes",
                1_800_000L to "Every 30 minutes",
                3_600_000L to "Every hour"
            ),
            selected = intervalMs,
            onSelected = {
                intervalMs = it
                DataPreferences.cacheExportIntervalMs = it
            }
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Export Now button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    val cache = binder?.cache ?: return@Button
                    isExporting = true
                    lastExportResult = null
                    CacheExportManager.exportNow(context, cache) { count ->
                        isExporting = false
                        lastExportResult = if (count > 0) "✓ Exported $count new song(s)"
                                           else "✓ No new songs to export"
                    }
                },
                enabled = !isExporting,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isExporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Exporting...")
                } else {
                    Text("Export now")
                }
            }
        }

        // Result message
        lastExportResult?.let { result ->
            Text(
                text = result,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Info note
        Text(
            text = "ℹ Songs are copied, not moved. ViTune still plays from cache. " +
                   "Your Music app will see the exported files.",
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(10.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp
        )
    }
}

// Helper: convert SAF URI to a human-readable path
private fun String.toDisplayPath(context: android.content.Context): String {
    return try {
        val uri = Uri.parse(this)
        DocumentFile.fromTreeUri(context, uri)?.name ?: this
    } catch (e: Exception) {
        this
    }
}

// ── Reusable setting composables ─────────────────────────────────────────────
// (ViTune already has these — replace calls below with ViTune's own versions)

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsEntry(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun <T> SettingsDropdown(
    title: String,
    subtitle: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.first == selected }?.second ?: selected.toString()

    Surface(modifier = Modifier.fillMaxWidth(), onClick = { expanded = true }) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(selectedLabel, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelected(value)
                        expanded = false
                    }
                )
            }
        }
    }
}
