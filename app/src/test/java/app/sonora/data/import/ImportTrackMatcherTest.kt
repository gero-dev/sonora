package app.sonora.data.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты сопоставления импортируемых треков. Фикстуры взяты из реальных
 * экспортов (docs/IMPORT.md §6.3) и из настоящих названий InnerTube/локальных
 * файлов, снятых с устройства.
 */
class ImportTrackMatcherTest {

    private fun entry(
        title: String,
        artist: String? = null,
        durationMs: Long? = null,
        videoId: String? = null,
        album: String? = null,
        url: String? = null,
    ) = ImportedTrackEntry(
        title = title,
        artist = artist,
        durationMs = durationMs,
        videoId = videoId,
        album = album,
        url = url,
    )

    private fun candidate(
        id: String,
        title: String,
        artist: String,
        durationMs: Long = 0L,
    ) = MatchCandidate(id = id, title = title, artist = artist, durationMs = durationMs)

    // ------------------------------------------------------------ normalize

    @Test
    fun normalizeStripsDecorationsAndUnifiesPunctuation() {
        assertEquals("smells like teen spirit", ImportTrackMatcher.normalize("Smells Like Teen Spirit"))
        // Украшения каталога снимаются.
        assertEquals(
            "nirvana smells like teen spirit",
            ImportTrackMatcher.normalize("Nirvana - Smells Like Teen Spirit (Official Video)"),
        )
        assertEquals(
            "song",
            ImportTrackMatcher.normalize("Song [Official Audio]"),
        )
        // Типографское тире и кавычки не мешают сравнению с ASCII-вариантом.
        assertEquals(
            ImportTrackMatcher.normalize("Artist - Title"),
            ImportTrackMatcher.normalize("Artist \u2014 Title"),
        )
        assertEquals(
            ImportTrackMatcher.normalize("Don't Stop"),
            ImportTrackMatcher.normalize("Don\u2019t Stop"),
        )
    }

    @Test
    fun normalizeFoldsDiacriticsAndCyrillic() {
        assertEquals("sara perche ti amo", ImportTrackMatcher.normalize("SARÀ PERCHÉ TI AMO"))
        // ё и е считаются одной буквой.
        assertEquals(ImportTrackMatcher.normalize("Всё Что Нас"), ImportTrackMatcher.normalize("Все Что Нас"))
    }

    @Test
    fun normalizeDropsFeatAndTopicAndRemasterSuffixes() {
        assertEquals("burn", ImportTrackMatcher.normalize("Burn (Acoustic Mix) (feat. ROOKIES)"))
        assertEquals("virtual insanity", ImportTrackMatcher.normalize("Virtual Insanity - Remastered 2006"))
        assertEquals("hypnotize", ImportTrackMatcher.normalize("Hypnotize - 2014 Remaster"))
        assertEquals("nirvana", ImportTrackMatcher.normalize("Nirvana - Topic"))
    }

    // ------------------------------------------------------------ scoring

    @Test
    fun exactTitleAlonePasses() {
        // Экспорт без исполнителя, но точное название: 3 балла — приём.
        val result = ImportTrackMatcher.best(
            listOf(candidate("a", "Bohemian Rhapsody", "")),
            entry("Bohemian Rhapsody"),
        )
        assertEquals("a", result?.id)
    }

    @Test
    fun exactTitleAndArtistPassesWithTopScore() {
        val result = ImportTrackMatcher.best(
            listOf(candidate("a", "Voyager", "Xdinary Heroes")),
            entry("Voyager", "Xdinary Heroes"),
        )
        assertEquals("a", result?.id)
        assertEquals(5, ImportTrackMatcher.score(candidate("a", "Voyager", "Xdinary Heroes"), entry("Voyager", "Xdinary Heroes")))
    }

    @Test
    fun partialTitleAloneDoesNotPass() {
        // Иначе «Iron Man» совпал бы с «Bohemian Rhapsody» по общим словам.
        val result = ImportTrackMatcher.best(
            listOf(candidate("a", "Iron Man", "Black Sabbath")),
            entry("Bohemian Rhapsody", "Queen"),
        )
        assertNull(result)
    }

    @Test
    fun wrongSongWithRightArtistDoesNotPass() {
        // Реальный случай: в библиотеке есть только 'Nirvana - Heart-Shaped Box',
        // а ищем 'Smells Like Teen Spirit' — это разные песни, приёма быть не должно.
        val result = ImportTrackMatcher.best(
            listOf(candidate("a", "Nirvana - Heart-Shaped Box", "Nirvana")),
            entry("Smells Like Teen Spirit", "Nirvana", 301_000L),
        )
        assertNull(result)
    }

    @Test
    fun wrongArtistDoesNotOutrankRightArtist() {
        val right = candidate("right", "Billie Jean", "Michael Jackson", 294_000L)
        val wrong = candidate("wrong", "Billie Jean", "Some Cover Band", 294_000L)
        val entry = entry("Billie Jean", "Michael Jackson", 294_000L)

        assertTrue(ImportTrackMatcher.score(right, entry) > ImportTrackMatcher.score(wrong, entry))
        // Порядок в списке не должен решать: даже если «неверный» идёт первым.
        assertEquals("right", ImportTrackMatcher.best(listOf(wrong, right), entry)?.id)
    }

    @Test
    fun durationWithinToleranceAddsBonus() {
        val base = candidate("a", "Voyager", "Xdinary Heroes", 213_000L)
        val withinTolerance = entry("Voyager", "Xdinary Heroes", 215_000L)
        val outsideTolerance = entry("Voyager", "Xdinary Heroes", 300_000L)
        assertEquals(
            ImportTrackMatcher.score(base, withinTolerance) - 1,
            ImportTrackMatcher.score(base, outsideTolerance),
        )
    }

    // ------------------------------------------- реальные формы каталогов

    @Test
    fun topicChannelArtistCountsAsHit() {
        // Локальный файл с YouTube часто подписан каналом «Nirvana - Topic».
        val result = ImportTrackMatcher.best(
            listOf(candidate("a", "Smells Like Teen Spirit", "Nirvana - Topic")),
            entry("Smells Like Teen Spirit", "Nirvana"),
        )
        assertEquals("a", result?.id)
    }

    @Test
    fun decoratedCatalogueTitleMatchesPlainExportTitle() {
        val result = ImportTrackMatcher.best(
            listOf(candidate("a", "Michael Jackson - Billie Jean (Official Video)", "Michael Jackson")),
            entry("Billie Jean", "Michael Jackson", 294_000L),
        )
        assertEquals("a", result?.id)
    }

    @Test
    fun artistPrefixedTitleMatchesEntryTail() {
        // Экспорт: 'Voyager' / 'Xdinary Heroes'; каталог: 'Xdinary Heroes - Voyager'.
        val result = ImportTrackMatcher.best(
            listOf(candidate("a", "Xdinary Heroes - Voyager", "Xdinary Heroes")),
            entry("Voyager", "Xdinary Heroes"),
        )
        assertEquals("a", result?.id)
    }

    @Test
    fun picksCorrectTrackAmongManyCandidates() {
        // Порядок как в реальной выдаче: сначала мусор, затем верный трек.
        val candidates = listOf(
            candidate("g1", "Queen - Another One Bites the Dust", "Queen"),
            candidate("g2", "Bohemian Rhapsody (Live Aid 1985)", "Queen", 300_000L),
            candidate("right", "Bohemian Rhapsody", "Queen", 354_000L),
            candidate("g3", "Bohemian Rhapsody - Remastered 2011", "Queen", 355_000L),
        )
        assertEquals("right", ImportTrackMatcher.best(candidates, entry("Bohemian Rhapsody", "Queen", 354_000L))?.id)
    }

    @Test
    fun emptyCandidatesReturnNull() {
        assertNull(ImportTrackMatcher.best(emptyList(), entry("Anything", "Anyone")))
    }

    @Test
    fun blankTitleNeverMatches() {
        val result = ImportTrackMatcher.best(
            listOf(candidate("a", "   ", "Artist")),
            entry("   ", "Artist"),
        )
        assertNull(result)
    }

    @Test
    fun cyrillicExportMatches() {
        val result = ImportTrackMatcher.best(
            listOf(candidate("a", "Ларёк", "ДДТ", 200_000L)),
            entry("Ларек", "ДДТ", 201_000L),
        )
        assertEquals("a", result?.id)
    }

    @Test
    fun wordBoundariesPreventFalseSubstringHits() {
        // 'ring' не должно совпадать с 'during'.
        val result = ImportTrackMatcher.best(
            listOf(candidate("a", "During", "Someone")),
            entry("Ring", "Someone"),
        )
        assertNull(result)
    }

    // -------------------------------------------------- выбор издания

    /**
     * Реальный дефект, найденный на устройстве: для строки «Adele — Rolling in
     * the Deep» импорт сохранял «Rolling in the Deep (Acapella)». Причина —
     * `normalize` снимает скобки, поэтому акапелла не отличалась от оригинала и
     * побеждала просто потому, что стояла выше в выдаче каталога.
     */
    @Test
    fun canonicalEditionBeatsAlternativeEdition() {
        val candidates = listOf(
            candidate("acapella", "Rolling in the Deep (Acapella)", "Adele"),
            candidate("original", "Rolling in the Deep", "Adele"),
        )
        val result = ImportTrackMatcher.best(candidates, entry("Rolling in the Deep", "Adele"))
        assertEquals("original", result?.id)
    }

    @Test
    fun alternativeEditionNeverWinsEvenWhenListedFirst() {
        // Тот же случай, но акапелла идёт первой: порядок не должен решать.
        val candidates = listOf(
            candidate("live", "Voyager (Live)", "Xdinary Heroes"),
            candidate("studio", "Voyager", "Xdinary Heroes"),
        )
        assertEquals(
            "studio",
            ImportTrackMatcher.best(candidates, entry("Voyager", "Xdinary Heroes"))?.id,
        )
    }

    @Test
    fun alternativeEditionStillMatchesWhenItIsTheOnlyOption() {
        // Если кроме концертной версии ничего нет — лучше она, чем ничего.
        val candidates = listOf(candidate("live", "Voyager (Live)", "Xdinary Heroes"))
        assertEquals(
            "live",
            ImportTrackMatcher.best(candidates, entry("Voyager", "Xdinary Heroes"))?.id,
        )
    }

    @Test
    fun catalogueDecorationsAreNotPenalised() {
        // «(Official Video)» и «- Remastered 2011» — это та же запись.
        val candidates = listOf(
            candidate("plain", "Bohemian Rhapsody", "Queen"),
            candidate("official", "Bohemian Rhapsody (Official Video)", "Queen"),
            candidate("remaster", "Bohemian Rhapsody - Remastered 2011", "Queen"),
        )
        assertEquals(
            "plain",
            ImportTrackMatcher.best(candidates, entry("Bohemian Rhapsody", "Queen"))?.id,
        )
    }

    @Test
    fun canonicalEditionSurvivesWhenEntryIsDecorated() {
        val candidates = listOf(
            candidate("acapella", "Bohemian Rhapsody (Acapella)", "Queen"),
            candidate("plain", "Bohemian Rhapsody", "Queen"),
        )
        val result = ImportTrackMatcher.best(
            candidates,
            entry("Bohemian Rhapsody (Official Video)", "Queen"),
        )
        assertEquals("plain", result?.id)
    }

    @Test
    fun topicChannelBeatsUserUploadOnTie() {
        // Канал правообладателя «- Topic» — обычно именно оригинал.
        val candidates = listOf(
            candidate("user", "Bohemian Rhapsody", "Queen Fan Channel"),
            candidate("topic", "Bohemian Rhapsody", "Queen - Topic"),
        )
        // Оба дают частичное совпадение исполнителя; выигрывать должен Topic.
        val result = ImportTrackMatcher.best(candidates, entry("Bohemian Rhapsody", "Queen"))
        assertEquals("topic", result?.id)
    }

    @Test
    fun candidateIndexAgreesOnEditionChoice() {
        // Быстрый путь обязан повторить выбор издания, иначе импорт будет
        // сохранять одно, а показывать другое.
        val corpora = listOf(
            listOf(
                candidate("acapella", "Rolling in the Deep (Acapella)", "Adele"),
                candidate("original", "Rolling in the Deep", "Adele"),
            ) to entry("Rolling in the Deep", "Adele"),
            listOf(
                candidate("live", "Voyager (Live)", "Xdinary Heroes"),
                candidate("studio", "Voyager", "Xdinary Heroes"),
            ) to entry("Voyager", "Xdinary Heroes"),
            listOf(
                candidate("plain", "Bohemian Rhapsody", "Queen"),
                candidate("official", "Bohemian Rhapsody (Official Video)", "Queen"),
            ) to entry("Bohemian Rhapsody", "Queen"),
            listOf(
                candidate("user", "Bohemian Rhapsody", "Queen Fan Channel"),
                candidate("topic", "Bohemian Rhapsody", "Queen - Topic"),
            ) to entry("Bohemian Rhapsody", "Queen"),
            listOf(
                candidate("live", "Voyager (Live)", "Xdinary Heroes"),
            ) to entry("Voyager", "Xdinary Heroes"),
        )
        for ((candidates, target) in corpora) {
            assertEquals(
                "разошлось на «${target.title}»",
                ImportTrackMatcher.best(candidates, target)?.id,
                ImportTrackMatcher.index(candidates).best(target)?.id,
            )
        }
    }

    @Test
    fun mixSuffixCountsAsAlternativeEdition() {
        // Ещё один случай с устройства: «Come As You Are (Devonshire Mix)» —
        // это бонусная версия, а не альбомная.
        val candidates = listOf(
            candidate("mix", "Come As You Are (Devonshire Mix)", "Nirvana"),
            candidate("album", "Come As You Are", "Nirvana"),
        )
        assertEquals(
            "album",
            ImportTrackMatcher.best(candidates, entry("Come As You Are", "Nirvana"))?.id,
        )
    }

    // ------------------------------------- кавер против настоящего исполнителя

    /**
     * Дефект, найденный на устройстве: для строки «Seven Nation Army — The White
     * Stripes» сохранялся кавер Gaullin, а для «Mr. Brightside» — Matty Hinds.
     *
     * Причина: название совпадало точно, исполнитель — нет, поэтому счёт
     * совпадал у кавера и у оригинала, а `best` брал первого в выдаче каталога.
     */
    @Test
    fun originalArtistBeatsCoverWhenScoresTie() {
        val candidates = listOf(
            candidate("cover", "Seven Nation Army", "Gaullin"),
            candidate("original", "Seven Nation Army", "The White Stripes"),
        ).reversed()   // оригинал ниже в выдаче — порядок не должен решать
        assertEquals(
            "original",
            ImportTrackMatcher.best(candidates, entry("Seven Nation Army", "The White Stripes"))?.id,
        )
    }

    @Test
    fun originalArtistWinsEvenWhenCoverIsListedFirst() {
        val candidates = listOf(
            candidate("cover", "Mr. Brightside", "Matty Hinds"),
            candidate("original", "Mr. Brightside", "The Killers"),
        )
        assertEquals(
            "original",
            ImportTrackMatcher.best(candidates, entry("Mr. Brightside", "The Killers"))?.id,
        )
    }

    /**
     * Реальные экспорты содержат не имя исполнителя, а имя канала:
     * «The Killers - Official Channel», «The White Stripes Topic». Раньше такой
     * исполнитель не совпадал ни с чем, и это и открывало дорогу каверу.
     */
    @Test
    fun channelNoiseIsStrippedBeforeComparingArtists() {
        val candidates = listOf(
            candidate("cover", "Seven Nation Army", "Gaullin"),
            candidate("original", "Seven Nation Army", "The White Stripes"),
        )
        val polluted = entry("Seven Nation Army", "The White Stripes Topic")
        assertEquals("original", ImportTrackMatcher.best(candidates, polluted)?.id)

        val polluted2 = entry("Mr. Brightside", "The Killers - Official Channel")
        val candidates2 = listOf(
            candidate("cover", "Mr. Brightside", "Matty Hinds"),
            candidate("original", "Mr. Brightside", "The Killers"),
        )
        assertEquals("original", ImportTrackMatcher.best(candidates2, polluted2)?.id)
    }

    /**
     * Если исполнитель списка вообще ни на кого не похож, угадывать нечего —
     * порядок каталога сохраняется, и трек всё равно принимается (иначе строка
     * потерялась бы целиком).
     */
    @Test
    fun firstCandidateStillWinsWhenNoArtistAgrees() {
        val candidates = listOf(
            candidate("first", "Some Song", "Nobody At All"),
            candidate("second", "Some Song", "Someone Else"),
        )
        assertEquals("first", ImportTrackMatcher.best(candidates, entry("Some Song", "Zzz Qqq"))?.id)
    }

    /** Более высокий балл по-прежнему важнее совпадения исполнителя. */
    @Test
    fun higherScoreStillBeatsArtistAgreement() {
        val candidates = listOf(
            candidate("exact", "Voyager", "Xdinary Heroes"),
            candidate("miss", "Totally Different Song", "Xdinary Heroes"),
        )
        assertEquals(
            "exact",
            ImportTrackMatcher.best(candidates, entry("Voyager", "Xdinary Heroes"))?.id,
        )
    }

    @Test
    fun candidateIndexAgreesOnArtistPreference() {
        val corpora = listOf(
            listOf(
                candidate("cover", "Seven Nation Army", "Gaullin"),
                candidate("original", "Seven Nation Army", "The White Stripes"),
            ) to entry("Seven Nation Army", "The White Stripes Topic"),
            listOf(
                candidate("cover", "Mr. Brightside", "Matty Hinds"),
                candidate("original", "Mr. Brightside", "The Killers"),
            ) to entry("Mr. Brightside", "The Killers - Official Channel"),
            listOf(
                candidate("first", "Some Song", "Nobody"),
                candidate("second", "Some Song", "Someone Else"),
            ) to entry("Some Song", "Zzz Qqq"),
        )
        for ((candidates, target) in corpora) {
            assertEquals(
                "разошлось на «${target.title}»",
                ImportTrackMatcher.best(candidates, target)?.id,
                ImportTrackMatcher.index(candidates).best(target)?.id,
            )
        }
    }

    // ------------------------------------------- artistAgrees (для локальных попаданий)

    /**
     * Вызывающий использует это, чтобы отличить надёжное локальное попадание от
     * закрепившегося неверного. `null` при отсутствии исполнителя — не то же
     * самое, что `false`: без данных судить нельзя.
     */
    @Test
    fun artistAgreesDetectsStickyWrongMatch() {
        val savedCover = candidate("saved", "Mr. Brightside", "Matty Hinds")
        assertEquals(false, ImportTrackMatcher.artistAgrees(savedCover, entry("Mr. Brightside", "The Killers")))

        val correct = candidate("right", "Mr. Brightside", "The Killers")
        assertEquals(true, ImportTrackMatcher.artistAgrees(correct, entry("Mr. Brightside", "The Killers")))
    }

    @Test
    fun artistAgreesReturnsNullWhenArtistMissing() {
        // The candidate() helper takes a non-nullable artist, so an absent artist is
        // expressed as "" here. normalizeArtist("") is "" as well, which is exactly the
        // state the production code sees for a track with no artist metadata.
        val noArtist = candidate("x", "Some Song", "")
        assertEquals(null, ImportTrackMatcher.artistAgrees(noArtist, entry("Some Song", "Someone")))

        val noEntryArtist = candidate("y", "Some Song", "Someone")
        assertEquals(null, ImportTrackMatcher.artistAgrees(noEntryArtist, entry("Some Song", null)))
    }

    @Test
    fun artistAgreesToleratesChannelNoise() {
        val channel = candidate("topic", "Mr. Brightside", "The Killers - Topic")
        assertEquals(true, ImportTrackMatcher.artistAgrees(channel, entry("Mr. Brightside", "The Killers")))

        val official = candidate("off", "Seven Nation Army", "The White Stripes - Official Channel")
        assertEquals(true, ImportTrackMatcher.artistAgrees(official, entry("Seven Nation Army", "The White Stripes")))
    }

    /**
     * Каналы YouTube часто без пробелов: `TheKillersVEVO`. `normalize` убирает
     * пунктуацию и регистр, но пробелов не расставляет, поэтому такой
     * исполнитель не совпадал со словом `the killers` ни при каком сравнении —
     * и в выдаче по «The Killers Mr. Brightside» не находилось ни одного
     * кандидата с совпавшим исполнителем.
     */
    @Test
    fun camelCaseChannelNamesAgreeWithSpacedArtists() {
        val vevo = candidate("v", "Mr. Brightside", "TheKillersVEVO")
        assertEquals(true, ImportTrackMatcher.artistAgrees(vevo, entry("Mr. Brightside", "The Killers")))

        val noSpace = candidate("n", "Take Me Out", "FranzFerdinand")
        assertEquals(true, ImportTrackMatcher.artistAgrees(noSpace, entry("Take Me Out", "Franz Ferdinand")))
    }

    /** Слипшийся регистр не должен ЛОМАТЬ уже работавшие совпадения. */
    @Test
    fun camelCaseSplittingStillMatchesPlainNames() {
        val plain = candidate("p", "Voyager", "Xdinary Heroes")
        assertEquals(true, ImportTrackMatcher.artistAgrees(plain, entry("Voyager", "Xdinary Heroes")))

        // Обе стороны проходят через одно и то же преобразование, поэтому
        // «McFly» против «McFly» остаётся совпадением.
        val mcfly = candidate("m", "Five Colours in Her Hair", "McFly")
        assertEquals(true, ImportTrackMatcher.artistAgrees(mcfly, entry("Five Colours in Her Hair", "McFly")))
    }

    /** И по-прежнему отличает настоящий кавер от оригинала. */
    @Test
    fun camelCaseSplittingDoesNotMakeCoversAgree() {
        val cover = candidate("c", "Mr. Brightside", "MattyHinds")
        assertEquals(false, ImportTrackMatcher.artistAgrees(cover, entry("Mr. Brightside", "The Killers")))
    }

    // ------------------------------------------------- пакетный индекс

    /**
     * Индекс обязан повторять [ImportTrackMatcher.best] один в один — включая
     * выбор первого кандидата при равном балле и порог MIN_SCORE. Если это
     * разойдётся, импорт начнёт находить другие треки, чем поиск по одному.
     */
    @Test
    fun candidateIndexMatchesScalarBestOnCorpus() {
        val corpus = candidateIndexCorpus()
        assertTrue("корпус должен быть представительным", corpus.size >= 40)

        var compared = 0
        for ((candidates, entry) in corpus) {
            val scalar = ImportTrackMatcher.best(candidates, entry)
            val indexed = ImportTrackMatcher.index(candidates).best(entry)
            assertEquals(
                "разошлось на «${entry.title}» / «${entry.artist}»",
                scalar?.id,
                indexed?.id,
            )
            compared++
        }
        assertEquals(corpus.size, compared)
    }

    /** Индекс не должен зависеть от повторных вызовов: нормализация кэшируется. */
    @Test
    fun candidateIndexIsReusableAcrossEntries() {
        val candidates = listOf(
            candidate("a", "Billie Jean", "Michael Jackson", 294_000L),
            candidate("b", "Voyager", "Xdinary Heroes", 213_000L),
            candidate("c", "Ларёк", "ДДТ", 200_000L),
        )
        val index = ImportTrackMatcher.index(candidates)
        assertEquals(3, index.size)
        assertEquals("a", index.best(entry("Billie Jean", "Michael Jackson", 294_000L))?.id)
        assertEquals("b", index.best(entry("Voyager", "Xdinary Heroes"))?.id)
        assertEquals("c", index.best(entry("Ларек", "ДДТ", 201_000L))?.id)
        // Порядок вызовов не важен — результат тот же.
        assertEquals("a", index.best(entry("Billie Jean", "Michael Jackson", 294_000L))?.id)
    }

    @Test
    fun candidateIndexPreservesEarliestWinsOnTie() {
        // Два одинаковых кандидата: победить должен первый, как и в best().
        val candidates = listOf(
            candidate("first", "Bohemian Rhapsody", "Queen"),
            candidate("second", "Bohemian Rhapsody", "Queen"),
        )
        val target = entry("Bohemian Rhapsody", "Queen")
        assertEquals("first", ImportTrackMatcher.best(candidates, target)?.id)
        assertEquals("first", ImportTrackMatcher.index(candidates).best(target)?.id)
    }

    @Test
    fun candidateIndexRespectsMinimumScore() {
        val candidates = listOf(candidate("a", "Iron Man", "Black Sabbath"))
        val target = entry("Bohemian Rhapsody", "Queen")
        assertNull(ImportTrackMatcher.best(candidates, target))
        assertNull(ImportTrackMatcher.index(candidates).best(target))
    }

    @Test
    fun candidateIndexHandlesEmptyInput() {
        assertNull(ImportTrackMatcher.index(emptyList()).best(entry("Anything", "Anyone")))
        assertEquals(0, ImportTrackMatcher.index(emptyList()).size)
    }

    /**
     * Корпус из реальных форм: точные совпадения, частичные, промахи ниже
     * порога, каналы `- Topic`, бонусы за длительность, украшения и кириллица.
     */
    private fun candidateIndexCorpus(): List<Pair<List<MatchCandidate>, ImportedTrackEntry>> {
        val queens = listOf(
            candidate("q1", "Queen - Another One Bites the Dust", "Queen"),
            candidate("q2", "Bohemian Rhapsody (Live Aid 1985)", "Queen", 300_000L),
            candidate("q3", "Bohemian Rhapsody", "Queen", 354_000L),
            candidate("q4", "Bohemian Rhapsody - Remastered 2011", "Queen", 355_000L),
        )
        val nirvana = listOf(
            candidate("n1", "Nirvana - Heart-Shaped Box", "Nirvana"),
            candidate("n2", "Smells Like Teen Spirit", "Nirvana - Topic"),
            candidate("n3", "Come as You Are", "Nirvana"),
        )
        val mixed = listOf(
            candidate("m1", "Michael Jackson - Billie Jean (Official Video)", "Michael Jackson"),
            candidate("m2", "Billie Jean", "Michael Jackson", 294_000L),
            candidate("m3", "Beat It", "Michael Jackson", 258_000L),
        )
        val cyrillic = listOf(
            candidate("c1", "Ларёк", "ДДТ", 200_000L),
            candidate("c2", "Что такое осень", "ДДТ", 320_000L),
            candidate("c3", "Всё Что Нас", "Сплин"),
        )
        val accented = listOf(
            candidate("a1", "SARÀ PERCHÉ TI AMO", "Ricchi e Poveri", 190_000L),
            candidate("a2", "Sara Perche Ti Amo", "Ricchi e Poveri", 191_000L),
        )
        val tiny = listOf(candidate("t1", "Ring", "Someone"))
        val blank = listOf(candidate("b1", "   ", "Artist"))

        return listOf(
            // Точные и почти точные совпадения.
            queens to entry("Bohemian Rhapsody", "Queen", 354_000L),
            queens to entry("Bohemian Rhapsody", "Queen"),
            queens to entry("Another One Bites the Dust", "Queen"),
            nirvana to entry("Smells Like Teen Spirit", "Nirvana"),
            nirvana to entry("Smells Like Teen Spirit", "Nirvana", 301_000L),
            nirvana to entry("Come as You Are", "Nirvana"),
            nirvana to entry("Heart-Shaped Box", "Nirvana"),
            mixed to entry("Billie Jean", "Michael Jackson", 294_000L),
            mixed to entry("Beat It", "Michael Jackson"),
            // Промахи: не должны приниматься.
            queens to entry("Bohemian Rhapsody", "Someone Else"),
            nirvana to entry("In Bloom", "Nirvana"),
            mixed to entry("Thriller", "Michael Jackson"),
            tiny to entry("During", "Someone"),
            tiny to entry("Ring", "Someone"),
            // Пустые и вырожденные.
            blank to entry("   ", "Artist"),
            emptyList<MatchCandidate>() to entry("Anything", "Anyone"),
            queens to entry("Bohemian Rhapsody"),
            queens to entry("Bohemian Rhapsody", null),
            nirvana to entry("Smells Like Teen Spirit", "Nirvana - Topic"),
            // Кириллица и диакритика.
            cyrillic to entry("Ларек", "ДДТ", 201_000L),
            cyrillic to entry("Ларёк", "ДДТ"),
            cyrillic to entry("Что такое осень", "ДДТ", 321_000L),
            cyrillic to entry("Все Что Нас", "Сплин"),
            cyrillic to entry("Все Что Нас", "Сплин", 200_000L),
            accented to entry("Sara Perche Ti Amo", "Ricchi e Poveri"),
            accented to entry("SARÀ PERCHÉ TI AMO", "Ricchi e Poveri", 190_000L),
            // Украшения каталога и feat.-хвосты.
            mixed to entry("Billie Jean (Official Video)", "Michael Jackson"),
            queens to entry("Bohemian Rhapsody - Remastered 2011", "Queen", 355_000L),
            mixed to entry("Beat It (feat. Eddie Van Halen)", "Michael Jackson"),
            // Равные баллы: первый в списке обязан победить.
            listOf(
                candidate("tie1", "Voyager", "Xdinary Heroes", 213_000L),
                candidate("tie2", "Voyager", "Xdinary Heroes", 213_000L),
            ) to entry("Voyager", "Xdinary Heroes", 213_000L),
            listOf(
                candidate("tie3", "Ларёк", "ДДТ"),
                candidate("tie4", "Ларёк", "ДДТ"),
            ) to entry("Ларек", "ДДТ"),
            // Длительность решает при равном названии и исполнителе.
            listOf(
                candidate("d1", "Voyager", "Xdinary Heroes", 100_000L),
                candidate("d2", "Voyager", "Xdinary Heroes", 213_000L),
            ) to entry("Voyager", "Xdinary Heroes", 213_000L),
            // Типографские тире и кавычки.
            listOf(candidate("p1", "Artist — Title", "Band")) to entry("Artist - Title", "Band"),
            listOf(candidate("p2", "Don\u2019t Stop", "Band")) to entry("Don't Stop", "Band"),
            // Название с исполнителем внутри строки и хвостом.
            listOf(candidate("s1", "Xdinary Heroes - Voyager", "Xdinary Heroes")) to
                entry("Voyager", "Xdinary Heroes"),
            listOf(candidate("s2", "Nirvana - Smells Like Teen Spirit", "Nirvana")) to
                entry("Smells Like Teen Spirit", "Nirvana"),
            // Очень длинный список-мусор с одним верным треком в конце.
            (List(30) { i -> candidate("noise$i", "Noise Track $i", "Various Artists") } +
                candidate("needle", "Bohemian Rhapsody", "Queen", 354_000L)) to
                entry("Bohemian Rhapsody", "Queen", 354_000L),
            // Только исполнитель совпал, название — нет.
            listOf(candidate("w1", "Some Other Song", "Queen")) to entry("Bohemian Rhapsody", "Queen"),
            // Пустой исполнитель в кандидате.
            listOf(candidate("e1", "Bohemian Rhapsody", "")) to entry("Bohemian Rhapsody"),
            listOf(candidate("e2", "Bohemian Rhapsody", "")) to entry("Bohemian Rhapsody", "Queen"),
        )
    }
}
