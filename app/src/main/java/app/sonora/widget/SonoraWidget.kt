package app.sonora.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.drawable.toBitmap
import app.sonora.AppGraph
import app.sonora.MainActivity
import app.sonora.R
import app.sonora.core.model.Track
import app.sonora.playback.PlayerUiState
import coil.Coil
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Сборка и обновление домашнего виджета Sonora (классический RemoteViews, без Glance).
 *
 * Состояние не опрашивается фоновыми сервисами: [updateAll] вызывается из процесса
 * приложения (PlaybackController) при каждом изменении [PlayerUiState], а также
 * из [SonoraWidgetProvider] при добавлении/изменении размера виджета.
 *
 * Раскладка выбирается по классу провайдера, которому принадлежит экземпляр
 * виджета: у legacy-провайдера — по фактическому размеру (2×2 / 4×1 / 4×2–4×4),
 * у фиксированных (2×2, 4×1, 4×2, 4×4) — своя постоянная раскладка.
 * См. [PROVIDER_SPECS] и [selectSize].
 */
object SonoraWidget {

    // Действия кнопок. Строки продублированы в AndroidManifest.xml в <intent-filter>
    // ресивера — при изменении правьте оба места.
    const val ACTION_PLAY_PAUSE = "app.sonora.widget.PLAY_PAUSE"
    const val ACTION_NEXT = "app.sonora.widget.NEXT"
    const val ACTION_PREVIOUS = "app.sonora.widget.PREVIOUS"

    private const val TAG = "SonoraWidget"

    /** Заглушки, когда очередь пуста или сервис ещё не подключился. */
    private const val DEFAULT_TITLE = "Sonora"
    private const val DEFAULT_SUBTITLE = "Нажмите, чтобы открыть"

    /** ProgressBar во всех раскладках объявлен с android:max="1000". */
    private const val PROGRESS_MAX = 1000

    // Границы выбора раскладки (ширина/высота в dp из options лончера).
    /** Уже этого — квадратная 2×2 и вообще «узкие» размеры. */
    private const val SMALL_MAX_WIDTH_DP = 190
    /** Выше этого — большая раскладка (примерно два ряда и больше). */
    private const val LARGE_MIN_HEIGHT_DP = 110
    /** Ниже этой высоты в большой раскладке прячем исполнителя: иначе не влезет. */
    private const val LARGE_MIN_HEIGHT_WITH_ARTIST_DP = 120
    /** Почти квадрат (2×2) тоже компактная раскладка. */
    private const val SQUARE_ASPECT = 1.1f
    /** Ниже этой высоты в 4×1 прячем исполнителя, чтобы не резать прогресс. */
    private const val WIDE_MIN_HEIGHT_DP = 70

    // Обложка грузится в фоне через Coil. 300px хватает и на 2×2, и на превью.
    private const val ARTWORK_SIZE_PX = 300
    private const val ARTWORK_CACHE_SIZE = 4

    // Разные requestCode, иначе PendingIntent'ы разных кнопок «склеятся».
    private const val REQ_OPEN = 1001
    private const val REQ_PLAY_PAUSE = 1002
    private const val REQ_NEXT = 1003
    private const val REQ_PREVIOUS = 1004

    // Последний проигранный трек: переживает смерть процесса приложения.
    private const val PREFS = "sonora_widget"
    private const val KEY_TITLE = "last_title"
    private const val KEY_ARTIST = "last_artist"
    private const val KEY_PLAYING = "last_playing"
    private const val KEY_PROGRESS = "last_progress"
    private const val KEY_TRACK_ID = "last_track_id"
    private const val KEY_ARTWORK = "last_artwork"

    /**
     * Компонент, на который уходят broadcast'ы кнопок. Достаточно одного
     * зарегистрированного ресивера с нужными action'ами: legacy-провайдер
     * объявлен в манифесте всегда, а его onReceive обновляет все виджеты.
     */
    private val ACTION_RECEIVER_CLASS = SonoraWidgetProvider::class.java

    /** Фоновые загрузки обложек: не блокируют обновление виджета. */
    private val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Кэш обложек по id трека: прогресс приходит ~1 раз в секунду, грузим один раз. */
    private val artworkCache = ConcurrentHashMap<String, Bitmap>()

    /** Какие обложки уже грузятся, чтобы не запускать загрузку на каждый апдейт. */
    private val artworkLoading: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Обложки, которые не удалось загрузить: не долбим сеть на каждом апдейте. */
    private val artworkFailed: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Вариант раскладки виджета под его текущий размер. */
    private enum class WidgetSize(val layoutRes: Int) {
        /** 2×2 и прочие узкие/квадратные размеры. */
        SMALL(R.layout.widget_sonora_small),

        /** 4×1: обложка, текст, все три кнопки. */
        WIDE(R.layout.widget_sonora),

        /** 4×2 / 4×3: крупная обложка слева, большой текст, кнопки внизу. */
        LARGE(R.layout.widget_sonora_large),

        /** 4×4: обложка сверху, крупный текст, ряд кнопок 48dp. */
        XLARGE(R.layout.widget_sonora_xlarge),
    }

    private data class WidgetOptions(val widthDp: Int, val heightDp: Int)

    private data class DisplayData(
        val title: String,
        val artist: String,
        val playing: Boolean,
        val progress: Int,
    )

    private data class LastPlayed(
        val title: String,
        val artist: String,
        val playing: Boolean,
        val progress: Float,
        val trackId: String?,
        val artwork: String?,
    )

    /**
     * Один зарегистрированный в манифесте провайдер и правило его раскладки.
     * [fixedSize] == null означает legacy: раскладка подбирается по размеру.
     */
    private data class ProviderSpec(
        val providerClass: Class<out AppWidgetProvider>,
        val fixedSize: WidgetSize?,
    )

    /** Конкретный экземпляр виджета, который нужно обновить. */
    private data class Target(
        val appWidgetId: Int,
        val size: WidgetSize,
        val options: WidgetOptions,
    )

    /**
     * Все провайдеры виджета: legacy (авто-раскладка) + четыре фиксированных.
     * Список должен совпадать с <receiver> в AndroidManifest.xml.
     */
    private val PROVIDER_SPECS: List<ProviderSpec> = listOf(
        ProviderSpec(SonoraWidgetProvider::class.java, null),
        ProviderSpec(SonoraWidget2x2Provider::class.java, WidgetSize.SMALL),
        ProviderSpec(SonoraWidget4x1Provider::class.java, WidgetSize.WIDE),
        ProviderSpec(SonoraWidget4x2Provider::class.java, WidgetSize.LARGE),
        ProviderSpec(SonoraWidget4x4Provider::class.java, WidgetSize.XLARGE),
    )

    /**
     * Обновляет все экземпляры виджета, читая состояние из общего процесса
     * (AppGraph.playback.state). Это «дешёвый» вариант: MediaController уже
     * подключён, если приложение живо.
     */
    fun updateAll(context: Context) {
        val state = runCatching { AppGraph.get(context).playback.state.value }
            .getOrDefault(PlayerUiState())
        updateAll(context, state)
    }

    /**
     * Обновляет все экземпляры виджета переданным состоянием.
     * Полностью защищён от исключений: виджет не должен ронять приложение.
     */
    fun updateAll(context: Context, state: PlayerUiState) {
        try {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val targets = collectTargets(context, appWidgetManager)
            if (targets.isEmpty()) return

            val track = state.current
            // Если процесс приложения был убит, показываем последний известный трек.
            val last = if (track == null) lastPlayed(context) else null
            if (track != null) rememberLast(context, state)

            val data = DisplayData(
                title = track?.title?.takeIf { it.isNotBlank() } ?: last?.title ?: DEFAULT_TITLE,
                artist = track?.artist?.takeIf { it.isNotBlank() } ?: last?.artist ?: DEFAULT_SUBTITLE,
                playing = if (track == null) last?.playing ?: false else state.isPlaying,
                progress = ((if (track == null) last?.progress ?: 0f else state.progress)
                    .coerceIn(0f, 1f) * PROGRESS_MAX).toInt(),
            )

            // Обложку ищем по id трека: ключ кэша стабилен между обновлениями.
            val artworkKey = track?.id ?: last?.trackId
            val coverSource = artworkSource(track) ?: last?.artwork
            val cachedArtwork = artworkKey?.let { artworkCache[it] }

            for (target in targets) {
                appWidgetManager.updateAppWidget(
                    target.appWidgetId,
                    buildViews(context, target.size, target.options, data, cachedArtwork),
                )
            }

            // Сразу показали заглушку — теперь догружаем обложку в фоне.
            if (artworkKey != null &&
                !coverSource.isNullOrBlank() &&
                cachedArtwork == null &&
                !artworkFailed.contains(artworkKey)
            ) {
                requestArtwork(context, artworkKey, coverSource)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось обновить виджет", t)
        }
    }

    // ---------------- Раскладка ----------------

    /**
     * Собирает все экземпляры виджетов всех провайдеров и решает, какая
     * раскладка нужна каждому: фиксированная — из [ProviderSpec.fixedSize],
     * legacy — по фактическому размеру ([selectSize]).
     */
    private fun collectTargets(
        context: Context,
        manager: AppWidgetManager,
    ): List<Target> {
        val targets = ArrayList<Target>(PROVIDER_SPECS.size)
        for (spec in PROVIDER_SPECS) {
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, spec.providerClass))
            }.getOrNull() ?: continue
            for (id in ids) {
                val options = readOptions(manager, id)
                val size = spec.fixedSize
                    ?: selectSize(options.widthDp, options.heightDp)
                targets += Target(id, size, options)
            }
        }
        return targets
    }

    private fun readOptions(manager: AppWidgetManager, appWidgetId: Int): WidgetOptions {
        val options = runCatching { manager.getAppWidgetOptions(appWidgetId) }
            .getOrNull()
            ?: Bundle()
        return WidgetOptions(
            widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0),
            heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0),
        )
    }

    /**
     * Выбирает раскладку по размеру виджета в dp.
     *
     * Порог большой раскладки — ~2 ряда (110dp), а не 180dp: на большинстве
     * лончеров 4×2 приходит как 110–160dp, и при пороге 180 он остался бы в
     * широкой раскладке с пустотой внизу (баг из отчёта про 3×2).
     */
    private fun selectSize(widthDp: Int, heightDp: Int): WidgetSize {
        if (widthDp <= 0 || heightDp <= 0) return WidgetSize.WIDE
        if (widthDp < SMALL_MAX_WIDTH_DP || widthDp < heightDp * SQUARE_ASPECT) {
            return WidgetSize.SMALL
        }
        return if (heightDp >= LARGE_MIN_HEIGHT_DP) WidgetSize.LARGE else WidgetSize.WIDE
    }

    private fun buildViews(
        context: Context,
        size: WidgetSize,
        options: WidgetOptions,
        data: DisplayData,
        artwork: Bitmap?,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, size.layoutRes)

        // Эти View есть во всех раскладках.
        views.setTextViewText(R.id.widget_title, data.title)
        views.setTextViewText(R.id.widget_artist, data.artist)
        views.setProgressBar(R.id.widget_progress, PROGRESS_MAX, data.progress, false)
        views.setImageViewResource(
            R.id.widget_play_pause,
            if (data.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )
        views.setContentDescription(
            R.id.widget_play_pause,
            context.getString(if (data.playing) R.string.widget_pause else R.string.widget_play),
        )
        views.setOnClickPendingIntent(
            R.id.widget_play_pause,
            broadcastPendingIntent(context, ACTION_PLAY_PAUSE, REQ_PLAY_PAUSE),
        )
        // Тап по карточке (и по текстовой области) открывает приложение.
        views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent(context))
        views.setOnClickPendingIntent(R.id.widget_info, openAppPendingIntent(context))

        applyArtwork(views, artwork)

        when (size) {
            WidgetSize.SMALL -> Unit
            WidgetSize.WIDE -> {
                // В очень низком 4×1 исполнителя убираем: важнее название и прогресс.
                if (options.heightDp in 1 until WIDE_MIN_HEIGHT_DP) {
                    views.setViewVisibility(R.id.widget_artist, View.GONE)
                }
                addTransportButtons(context, views)
            }
            WidgetSize.LARGE -> {
                // На нижней границе большой раскладки (~2 ряда) исполнитель не влезает.
                if (options.heightDp in 1 until LARGE_MIN_HEIGHT_WITH_ARTIST_DP) {
                    views.setViewVisibility(R.id.widget_artist, View.GONE)
                }
                addTransportButtons(context, views)
            }
            // Крупная 4×4-раскладка: места всегда хватает, адаптивность не нужна.
            WidgetSize.XLARGE -> addTransportButtons(context, views)
        }
        return views
    }

    /** prev/next есть во всех раскладках, кроме компактной 2×2. */
    private fun addTransportButtons(context: Context, views: RemoteViews) {
        views.setContentDescription(R.id.widget_prev, context.getString(R.string.widget_prev))
        views.setContentDescription(R.id.widget_next, context.getString(R.string.widget_next))
        views.setOnClickPendingIntent(
            R.id.widget_prev,
            broadcastPendingIntent(context, ACTION_PREVIOUS, REQ_PREVIOUS),
        )
        views.setOnClickPendingIntent(
            R.id.widget_next,
            broadcastPendingIntent(context, ACTION_NEXT, REQ_NEXT),
        )
    }

    /**
     * Показывает обложку, если она уже загружена, иначе — иконку-ноту.
     * Скруглённые углы обложки делает [roundedBitmap], поэтому фон-заглушка
     * просвечивает по углам.
     */
    private fun applyArtwork(views: RemoteViews, artwork: Bitmap?) {
        if (artwork != null) {
            views.setImageViewBitmap(R.id.widget_cover, artwork)
            views.setViewVisibility(R.id.widget_cover, View.VISIBLE)
            views.setViewVisibility(R.id.widget_icon, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_cover, View.GONE)
            views.setViewVisibility(R.id.widget_icon, View.VISIBLE)
        }
    }

    // ---------------- Обложка ----------------

    private fun artworkSource(track: Track?): String? = track?.let { t ->
        t.artworkUrl?.takeIf { it.isNotBlank() } ?: t.artworkUri?.takeIf { it.isNotBlank() }
    }

    /**
     * Грузит обложку в фоне. Первый апдейт уже показал заглушку, поэтому здесь
     * можно не спешить: по завершении перерисовываем виджеты ещё раз — битмап
     * уже в кэше, повторной загрузки не будет.
     */
    private fun requestArtwork(context: Context, key: String, source: String) {
        if (!artworkLoading.add(key)) return

        val appContext = context.applicationContext
        artworkScope.launch {
            var loaded: Bitmap? = null
            try {
                val request = ImageRequest.Builder(appContext)
                    .data(source)
                    .allowHardware(false) // софт-битмап: нужен для скругления и RemoteViews
                    .size(ARTWORK_SIZE_PX)
                    .build()
                val result = Coil.imageLoader(appContext).execute(request)
                // Coil 2.6: SuccessResult отдаёт Drawable; для RemoteViews нужен Bitmap.
                val success = result as? coil.request.SuccessResult
                val bitmap = (success?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                loaded = bitmap?.let { roundedBitmap(it) }
            } catch (t: Throwable) {
                artworkFailed.add(key)
                Log.w(TAG, "Не удалось загрузить обложку: $source", t)
            } finally {
                artworkLoading.remove(key)
            }

            val bitmap = loaded
            if (bitmap == null) {
                artworkFailed.add(key)
                return@launch
            }
            storeArtwork(key, bitmap)
            withContext(Dispatchers.Main) {
                runCatching { updateAll(appContext) }
                    .onFailure { Log.w(TAG, "Не удалось перерисовать виджет с обложкой", it) }
            }
        }
    }

    private fun storeArtwork(key: String, bitmap: Bitmap) {
        // Держим в памяти только несколько последних обложек.
        if (artworkCache.size >= ARTWORK_CACHE_SIZE && !artworkCache.containsKey(key)) {
            val victim = artworkCache.keys.firstOrNull { it != key }
            if (victim != null) artworkCache.remove(victim)
        }
        artworkCache[key] = bitmap
    }

    /** Скругляет углы обложки: RemoteViews не умеет clipToOutline, поэтому правим пиксели. */
    private fun roundedBitmap(source: Bitmap): Bitmap {
        return try {
            val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val rect = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
            val radius = minOf(source.width, source.height) * 0.14f
            val path = Path().apply {
                addRoundRect(rect, radius, radius, Path.Direction.CW)
            }
            canvas.drawPath(path, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(source, 0f, 0f, paint)
            output
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось скруглить обложку", t)
            source
        }
    }

    // ---------------- Последний трек ----------------

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun rememberLast(context: Context, state: PlayerUiState) {
        val track = state.current ?: return
        runCatching {
            prefs(context).edit()
                .putString(KEY_TITLE, track.title)
                .putString(KEY_ARTIST, track.artist)
                .putBoolean(KEY_PLAYING, state.isPlaying)
                .putFloat(KEY_PROGRESS, state.progress)
                .putString(KEY_TRACK_ID, track.id)
                .putString(KEY_ARTWORK, artworkSource(track))
                .apply()
        }
    }

    private fun lastPlayed(context: Context): LastPlayed? = runCatching {
        val p = prefs(context)
        val title = p.getString(KEY_TITLE, null) ?: return null
        LastPlayed(
            title = title,
            artist = p.getString(KEY_ARTIST, "") ?: "",
            playing = p.getBoolean(KEY_PLAYING, false),
            progress = p.getFloat(KEY_PROGRESS, 0f),
            trackId = p.getString(KEY_TRACK_ID, null),
            artwork = p.getString(KEY_ARTWORK, null),
        )
    }.getOrNull()

    // ---------------- PendingIntent'ы ----------------

    /** PendingIntent на запуск MainActivity (launchMode="singleTop"). */
    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            REQ_OPEN,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * PendingIntent на broadcast в собственный ресивер. Intent явный
     * (setComponent), поэтому его нельзя перехватить снаружи;
     * FLAG_IMMUTABLE обязателен начиная с API 31.
     */
    private fun broadcastPendingIntent(
        context: Context,
        action: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(action).setComponent(ComponentName(context, ACTION_RECEIVER_CLASS))
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
