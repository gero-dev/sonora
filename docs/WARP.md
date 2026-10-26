# WARP-туннель в Sonora

Встроенный WireGuard-туннель к Cloudflare WARP. Он нужен для одного практического
сценария: когда `music.youtube.com` / API YouTube недоступны с текущего провайдера,
пользователь может включить в Sonora собственные WARP-конфиги, и **только трафик
Sonora** пойдёт через туннель. Это не «обход блокировок в коде стриминга» и не
подмена плеера: обычный per-app VPN, меняющий сетевой маршрут процесса.

Версия 0.9.2+: поддерживается **до 8 конфигов**, автоперебор (failover) при
неудачном подключении и проверка уже активного внешнего VPN.

## Что делает

- Импортирует один или несколько `.conf` WARP (формат `wg-quick`) через SAF.
- Хранит до `WarpTunnel.MAX_CONFIGS = 8` конфигов; каждый текст — без приватного
  ключа, ключи — в `SecureStore` (EncryptedSharedPreferences).
- Позволяет выбрать активный конфиг, удалять конфиги, видеть список в UI.
- Поднимает туннель официальной библиотекой
  `com.wireguard.android:tunnel:1.0.20230706` (userspace `wireguard-go`).
- При включённом автопереборе (по умолчанию) пробует выбранный конфиг, затем
  остальные, пока не получит handshake.
- Показывает foreground-уведомление и статистику (rx/tx, последний handshake).
- Туннелирует **только пакеты Sonora**: в конфиг добавляется
  `IncludedApplications = app.sonora`, и WireGuard-бэкенд вызывает
  `VpnService.Builder.addAllowedApplication("app.sonora")`.

## Что НЕ делает

- Не трогает `innertube/`, `playback/`, InnerTube, PoToken и логику стриминга.
- Не маршрутизирует весь телефон и не является системным VPN.
- Не реализует собственный криптографический протокол — используется официальная
  библиотека WireGuard (Apache-2.0).
- Не хранит и не передаёт приватные ключи куда-либо, кроме локального хранилища.
- Не запускается автоматически при загрузке устройства.
- Не поддерживает AmneziaWG-обфускацию: параметры `S1/S2/Jc/Jmin/Jmax/H1–H4/I1`
  из `.conf` распознаются, но игнорируются (стоковый WireGuard их не понимает).
  Если провайдер режет именно обычный WireGuard, WARP может не подключиться —
  это ограничение библиотеки, а не приложения.
- Не «воюет» с чужим VPN: если на устройстве уже активен внешний VPN/WARP,
  UI может проверить это через `externalVpnActive()` и не запускать свой туннель.

## Хранение и приватность

| Что | Где |
|---|---|
| Конфиг N без ключа | `filesDir/warp/configs/<id>.conf` (первая строка `# name: …`) |
| Приватный ключ конфига N | `SecureStore`, ключ `warp.key.<id>` |
| Порядок, имена, выбранный id | `filesDir/warp/index.json` |
| Автоперебор (bool) | SharedPreferences `warp_settings`, ключ `auto_rotate` |
| Старый одиночный конфиг | `filesDir/warp/warp.conf` + `warp.private_key` — миграция автоматически |

- На диске **никогда** нет строки `PrivateKey`; ключ пишется только в
  `EncryptedSharedPreferences`.
- `WarpConfig.toString()` скрывает ключ, чтобы он не попал в логи.
- `android:allowBackup="false"` уже стоит в манифесте: файлы и prefs не уходят в
  облачный бэкап.
- В туннель попадает только трафик Sonora; остальные приложения идут напрямую.
- Как и любой VPN, WARP видит метаданные и объём трафика (свойство сервиса
  Cloudflare, а не Sonora).

## Автоперебор (failover)

Алгоритм в `WarpVpnService.runRotation()`:

1. UI вызывает `warp.start()`. Сервис читает список конфигов.
2. Порядок попыток: **выбранный первым**, затем остальные в порядке импорта.
   Если `autoRotate = false` — только выбранный (одна попытка).
3. Всего попыток не больше числа конфигов (`WarpStatus.totalAttempts`).
4. Для каждой попытки:
   - конфиг читается из файла, ключ подмешивается из `SecureStore`;
   - библиотека поднимает туннель (`GoBackend.setState(UP, config)`);
   - сервис ждёт первый handshake **12 с** (`WarpTunnel.HANDSHAKE_TIMEOUT_MS`).
     WireGuard инициирует handshake первым пакетом, поэтому сервис периодически
     шлёт probe-соединение `1.1.1.1:80` — пакет идёт уже через поднятый туннель;
   - если handshake получен (`latestHandshakeEpochMillis > 0`) — успех,
     состояние `CONNECTED`, перебор прекращается;
   - если `setState` бросил ошибку или handshake не пришёл — туннель гасится
     (`setState(DOWN)`), пауза 400 мс, следующая попытка.
5. Если кончились все попытки — `WarpState.ERROR`, `error` =
   `"Ни один WARP-конфиг не подключился"`; в уведомлении — детали последней
   ошибки. Подробности каждой неудачи пишутся в logcat (`WarpVpnService`).
6. Перебор останавливается по ручному `stop()`, `clearConfig()` или из-за
   `onDestroy`; корутина отменяется, туннель гасится.

Прогресс виден в `WarpStatus`:

```kotlin
data class WarpStatus(
    val state: WarpState,                 // CONNECTING → CONNECTED / ERROR
    val error: String?,                   // финальная ошибка
    val endpoint: String?,                // endpoint текущей/успешной попытки
    val connectedSinceMs: Long?,
    val rxBytes: Long, val txBytes: Long,
    val latestHandshakeMs: Long?,
    val attempt: Int,                     // номер текущей попытки
    val totalAttempts: Int,               // сколько запланировано
    val triedConfigIds: List<String>,     // id попыток по порядку
    val autoRotate: Boolean,              // текущий режим
)
```

## Внешний VPN

- `externalVpnActive(): Boolean` — на устройстве есть сеть с
  `TRANSPORT_VPN`, и это **не** наш туннель (если состояние Sonora
  `CONNECTING/CONNECTED/DISCONNECTING`, возвращается false).
- `externalVpnName(): String?` — best-effort имя tun-интерфейса внешнего VPN
  (например, `"tun0"`); может быть null.
- `startIfNoExternalVpn(): Result<Unit>` — если внешний VPN активен, туннель
  Sonora **не** запускается и разрешение VPN **не** запрашивается; возвращается
  failure с текстом:
  `"На устройстве уже активен VPN/WARP — туннель Sonora не включён."`.
  Иначе — обычный `start()`.

Android разрешает только один активный VPN; если чужой VPN уже включён,
`Builder`/`GoBackend` всё равно вернут `TUN_CREATION_ERROR`, но лучше не доводить
до этого и показать пользователю понятный диалог.

## Публичный API `WarpTunnel`

```kotlin
class WarpTunnel(context: Context, secure: SecureStore) {
    constructor(context: Context)

    val status: StateFlow<WarpStatus>

    // конфиги
    fun hasConfig(): Boolean
    fun configs(): List<WarpConfigEntry>
    fun configSummary(): WarpConfigSummary?
    suspend fun importConfig(uri: Uri): Result<WarpConfigSummary>
    suspend fun importConfigs(uris: List<Uri>): List<Result<WarpConfigSummary>>
    fun selectConfig(id: String): Boolean          // если подключено — перезапуск
    fun removeConfig(id: String)                  // выбранный удалён — перевыбор
    fun clearConfig()                             // удалить все конфиги/ключи

    // автоперебор
    fun setAutoRotate(enabled: Boolean)
    fun isAutoRotate(): Boolean

    // внешний VPN
    fun externalVpnActive(): Boolean
    fun externalVpnName(): String?
    fun startIfNoExternalVpn(): Result<Unit>

    // запуск/стоп (как раньше)
    fun vpnPermissionIntent(): Intent?            // null — разрешение уже есть
    fun hasVpnPermission(): Boolean
    fun start(): Result<Unit>
    fun stop()

    companion object {
        const val MAX_CONFIGS = 8
        const val HANDSHAKE_TIMEOUT_MS = 12_000L
    }
}

data class WarpConfigEntry(
    val id: String, val name: String, val endpoint: String?,
    val importedAtMs: Long, val selected: Boolean,
)
```

`WarpVpnService` — деталь реализации; UI работает только через `WarpTunnel`.
Настройки (`auto_rotate`) живут в SharedPreferences `warp_settings`.

## Как пользоваться (UI делает Lead)

1. **Импорт одного**: SAF `OpenDocument` (`*/*` или `application/octet-stream`) →
   `warp.importConfig(uri)`.
2. **Импорт нескольких**: `OpenMultipleDocuments` → `warp.importConfigs(uris)`;
   в списке показать результат по каждому (последний успешный становится выбранным).
3. **Список конфигов**: `warp.configs()` — id, имя (из имени файла), endpoint,
   дата импорта, `selected`; выбранный — radio/галочка.
4. **Выбрать**: `warp.selectConfig(id)`; если туннель был подключён, сервис
   перезапустится на новом выбранном (с автоперебором).
5. **Удалить**: `warp.removeConfig(id)`; если удалён выбранный — выберется
   следующий или состояние станет `NOT_CONFIGURED`.
6. **Автоперебор**: Switch → `warp.setAutoRotate(enabled)`, начальное значение —
   `warp.isAutoRotate()`; в статусе есть `autoRotate`.
7. **Включить**:
   - если `warp.externalVpnActive()` → показать сообщение (можно `externalVpnName()`)
     и не запускать;
   - иначе, если `warp.vpnPermissionIntent() != null` — показать системный диалог
     (`ActivityResultLauncher<Intent>`), после `RESULT_OK` снова вызвать
     `startIfNoExternalVpn()`;
   - иначе сразу `warp.startIfNoExternalVpn()` (он проверяет внешний VPN и
     делегирует в `start()`).
8. **Выключить**: `warp.stop()` (или кнопка «Отключить» в уведомлении).
9. **Прогресс**: `warpStatus.attempt/totalAttempts/triedConfigIds/autoRotate/
   endpoint/error`; при `ERROR` показать `error` и что перебор исчерпан.

## Ограничения Android

- На устройстве может быть активен только один VPN. Если включён другой VPN,
  `startIfNoExternalVpn()` вернёт failure; `start()` без проверки упрётся в
  `TUN_CREATION_ERROR` после перебора всех конфигов.
- Разрешение VPN выдаётся системным диалогом; без него `start()` не работает.
- Foreground-сервис типа `specialUse` требует `targetSdk 34`-совместимого
  объявления (уже в манифесте).
- После перезагрузки/убийства процесса туннель не восстанавливается сам.
- `.conf` с нестандартными маршрутами/DNS зависит от сети; приложение не
  переписывает маршруты пользователя.
- Probe `1.1.1.1:80` нужен только для рукопожатия; если он заблокирован, но
  handshake от WARP всё равно случится, попытка всё равно считается успешной.
- `GoBackend` при одном пире и `0.0.0.0/0` включает «kill-switch»-семантику для
  Sonora, но это не системный kill switch для всех приложений.

## Ошибки и что они значат

| Текст | Причина |
|---|---|
| «На устройстве уже активен VPN/WARP…» | включён чужой VPN; `startIfNoExternalVpn()` |
| «Ни один WARP-конфиг не подключился» | все попытки исчерпаны; детали — в уведомлении/logcat |
| «Нужно разрешение Android на VPN» | не показан/не подтверждён системный диалог |
| `TUN_CREATION_ERROR` | уже активен другой VPN или система отказала |
| `DNS_RESOLUTION_FAILURE` | не разрешается endpoint конфига |
| «WireGuard отклонил конфигурацию» | битый `.conf` (ключи/AllowedIPs/Endpoint) |
| «Файл больше 128 КБ» | выбран не конфиг |
| «Достигнут лимит 8 WARP-конфигов» | удалите лишний конфиг |

## Почему это легально

Пользователь использует **свои** WARP-конфиги для доступа к публичному каталогу
YouTube. Sonora не обходит DRM; сохранение доступного потока идёт только в
приватную папку приложения. Меняется только маршрут собственного сетевого трафика. Библиотека WireGuard распространяется по
Apache-2.0; Cloudflare WARP — официальный сервис Cloudflare, условия его
использования пользователь принимает сам.
