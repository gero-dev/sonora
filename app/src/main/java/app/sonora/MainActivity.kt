package app.sonora

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import app.sonora.ui.MainScreen
import app.sonora.core.display.AdaptiveRefreshRate
import app.sonora.core.display.ColorOsHighRefresh
import app.sonora.ui.theme.SonoraTheme
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private lateinit var graph: AppGraph
    private lateinit var refreshRate: AdaptiveRefreshRate
    private var askedShizuku = false

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == ColorOsHighRefresh.REQUEST_CODE &&
            grantResult == PackageManager.PERMISSION_GRANTED
        ) {
            ColorOsHighRefresh.onForeground(this)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        graph = (application as SonoraApp).graph
        refreshRate = AdaptiveRefreshRate(this)
        ColorOsHighRefresh.addPermissionListener(shizukuListener)

        handleDeepLink(intent)
        requestStartupPermissions()

        setContent {
            val settings by graph.settings.state.collectAsState()
            SonoraTheme(settings = settings) {
                MainScreen(graph = graph)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRate.start()
    }

    override fun onPause() {
        refreshRate.stop()
        super.onPause()
    }

    override fun onDestroy() {
        ColorOsHighRefresh.removePermissionListener(shizukuListener)
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        refreshRate.onTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * ColorOS ограничивает сторонние приложения 90 Гц. Если установлен и
     * запущен Shizuku, просим разрешение (один диалог) и дальше включаем
     * 120 Гц автоматически на переднем плане; без Shizuku — тихо работаем на 90.
     */
    private fun enableColorOs120IfPossible() {
        if (!ColorOsHighRefresh.isSupported(this)) return
        if (!ColorOsHighRefresh.isShizukuRunning()) return
        if (ColorOsHighRefresh.hasPermission()) {
            ColorOsHighRefresh.onForeground(this)
        } else if (!askedShizuku) {
            askedShizuku = true
            ColorOsHighRefresh.requestPermission()
        }
    }
    /** sonora://oauth/<service>?code=... — возврат из браузера после входа. */
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "sonora") return
        lifecycleScope.launch {
            val name = graph.handleOAuth(data)
            val message = if (name != null) "$name подключён" else "Не удалось подключить аккаунт"
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestStartupPermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
