# Сборка Android

- Android Studio с поддержкой Android Gradle Plugin 9.2.1 и JDK 17 или новее. Можно использовать встроенный JDK Android Studio.
- Android SDK Platform 36 и Build Tools 36.0.0. Для установки с компьютера нужен Android SDK Platform-Tools.
- В проекте есть Gradle Wrapper 9.4.1. Для первой сборки нужен доступ к Google Maven, Maven Central, Gradle Plugin Portal и Gradle Distributions.
- Телефон должен поддерживать ARCore, иметь Android 7.0 (API 24) или новее, Google Play Services для AR, OpenGL ES 2.0 и разрешение на камеру. Для первой установки или обновления сервисов AR может потребоваться интернет. [Список совместимых устройств](https://developers.google.com/ar/devices)

При сборке из терминала укажите `JAVA_HOME` и `ANDROID_HOME`, если они ещё не настроены. Из корня проекта выполните:

```powershell
.\gradlew.bat :cv:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

На Linux и macOS используйте `./gradlew`. APK появится в `app/build/outputs/apk/debug/app-debug.apk`.

Обычная сборка включает модели насоса и коррозии, а также экспериментальные ориентиры для ручных проверок. Описание источников и ограничений хранится в [MODEL_CARD.txt](cv/android-baseline/MODEL_CARD.txt). Сборка с локальными весами из `Примеры/.cv-local/android-assets` требует параметр `-PcvLocalPilot=true` и доступна на компьютере с этими весами.
