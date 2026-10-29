package app.sonora.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sonora.core.model.Source
import app.sonora.core.settings.Accent
import app.sonora.core.settings.AppSettings
import app.sonora.core.settings.AudioQuality
import app.sonora.core.settings.PlaybackMode
import app.sonora.core.settings.StreamSource
import app.sonora.core.settings.ThemeMode
import app.sonora.core.settings.YoutubeIpMode
import app.sonora.net.warp.WarpState
import app.sonora.ui.MainViewModel
import app.sonora.ui.nav.Routes
import app.sonora.ui.theme.paletteFor
import java.util.Locale

private val SPEED_PRESETS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

private const val LEGAL_TEXT =
    "Sonora работает через официальные API сервисов и InnerTube — тот же открытый протокол " +
        "YouTube Music, что использует Metrolist.\n\n" +
        "• YouTube Music: поиск, плейлисты, очередь, тексты и аудиопоток идут через InnerTube " +
        "без API-ключей. Можно войти в Google и скачать трек в приватную папку приложения " +
        "для личного офлайна; файлы не публикуются.\n" +
        "• Spotify: Web API отдаёт метаданные и 30-секундные превью. Полные треки " +
        "воспроизводятся только в приложении Spotify — Sonora открывает их там.\n" +
        "• SoundCloud: поиск и метаданные по официальному client_id, воспроизведение " +
        "разрешённых треков и переход в приложение SoundCloud.\n" +
        "• Обход DRM не поддерживается. Скачивание с YouTube нарушает условия сервиса — " +
        "ответственность за использование лежит на пользователе.\n" +
        "• Локальные файлы (свои записи, покупки с правом скачивания) проигрываются " +
        "в приложении без ограничений."

/**
 * Настройки: оформление, аудио, библиотека, аккаунты и правовая информация.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, padding: PaddingValues, navigate: (String) -> Unit) {
    val settings by vm.settings.collectAsState()
    val waveHistorySize by vm.waveHistorySize.collectAsState()
    val waveDisliked by vm.waveDisliked.collectAsState()
    val connections by vm.connections.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val enriching by vm.enriching.collectAsState()
    val enrichProgress by vm.enrichProgress.collectAsState()
    val google by vm.googleAccount.collectAsState()

    var legalExpanded by rememberSaveable { mutableStateOf(false) }
    var showPlaylistImportHelp by rememberSaveable { mutableStateOf(false) }
    var showWaveSettings by remember { mutableStateOf(false) }
    var showProxyDialog by remember { mutableStateOf(false) }
    var showRelayServersDialog by remember { mutableStateOf(false) }
    var showStreamDetails by remember { mutableStateOf(false) }
    var showAutoPilotDialog by remember { mutableStateOf(false) }
    var jamendoClientId by rememberSaveable(settings.jamendoClientId) {
        mutableStateOf(settings.jamendoClientId)
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.importFile(uri)
    }
    val takeout by vm.takeout.collectAsState()
    val metadataCacheSize by vm.metadataCacheSize.collectAsState()
    val warpStatus by vm.warpStatus.collectAsState()
    val warpConfigs by vm.warpConfigs.collectAsState()
    val warpAutoRotate by vm.warpAutoRotate.collectAsState()
    val warpExternalVpn by vm.warpExternalVpn.collectAsState()
    val warpKeepAlive by vm.warpKeepAlive.collectAsState()
    val warpImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) vm.importWarpConfigs(uris)
    }
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        vm.startWarp()
        // После выдачи разрешения сразу проверяем, помогает ли WARP стримингу.
        vm.runStreamingAudit(deep = true)
    }
    LaunchedEffect(Unit) { vm.refreshWarp() }
    // Версия yt-dlp нужна в карточке «Поток и сеть».
    LaunchedEffect(Unit) { vm.refreshYtDlpStatus() }
    val streamCheckDetails by vm.streamCheckDetails.collectAsState()
    val relayCheckState by vm.relayCheck.collectAsState()
    val ytDlpStatusState by vm.ytDlpStatus.collectAsState()
    val relayHealthState by vm.relayHealth.collectAsState()
    val autoPilotAuditState by vm.autoPilotAudit.collectAsState()
    val autoPilotRunningState by vm.autoPilotRunning.collectAsState()
    val autoPilotMessageState by vm.autoPilotMessage.collectAsState()
    val nohitTuningState by vm.nohitTuning.collectAsState()
    val nohitMessageState by vm.nohitMessage.collectAsState()
    val nohitConfigLabelState = vm.nohitConfigLabel()
    val updateAvailableState by vm.updateAvailable.collectAsState()
    val updateCheckingState by vm.updateChecking.collectAsState()
    val updateDownloadingState by vm.updateDownloading.collectAsState()
    val updateProgressState by vm.updateProgress.collectAsState()
    val updateErrorState by vm.updateError.collectAsState()
    var updateNotesExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val takeoutLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.importTakeout(uri)
    }
    val playlistFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.importPlaylistFile(uri)
    }
    val playlistImport by vm.playlistFileImport.collectAsState()
    val metadataImport by vm.metadataImport.collectAsState()
    val coverSearch by vm.coverSearch.collectAsState()
    val metadataLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) vm.importMetadataFiles(uris)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 20.dp,
                end = 20.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Внешний вид, звук, фонотека и подключённые сервисы.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))

        // ---------- Оформление ----------
        SettingsSection(title = "Оформление", icon = Icons.Rounded.Palette) {
            SettingsLabel("Тема оформления")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { vm.setThemeMode(mode) },
                        label = { Text(themeLabel(mode)) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            SettingsLabel("Акцентный цвет · ${settings.accent.label}")
            FlowRow(
                modifier = Modifier.padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 8,
            ) {
                Accent.entries.forEach { accent ->
                    AccentSwatch(
                        accent = accent,
                        selected = settings.accent == accent,
                        onClick = { vm.setAccent(accent) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            SwitchRow(
                title = "Динамический цвет",
                subtitle = "Android 12+: палитра обоев вместо акцентного цвета",
                checked = settings.dynamicColor,
                onCheckedChange = vm::setDynamicColor,
            )
        }

        // ---------- Руководство ----------
        SettingsSection(title = "Руководство", icon = Icons.Rounded.Info) {
            ActionRow(
                title = "Показать обучение заново",
                subtitle = "Где какие кнопки: вкладки, плеер, жесты, виджеты",
                onClick = { vm.replayOnboarding() },
            )
        }

        // ---------- Аудио ----------
        SettingsSection(title = "Аудио", icon = Icons.Rounded.GraphicEq) {
            SwitchRow(
                title = "Пропускать тишину",
                subtitle = "Убирать длинные паузы в записях",
                checked = settings.skipSilence,
                onCheckedChange = vm::setSkipSilence,
            )
            SwitchRow(
                title = "Бесшовное воспроизведение",
                subtitle = "Без паузы между треками альбома",
                checked = settings.gapless,
                onCheckedChange = vm::setGapless,
            )
            SwitchRow(
                title = "Эквалайзер",
                subtitle = "5 полос, от −12 до +12 дБ",
                checked = settings.eqEnabled,
                onCheckedChange = vm::setEqEnabled,
            )
            if (settings.eqEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    AppSettings.bandFrequencies.forEachIndexed { index, hz ->
                        EqBand(
                            hz = hz,
                            value = settings.eqBands.getOrElse(index) { 0f },
                            onValueChange = { gain -> vm.setEqBand(index, gain) },
                        )
                    }
                }
                TextButton(
                    onClick = { vm.setEqBands(List(AppSettings.BANDS_COUNT) { 0f }) },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Сбросить эквалайзер")
                }
            }
            Spacer(Modifier.height(6.dp))
            SettingsLabel("Качество аудио")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AudioQuality.values().size) { index ->
                    val quality = AudioQuality.values()[index]
                    FilterChip(
                        selected = settings.audioQuality == quality,
                        onClick = { vm.setAudioQuality(quality) },
                        label = { Text(quality.label) },
                    )
                }
            }
            Text(
                text = settings.audioQuality.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = "Влияет на онлайн-воспроизведение. Сохранённые файлы играются " +
                    "в исходном качестве.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (settings.audioQuality == AudioQuality.AUTO) {
                Spacer(Modifier.height(8.dp))
                SettingsLabel("Качество на мобильной сети")
                val mobileLevels = AudioQuality.values().filter { it != AudioQuality.AUTO }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(mobileLevels.size) { index ->
                        val quality = mobileLevels[index]
                        FilterChip(
                            selected = settings.mobileAudioQuality == quality,
                            onClick = { vm.setMobileAudioQuality(quality) },
                            label = { Text(quality.label) },
                        )
                    }
                }
                Text(
                    text = "Применяется, когда основное качество — «Авто»: Wi-Fi играет " +
                        "в высоком, сотовая сеть — с этой градацией.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            SettingsLabel("Скорость воспроизведения")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SPEED_PRESETS.size) { index ->
                    val speed = SPEED_PRESETS[index]
                    FilterChip(
                        selected = kotlin.math.abs(settings.playbackSpeed - speed) < 0.01f,
                        onClick = { vm.setSpeedPreset(speed) },
                        label = { Text(speedLabel(speed)) },
                    )
                }
            }
        }

        // ---------- Поток и сеть ----------
        SettingsSection(title = "Поток и сеть", icon = Icons.Rounded.Language) {
            SettingsLabel("Источник потока")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(StreamSource.values().size) { index ->
                    val source = StreamSource.values()[index]
                    FilterChip(
                        selected = settings.streamSource == source,
                        onClick = { vm.setStreamSource(source) },
                        label = { Text(source.label) },
                    )
                }
            }
            Text(
                text = settings.streamSource.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(4.dp))
            SwitchRow(
                title = "Медиашлюз без VPN",
                subtitle = "Если YouTube блокирует IP, звук идёт через публичные зеркала — " +
                    "без WARP и VPN",
                checked = settings.relayEnabled,
                onCheckedChange = vm::setRelayEnabled,
            )
            ClickRow(
                title = "Серверы шлюза",
                subtitle = settings.relayInstances.ifBlank {
                    "Встроенный список зеркал (Invidious/Piped)"
                },
                onClick = { showRelayServersDialog = true },
            )
            ClickRow(
                title = "Проверить шлюзы",
                subtitle = relayCheckState ?: "Какие зеркала реально отдают звук",
                onClick = { vm.checkRelayHealth() },
            )
            SettingsLabel("DPI-обход (NoHiT)")
            SwitchRow(
                title = "Обход блокировок NoHiT",
                subtitle = "Фрагментация TLS ClientHello + DoH: без VPN и WARP, только для трафика Sonora",
                checked = settings.nohitEnabled,
                onCheckedChange = vm::setNohitEnabled,
            )
            ClickRow(
                title = "Стратегия NoHiT",
                subtitle = when {
                    nohitTuningState -> nohitMessageState ?: "Подбираем стратегию…"
                    nohitMessageState != null -> nohitMessageState!!
                    else -> nohitConfigLabelState
                },
                onClick = { vm.tuneNohit() },
            )
            Text(
                text = "Автопилот больше не включает WARP: только ручная кнопка в разделе " +
                    "«Туннель WARP» ниже. Переключатель NoHiT полностью применяется " +
                    "после перезапуска приложения.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            SettingsLabel("Автопилот")
            SwitchRow(
                title = "Автопилот стриминга",
                subtitle = "Автоматически перебирает способы и стратегии без WARP",
                checked = settings.autoTransport,
                onCheckedChange = vm::setAutoTransport,
            )
            SwitchRow(
                title = "Автопереключение потока",
                subtitle = "При 403/ошибке плеер сам переходит на другой источник и продолжает трек",
                checked = settings.autoFailover,
                onCheckedChange = vm::setAutoFailover,
            )
            ClickRow(
                title = "Проверить все способы",
                subtitle = autoPilotMessageState ?: "Прямой YouTube · yt-dlp · медиашлюз · WARP",
                onClick = {
                    vm.runStreamingAudit(deep = true)
                    showAutoPilotDialog = true
                },
            )
            if (vm.autoPilotNeedsVpnPermission()) {
                ClickRow(
                    title = "Разрешить VPN для WARP",
                    subtitle = "Android спросит один раз; туннель работает только для трафика Sonora",
                    onClick = {
                        vm.autoPilotVpnPermissionIntent()?.let { vpnPermissionLauncher.launch(it) }
                    },
                )
            }
            SettingsLabel("Сеть YouTube")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(YoutubeIpMode.values().size) { index ->
                    val mode = YoutubeIpMode.values()[index]
                    FilterChip(
                        selected = settings.youtubeIpMode == mode,
                        onClick = { vm.setYoutubeIpMode(mode) },
                        label = { Text(mode.label) },
                    )
                }
            }
            Text(
                text = settings.youtubeIpMode.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(4.dp))
            SwitchRow(
                title = "Обновлять yt-dlp",
                subtitle = ytDlpStatusState ?: "Сборка yt-dlp устаревает за недели: обновление из GitHub",
                checked = settings.ytDlpAutoUpdate,
                onCheckedChange = vm::setYtDlpAutoUpdate,
            )
            ClickRow(
                title = "Обновить yt-dlp сейчас",
                subtitle = "Скачать свежий yt-dlp (STABLE) с GitHub",
                onClick = { vm.updateYtDlp() },
            )
            SwitchRow(
                title = "Проверять ссылку перед воспроизведением",
                subtitle = "Range-запрос: в плеер попадает только реально играющий поток",
                checked = settings.probeBeforePlay,
                onCheckedChange = vm::setProbeBeforePlay,
            )
            SwitchRow(
                title = "Аудио-фичи ReccoBeats",
                subtitle = "BPM и энергия для играющего трека (бесплатно, без ключей)",
                checked = settings.audioTelemetry,
                onCheckedChange = vm::setAudioTelemetry,
            )
            ClickRow(
                title = "Прокси для yt-dlp",
                subtitle = settings.ytDlpProxy.ifBlank { "Напрямую, без прокси" },
                onClick = { showProxyDialog = true },
            )
            val streamCheckState by vm.streamCheck.collectAsState()
            ClickRow(
                title = "Проверить поток",
                subtitle = streamCheckState ?: "Прямой YouTube · yt-dlp · медиашлюз",
                onClick = {
                    vm.checkStreamAccess()
                    showStreamDetails = true
                },
            )
            Text(
                text = "Прямой YouTube в РФ обычно отдаёт 403 на медиа. Порядок: InnerTube → " +
                    "yt-dlp (свежий рантайм, перебор клиентов и IPv4/IPv6) → медиашлюз без VPN. " +
                    "Если ничего не работает — добавьте своё зеркало или включите WARP.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---------- Воспроизведение ----------
        SettingsSection(title = "Воспроизведение", icon = Icons.Rounded.PlayCircle) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shuffle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when {
                            settings.playbackMode != PlaybackMode.STREAMING ->
                                "Только на устройстве"
                            settings.streamingInQueue -> "Стриминг включён"
                            else -> "Стриминг в очереди выключен"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "Переключается в плеере: долгое нажатие на кнопку перемешивания",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            SwitchRow(
                title = "Сохранять историю прослушиваний",
                subtitle = "Треки, исполнители и статистика — только на этом устройстве",
                checked = settings.saveHistory,
                onCheckedChange = vm::setSaveHistory,
            )
        }

        // ---------- Библиотека ----------
        SettingsSection(title = "Библиотека", icon = Icons.Rounded.LibraryMusic) {
            SwitchRow(
                title = "Сканировать при запуске",
                subtitle = "Искать новые файлы при каждом открытии приложения",
                checked = settings.scanOnStart,
                onCheckedChange = vm::setScanOnStart,
            )
            SwitchRow(
                title = "Спрашивать перед мобильной сетью",
                subtitle = "Показывать разрешение при сохранении без Wi-Fi",
                checked = settings.askMobileDownload,
                onCheckedChange = vm::setAskMobileDownload,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { vm.scanLibrary() },
                    enabled = !scanning,
                    modifier = Modifier.weight(1f),
                ) {
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("Пересканировать", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("audio/*")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.UploadFile,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Импортировать", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { takeoutLauncher.launch(arrayOf("*/*")) },
                enabled = !takeout.importing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (takeout.importing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.UploadFile,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (takeout.importing) {
                        "Импорт Takeout…"
                    } else {
                        "Импорт библиотеки из Google Takeout"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val takeoutSummary = buildList {
                if (takeout.importedTracks > 0) add("Треков: ${takeout.importedTracks}")
                if (takeout.importedPlaylists > 0) add("Плейлистов: ${takeout.importedPlaylists}")
                takeout.error?.let { add(it) }
                if (takeout.warnings.isNotEmpty()) add(takeout.warnings.first())
            }.joinToString(" · ")
            if (takeoutSummary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = takeoutSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (takeout.error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Кэш метаданных: $metadataCacheSize записей — хранятся на устройстве постоянно",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { vm.clearMetadataCache() },
                enabled = metadataCacheSize > 0,
            ) {
                Text("Очистить кэш метаданных")
            }
            Spacer(Modifier.height(4.dp))
            SwitchRow(
                title = "Искать метаданные и тексты онлайн",
                subtitle = "MusicBrainz, Cover Art Archive, iTunes, Deezer, TheAudioDB, " +
                    "Wikipedia и InnerTube: альбом, год, обложка, фото и биография автора, текст",
                checked = settings.enrichMetadataOnScan,
                onCheckedChange = vm::setEnrichOnScan,
            )
            SwitchRow(
                title = "Читать метаданные из файлов рядом с треками",
                subtitle = "JSON/NFO/LRC и cover.jpg в папке с музыкой",
                checked = settings.readSidecarMetadata,
                onCheckedChange = vm::setReadSidecarMetadata,
            )
            SwitchRow(
                title = "Брать обложки из аудиофайлов",
                subtitle = "Встроенные ID3/FLAC/MP4-обложки — офлайн и точнее",
                checked = settings.useEmbeddedArtwork,
                onCheckedChange = vm::setUseEmbeddedArtwork,
            )
            ActionRow(
                title = "Импортировать метаданные из файла",
                subtitle = "JSON/CSV/NFO: альбом, год, обложка, текст",
                onClick = { metadataLauncher.launch(arrayOf("*/*")) },
                enabled = !metadataImport.importing,
            )
            val importNote = metadataImport.message
            val metadataImportText = when {
                metadataImport.importing -> "Импорт…"
                metadataImport.files > 0 || metadataImport.matched > 0 ||
                    metadataImport.updated > 0 || metadataImport.failed > 0 -> buildString {
                    append("Совпало: ${metadataImport.matched}, обновлено: ${metadataImport.updated}")
                    if (metadataImport.failed > 0) {
                        append(" · ошибок: ${metadataImport.failed}")
                    }
                    if (!importNote.isNullOrBlank()) {
                        append(" · ")
                        append(importNote)
                    }
                }
                else -> importNote?.takeIf { it.isNotBlank() }
            }
            if (metadataImportText != null) {
                Text(
                    text = metadataImportText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (!metadataImport.importing && metadataImport.failed > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            val coverSearchSubtitle = when {
                coverSearch.searching -> "Ищу… ${coverSearch.processed} из ${coverSearch.total}"
                coverSearch.total > 0 ->
                    "Готово: найдено ${coverSearch.found} ${coverWord(coverSearch.found)}"
                else -> "Обложки есть не у всех локальных треков — попробовать найти"
            }
            ActionRow(
                title = "Найти пропущенные обложки",
                subtitle = coverSearchSubtitle,
                onClick = { vm.findMissingCovers() },
                enabled = !coverSearch.searching,
            )
            SwitchRow(
                title = "Автоматическая очередь",
                subtitle = "При запуске трека добавлять похожие: альбом → исполнитель → фонотека",
                checked = settings.autoQueue,
                onCheckedChange = vm::setAutoQueue,
            )
            SwitchRow(
                title = "Бесконечная очередь",
                subtitle = "Когда очередь заканчивается — подкидывать случайные треки",
                checked = settings.infiniteQueue,
                onCheckedChange = vm::setInfiniteQueue,
            )
            Spacer(Modifier.height(8.dp))
            val progress = enrichProgress
            if (enriching) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Обогащение… ${progress?.first ?: 0} / ${progress?.second ?: 0}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                OutlinedButton(
                    onClick = { vm.enrichLibrary() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Обогатить метаданные сейчас",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!enriching && progress != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (progress.second == 0) {
                        "Все треки уже заполнены"
                    } else {
                        "Готово: ${progress.first} из ${progress.second}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------- Рекомендации ----------
        SettingsSection(title = "Рекомендации", icon = Icons.Rounded.AutoAwesome) {
            ActionRow(
                title = "Настройки «Моей волны»",
                subtitle = "История, новизна, доля устройства и только музыка",
                onClick = { showWaveSettings = true },
            )
            SwitchRow(
                title = "Только музыка в волне",
                subtitle = "Без клипов, концертов и подкастов",
                checked = settings.wave.musicOnly,
                onCheckedChange = { vm.updateWaveSettings { w -> w.copy(musicOnly = it) } },
            )
        }

        // ---------- Импорт из других платформ ----------
        SettingsSection(title = "Импорт из других платформ", icon = Icons.Rounded.UploadFile) {
            Text(
                text = "Перенесите плейлист из Spotify, Apple Music, YouTube Music или " +
                    "любого сервиса, который умеет экспортировать список треков.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { playlistFileLauncher.launch(arrayOf("*/*")) },
                enabled = !playlistImport.importing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (playlistImport.importing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.UploadFile,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (playlistImport.importing) {
                        "Импорт: ${playlistImport.processed} из ${playlistImport.total}"
                    } else {
                        "Импортировать плейлист из файла"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { showPlaylistImportHelp = true }) {
                Text("Как это работает")
            }
            val summary = when {
                playlistImport.error != null -> playlistImport.error
                playlistImport.done && playlistImport.matched > 0 -> buildString {
                    append("Готово: ${playlistImport.matched} треков")
                    playlistImport.playlistName?.let { append(" → «$it»") }
                    if (playlistImport.notFound > 0) {
                        append(" · не найдено: ${playlistImport.notFound}")
                    }
                }

                playlistImport.done -> "Не удалось найти треки"
                playlistImport.importing -> "Ищем совпадения в библиотеке и YouTube…"
                else -> null
            }
            if (!summary.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (playlistImport.error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (playlistImport.warnings.isNotEmpty()) {
                Text(
                    text = playlistImport.warnings.first(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (playlistImport.done) {
                TextButton(onClick = vm::dismissPlaylistImport) {
                    Text("Скрыть")
                }
            }

            if (showPlaylistImportHelp) {
                AlertDialog(
                    onDismissRequest = { showPlaylistImportHelp = false },
                    title = { Text("Импорт из других платформ") },
                    text = {
                        Text(
                            "1. Экспортируйте плейлист в файл:\n" +
                                "• Spotify — Exportify или CSV-экспорт;\n" +
                                "• Apple Music — CSV/JSON-экспорт;\n" +
                                "• YouTube Music — CSV или Google Takeout;\n" +
                                "• вручную — TXT/M3U, по строке «Артист — Название».\n\n" +
                                "2. Нажмите «Импортировать плейлист из файла» и выберите файл.\n" +
                                "3. Sonora найдёт совпадения в вашей библиотеке и YouTube Music " +
                                "и создаст плейлист «Импорт: имя файла».\n\n" +
                                "Поддерживаются CSV/TSV, JSON, M3U/M3U8 и TXT. " +
                                "За один раз обрабатывается до 150 треков; поиск может занять время.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { showPlaylistImportHelp = false }) {
                            Text("Понятно")
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
            }
        }

        // ---------- Аккаунты ----------
        SettingsSection(title = "Аккаунты и сервисы", icon = Icons.Rounded.Cloud) {
            val connectedCount = connections.count { it.connected }
            val servicesCount = Source.entries.count { it.isRemote }
            ClickRow(
                title = "Подключённые сервисы",
                subtitle = "Подключено: $connectedCount из $servicesCount",
                onClick = { navigate(Routes.CONNECTIONS) },
            )
            ClickRow(
                title = "InnerTube и сервисы",
                subtitle = "YouTube Music без ключей · Spotify · SoundCloud",
                onClick = { navigate(Routes.CONNECTIONS) },
            )
            ClickRow(
                title = "Google-аккаунт",
                subtitle = if (google.connected) {
                    "Подключён: ${google.accountName ?: "Google-аккаунт"}"
                } else {
                    "Вход для плейлистов и «Понравившихся» YouTube Music"
                },
                onClick = { navigate(Routes.CONNECTIONS) },
            )
            ClickRow(
                title = "YouTube Music",
                subtitle = "Веб-плеер music.youtube.com: вход и личная библиотека",
                onClick = { navigate(Routes.YOUTUBE_WEB) },
            )
        }

        // ---------- Туннель WARP ----------
        SettingsSection(title = "Туннель WARP (только Sonora)", icon = Icons.Rounded.VpnKey) {
            Text(
                text = "Необязательный WireGuard-туннель Cloudflare WARP: через него идёт " +
                    "только трафик Sonora, остальные приложения ходят напрямую. Если один " +
                    "конфиг не подключается, приложение само пробует следующий.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            InfoRow(
                title = "Состояние",
                value = when (warpStatus.state) {
                    WarpState.NOT_CONFIGURED -> "Конфигурация не импортирована"
                    WarpState.DISCONNECTED -> "Выключен"
                    WarpState.CONNECTING -> "Подключение…"
                    WarpState.CONNECTED -> "Подключён"
                    WarpState.DISCONNECTING -> "Отключение…"
                    WarpState.ERROR -> "Ошибка"
                },
            )
            if (warpStatus.totalAttempts > 0) {
                InfoRow(
                    title = "Попытка",
                    value = "${warpStatus.attempt} из ${warpStatus.totalAttempts}" +
                        if (warpStatus.triedConfigIds.isNotEmpty()) {
                            " · использовано ${warpStatus.triedConfigIds.size}"
                        } else {
                            ""
                        },
                )
            }
            warpStatus.endpoint?.let { InfoRow(title = "Сервер", value = it) }
            if (warpExternalVpn) {
                Text(
                    text = "На устройстве уже активен другой VPN/WARP — туннель Sonora " +
                        "не включён, чтобы не мешать.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            vm.warpError.collectAsState().value?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            warpStatus.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(6.dp))
            ActionRow(
                title = "Восстановить встроенные конфиги",
                subtitle = "3 конфига уже в комплекте; импорт дополнительных — ниже",
                onClick = { vm.restoreBundledWarpConfigs() },
            )
            Button(
                onClick = { warpImportLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Импортировать .conf (можно несколько)")
            }
            if (warpConfigs.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SettingsLabel("Конфигурации · выбрана одна")
                warpConfigs.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { vm.selectWarpConfig(entry.id) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = entry.selected,
                            onClick = { vm.selectWarpConfig(entry.id) },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = entry.endpoint ?: "endpoint неизвестен",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { vm.removeWarpConfig(entry.id) }) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = "Удалить конфигурацию",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                val running = warpStatus.state == WarpState.CONNECTED ||
                    warpStatus.state == WarpState.CONNECTING
                SwitchRow(
                    title = "Включить туннель",
                    subtitle = "Только трафик приложения Sonora",
                    checked = running,
                    onCheckedChange = { enable ->
                        if (enable) {
                            if (warpExternalVpn) {
                                vm.startWarp()
                            } else {
                                val permission = vm.warpVpnPermissionIntent()
                                if (permission != null) {
                                    vpnPermissionLauncher.launch(permission)
                                } else {
                                    vm.startWarp()
                                }
                            }
                        } else {
                            vm.stopWarp()
                        }
                    },
                )
                SwitchRow(
                    title = "Автоматически перебирать конфиги",
                    subtitle = "Если конфиг не поднялся за 12 секунд — пробовать следующий (после ручного подключения)",
                    checked = warpAutoRotate,
                    onCheckedChange = { vm.setWarpAutoRotate(it) },
                )
                Text(
                    text = "WARP включается только этой кнопкой: автозапуска, автовосстановления " +
                        "и подъёма из автопилота больше нет.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                TextButton(onClick = { vm.clearWarpConfig() }) {
                    Text("Удалить все конфигурации")
                }
            }
        }

        // ---------- Открытые каталоги ----------
        SettingsSection(title = "Открытые каталоги", icon = Icons.Rounded.Public) {
            Text(
                text = "Internet Archive работает без ключа. Для Jamendo нужен бесплатный client_id.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = jamendoClientId,
                onValueChange = { jamendoClientId = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Jamendo client_id") },
                shape = RoundedCornerShape(16.dp),
                trailingIcon = {
                    TextButton(onClick = { vm.setJamendoClientId(jamendoClientId) }) {
                        Text("Сохранить")
                    }
                },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Бесплатный ключ: devportal.jamendo.com. Каталог Creative Commons.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---------- Правовая информация ----------
        SettingsSection(title = "Правовая информация", icon = Icons.Rounded.Info) {
            ClickRow(
                title = "Как Sonora работает с сервисами",
                subtitle = if (legalExpanded) "Свернуть" else "Показать подробности",
                onClick = { legalExpanded = !legalExpanded },
            )
            if (legalExpanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = LEGAL_TEXT,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------- Обновления ----------
        SettingsSection(title = "Обновления", icon = Icons.Rounded.SystemUpdate) {
            InfoRow(title = "Установлено", value = vm.appVersionLabel())
            SwitchRow(
                title = "Проверять обновления автоматически",
                subtitle = "Раз в сутки, без навязчивых напоминаний",
                checked = settings.updateAutoCheck,
                onCheckedChange = vm::setUpdateAutoCheck,
            )
            ClickRow(
                title = "Проверить обновления",
                subtitle = when {
                    updateCheckingState -> "Проверяем GitHub…"
                    updateAvailableState != null -> "Доступна ${updateAvailableState?.versionName}"
                    updateErrorState != null -> updateErrorState
                    else -> "У вас последняя версия"
                },
                onClick = { vm.checkForUpdates() },
            )
            val update = updateAvailableState
            if (update != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildString {
                        append("Sonora ${update.versionName}")
                        update.sizeMb?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (updateDownloadingState) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { updateProgressState },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Скачиваем… ${(updateProgressState * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { vm.cancelUpdateDownload() }) { Text("Отменить") }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            val intent = vm.updatePermissionIntent()
                            if (intent != null) {
                                context.startActivity(intent)
                            } else {
                                vm.downloadUpdate()
                            }
                        }) {
                            Text(
                                if (vm.updatePermissionIntent() != null) {
                                    "Разрешить установку"
                                } else {
                                    "Скачать и установить"
                                },
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                        TextButton(onClick = { vm.dismissUpdate() }) { Text("Позже") }
                    }
                }
                if (updateErrorState != null) {
                    Text(
                        text = updateErrorState.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (update.notes.isNotBlank()) {
                    TextButton(onClick = { updateNotesExpanded = !updateNotesExpanded }) {
                        Text(if (updateNotesExpanded) "Скрыть изменения" else "Что нового")
                    }
                    if (updateNotesExpanded) {
                        Text(
                            text = update.notes.take(2000),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (updateErrorState != null) {
                Text(
                    text = updateErrorState.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(onClick = { vm.openReleasePage() }) {
                Text("Открыть релизы на GitHub")
            }
        }

        // ---------- О приложении ----------
        SettingsSection(title = "О приложении", icon = Icons.Rounded.Info) {
            InfoRow(title = "Версия", value = vm.appVersionLabel())
            InfoRow(title = "Стек", value = "Kotlin · Compose · Media3")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Sonora — открытый клиент для своей музыки: локальная фонотека, " +
                    "плейлисты и официальные API сервисов.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (showWaveSettings) {
            WaveSettingsSheet(
                settings = settings.wave,
                historySize = waveHistorySize,
                dislikedCount = waveDisliked.size,
                onDismiss = { showWaveSettings = false },
                onApply = { draft ->
                    vm.updateWaveSettings { _ -> draft }
                    showWaveSettings = false
                },
                onClearHistory = { vm.clearWaveHistory() },
                onClearDislikes = { vm.clearWaveDislikes() },
            )
        }

        if (showProxyDialog) {
            var proxy by remember(settings.ytDlpProxy) {
                mutableStateOf(settings.ytDlpProxy)
            }
            AlertDialog(
                onDismissRequest = { showProxyDialog = false },
                title = { Text("Прокси для yt-dlp") },
                text = {
                    Column {
                        Text(
                            text = "Только для yt-dlp: http://host:port или socks5://host:port. " +
                                "Пустое поле — прямое подключение.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = proxy,
                            onValueChange = { proxy = it },
                            singleLine = true,
                            label = { Text("Прокси") },
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showProxyDialog = false
                            vm.setYtDlpProxy(proxy)
                        },
                    ) { Text("Сохранить") }
                },
                dismissButton = {
                    TextButton(onClick = { showProxyDialog = false }) { Text("Отмена") }
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }

        if (showRelayServersDialog) {
            var draft by remember(settings.relayInstances) {
                mutableStateOf(settings.relayInstances)
            }
            AlertDialog(
                onDismissRequest = { showRelayServersDialog = false },
                title = { Text("Серверы шлюза") },
                text = {
                    Column(
                        Modifier
                            .height(380.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = "Публичные зеркала Invidious/Piped: по одному адресу в " +
                                "строке. Пустое поле — встроенный список. Тип зеркала " +
                                "определяется по имени: `piped` — Piped, иначе Invidious.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            minLines = 4,
                            label = { Text("Зеркала") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (relayHealthState.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "Последняя проверка",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            relayHealthState.take(12).forEach { health ->
                                Text(
                                    text = if (health.ok) {
                                        "✓ ${health.instance.label} · ${health.latencyMs} мс"
                                    } else {
                                        "✕ ${health.instance.label} · ${health.detail}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (health.ok) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showRelayServersDialog = false
                            vm.setRelayInstances(draft)
                        },
                    ) { Text("Сохранить") }
                },
                dismissButton = {
                    TextButton(onClick = { showRelayServersDialog = false }) { Text("Отмена") }
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }

        if (showStreamDetails && streamCheckDetails != null) {
            AlertDialog(
                onDismissRequest = {
                    showStreamDetails = false
                    vm.dismissStreamCheckDetails()
                },
                title = { Text("Проверка потока") },
                text = {
                    Text(
                        text = streamCheckDetails.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showStreamDetails = false
                            vm.dismissStreamCheckDetails()
                        },
                    ) { Text("Понятно") }
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }

        if (showAutoPilotDialog) {
            AlertDialog(
                onDismissRequest = { showAutoPilotDialog = false },
                title = { Text("Проверка способов стриминга") },
                text = {
                    Column(
                        Modifier
                            .height(430.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (autoPilotRunningState) {
                            Text(
                                text = autoPilotMessageState ?: "Проверяем все способы…",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            Text(
                                text = autoPilotAuditState?.detailsText()
                                    ?: autoPilotMessageState
                                    ?: "Пока нет данных — нажмите «Проверить все способы».",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (vm.autoPilotNeedsVpnPermission()) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = "Для WARP нужно разрешение VPN — включите пункт " +
                                        "«Разрешить VPN для WARP» в разделе «Поток и сеть».",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAutoPilotDialog = false }) { Text("Понятно") }
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }
    }
}

/** Карточка раздела с заголовком и иконкой. */
@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Row(
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(16.dp),
            content = content,
        )
    }
}

/** Мелкий заголовок внутри раздела. */
@Composable
private fun SettingsLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/** Строка с переключателем. */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
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
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Строка-переход. */
@Composable
private fun ClickRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Строка «параметр — значение». */
@Composable
private fun InfoRow(title: String, value: String) {    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Строка-действие: заголовок, подпись и стрелка; [enabled] гасит повторные нажатия. */
@Composable
private fun ActionRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
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
                color = MaterialTheme.colorScheme.onSurface.copy(
                    alpha = if (enabled) 1f else 0.6f,
                ),
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
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                alpha = if (enabled) 1f else 0.6f,
            ),
        )
    }
}

/** Кружок акцентного цвета. */
@Composable
private fun AccentSwatch(accent: Accent, selected: Boolean, onClick: () -> Unit) {
    val palette = paletteFor(accent)
    val selectionBorder = if (selected) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
    } else {
        Modifier
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(palette.gradientStart, palette.gradientEnd)))
            .then(selectionBorder)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Выбрано",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Одна полоса эквалайзера: значение, вертикальный слайдер, частота. */
@Composable
private fun EqBand(hz: Int, value: Float, onValueChange: (Float) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = formatDb(value),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VerticalFader(value = value, onValueChange = onValueChange)
        Text(
            text = formatHz(hz),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Вертикальный слайдер: обычный Slider поворачиваем на −90°.
 * requiredWidth игнорирует узкую рамку контейнера, иначе слайдер сожмётся.
 */
@Composable
private fun VerticalFader(value: Float, onValueChange: (Float) -> Unit) {
    Box(
        modifier = Modifier
            .width(44.dp)
            .height(150.dp),
        contentAlignment = Alignment.Center,
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = -12f..12f,
            modifier = Modifier
                .requiredWidth(150.dp)
                .rotate(-90f),
        )
    }
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "Системная"
    ThemeMode.DARK -> "Тёмная"
    ThemeMode.LIGHT -> "Светлая"
}

private fun speedLabel(speed: Float): String = when (speed) {
    0.75f -> "0.75×"
    1f -> "1×"
    1.25f -> "1.25×"
    1.5f -> "1.5×"
    else -> "2×"
}

private fun formatDb(value: Float): String = String.format(Locale.US, "%+.1f", value)

private fun formatHz(hz: Int): String = if (hz >= 1000) {
    String.format(Locale.US, "%.1fк", hz / 1000f).replace(".0к", "к")
} else {
    hz.toString()
}

/** Склонение слова «обложка» для подписи результата поиска. */
private fun coverWord(count: Int): String = when {
    count % 10 == 1 && count % 100 != 11 -> "обложка"
    count % 10 in 2..4 && count % 100 !in 12..14 -> "обложки"
    else -> "обложек"
}
