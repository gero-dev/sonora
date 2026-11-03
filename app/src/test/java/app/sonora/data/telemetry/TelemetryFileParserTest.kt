package app.sonora.data.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор файлов с данными о треках.
 *
 * Проверяется главным образом совместимость с форматом импорта списков: тот же
 * JSON, та же таблица, те же хвосты. Отдельно — что неверное число не попадает в
 * данные молча (зажимать его нельзя) и что строка без чисел не теряется.
 */
class TelemetryFileParserTest {

    private fun parse(name: String, text: String) = TelemetryFileParser.parse(name, text)

    @Test
    fun `flat json array with all nine features`() {
        val data = parse(
            "features.json",
            """
            [
              {
                "title": "Midnight City",
                "artist": "M83",
                "album": "Hurry Up",
                "duration_ms": 243000,
                "tempo": 104.5,
                "energy": 0.82,
                "danceability": 0.63,
                "valence": 0.44,
                "acousticness": 0.02,
                "instrumentalness": 0.11,
                "liveness": 0.19,
                "speechiness": 0.05,
                "loudness": 0.71
              }
            ]
            """.trimIndent(),
        )

        assertEquals(1, data.entries.size)
        val entry = data.entries.first()
        assertEquals("Midnight City", entry.title)
        assertEquals("M83", entry.artist)
        assertEquals("Hurry Up", entry.album)
        assertEquals(243000L, entry.durationMs)
        assertEquals("9 из 9 должны быть прочитаны", 1.0, entry.feature!!.confidence, 0.0001)
        assertEquals(104.5, entry.feature!!.tempo!!, 0.0001)
        assertEquals(0.82, entry.feature!!.energy!!, 0.0001)
        assertEquals(0.71, entry.feature!!.loudness!!, 0.0001)
    }

    @Test
    fun `nested audio_features object is read`() {
        val data = parse(
            "nested.json",
            """
            {"tracks":[{"title":"Teardrop","artist":"Massive Attack",
             "audio_features":{"tempo":78.0,"energy":0.31,"valence":0.12}}]}
            """.trimIndent(),
        )

        assertEquals(1, data.entries.size)
        val feature = data.entries.first().feature!!
        assertEquals(78.0, feature.tempo!!, 0.0001)
        assertEquals(0.31, feature.energy!!, 0.0001)
        assertEquals(0.12, feature.valence!!, 0.0001)
        assertNull("Остальных полей в файле не было", feature.danceability)
    }

    @Test
    fun `camelCase audioFeatures container is read`() {
        val data = parse(
            "camel.json",
            """[{"trackName":"Xtal","artistName":"Aphex Twin","audioFeatures":{"bpm":120.0}}]""",
        )
        assertEquals(120.0, data.entries.first().feature!!.tempo!!, 0.0001)
    }

    @Test
    fun `bpm alias maps to tempo`() {
        val data = parse("bpm.json", """[{"title":"Song","artist":"A","bpm":128}]""")
        assertEquals(128.0, data.entries.first().feature!!.tempo!!, 0.0001)
    }

    @Test
    fun `string numbers are accepted`() {
        val data = parse(
            "strings.json",
            """[{"title":"Song","artist":"A","energy":"0.42","tempo":"99.5"}]""",
        )
        val feature = data.entries.first().feature!!
        assertEquals(0.42, feature.energy!!, 0.0001)
        assertEquals(99.5, feature.tempo!!, 0.0001)
    }

    @Test
    fun `out of range energy is dropped with a warning not clamped`() {
        val data = parse("bad.json", """[{"title":"Song","artist":"A","energy":5.0,"valence":0.5}]""")

        val feature = data.entries.first().feature!!
        assertNull("5.0 — это ошибка разбора, а не 1.0: зажимать нельзя", feature.energy)
        assertEquals("Соседнее поле должно остаться", 0.5, feature.valence!!, 0.0001)
        assertTrue(
            "О пропущенном значении нужно сказать пользователю: ${data.warnings}",
            data.warnings.any { it.contains("energy") },
        )
    }

    @Test
    fun `tempo boundaries`() {
        val ok = parse("t.json", """[{"title":"A","artist":"B","tempo":200}]""")
        assertEquals(200.0, ok.entries.first().feature!!.tempo!!, 0.0001)

        val tooFast = parse("t2.json", """[{"title":"A","artist":"B","tempo":5000}]""")
        assertNull(tooFast.entries.first().feature?.tempo)
        assertTrue(tooFast.warnings.any { it.contains("tempo") })

        val zero = parse("t3.json", """[{"title":"A","artist":"B","tempo":0}]""")
        assertEquals("Ноль — допустимый темп", 0.0, zero.entries.first().feature!!.tempo!!, 0.0001)
    }

    @Test
    fun `row with title but no features is kept`() {
        val data = parse("identity.json", """[{"title":"Only A Name","artist":"Someone"}]""")

        assertEquals("Опознание трека терять нельзя", 1, data.entries.size)
        val entry = data.entries.first()
        assertEquals("Only A Name", entry.title)
        assertNull(entry.feature)
    }

    @Test
    fun `csv with header`() {
        val data = parse(
            "features.csv",
            """
            title,artist,album,duration,tempo,energy
            Yellow,Coldplay,Parachutes,266,173.5,0.66
            """.trimIndent(),
        )

        assertEquals(1, data.entries.size)
        val entry = data.entries.first()
        assertEquals("Yellow", entry.title)
        assertEquals("Coldplay", entry.artist)
        assertEquals(266000L, entry.durationMs)
        val feature = entry.feature!!
        assertEquals(173.5, feature.tempo!!, 0.0001)
        assertEquals(0.66, feature.energy!!, 0.0001)
    }

    @Test
    fun `csv positional without header`() {
        val data = parse(
            "plain.csv",
            """
            Yellow,Coldplay,Parachutes,266
            Trouble,Coldplay,Parachutes,213
            """.trimIndent(),
        )

        assertEquals(2, data.entries.size)
        assertEquals("Yellow", data.entries[0].title)
        assertEquals("Coldplay", data.entries[1].artist)
        assertNull("Без шапки столбцов с числами нет", data.entries[0].feature)
    }

    @Test
    fun `tsv with feature columns`() {
        val data = parse(
            "features.tsv",
            "title\tartist\tenergy\tvalence\nSong\tArtist\t0.9\t0.1",
        )
        val feature = data.entries.first().feature!!
        assertEquals(0.9, feature.energy!!, 0.0001)
        assertEquals(0.1, feature.valence!!, 0.0001)
    }

    @Test
    fun `duplicate title and artist rows collapse`() {
        val data = parse(
            "dupes.json",
            """
            [{"title":"Song","artist":"Artist","energy":0.1},
             {"title":"song","artist":"artist","energy":0.2}]
            """.trimIndent(),
        )
        assertEquals(1, data.entries.size)
    }

    @Test
    fun `blank title rows are skipped`() {
        val data = parse(
            "blank.json",
            """[{"title":"","artist":"A","energy":0.5},{"title":"  ","artist":"B"},{"title":"Real","artist":"C"}]""",
        )
        assertEquals(1, data.entries.size)
        assertEquals("Real", data.entries.first().title)
    }

    @Test
    fun `spotify track wrapper is unwrapped`() {
        val data = parse(
            "spotify.json",
            """[{"added_at":"2020-01-01","track":{"name":"Instant Crush","artists":[{"name":"Daft Punk"}],"duration_ms":337000}}]""",
        )

        assertEquals(1, data.entries.size)
        assertEquals("Instant Crush", data.entries.first().title)
        assertEquals("Daft Punk", data.entries.first().artist)
        assertEquals(337000L, data.entries.first().durationMs)
    }

    @Test
    fun `playlist container is not mistaken for a track`() {
        val data = parse(
            "container.json",
            """[{"type":"playlist","name":"My Playlist","tracks":{"items":[]}}]""",
        )
        assertTrue("Плейлист — не трек: ${data.entries}", data.entries.isEmpty())
    }

    @Test
    fun `spotify-style external_urls and resourceId`() {
        val data = parse(
            "spotify2.json",
            """[{"trackName":"Song","artistName":"A","external_urls":{"spotify":"https://open.spotify.com/track/abc"}}]""",
        )
        assertEquals("https://open.spotify.com/track/abc", data.entries.first().url)
    }

    @Test
    fun `youtube json with snippet`() {
        val data = parse(
            "yt.json",
            """[{"snippet":{"title":"Video","channelTitle":"Channel","resourceId":{"videoId":"dQw4w9WgXcQ"}}}]""",
        )

        assertEquals("Video", data.entries.first().title)
        assertEquals("Channel", data.entries.first().artist)
        assertEquals("dQw4w9WgXcQ", data.entries.first().videoId)
    }

    @Test
    fun `txt list yields identity without features`() {
        val data = parse("list.txt", "Coldplay - Yellow\nM83 – Midnight City\n# comment")

        assertEquals(2, data.entries.size)
        assertEquals("Coldplay", data.entries[0].artist)
        assertEquals("Yellow", data.entries[0].title)
        assertNull(data.entries[0].feature)
    }

    @Test
    fun `empty input is handled`() {
        assertTrue(parse("empty.json", "").entries.isEmpty())
        assertTrue(parse("blank.txt", "   \n  ").entries.isEmpty())
    }

    @Test
    fun `malformed json does not throw`() {
        val data = parse("broken.json", "{ this is not json ]")
        assertTrue(data.entries.isEmpty())
        assertNotNull("О сломанном файле надо сообщить", data.warnings)
    }

    @Test
    fun `seconds duration in csv is converted to millis`() {
        val data = parse("dur.csv", "title,artist,duration\nSong,A,225")
        assertEquals(225_000L, data.entries.first().durationMs)
    }

    @Test
    fun `clock duration is parsed`() {
        val data = parse("clock.csv", "title,artist,duration\nSong,A,3:45")
        assertEquals(225_000L, data.entries.first().durationMs)
    }

    @Test
    fun `feature is null when all numbers are out of range`() {
        val data = parse(
            "allbad.json",
            """[{"title":"Song","artist":"A","energy":9.0,"valence":-3.0}]""",
        )
        assertNull("Ни одного верного числа — фичи нет", data.entries.first().feature)
        assertEquals(2, data.warnings.size)
    }

    @Test
    fun `entry count is capped`() {
        val rows = (1..25_000).joinToString(",") { """{"title":"T$it","artist":"A","energy":0.5}""" }
        val data = parse("many.json", "[$rows]")
        assertEquals(TelemetryFileParser.MAX_ENTRIES, data.entries.size)
    }

    @Test
    fun `partial features produce partial confidence`() {
        val data = parse(
            "partial.json",
            """[{"title":"Song","artist":"A","tempo":100.0,"energy":0.5,"valence":0.5}]""",
        )
        val feature = data.entries.first().feature!!
        assertEquals("3 из 9", 3.0 / 9.0, feature.confidence, 0.0001)
    }

    /**
     * Особый случай, который легко пропустить: `Double.toDoubleOrNull` успешно
     * разбирает строки «NaN» и «Infinity», а `"1e400"` переполняется в
     * бесконечность. Все три обязаны быть отклонены, а не попасть в данные.
     *
     * Отсекает их проверка диапазона: сравнения с NaN всегда ложны, поэтому
     * `takeIf { it in 0.0..1.0 }` для NaN даёт null. Тест закрепляет это
     * поведение, чтобы замена проверки на `coerceIn` его не сломала.
     */
    @Test
    fun `NaN and Infinity are rejected`() {
        val data = parse(
            "nan.json",
            """[{"title":"Song","artist":"A","energy":"NaN","valence":"Infinity","tempo":"1e400"}]""",
        )

        val entry = data.entries.first()
        assertNull("NaN не должен попасть в энергию", entry.feature?.energy)
        assertNull("Infinity не должен попасть в valence", entry.feature?.valence)
        assertNull("Переполнение 1e400 не должно попасть в tempo", entry.feature?.tempo)
        assertTrue(
            "О каждом отклонённом значении нужно предупредить: ${data.warnings}",
            data.warnings.size >= 3,
        )
    }

    @Test
    fun `negative values are rejected`() {
        val data = parse("neg.json", """[{"title":"Song","artist":"A","energy":-0.5,"tempo":-10}]""")
        val feature = data.entries.first().feature
        assertNull("Отрицательная энергия невозможна", feature?.energy)
        assertNull("Отрицательный темп невозможен", feature?.tempo)
    }

    /**
     * Европейская запись дробей через запятую. В CSV это неразрешимо (запятая —
     * разделитель столбцов), но в JSON строка `"0,42"` встречается, и молча
     * превращать её в 0 нельзя: это испортило бы данные. Значение отклоняется.
     */
    @Test
    fun `comma decimal is rejected rather than silently zeroed`() {
        val data = parse("eu.json", """[{"title":"Song","artist":"A","energy":"0,42"}]""")
        val feature = data.entries.first().feature
        assertTrue(
            "Запятая не должна давать ноль: ${feature?.energy}",
            feature?.energy == null,
        )
    }

    /**
     * Круговой цикл на настоящем файле выгрузки: текст ниже снят с устройства
     * после нажатия «Выгрузить данные о треках». Проверка важна потому, что
     * выгрузку и разбор пишут разные места кода, и разойтись они могут молча —
     * файл сохранится, а обратно уже не прочитается.
     */
    @Test
    fun `real exported file is read back`() {
        val exported = """
            [{"title":"Hotel California (2013 Remaster)","artist":"Eagles","album":"To the Limit: The Essential Collection","duration_ms":391376,"video_id":"BciS5krYL80","audio_features":{"tempo":107.5,"energy":0.41,"danceability":0.51,"valence":0.31}},{"title":"Losing My Religion","artist":"remhq","album":"In Time: The Best of R.E.M. 1988-2003","duration_ms":268520,"video_id":"japBNRP47-8","audio_features":{"tempo":128.5,"energy":0.44,"danceability":0.54,"valence":0.34}}]
        """.trimIndent()

        val data = parse("sonora-tracks.json", exported)

        assertEquals(2, data.entries.size)
        val first = data.entries.first()
        assertEquals("Hotel California (2013 Remaster)", first.title)
        assertEquals("Eagles", first.artist)
        assertEquals("To the Limit: The Essential Collection", first.album)
        assertEquals(391376L, first.durationMs)
        assertEquals("BciS5krYL80", first.videoId)
        val feature = first.feature!!
        assertEquals(107.5, feature.tempo!!, 0.0001)
        assertEquals(0.41, feature.energy!!, 0.0001)
        assertEquals(0.31, feature.valence!!, 0.0001)
        assertTrue("Выгрузка не должна вызывать замечаний: ${data.warnings}", data.warnings.isEmpty())
        assertEquals("Второй трек тоже на месте", "japBNRP47-8", data.entries[1].videoId)
    }
}
