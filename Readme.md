# AR Engineering

Android-приложение для осмотра оборудования. Камера ARCore показывает ориентиры, сервер FastAPI хранит паспорта, показания и отчёты в SQLite. Для работы с базой в браузере есть панель управления.

- [Настройка сервера, панели и локальной модели](server/README.md)
- [Требования к сборке Android](REQUIREMENTS.md)

Откройте репозиторий в Android Studio и запустите конфигурацию `app` на смартфоне с ARCore. Для сборки из PowerShell используйте:

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

APK появится в `app/build/outputs/apk/debug/app-debug.apk`. В серверном режиме введите локальный адрес ноутбука и войдите под учётной записью из базы. Сохранённый отчёт можно выгрузить в PDF.
