package app.sonora.core.display

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.Window
import androidx.annotation.RequiresApi

/**
 * Автоматическая адаптация под частоту экрана 1–120 Гц (LTPO).
 *
 * Принцип: приложение **не фиксирует** частоту. На время касания/скролла и
 * стартовых анимаций просим максимум (не выше 120 Гц), а после жеста
 * возвращаем системную «0» — тогда LTPO сам уходит в минимальную частоту
 * (вплоть до 1 Гц), когда на экране нет движения. Никаких настроек у
 * пользователя: всё определяется возможностями дисплея на лету.
 */
class AdaptiveRefreshRate(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())
    private val displayManager: DisplayManager? =
        activity.getSystemService(DisplayManager::class.java)

    /** Максимум, который реально поддерживает текущий дисплей (ограничен 120 Гц). */
    @Volatile
    private var maxHz: Float = 60f

    /** Идёт жест: держим высокую частоту, пока пользователь взаимодействует. */
    @Volatile
    private var interacting: Boolean = false

    private val idleRunnable = Runnable {
        if (!interacting) applyFrameRate(0f)
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == currentDisplay()?.displayId) detectDisplay()
        }
    }

    fun start() {
        runCatching { displayManager?.registerDisplayListener(displayListener, handler) }
        detectDisplay()
        // Первый кадр и стартовые анимации — на максимальной частоте.
        applyFrameRate(maxHz)
        handler.removeCallbacks(idleRunnable)
        handler.postDelayed(idleRunnable, START_BURST_MS)
    }

    fun stop() {
        interacting = false
        handler.removeCallbacksAndMessages(null)
        runCatching { displayManager?.unregisterDisplayListener(displayListener) }
        applyFrameRate(0f)
    }

    /**
     * Вызывается из Activity.dispatchTouchEvent. Любой жест — это движение на
     * экране, поэтому на время касания включаем максимум; после отпускания
     * через небольшую паузу отдаём частоту системе.
     */
    fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_SCROLL -> {
                interacting = true
                handler.removeCallbacks(idleRunnable)
                applyFrameRate(maxHz)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                interacting = false
                handler.removeCallbacks(idleRunnable)
                handler.postDelayed(idleRunnable, IDLE_AFTER_TOUCH_MS)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplay(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            activity.windowManager.defaultDisplay
        }
    private fun detectDisplay() {
        val display = currentDisplay() ?: return
        val supported = display.supportedModes
            .map { it.refreshRate }
            .filter { it > 0f }
        val hardwareMax = supported.maxOrNull() ?: display.refreshRate
        // 1–120 Гц по требованию: даже на 144-Гц панели не просим больше 120.
        maxHz = hardwareMax.coerceIn(MIN_HZ, MAX_HZ)
        Log.i(
            TAG,
            "display=${display.displayId} supported=${supported.distinct().sorted()} " +
                "max=$hardwareMax -> request up to $maxHz Hz, now=${display.refreshRate} Hz",
        )
    }

    private fun applyFrameRate(hz: Float) {
        runCatching {
            // preferredRefreshRate=0 — «системная частота»: LTPO может опуститься
            // до 1 Гц. На время жеста просим максимум (не выше 120 Гц).
            val attributes = activity.window.attributes
            val target = if (hz <= 0f) 0f else hz.coerceIn(MIN_HZ, MAX_HZ)
            if (attributes.preferredRefreshRate != target) {
                attributes.preferredRefreshRate = target
                activity.window.attributes = attributes
            }
        }
    }

    private companion object {
        const val TAG = "RefreshRate"
        const val MIN_HZ = 24f
        const val MAX_HZ = 120f
        const val START_BURST_MS = 1_200L
        const val IDLE_AFTER_TOUCH_MS = 700L
    }
}
