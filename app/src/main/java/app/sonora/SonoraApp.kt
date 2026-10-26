package app.sonora

import android.app.Application

class SonoraApp : Application() {

    val graph: AppGraph by lazy { AppGraph.get(this) }

    override fun onCreate() {
        super.onCreate()
        // Прогреваем контейнер, чтобы первый кадр не ждал инициализации prefs.
        graph
    }
}
