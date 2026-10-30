package dev.nohit.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build

/**
 * Подписка на смену сети через `android.net.ConnectivityManager` напрямую
 * (проект без AndroidX).
 *
 * При появлении/потере сети вызывается [onNetworkChanged] — фасад чистит
 * DNS-кэш [NohitDns] и таблицу решений [HostPolicy]. Сам конфиг не трогается.
 *
 * Требуется `android.permission.ACCESS_NETWORK_STATE` (объявлено в манифесте
 * библиотеки). Если разрешения нет или сервис недоступен, [start] вернёт `false`
 * и ничего не сломает — кэш просто продолжит жить свои 60 секунд.
 */
internal class NetworkChangeWatcher(
    context: Context,
    private val onNetworkChanged: () -> Unit
) {

    private val manager: ConnectivityManager? = runCatching {
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }.getOrNull()

    @Volatile
    private var started = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            notifyChanged()
        }

        override fun onLost(network: Network) {
            notifyChanged()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            // Смена «валидности» сети (например, captive portal) — тоже повод
            // переспросить DNS, но только когда сеть реально пригодна к работе.
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                notifyChanged()
            }
        }
    }

    /** @return `true`, если подписка оформлена. Повторный вызов ничего не делает. */
    fun start(): Boolean {
        if (started) return true
        val manager = manager ?: return false
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // API 24+: «сеть по умолчанию», уведомляет и о переключении Wi-Fi ↔ LTE.
                manager.registerDefaultNetworkCallback(callback)
            } else {
                // API 21–23: fallback на явный NetworkRequest с тем же набором событий.
                manager.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    callback
                )
            }
            true
        } catch (_: Throwable) {
            // SecurityException (нет ACCESS_NETWORK_STATE) / IllegalArgumentException
            // (превышен лимит колбэков) — деградируем молча, feature не критична.
            false
        }
        started = ok
        return ok
    }

    /** Отписывается от событий; безопасно вызывать несколько раз. */
    fun stop() {
        if (!started) return
        started = false
        try {
            manager?.unregisterNetworkCallback(callback)
        } catch (_: Throwable) {
            // уже отписан или менеджера нет — не важно
        }
    }

    private fun notifyChanged() {
        try {
            onNetworkChanged()
        } catch (_: Throwable) {
            // обработчик не должен ронять колбэк системного биндера
        }
    }
}
