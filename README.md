# WJ 2.7 Diagnostic — Android

Нативный Kotlin / Jetpack Compose клиент для **Jeep Grand Cherokee WJ/WG 2.7 CRD (2002–2004)**. Минимум Android 8.0 (API 26); тестовое устройство — Honor 8X / Android 8.1. Не нужны Google Play Services и платные библиотеки.

## Состояние

Это первая тестовая сборка для реального обмена на Honor 8X. Есть Bluetooth Classic SPP и BLE GATT, `ATI`, ELM-консоль, RAW лог, проверенные K-Line и J1850 последовательности из [референсного проекта](https://github.com/takyonxxx/Jeep_Grand_Cherokee_WJ_2.7_CRD_OBD-II_Diagnostic/). Реализованы ECM/TCM подключение, чтение и подтверждаемое стирание DTC, базовые Live Data; J1850 поиск блоков и DTC PID scan для ABS, Airbag и BCM. График, избранное и CSV доступны для полученных значений. Исполнительные тесты, прошивка, кодирование и запись EEPROM не включены. Стирание DTC J1850, полноценное переподключение и несколько других функций ТЗ пока не завершены. Сравнение с WJdiag на этом Honor 8X пока не проводилось.

Код протоколов и карты полей адаптированы из GPL-3.0 проекта; данная работа также GPL-3.0, см. `LICENSE`.

## Получить APK

На вкладке **Actions** откройте успешный запуск **Build Android APK** и скачайте артефакт `WJ-Diagnostic-Android`. `WJ-Diagnostic.apk` — устанавливаемый debug APK для первого теста. `WJ-Diagnostic-debug.apk` — тот же файл. `WJ-Diagnostic-release-unsigned.apk` оптимизирован, но **не устанавливается до подписи**; он не предназначен для пользователя. APK не требует платного аккаунта и может быть установлен вручную без Play Store.

На Honor 8X откройте скачанный APK и разрешите установку из этого источника, когда Android спросит. Сопрягите AutoHelfer в настройках Bluetooth, если используете Classic. Для BLE дайте приложению разрешение на поиск Bluetooth и доступ к местоположению: Android 8.1 требует его для BLE scan, координаты не собираются. После подключения отправьте `ATI` в консоли, затем проверьте ECM, DTC и Live Data. Не используйте неизвестные команды записи.

## Структура

- `BluetoothTransport.kt`: RFCOMM SPP и BLE GATT, выбор характеристик.
- `Elm327.kt`: последовательная очередь, prompt `>`, таймауты, RAW лог.
- `JeepDiagnostic.kt`: проверенные команды K-Line/J1850, блоки, DTC и Live Data.
- `DiagnosticController.kt`: состояние диагностики и жизненный цикл.
- `MainActivity.kt`: русскоязычный интерфейс Compose.
- `DiagnosticService.kt`: foreground уведомление при диагностике.

## Сборка

Откройте проект в Android Studio или выполните `gradle :app:assembleDebug` с JDK 17, Android SDK 36 и Gradle 8.13. GitHub Actions повторяет эту сборку автоматически. Данные автомобиля никуда не отправляются.
