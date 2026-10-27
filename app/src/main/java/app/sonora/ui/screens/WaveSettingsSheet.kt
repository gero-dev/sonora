package app.sonora.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sonora.core.settings.WaveSettings
import kotlin.math.roundToInt

/** Границы и шаг дискретного выбора длительности треков, секунды. */
private const val DURATION_MIN_SEC = 15
private const val DURATION_MAX_SEC = 1800
private const val DURATION_STEP_SEC = 15

/** Промежуточных значений между 15 с и 1800 с при шаге 15 с — 118. */
private const val DURATION_SLIDER_STEPS =
    (DURATION_MAX_SEC - DURATION_MIN_SEC) / DURATION_STEP_SEC - 1

/**
 * Нижний лист тонкой настройки «Моей волны»: фильтры (только музыка, видео),
 * что учитывать (избранное, история) и баланс подборки (новизна, разнообразие
 * артистов, доля устройства, длительность).
 *
 * Лист работает с черновиком [WaveSettings]: любые правки видны сразу в UI, но
 * сохраняются только по кнопке «Применить» ([onApply]). «Сбросить» возвращает
 * настройки к значениям [WaveSettings] по умолчанию. Очистка истории — отдельное
 * немедленное действие ([onClearHistory]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaveSettingsSheet(
    settings: WaveSettings,
    historySize: Int,
    onDismiss: () -> Unit,
    onApply: (WaveSettings) -> Unit,
    onClearHistory: () -> Unit,
) {
    // Черновик: правки применяются только по кнопке «Применить».
    var draft by remember(settings) { mutableStateOf(settings) }
    val listened = historySize.coerceAtLeast(0)

    // Диапазон длительности держим в локальном состоянии с шагом 15 с: иначе
    // при пересчёте из целых минут ползунок «дёргался» бы назад при каждом движении.
    var durationStartSec by remember(settings) {
        mutableStateOf(snapDurationSec(settings.minDurationSec))
    }
    var durationEndSec by remember(settings) {
        mutableStateOf(
            snapDurationSec(settings.maxDurationMin * 60)
                .coerceAtLeast(
                    (snapDurationSec(settings.minDurationSec) + DURATION_STEP_SEC)
                        .coerceAtMost(DURATION_MAX_SEC),
                ),
        )
    }

    val novelty = draft.novelty.coerceIn(0, 100)
    val diversity = draft.artistDiversity.coerceIn(1, 5)
    val localShare = draft.localShare.coerceIn(0, 100)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            // Заголовок и описание зафиксированы: листать нужно только настройки.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                Text(
                    text = "Настройки «Моей волны»",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Чем точнее настройки, тем ближе подборка к вашему вкусу",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                SheetSectionLabel("Фильтры")
                SettingsSheetSwitch(
                    title = "Только музыка",
                    subtitle = "Без клипов, концертов, интервью и подкастов",
                    checked = draft.musicOnly,
                    onCheckedChange = { draft = draft.copy(musicOnly = it) },
                )
                SettingsSheetSwitch(
                    title = "Скрывать видеоклипы",
                    subtitle = if (draft.musicOnly) {
                        "При включённом «Только музыка» видео и так скрыты"
                    } else {
                        "Отсекать клипы, живые записи и видеообзоры"
                    },
                    checked = draft.excludeVideos,
                    enabled = !draft.musicOnly,
                    onCheckedChange = { draft = draft.copy(excludeVideos = it) },
                )

                SheetDivider()

                SheetSectionLabel("Что учитывать")
                SettingsSheetSwitch(
                    title = "Учитывать избранное",
                    subtitle = "Любимые треки получают больший вес в подборке",
                    checked = draft.includeFavorites,
                    onCheckedChange = { draft = draft.copy(includeFavorites = it) },
                )
                SettingsSheetSwitch(
                    title = "Учитывать историю прослушиваний",
                    subtitle = historyLabel(listened),
                    checked = draft.useHistory,
                    onCheckedChange = { draft = draft.copy(useHistory = it) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Дослушивания и пропуски влияют на подбор",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClearHistory) {
                        Text("Очистить историю", style = MaterialTheme.typography.labelLarge)
                    }
                }

                SheetDivider()

                SheetSectionLabel("Баланс подборки")
                LabeledSlider(
                    title = "Новизна",
                    valueText = "$novelty",
                    value = novelty.toFloat(),
                    valueRange = 0f..100f,
                    steps = 9,
                    hint = "знакомое ↔ новое",
                    onValueChange = {
                        draft = draft.copy(novelty = it.roundToInt().coerceIn(0, 100))
                    },
                )
                LabeledSlider(
                    title = "Один артист не чаще",
                    valueText = trackCountLabel(diversity),
                    value = diversity.toFloat(),
                    valueRange = 1f..5f,
                    steps = 3,
                    hint = "Чем меньше, тем разнообразнее начало волны",
                    onValueChange = {
                        draft = draft.copy(artistDiversity = it.roundToInt().coerceIn(1, 5))
                    },
                )
                LabeledSlider(
                    title = "Доля устройства",
                    valueText = "$localShare%",
                    value = localShare.toFloat(),
                    valueRange = 0f..100f,
                    steps = 9,
                    hint = "Сколько локальных треков подмешивать в онлайн-волну",
                    onValueChange = {
                        draft = draft.copy(localShare = it.roundToInt().coerceIn(0, 100))
                    },
                )

                SheetDivider()

                SheetSectionLabel("Длительность треков")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Длительность треков",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = durationRangeValue(durationStartSec, durationEndSec),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                RangeSlider(
                    value = durationStartSec.toFloat()..durationEndSec.toFloat(),
                    onValueChange = { range ->
                        val start = range.start.roundToInt()
                            .coerceIn(DURATION_MIN_SEC, DURATION_MAX_SEC)
                        val end = range.endInclusive.roundToInt()
                            .coerceIn(DURATION_MIN_SEC, DURATION_MAX_SEC)
                        durationStartSec = start
                        durationEndSec = end
                        draft = draft.copy(
                            minDurationSec = start,
                            maxDurationMin = (end / 60f).roundToInt().coerceAtLeast(1),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    valueRange = DURATION_MIN_SEC.toFloat()..DURATION_MAX_SEC.toFloat(),
                    steps = DURATION_SLIDER_STEPS,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = durationRangeCaption(durationStartSec, durationEndSec),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "15 с – 30 мин",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // Кнопки внизу листа — всегда под рукой, даже при прокрутке настроек.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val defaults = WaveSettings()
                        draft = defaults
                        durationStartSec = snapDurationSec(defaults.minDurationSec)
                        durationEndSec = snapDurationSec(defaults.maxDurationMin * 60)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Сбросить")
                }
                Button(
                    onClick = {
                        onApply(draft)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Применить")
                }
            }
        }
    }
}

/** Мелкий заголовок группы настроек внутри листа. */
@Composable
private fun SheetSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

/** Тонкий разделитель между группами настроек. */
@Composable
private fun SheetDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 10.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** Строка с переключателем: заголовок, пояснение и Switch (может быть недоступен). */
@Composable
private fun SettingsSheetSwitch(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.6f),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (enabled) 1f else 0.6f,
                    ),
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/** Заголовок со значением и дискретным ползунком; [hint] — пояснение под ним. */
@Composable
private fun LabeledSlider(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    hint: String? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Приводит сохранённые секунды к сетке слайдера (кратно 15 с). */
private fun snapDurationSec(value: Int): Int =
    ((value.coerceIn(DURATION_MIN_SEC, DURATION_MAX_SEC) / DURATION_STEP_SEC.toFloat())
        .roundToInt() * DURATION_STEP_SEC)
        .coerceIn(DURATION_MIN_SEC, DURATION_MAX_SEC)

/**
 * Подпись одного значения длительности: меньше минуты — секунды («45 с»),
 * иначе целые минуты («12 мин»). Дробные минуты не показываем.
 */
private fun durationLabel(seconds: Int): String =
    if (seconds < 60) {
        "$seconds с"
    } else {
        "${minutesValue(seconds)} мин"
    }

/**
 * Значение в строке диапазона: «45 с – 12 мин», когда нижняя граница меньше
 * минуты, иначе — по-старому «1–12 мин» (минуты без повтора единицы).
 */
private fun durationRangeValue(startSec: Int, endSec: Int): String =
    if (startSec < 60) {
        "${durationLabel(startSec)} – ${durationLabel(endSec)}"
    } else {
        "${minutesValue(startSec)}–${minutesValue(endSec)} мин"
    }

/**
 * Подпись под слайдером: «от 45 с до 12 мин», когда нижняя граница меньше
 * минуты, иначе — по-старому «от 1 до 12 мин».
 */
private fun durationRangeCaption(startSec: Int, endSec: Int): String =
    if (startSec < 60) {
        "от ${durationLabel(startSec)} до ${durationLabel(endSec)}"
    } else {
        "от ${minutesValue(startSec)} до ${minutesValue(endSec)} мин"
    }

/** Округлённые минуты для подписи; минимум одна минута. */
private fun minutesValue(seconds: Int): Int =
    (seconds / 60f).roundToInt().coerceAtLeast(1)

/** «Учтено N прослушиваний» с правильной формой слова; для нуля — «Пока нет прослушиваний». */
private fun historyLabel(count: Int): String {
    if (count <= 0) return "Пока нет прослушиваний"
    return "Учтено $count ${plural(count, "прослушивание", "прослушивания", "прослушиваний")}"
}

/** Подпись количества треков для слайдера разнообразия артистов. */
private fun trackCountLabel(count: Int): String =
    "$count ${plural(count, "трек", "трека", "треков")}"

/** Русская форма множественного числа: 1 трек, 2 трека, 5 треков. */
private fun plural(count: Int, one: String, few: String, many: String): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}
