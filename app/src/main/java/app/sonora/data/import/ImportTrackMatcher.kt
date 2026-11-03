package app.sonora.data.import

/** Кандидат для сопоставления: обычные данные, без Android-зависимостей. */
data class MatchCandidate(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long = 0L,
)

/**
 * Сопоставление трека из импортируемого списка (Spotify/Apple Music/YouTube Music)
 * с кандидатами из локальной библиотеки или из поиска InnerTube.
 *
 * Задача нетривиальна, потому что названия в экспортах и в каталогах описывают
 * одну песню по-разному:
 *
 * - экспорт: `Smells Like Teen Spirit` / `Nirvana`;
 * - локальный файл с YouTube: `Nirvana - Heart-Shaped Box`;
 * - каталог: `Nirvana - Smells Like Teen Spirit (Official Video)`,
 *   исполнитель-канал `Nirvana - Topic`.
 *
 * Поэтому сравнение идёт по нормализованным строкам ([normalize]): снимаются
 * украшения, `feat.`, диакритика, типографские тире/кавычки и регистр.
 *
 * ## Правило приёма
 *
 * Итоговый балл [score] складывается из:
 * - название: точное совпадение **3**, частичное **1**;
 * - исполнитель: точное **2**, частичное **1**;
 * - длительность в пределах [DURATION_TOLERANCE_MS] **+1**.
 *
 * Совпадение принимается при `score >= [MIN_SCORE]`. Отсюда:
 * - точное название само по себе (3) проходит — исполнитель может быть не указан
 *   или назван иначе (`Nirvana` против `Nirvana - Topic`);
 * - название + исполнитель (3+2) проходят уверенно;
 * - **частичное название само по себе (1) не проходит**: иначе `Iron Man`
 *   совпал бы с `Bohemian Rhapsody` по общим словам, а `Heart-Shaped Box`
 *   вытеснил бы настоящий трек;
 * - неверный исполнитель не может обогнать верного: у верного есть +2, и при
 *   равном названии он всегда впереди.
 *
 * Никаких сетевых или Android-вызовов: объект чистый и тестируется на JVM.
 */
object ImportTrackMatcher {

    /** Минимальный балл, при котором совпадение считается найденным. */
    const val MIN_SCORE: Int = 3

    /** Название совпало точно. */
    private const val TITLE_EXACT = 3

    /** Название совпало частично (одно содержится в другом). */
    private const val TITLE_PARTIAL = 1

    /** Исполнитель совпал точно. */
    private const val ARTIST_EXACT = 2

    /** Исполнитель совпал частично. */
    private const val ARTIST_PARTIAL = 1

    /** Бонус за совпадение длительности. */
    private const val DURATION_BONUS = 1

    /** Премия «голому» названию против канцелярской пометки каталога. */
    private const val CANONICAL_BONUS = 1

    /** Штраф за другое издание: концерт, ремикс, акапелла. */
    private const val ALT_EDITION_PENALTY = 2

    /** Премия оригиналу с канала правообладателя (`- Topic`). */
    private const val TOPIC_CHANNEL_BONUS = 1

    /** Разница длительности, при которой начисляется бонус. */
    const val DURATION_TOLERANCE_MS: Long = 10_000L

    /**
     * Украшения в названиях каталогов: `(Official Video)`, `[Official Audio]`,
     * `(Remastered 2011)`, `【MV】`. Снимаются целиком вместе со скобками.
     */
    private val DECORATION_PARENS = Regex(
        """[\(\[]\s*(?:official|lyric|lyrics|audio|video|mv|m/v|hd|hq|4k|8k|""" +
            """remaster(?:ed)?|remastering|live|visualizer|clip|music\s+video|""" +
            """официальн\w*|клип|премьера|аудио|видео)\b[^\)\]]*[\)\]]""",
        RegexOption.IGNORE_CASE,
    )

    /** Полноширинные скобки и «ёлочки»: `【Official】`, `（Live）`. */
    private val DECORATION_WIDE = Regex(
        """[【（\[][^】）\]]*[】）\]]""",
    )

    /** `- Topic` / " - Topic" в конце исполнителя-канала. */
    private val TOPIC_SUFFIX = Regex("""\s*[-–—]\s*topic\s*$""", RegexOption.IGNORE_CASE)

    /** `feat. X`, `ft. X`, `featuring X` и всё, что после них. */
    private val FEAT_TAIL = Regex("""\b(?:feat|ft|featuring|при\s+участии)\b.*$""", RegexOption.IGNORE_CASE)

    /** Версии/ремастеры через дефис: `- Remastered 2006`, `- 2014 Remaster`. */
    private val DASH_VERSION = Regex(
        """\s*[-–—]\s*(?:\d{4}\s+)?(?:remaster(?:ed)?|remastering|live|""" +
            """radio\s+edit|single\s+version|album\s+version|deluxe)\b.*$""",
        RegexOption.IGNORE_CASE,
    )

    /** Скобки с любым содержимым — снимаем как шум (`(Acoustic Mix)`, `(Live ver.)`). */
    private val ANY_PARENS = Regex("""[\(\[][^\)\]]*[\)\]]""")

    /**
     * Хвосты имён каналов и лейблов, которые не являются частью имени
     * исполнителя: `the killers - official channel` → `the killers`.
     *
     * Без этого шага исполнитель из реального экспорта («The Killers - Official
     * Channel») не совпадал ни с одним кандидатом, и трек выбирался по одному
     * лишь названию — так кавер Gaullin побеждал The White Stripes.
     */
    private val CHANNEL_NOISE = Regex(
        """\b(?:official(?:\s+(?:channel|video|audio|music\s+video))?|vevo|topic|""".trimIndent() +
            """records|music|band|channel|официальный\s+канал)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Всё, кроме букв и цифр (кириллица и латиница). */
    private val NON_WORD = Regex("""[^a-zа-яё0-9]+""")

    /**
     * Пометки, которые в скобках означают **другую запись** той же песни:
     * концертную версию, ремикс, акапеллу, инструментал. Канцелярские пометки
     * каталога (`Official Video`, `Remastered 2011`, `HD`) сюда не входят — это
     * ровно та же запись, и штрафовать её нельзя.
     */
    private val ALTERNATIVE_EDITION = Regex(
        """\b(?:live|acoustic|unplugged|acap+p?ella|instrumental|karaoke|""".trimIndent() +
            """remix|rmx|mix|bootleg|mashup|cover|demo|rehearsal|session|""".trimIndent() +
            """концерт\w*|живой|акапелл\w*|минус|ремикс\w*|кавер\w*|инструментал\w*)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Балл совпадения кандидата с элементом списка. 0 — совпадений нет.
     * Больше — лучше; при равенстве вызывающий сам выбирает порядок.
     */
    fun score(candidate: MatchCandidate, entry: ImportedTrackEntry): Int = scoreNormalized(
        candidateTitle = normalize(candidate.title),
        candidateArtist = normalizeArtist(candidate.artist),
        candidateDurationMs = candidate.durationMs,
        candidateRawTitle = candidate.title,
        entryTitle = normalize(entry.title),
        entryArtist = normalizeArtist(entry.artist),
        entryDurationMs = entry.durationMs ?: 0L,
        entryRawTitle = entry.title,
    )

    /**
     * Единственная реализация правила приёма. И скалярный [score], и пакетный
     * [CandidateIndex] ходят только сюда, поэтому «быстрый» путь не может
     * разойтись с «медленным»: сравнивать их поведение не придётся, они буквально
     * один и тот же код.
     */
    private fun scoreNormalized(
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationMs: Long,
        candidateRawTitle: String,
        entryTitle: String,
        entryArtist: String,
        entryDurationMs: Long,
        entryRawTitle: String,
    ): Int {
        var total = 0
        if (candidateTitle.isNotEmpty() && entryTitle.isNotEmpty()) {
            total += when {
                candidateTitle == entryTitle -> TITLE_EXACT
                related(candidateTitle, entryTitle) -> TITLE_PARTIAL
                else -> 0
            }
            // Название вида "Артист - Песня" сравниваем ещё и по хвосту:
            // каталоги часто склеивают исполнителя в название.
            if (total < TITLE_EXACT) {
                tailOf(entryTitle)?.let { tail ->
                    if (tail == candidateTitle) total = maxOf(total, TITLE_EXACT)
                    else if (tail.isNotEmpty() && related(tail, candidateTitle)) {
                        total = maxOf(total, TITLE_PARTIAL)
                    }
                }
            }
            // Издание решает исход спора: normalize() снимает скобки, и без этой
            // поправки «Rolling in the Deep» и «Rolling in the Deep (Acapella)»
            // неотличимы — тогда побеждал тот, кто выше в выдаче каталога, и
            // вместо песни сохранялся ремикс или акапелла.
            if (candidateTitle == entryTitle) {
                total += editionTieBreak(
                    candidateRawTitle = candidateRawTitle,
                    entryRawTitle = entryRawTitle,
                    candidateArtist = candidateArtist,
                    entryArtist = entryArtist,
                )
            }
        }

        if (candidateArtist.isNotEmpty() && entryArtist.isNotEmpty()) {
            total += artistScore(candidateArtist, entryArtist)
        }

        if (entryDurationMs > 0L && candidateDurationMs > 0L &&
            kotlin.math.abs(entryDurationMs - candidateDurationMs) <= DURATION_TOLERANCE_MS
        ) {
            total += DURATION_BONUS
        }
        return total
    }

    /**
     * Лучший кандидат для [entry] или `null`, если ни один не набрал [MIN_SCORE].
     *
     * При равном балле выигрывает кандидат с совпавшим исполнителем, а если
     * такого нет — тот, кто идёт раньше в [candidates]. См. [pickBest].
     */
    fun best(candidates: List<MatchCandidate>, entry: ImportedTrackEntry): MatchCandidate? {
        val entryTitle = normalize(entry.title)
        val entryArtist = normalizeArtist(entry.artist)
        val entryDuration = entry.durationMs ?: 0L
        val scored = ArrayList<ScoredCandidate>(candidates.size)
        for (candidate in candidates) {
            scored += scoreOne(
                candidate = candidate,
                candidateTitle = normalize(candidate.title),
                candidateArtist = normalizeArtist(candidate.artist),
                entryTitle = entryTitle,
                entryArtist = entryArtist,
                entryDurationMs = entryDuration,
                entryRawTitle = entry.title,
            )
        }
        return pickBest(scored)
    }

    /**
     * Готовит список кандидатов к многократному поиску: нормализует каждого
     * один раз. Список из 40 кандидатов, проверяемый для 5 000 строк, иначе
     * нормализуется 200 000 раз вместо 40 — нормализация здесь самое дорогое.
     */
    fun index(candidates: List<MatchCandidate>): CandidateIndex = CandidateIndex(
        candidates = candidates,
        titles = candidates.map { normalize(it.title) },
        artists = candidates.map { normalizeArtist(it.artist) },
    )

    /**
     * Пакетный поиск лучшего кандидата. Поведение обязано совпадать с
     * [ImportTrackMatcher.best]: тот же победитель, тот же выбор первого при
     * равном балле, тот же порог [MIN_SCORE] и тот же `null`.
     *
     * Совпадение гарантировано конструкцией: обе ветки собирают [ScoredCandidate]
     * через один и тот же [scoreOne] и выбирают победителя одним и тем же
     * [pickBest]. Ни порог [MIN_SCORE], ни правило «при равном балле выигрывает
     * идущий раньше» не могут разойтись, потому что продублированы не правила, а
     * буквально один код на двоих.
     *
     * Нормализованные поля лежат в параллельных списках, а не в отдельном
     * классе-обёртке: так индекс не выносит наружу вспомогательный тип.
     */
    class CandidateIndex internal constructor(
        private val candidates: List<MatchCandidate>,
        private val titles: List<String>,
        private val artists: List<String>,
    ) {
        /** Сколько кандидатов проиндексировано. */
        val size: Int get() = candidates.size

        fun best(entry: ImportedTrackEntry): MatchCandidate? {
            val entryTitle = normalize(entry.title)
            val entryArtist = normalizeArtist(entry.artist)
            val entryDuration = entry.durationMs ?: 0L
            val entryRawTitle = entry.title

            val scored = ArrayList<ScoredCandidate>(candidates.size)
            for (i in candidates.indices) {
                scored += scoreOne(
                    candidate = candidates[i],
                    candidateTitle = titles[i],
                    candidateArtist = artists[i],
                    entryTitle = entryTitle,
                    entryArtist = entryArtist,
                    entryDurationMs = entryDuration,
                    entryRawTitle = entry.title,
                )
            }
            return pickBest(scored)
        }
    }

    /**
     * Нормализация названия или исполнителя для сравнения: регистр, украшения,
     * `feat.`, диакритика, типографские тире/кавычки и пунктуация.
     */
    fun normalize(value: String?): String {
        if (value.isNullOrBlank()) return ""
        var text = value.lowercase().trim()
        text = foldDiacritics(text)
        text = unifyDashesAndQuotes(text)
        text = TOPIC_SUFFIX.replace(text, " ")
        text = DECORATION_WIDE.replace(text, " ")
        text = DECORATION_PARENS.replace(text, " ")
        text = DASH_VERSION.replace(text, " ")
        text = FEAT_TAIL.replace(text, " ")
        text = ANY_PARENS.replace(text, " ")
        text = NON_WORD.replace(text, " ")
        return text.trim().replace(Regex("""\s+"""), " ")
    }

    /** Исполнитель без `feat.` и `- Topic`: для сравнения имён важнее основа. */
    private fun normalizeArtist(value: String?): String =
        normalize(value?.let { CAMEL_BOUNDARY.replace(it, " ") }?.replace(TOPIC_SUFFIX, " "))

    /**
     * Балл за совпадение исполнителя. Вынесен отдельно, потому что тот же расчёт
     * нужен при выборе между несколькими кандидатами с одинаковым названием:
     * [hasArtistAgreement] опирается на него, чтобы отличить «исполнитель
     * совпал» от «совпадения нет вовсе».
     */
    private fun artistScore(candidateArtist: String, entryArtist: String): Int = when {
        candidateArtist == entryArtist -> ARTIST_EXACT
        related(candidateArtist, entryArtist) -> ARTIST_PARTIAL
        // "Nirvana" в списке против "Nirvana - Topic" в каталоге.
        topicBase(entryArtist)?.let { candidateArtist == it } == true -> ARTIST_EXACT
        topicBase(candidateArtist)?.let { entryArtist == it } == true -> ARTIST_EXACT
        // Каналы-склейки: "The Killers - Official Channel", "Queen Official".
        // Без снятия хвоста такой исполнитель вообще ни на что не похож, и
        // кавер с чужим именем выигрывал по одному лишь названию.
        related(artistBase(candidateArtist), artistBase(entryArtist)) -> ARTIST_PARTIAL
        else -> 0
    }

    /**
     * Убирает из имени исполнителя хвосты каналов и лейблов: `- Topic`,
     * `- Official Channel`, `Official`, `VEVO`, `(Band)`.
     *
     * Нужно только для сравнения: возвращённое значение не показывается
     * пользователю. Если после снятия не остаётся ничего осмысленного,
     * возвращается исходная строка — пустой исполнитель сравнивался бы со всем
     * подряд и давал ложные совпадения.
     */
    private fun artistBase(normalized: String): String {
        val stripped = CHANNEL_NOISE.replace(normalized, " ").trim().replace(Regex("""\s+"""), " ")
        return if (stripped.length >= 3) stripped else normalized
    }

    /**
     * Совпал ли исполнитель хоть как-то. Отличается от `artistScore(...) > 0`
     * тем, что при пустом исполнителе в списке возвращает `null` — «неизвестно»,
     * а не «не совпало»: тогда штрафовать кандидата не за что.
     */
    private fun hasArtistAgreement(candidateArtist: String, entryArtist: String): Boolean? {
        if (candidateArtist.isEmpty() || entryArtist.isEmpty()) return null
        return artistScore(candidateArtist, entryArtist) > 0
    }

    /**
     * Выбирает лучшего кандидата вручную, чтобы применить правила, которые не
     * выражаются одним числом на кандидата.
     *
     * Главное правило — исход спора о кавере. Когда исполнитель в списке не
     * совпал ни с одним кандидатом, название часто всё равно точное, и тогда
     * побеждал первый по выдаче каталога: для «Seven Nation Army — The White
     * Stripes» сохранялся кавер Gaullin, для «Mr. Brightside» — Matty Hinds.
     * Поэтому при равном счёте предпочитается кандидат, у которого исполнитель
     * действительно совпал: настоящий исполнитель весит больше, чем его позиция
     * в выдаче.
     *
     * При полном отсутствии совпадений по исполнителю порядок сохраняется:
     * угадывать там нечего.
     */
    private fun pickBest(scored: List<ScoredCandidate>): MatchCandidate? {
        var best: ScoredCandidate? = null
        for (item in scored) {
            if (item.score <= 0) continue
            val current = best ?: run { best = item; continue }
            if (item.score > current.score) {
                best = item
            } else if (item.score == current.score &&
                current.artistAgreement != true && item.artistAgreement == true
            ) {
                // Счёт равен, но у этого кандидата исполнитель совпал — он и есть
                // настоящий трек. Строгое `==` при этом сохраняет правило
                // «при равном балле выигрывает тот, кто раньше в списке».
                best = item
            }
        }
        val winner = best ?: return null
        return if (winner.score >= MIN_SCORE) winner.candidate else null
    }

    /** Кандидат вместе со всем, что нужно для сравнения. */
    private class ScoredCandidate(
        val candidate: MatchCandidate,
        val score: Int,
        val artistAgreement: Boolean?,
    )

    private fun scoreOne(
        candidate: MatchCandidate,
        candidateTitle: String,
        candidateArtist: String,
        entryTitle: String,
        entryArtist: String,
        entryDurationMs: Long,
        entryRawTitle: String,
    ): ScoredCandidate = ScoredCandidate(
        candidate = candidate,
        score = scoreNormalized(
            candidateTitle = candidateTitle,
            candidateArtist = candidateArtist,
            candidateDurationMs = candidate.durationMs,
            candidateRawTitle = candidate.title,
            entryTitle = entryTitle,
            entryArtist = entryArtist,
            entryDurationMs = entryDurationMs,
            entryRawTitle = entryRawTitle,
        ),
        artistAgreement = hasArtistAgreement(candidateArtist, entryArtist),
    )

    /**
     * Согласуется ли исполнитель кандидата с исполнителем строки импорта.
     *
     * `null` означает «сравнить нельзя» (у одной из сторон исполнителя нет), и это
     * не то же самое, что `false`: отсутствие данных не повод считать совпадение
     * неверным.
     *
     * Нужно вызывающему, чтобы отличить надёжное локальное попадание от
     * сомнительного. Локальная библиотека проверяется раньше сети, поэтому один
     * раз сохранённый неверный матч (кавер вместо оригинала) иначе остаётся
     * навсегда: строка «Mr. Brightside — The Killers» снова и снова попадала в
     * закрепившийся в библиотеке «Mr. Brightside — Matty Hinds», и сеть даже не
     * опрашивалась.
     */
    fun artistAgrees(candidate: MatchCandidate, entry: ImportedTrackEntry): Boolean? =
        hasArtistAgreement(
            candidateArtist = normalizeArtist(candidate.artist),
            entryArtist = normalizeArtist(entry.artist),
        )

    /**
     * Поправка за «издание» трека. Применяется только когда нормализованные
     * названия уже совпали, поэтому она не может пропустить мимо порога трек,
     * который раньше проходил, — только выбрать более уместный из равных.
     *
     * Смысл: [normalize] снимает скобки целиком, поэтому «Voyager» и
     * «Voyager (Live)» для матчера одно и то же. Без поправки побеждал первый по
     * выдаче каталога, и пользователь получал ремикс или акапеллу вместо песни.
     *
     * Правила:
     * - канцелярская пометка каталога (`Official`, `Remaster`, `HD`) — это та же
     *   песня, поэтому она не штрафуется, а «голое» название получает премию;
     * - содержательное другое издание (`Live`, `Acapella`, `Remix`) штрафуется —
     *   это уже другая запись;
     * - канал `- Topic` — обычно загруженный правообладателем оригинал.
     */
    private fun editionTieBreak(
        candidateRawTitle: String,
        entryRawTitle: String,
        candidateArtist: String,
        entryArtist: String,
    ): Int {
        var bonus = 0
        if (isCanonicalEdition(candidateRawTitle) && isCanonicalEdition(entryRawTitle)) {
            if (!hasParenthetical(candidateRawTitle) && hasParenthetical(entryRawTitle)) {
                bonus += CANONICAL_BONUS
            }
        }
        if (hasAlternativeEditionTag(candidateRawTitle) &&
            !hasAlternativeEditionTag(entryRawTitle)
        ) {
            bonus -= ALT_EDITION_PENALTY
        }
        if (candidateArtist != entryArtist && topicBase(candidateArtist) == entryArtist) {
            bonus += TOPIC_CHANNEL_BONUS
        }
        return bonus
    }

    /** Есть ли в названии скобочная пометка вообще. */
    private fun hasParenthetical(value: String): Boolean = ANY_PARENS.containsMatchIn(value)

    /** Пометка есть, но она «канцелярская»: это та же самая запись. */
    private fun isCanonicalEdition(value: String): Boolean {
        val markers = ANY_PARENS.findAll(value).map { it.value }.toList()
        if (markers.isEmpty()) return true
        return markers.all { marker -> ALTERNATIVE_EDITION.containsMatchIn(marker) != true }
    }

    /** Пометка означает другую запись: концерт, ремикс, акапелла и т. п. */
    private fun hasAlternativeEditionTag(value: String): Boolean {
        if (ALTERNATIVE_EDITION.containsMatchIn(value)) return true
        return DASH_VERSION.containsMatchIn(value)
    }

    /** Основа имени канала: `nirvana topic` → `nirvana`. */
    private fun topicBase(normalized: String): String? {
        val stripped = normalized.removeSuffix(" topic").trim()
        return stripped.takeIf { it.isNotEmpty() && it != normalized }
    }

    /**
     * Одно название содержится в другом **по целым словам**: сравнение подстрокой
     * давало ложные срабатывания вроде `ring` внутри `during`.
     */
    private fun related(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        if (shorter == longer) return true
        // Слишком короткое пересечение ничего не доказывает («я», «the»).
        if (shorter.length < 4) return false
        return Regex("""(?:^|\s)${Regex.escape(shorter)}(?:\s|$)""").containsMatchIn(longer)
    }

    /** Часть `artist title` после первого разделителя — «Песня» из «Артист - Песня». */
    private fun tailOf(normalized: String): String? {
        val index = normalized.indexOf(' ')
        if (index <= 0) return null
        val head = normalized.substring(0, index)
        val tail = normalized.substring(index + 1).trim()
        // Отбрасываем только короткий заголовок-исполнитель (1–3 слова),
        // иначе у «a day in the life» хвост потеряет смысл.
        if (tail.isEmpty() || head.length > 30 || head.split(' ').size > 3) return null
        return tail
    }

    /**
     * Границы «слипшегося» регистра: `TheKillersVEVO` → `The Killers VEVO`.
     *
     * Каналы YouTube часто пишутся без пробелов, а `normalize` убирает пунктуацию
     * и приводит к нижнему регистру, но пробелов не расставляет: `TheKillersVEVO`
     * превращался в `thekillersvevo` и не совпадал со словом `the killers` ни при
     * каком сравнении. Из-за этого в выдаче по «The Killers Mr. Brightside» не
     * находилось ни одного кандидата с совпавшим исполнителем, и выбирался кавер.
     *
     * Преобразование применяется к обеим сторонам сравнения, поэтому оно
     * безопасно: даже если граница угадана неверно (`McFly` → `mc fly`),
     * исполнитель из списка проходит через то же самое и совпадение сохраняется.
     */
    private val CAMEL_BOUNDARY = Regex(
        """(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])""",
    )

    /** ё→е и снятие диакритики: `SARÀ PERCHÉ` → `sara perche`. */
    private fun foldDiacritics(value: String): String = buildString(value.length) {
        for (ch in value) {
            val folded = when (ch) {
                'ё' -> 'е'
                'à', 'á', 'â', 'ã', 'ä', 'å' -> 'a'
                'è', 'é', 'ê', 'ë' -> 'e'
                'ì', 'í', 'î', 'ï' -> 'i'
                'ò', 'ó', 'ô', 'õ', 'ö' -> 'o'
                'ù', 'ú', 'û', 'ü' -> 'u'
                'ý', 'ÿ' -> 'y'
                'ñ' -> 'n'
                'ç' -> 'c'
                'ß' -> 's'
                'ў' -> 'у'
                'ї' -> 'и'
                'є' -> 'е'
                'ґ' -> 'г'
                else -> ch
            }
            append(folded)
        }
    }

    /** Типографские тире/кавычки → ASCII, чтобы `–` и `-` не различались. */
    private fun unifyDashesAndQuotes(value: String): String = value
        .replace('–', '-')
        .replace('—', '-')
        .replace('―', '-')
        .replace('−', '-')
        .replace('\u2018', '\'')
        .replace('\u2019', '\'')
        .replace('\u201C', '"')
        .replace('\u201D', '"')
        .replace('«', '"')
        .replace('»', '"')
        .replace('\u00A0', ' ')
}
