package app.sonora.data.wave

import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.settings.WaveSettings

/** Почему кандидат не прошёл фильтры. Счётчики нужны для диагностики и подсказок. */
enum class WaveRejectReason(val label: String) {
    NOT_PLAYABLE("не играется"),
    VIDEO("видео"),
    SOURCE("другой источник"),
    MUSIC_ONLY("не музыка"),
    DISLIKED("скрыт"),
    NOT_FAVORITE("не в избранном"),
    MOOD("не то настроение"),
    ALREADY_SHOWN("уже показан"),
    FRESH_HISTORY("недавно играл"),
}

/**
 * Всё, что нужно жёсткому фильтру. Никакой сети и Android — только переданные
 * данные, поэтому правила проверяются юнит-тестами один в один с продакшеном.
 */
data class WaveFilterRequest(
    val filters: WaveFilters = WaveFilters(),
    val tuning: WaveSettings = WaveSettings(),
    val favoriteIds: Set<String> = emptySet(),
    val dislikedKeys: Set<String> = emptySet(),
    val featureIndex: WaveFeatureIndex = WaveFeatureIndex.EMPTY,
    val history: List<WaveHistoryEntry> = emptyList(),
    val nowMs: Long = System.currentTimeMillis(),
    /** Уже показанные треки: их не повторяем в следующих порциях. */
    val excludeIds: Set<String> = emptySet(),
    val excludeKeys: Set<String> = emptySet(),
    /** Разрешена ли онлайн-часть (стриминг включён в плеере). */
    val onlineAllowed: Boolean = true,
)

data class WaveFilterOutcome(
    val accepted: List<Track>,
    val rejected: Map<WaveRejectReason, Int>,
    val status: WaveStatus,
    val hint: String?,
) {
    val isEmpty: Boolean get() = accepted.isEmpty()

    fun rejectedCount(reason: WaveRejectReason): Int = rejected[reason] ?: 0
}

/**
 * Жёсткие фильтры «Моей волны».
 *
 * Ключевое правило: **фильтр никогда не «снимается» из-за пустой выдачи**.
 * Если после настроения/избранного/источников/дизлайков не осталось треков,
 * волна возвращает пустой список и понятную причину ([WaveFilterOutcome.hint])
 * вместо подмешивания треков, нарушающих фильтр.
 */
object WaveFilterEngine {

    /** Сколько считаем «совсем недавним» для подавления повторов (сутки). */
    const val FRESH_REPEAT_WINDOW_MS = 24L * 60L * 60L * 1000L

    /** С какой новизны пользователь просит «больше нового» — свежее вырезаем жёстко. */
    const val STRICT_NOVELTY = 60

    /**
     * Проверка одного трека. null — трек проходит; иначе первая причина отказа.
     *
     * @param freshKeys ключи треков, игравших в последние сутки; null — проверка
     *   повторов выключена (история не учитывается или новизна низкая).
     */
    fun check(
        track: Track,
        request: WaveFilterRequest,
        freshKeys: Set<String>? = null,
    ): WaveRejectReason? {
        if (!track.playable || track.uri.isNullOrBlank()) return WaveRejectReason.NOT_PLAYABLE
        if (track.isVideoContent) return WaveRejectReason.VIDEO

        when (request.filters.source) {
            WaveSourceMode.LOCAL -> if (track.source != Source.LOCAL) return WaveRejectReason.SOURCE
            WaveSourceMode.ONLINE -> if (track.source == Source.LOCAL) return WaveRejectReason.SOURCE
            WaveSourceMode.MIX -> Unit
        }

        if (!WaveEngine.isMusic(track, request.tuning)) return WaveRejectReason.MUSIC_ONLY

        if (WaveEngine.isDisliked(track, request.dislikedKeys)) return WaveRejectReason.DISLIKED

        if (request.filters.favoritesOnly && track.id !in request.favoriteIds) {
            return WaveRejectReason.NOT_FAVORITE
        }

        if (track.id in request.excludeIds) return WaveRejectReason.ALREADY_SHOWN
        if (WaveEngine.waveKey(track) in request.excludeKeys) return WaveRejectReason.ALREADY_SHOWN

        if (freshKeys != null && WaveEngine.waveKey(track) in freshKeys) {
            return WaveRejectReason.FRESH_HISTORY
        }

        if (request.filters.mood != WaveMood.ANY) {
            val feature = request.featureIndex.forTrack(track)
            if (!WaveMoodMatcher.matches(track, request.filters.mood, feature)) {
                return WaveRejectReason.MOOD
            }
        }

        return null
    }

    /** Прогоняет пул через все фильтры; порядок кандидатов сохраняется. */
    fun apply(candidates: List<Track>, request: WaveFilterRequest): WaveFilterOutcome {
        if (candidates.isEmpty()) {
            val status = emptyStatus(request)
            return WaveFilterOutcome(
                accepted = emptyList(),
                rejected = emptyMap(),
                status = status,
                hint = hintFor(status, request),
            )
        }

        val freshKeys = freshKeysOf(request)
        val accepted = ArrayList<Track>(candidates.size)
        val rejected = HashMap<WaveRejectReason, Int>(WaveRejectReason.entries.size)

        for (track in candidates) {
            val reason = check(track, request, freshKeys)
            if (reason == null) {
                accepted += track
            } else {
                rejected[reason] = (rejected[reason] ?: 0) + 1
            }
        }

        val status = if (accepted.isEmpty()) emptyStatus(request, rejected) else WaveStatus.READY
        return WaveFilterOutcome(
            accepted = accepted,
            rejected = rejected,
            status = status,
            hint = if (accepted.isEmpty()) hintFor(status, request) else null,
        )
    }

    /** Свежие повторы вырезаем только когда история включена и просят новизну. */
    fun freshKeysOf(request: WaveFilterRequest): Set<String>? {
        if (!request.tuning.useHistory) return null
        if (request.tuning.novelty < STRICT_NOVELTY) return null
        if (request.history.isEmpty()) return null
        val threshold = request.nowMs - FRESH_REPEAT_WINDOW_MS
        return request.history.asSequence()
            .filter { it.playedAtMs >= threshold }
            .map { WaveEngine.waveKeyOf(it.artist, it.title) }
            .toSet()
            .takeIf { it.isNotEmpty() }
    }

    /** Текст причины, который видит пользователь. */
    fun hintFor(status: WaveStatus, request: WaveFilterRequest): String = when (status) {
        WaveStatus.EMPTY_SOURCE -> when {
            request.filters.source == WaveSourceMode.ONLINE && !request.onlineAllowed ->
                "Онлайн-волна недоступна: стриминг выключен в настройках плеера."
            request.filters.source == WaveSourceMode.ONLINE ->
                "Онлайн-поиск не дал треков. Проверьте подключение и обновите волну."
            request.filters.source == WaveSourceMode.LOCAL ->
                "На устройстве нет подходящей музыки: добавьте файлы или включите стриминг."
            else -> "Нет подходящих источников для волны."
        }
        WaveStatus.EMPTY_FAVORITES ->
            "В избранном нет треков под текущие фильтры. Добавьте любимые треки " +
                "или выключите «Избранное»."
        WaveStatus.EMPTY_MOOD ->
            "Настроение «${request.filters.mood.label}»: подходящих треков не нашлось. " +
                "Снимите фильтр настроения или добавьте музыку в библиотеку."
        WaveStatus.EMPTY_LIBRARY ->
            "В библиотеке нет музыки — добавьте файлы на устройство или включите стриминг."
        WaveStatus.EMPTY_EXHAUSTED ->
            "Больше треков по этим фильтрам нет — обновите волну или смягчите фильтры."
        WaveStatus.ERROR -> "Не удалось собрать волну. Попробуйте обновить."
        WaveStatus.READY -> ""
        WaveStatus.LOADING -> ""
        WaveStatus.EMPTY_FILTERED ->
            "Все треки отсеяны фильтрами (скрытые, недавно игравшие, длина или видео)."
    }

    private fun emptyStatus(
        request: WaveFilterRequest,
        rejected: Map<WaveRejectReason, Int> = emptyMap(),
    ): WaveStatus {
        if (request.filters.source == WaveSourceMode.ONLINE && !request.onlineAllowed) {
            return WaveStatus.EMPTY_SOURCE
        }
        // Пул изначально пуст (нет локальной музыки / онлайн ничего не нашёл):
        // честнее сказать про источник, чем «все отсеяны фильтрами».
        if (rejected.isEmpty()) {
            if (request.filters.favoritesOnly) return WaveStatus.EMPTY_FAVORITES
            if (request.filters.mood != WaveMood.ANY) return WaveStatus.EMPTY_MOOD
            if (request.filters.source == WaveSourceMode.ONLINE) return WaveStatus.EMPTY_SOURCE
            return WaveStatus.EMPTY_FILTERED
        }
        val favoritesBlocked = request.filters.favoritesOnly &&
            (rejected[WaveRejectReason.NOT_FAVORITE] ?: 0) > 0
        if (favoritesBlocked) return WaveStatus.EMPTY_FAVORITES
        val moodBlocked = request.filters.mood != WaveMood.ANY &&
            (rejected[WaveRejectReason.MOOD] ?: 0) > 0
        if (moodBlocked) return WaveStatus.EMPTY_MOOD
        if (request.filters.source == WaveSourceMode.LOCAL) return WaveStatus.EMPTY_SOURCE
        return WaveStatus.EMPTY_FILTERED
    }
}
