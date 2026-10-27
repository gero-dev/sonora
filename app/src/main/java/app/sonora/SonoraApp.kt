package app.sonora

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class SonoraApp : Application(), ImageLoaderFactory {

    val graph: AppGraph by lazy { AppGraph.get(this) }

    override fun onCreate() {
        super.onCreate()
        // Прогреваем контейнер, чтобы первый кадр не ждал инициализации prefs.
        graph
    }

    /**
     * Один общий Coil-загрузчик: диск на 3 % кэша приложения и память на 20 %.
     * Это заметно ускоряет прокрутку обложек и переживает перезапуск без сети.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(120)
        .respectCacheHeaders(false)
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("artwork_cache"))
                .maxSizePercent(0.03)
                .build()
        }
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.2)
                .build()
        }
        .build()
}
