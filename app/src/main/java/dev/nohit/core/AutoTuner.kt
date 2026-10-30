package dev.nohit.core

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Автоподбор: перебирает DNS-апстримы и стратегии против DPI, измеряет
 * реальный HTTPS-ответ и сохраняет лучшую комбинацию.
 *
 * Запускать в фоновом потоке (сеть + ожидание), например:
 *   Thread { AutoTuner.tune(context) { Log.d("NoHiT", it) } }.start()
 */
object AutoTuner {

    data class Report(
        val ok: Boolean,
        val dns: DnsUpstream,
        val split: SplitPlan,
        val latencyMs: Long,
        val log: List<String>,
        /** Человекочитаемое имя выбранной стратегии, например "sni-mid+tcp2". */
        val strategyName: String = split.label()
    )

    fun tune(
        context: Context,
        base: NoHiTConfig = NoHiT.currentConfig(),
        progress: ((String) -> Unit)? = null
    ): Report {
        val log = ArrayList<String>()
        val probeTimeout = base.timeoutsMs.coerceAtMost(6_000).toLong()

        // Фаза 1: какой DNS вообще отвечает и насколько быстро (без вмешательства в TLS).
        var bestDns = base.dns.firstOrNull() ?: DnsUpstream.SYSTEM
        var bestDnsMs = Long.MAX_VALUE
        for (upstream in base.dns) {
            val client = NoHiT.buildClient(base.copy(dns = listOf(upstream), split = SplitPlan.NONE))
            val ms = measure(client, base, probeTimeout)
            log += "DNS ${upstream.name}: ${ms?.let { "$it ms" } ?: "fail"}"
            progress?.invoke(log.last())
            if (ms != null && ms < bestDnsMs) {
                bestDnsMs = ms
                bestDns = upstream
            }
        }

        // Фаза 2: стратегии. Для скорости — только первый тестовый URL.
        val primaryUrl = base.testUrls.firstOrNull()
        var bestSplit = base.split
        var bestMs = Long.MAX_VALUE
        for (split in listOf(SplitPlan.NONE) + SplitPlan.all) {
            val client = NoHiT.buildClient(base.copy(dns = listOf(bestDns), split = split))
            val ms = if (primaryUrl != null) probe(client, primaryUrl, probeTimeout) else null
            log += "Стратегия ${split.label()}: ${ms?.let { "$it ms" } ?: "fail"}"
            progress?.invoke(log.last())
            if (ms != null && ms < bestMs) {
                bestMs = ms
                bestSplit = split
            }
        }

        // Проверка победителя на всех тестовых URL.
        val winnerClient = NoHiT.buildClient(base.copy(dns = listOf(bestDns), split = bestSplit))
        val verified = measure(winnerClient, base, base.timeoutsMs.toLong())
        val ok = verified != null
        log += "Проверка: ${verified?.let { "ok, $it ms" } ?: "fail"}"

        val updated = base.copy(
            dns = listOf(bestDns) + base.dns.filter { it.id != bestDns.id },
            split = if (ok) bestSplit else base.split
        )
        NoHiT.configure(context, updated)

        return Report(
            ok = ok,
            dns = bestDns,
            split = updated.split,
            latencyMs = verified ?: -1,
            log = log,
            strategyName = updated.split.label()
        )
    }

    /** Быстрая проверка текущей конфигурации без изменения настроек. */
    fun check(context: Context, cfg: NoHiTConfig = NoHiT.currentConfig()): Long? =
        measure(NoHiT.buildClient(cfg), cfg, cfg.timeoutsMs.toLong())

    private fun measure(client: OkHttpClient, cfg: NoHiTConfig, timeoutMs: Long): Long? {
        var best: Long? = null
        for (url in cfg.testUrls) {
            val value = probe(client, url, timeoutMs)
            if (value != null && (best == null || value < best)) best = value
        }
        return best
    }

    private fun probe(client: OkHttpClient, url: String, timeoutMs: Long): Long? {
        val started = System.nanoTime()
        return try {
            val call = client.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
                .newCall(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .get()
                        .build()
                )
            call.execute().use { response ->
                if (response.code in 200..499) {
                    (System.nanoTime() - started) / 1_000_000
                } else {
                    null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) NoHiT/0.2"
}
