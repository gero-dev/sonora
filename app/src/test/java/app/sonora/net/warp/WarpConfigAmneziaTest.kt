package app.sonora.net.warp

import org.amnezia.awg.config.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * Проверяет, что параметры обфускации AmneziaWG из импортированного `.conf`
 * не теряются, попадают в сгенерированный текст и принимаются движком AmneziaWG.
 */
class WarpConfigAmneziaTest {

    private val privateKey = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
    private val publicKey = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg="

    private val awgConfigText = """
        [Interface]
        PrivateKey = $privateKey
        Address = 172.16.0.2/32
        DNS = 1.1.1.1
        MTU = 1280
        Jc = 4
        Jmin = 40
        Jmax = 70
        S1 = 0
        S2 = 0
        H1 = 1
        H2 = 2
        H3 = 3
        H4 = 4
        I1 = <b 0x00>

        [Peer]
        PublicKey = $publicKey
        AllowedIPs = 0.0.0.0/0
        Endpoint = engage.cloudflareclient.com:2408
        PersistentKeepalive = 25
    """.trimIndent()

    @Test
    fun awgOptionsSurviveParseAndRoundTrip() {
        val config = WarpConfig.parse(awgConfigText)

        assertEquals(
            listOf("H1", "H2", "H3", "H4", "I1", "JC", "JMAX", "JMIN", "S1", "S2"),
            config.amneziaKeys,
        )
        assertTrue("Jc должен попасть в awgOptions", config.awgOptions.containsKey("JC"))

        val text = config.toWgQuickText(includedApplications = listOf("app.sonora"))
        assertTrue(text.contains("JC = 4"))
        assertTrue(text.contains("JMIN = 40"))
        assertTrue(text.contains("H1 = 1"))
        assertTrue(text.contains("I1 = <b 0x00>"))
        assertTrue(text.contains("IncludedApplications = app.sonora"))
    }

    @Test
    fun awgEngineAcceptsGeneratedConfig() {
        val config = WarpConfig.parse(awgConfigText)
        val text = config.toWgQuickText(includedApplications = listOf("app.sonora"))

        // Парсер движка AmneziaWG должен принять наш текст без исключений.
        val parsed = Config.parse(StringReader(text).buffered())
        assertEquals(1, parsed.peers.size)
        assertEquals(4, parsed.getInterface().junkPacketCount.orElse(-1))
        assertEquals(40, parsed.getInterface().junkPacketMinSize.orElse(-1))
        assertEquals(70, parsed.getInterface().junkPacketMaxSize.orElse(-1))
        assertEquals("1", parsed.getInterface().initPacketMagicHeader.orElse(""))
        assertEquals("<b 0x00>", parsed.getInterface().specialJunkI1.orElse(""))
    }

    @Test
    fun vanillaConfigHasNoAwgOptions() {
        val config = WarpConfig.parse(
            """
            [Interface]
            PrivateKey = $privateKey
            Address = 172.16.0.2/32
            DNS = 1.1.1.1
            MTU = 1280

            [Peer]
            PublicKey = $publicKey
            AllowedIPs = 0.0.0.0/0
            Endpoint = engage.cloudflareclient.com:2408
            """.trimIndent(),
        )

        assertTrue(config.amneziaKeys.isEmpty())
        assertTrue(config.awgOptions.isEmpty())
        val text = config.toWgQuickText()
        assertTrue(!text.contains("Jc ="))
        Config.parse(StringReader(text).buffered())
    }
}
