package com.facelapse.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.facelapse.app.R
import kotlin.math.roundToInt
import java.text.DecimalFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onBackClick: () -> Unit
) {
    val isDateOverlayEnabled by viewModel.isDateOverlayEnabled.collectAsState(initial = true)
    val showDayOfWeek by viewModel.showDayOfWeek.collectAsState(initial = false)
    val defaultFps by viewModel.defaultFps.collectAsState(initial = 10f)
    val defaultExportGif by viewModel.defaultExportGif.collectAsState(initial = false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "New Project Defaults",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            SettingSwitchItem(
                title = "Enable Date Overlay",
                checked = isDateOverlayEnabled,
                onCheckedChange = { viewModel.setDateOverlayEnabled(it) }
            )
            SettingSwitchItem(
                title = "Show Day of Week",
                checked = showDayOfWeek,
                onCheckedChange = { viewModel.setShowDayOfWeek(it) }
            )
            SettingSliderItem(
                title = stringResource(R.string.settings_default_fps_format, remember { DecimalFormat("#.##") }.format(defaultFps)),
                value = defaultFps,
                valueRange = 0.25f..10f,
                steps = 38,
                onValueChangeFinished = { viewModel.setDefaultFps(it) }
            )
            SettingSwitchItem(
                title = stringResource(R.string.settings_default_export_format),
                subtitle = if (defaultExportGif) stringResource(R.string.settings_format_gif) else stringResource(R.string.settings_format_video),
                checked = defaultExportGif,
                onCheckedChange = { viewModel.setDefaultExportGif(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Apply Defaults",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Apply these default settings (FPS, Date Overlay, Format) to all existing projects.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { viewModel.applyDefaultsToAllProjects() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Apply to All Existing Projects")
            }
        }
    }
}

@Composable
fun SettingSwitchItem(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun SettingSliderItem(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChangeFinished: (Float) -> Unit
) {
    var sliderPosition by remember(value) { mutableStateOf(value) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge
        )
        Slider(
            value = sliderPosition,
            onValueChange = { sliderPosition = it },
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = { onValueChangeFinished(sliderPosition) }
        )
    }
}
