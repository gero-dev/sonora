package app.sonora.core.display

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/**
 * 120 Гц на ColorOS (OPPO / OnePlus / realme) через Shizuku.
 *
 * ColorOS ограничивает сторонние приложения 90 Гц и слушается только
 * системного peak_refresh_rate. Обычному приложению писать эту настройку
 * нельзя, но Shizuku (запущенный через ADB или root) даёт приложению
 * shell-доступ — через пользовательский сервис выполняем
 * `settings put system peak_refresh_rate 1`.
 *
 * Пока Sonora на переднем плане — 120 Гц; при уходе в фон возвращаем прежнее
 * значение, чтобы не жечь батарею другим приложениям. Если Shizuku не
 * установлен/не запущен, приложение работает как раньше (90 Гц), без ошибок.
 */
object ColorOsHighRefresh {

    private const val TAG = "ColorOsRefresh"
    private const val PREFS = "sonora_coloros"
    private const val KEY_PREV = "prev_peak_refresh_rate"
    private const val KEY_ACTIVE = "boost_active"
    private const val SETTING = "peak_refresh_rate"
    private const val BOOST_VALUE = 1f
    private const val DEFAULT_HZ = 120f

    const val REQUEST_CODE = 4310

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "coloros-refresh").apply { isDaemon = true }
    }

    @Volatile
    private var shell: IBinder? = null
    @Volatile
    private var binding = false
    @Volatile
    private var foreground = false
    @Volatile
    private var userServiceArgs: Shizuku.UserServiceArgs? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            shell = binder
            binding = false
            Log.i(TAG, "Shizuku shell service connected")
            if (foreground) boost() else restore()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shell = null
            binding = false
            Log.w(TAG, "Shizuku shell service disconnected")
        }
    }

    /** Похоже ли устройство на ColorOS/OnePlus. */
    fun isColorOs(): Boolean {
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val display = Build.DISPLAY.lowercase()
        return brand == "oneplus" || brand == "oppo" || brand == "realme" ||
            manufacturer == "oneplus" || manufacturer == "oppo" ||
            manufacturer == "realme" || display.contains("coloros") ||
            display.contains("oxygenos") || display.contains("oplus")
    }

    fun maxHz(context: Context): Float = runCatching {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.view.WindowManager::class.java)?.defaultDisplay
        } ?: return 0f
        display.supportedModes.maxOfOrNull { it.refreshRate } ?: display.refreshRate
    }.getOrDefault(0f)

    fun isSupported(context: Context): Boolean = isColorOs() && maxHz(context) >= 120f

    fun isShizukuInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    }.getOrDefault(false)

    fun isShizukuRunning(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Статус для настроек: что сейчас с 120 Гц на этом устройстве. */
    fun statusLabel(context: Context): String {
        if (!isSupported(context)) return "Не требуется на этом устройстве"
        if (!isShizukuInstalled(context)) return "Нужен Shizuku: установите и запустите"
        if (!isShizukuRunning()) return "Shizuku не запущен"
        if (!hasPermission()) return "Нажмите, чтобы разрешить Shizuku"
        return if (shell != null) "Автоматически: 120 Гц на переднем плане"
        else "Подключаем Shizuku…"
    }

    /** Запросить разрешение Shizuku (вызывает диалог Shizuku). */
    fun requestPermission() {
        runCatching {
            if (!isShizukuRunning()) return
            if (hasPermission()) return
            Shizuku.requestPermission(REQUEST_CODE)
        }.onFailure { Log.w(TAG, "requestPermission: ${it.message}") }
    }

    /** onResume: включить 120 Гц, если Shizuku доступен. */
    fun onForeground(context: Context) {
        foreground = true
        if (!isSupported(context)) return
        if (!isShizukuRunning() || !hasPermission()) return
        ensureBound(context)
        if (shell != null) boost()
    }

    /** onPause: вернуть системное значение. */
    fun onBackground(context: Context) {
        foreground = false
        if (!isSupported(context)) return
        if (shell != null) restore()
    }

    private fun ensureBound(context: Context) {
        if (shell != null || binding) return
        runCatching {
            val app = context.applicationContext
            val args = userServiceArgs ?: Shizuku.UserServiceArgs(
                ComponentName(app, ShellService::class.java),
            )
                .daemon(false)
                .processNameSuffix("sonora_shell")
                .version(1)
                .also { userServiceArgs = it }
            binding = true
            Shizuku.bindUserService(args, connection)
        }.onFailure {
            binding = false
            Log.w(TAG, "bindUserService failed: ${it.message}")
        }
    }

    private fun boost() {
        executor.execute {
            val app = shellContext ?: return@execute
            restoreIfStale(app)
            val current = readPeak(app)
            if (current == BOOST_VALUE) return@execute
            prefs(app).edit()
                .putFloat(KEY_PREV, current)
                .putBoolean(KEY_ACTIVE, true)
                .apply()
            if (exec("settings put system $SETTING 1")) {
                Log.i(TAG, "120 Hz: $SETTING $current -> 1 via Shizuku")
            }
        }
    }

    private fun restore() {
        val app = shellContext ?: return
        executor.execute {
            val prefs = prefs(app)
            if (!prefs.getBoolean(KEY_ACTIVE, false)) return@execute
            val previous = prefs.getFloat(KEY_PREV, DEFAULT_HZ)
            if (exec("settings put system $SETTING ${previous.toInt()}")) {
                Log.i(TAG, "120 Hz: restored $SETTING=$previous via Shizuku")
            }
            prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
        }
    }

    private fun restoreIfStale(context: Context) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return
        val previous = prefs.getFloat(KEY_PREV, DEFAULT_HZ)
        exec("settings put system $SETTING ${previous.toInt()}")
        prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
        Log.i(TAG, "120 Hz: restored stale $SETTING=$previous")
    }

    private fun readPeak(context: Context): Float = runCatching {
        Settings.System.getFloat(context.contentResolver, SETTING, DEFAULT_HZ)
    }.getOrDefault(DEFAULT_HZ)

    private fun exec(command: String): Boolean {
        val s = shell ?: return false
        return runCatching {
            val result = ShellService.exec(s, command)
            val code = result.lineSequence().firstOrNull()?.trim()?.toIntOrNull() ?: 1
            if (code != 0) Log.w(TAG, "sh exit=$code result=${result.take(300)}")
            code == 0
        }.onFailure { Log.w(TAG, "sh failed: ${it.message}") }
            .getOrDefault(false)
    }

    fun addPermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.addRequestPermissionResultListener(listener) }
    }

    fun removePermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Контекст нужен только для prefs; запоминаем при привязке. */
    @Volatile
    private var shellContext: Context? = null
}