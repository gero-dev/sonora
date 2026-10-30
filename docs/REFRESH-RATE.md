# Частота экрана и ColorOS

## Что делает Sonora

`AdaptiveRefreshRate` определяет поддерживаемые частоты дисплея и:

- на старте/касании/скролле просит максимум (до 120 Гц);
- через 700 мс после жеста снимает запрос, чтобы LTPO мог опуститься до 1 Гц;
- реагирует на смену режима дисплея.

На Pixel/stock Android это даёт динамические 1–120 Гц.

## ColorOS / OnePlus: системное ограничение 90 Гц

Проверено на OnePlus PKG110 (ColorOS 16, Android 16, панель 120 Гц):

- `Display.getSupportedModes()`: `60, 90, 120`;
- при запросе приложением 120 Гц ColorOS всё равно даёт
  `mDesiredDisplayModeSpecs ... render: (0.0 90.0)` и `renderFrameRate 90.0`;
- `setAppRequest requestedRefreshRate: 120.0 ... requestedMaxRefreshRateRange: 90.0` —
  система принудительно режет сторонние приложения до 90 Гц.

Перепробовано и **не помогает** на ColorOS:

- `WindowManager.LayoutParams.preferredRefreshRate = 120`;
- `preferredDisplayModeId` режима 120 Гц;
- `View.setRequestedFrameRate(120)` (API 35);
- `SurfaceControl.Transaction.setFrameRate(...)` через reflection;
- `android:isGame="true"`, targetSdk 35;
- запись `peak_refresh_rate` приложением с разрешением `WRITE_SETTINGS`:
  ColorOS SettingsProvider отвечает
  `You cannot keep your settings in the secure settings.`

## Как включить 120 Гц на ColorOS

ColorOS слушается только системного `peak_refresh_rate`, запись требует
привилегий (Shizuku/root/ADB). Два рабочих способа:

1. **Настройки экрана ColorOS** → «Частота обновления» → выбрать
   «Высокая/120 Гц» или «Принудительно 120 Гц» (название зависит от версии).
2. **ADB с компьютера** (одноразово, действует до перезагрузки/сброса):

   `adb shell settings put system peak_refresh_rate 1`

   Вернуть автообратно:

   `adb shell settings put system peak_refresh_rate 120`

После этого Sonora и другие приложения смогут работать на 120 Гц; наш
адаптивный контроллер не мешает системе и по-прежнему снимает запрос после
жеста (на ColorOS LTPO при `peak=1` обычно держит высокую частоту).

## Почему приложение не делает это само

Google не даёт обычным приложениям писать системные настройки дисплея.
`WRITE_SETTINGS` позволяет менять обычные настройки, но `peak_refresh_rate`
на ColorOS отнесён к защищённым. Без root/Shizuku/ADB обойти это нельзя —
это ограничение прошивки, а не Sonora.
