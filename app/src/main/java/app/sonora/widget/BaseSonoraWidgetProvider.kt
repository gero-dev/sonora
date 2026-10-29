package app.sonora.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import app.sonora.AppGraph

/**
 * Общая логика всех виджетов Sonora (общий предок для legacy и фиксированных).
 *
 * Конкретная раскладка выбирается в [SonoraWidget] по классу провайдера, поэтому
 * наследнику достаточно объявить себя в манифесте со своим appwidget-provider
 * XML — переопределять колбэки не нужно.
 *
 * Системные колбэки (APPWIDGET_UPDATE / ENABLED / DELETED / OPTIONS_CHANGED)
 * обрабатывает базовый [AppWidgetProvider], поэтому для них вызывается super.
 * Кастомные действия кнопок (PLAY_PAUSE / NEXT / PREVIOUS) перехватываются
 * здесь: после команды виджет перерисовывается принудительно.
 */
open class BaseSonoraWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // Обновляем не только «свои» id: так проще и безопаснее — состояние одно
        // на все виджеты, а SonoraWidget сам разложит его по нужным раскладкам.
        SonoraWidget.updateAll(context)
    }

    /**
     * Пользователь изменил размер (актуально для legacy-виджета): updateAll
     * заново читает options и подбирает раскладку.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        SonoraWidget.updateAll(context)
    }

    /**
     * Кнопки виджета приходят сюда, потому что PendingIntent'ы созданы как
     * explicit broadcast на SonoraWidgetProvider (у него тот же intent-filter).
     *
     * super.onReceive вызываем только для системных действий, чтобы не потерять
     * onEnabled/onDisabled/onDeleted: для кастомных actions базовый класс
     * ничего не делает.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val isWidgetAction = action == SonoraWidget.ACTION_PLAY_PAUSE ||
            action == SonoraWidget.ACTION_NEXT ||
            action == SonoraWidget.ACTION_PREVIOUS

        if (!isWidgetAction) {
            super.onReceive(context, intent)
            return
        }

        runCatching {
            // Запускаем музыку через сервис, а не через AppGraph: виджет должен
            // работать, когда приложение убито. startForegroundService нужен,
            // чтобы Android не убил сервис, поднятый из виджета.
            val serviceIntent = app.sonora.playback.PlaybackService.widgetAction(context, action)
            androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
        }.onFailure { t ->
            Log.w(TAG, "Не удалось обработать действие виджета: $action", t)
        }

        SonoraWidget.updateAll(context)
    }

    private companion object {
        const val TAG = "SonoraWidget"
    }
}
