package dev.nohit.core

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Автоподбор: перебирает DNS-апстримы и планы фрагментации, измеряет
 * реальный HTTPS-ответ и сохраняет лучшую комбинацию.
 *
 * Запускать в фоновом потоке (сеть + sleep), например:
 *   Thread { AutoTuner.tune(context) { Log.d("NoHiT", it) } }.start()
 */
object AutoTuner {

    data class Report(
        val ok: Boolean,
        val dns: DnsUpstream,
        val split: SplitPlan,
        val latencyMs: Long,
        val log: List<String>
    )

    fun tune(
        context: Context,
        base: NoHiTConfig = NoHiT.currentConfig(),
        progress: ((String) -> Unit)? = null
    ): Report {
        val log = ArrayList<String>()

        var bestDns = base.dns.firstOrNull() ?: DnsUpstream.SYSTEM
        var bestDnsMs = Long.MAX_VALUE

        for (upstream in base.dns) {
            val probeClient = NoHiT.buildClient(
                base.copy(dns = listOf(upstream), split = SplitPlan.NONE)
            )
            val ms = measure(probeClient, base)
            log += "DNS ${upstream.name}: ${if (ms == null) "fail" else "$ms ms"}"
            progress?.invoke(log.last())
            if (ms != null && ms < bestDnsMs) {
                bestDnsMs = ms
                bestDns = upstream
            }
        }

        var bestSplit = base.split
        var bestSplitMs = Long.MAX_VALUE
        for (split in listOf(SplitPlan.NONE) + SplitPlan.all) {
            val probeClient = NoHiT.buildClient(
                base.copy(dns = listOf(bestDns), split = split)
            )
            val ms = measure(probeClient, base)
            log += "Split ${describe(split)}: ${if (ms == null) "fail" else "$ms ms"}"
            progress?.invoke(log.last())
            if (ms != null && ms < bestSplitMs) {
                bestSplitMs = ms
                bestSplit = split
            }
        }

        val ok = bestSplitMs != Long.MAX_VALUE
        val updated = base.copy(
            dns = listOf(bestDns) + base.dns.filter { it.id != bestDns.id },
            split = if (ok) bestSplit else base.split
        )
        NoHiT.configure(context, updated)

        return Report(
            ok = ok,
            dns = bestDns,
            split = updated.split,
            latencyMs = if (ok) bestSplitMs else -1,
            log = log
        )
    }

    /** Быстрая проверка текущей конфигурации без изменения настроек. */
    fun check(context: Context, cfg: NoHiTConfig = NoHiT.currentConfig()): Long? =
        measure(NoHiT.buildClient(cfg), cfg)

    private fun measure(client: OkHttpClient, cfg: NoHiTConfig): Long? {
        var best: Long? = null
        for (url in cfg.testUrls) {
            val value = probe(client, url, cfg.timeoutsMs.toLong())
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

    private fun describe(split: SplitPlan): String {
        if (split.chunks.isEmpty()) return "none"
        val base = split.chunks.joinToString("-")
        return if (split.delayMs > 0) "$base+${split.delayMs}ms" else base
    }

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) NoHiT/0.1"
}
