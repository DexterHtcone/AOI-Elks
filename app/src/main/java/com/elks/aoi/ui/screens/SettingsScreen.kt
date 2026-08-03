package com.elks.aoi.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elks.aoi.settings.AppSettings
import com.elks.aoi.settings.DiffMetric
import com.elks.aoi.settings.WorkResolution

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onScaleCalibration: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionTitle("Камера и интерфейс")

            SwitchRow("Авто-фонарик", "Включать вспышку при инспекции", settings.autoTorch) {
                settings.setAutoTorch(it)
            }
            SwitchRow("Автосъёмка", "Снимать при стабильном кадре", settings.autoCapture) {
                settings.setAutoCapture(it)
            }
            SwitchRow("Звуки", "Сигнал брака / повтор", settings.soundEnabled) {
                settings.setSoundEnabled(it)
            }
            SwitchRow("Графические подсказки", "Рамка и стрелки позиционирования", settings.guidanceOverlay) {
                settings.setGuidanceOverlay(it)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionTitle("Масштаб (мм/px)")

            val scaleLabel = if (settings.mmPerPixel > 0f)
                String.format("%.5f мм/px", settings.mmPerPixel)
            else
                "не задан"
            Text(
                "Текущий: $scaleLabel",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            OutlinedButton(
                onClick = onScaleCalibration,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Straighten, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Калибровка по линейке")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionTitle("Детекция")

            Text("Разрешение анализа", fontWeight = FontWeight.Medium)
            Text(
                "Больше px → чувствительнее к мелким компонентам, медленнее",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            WorkResolution.entries.forEach { res ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = settings.workResolution == res,
                        onClick = { settings.setWorkResolution(res) }
                    )
                    Text(res.label, modifier = Modifier.clickableLabel { settings.setWorkResolution(res) })
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text("Метрика сравнения", fontWeight = FontWeight.Medium)
            MetricRadio(DiffMetric.MEAN, "Mean absdiff (быстро)", settings)
            MetricRadio(DiffMetric.ZNCC, "ZNCC (устойчивее к свету)", settings)
            MetricRadio(DiffMetric.PIXEL_RATIO, "Доля «горячих» пикселей", settings)

            Spacer(modifier = Modifier.height(8.dp))
            SliderRow(
                title = "Порог чувствительности",
                value = settings.threshold,
                valueLabel = String.format("%.2f", settings.threshold),
                range = 0.05f..0.50f,
                steps = 17
            ) { settings.setThreshold(it) }

            SliderRow(
                title = "Сетка по X",
                value = settings.gridX.toFloat(),
                valueLabel = settings.gridX.toString(),
                range = 4f..24f,
                steps = 19
            ) { settings.setGridX(it.toInt()) }

            SliderRow(
                title = "Сетка по Y",
                value = settings.gridY.toFloat(),
                valueLabel = settings.gridY.toString(),
                range = 4f..20f,
                steps = 15
            ) { settings.setGridY(it.toInt()) }

            SwitchRow("CLAHE (контраст)", "Выравнивание локального контраста", settings.useClahe) {
                settings.setUseClahe(it)
            }
            SwitchRow(
                "Геометрическая маска",
                "Не отсекать тёмные корпуса по яркости",
                settings.useGeometricMask
            ) { settings.setUseGeometricMask(it) }
            SwitchRow(
                "Показывать все зоны",
                "Не ограничивать число красных квадратов",
                settings.showAllZones
            ) { settings.setShowAllZones(it) }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionTitle("Выравнивание (ORB)")

            SliderRow(
                title = "Мин. совпадений",
                value = settings.minMatches.toFloat(),
                valueLabel = settings.minMatches.toString(),
                range = 5f..40f,
                steps = 34
            ) { settings.setMinMatches(it.toInt()) }

            SliderRow(
                title = "Мин. inliers RANSAC",
                value = settings.minInliers.toFloat(),
                valueLabel = settings.minInliers.toString(),
                range = 8f..60f,
                steps = 51
            ) { settings.setMinInliers(it.toInt()) }

            SliderRow(
                title = "Макс. доля «брака» (иначе недостоверно)",
                value = settings.maxDefectFraction,
                valueLabel = String.format("%.0f%%", settings.maxDefectFraction * 100),
                range = 0.15f..0.70f,
                steps = 10
            ) { settings.setMaxDefectFraction(it) }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionTitle("Хранение")

            SwitchRow(
                "Эталон в PNG",
                "Без JPEG-артефактов на границах компонентов",
                settings.savePng
            ) { settings.setSavePng(it) }

            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = { settings.resetDefaults() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Сбросить настройки по умолчанию")
            }

            Text(
                "Изменения применяются сразу. Для мелких компонентов увеличьте разрешение и сетку, метрику — ZNCC или PIXEL_RATIO.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(valueLabel, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps
        )
    }
}

@Composable
private fun MetricRadio(m: DiffMetric, label: String, settings: AppSettings) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(
            selected = settings.metric == m,
            onClick = { settings.setMetric(m) }
        )
        Text(label, modifier = Modifier.clickableLabel { settings.setMetric(m) })
    }
}

private fun Modifier.clickableLabel(onClick: () -> Unit): Modifier =
    this
        .padding(vertical = 4.dp)
        .clickable(onClick = onClick)
