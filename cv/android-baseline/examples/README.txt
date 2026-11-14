BridgeIntegrationExample.kt — компилируемый образец вызовов модуля, без UI.
Можно копировать в app/src/main/java/com/arengineering/inspection/cv/.
Callbacks вызываются из GL (pause/close — из вызывающего потока); публикацию
Compose-state/текста перевести на UI thread. Событие HitResult обрабатывается
сразу на GL, createAnchor() принадлежит приложению. Политика overlay по умолчанию
строгая SOURCE_FRAME_ONLY; практические ограничения см. ARCORE_INTEGRATION.txt.

results/*.json — фактические результаты Kotlin/JVM CPU на фиксированных
контрольных изображениях. Это численная проверка runtime, не запись Android
камеры и не дополнительная оценка качества модели. Timings — значения ПК.

pump90: JTN Pump, Corporacion EG, CC BY-SA4.0;
https://commons.wikimedia.org/wiki/File:JTN_Pump.jpg
pump270: Multistage centrifugal pump, Z22, CC BY-SA4.0;
https://commons.wikimedia.org/wiki/File:Multistage_centrifugal_pump.jpg
https://creativecommons.org/licenses/by-sa/4.0/
Изменения для тестовых RGB: уменьшение/поворот, добавление результатов модели.
Полная атрибуция предыдущих учебных источников — evidence/DATA-CREDITS.txt.
rust0: контрольное изображение из ранее предоставленных материалов проекта.
Оригинальная публикация/автор этого изображения не установлены в этом пакете;
тестовый ресурс предназначен для внутренней передачи команде проекта.

src/test/resources/parity содержит ZIP: raw RGB, эталонный CHW, выходы ONNX,
ожидаемые координаты/маски и source/sha в fixture.properties. Эти архивы
нужны для unit-тестов на ПК и не попадают в assets/APK. Внутри main runtime
отсутствуют запись камеры и обращения к этим ресурсам.
evidence/generate_parity_original.py.txt сохраняет исходный NumPy/Python ORT
метод получения эталонов; его пути относятся к рабочему проекту подготовки.
Для запуска готовых JUnit-тестов этот скрипт и Python не требуются.
